package nicocache.diagnostics;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;

/** Keeps bounded in-memory snapshots that survive an unexpected core exit. */
final class RecentSnapshots {
    @FunctionalInterface
    interface Source {
        String capture() throws IOException, InterruptedException;
    }

    private static final int DEFAULT_CAPACITY = 3;
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

    private final int capacity;
    private final Duration interval;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long sessionPid = -1L;
    private Instant nextCapture = Instant.EPOCH;
    private boolean capturing;

    RecentSnapshots() {
        this(DEFAULT_CAPACITY, DEFAULT_INTERVAL);
    }

    RecentSnapshots(int capacity, Duration interval) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (interval.isNegative()) {
            throw new IllegalArgumentException("interval must not be negative");
        }
        this.capacity = capacity;
        this.interval = interval;
    }

    void captureIfDue(HeartbeatSample sample, Source source,
            Executor executor) {
        if (!sample.healthy() || sample.pid <= 0L) {
            return;
        }
        Instant requestedAt = Instant.now();
        synchronized (this) {
            if (sample.pid != sessionPid) {
                sessionPid = sample.pid;
                entries.clear();
                nextCapture = Instant.EPOCH;
                capturing = false;
            }
            if (capturing || requestedAt.isBefore(nextCapture)) {
                return;
            }
            capturing = true;
            nextCapture = requestedAt.plus(interval);
        }
        try {
            executor.execute(() -> capture(sample.pid, source));
        } catch (RuntimeException error) {
            captureFailed(sample.pid);
        }
    }

    synchronized List<String> snapshotsFor(long pid) {
        if (pid <= 0L || pid != sessionPid) {
            return List.of();
        }
        List<String> result = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            result.add("Captured before incident " + entry.capturedAt + "\n"
                    + entry.content);
        }
        return result;
    }

    private void capture(long pid, Source source) {
        try {
            String content = source.capture();
            Instant capturedAt = Instant.now();
            synchronized (this) {
                if (pid == sessionPid) {
                    while (entries.size() >= capacity) {
                        entries.removeFirst();
                    }
                    entries.addLast(new Entry(capturedAt, content));
                }
            }
        } catch (IOException | RuntimeException error) {
            captureFailed(pid);
            return;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            captureFailed(pid);
            return;
        }
        synchronized (this) {
            if (pid == sessionPid) {
                capturing = false;
            }
        }
    }

    private synchronized void captureFailed(long pid) {
        if (pid == sessionPid) {
            capturing = false;
            nextCapture = Instant.EPOCH;
        }
    }

    private static final class Entry {
        final Instant capturedAt;
        final String content;

        Entry(Instant capturedAt, String content) {
            this.capturedAt = capturedAt;
            this.content = content;
        }
    }
}
