package nicocache.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Starts the independent watchdog once; it never controls the core process. */
final class DiagnosticsProcess {
    private static final long START_TIMEOUT_SECONDS = 10L;
    private final LauncherPaths paths;

    DiagnosticsProcess(LauncherPaths paths) {
        this.paths = paths;
    }

    void startIfNeeded() throws IOException {
        if (isRunning()) {
            return;
        }
        Path diagnosticsJar = paths.getDiagnosticsJar();
        if (diagnosticsJar == null || !Files.isRegularFile(diagnosticsJar)) {
            throw new IOException(
                    "NicoCacheDiagnostics.jar が見つかりません");
        }
        List<String> command = buildStartCommand();
        Path logDirectory = paths.getDataRoot().resolve("data/logs");
        Files.createDirectories(logDirectory);
        Process started = new ProcessBuilder(command)
                .directory(paths.getApplicationRoot().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(
                        logDirectory.resolve("nicocache-diagnostics.log")
                                .toFile()))
                .start();
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (isRunning()) {
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                if (started.isAlive()) {
                    started.destroy();
                }
                throw new IOException("診断アプリの起動待機が中断されました", error);
            }
        }
        if (started.isAlive()) {
            started.destroy();
        }
        throw new IOException("診断アプリが10秒以内に起動しませんでした。"
                + "data/logs/nicocache-diagnostics.log を確認してください");
    }

    List<String> buildStartCommand() {
        if (paths.getDiagnosticsJar() == null) {
            throw new IllegalStateException(
                    "NicoCacheDiagnostics.jar が見つかりません");
        }
        List<String> command = new ArrayList<>();
        command.add(paths.getJavaExecutable(true).toString());
        command.add("-jar");
        command.add(paths.getDiagnosticsJar().toString());
        command.add("--app-root=" + paths.getApplicationRoot());
        command.add("--data-root=" + paths.getDataRoot());
        command.add("--hidden");
        return command;
    }

    private boolean isRunning() {
        Path diagnosticsJar = paths.getDiagnosticsJar();
        if (diagnosticsJar == null) {
            return false;
        }
        Path statusPath = paths.getDiagnosticsStatusFile();
        if (!Files.isRegularFile(statusPath)) {
            return false;
        }
        Properties status = new Properties();
        try (var input = Files.newInputStream(statusPath)) {
            status.load(input);
            long pid = Long.parseLong(status.getProperty("pid", "-1"));
            if (!samePath(status.getProperty("applicationRoot"),
                    paths.getApplicationRoot())
                    || !samePath(status.getProperty("dataRoot"),
                            paths.getDataRoot())) {
                return false;
            }
            return pid > 0L && ProcessHandle.of(pid).filter(
                    ProcessHandle::isAlive).map(handle -> handle.info()
                    .commandLine().map(command -> command.contains(
                            diagnosticsJar.toString()))
                    .orElse(true)).orElse(false);
        } catch (IOException | NumberFormatException error) {
            return false;
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
}
