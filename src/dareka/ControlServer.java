package dareka;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dareka.common.Logger;

/**
 * Loopback-only control endpoint used by the independent launcher.
 *
 * <p>The proxy already owns the public HTTP port, so this endpoint has a
 * separate random port and token. It uses only Java SE socket APIs so that
 * the same control path works on Windows, Linux, and macOS.</p>
 */
final class ControlServer implements AutoCloseable {
    static final String STATUS_RELATIVE_PATH = "data/nicocache-control.properties";
    static final String EXPECTED_STOP_RELATIVE_PATH =
            "data/nicocache-expected-stop.properties";
    private static final String HOST = "127.0.0.1";
    private static final int MAX_HEADER_LINES = 64;
    private static final int MAX_HEADER_LENGTH = 16 * 1024;
    private static final int MAX_BODY_LENGTH = 4096;

    private final Path statusPath;
    private final Path expectedStopPath;
    private final Runnable gracefulShutdown;
    private final Runnable forceShutdown;
    private final ExecutorService requestExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<String> state =
            new AtomicReference<>("starting");
    private volatile String problem = "";
    private final String token = createToken();
    private final Instant startedAt = Instant.now();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private int port;

    private ControlServer(Path dataRoot, Runnable gracefulShutdown,
            Runnable forceShutdown) {
        this.statusPath = dataRoot.toAbsolutePath().normalize()
                .resolve(STATUS_RELATIVE_PATH);
        this.expectedStopPath = dataRoot.toAbsolutePath().normalize()
                .resolve(EXPECTED_STOP_RELATIVE_PATH);
        this.gracefulShutdown = gracefulShutdown;
        this.forceShutdown = forceShutdown;
        this.requestExecutor = Executors.newCachedThreadPool(
                new DaemonThreadFactory("nicocache-control-request"));
    }

    static ControlServer start(Path dataRoot, Runnable gracefulShutdown,
            Runnable forceShutdown) throws IOException {
        ControlServer control = new ControlServer(
                dataRoot, gracefulShutdown, forceShutdown);
        control.open();
        return control;
    }

    int getPort() {
        return port;
    }

