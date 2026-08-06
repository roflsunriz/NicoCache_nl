package dareka;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** JDK WebSocketクライアントでGUIログのバッチを受信する。 */
final class GuiLogWebSocketClient implements AutoCloseable {
    interface BatchListener {
        void onBatch(List<GuiLogEvent> events);
    }

    private static final int MAX_FRAGMENTED_MESSAGE_BYTES = 4 * 1024 * 1024;
    private static final long RECONNECT_DELAY_MILLIS = 250L;

    private final URI endpoint;
    private final String token;
    private final BatchListener listener;
    private final ExecutorService httpExecutor =
            Executors.newCachedThreadPool(new DaemonThreadFactory(
                    "nicocache-gui-log-ws-http"));
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory(
                    "nicocache-gui-log-ws-reconnect"));
    private final HttpClient httpClient;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();

    private GuiLogWebSocketClient(
            URI endpoint, String token, BatchListener listener) {
        this.endpoint = endpoint;
        this.token = token;
        this.listener = listener;
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(httpExecutor)
                .proxy(new DirectProxySelector())
                .build();
    }

    static GuiLogWebSocketClient connect(
            URI endpoint, String token, BatchListener listener) {
        GuiLogWebSocketClient client = new GuiLogWebSocketClient(
                endpoint, token, listener);
        client.connect();
        return client;
    }

    private void connect() {
        if (closed.get() || !connecting.compareAndSet(false, true)) {
            return;
        }
        IncomingListener incoming = new IncomingListener();
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .header("Authorization", "Bearer " + token)
                .buildAsync(endpoint, incoming)
                .whenComplete((webSocket, error) -> {
                    connecting.set(false);
                    if (closed.get()) {
                        if (webSocket != null) {
                            webSocket.abort();
                        }
                        return;
                    }
                    if (error != null) {
                        scheduleReconnect();
                        return;
                    }
                    incoming.attach(webSocket);
                    WebSocket previous = socket.getAndSet(webSocket);
                    if (previous != null && previous != webSocket) {
                        previous.abort();
                    }
                });
    }

    private void disconnected(WebSocket disconnected) {
        socket.compareAndSet(disconnected, null);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!closed.get()) {
            reconnectExecutor.schedule(
                    (Runnable) this::connect, RECONNECT_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        WebSocket webSocket = socket.getAndSet(null);
        if (webSocket != null) {
            webSocket.sendClose(
                    WebSocket.NORMAL_CLOSURE, "NicoCacheGUI closing");
        }
        reconnectExecutor.shutdownNow();
        httpExecutor.shutdownNow();
    }

    private final class IncomingListener implements WebSocket.Listener {
        private final ByteArrayOutputStream fragments =
                new ByteArrayOutputStream();
        private volatile WebSocket attached;

        void attach(WebSocket webSocket) {
            attached = webSocket;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            attached = webSocket;
            webSocket.request(1);
        }

        @Override
        public synchronized CompletionStage<?> onBinary(
                WebSocket webSocket, ByteBuffer data, boolean last) {
            try {
                if (fragments.size() + data.remaining()
                        > MAX_FRAGMENTED_MESSAGE_BYTES) {
                    fragments.reset();
                    return webSocket.sendClose(
                            1002,
                            "GUI log batch is too large");
                }
                byte[] part = new byte[data.remaining()];
                data.get(part);
                fragments.write(part);
                if (last) {
                    List<GuiLogEvent> events = GuiLogBatchCodec.decode(
                            ByteBuffer.wrap(fragments.toByteArray()));
                    fragments.reset();
                    listener.onBatch(events);
                }
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            } catch (IOException | RuntimeException error) {
                fragments.reset();
                return webSocket.sendClose(
                        1002,
                        "Invalid GUI log batch");
            }
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket, int statusCode, String reason) {
            disconnected(webSocket);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            disconnected(webSocket != null ? webSocket : attached);
        }
    }

    private static final class DirectProxySelector extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(
                URI uri, SocketAddress address, IOException error) {
            // loopbackへ直接接続するため、プロキシ失敗の通知は発生しない。
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String name;

        DaemonThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
