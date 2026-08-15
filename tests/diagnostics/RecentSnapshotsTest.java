package nicocache.diagnostics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class RecentSnapshotsTest {
    private RecentSnapshotsTest() { }

    public static void main(String[] args) {
        RecentSnapshots snapshots = new RecentSnapshots(3, Duration.ZERO);
        HeartbeatSample healthy = healthy(101L);
        snapshots.captureIfDue(healthy, () -> "snapshot-one",
                Runnable::run);
        snapshots.captureIfDue(healthy, () -> "snapshot-two",
                Runnable::run);
        snapshots.captureIfDue(healthy, () -> "snapshot-three",
                Runnable::run);
        snapshots.captureIfDue(healthy, () -> "snapshot-four",
                Runnable::run);

        List<String> retained = snapshots.snapshotsFor(101L);
        assertEquals(3, retained.size(), "snapshot capacity");
        assertNotContains(retained, "snapshot-one",
                "oldest snapshot must be evicted");
        assertContains(retained, "snapshot-two",
                "second snapshot must remain");
        assertContains(retained, "snapshot-four",
                "newest snapshot must remain");

        snapshots.captureIfDue(healthy(202L), () -> "new-session",
                Runnable::run);
        assertTrue(snapshots.snapshotsFor(101L).isEmpty(),
                "old PID snapshots must not leak into a new session");
        assertContains(snapshots.snapshotsFor(202L), "new-session",
                "new PID snapshot must be retained");

        RecentSnapshots retry = new RecentSnapshots(1, Duration.ofDays(1));
        retry.captureIfDue(healthy, () -> {
            throw new java.io.IOException("temporary failure");
        }, Runnable::run);
        retry.captureIfDue(healthy, () -> "retry-succeeded", Runnable::run);
        assertContains(retry.snapshotsFor(101L), "retry-succeeded",
                "failed capture must be retried on the next heartbeat");
        System.out.println("Recent snapshot tests passed");
    }

    private static HeartbeatSample healthy(long pid) {
        return new HeartbeatSample(Instant.now(), pid, "running", true,
                true, true, 1L, 1L, "", HeartbeatSample.Health.HEALTHY);
    }

    private static void assertContains(List<String> values, String expected,
            String message) {
        for (String value : values) {
            if (value.contains(expected)) {
                return;
            }
        }
        throw new AssertionError(message + ": expected=" + expected);
    }

    private static void assertNotContains(List<String> values,
            String unexpected, String message) {
        for (String value : values) {
            if (value.contains(unexpected)) {
                throw new AssertionError(message + ": unexpected="
                        + unexpected);
            }
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
