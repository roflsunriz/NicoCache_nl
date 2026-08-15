package nicocache.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/** Collects bounded local evidence and writes only sanitized report content. */
final class IncidentCollector {
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_INVENTORY_ENTRIES = 500;
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss XXX", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final DiagnosticsPaths paths;
    private final ThreadDumpCollector threadDumps;
    private final HtmlReportWriter writer;

    IncidentCollector(DiagnosticsPaths paths, CoreProbe probe) {
        this(paths, probe, new ThreadDumpCollector(paths, probe),
                new HtmlReportWriter());
    }

    IncidentCollector(DiagnosticsPaths paths, CoreProbe probe,
            ThreadDumpCollector threadDumps, HtmlReportWriter writer) {
        this.paths = paths;
        this.threadDumps = threadDumps;
        this.writer = writer;
    }

    Path collect(String reason, long pid, List<HeartbeatSample> timeline)
            throws IOException {
        return collect(reason, pid, timeline, List.of());
    }

    Path collect(String reason, long pid, List<HeartbeatSample> timeline,
            List<String> recentSnapshots) throws IOException {
        Redactor redactor = new Redactor(paths);
        IncidentReport report = new IncidentReport(redactor.redact(reason),
                Instant.now(), pid, sanitizeTimeline(timeline, redactor));
        collectEnvironment(report, redactor);
        collectFileLayout(report, redactor);
        collectConfiguration(report, redactor);
        collectLogs(report, redactor);
        boolean externalFirst = reason.contains("control-heartbeat")
                || reason.contains("all-heartbeats");
        boolean processEnded = "process-exited".equals(reason)
                || pid <= 0L || !ProcessHandle.of(pid)
                .map(ProcessHandle::isAlive).orElse(false);
        threadDumps.collect(pid, report, redactor, externalFirst,
                processEnded, recentSnapshots);
        for (int index = 0; index < report.errors.size(); index++) {
            report.errors.set(index, redactor.redact(report.errors.get(index)));
        }
        for (int index = 0; index < report.notices.size(); index++) {
            report.notices.set(index,
                    redactor.redact(report.notices.get(index)));
        }
        report.redactionCounts = redactor.counts();
        return writer.write(paths.incidentsRoot(), report);
    }

    private void collectEnvironment(IncidentReport report, Redactor redactor) {
        put(report.environment, "NicoCache PID", Long.toString(report.pid), redactor);
        put(report.environment, "診断アプリPID",
                Long.toString(ProcessHandle.current().pid()), redactor);
        put(report.environment, "OS",
                System.getProperty("os.name", "unknown") + " "
                + System.getProperty("os.version", "unknown"), redactor);
        put(report.environment, "CPU architecture",
                System.getProperty("os.arch", "unknown"), redactor);
        put(report.environment, "Processors",
                Integer.toString(Runtime.getRuntime().availableProcessors()), redactor);
        put(report.environment, "Java",
                System.getProperty("java.version", "unknown") + " / "
                + System.getProperty("java.vendor", "unknown") + " / "
                + System.getProperty("java.vm.name", "unknown"), redactor);
        put(report.environment, "Application root",
                paths.applicationRoot().toString(), redactor);
        put(report.environment, "User data root",
                paths.dataRoot().toString(), redactor);
        addFileStore(report, paths.applicationRoot(), "Application disk", redactor);
        addFileStore(report, paths.dataRoot(), "Data disk", redactor);
    }

    private void addFileStore(IncidentReport report, Path path, String label,
            Redactor redactor) {
        try {
            Path existing = nearestExisting(path);
            FileStore store = Files.getFileStore(existing);
            put(report.environment, label,
                    "usable=" + store.getUsableSpace() + ", total="
                    + store.getTotalSpace() + ", type=" + store.type(), redactor);
        } catch (IOException error) {
            report.errors.add(label + ": " + safe(error));
        }
    }

    private void collectFileLayout(IncidentReport report, Redactor redactor) {
        for (Path path : List.of(
                paths.applicationRoot().resolve("NicoCache_nl.jar"),
                paths.applicationRoot().resolve("NicoCacheLauncher.jar"),
                paths.applicationRoot().resolve("NicoCacheDiagnostics.jar"),
                paths.applicationRoot().resolve("NicoCache_nl.version"),
                paths.applicationRoot().resolve("config.properties"),
                paths.dataRoot().resolve("NicoCacheGUI.property"),
                paths.dataRoot().resolve("certs/site.jks"),
                paths.dataRoot().resolve("certs/ca.cer"),
                paths.dataRoot().resolve("proxy.pac"))) {
            report.files.put(redactor.redact(path.toString()),
                    fileMetadata(path));
        }
        inventory(report, paths.applicationRoot().resolve("extensions"), redactor);
        inventory(report, paths.dataRoot().resolve("extensions"), redactor);
        inventory(report, paths.applicationRoot().resolve("nlFilters"), redactor);
        inventory(report, paths.dataRoot().resolve("nlFilters"), redactor);
    }

