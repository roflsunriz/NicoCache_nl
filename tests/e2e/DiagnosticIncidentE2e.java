package e2e;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Fault-injection E2E for the resident diagnostics process. */
final class DiagnosticIncidentE2e {
    private static final Duration INCIDENT_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration PROCESS_STOP_TIMEOUT = Duration.ofSeconds(10);

    private final Path application;
    private final Path data;
    private final long diagnosticsPid;

    DiagnosticIncidentE2e(Path application, Path data, long diagnosticsPid) {
        this.application = application;
        this.data = data;
        this.diagnosticsPid = diagnosticsPid;
    }

    void run() throws Exception {
        Path statusFile = data.resolve("data/nicocache-control.properties");
        Properties originalStatus = readProperties(statusFile);
        long corePid = Long.parseLong(originalStatus.getProperty("pid", "-1"));
        ProcessHandle core = ProcessHandle.of(corePid).orElseThrow(() ->
                new AssertionError("core process is unavailable: " + corePid));
        assertTrue(core.isAlive(), "core must be alive before fault injection");
        String commandLine = core.info().commandLine().orElse("");
        assertTrue(commandLine.isEmpty()
                        || commandLine.contains("NicoCache_nl.jar"),
                "refusing to fault an unexpected process: " + commandLine);
        assertDiagnosticsAlive("before fault injection");

        writeSensitiveDiagnosticFixture();
        Thread.sleep(2500L);
        assertEquals(0, incidentReports().size(),
                "healthy startup must not create an incident report");

        Properties unreachableControl = new Properties();
        unreachableControl.putAll(originalStatus);
        unreachableControl.setProperty("port", Integer.toString(freePort()));
        try {
            writePropertiesAtomically(statusFile, unreachableControl);
            verifyReport(waitForIncidentCount(1),
                    "control-heartbeat-lost", corePid, true);
        } finally {
            if (core.isAlive()) {
                writePropertiesAtomically(statusFile, originalStatus);
            }
        }

        // Three healthy polls release the per-incident latch.
        Thread.sleep(7000L);
        assertTrue(core.isAlive(),
                "heartbeat fault injection must not stop or restart the core");
        assertDiagnosticsAlive("after automatic collection");

        core.destroyForcibly();
        core.onExit().get(PROCESS_STOP_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
        verifyReport(waitForIncidentCount(2), "process-exited", corePid,
                false);

        Thread.sleep(2500L);
        long afterCrashPid = Long.parseLong(readProperties(statusFile)
                .getProperty("pid", "-1"));
        assertEquals(corePid, afterCrashPid,
                "watchdog must not replace the crashed core process");
        assertFalse(ProcessHandle.of(afterCrashPid)
                        .map(ProcessHandle::isAlive).orElse(false),
                "watchdog must not automatically restart the core");
        assertDiagnosticsAlive("after a core crash");
    }

    private void writeSensitiveDiagnosticFixture() throws IOException {
        Files.writeString(application.resolve("debug.log"), String.join("\n",
                "Authorization: Bearer e2e-bearer-secret",
                "Cookie: user_session=e2e-cookie-secret",
                "mail=e2e-user@example.invalid ip=198.51.100.42",
                "path=" + data,
                "sm12345678 E2E診断テスト動画タイトル",
                ""), StandardCharsets.UTF_8);
    }

    private void verifyReport(Path report, String reason, long corePid,
            boolean requireThreadDumps) throws IOException {
        String html = Files.readString(report, StandardCharsets.UTF_8);
        assertContains(html, reason, "incident reason");
        assertContains(html, Long.toString(corePid), "incident core PID");
        assertContains(html, "ハートビート履歴", "heartbeat history");
        assertContains(html, "実行環境", "runtime environment");
        assertContains(html, "ファイルと拡張構成", "file inventory");
        assertContains(html, "設定（匿名化済み）", "configuration");
        assertContains(html, "直近ログ（匿名化済み・容量制限あり）",
                "bounded logs");
        assertContains(html, "sm12345678", "video ID must be retained");
        assertContains(html, "E2E診断テスト動画タイトル",
                "video title must be retained");
        assertContains(html, "&lt;APP_ROOT&gt;", "application root redaction");
        assertContains(html, "&lt;DATA_ROOT&gt;", "data root redaction");
        assertNotContains(html, "e2e-config-secret",
                "configuration secret must be omitted");
        assertNotContains(html, "e2e-bearer-secret",
                "authorization secret must be omitted");
        assertNotContains(html, "e2e-cookie-secret",
                "cookie secret must be omitted");
        assertNotContains(html, "e2e-user@example.invalid",
                "email address must be omitted");
        assertNotContains(html, "198.51.100.42",
                "external IP address must be omitted");
        assertNotContains(html, application.toString(),
                "raw application path must be omitted");
        assertNotContains(html, data.toString(),
                "raw data path must be omitted");
        assertNotContains(html, "<script", "report must not contain scripts");
        if (requireThreadDumps) {
            assertContains(html, "Snapshot 1", "first thread dump");
            assertContains(html, "Snapshot 2", "second thread dump");
            assertContains(html, "Snapshot 3", "third thread dump");
        }
    }

    private Path waitForIncidentCount(int expected) throws Exception {
        long deadline = System.nanoTime() + INCIDENT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            List<Path> reports = incidentReports();
            if (reports.size() >= expected) {
                return reports.get(expected - 1);
            }
            assertDiagnosticsAlive("while collecting an incident");
            Thread.sleep(100L);
        }
        throw new AssertionError("incident report timed out: expected="
                + expected + ", actual=" + incidentReports().size());
    }

    private List<Path> incidentReports() throws IOException {
        Path root = data.resolve("diagnostics/incidents");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.walk(root)) {
            return files.filter(path -> Files.isRegularFile(path)
                            && "report.html".equals(
                                    path.getFileName().toString()))
                    .sorted().collect(Collectors.toList());
        }
    }

    private void assertDiagnosticsAlive(String phase) {
        assertTrue(diagnosticsPid > 0L && ProcessHandle.of(diagnosticsPid)
                        .map(ProcessHandle::isAlive).orElse(false),
                "diagnostics must remain alive " + phase);
    }

    private static Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void writePropertiesAtomically(Path destination,
            Properties properties) throws IOException {
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".e2e.tmp");
        try (var output = Files.newOutputStream(temporary)) {
            properties.store(output, "E2E fault injection");
        }
        try {
            Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(
                0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void assertContains(
            String actual, String expected, String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + ": expected=" + expected);
        }
    }

    private static void assertNotContains(
            String actual, String unexpected, String message) {
        if (actual != null && actual.contains(unexpected)) {
            throw new AssertionError(
                    message + ": unexpected=" + unexpected);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual,
            String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }
}
