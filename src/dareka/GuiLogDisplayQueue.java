package dareka;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** WebSocket受信スレッドとSwing EDTの間に置く有界キュー。 */
final class GuiLogDisplayQueue {
    private static final int DEFAULT_CAPACITY = 8192;

    private final ArrayDeque<GuiLogEvent> events = new ArrayDeque<>();
    private final int capacity;
    private long droppedEvents;

    GuiLogDisplayQueue() {
        int configured = Integer.getInteger(
                "guiLogDisplayQueueCapacity",
                Integer.getInteger("guiLogQueueCapacity", DEFAULT_CAPACITY));
        capacity = Math.max(256, Math.min(100000, configured));
    }

    synchronized void offerAll(List<GuiLogEvent> incoming) {
        for (GuiLogEvent event : incoming) {
            if (events.size() >= capacity) {
                events.removeFirst();
                droppedEvents++;
            }
            events.addLast(event);
        }
    }

    synchronized List<GuiLogEvent> drain(int maximumEvents) {
        List<GuiLogEvent> drained = new ArrayList<>(maximumEvents + 1);
        if (droppedEvents > 0) {
            drained.add(new GuiLogEvent("main",
                    "GUI描画の上限を超えたため " + droppedEvents
                    + " 件を省略しました。"));
            droppedEvents = 0;
        }
        while (!events.isEmpty() && drained.size() < maximumEvents) {
            drained.add(events.removeFirst());
        }
        return drained;
    }

    synchronized boolean isEmpty() {
        return events.isEmpty() && droppedEvents == 0;
    }

    synchronized int size() {
        return events.size();
    }

    synchronized void clear() {
        events.clear();
        droppedEvents = 0;
    }
}
