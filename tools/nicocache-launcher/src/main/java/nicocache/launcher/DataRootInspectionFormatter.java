package nicocache.launcher;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/** Localized text rendering shared by the launcher GUI and CLI. */
final class DataRootInspectionFormatter {
    private DataRootInspectionFormatter() {
    }

    static String stateText(DataRootInspection inspection,
            ResourceBundle messages) {
        return messages.getString("dataRoot.state."
                + inspection.getState().name().toLowerCase(Locale.ROOT));
    }

    static String summary(DataRootInspection inspection,
            ResourceBundle messages) {
        return MessageFormat.format(
                messages.getString("dataRoot.summary"),
                stateText(inspection, messages),
                inspection.getBlockingCount(),
                inspection.getAttentionCount());
    }

    static String details(DataRootInspection inspection,
            ResourceBundle messages) {
        StringBuilder output = new StringBuilder();
        output.append(summary(inspection, messages))
                .append(System.lineSeparator())
                .append(MessageFormat.format(
                        messages.getString("dataRoot.applicationRoot"),
                        pathText(inspection.getApplicationRoot(), messages)))
                .append(System.lineSeparator())
                .append(MessageFormat.format(
                        messages.getString("dataRoot.dataRoot"),
                        pathText(inspection.getDataRoot(), messages)))
                .append(System.lineSeparator());
        for (DataRootInspection.Item item : inspection.getItems()) {
            output.append(formatItem(item, messages))
                    .append(System.lineSeparator());
        }
        return output.toString();
    }

    private static String formatItem(DataRootInspection.Item item,
            ResourceBundle messages) {
        String label = messages.getString("dataRoot.item." + item.getId());
        String severity = messages.getString("dataRoot.severity."
                + item.getSeverity().name().toLowerCase(Locale.ROOT));
        String state = messages.getString("dataRoot.itemState."
                + item.getState().name().toLowerCase(Locale.ROOT));
        String reason = messages.getString("dataRoot.reason."
                + item.getReasonKey());
        String action = actionText(item, messages);
        String result = MessageFormat.format(
                messages.getString("dataRoot.item.format"), label, severity,
                state, reason, pathText(item.getPath(), messages), action);
        Path fallback = item.getFallbackPath();
        if (fallback != null) {
            result += MessageFormat.format(
                    messages.getString("dataRoot.fallback.format"),
                    messages.getString("dataRoot.fallback.label"),
                    pathText(fallback, messages));
        }
        return result;
    }

    private static String actionText(DataRootInspection.Item item,
            ResourceBundle messages) {
        String reasonKey = item.getReasonKey();
        if (reasonKey != null) {
            String itemActionKey = "dataRoot.action.item." + item.getId()
                    + "." + reasonKey;
            if (messages.containsKey(itemActionKey)) {
                return messages.getString(itemActionKey);
            }
            String reasonActionKey = "dataRoot.action.reason." + reasonKey;
            if (messages.containsKey(reasonActionKey)) {
                return messages.getString(reasonActionKey);
            }
        }
        String stateActionKey = "dataRoot.action.state."
                + item.getState().name().toLowerCase(Locale.ROOT);
        if (messages.containsKey(stateActionKey)) {
            return messages.getString(stateActionKey);
        }
        return messages.getString("dataRoot.action.default");
    }

    private static String pathText(Path path, ResourceBundle messages) {
        return path == null
                ? messages.getString("dataRoot.path.unknown")
                : path.toString();
    }
}