    private void inventory(IncidentReport report, Path root, Redactor redactor) {
        if (!Files.isDirectory(root)) {
            report.files.put(redactor.redact(root.toString()), "directory missing");
            return;
        }
        try (Stream<Path> stream = Files.walk(root, 3)) {
            List<Path> entries = stream.filter(Files::isRegularFile)
                    .sorted().limit(MAX_INVENTORY_ENTRIES)
                    .collect(Collectors.toList());
            for (Path entry : entries) {
                report.files.put(redactor.redact(entry.toString()),
                        fileMetadata(entry));
            }
            if (entries.size() == MAX_INVENTORY_ENTRIES) {
                report.files.put(redactor.redact(root.toString()) + "/…",
                        "inventory truncated at " + MAX_INVENTORY_ENTRIES);
            }
        } catch (IOException error) {
            report.errors.add("inventory " + root + ": " + safe(error));
        }
    }

    private void collectConfiguration(IncidentReport report, Redactor redactor) {
        for (Path config : List.of(
                paths.applicationRoot().resolve("config.properties"),
                paths.applicationRoot().resolve("config.ini"),
                paths.dataRoot().resolve("NicoCacheGUI.property"))) {
            if (!Files.isRegularFile(config)) {
                continue;
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(config)) {
                properties.load(input);
                Map<String, String> sorted = new TreeMap<>();
                for (String key : properties.stringPropertyNames()) {
                    sorted.put(key, properties.getProperty(key, ""));
                }
                StringBuilder value = new StringBuilder();
                for (Map.Entry<String, String> entry : sorted.entrySet()) {
                    value.append(entry.getKey()).append('=')
                            .append(entry.getValue()).append('\n');
                }
                report.configuration.put(redactor.redact(config.toString()),
                        redactor.redact(value.toString()));
            } catch (IOException | IllegalArgumentException error) {
                report.errors.add("configuration " + config + ": " + safe(error));
            }
        }
    }

    private void collectLogs(IncidentReport report, Redactor redactor) {
        for (Path log : List.of(
                paths.applicationRoot().resolve("debug.log"),
                paths.dataRoot().resolve("data/logs/nicocache-core.log"),
                paths.dataRoot().resolve("data/logs/nicocache-diagnostics.log"),
                paths.dataRoot().resolve("debug-dump-stack.txt"))) {
            if (!Files.isRegularFile(log)) {
                continue;
            }
            try {
                report.logs.put(redactor.redact(log.toString()),
                        redactor.redact(readTail(log, MAX_TEXT_BYTES)));
            } catch (IOException error) {
                report.errors.add("log " + log + ": " + safe(error));
            }
        }
    }

    private static List<HeartbeatSample> sanitizeTimeline(
            List<HeartbeatSample> source, Redactor redactor) {
        List<HeartbeatSample> result = new ArrayList<>(source.size());
        for (HeartbeatSample sample : source) {
            result.add(new HeartbeatSample(sample.capturedAt, sample.pid,
                    redactor.redact(sample.coreState), sample.processAlive,
                    sample.controlAlive, sample.proxyAlive,
                    sample.controlMillis, sample.proxyMillis,
                    redactor.redact(sample.detail), sample.health));
        }
        return result;
    }

    private static void put(Map<String, String> destination, String key,
            String value, Redactor redactor) {
        destination.put(key, redactor.redact(value));
    }

    private static Path nearestExisting(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IOException("existing parent not found");
        }
        return current;
    }

    private static String fileMetadata(Path path) {
        if (!Files.exists(path)) {
            return "missing";
        }
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            return (Files.isDirectory(path) ? "directory" : "file")
                    + ", size=" + (Files.isRegularFile(path)
                    ? Files.size(path) : 0L)
                    + ", modified=" + TIME.format(modified.toInstant());
        } catch (IOException error) {
            return "exists, metadata unavailable";
        }
    }

    private static String readTail(Path path, int limit) throws IOException {
        long size = Files.size(path);
        long skip = Math.max(0L, size - limit);
        try (InputStream input = Files.newInputStream(path)) {
            long remaining = skip;
            while (remaining > 0L) {
                long skipped = input.skip(remaining);
                if (skipped <= 0L) {
                    if (input.read() < 0) {
                        break;
                    }
                    skipped = 1L;
                }
                remaining -= skipped;
            }
            byte[] bytes = input.readNBytes(limit);
            String prefix = skip > 0L ? "[earlier content omitted]\n" : "";
            return prefix + new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String safe(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
