package nicocache.diagnostics;

/** Pure state machine that debounces failures and suppresses planned stops. */
final class HeartbeatEvaluator {
    private final int missThreshold;
    private long sessionPid = -1L;
    private boolean sessionSeen;
    private boolean incidentLatched;
    private int consecutiveFailures;
    private int healthyAfterIncident;

    HeartbeatEvaluator(int missThreshold) {
        if (missThreshold < 1) {
            throw new IllegalArgumentException("missThreshold must be positive");
        }
        this.missThreshold = missThreshold;
    }

    Decision accept(HeartbeatSample sample, boolean expectedStop) {
        if (expectedStop) {
            consecutiveFailures = 0;
            healthyAfterIncident = 0;
            if (!sample.processAlive) {
                sessionSeen = false;
                sessionPid = -1L;
                incidentLatched = false;
            }
            return Decision.none();
        }
        if (sample.processAlive) {
            if (sample.pid != sessionPid) {
                sessionPid = sample.pid;
                sessionSeen = true;
                incidentLatched = false;
                consecutiveFailures = 0;
                healthyAfterIncident = 0;
            }
            if (sample.healthy()) {
                consecutiveFailures = 0;
                if (incidentLatched && ++healthyAfterIncident >= 3) {
                    incidentLatched = false;
                    healthyAfterIncident = 0;
                }
                return Decision.none();
            }
            if (sample.health == HeartbeatSample.Health.STARTING) {
                consecutiveFailures = 0;
                return Decision.none();
            }
            healthyAfterIncident = 0;
            consecutiveFailures++;
            if (!incidentLatched && consecutiveFailures >= missThreshold) {
                incidentLatched = true;
                return new Decision(true, reason(sample.health), sessionPid);
            }
            return Decision.none();
        }

        consecutiveFailures = 0;
        if (sessionSeen && !incidentLatched) {
            incidentLatched = true;
            return new Decision(true, "process-exited", sessionPid);
        }
        return Decision.none();
    }

    long sessionPid() {
        return sessionPid;
    }

    private static String reason(HeartbeatSample.Health health) {
        switch (health) {
        case CONTROL_UNRESPONSIVE:
            return "control-heartbeat-lost";
        case PROXY_UNRESPONSIVE:
            return "proxy-heartbeat-lost";
        case UNRESPONSIVE:
            return "all-heartbeats-lost";
        default:
            return "heartbeat-failure";
        }
    }

    static final class Decision {
        final boolean incident;
        final String reason;
        final long pid;

        Decision(boolean incident, String reason, long pid) {
            this.incident = incident;
            this.reason = reason;
            this.pid = pid;
        }

        static Decision none() {
            return new Decision(false, "", -1L);
        }
    }
}
