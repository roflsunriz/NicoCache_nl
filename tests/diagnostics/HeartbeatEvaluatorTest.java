package nicocache.diagnostics;

import java.time.Instant;

public final class HeartbeatEvaluatorTest {
    private HeartbeatEvaluatorTest() { }

    public static void main(String[] args) {
        HeartbeatEvaluator evaluator = new HeartbeatEvaluator(3);
        assertFalse(evaluator.accept(stopped(), false).incident,
                "initial stopped state is not an incident");
        assertFalse(evaluator.accept(healthy(101L), false).incident,
                "healthy session starts monitoring");
        assertFalse(evaluator.accept(proxyFailure(101L), false).incident,
                "first miss is debounced");
        assertFalse(evaluator.accept(proxyFailure(101L), false).incident,
                "second miss is debounced");
        HeartbeatEvaluator.Decision failure = evaluator.accept(
                proxyFailure(101L), false);
        assertTrue(failure.incident, "third miss creates an incident");
        assertEquals("proxy-heartbeat-lost", failure.reason,
                "proxy failure reason");
        assertFalse(evaluator.accept(proxyFailure(101L), false).incident,
                "one incident is generated per failure episode");
        evaluator.accept(healthy(101L), false);
        evaluator.accept(healthy(101L), false);
        evaluator.accept(healthy(101L), false);
        evaluator.accept(controlFailure(101L), false);
        evaluator.accept(controlFailure(101L), false);
        assertTrue(evaluator.accept(controlFailure(101L), false).incident,
                "recovered session can generate a later incident");

        HeartbeatEvaluator planned = new HeartbeatEvaluator(3);
        planned.accept(healthy(202L), false);
        assertFalse(planned.accept(proxyFailure(202L), true).incident,
                "planned stop suppresses heartbeat loss while exiting");
        assertFalse(planned.accept(proxyFailure(202L), true).incident,
                "planned slow shutdown remains suppressed");
        assertFalse(planned.accept(proxyFailure(202L), true).incident,
                "planned stop cannot cross the failure threshold");
        assertFalse(planned.accept(stopped(), true).incident,
                "planned stop must not generate a report");

        HeartbeatEvaluator crash = new HeartbeatEvaluator(3);
        crash.accept(healthy(303L), false);
        HeartbeatEvaluator.Decision exit = crash.accept(stopped(), false);
        assertTrue(exit.incident, "unexpected exit creates an incident");
        assertEquals("process-exited", exit.reason, "exit reason");
        System.out.println("Heartbeat evaluator tests passed");
    }

    private static HeartbeatSample healthy(long pid) {
        return sample(pid, true, true, true, HeartbeatSample.Health.HEALTHY);
    }
    private static HeartbeatSample proxyFailure(long pid) {
        return sample(pid, true, true, false,
                HeartbeatSample.Health.PROXY_UNRESPONSIVE);
    }
    private static HeartbeatSample controlFailure(long pid) {
        return sample(pid, true, false, true,
                HeartbeatSample.Health.CONTROL_UNRESPONSIVE);
    }
    private static HeartbeatSample stopped() {
        return sample(-1L, false, false, false, HeartbeatSample.Health.STOPPED);
    }
    private static HeartbeatSample sample(long pid, boolean process,
            boolean control, boolean proxy, HeartbeatSample.Health health) {
        return new HeartbeatSample(Instant.now(), pid,
                process ? "running" : "stopped", process, control, proxy,
                1L, 1L, "", health);
    }
    private static void assertTrue(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
    private static void assertFalse(boolean value, String message) {
        if (value) { throw new AssertionError(message); }
    }
    private static void assertEquals(Object expected, Object actual,
            String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }
}
