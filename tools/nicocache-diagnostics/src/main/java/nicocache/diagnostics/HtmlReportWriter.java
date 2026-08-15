package nicocache.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Writes one offline, self-contained HTML incident report with no scripts. */
final class HtmlReportWriter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSS XXX")
            .withZone(ZoneId.systemDefault());

    Path write(Path incidentsRoot, IncidentReport report) throws IOException {
        String slug = report.reason.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "incident";
        }
        Path directory = incidentsRoot.resolve(FILE_TIME.format(report.capturedAt)
                + "-" + report.capturedAt.toEpochMilli() + "-" + slug);
        Files.createDirectories(directory);
        Path destination = directory.resolve("report.html");
        Path temporary = directory.resolve("report.html.tmp");
        String html = render(report);
        Files.writeString(temporary, html, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
    }

    String render(IncidentReport report) {
        StringBuilder html = new StringBuilder(256 * 1024);
        html.append("<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">")
                .append("<title>NicoCache_nl 診断レポート</title><style>")
                .append("body{font-family:system-ui,sans-serif;margin:0;background:#f4f6fb;color:#172033;line-height:1.5}")
                .append("main{max-width:1100px;margin:auto;padding:clamp(12px,3vw,32px)}")
                .append("header,section,details{background:white;border:1px solid #d7ddeb;border-radius:10px;padding:16px;margin:12px 0}")
                .append("h1,h2{margin:.2em 0}.badge{display:inline-block;background:#a51d2d;color:white;border-radius:999px;padding:4px 10px}")
                .append("table{width:100%;border-collapse:collapse;display:block;overflow:auto}th,td{text-align:left;border-bottom:1px solid #e3e7f0;padding:7px;white-space:nowrap}")
                .append("pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#101724;color:#e9efff;padding:12px;border-radius:7px;max-height:55vh;overflow:auto}")
                .append("code{font-family:ui-monospace,monospace}.ok{color:#08783d}.warn{color:#a25a00}.muted{color:#596579}")
                .append("@media(max-width:600px){main{padding:8px}header,section,details{padding:11px}th,td{font-size:12px}}")
                .append("</style></head><body><main><header><span class=\"badge\">Incident</span>")
                .append("<h1>NicoCache_nl 診断レポート</h1><p><b>原因:</b> ")
                .append(escape(report.reason)).append("<br><b>採取時刻:</b> ")
                .append(escape(DISPLAY_TIME.format(report.capturedAt)))
                .append("<br><b>対象PID:</b> ").append(report.pid).append("</p>")
                .append("<p class=\"muted\">外部送信は行っていません。認証情報、Cookie、個人パス等は自動除去しています。動画IDと動画タイトルは不具合特定に必要なため保持しています。</p></header>");

        appendMap(html, "実行環境", report.environment, false);
        appendTimeline(html, report);
        appendMap(html, "ファイルと拡張構成", report.files, false);
        appendMap(html, "設定（匿名化済み）", report.configuration, true);
        appendTexts(html, "スレッドダンプ／JVMスナップショット", report.snapshots);
        appendMap(html, "直近ログ（匿名化済み・容量制限あり）", report.logs, true);

        html.append("<section><h2>匿名化実績</h2><ul>");
        if (report.redactionCounts.isEmpty()) {
            html.append("<li>置換対象は検出されませんでした。</li>");
        } else {
            for (Map.Entry<String, Integer> entry : report.redactionCounts.entrySet()) {
                html.append("<li>").append(escape(entry.getKey())).append(": ")
                        .append(entry.getValue()).append("</li>");
            }
        }
        html.append("</ul></section><section><h2>収集注記</h2><ul>");
        if (report.notices.isEmpty()) {
            html.append("<li class=\"ok\">なし</li>");
        } else {
            for (String notice : report.notices) {
                html.append("<li class=\"muted\">").append(escape(notice))
                        .append("</li>");
            }
        }
        html.append("</ul></section><section><h2>収集エラー</h2><ul>");
        if (report.errors.isEmpty()) {
            html.append("<li class=\"ok\">なし</li>");
        } else {
            for (String error : report.errors) {
                html.append("<li class=\"warn\">").append(escape(error))
                        .append("</li>");
            }
        }
        html.append("</ul></section><footer class=\"muted\"><p>Report schema 1 / NicoCacheDiagnostics</p></footer></main></body></html>");
        return html.toString();
    }

    private static void appendTimeline(StringBuilder html, IncidentReport report) {
        html.append("<section><h2>ハートビート履歴</h2><table><thead><tr><th>時刻</th><th>状態</th><th>PID</th><th>管理API</th><th>プロキシー</th><th>詳細</th></tr></thead><tbody>");
        for (HeartbeatSample sample : report.timeline) {
            html.append("<tr><td>").append(escape(DISPLAY_TIME.format(sample.capturedAt)))
                    .append("</td><td>").append(escape(sample.health.name()))
                    .append("</td><td>").append(sample.pid)
                    .append("</td><td>").append(sample.controlAlive ? "OK" : "NG")
                    .append(" (").append(sample.controlMillis).append(" ms)</td><td>")
                    .append(sample.proxyAlive ? "OK" : "NG").append(" (")
                    .append(sample.proxyMillis).append(" ms)</td><td>")
                    .append(escape(sample.detail)).append("</td></tr>");
        }
        html.append("</tbody></table></section>");
    }

    private static void appendMap(StringBuilder html, String title,
            Map<String, String> values, boolean preformatted) {
        html.append("<details open><summary><b>").append(escape(title))
                .append("</b></summary>");
        if (values.isEmpty()) {
            html.append("<p>データなし</p>");
        } else if (preformatted) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                html.append("<h3>").append(escape(entry.getKey()))
                        .append("</h3><pre>").append(escape(entry.getValue()))
                        .append("</pre>");
            }
        } else {
            html.append("<table><tbody>");
            for (Map.Entry<String, String> entry : values.entrySet()) {
                html.append("<tr><th>").append(escape(entry.getKey()))
                        .append("</th><td>").append(escape(entry.getValue()))
                        .append("</td></tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</details>");
    }

    private static void appendTexts(StringBuilder html, String title,
            java.util.List<String> values) {
        html.append("<details open><summary><b>").append(escape(title))
                .append("</b></summary>");
        if (values.isEmpty()) {
            html.append("<p>取得できませんでした。</p>");
        }
        for (int index = 0; index < values.size(); index++) {
            html.append("<h3>Snapshot ").append(index + 1)
                    .append("</h3><pre>").append(escape(values.get(index)))
                    .append("</pre>");
        }
        html.append("</details>");
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
