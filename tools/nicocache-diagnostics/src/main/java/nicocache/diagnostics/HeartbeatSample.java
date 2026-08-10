package nicocache.diagnostics;

import java.time.Instant;

/** One bounded monitoring observation retained for the incident timeline. */
final class HeartbeatSample {
    enum Health { STOPPED, STARTING, HEALTHY, CONTROL_UNRESPONSIVE,
        PROXY_UNRESPONSIVE, UNRESPONSIVE }

    final Instant capturedAt;
    final long pid;
    final String coreState;
    final boolean processAlive;
    final boolean controlAlive;
    final boolean proxyAlive;
    final long controlMillis;
    final long proxyMillis;
    final String detail;
    final Health health;

    HeartbeatSample(Instant capturedAt, long pid, String coreState,
            boolean processAlive, boolean controlAlive, boolean proxyAlive,
            long controlMillis, long proxyMillis, String detail,
            Health health) {
        this.capturedAt = capturedAt;
        this.pid = pid;
        this.coreState = coreState;
        this.processAlive = processAlive;
        this.controlAlive = controlAlive;
        this.proxyAlive = proxyAlive;
        this.controlMillis = controlMillis;
        this.proxyMillis = proxyMillis;
        this.detail = detail;
        this.health = health;
    }

    boolean healthy() { return health == Health.HEALTHY; }
}
