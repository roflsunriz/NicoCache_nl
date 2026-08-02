package dareka.updater;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Disposable-runner E2E: install/update dependencies and resolve them from a fresh user shell. */
public final class LiveDependencyInstallTest {
    private LiveDependencyInstallTest() {}

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            System.out.println("Live dependency install test skipped outside Windows");
            return;
        }
        Path root = Files.createTempDirectory("dependency-live-install-");
        try {
            Files.writeString(root.resolve("NicoCache_nl.jar"), "test marker", StandardCharsets.UTF_8);
            DependencyEngine engine = new DependencyEngine(root);
            String result = engine.updateAll(21);
            assertContains(result, "Eclipse Temurin JDK", "Temurin result");
            assertContains(result, "FFmpeg", "FFmpeg result");
            assertContains(result, "Apache Ant", "Ant result");
            assertContains(result, "7-Zip", "7-Zip result");
            assertContains(result, "GPAC / MP4Box", "GPAC result");
            assertContains(result, "Bouncy Castle", "Bouncy Castle result");

            String userPath = readUserEnvironment("Path");
            String javaHome = readUserEnvironment("JAVA_HOME");
            String inheritedPath = System.getenv().getOrDefault("PATH", "");
            String effectivePath = userPath.isBlank() ? inheritedPath : userPath + ";" + inheritedPath;
            if (javaHome.isBlank()) javaHome = System.getenv().getOrDefault("JAVA_HOME", "");

            verifyFreshShell(Arrays.asList("java", "-version"), effectivePath, javaHome);
            verifyFreshShell(Arrays.asList("javac", "-version"), effectivePath, javaHome);
            verifyFreshShell(Arrays.asList("ffmpeg", "-version"), effectivePath, javaHome);
            verifyFreshShell(Arrays.asList("ffprobe", "-version"), effectivePath, javaHome);
            verifyFreshShell(Arrays.asList("ant", "-version"), effectivePath, javaHome);
            verifyFreshShell(Arrays.asList("7z"), effectivePath, javaHome);
            verifyFreshShell(Arrays.asList("MP4Box", "-version"), effectivePath, javaHome);

            assertTrue(Files.isRegularFile(root.resolve("lib/bcprov.jar")), "Bouncy Castle was not installed locally");
            assertTrue(!Files.exists(root.resolve("runtime")), "Temurin leaked into the NicoCache_nl runtime directory");
            assertTrue(!Files.exists(root.resolve("tools/ffmpeg")), "FFmpeg leaked into the NicoCache_nl directory");
            assertTrue(!Files.exists(root.resolve("tools/ant")), "Ant leaked into the NicoCache_nl directory");
            assertTrue(!Files.exists(root.resolve("tools/7zip")), "7-Zip leaked into the NicoCache_nl directory");
            System.out.println("User-wide dependency installation and fresh-shell command E2E passed");
        } finally {
            deleteTree(root);
        }
    }

    private static String readUserEnvironment(String name) throws Exception {
        Process process = new ProcessBuilder("reg.exe", "query", "HKCU\\Environment", "/v", name)
                .redirectErrorStream(true).start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        process.waitFor();
        if (process.exitValue() != 0) return "";
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase().startsWith(name.toLowerCase() + " ")) {
                String[] parts = trimmed.split("\\s+", 3);
                if (parts.length == 3) return parts[2];
            }
        }
        return "";
    }

    private static void verifyFreshShell(List<String> command, String path, String javaHome) throws Exception {
        Path script = Files.createTempFile("dependency-command-probe-", ".cmd");
        try {
            StringBuilder commandLine = new StringBuilder();
            for (String value : command) {
                if (commandLine.length() > 0) commandLine.append(' ');
                commandLine.append(quoteBatchArgument(value));
            }
            String scriptText = "@echo off\r\n" + commandLine + "\r\nexit /b %errorlevel%\r\n";
            Files.writeString(script, scriptText, StandardCharsets.UTF_8);

            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/d", "/c", script.toString())
                    .redirectErrorStream(true);
            builder.environment().put("PATH", path);
            if (!javaHome.isBlank()) builder.environment().put("JAVA_HOME", javaHome);
            Process process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream input = process.getInputStream()) { input.transferTo(output); }
                catch (Exception ignored) { }
            });
            reader.start();
            if (!process.waitFor(Duration.ofMinutes(3).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Fresh-shell command timed out: " + command);
            }
            reader.join(5000);
            assertTrue(process.exitValue() == 0,
                    "Fresh-shell command failed: " + command + "\n" + output.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(script);
        }
    }

    private static String quoteBatchArgument(String value) {
        if (value.matches("[A-Za-z0-9._:/\\\\-]+")) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void assertContains(String value, String expected, String label) {
        assertTrue(value.contains(expected), label + " missing: " + expected + " in " + value);
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
