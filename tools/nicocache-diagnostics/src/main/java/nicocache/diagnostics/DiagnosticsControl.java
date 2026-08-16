package nicocache.diagnostics;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Authenticated-by-instance local lifecycle control for the watchdog. */
final class DiagnosticsControl {
    private DiagnosticsControl() {
    }

    static void requestShutdown(DiagnosticsPaths paths, Duration timeout)
            throws IOException {
        Properties status = readStatus(paths.diagnosticsStatus());
        if (status == null) {
            return;
        }
        long pid = parseLong(status.getProperty("pid"), -1L);
        String instanceId = status.getProperty("instanceId", "");
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null || !handle.isAlive()) {
            Files.deleteIfExists(paths.diagnosticsStatus());
            return;
        }
        if (instanceId.isBlank()
                || !samePath(status.getProperty("applicationRoot"),
                        paths.applicationRoot())
                || !samePath(status.getProperty("dataRoot"),
                        paths.dataRoot())
                || !matchesProcess(handle, status, paths)) {
            throw new IOException("診断アプリの状態ファイルが現在の環境と一致しません");
        }

        Properties request = new Properties();
        request.setProperty("version", "1");
        request.setProperty("pid", Long.toString(pid));
        request.setProperty("instanceId", instanceId);
        request.setProperty("requestedAt", Instant.now().toString());
        writeProperties(paths.diagnosticsShutdownRequest(), request,
                "NicoCacheDiagnostics planned shutdown request");
        try {
            handle.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("診断アプリの終了待機が中断されました", error);
        } catch (ExecutionException error) {
            throw new IOException("診断アプリの終了待機に失敗しました", error);
        } catch (TimeoutException error) {
            throw new IOException("診断アプリが正常終了時間内に終了しませんでした", error);
        }
    }

    static boolean consumeShutdownRequest(DiagnosticsPaths paths, long pid,
            String instanceId) {
        Path requestPath = paths.diagnosticsShutdownRequest();
        if (!Files.isRegularFile(requestPath)) {
            return false;
        }
        Properties request;
        try {
            request = readStatus(requestPath);
            Files.deleteIfExists(requestPath);
        } catch (IOException error) {
            return false;
        }
        return request != null
                && parseLong(request.getProperty("pid"), -1L) == pid
                && instanceId.equals(request.getProperty("instanceId"));
    }

    private static Properties readStatus(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void writeProperties(Path destination,
            Properties properties, String comment) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".tmp");
        try (var output = Files.newOutputStream(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, comment);
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean samePath(String value, Path expected) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize().equals(
                    expected.toAbsolutePath().normalize());
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean matchesProcess(ProcessHandle handle,
            Properties status, DiagnosticsPaths paths) {
        String commandLine = handle.info().commandLine().orElse("");
        if (!commandLine.isEmpty()) {
            return matchesCommandLine(commandLine, paths);
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

    static boolean matchesCommandLine(String commandLine,
            DiagnosticsPaths paths) {
        Path jar = paths.applicationRoot().resolve("NicoCacheDiagnostics.jar");
        if (commandLine.contains(jar.toString())
                || commandLine.contains("NicoCacheDiagnostics.jar")) {
            return true;
        }
        return containsCommandArgument(commandLine,
                        DiagnosticsMain.class.getName())
                && containsCommandArgument(commandLine,
                        "--app-root=" + paths.applicationRoot())
                && containsCommandArgument(commandLine,
                        "--data-root=" + paths.dataRoot());
    }

    private static boolean containsCommandArgument(String commandLine,
            String argument) {
        int from = 0;
        while (from <= commandLine.length() - argument.length()) {
            int start = commandLine.indexOf(argument, from);
            if (start < 0) {
                return false;
            }
            int end = start + argument.length();
            boolean startsAtBoundary = start == 0
                    || isCommandBoundary(commandLine.charAt(start - 1));
            boolean endsAtBoundary = end == commandLine.length()
                    || isCommandBoundary(commandLine.charAt(end));
            if (startsAtBoundary && endsAtBoundary) {
                return true;
            }
            from = start + 1;
        }
        return false;
    }

    private static boolean isCommandBoundary(char value) {
        return Character.isWhitespace(value) || value == '"' || value == '\'';
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
