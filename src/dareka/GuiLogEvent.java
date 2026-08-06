package dareka;

import java.util.Objects;

/** GUIログ配送で使う、タブとメッセージの組。 */
final class GuiLogEvent {
    private final String channel;
    private final String message;

    GuiLogEvent(String channel, String message) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.message = String.valueOf(message);
    }

    String getChannel() {
        return channel;
    }

    String getMessage() {
        return message;
    }
}
