package dareka;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import dareka.common.Logger;

/** Keeps the diagnostics watchdog coupled to the core process lifecycle. */
final class DiagnosticsLifecycle {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(20);
    private static final Object LOCK = new Object();
    private static volatile boolean stopping;
    private static volatile boolean supervisorStarted;

    private DiagnosticsLifecycle() {
    }

    static void start() throws IOException {
        synchronized (LOCK) {
            stopping = false;
            ensureRunning();
            if (!supervisorStarted) {
                Thread supervisor = new Thread(
                        DiagnosticsLifecycle::supervise,
                        "nicocache-diagnostics-supervisor");
                supervisor.setDaemon(true);
                supervisor.start();
                supervisorStarted = true;
            }
        }
    }

    static void stopPlanned(String mode) {
        synchronized (LOCK) {
            stopping = true;
            try {
                requestShutdown(mode);
            } catch (IOException error) {
                Logger.warning("診断アプリの正常終了要求に失敗しました: " + error);
                stopVerifiedProcess();
            }
        }
    }

    private static void supervise() {
        while (!stopping) {
            try {
                Thread.sleep(2000L);
                synchronized (LOCK) {
                    if (!stopping) {
                        ensureRunning();
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException error) {
                Logger.warning("診断アプリを再起動できません: " + error);
            }
        }
    }

    private static void ensureRunning() throws IOException {
        if (isRunning()) {
            return;
        }
        Path jar = diagnosticsJar();
        if (!Files.isRegularFile(jar)) {
            throw new IOException("NicoCacheDiagnostics.jar が見つかりません: "
                    + jar);
        }
        Path logDirectory = dataRoot().resolve("data/logs");
        Files.createDirectories(logDirectory);
        Process process = new ProcessBuilder(startCommand())
                .directory(applicationRoot().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(
                        logDirectory.resolve("nicocache-diagnostics.log")
                                .toFile()))
                .start();
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (isRunning()) {
                return;
            }
            if (!process.isAlive()) {
                throw new IOException("診断アプリが起動直後に終了しました");
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroy();
                throw new IOException("診断アプリの起動待機が中断されました", error);
            }
        }
        process.destroy();
        throw new IOException("診断アプリが10秒以内に起動しませんでした。"
                + "data/logs/nicocache-diagnostics.log を確認してください");
    }

    private static void requestShutdown(String mode) throws IOException {
        if (!isRunning()) {
            return;
        }
        List<String> command = baseCommand();
        command.add("--shutdown");
        command.add("--app-root=" + applicationRoot());
        command.add("--data-root=" + dataRoot());
        Process process = new ProcessBuilder(command)
                .directory(applicationRoot().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(
                        dataRoot().resolve("data/logs/nicocache-diagnostics.log")
                                .toFile()))
                .start();
        try {
            if (!process.waitFor(STOP_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("診断アプリ終了コマンドがタイムアウトしました"
                        + " (" + mode + ")");
            }
            if (process.exitValue() != 0 || isRunning()) {
                throw new IOException("診断アプリが正常終了しませんでした"
                        + " (" + mode + ", exit=" + process.exitValue() + ")");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("診断アプリの終了待機が中断されました", error);
        }
    }

    private static void stopVerifiedProcess() {
        Properties status = readStatus();
        if (status == null || !sameRoots(status)) {
            return;
        }
        long pid = parseLong(status.getProperty("pid"), -1L);
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null || !handle.isAlive()
                || !isDiagnostics(handle, status)) {
            return;
        }
        handle.destroy();
        try {
            handle.onExit().get(5L, TimeUnit.SECONDS);
        } catch (Exception error) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
    }

    private static List<String> startCommand() {
        List<String> command = baseCommand();
        command.add("--app-root=" + applicationRoot());
        command.add("--data-root=" + dataRoot());
        command.add("--hidden");
        return command;
    }

    private static List<String> baseCommand() {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(diagnosticsJar().toString());
        return command;
    }

    private static boolean isRunning() {
        Properties status = readStatus();
        if (status == null || !sameRoots(status)) {
            return false;
        }
        long pid = parseLong(status.getProperty("pid"), -1L);
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .map(handle -> isDiagnostics(handle, status)).orElse(false);
    }

    private static Properties readStatus() {
        Path statusPath = dataRoot().resolve(
                "data/nicocache-diagnostics-status.properties");
        if (!Files.isRegularFile(statusPath)) {
            return null;
        }
        Properties status = new Properties();
        try (var input = Files.newInputStream(statusPath)) {
            status.load(input);
            return status;
        } catch (IOException error) {
            return null;
        }
    }

    private static boolean sameRoots(Properties status) {
        return samePath(status.getProperty("applicationRoot"), applicationRoot())
                && samePath(status.getProperty("dataRoot"), dataRoot());
    }

    private static boolean samePath(String value, Path expected) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize().equals(expected);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean isDiagnostics(ProcessHandle handle,
            Properties status) {
        String commandLine = handle.info().commandLine().orElse("");
        if (!commandLine.isEmpty()) {
            return commandLine.contains(diagnosticsJar().toString())
                    || commandLine.contains("NicoCacheDiagnostics.jar");
        }
        try {
            Instant recorded = Instant.parse(status.getProperty("startedAt"));
            Instant actual = handle.info().startInstant().orElse(null);
            return actual != null && Duration.between(recorded, actual)
                    .abs().compareTo(Duration.ofSeconds(5)) <= 0;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
        String name = windows ? "javaw.exe" : "java";
        Path bundled = applicationRoot().resolve("jre/bin").resolve(name);
        if (Files.isRegularFile(bundled)) {
            return bundled;
        }
        Path current = Path.of(System.getProperty("java.home"), "bin", name);
        if (Files.isRegularFile(current)) {
            return current.toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("java.home"), "bin",
                windows ? "java.exe" : "java").toAbsolutePath().normalize();
    }

    private static Path applicationRoot() {
        return NicoCachePaths.applicationRoot().toAbsolutePath().normalize();
    }

    private static Path dataRoot() {
        return NicoCachePaths.dataRoot().toAbsolutePath().normalize();
    }

    private static Path diagnosticsJar() {
        return applicationRoot().resolve("NicoCacheDiagnostics.jar");
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
