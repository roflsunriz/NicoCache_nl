package nicocache.launcher;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/** Immutable result of a user-data-root inspection. */
final class DataRootInspection {
    enum OverallState {
        COMPLETE,
        ATTENTION,
        BLOCKED
    }

    enum Severity {
        REQUIRED,
        RECOMMENDED,
        INFORMATIONAL
    }

    enum ItemState {
        OK,
        FALLBACK,
        MISSING,
        ATTENTION,
        BLOCKED,
        ERROR,
        NOT_APPLICABLE
    }

    static final class Item {
        private final String id;
        private final Severity severity;
        private final ItemState state;
        private final Path path;
        private final Path fallbackPath;
        private final String reasonKey;

        Item(String id, Severity severity, ItemState state, Path path,
                Path fallbackPath, String reasonKey) {
            this.id = id;
            this.severity = severity;
            this.state = state;
            this.path = path;
            this.fallbackPath = fallbackPath;
            this.reasonKey = reasonKey;
        }

        String getId() {
            return id;
        }

        Severity getSeverity() {
            return severity;
        }

        ItemState getState() {
            return state;
        }

        Path getPath() {
            return path;
        }

        Path getFallbackPath() {
            return fallbackPath;
        }

        String getReasonKey() {
            return reasonKey;
        }

        boolean isBlocking() {
            return severity == Severity.REQUIRED
                    && (state == ItemState.BLOCKED || state == ItemState.ERROR);
        }

        boolean isCompletenessIssue() {
            return severity != Severity.INFORMATIONAL
                    && state != ItemState.OK
                    && state != ItemState.NOT_APPLICABLE;
        }
    }

    private final Path applicationRoot;
    private final Path dataRoot;
    private final OverallState state;
    private final List<Item> items;

    private DataRootInspection(Path applicationRoot, Path dataRoot,
            OverallState state, List<Item> items) {
        this.applicationRoot = applicationRoot;
        this.dataRoot = dataRoot;
        this.state = state;
        this.items = Collections.unmodifiableList(items);
    }

    static DataRootInspection create(Path applicationRoot, Path dataRoot,
            List<Item> items) {
        boolean blocked = false;
        boolean attention = false;
        for (Item item : items) {
            blocked |= item.isBlocking();
            attention |= item.isCompletenessIssue();
        }
        OverallState state = blocked
                ? OverallState.BLOCKED
                : attention ? OverallState.ATTENTION : OverallState.COMPLETE;
        return new DataRootInspection(applicationRoot, dataRoot, state,
                items);
    }

    Path getApplicationRoot() {
        return applicationRoot;
    }

    Path getDataRoot() {
        return dataRoot;
    }

    OverallState getState() {
        return state;
    }

    List<Item> getItems() {
        return items;
    }

    int getBlockingCount() {
        int count = 0;
        for (Item item : items) {
            if (item.isBlocking()) {
                count++;
            }
        }
        return count;
    }

    int getAttentionCount() {
        int count = 0;
        for (Item item : items) {
            if (item.isCompletenessIssue() && !item.isBlocking()) {
                count++;
            }
        }
        return count;
    }

    /** 0=complete, 1=operational but incomplete, 2=blocked/invalid. */
    int getExitCode() {
        switch (state) {
        case COMPLETE:
            return 0;
        case ATTENTION:
            return 1;
        case BLOCKED:
        default:
            return 2;
        }
    }
}
