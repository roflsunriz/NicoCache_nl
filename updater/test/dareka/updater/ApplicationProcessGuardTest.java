package dareka.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/** Windows integration test for detecting a running executable inside the target installation. */
public final class ApplicationProcessGuardTest {
    private ApplicationProcessGuardTest() {}

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            System.out.println("Application process guard test skipped outside Windows");
            return;
        }
        Path root = Files.createTempDirectory("application-process-guard-");
        Process process = null;
        try {
            Path systemRoot = Path.of(System.getenv("SystemRoot"));
            Path executable = root.resolve("NicoCache_nl.exe");
            Files.copy(systemRoot.resolve("System32").resolve("ping.exe"), executable,
                    StandardCopyOption.REPLACE_EXISTING);
            process = new ProcessBuilder(executable.toString(), "-n", "30", "127.0.0.1")
                    .directory(root.toFile()).redirectErrorStream(true).start();
            Thread.sleep(1000);
            assertTrue(process.isAlive(), "fixture process exited before detection");

            boolean rejected = false;
            try {
                ApplicationProcessGuard.requireStopped(root);
            } catch (IOException expected) {
                rejected = expected.getMessage().contains("NicoCache_nlが実行中")
                        && expected.getMessage().contains("PID");
            }
            assertTrue(rejected, "running target executable was not rejected");

            process.destroyForcibly();
            process.waitFor();
            process = null;
            ApplicationProcessGuard.requireStopped(root);
            System.out.println("Application process guard tests passed");
        } finally {
            if (process != null) process.destroyForcibly();
            deleteTree(root);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}
