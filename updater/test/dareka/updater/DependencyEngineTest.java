package dareka.updater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Security and transaction tests runnable with plain javac/java. */
public final class DependencyEngineTest {
    private DependencyEngineTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("dependency-engine-test-");
        try {
            DependencyEngine engine = new DependencyEngine(root);
            String result = engine.selfTestTransactions();
            assertContains(result, "TRANSACTION_E2E_OK", "transaction self-test");

            boolean invalidLts = false;
            try {
                engine.checkAll(25);
            } catch (IOException expected) {
                invalidLts = expected.getMessage().contains("未検証のTemurin");
            }
            assertTrue(invalidLts, "Unvalidated Temurin LTS was accepted");

            Method compare = DependencyEngine.class.getDeclaredMethod(
                    "compareVersions", String.class, String.class);
            compare.setAccessible(true);
            assertSign(compare, 0, "1.2", "1.2.0");
            assertSign(compare, 1, "1.10.0", "1.9.99");
            assertSign(compare, -1, "1.0.9", "1.1");

            Method assertInside = DependencyEngine.class.getDeclaredMethod(
                    "assertInside", Path.class, Path.class);
            assertInside.setAccessible(true);
            expectInvocationFailure(() -> assertInside.invoke(null, root, root),
                    "Root itself was accepted as a managed child");
            expectInvocationFailure(() -> assertInside.invoke(null, root, root.resolve("..").resolve("escape")),
                    "Parent traversal was accepted");
            assertInside.invoke(null, root, root.resolve("safe").resolve("file"));

            Method unzip = DependencyEngine.class.getDeclaredMethod("unzip", Path.class, Path.class);
            unzip.setAccessible(true);
            Path goodZip = root.resolve("good.zip");
            Files.write(goodZip, zip("folder/value.txt", "ok"));
            Path goodOut = root.resolve("good-out");
            Files.createDirectories(goodOut);
            unzip.invoke(null, goodZip, goodOut);
            assertTrue("ok".equals(Files.readString(goodOut.resolve("folder/value.txt"))),
                    "Normal ZIP extraction failed");

            Path evilZip = root.resolve("evil.zip");
            Files.write(evilZip, zip("../escaped.txt", "bad"));
            Path evilOut = root.resolve("evil-out");
            Files.createDirectories(evilOut);
            expectInvocationFailure(() -> unzip.invoke(null, evilZip, evilOut),
                    "ZIP traversal was accepted");
            assertTrue(!Files.exists(root.resolve("escaped.txt")), "ZIP traversal wrote outside destination");

            Method findAsset = DependencyEngine.class.getDeclaredMethod(
                    "findAsset", String.class, Pattern.class);
            findAsset.setAccessible(true);
            String digest = repeat('a', 64);
            String json = "{\"assets\":[{\"browser_download_url\":\"https://example.invalid/a.bin\","
                    + "\"name\":\"a.bin\",\"digest\":\"sha256:" + digest + "\"}]}";
            Object asset = findAsset.invoke(null, json, Pattern.compile("^a\\.bin$"));
            java.lang.reflect.Field digestField = asset.getClass().getDeclaredField("digest");
            digestField.setAccessible(true);
            assertTrue(digest.equals(digestField.get(asset)), "GitHub asset digest was not parsed");

            testSymlinkEscape(root);
            System.out.println("DependencyEngine security and transaction tests passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void testSymlinkEscape(Path root) throws Exception {
        Path outside = Files.createTempDirectory("dependency-engine-outside-");
        try {
            Path link = root.resolve("link-outside");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
                return;
            }
            Method method = DependencyEngine.class.getDeclaredMethod(
                    "assertNoReparseEscape", Path.class, Path.class);
            method.setAccessible(true);
            expectInvocationFailure(() -> method.invoke(null, root, link.resolve("payload")),
                    "Symlink/reparse escape was accepted");
        } finally {
            deleteTree(outside);
        }
    }

    private static byte[] zip(String name, String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(value.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void assertSign(Method method, int expected, String left, String right) throws Exception {
        int actual = Integer.signum((Integer) method.invoke(null, left, right));
        assertTrue(actual == expected, left + " vs " + right + ": expected " + expected + ", got " + actual);
    }

    private static void expectInvocationFailure(CheckedAction action, String message) throws Exception {
        boolean failed = false;
        try {
            action.run();
        } catch (InvocationTargetException expected) {
            failed = expected.getCause() instanceof IOException;
        }
        assertTrue(failed, message);
    }

    private static void assertContains(String value, String expected, String label) {
        assertTrue(value.contains(expected), label + " missing: " + expected + " in " + value);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private interface CheckedAction { void run() throws Exception; }
}
