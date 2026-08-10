package nicocache.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RedactorTest {
    private RedactorTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("diagnostics-redactor-");
        DiagnosticsPaths paths = DiagnosticsPaths.resolve(
                root.resolve("app"), root.resolve("personal-data"));
        Redactor redactor = new Redactor(paths);
        String title = "sm12345678 これは再現に必要な動画タイトル";
        String input = "Authorization: Bearer abcdef\n"
                + "Cookie: user_session=secret\npassword=hunter2\n"
                + "myApiToken=compound-secret\n"
                + "upstream=http://alice:private@example.invalid/\n"
                + "path=" + paths.dataRoot() + "\\cache\n"
                + "mail=user@example.com ip=192.168.1.20\n" + title;
        String output = redactor.redact(input);
        assertNotContains(output, "abcdef");
        assertNotContains(output, "user_session=secret");
        assertNotContains(output, "hunter2");
        assertNotContains(output, "compound-secret");
        assertNotContains(output, "alice:private");
        assertNotContains(output, "user@example.com");
        assertNotContains(output, "192.168.1.20");
        assertContains(output, "<DATA_ROOT>");
        assertContains(output, "sm12345678");
        assertContains(output, "これは再現に必要な動画タイトル");
        assertTrue(!redactor.counts().isEmpty(),
                "redaction counts must be recorded");
        System.out.println("Diagnostics redactor tests passed");
    }

    private static void assertContains(String value, String expected) {
        if (!value.contains(expected)) {
            throw new AssertionError("missing: " + expected + " in " + value);
        }
    }
    private static void assertNotContains(String value, String unexpected) {
        if (value.contains(unexpected)) {
            throw new AssertionError("secret remained: " + unexpected);
        }
    }
    private static void assertTrue(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
}
