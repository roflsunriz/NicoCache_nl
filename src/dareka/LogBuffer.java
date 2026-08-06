package dareka;

import java.util.List;

/**
 * GUIログの原本を保持し、重複抑制と進捗行の更新を適用する。
 */
final class LogBuffer {
    private final StringBuilder text = new StringBuilder();
    private String lastMessage;
    private boolean needNewline;

    void append(String message, boolean dedupe, int maximumLineCount) {
        appendWithoutTrim(message, dedupe);
        trim(maximumLineCount);
    }

    void appendAll(List<String> messages, boolean dedupe,
            int maximumLineCount) {
        for (String message : messages) {
            appendWithoutTrim(message, dedupe);
        }
        trim(maximumLineCount);
    }

    private void appendWithoutTrim(String message, boolean dedupe) {
        if (replaceCachingProgress(message)) {
            return;
        }
        if (dedupeMessage(message, dedupe)) {
            text.append(String.valueOf(message)).append('\n');
        }
    }

    String getText() {
        return text.toString();
    }

    void clear() {
        text.setLength(0);
        lastMessage = null;
        needNewline = false;
    }

    private boolean replaceCachingProgress(String message) {
        if (message == null || !message.startsWith("caching ")) {
            return false;
        }
        int separator = message.indexOf(": ");
        if (separator < 0) {
            return false;
        }

        String prefix = message.substring(0, separator + 1);
        int lineEnd = text.length();
        while (lineEnd > 0) {
            if (text.charAt(lineEnd - 1) == '\n') {
                lineEnd--;
            }
            int previousBreak = lastIndexOf('\n', lineEnd - 1);
            int lineStart = previousBreak + 1;
            String existing = text.substring(lineStart, lineEnd).strip();
            if (existing.startsWith(prefix)) {
                text.replace(lineStart, lineEnd, message);
                lastMessage = message;
                needNewline = false;
                return true;
            }
            if (previousBreak < 0) {
                break;
            }
            lineEnd = previousBreak;
        }
        return false;
    }

    private int lastIndexOf(char value, int fromIndex) {
        for (int index = Math.min(fromIndex, text.length() - 1);
                index >= 0; index--) {
            if (text.charAt(index) == value) {
                return index;
            }
        }
        return -1;
    }

    private boolean dedupeMessage(String message, boolean dedupe) {
        if (!dedupe) {
            return true;
        }

        if (lastMessage == null || message == null) {
            lastMessage = message;
            if (needNewline) {
                text.append('\n');
                needNewline = false;
            }
            return true;
        }
        boolean same = lastMessage.equals(message);
        lastMessage = message;
        if (same) {
            text.append('+');
            needNewline = true;
            return false;
        }
        if (needNewline) {
            text.append('\n');
            needNewline = false;
        }
        return true;
    }

    private void trim(int maximumLineCount) {
        int threshold = Math.max(1, maximumLineCount);
        int lineCount = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lineCount++;
            }
        }
        int linesToRemove = lineCount - threshold;
        if (linesToRemove <= 0) {
            return;
        }

        int removeEnd = 0;
        while (removeEnd < text.length() && linesToRemove > 0) {
            if (text.charAt(removeEnd++) == '\n') {
                linesToRemove--;
            }
        }
        text.delete(0, removeEnd);
    }
}
