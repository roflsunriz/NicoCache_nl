package dareka.processor.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.PatternSyntaxException;

import dareka.common.Logger;
import dareka.processor.HttpHeader;
import dareka.processor.HttpResponseHeader;

/** nlFilterの一回の適用結果と、読み込み世代ごとの通常ログ集計。 */
final class NlFilterDiagnostics implements AutoCloseable {
    static final long REPEAT_INTERVAL_MILLIS = 60_000;

    enum Reason {
        MATCH_ZERO(false, "一致箇所がありません"),
        APPEND_TARGET_MISSING(false, "挿入位置が見つかりません"),
        UNRESOLVED_VARIABLE(false, "置換に必要な変数を解決できません"),
        READ_ERROR(true, "フィルター定義を読み込めません"),
        SYNTAX_ERROR(true, "フィルター定義の構文が不正です"),
        PATTERN_ERROR(true, "正規表現の構文が不正です"),
        INVALID_GROUP(true, "キャプチャグループの参照が不正です"),
        PROCESSING_ERROR(true, "フィルターの処理中にエラーが発生しました"),
        LIST_WRITE_FAILED(true, "リストへの登録に失敗しました");

        final boolean error;
        final String message;

        Reason(boolean error, String message) {
            this.error = error;
            this.message = message;
        }
    }

    /** パーサーだけが構築し、公開後は変更しない出典情報。本文や正規表現は保持しない。 */
    static final class Source {
        String file = "(built-in)";
        String name = "(no name)";
        int sectionLine;
        int operationLine;
        int failedPatternLine;
        boolean append;
        boolean eachLine;
        String appendTarget = "";
        final List<Integer> matchLines = new ArrayList<>();

        int line(int index) {
            if (eachLine && !append && index >= 0 && index < matchLines.size()) {
                return matchLines.get(index);
            }
            return operationLine > 0 ? operationLine : sectionLine;
        }
    }

    static final class Context {
        final String url;
        final int status;
        final String contentType;

        Context(String url, HttpResponseHeader response) {
            this.url = safeUrl(url);
            status = response == null ? 0 : response.getStatusCode();
            String type = response == null ? null
                    : response.getMessageHeader(HttpHeader.CONTENT_TYPE);
            // boundary等の任意パラメーターを通常ログへ転記しない。
            contentType = type == null ? "(unknown)" : clean(type.split(";", 2)[0]);
        }
    }

    final class Run {
        private final Source source;
        private final Context context;
        private final Map<Key, String> problems = new LinkedHashMap<>();
        long matches;
        long applied;

        Run(Source source, Context context) {
            this.source = source;
            this.context = context;
        }

        void problem(Reason reason, int index, String detail) {
            problems.putIfAbsent(new Key(source, reason, source.line(index)), clean(detail));
        }

        void error(Reason reason, int index, Throwable failure) {
            problem(reason, index, describe(failure));
        }

        void finish(boolean expectMatch) {
            if (expectMatch && matches == 0 && problems.isEmpty()) {
                problem(source.append ? Reason.APPEND_TARGET_MISSING : Reason.MATCH_ZERO,
                        -1, source.append ? source.appendTarget : "");
            }
            for (Map.Entry<Key, String> problem : problems.entrySet()) {
                report(problem.getKey(), context, problem.getValue(), matches, applied);
            }
        }
    }

    private static final class Key {
        final Source source;
        final Reason reason;
        final int line;

