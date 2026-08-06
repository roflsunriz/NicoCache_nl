package dareka;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** GUIログ用WebSocketサーバーとクライアントのライフサイクルを束ねる。 */
final class GuiLogWebSocketTransport implements AutoCloseable {
    private final GuiLogWebSocketServer server;
    private final GuiLogWebSocketClient client;
    private final AtomicBoolean closed = new AtomicBoolean();

    private GuiLogWebSocketTransport(
            GuiLogWebSocketServer server, GuiLogWebSocketClient client) {
        this.server = server;
        this.client = client;
    }

    static GuiLogWebSocketTransport start(
            GuiLogWebSocketClient.BatchListener listener) throws IOException {
        GuiLogWebSocketServer server = GuiLogWebSocketServer.start();
        try {
            GuiLogWebSocketClient client = GuiLogWebSocketClient.connect(
                    server.getEndpoint(), server.getToken(), listener);
            return new GuiLogWebSocketTransport(server, client);
        } catch (RuntimeException error) {
            server.close();
            throw error;
        }
    }

    void publish(String channel, String message) {
        if (!closed.get()) {
            server.publish(channel, message);
        }
    }

    int pendingEventCount() {
        return server.pendingEventCount();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        client.close();
        server.close();
    }
}
