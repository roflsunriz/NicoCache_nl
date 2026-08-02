package nicocache.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class CoreProcess {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);
    private final LauncherPaths paths;
    private Process startedProcess;

    CoreProcess(LauncherPaths paths) {
        this.paths = paths;
    }

    void startGui() throws IOException {
        start(false, false);
    }

    void startHeadless(boolean foreground) throws IOException {
        start(foreground, true);
    }

    private void start(boolean foreground, boolean headless) throws IOException {
        Properties existing = ControlClient.readStatusIfPresent(
                paths.getControlStatusFile());
        if (ControlClient.isAlive(existing)) {
            if ("degraded".equals(existing.getProperty("state"))) {
                forceStop();
            } else {
                ControlClient.waitUntilReady(paths, null, START_TIMEOUT);
                return;
            }
        }

        List<String> command = buildStartCommand(headless);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(paths.getApplicationRoot().toFile())
                .redirectErrorStream(true);
        if (foreground) {
            builder.inheritIO();
        } else {
            Path logDirectory = paths.getDataRoot().resolve("data/logs");
            Files.createDirectories(logDirectory);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    logDirectory.resolve("nicocache-core.log").toFile()));
        }
        startedProcess = builder.start();
        try {
            ControlClient.waitUntilReady(paths, startedProcess, START_TIMEOUT);
        } catch (IOException error) {
            stopFailedStart();
            throw error;
        }
    }

    List<String> buildStartCommand(boolean headless) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Xmx128m");
        command.add("-Dnicocache.applicationRoot="
                + paths.getApplicationRoot());
        command.add("-Dnicocache.userDataRoot=" + paths.getDataRoot());
        command.add("-jar");
        command.add(paths.getCoreJar().toString());
        if (headless) {
            command.add("--headless");
        }
        return command;
    }

    int startAndWait() throws IOException {
        Properties existing = ControlClient.readStatusIfPresent(
                paths.getControlStatusFile());
        if (ControlClient.isAlive(existing)) {
            ControlClient.waitUntilReady(paths, null, START_TIMEOUT);
            waitForExistingProcess(existing);
            return 0;
        }
        startHeadless(true);
        try {
            return startedProcess == null ? 0 : startedProcess.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("本体の待機が中断されました", error);
        }
    }

    private void waitForExistingProcess(Properties status) throws IOException {
        long pid;
        try {
            pid = Long.parseLong(status.getProperty("pid"));
        } catch (NumberFormatException error) {
            throw new IOException("本体のPIDが不正です", error);
        }
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null) {
            throw new IOException("本体のプロセスを確認できません: " + pid);
        }
        try {
            handle.onExit().get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("本体の待機が中断されました", error);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new IOException("本体の終了待機に失敗しました", error);
        }
    }

    int runSetup(List<String> setupArguments, boolean inheritIo)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Dnicocache.applicationRoot="
                + paths.getApplicationRoot());
        command.add("-jar");
        command.add(paths.getCoreJar().toString());
        command.addAll(setupArguments);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(paths.getApplicationRoot().toFile());
        if (inheritIo) {
            builder.inheritIO();
        } else {
            Files.createDirectories(paths.getDataRoot().resolve("data/logs"));
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    paths.getDataRoot().resolve("data/logs/setup.log").toFile()));
        }
        Process process = builder.start();
        try {
            return process.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("初回セットアップが中断されました", error);
        }
    }

    void gracefulStop() throws IOException {
        Properties status = ControlClient.readStatusIfPresent(
                paths.getControlStatusFile());
        if (status == null || !ControlClient.isAlive(status)) {
            return;
        }
        try {
            int code = ControlClient.post(paths,
                    "/api/control/graceful-shutdown").statusCode();
            if (code != 202 && code != 200) {
                throw new IOException("graceful shutdown の応答が不正です: " + code);
            }
            ControlClient.waitForExit(status, STOP_TIMEOUT);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("停止処理が中断されました", error);
        }
        if (ControlClient.isAlive(status)) {
            throw new IOException("本体が graceful shutdown 時間内に終了しませんでした");
        }
    }

    void forceStop() throws IOException {
        Properties status = ControlClient.readStatusIfPresent(
                paths.getControlStatusFile());
        if (status == null || !ControlClient.isAlive(status)) {
            return;
        }
        try {
            ControlClient.post(paths, "/api/control/force-shutdown");
            ControlClient.waitForExit(status, Duration.ofSeconds(5));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            // The fallback below still requires the process to be the product.
        }
        if (ControlClient.isAlive(status)) {
            long pid = Long.parseLong(status.getProperty("pid"));
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle != null && handle.isAlive() && isProductProcess(handle)) {
                handle.destroyForcibly();
            }
        }
    }

    private boolean isProductProcess(ProcessHandle handle) {
        String commandLine = handle.info().commandLine().orElse("");
        return commandLine.contains(paths.getCoreJar().getFileName().toString())
                || commandLine.contains("NicoCache_nl.jar");
    }

    private void stopFailedStart() {
        if (startedProcess == null || !startedProcess.isAlive()) {
            return;
        }
        startedProcess.destroy();
        try {
            if (!startedProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                startedProcess.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            startedProcess.destroyForcibly();
        }
    }

    private Path javaExecutable() {
        String executable = paths.getPlatform() == LauncherPaths.Platform.WINDOWS
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
