package nicocache.diagnostics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

/** Authenticated control and real proxy-path probes. */
final class CoreProbe {
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(800);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(1200);
    private final DiagnosticsPaths paths;
    private final HttpClient httpClient;

    CoreProbe(DiagnosticsPaths paths) {
        this.paths = paths;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT).build();
    }

    HeartbeatSample probe() {
        Instant capturedAt = Instant.now();
        Properties status;
        try {
            status = readControlStatus();
        } catch (IOException error) {
            return stopped(capturedAt, safeMessage(error));
        }
        long pid = parseLong(status.getProperty("pid"), -1L);
        boolean processAlive = pid > 0L && ProcessHandle.of(pid)
                .map(ProcessHandle::isAlive).orElse(false);
        String coreState = status.getProperty("state", "unknown");
        if (!processAlive) {
            return new HeartbeatSample(capturedAt, pid, coreState, false,
                    false, false, -1L, -1L, "process-not-alive",
                    HeartbeatSample.Health.STOPPED);
        }

        Probe control = request(status, "/api/control/ping");
        Probe proxy = proxyHeartbeat(status);
        HeartbeatSample.Health health;
        if (control.ok && proxy.ok && "running".equals(coreState)) {
            health = HeartbeatSample.Health.HEALTHY;
        } else if (control.ok && proxy.ok) {
            health = HeartbeatSample.Health.STARTING;
        } else if (!control.ok && !proxy.ok) {
            health = HeartbeatSample.Health.UNRESPONSIVE;
        } else if (!control.ok) {
            health = HeartbeatSample.Health.CONTROL_UNRESPONSIVE;
        } else {
            health = HeartbeatSample.Health.PROXY_UNRESPONSIVE;
        }
        return new HeartbeatSample(capturedAt, pid, coreState, true,
                control.ok, proxy.ok, control.elapsedMillis,
                proxy.elapsedMillis, joinDetail(control.detail, proxy.detail),
                health);
    }

    String diagnosticSnapshot() throws IOException, InterruptedException {
        Properties status = readControlStatus();
        HttpResponse<String> response = send(status,
                "/api/control/diagnostics/snapshot", Duration.ofSeconds(8));
        if (response.statusCode() != 200) {
            throw new IOException("diagnostic snapshot HTTP "
                    + response.statusCode());
        }
        return response.body();
    }

    boolean requestGracefulShutdown() throws IOException, InterruptedException {
        if (!Files.isRegularFile(paths.controlStatus())) {
            return false;
        }
        Properties status = readControlStatus();
        long pid = parseLong(status.getProperty("pid"), -1L);
        if (pid <= 0L || !ProcessHandle.of(pid)
                .map(ProcessHandle::isAlive).orElse(false)) {
            return false;
        }
        HttpResponse<String> response = send(status,
                "/api/control/graceful-shutdown", Duration.ofSeconds(5),
                true);
        if (response.statusCode() != 200 && response.statusCode() != 202) {
            throw new IOException("graceful shutdown HTTP "
                    + response.statusCode());
        }
        return true;
    }

    boolean expectedStop(long pid) {
        if (pid <= 0L || !Files.isRegularFile(paths.expectedStop())) {
            return false;
        }
        Properties marker = new Properties();
        try (var input = Files.newInputStream(paths.expectedStop())) {
            marker.load(input);
            if (parseLong(marker.getProperty("pid"), -1L) != pid) {
                return false;
            }
            Instant requested = Instant.parse(marker.getProperty("requestedAt"));
            return requested.isAfter(Instant.now().minus(Duration.ofMinutes(5)));
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    private Properties readControlStatus() throws IOException {
        if (!Files.isRegularFile(paths.controlStatus())) {
            throw new IOException("control-status-missing");
        }
        Properties status = new Properties();
        try (var input = Files.newInputStream(paths.controlStatus())) {
            status.load(input);
        }
        for (String required : new String[] { "host", "port", "token", "pid" }) {
            String value = status.getProperty(required);
            if (value == null || value.isBlank()) {
                throw new IOException("control-status-invalid:" + required);
            }
        }
        if (!"127.0.0.1".equals(status.getProperty("host"))) {
            throw new IOException("control-host-not-loopback");
        }
        return status;
    }

    private Probe request(Properties status, String endpoint) {
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = send(status, endpoint,
                    REQUEST_TIMEOUT, false);
            boolean ok = response.statusCode() == 200;
            return new Probe(ok, elapsed(started), ok ? "" : "control-http-"
                    + response.statusCode());
        } catch (IOException | InterruptedException | RuntimeException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Probe(false, elapsed(started),
                    "control:" + safeMessage(error));
        }
    }

    private HttpResponse<String> send(Properties status, String endpoint,
            Duration timeout) throws IOException, InterruptedException {
        return send(status, endpoint, timeout, false);
    }

    private HttpResponse<String> send(Properties status, String endpoint,
            Duration timeout, boolean post)
            throws IOException, InterruptedException {
        int port = parsePort(status.getProperty("port"));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + endpoint))
                .timeout(timeout)
                .header("Authorization", "Bearer "
                        + status.getProperty("token"));
        HttpRequest request = post
                ? builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                : builder.GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(
                StandardCharsets.UTF_8));
    }

    private Probe proxyHeartbeat(Properties status) {
        long started = System.nanoTime();
        int port;
        try {
            port = parsePort(status.getProperty("proxyPort", "8080"));
        } catch (IOException error) {
            return new Probe(false, elapsed(started), "proxy-port-invalid");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            socket.setSoTimeout(1200);
            String request = "GET http://DEBUG/debug/heartbeat HTTP/1.1\r\n"
                    + "Host: DEBUG\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(
                    request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(),
                            StandardCharsets.US_ASCII))) {
                String statusLine = reader.readLine();
                boolean ok = statusLine != null
                        && statusLine.startsWith("HTTP/1.1 200");
                return new Probe(ok, elapsed(started),
                        ok ? "" : "proxy-http-invalid");
            }
        } catch (IOException error) {
            return new Probe(false, elapsed(started),
                    "proxy:" + safeMessage(error));
        }
    }

    private static HeartbeatSample stopped(Instant capturedAt, String detail) {
        return new HeartbeatSample(capturedAt, -1L, "stopped", false,
                false, false, -1L, -1L, detail,
                HeartbeatSample.Health.STOPPED);
    }

    private static int parsePort(String value) throws IOException {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException(value);
            }
            return port;
        } catch (NumberFormatException error) {
            throw new IOException("invalid-port", error);
        }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { return fallback; }
    }

    private static long elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private static String joinDetail(String first, String second) {
        if (first == null || first.isBlank()) { return second == null ? "" : second; }
        if (second == null || second.isBlank()) { return first; }
        return first + "; " + second;
    }

    private static final class Probe {
        final boolean ok;
        final long elapsedMillis;
        final String detail;
        Probe(boolean ok, long elapsedMillis, String detail) {
            this.ok = ok;
            this.elapsedMillis = elapsedMillis;
            this.detail = detail;
        }
    }
}
