package nicocache.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class ThreadDumpCollectorTest {
    private ThreadDumpCollectorTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("thread-dump-collector-");
        try {
            DiagnosticsPaths paths = DiagnosticsPaths.resolve(root, root);
            CoreProbe probe = new CoreProbe(paths);
            ThreadDumpCollector collector = new ThreadDumpCollector(paths,
                    probe, new long[] { 0L });
            IncidentReport report = new IncidentReport("process-exited",
                    Instant.now(), 987654321L, List.of());
            collector.collect(987654321L, report, new Redactor(paths), true,
                    false, List.of("pre-failure snapshot"));

            assertEquals(1, report.snapshots.size(),
                    "cached snapshot must replace impossible post-exit dump");
            assertContains(report.snapshots.get(0), "pre-failure snapshot",
                    "cached snapshot content");
            assertTrue(report.errors.isEmpty(),
                    "an exited process must not create repeated dump errors");
            assertEquals(1, report.notices.size(),
                    "post-exit substitution must be explained");
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                });
            }
        }
        System.out.println("Thread dump collector tests passed");
    }

    private static void assertContains(String value, String expected,
            String message) {
        if (!value.contains(expected)) {
            throw new AssertionError(message + ": expected=" + expected);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
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
