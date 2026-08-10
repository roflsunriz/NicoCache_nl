package nicocache.diagnostics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory sanitized report model; secrets are never written as raw files. */
final class IncidentReport {
    final String reason;
    final Instant capturedAt;
    final long pid;
    final List<HeartbeatSample> timeline;
    final Map<String, String> environment = new LinkedHashMap<>();
    final Map<String, String> files = new LinkedHashMap<>();
    final Map<String, String> configuration = new LinkedHashMap<>();
    final Map<String, String> logs = new LinkedHashMap<>();
    final List<String> snapshots = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    Map<String, Integer> redactionCounts = Map.of();

    IncidentReport(String reason, Instant capturedAt, long pid,
            List<HeartbeatSample> timeline) {
        this.reason = reason;
        this.capturedAt = capturedAt;
        this.pid = pid;
        this.timeline = new ArrayList<>(timeline);
    }
}