        Key(Source source, Reason reason, int line) {
            this.source = source;
            this.reason = reason;
            this.line = line;
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(source), reason, line);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return source == key.source && reason == key.reason && line == key.line;
        }
    }

    private static final class Repeated {
        long lastPrinted;
        long pending;
        String latest;

        Repeated(long now, String message) {
            lastPrinted = now;
            latest = message;
        }
    }

    private final LongSupplier clock;
    private final Consumer<String> sink;
    // キーは読込済み定義・固定の理由・定義内の行のみ。URL数には比例して増えない。
    private final Map<Key, Repeated> repeated = new LinkedHashMap<>();
    private boolean closed;

    NlFilterDiagnostics() {
        this(() -> System.nanoTime() / 1_000_000, Logger::warning);
    }

    NlFilterDiagnostics(LongSupplier clock, Consumer<String> sink) {
        this.clock = Objects.requireNonNull(clock);
        this.sink = Objects.requireNonNull(sink);
    }

    Run begin(Source source, Context context) {
        return new Run(source, context);
    }

    void definitionError(Source source, int line, Reason reason, String detail) {
        report(new Key(source, reason, line), null, clean(detail), 0, 0);
    }

    private synchronized void report(Key key, Context context, String detail,
            long matches, long applied) {
        String message = format(key, context, detail, matches, applied);
        if (closed) {
            // 再読込・終了と競合した旧リクエストの結果も、未出力のまま捨てない。
            sink.accept(message);
            return;
        }
        long now = clock.getAsLong();
        Repeated entry = repeated.get(key);
        if (entry == null) {
            repeated.put(key, new Repeated(now, message));
            sink.accept(message);
        } else {
            entry.latest = message;
            entry.pending++;
            if (now - entry.lastPrinted >= REPEAT_INTERVAL_MILLIS) {
                printPending(entry);
                entry.lastPrinted = now;
            }
        }
    }

    private void printPending(Repeated entry) {
        if (entry.pending > 0) {
            sink.accept(entry.latest + " 同種の事象が追加" + entry.pending + "回");
            entry.pending = 0;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Repeated entry : repeated.values()) printPending(entry);
        repeated.clear();
    }

    private static String format(Key key, Context context, String detail,
            long matches, long applied) {
        Source source = key.source;
        StringBuilder message = new StringBuilder(256);
        message.append(key.reason.error ? "[ERROR]" : "[WARN]")
                .append("[nlFilter][").append(key.reason).append("] ")
                .append(clean(source.file));
        if (key.line > 0) message.append(':').append(key.line);
        message.append(" Name=\"").append(clean(source.name)).append("\" ")
                .append(source.append || key.reason == Reason.APPEND_TARGET_MISSING ? "Append: " : "Match: ")
                .append(key.reason.message);
        if (detail != null && !detail.isEmpty()) message.append(" (").append(detail).append(')');
        if (context != null) {
            message.append(" 一致=").append(matches).append(" 処理成功=").append(applied)
                    .append(" URL=").append(context.url)
                    .append(" Status=").append(context.status == 0 ? "(unknown)" : context.status)
                    .append(" Content-Type=").append(context.contentType);
        }
        return message.toString();
    }

    static String describe(Throwable failure) {
        // Throwableのmessage/causeには置換本文や認証URLが含まれる場合がある。
        String description = failure.getClass().getSimpleName();
        if (failure instanceof PatternSyntaxException) {
            PatternSyntaxException pattern = (PatternSyntaxException) failure;
            description += ": " + clean(pattern.getDescription()) + " index=" + pattern.getIndex();
        } else if (failure instanceof IndexOutOfBoundsException) {
            description += ": 参照番号または文字列の範囲を確認してください";
        } else if (failure instanceof IllegalArgumentException) {
            description += ": 置換式・参照名・設定値を確認してください";
        }
        StackTraceElement[] stack = failure.getStackTrace();
        if (stack.length > 0) description += " at " + stack[0];
        return clean(description);
    }

    static String safeUrl(String value) {
        if (value == null) return "(unknown)";
        try {
            // POST/付きURLではスペース以降にPOST本文が入る場合がある。
            int space = value.indexOf(' ');
            String address = space < 0 ? value : value.substring(0, space);
            if (address.startsWith("POST/")) address = address.substring(5);
            URI uri = new URI(address);
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) return "(invalid URL)";
            String host = uri.getHost();
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
            return clean(uri.getScheme() + "://" + host
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                    + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?[redacted]"));
        } catch (URISyntaxException | IllegalArgumentException failure) {
            return "(invalid URL)";
        }
    }

    static String clean(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(Math.min(value.length(), 512));
        int limit = Math.min(value.length(), 512);
        for (int i = 0; i < limit; i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') result.append(' ');
            else if (c == '"') result.append("\\\"");
            else result.append(c);
        }
        if (value.length() > limit) result.append('…');
        return result.toString();
    }
}
