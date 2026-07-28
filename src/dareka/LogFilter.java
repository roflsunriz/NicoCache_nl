package dareka;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * GUIログを物理行単位で絞り込む純粋な変換処理。
 */
final class LogFilter {
    private LogFilter() {
    }

    static Result apply(String source, String query,
            boolean regularExpression, boolean caseSensitive) {
        String safeSource = source == null ? "" : source;
        String safeQuery = query == null ? "" : query;
        int totalLines = countLines(safeSource);
        if (safeQuery.isEmpty()) {
            return new Result(
                    safeSource, totalLines, totalLines, null);
        }

        int flags = caseSensitive
                ? 0
                : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Pattern pattern;
        try {
            pattern = regularExpression
                    ? Pattern.compile(safeQuery, flags)
                    : Pattern.compile(Pattern.quote(safeQuery), flags);
        } catch (PatternSyntaxException error) {
            return new Result(
                    safeSource, totalLines, totalLines,
                    concisePatternError(error));
        }

        StringBuilder filtered = new StringBuilder();
        int matchedLines = 0;
        int lineStart = 0;
        while (lineStart < safeSource.length()) {
            int breakIndex = safeSource.indexOf('\n', lineStart);
            int lineEnd = breakIndex >= 0
                    ? breakIndex
                    : safeSource.length();
            String line = safeSource.substring(lineStart, lineEnd);
            String matchTarget = line.endsWith("\r")
                    ? line.substring(0, line.length() - 1)
                    : line;
            if (pattern.matcher(matchTarget).find()) {
                filtered.append(safeSource, lineStart, lineEnd);
                if (breakIndex >= 0) {
                    filtered.append('\n');
                }
                matchedLines++;
            }
            if (breakIndex < 0) {
                break;
            }
            lineStart = breakIndex + 1;
        }
        return new Result(
                filtered.toString(), matchedLines, totalLines, null);
    }

    private static int countLines(String source) {
        if (source.isEmpty()) {
            return 0;
        }
        int count = 0;
        int lineStart = 0;
        while (lineStart < source.length()) {
            count++;
            int breakIndex = source.indexOf('\n', lineStart);
            if (breakIndex < 0) {
                break;
            }
            lineStart = breakIndex + 1;
        }
        return count;
    }

    private static String concisePatternError(PatternSyntaxException error) {
        String description = error.getDescription();
        if (description == null || description.isBlank()) {
            return "正規表現を解析できません";
        }
        return description;
    }

    static final class Result {
        private final String text;
        private final int matchedLines;
        private final int totalLines;
        private final String error;

        Result(String text, int matchedLines, int totalLines, String error) {
            this.text = text;
            this.matchedLines = matchedLines;
            this.totalLines = totalLines;
            this.error = error;
        }

        String getText() {
            return text;
        }

        int getMatchedLines() {
            return matchedLines;
        }

        int getTotalLines() {
            return totalLines;
        }

        String getError() {
            return error;
        }

        boolean isValid() {
            return error == null;
        }
    }
}
