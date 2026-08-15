package nicocache.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class HtmlReportWriterTest {
    private HtmlReportWriterTest() { }

    public static void main(String[] args) throws Exception {
        HeartbeatSample heartbeat = new HeartbeatSample(Instant.now(), 55L,
                "running", true, false, false, 1200L, 1200L,
                "timeout <unsafe>", HeartbeatSample.Health.UNRESPONSIVE);
        IncidentReport report = new IncidentReport("all-heartbeats-lost",
                Instant.now(), 55L, List.of(heartbeat));
        report.environment.put("OS", "Test OS");
        report.configuration.put("config", "token=<OMITTED>");
        report.snapshots.add("sm123456 タイトル <thread>");
        report.notices.add("終了前スナップショットを収録");
        report.logs.put("debug.log", "Cookie: <OMITTED>");
        HtmlReportWriter writer = new HtmlReportWriter();
        String html = writer.render(report);
        assertContains(html, "Content-Security-Policy");
        assertContains(html, "sm123456 タイトル");
        assertContains(html, "&lt;thread&gt;");
        assertContains(html, "収集注記");
        assertContains(html, "終了前スナップショットを収録");
        assertNotContains(html, "<unsafe>");
        assertNotContains(html, "<script");
        Path root = Files.createTempDirectory("diagnostics-html-");
        Path output = writer.write(root, report);
        assertTrue(Files.isRegularFile(output), "report file must exist");
        assertContains(Files.readString(output), "NicoCache_nl 診断レポート");
        System.out.println("HTML report writer tests passed");
    }

    private static void assertContains(String value, String expected) {
        if (!value.contains(expected)) { throw new AssertionError("missing: " + expected); }
    }
    private static void assertNotContains(String value, String unexpected) {
        if (value.contains(unexpected)) { throw new AssertionError("unexpected: " + unexpected); }
    }
    private static void assertTrue(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
}
