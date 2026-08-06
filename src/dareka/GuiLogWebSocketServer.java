package dareka;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** loopback上でGUIログを配信する最小限のRFC 6455サーバー。 */
final class GuiLogWebSocketServer implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final String PATH = "/api/gui/logs";
    private static final String WEBSOCKET_GUID =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_CLIENT_FRAME_BYTES = 64 * 1024;
    private static final int MAX_MESSAGE_CHARS = 240 * 1024;
    private static final int DEFAULT_QUEUE_CAPACITY = 8192;
    private static final int DEFAULT_BATCH_SIZE = 256;
    private static final long BATCH_DELAY_MILLIS = 16L;

    private final Object queueLock = new Object();
    private final ArrayDeque<GuiLogEvent> queue = new ArrayDeque<>();
    private final int queueCapacity;
    private final int batchSize;
    private final String token = createToken();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<ClientConnection> client =
            new AtomicReference<>();
    private final ExecutorService connectionExecutor =
            Executors.newCachedThreadPool(new DaemonThreadFactory());
    private long droppedEvents;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread senderThread;

    private GuiLogWebSocketServer() {
        queueCapacity = boundedIntegerProperty(
                "guiLogQueueCapacity", DEFAULT_QUEUE_CAPACITY, 256, 100000);
        batchSize = boundedIntegerProperty(
                "guiLogBatchSize", DEFAULT_BATCH_SIZE, 1, 4096);
    }

    static GuiLogWebSocketServer start() throws IOException {
        GuiLogWebSocketServer server = new GuiLogWebSocketServer();
        server.open();
        return server;
    }

    URI getEndpoint() {
        return URI.create("ws://" + HOST + ":"
                + serverSocket.getLocalPort() + PATH);
    }

    String getToken() {
        return token;
    }

    void publish(String channel, String message) {
        if (closed.get()) {
            return;
        }
        String safeMessage = String.valueOf(message);
        if (safeMessage.length() > MAX_MESSAGE_CHARS) {
            int end = MAX_MESSAGE_CHARS;
            if (Character.isHighSurrogate(safeMessage.charAt(end - 1))) {
                end--;
            }
            safeMessage = safeMessage.substring(0, end)
                    + "\n[GUI表示用ログを上限で省略しました]";
        }
        synchronized (queueLock) {
            if (queue.size() >= queueCapacity) {
                queue.removeFirst();
                droppedEvents++;
            }
            queue.addLast(new GuiLogEvent(channel, safeMessage));
            queueLock.notifyAll();
        }
    }

    int pendingEventCount() {
        synchronized (queueLock) {
            return queue.size();
        }
    }

    private void open() throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(HOST, 0));
            serverSocket = socket;
        } catch (IOException error) {
            try {
                socket.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            connectionExecutor.shutdownNow();
            throw error;
        }
        acceptThread = daemonThread(
                this::acceptLoop, "nicocache-gui-log-ws-accept");
        senderThread = daemonThread(
                this::senderLoop, "nicocache-gui-log-ws-send");
        acceptThread.start();
        senderThread.start();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = serverSocket.accept();
                connectionExecutor.execute(() -> upgrade(socket));
            } catch (SocketException error) {
                if (!closed.get()) {
                    report("GUIログWebSocketの受付に失敗しました", error);
                }
                return;
            } catch (IOException error) {
                if (!closed.get()) {
                    report("GUIログWebSocketの受付に失敗しました", error);
                }
            }
        }
    }

    private void upgrade(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            InputStream input = socket.getInputStream();
            byte[] headerBytes = readHttpHeader(input);
            Handshake request = Handshake.parse(headerBytes);
            if (!request.isValid(token)) {
                writeHttpError(socket, 401, "Unauthorized");
                socket.close();
                return;
            }
            String accept = websocketAccept(request.key);
            OutputStream output = socket.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            socket.setSoTimeout(0);
            ClientConnection connection = new ClientConnection(socket);
            ClientConnection previous = client.getAndSet(connection);
            if (previous != null) {
                previous.close();
            }
            synchronized (queueLock) {
                queueLock.notifyAll();
            }
            connection.readFrames();
            client.compareAndSet(connection, null);
            connection.close();
        } catch (IOException | RuntimeException error) {
            closeQuietly(socket);
            if (!closed.get()) {
                report("GUIログWebSocket接続を終了しました", error);
            }
        }
    }

    private void senderLoop() {
        while (!closed.get()) {
            List<GuiLogEvent> batch;
            try {
                batch = takeBatch();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            if (batch.isEmpty()) {
                continue;
            }
            ClientConnection destination = client.get();
            if (destination == null) {
                requeue(batch);
                continue;
            }
            try {
                destination.sendBinary(GuiLogBatchCodec.encode(batch));
            } catch (IOException error) {
                client.compareAndSet(destination, null);
                destination.close();
                requeue(batch);
            }
        }
    }

    private List<GuiLogEvent> takeBatch() throws InterruptedException {
        synchronized (queueLock) {
            while (!closed.get() && (client.get() == null || queue.isEmpty())) {
                queueLock.wait();
            }
            if (closed.get()) {
                return List.of();
            }
            if (queue.size() < batchSize) {
                queueLock.wait(BATCH_DELAY_MILLIS);
            }
            List<GuiLogEvent> batch = new ArrayList<>(batchSize + 1);
            int encodedBytes = Integer.BYTES + 1 + Integer.BYTES;
            if (droppedEvents > 0) {
                GuiLogEvent dropped = new GuiLogEvent("main",
                        "GUIログ配送の上限を超えたため " + droppedEvents
                        + " 件を省略しました。");
                batch.add(dropped);
                encodedBytes += GuiLogBatchCodec.encodedEventSize(dropped);
                droppedEvents = 0;
            }
            while (!queue.isEmpty() && batch.size() < batchSize) {
                GuiLogEvent next = queue.peekFirst();
                int eventBytes = GuiLogBatchCodec.encodedEventSize(next);
                if (!batch.isEmpty()
                        && encodedBytes + eventBytes
                                > GuiLogBatchCodec.MAX_BATCH_BYTES) {
                    break;
                }
                batch.add(queue.removeFirst());
                encodedBytes += eventBytes;
            }
            return batch;
        }
    }

    private void requeue(List<GuiLogEvent> batch) {
        synchronized (queueLock) {
            for (int index = batch.size() - 1; index >= 0; index--) {
                if (queue.size() >= queueCapacity) {
                    queue.removeLast();
                    droppedEvents++;
                }
                queue.addFirst(batch.get(index));
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (queueLock) {
            queueLock.notifyAll();
        }
        closeQuietly(serverSocket);
        ClientConnection connection = client.getAndSet(null);
        if (connection != null) {
            connection.close();
        }
        connectionExecutor.shutdownNow();
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        if (senderThread != null) {
            senderThread.interrupt();
        }
    }

    private static byte[] readHttpHeader(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        while (output.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("WebSocket handshake was truncated");
            }
            output.write(value);
            int expected = matched == 0 || matched == 2 ? '\r' : '\n';
            if (value == expected) {
                matched++;
                if (matched == 4) {
                    return output.toByteArray();
                }
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        throw new IOException("WebSocket handshake header is too large");
    }

    private static String websocketAccept(String key) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WEBSOCKET_GUID)
                    .getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-1 is unavailable", error);
        }
    }

    private static void writeHttpError(Socket socket, int status, String reason)
            throws IOException {
        byte[] body = reason.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        socket.getOutputStream().write(
                header.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    private static String createToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean containsToken(String value, String token) {
        if (value == null) {
            return false;
        }
        for (String part : value.split(",")) {
            if (token.equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private static int boundedIntegerProperty(String name, int defaultValue,
            int minimum, int maximum) {
        int value = Integer.getInteger(name, defaultValue);
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Thread daemonThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 終了処理では既に閉じられたソケットを許容する。
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 終了処理では既に閉じられたソケットを許容する。
            }
        }
    }

    private static void report(String message, Throwable error) {
        if (Boolean.getBoolean("dareka.debug")) {
            System.err.println(message + ": " + error);
        }
    }

    private final class ClientConnection implements AutoCloseable {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private final Object outputLock = new Object();
        private final AtomicBoolean connectionClosed = new AtomicBoolean();

        ClientConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
        }

        void sendBinary(byte[] payload) throws IOException {
            synchronized (outputLock) {
                writeFrame(output, 0x2, payload);
            }
        }

        void readFrames() throws IOException {
            while (!closed.get() && !connectionClosed.get()) {
                int first = input.read();
                if (first < 0) {
                    return;
                }
                int second = readRequired(input);
                boolean finalFrame = (first & 0x80) != 0;
                boolean reservedBitsSet = (first & 0x70) != 0;
                int opcode = first & 0x0f;
                boolean masked = (second & 0x80) != 0;
                long length = second & 0x7f;
                if (length == 126) {
                    length = (readRequired(input) << 8) | readRequired(input);
                } else if (length == 127) {
                    length = 0;
                    for (int index = 0; index < 8; index++) {
                        length = (length << 8) | readRequired(input);
                    }
                }
                boolean control = opcode >= 0x8;
                if (reservedBitsSet || !masked || length < 0
                        || length > MAX_CLIENT_FRAME_BYTES
                        || (control && (!finalFrame || length > 125))) {
                    sendClose(1002);
                    return;
                }
                byte[] mask = readBytes(input, 4);
                byte[] payload = readBytes(input, (int) length);
                for (int index = 0; index < payload.length; index++) {
                    payload[index] ^= mask[index % 4];
                }
                if (opcode == 0x8) {
                    sendClose(1000);
                    return;
                }
                if (opcode == 0x9) {
                    synchronized (outputLock) {
                        writeFrame(output, 0xA, payload);
                    }
                } else if (opcode != 0x0 && opcode != 0x1 && opcode != 0x2
                        && opcode != 0xA) {
                    sendClose(1002);
                    return;
                }
            }
        }

        private void sendClose(int status) throws IOException {
            byte[] payload = {
                (byte) ((status >>> 8) & 0xff), (byte) (status & 0xff)
            };
            synchronized (outputLock) {
                writeFrame(output, 0x8, payload);
            }
        }

        @Override
        public void close() {
            if (connectionClosed.compareAndSet(false, true)) {
                closeQuietly(socket);
            }
        }
    }

    private static void writeFrame(OutputStream output, int opcode,
            byte[] payload) throws IOException {
        output.write(0x80 | opcode);
        if (payload.length <= 125) {
            output.write(payload.length);
        } else if (payload.length <= 0xffff) {
            output.write(126);
            output.write((payload.length >>> 8) & 0xff);
            output.write(payload.length & 0xff);
        } else {
            output.write(127);
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                output.write((int) (length >>> shift) & 0xff);
            }
        }
        output.write(payload);
        output.flush();
    }

    private static int readRequired(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("WebSocket frame was truncated");
        }
        return value;
    }

    private static byte[] readBytes(InputStream input, int length)
            throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) {
                throw new EOFException("WebSocket frame was truncated");
            }
            offset += read;
        }
        return bytes;
    }

    private static final class Handshake {
        private final String method;
        private final String path;
        private final Map<String, String> headers;
        private final String key;

        private Handshake(String method, String path,
                Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.key = headers.get("sec-websocket-key");
        }

        static Handshake parse(byte[] request) throws IOException {
            String text = new String(request, StandardCharsets.US_ASCII);
            String[] lines = text.split("\\r\\n");
            if (lines.length == 0) {
                throw new IOException("WebSocket request line is missing");
            }
            String[] requestParts = lines[0].split(" ", 3);
            if (requestParts.length != 3) {
                throw new IOException("WebSocket request line is invalid");
            }
            Map<String, String> headers = new HashMap<>();
            for (int index = 1; index < lines.length; index++) {
                int separator = lines[index].indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                String name = lines[index].substring(0, separator).trim()
                        .toLowerCase(Locale.ROOT);
                String value = lines[index].substring(separator + 1).trim();
                headers.merge(name, value, (first, second) ->
                        first + "," + second);
            }
            return new Handshake(requestParts[0], requestParts[1], headers);
        }

        boolean isValid(String expectedToken) {
            if (!"GET".equals(method) || !PATH.equals(path)
                    || !"13".equals(headers.get("sec-websocket-version"))
                    || !containsToken(headers.get("upgrade"), "websocket")
                    || !containsToken(headers.get("connection"), "upgrade")
                    || key == null || !validKey(key)) {
                return false;
            }
            String authorization = headers.get("authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return false;
            }
            return MessageDigest.isEqual(
                    authorization.substring("Bearer ".length())
                            .getBytes(StandardCharsets.US_ASCII),
                    expectedToken.getBytes(StandardCharsets.US_ASCII));
        }

        private static boolean validKey(String value) {
            try {
                return Base64.getDecoder().decode(value).length == 16;
            } catch (IllegalArgumentException error) {
                return false;
            }
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            return daemonThread(task, "nicocache-gui-log-ws-client");
        }
    }
}
