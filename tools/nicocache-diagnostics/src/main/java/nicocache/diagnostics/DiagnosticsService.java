package nicocache.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Long-lived watchdog orchestration. It never starts or restarts the core. */
final class DiagnosticsService implements AutoCloseable {
    interface Listener {
        void heartbeat(HeartbeatSample sample);
        void collectionStarted(String reason);
        void collectionCompleted(Path report);
        void collectionFailed(String message);
        void showRequested();
    }

    private static final int TIMELINE_CAPACITY = 300;
    private final DiagnosticsPaths paths;
    private final CoreProbe probe;
    private final IncidentCollector collector;
    private final HeartbeatEvaluator evaluator = new HeartbeatEvaluator(3);
    private final ScheduledExecutorService monitor;
    private final ExecutorService collectionExecutor;
    private final Deque<HeartbeatSample> timeline = new ArrayDeque<>();
    private final AtomicBoolean collecting = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Listener listener;
    private volatile HeartbeatSample lastSample;
    private volatile Path lastReport;

    DiagnosticsService(DiagnosticsPaths paths) {
        this(paths, new CoreProbe(paths));
    }

    DiagnosticsService(DiagnosticsPaths paths, CoreProbe probe) {
        this.paths = paths;
        this.probe = probe;
        this.collector = new IncidentCollector(paths, probe);
        this.monitor = Executors.newSingleThreadScheduledExecutor(runnable ->
                daemon(runnable, "nicocache-diagnostics-monitor"));
        this.collectionExecutor = Executors.newSingleThreadExecutor(runnable ->
                daemon(runnable, "nicocache-diagnostics-collector"));
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void start() throws IOException {
        writeStatus();
        monitor.scheduleWithFixedDelay(this::monitorOnce,
                0L, 2L, TimeUnit.SECONDS);
    }

    CompletableFuture<Path> collectNow() {
        HeartbeatSample sample = lastSample;
        long pid = sample == null ? evaluator.sessionPid() : sample.pid;
        return collect("manual-collection", pid);
    }

    HeartbeatSample lastSample() { return lastSample; }
    Path lastReport() { return lastReport; }

    private void monitorOnce() {
        if (closed.get()) {
            return;
        }
        try {
            consumeShowRequest();
            HeartbeatSample sample = probe.probe();
            lastSample = sample;
            appendTimeline(sample);
            Listener current = listener;
            if (current != null) {
                current.heartbeat(sample);
            }
            long expectedPid = sample.pid > 0L
                    ? sample.pid : evaluator.sessionPid();
            boolean expectedStop = probe.expectedStop(expectedPid)
                    || "stopping".equals(sample.coreState);
            HeartbeatEvaluator.Decision decision = evaluator.accept(
                    sample, expectedStop);
            if (decision.incident) {
                collect(decision.reason, decision.pid);
            }
        } catch (RuntimeException error) {
            Listener current = listener;
            if (current != null) {
                current.collectionFailed(safe(error));
            }
        }
    }

    private CompletableFuture<Path> collect(String reason, long pid) {
        if (!collecting.compareAndSet(false, true)) {
            CompletableFuture<Path> busy = new CompletableFuture<>();
            busy.completeExceptionally(new IllegalStateException(
                    "diagnostic collection is already running"));
            return busy;
        }
        Listener current = listener;
        if (current != null) {
            current.collectionStarted(reason);
        }
        List<HeartbeatSample> history = timelineSnapshot();
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path report = collector.collect(reason, pid, history);
                lastReport = report;
                Listener active = listener;
                if (active != null) {
                    active.collectionCompleted(report);
                }
                return report;
            } catch (IOException error) {
                Listener active = listener;
                if (active != null) {
                    active.collectionFailed(safe(error));
                }
                throw new java.io.UncheckedIOException(error);
            } finally {
                collecting.set(false);
            }
        }, collectionExecutor);
    }

    private synchronized void appendTimeline(HeartbeatSample sample) {
        while (timeline.size() >= TIMELINE_CAPACITY) {
            timeline.removeFirst();
        }
        timeline.addLast(sample);
    }

    private synchronized List<HeartbeatSample> timelineSnapshot() {
        return new ArrayList<>(timeline);
    }

    private void writeStatus() throws IOException {
        Files.createDirectories(paths.diagnosticsStatus().getParent());
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("pid",
                Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("startedAt", Instant.now().toString());
        properties.setProperty("applicationRoot",
                paths.applicationRoot().toString());
        properties.setProperty("dataRoot", paths.dataRoot().toString());
        try (var output = Files.newOutputStream(paths.diagnosticsStatus(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, "NicoCacheDiagnostics status");
        }
    }

    private void consumeShowRequest() {
        Path request = paths.dataRoot().resolve(
                "data/nicocache-diagnostics-show.request");
        try {
            if (Files.deleteIfExists(request)) {
                Listener current = listener;
                if (current != null) {
                    current.showRequested();
                }
            }
        } catch (IOException ignored) {
            // A later heartbeat retries the one-shot request.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        monitor.shutdownNow();
        collectionExecutor.shutdown();
        try {
            collectionExecutor.awaitTermination(10L, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        try {
            Files.deleteIfExists(paths.diagnosticsStatus());
        } catch (IOException ignored) {
            // A stale PID is verified with ProcessHandle by the launcher.
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String safe(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
