package dareka;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Regression tests for one-way canonical UTF-8 user text migration. */
final class UserTextEncodingMigratorTest {
    private static final Charset WINDOWS_31J = Charset.forName("windows-31j");

    private UserTextEncodingMigratorTest() {
    }

    static void run(Path sandbox) throws Exception {
        Files.createDirectories(sandbox);
        String oldDataRoot = System.getProperty(
                NicoCachePaths.USER_DATA_ROOT_PROPERTY);
        try {
            System.setProperty(NicoCachePaths.USER_DATA_ROOT_PROPERTY,
                    sandbox.toString());
            Path configuration = sandbox.resolve(
                    "application/config.properties");
            writeLegacy(configuration,
                    "# 旧Windows設定\r\nuserDataRoot=C:\\\\利用者データ\r\n");
            byte[] proxyOriginal = writeLegacy(sandbox.resolve("proxy.pac"),
                    "function FindProxyForURL(url, host) {\r\n"
                    + "  // 日本語PAC\r\n  return 'DIRECT';\r\n}\r\n");
            writeLegacy(sandbox.resolve("nlFilters/user.txt"),
                    "# nlフィルタ定義\r\n[Replace]\r\nName = 日本語\r\n");
            writeLegacy(sandbox.resolve("list/NGtitle.txt"),
                    "日本語タイトル\r\n");
            writeLegacy(sandbox.resolve("local/user.js"),
                    "// 日本語スクリプト\r\nvoid 0;\r\n");
            writeLegacy(sandbox.resolve("data/cors/user.conf"),
                    "// CORS設定ファイル\r\n[]\r\n");
            Path invalid = sandbox.resolve("list/unknown.txt");
            Files.write(invalid, new byte[] {(byte) 0x81});
            Path linkedTarget = sandbox.resolve("linked-target.txt");
            byte[] linkedOriginal = "リンク先日本語".getBytes(WINDOWS_31J);
            Files.write(linkedTarget, linkedOriginal);
            Path linked = sandbox.resolve("list/linked.txt");
            boolean linkCreated = createSymbolicLink(linked, linkedTarget);

            UserTextEncodingMigrator.Result first =
                    UserTextEncodingMigrator.migrate(configuration);
            assertEquals(6, first.getConverted(), "converted file count");
            assertEquals(1, first.getIssueCount(), "migration issue count");
            for (Path path : List.of(
                    configuration,
                    sandbox.resolve("proxy.pac"),
                    sandbox.resolve("nlFilters/user.txt"),
                    sandbox.resolve("list/NGtitle.txt"),
                    sandbox.resolve("local/user.js"),
                    sandbox.resolve("data/cors/user.conf"))) {
                assertCanonicalUtf8(path);
            }
            assertArrayEquals(new byte[] {(byte) 0x81},
                    Files.readAllBytes(invalid),
                    "unrecognized text must remain unchanged");
            if (linkCreated) {
                assertArrayEquals(linkedOriginal,
                        Files.readAllBytes(linkedTarget),
                        "symbolic link target must not be converted");
            }
            Path backupRoot = sandbox.resolve(
                    "data/text-encoding-backups/v1");
            List<Path> backups;
            try (Stream<Path> stream = Files.walk(backupRoot)) {
                backups = stream.filter(Files::isRegularFile)
                        .collect(Collectors.toList());
            }
            assertEquals(6, backups.size(), "backup count");
            List<Path> proxyBackups = backups.stream()
                    .filter(path -> path.getFileName().toString()
                            .startsWith("proxy.pac."))
                    .collect(Collectors.toList());
            assertEquals(1, proxyBackups.size(), "proxy backup count");
            assertArrayEquals(proxyOriginal,
                    Files.readAllBytes(proxyBackups.get(0)),
                    "proxy backup bytes");

            Path report = sandbox.resolve(
                    "data/text-encoding-migration-v1.properties");
            assertCanonicalUtf8(report);
            String reportText = Files.readString(report,
                    StandardCharsets.UTF_8);
            assertContains(reportText, "issues=1", "issue summary");
            assertContains(reportText, "UTF-8",
                    "beginner recovery guidance");

            UserTextEncodingMigrator.Result second =
                    UserTextEncodingMigrator.migrate(configuration);
            assertEquals(0, second.getConverted(),
                    "second migration must be idempotent");
            assertEquals(1, second.getIssueCount(),
                    "unresolved issue must remain visible");

            ProxyPacUpdater.update();
            assertCanonicalUtf8(sandbox.resolve("proxy.pac"));
            assertContains(Files.readString(sandbox.resolve("proxy.pac"),
                    StandardCharsets.UTF_8), "nicocachenl.test",
                    "PAC route after UTF-8 migration");
        } finally {
            restoreProperty(NicoCachePaths.USER_DATA_ROOT_PROPERTY,
                    oldDataRoot);
        }
        System.out.println("PASS managed user text canonical UTF-8 migration");
    }

    private static byte[] writeLegacy(Path path, String text)
            throws IOException {
        Files.createDirectories(path.getParent());
        byte[] bytes = text.getBytes(WINDOWS_31J);
        Files.write(path, bytes);
        return bytes;
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException
                | SecurityException error) {
            return false;
        }
    }

    private static void assertCanonicalUtf8(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        byte[] roundTrip = text.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(bytes, roundTrip,
                "canonical UTF-8 round trip: " + path.getFileName());
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static void assertContains(String actual, String expected,
            String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual,
            String message) {
        if (!java.util.Arrays.equals(expected, actual)) {
            throw new AssertionError(message + ": expectedLength="
                    + expected.length + ", actualLength=" + actual.length);
        }
    }

    private static void assertEquals(int expected, int actual,
            String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }
}
