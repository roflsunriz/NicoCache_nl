package nicocache.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Inter-process E2E for stopping only the resident launcher. */
public final class LauncherControlTest {
    private LauncherControlTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--launcher-fixture".equals(args[0])) {
            runLauncherFixture(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length > 0 && "--core-fixture".equals(args[0])) {
            Thread.sleep(Duration.ofMinutes(1).toMillis());
            return;
        }
        runInterProcessTest();
        System.out.println("Launcher control E2E tests passed");
    }

    private static void runInterProcessTest() throws Exception {
        Path sandbox = Files.createTempDirectory("nicocache-launcher-control-");
        Process launcher = null;
        Process core = null;
        try {
            Path application = Files.createDirectories(
                    sandbox.resolve("application"));
            Path data = Files.createDirectories(sandbox.resolve("data-root"));
            Files.write(application.resolve("NicoCache_nl.jar"),
                    new byte[] {0});

            core = startFixture("--core-fixture");
            launcher = startFixture("--launcher-fixture",
                    application.toString(), data.toString());
            Path controlFile = data.resolve(
                    "data/nicocache-launcher-control.properties");
            waitForFile(controlFile, launcher, Duration.ofSeconds(10));
            if (Files.getFileAttributeView(controlFile,
                    java.nio.file.attribute.PosixFileAttributeView.class)
                    != null) {
                assertTrue(Files.getPosixFilePermissions(controlFile).equals(
                        java.util.Set.of(
                                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)),
                        "launcher control state must be owner-only");
            }
            Path requestFile = data.resolve(
                    "data/nicocache-launcher-exit.request");
            Files.writeString(requestFile, "invalid-token",
                    StandardCharsets.UTF_8);
            waitForAbsent(requestFile, launcher, Duration.ofSeconds(5));
            assertTrue(launcher.isAlive(),
                    "invalid token must not stop the resident launcher");

            Process command = new ProcessBuilder(javaExecutable(),
                    "-cp", System.getProperty("java.class.path"),
                    LauncherMain.class.getName(),
                    "--app-root=" + application,
                    "--data-root=" + data,
                    "--headless", "--launcher-only-stop")
                    .redirectErrorStream(true)
                    .start();
            if (!command.waitFor(20, TimeUnit.SECONDS)) {
                command.destroyForcibly();
                throw new AssertionError("launcher-only CLI timed out");
            }
            String output = new String(command.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, command.exitValue(),
                    "launcher-only CLI exit: " + output);
            assertContains(output, "NicoCacheLauncher stopped",
                    "launcher-only CLI output");
            assertTrue(launcher.waitFor(10, TimeUnit.SECONDS),
                    "resident launcher fixture must exit");
            assertEquals(0, launcher.exitValue(),
                    "resident launcher fixture exit");
            assertTrue(core.isAlive(),
                    "core fixture must remain alive after launcher-only stop");
            assertFalse(Files.exists(controlFile),
                    "launcher control state must be removed on exit");
            assertFalse(Files.exists(requestFile),
                    "launcher exit request must be consumed");

            Process missingCommand = new ProcessBuilder(javaExecutable(),
                    "-cp", System.getProperty("java.class.path"),
                    LauncherMain.class.getName(),
                    "--app-root=" + application,
                    "--data-root=" + data,
                    "--headless", "--launcher-only-stop")
                    .redirectErrorStream(true)
                    .start();
            assertTrue(missingCommand.waitFor(5, TimeUnit.SECONDS),
                    "missing launcher CLI must finish");
            String missingOutput = new String(
                    missingCommand.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(1, missingCommand.exitValue(),
                    "missing launcher CLI exit");
            assertContains(missingOutput, "NicoCacheLauncher:",
                    "missing launcher CLI diagnostic");
            assertTrue(core.isAlive(),
                    "missing launcher request must not stop the core fixture");
        } finally {
            stop(core);
            stop(launcher);
            deleteRecursively(sandbox);
        }
    }

    private static Process startFixture(String... arguments)
            throws IOException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(LauncherControlTest.class.getName());
        command.addAll(java.util.List.of(arguments));
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
    }

    private static void runLauncherFixture(Path application, Path data)
            throws Exception {
        LauncherPaths paths = LauncherPaths.resolve(application, data);
        try (LauncherControl control = LauncherControl.register(paths)) {
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                if (control.consumeExitRequest()) {
                    return;
                }
                Thread.sleep(50L);
            }
        }
        throw new AssertionError("launcher fixture did not receive exit request");
    }

    private static void waitForFile(Path path, Process owner,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)) {
                return;
            }
            if (!owner.isAlive()) {
                String output = new String(owner.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                throw new AssertionError(
                        "launcher fixture exited before registration: " + output);
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("launcher control state was not created");
    }

    private static void waitForAbsent(Path path, Process owner,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!Files.exists(path)) {
                return;
            }
            if (!owner.isAlive()) {
                throw new AssertionError(
                        "launcher fixture exited for an invalid token");
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("invalid launcher request was not consumed");
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase()
                        .contains("win") ? "java.exe" : "java")
                .toString();
    }

    private static void stop(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void assertContains(String actual, String expected,
            String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertEquals(int expected, int actual,
            String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }
}
