package dareka;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** WebSocketの1バイナリメッセージに複数のGUIログを格納する。 */
final class GuiLogBatchCodec {
    private static final int MAGIC = 0x4e434c47; // NCLG
    private static final int VERSION = 1;
    private static final int MAX_EVENTS = 4096;
    private static final int MAX_CHANNEL_BYTES = 4096;
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    static final int MAX_BATCH_BYTES = 4 * 1024 * 1024;

    private GuiLogBatchCodec() {
    }

    static byte[] encode(List<GuiLogEvent> events) throws IOException {
        if (events.size() > MAX_EVENTS) {
            throw new IOException("GUIログのバッチ件数が上限を超えています");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeInt(events.size());
            for (GuiLogEvent event : events) {
                writeString(output, event.getChannel(), MAX_CHANNEL_BYTES);
                writeString(output, event.getMessage(), MAX_MESSAGE_BYTES);
                if (bytes.size() > MAX_BATCH_BYTES) {
                    throw new IOException("GUIログのバッチサイズが上限を超えています");
                }
            }
        }
        return bytes.toByteArray();
    }

    static List<GuiLogEvent> decode(ByteBuffer source) throws IOException {
        if (source.remaining() > MAX_BATCH_BYTES) {
            throw new IOException("GUIログの受信バッチが大きすぎます");
        }
        byte[] encoded = new byte[source.remaining()];
        source.get(encoded);
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("GUIログのバッチ形式が不正です");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_EVENTS) {
                throw new IOException("GUIログのバッチ件数が不正です");
            }
            List<GuiLogEvent> events = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String channel = readString(input, MAX_CHANNEL_BYTES);
                String message = readString(input, MAX_MESSAGE_BYTES);
                events.add(new GuiLogEvent(channel, message));
            }
            if (input.available() != 0) {
                throw new IOException("GUIログのバッチ末尾に余分なデータがあります");
            }
            return events;
        } catch (EOFException error) {
            throw new IOException("GUIログの受信バッチが途中で切れています", error);
        }
    }

    static int encodedEventSize(GuiLogEvent event) {
        return Integer.BYTES
                + event.getChannel().getBytes(StandardCharsets.UTF_8).length
                + Integer.BYTES
                + event.getMessage().getBytes(StandardCharsets.UTF_8).length;
    }

    private static void writeString(DataOutputStream output, String value,
            int maximumBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) {
            throw new IOException("GUIログの文字列が上限を超えています");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximumBytes)
            throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes) {
            throw new IOException("GUIログの文字列長が不正です");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