    private void open() throws IOException {
        int configuredPort = Integer.getInteger("controlPort", 0);
        if (configuredPort < 0 || configuredPort > 65535) {
            throw new IOException("controlPort が不正です: " + configuredPort);
        }

        ServerSocket socket = new ServerSocket();
        try {
            Files.deleteIfExists(expectedStopPath);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(HOST, configuredPort));
            serverSocket = socket;
            port = socket.getLocalPort();
            writeStatus();
        } catch (IOException error) {
            try {
                socket.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            requestExecutor.shutdownNow();
            throw error;
        }

        acceptThread = new Thread(this::acceptLoop, "nicocache-control-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void markReady() throws IOException {
        problem = "";
        state.set("running");
        writeStatus();
    }

    void markDegraded(String reason) throws IOException {
        problem = reason == null ? "unknown" : reason;
        state.set("degraded");
        writeStatus();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket client = serverSocket.accept();
                requestExecutor.execute(() -> handle(client));
            } catch (SocketException error) {
                if (!closed.get()) {
                    Logger.warning("管理APIの受付に失敗しました: " + error);
                }
                return;
            } catch (IOException error) {
                if (!closed.get()) {
                    Logger.error(error);
                }
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.length() > MAX_HEADER_LENGTH) {
                writeResponse(socket, 400, "invalid request");
                return;
            }
            String[] requestParts = requestLine.split(" ", 3);
            if (requestParts.length != 3) {
                writeResponse(socket, 400, "invalid request");
                return;
            }

            Map<String, String> headers = new HashMap<>();
            int contentLength = 0;
            boolean headerEnded = false;
            for (int lineCount = 0; lineCount < MAX_HEADER_LINES; lineCount++) {
                String line = reader.readLine();
                if (line == null || line.length() > MAX_HEADER_LENGTH) {
                    writeResponse(socket, 400, "invalid headers");
                    return;
                }
                if (line.isEmpty()) {
                    headerEnded = true;
                    break;
                }
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    writeResponse(socket, 400, "invalid headers");
                    return;
                }
                String name = line.substring(0, separator).trim()
                        .toLowerCase(java.util.Locale.ROOT);
                String value = line.substring(separator + 1).trim();
                headers.put(name, value);
                if ("content-length".equals(name)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException error) {
                        writeResponse(socket, 400, "invalid content length");
                        return;
                    }
                    if (contentLength < 0 || contentLength > MAX_BODY_LENGTH) {
                        writeResponse(socket, 413, "request body too large");
                        return;
                    }
                }
            }
            if (!headerEnded) {
                writeResponse(socket, 400, "too many headers");
                return;
            }
            for (int read = 0; read < contentLength; read++) {
                if (reader.read() < 0) {
                    writeResponse(socket, 400, "incomplete request body");
                    return;
                }
            }

            if (!authorized(headers.get("authorization"))) {
                writeResponse(socket, 401, "unauthorized");
                return;
            }

            String method = requestParts[0];
            String target = requestParts[1];
            int queryStart = target.indexOf('?');
            String path = queryStart < 0
                    ? target
                    : target.substring(0, queryStart);
            if ("GET".equals(method) && "/api/control/status".equals(path)) {
                writeResponse(socket, 200, statusJson());
                return;
            }
            if ("GET".equals(method) && "/api/control/ping".equals(path)) {
                writeResponse(socket, 200, "{\"status\":\"ok\"}");
                return;
            }
            if ("GET".equals(method)
                    && "/api/control/diagnostics/snapshot".equals(path)) {
                writeResponse(socket, 200,
                        DiagnosticSnapshot.capture(state.get(), problem));
                return;
            }
            if ("POST".equals(method)
                    && ("/api/control/shutdown".equals(path)
                    || "/api/control/graceful-shutdown".equals(path))) {
                requestShutdown(gracefulShutdown, false);
                writeResponse(socket, 202, "{\"status\":\"stopping\"}");
                return;
            }
            if ("POST".equals(method)
                    && ("/api/control/force".equals(path)
                    || "/api/control/force-shutdown".equals(path))) {
                requestShutdown(forceShutdown, true);
                writeResponse(socket, 202, "{\"status\":\"forcing\"}");
                return;
            }
            writeResponse(socket, 404, "not found");
        } catch (IOException error) {
            if (!closed.get()) {
                Logger.debugWithThread(error);
            }
        } catch (RuntimeException error) {
            Logger.error(error);
        }
    }

    private void requestShutdown(Runnable action, boolean force) {
        if (closed.get()) {
            return;
        }
        if (!force) {
            boolean accepted = state.compareAndSet("running", "stopping")
                    || state.compareAndSet("degraded", "stopping")
                    || state.compareAndSet("starting", "stopping");
            if (!accepted) {
                return;
            }
        }
        if (force) {
            state.set("stopping");
        }
        markExpectedStop(force ? "force" : "graceful");
        try {
            writeStatus();
        } catch (IOException error) {
            Logger.error(error);
        }
        Thread shutdown = new Thread(() -> {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            action.run();
        }, "nicocache-control-shutdown");
        shutdown.setDaemon(true);
        shutdown.start();
    }

    void markExpectedStop(String mode) {
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("pid",
                Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("requestedAt", Instant.now().toString());
        properties.setProperty("mode", mode == null ? "graceful" : mode);
        try {
            writeProperties(expectedStopPath, properties,
                    "NicoCache_nl expected stop marker");
        } catch (IOException error) {
            Logger.warning("正常終了マーカーを書き込めませんでした: " + error);
        }
    }

    private boolean authorized(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] actual = authorization.substring("Bearer ".length())
                .getBytes(StandardCharsets.US_ASCII);
        byte[] expected = token.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }

    private String statusJson() {
        return "{\"status\":\"" + jsonEscape(state.get())
                + "\",\"pid\":" + ProcessHandle.current().pid()
                + ",\"port\":" + port
                + ",\"proxyPort\":" + Integer.getInteger("listenPort", 8080)
                + ",\"problem\":\"" + jsonEscape(problem)
                + "\",\"version\":\"" + jsonEscape(Main.getVersion())
                + "\"}";
    }

    private void writeResponse(Socket socket, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = statusReason(status);
        OutputStream output = socket.getOutputStream();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                output, StandardCharsets.US_ASCII));
        writer.write("HTTP/1.1 " + status + " " + reason + "\r\n");
        writer.write("Content-Type: application/json; charset=utf-8\r\n");
        writer.write("Content-Length: " + bytes.length + "\r\n");
        writer.write("Connection: close\r\n\r\n");
        writer.flush();
        output.write(bytes);
        output.flush();
    }

    private static String statusReason(int status) {
        switch (status) {
        case 200:
            return "OK";
        case 202:
            return "Accepted";
        case 400:
            return "Bad Request";
        case 401:
            return "Unauthorized";
        case 404:
            return "Not Found";
        case 413:
            return "Payload Too Large";
        default:
            return "Error";
        }
    }

    private void writeStatus() throws IOException {
        Path parent = statusPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("host", HOST);
        properties.setProperty("port", Integer.toString(port));
        properties.setProperty("proxyPort",
                Integer.toString(Integer.getInteger("listenPort", 8080)));
        properties.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("token", token);
        properties.setProperty("state", state.get());
        if (!problem.isBlank()) {
            properties.setProperty("problem", problem);
        }
        properties.setProperty("startedAt", startedAt.toString());
        writeProperties(statusPath, properties,
                "NicoCache_nl local control endpoint");
    }

    private void writeProperties(Path destination, Properties properties,
            String comment) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".tmp");
        try (var output = Files.newOutputStream(temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, comment);
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        restrictFile(destination);
    }

    private void restrictFile(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows has no POSIX permission view. The endpoint is still
            // loopback-only and the status file stays in the user data root.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state.set("stopped");
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException error) {
            Logger.debugWithThread(error);
        }
        requestExecutor.shutdownNow();
        try {
            Files.deleteIfExists(statusPath);
        } catch (IOException error) {
            Logger.warning("管理APIの状態ファイルを削除できませんでした: "
                    + statusPath);
        }
    }

    private static String createToken() {
        byte[] bytes = new byte[32];
        try {
            java.security.SecureRandom.getInstanceStrong().nextBytes(bytes);
        } catch (NoSuchAlgorithmException error) {
            new java.security.SecureRandom().nextBytes(bytes);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || character == '"') {
                escaped.append('\\').append(character);
            } else if (character < 0x20) {
                escaped.append(String.format("\\u%04x", (int) character));
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String name;

        DaemonThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
