package dareka.updater;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free unit tests runnable with plain javac/java. */
public final class NicoCacheUpdaterTest {
    private NicoCacheUpdaterTest() {}

    public static void main(String[] args) throws Exception {
        Class<?> updater = Class.forName("dareka.updater.NicoCacheUpdater");
        Method compare = updater.getDeclaredMethod("compareVersions", String.class, String.class);
        compare.setAccessible(true);
        assertInt(compare, 0, "1.2", "1.2.0");
        assertInt(compare, -1, "1.2.3", "1.2.4");
        assertInt(compare, 1, "1.10", "1.9");
        assertInt(compare, 0, "25.0.0.0", "25");

        Method sha256 = updater.getDeclaredMethod("sha256", Path.class);
        sha256.setAccessible(true);
        Path file = Files.createTempFile("updater-test-", ".bin");
        try {
            Files.write(file, "abc".getBytes(StandardCharsets.US_ASCII));
            String hash = (String) sha256.invoke(null, file);
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash,
                    "SHA-256");
        } finally {
            Files.deleteIfExists(file);
        }

        Class<?> choiceClass = Class.forName("dareka.updater.NicoCacheUpdater$JavaChoice");
        Constructor<?> choiceConstructor = choiceClass.getDeclaredConstructor(int.class, boolean.class, boolean.class);
        choiceConstructor.setAccessible(true);
        Object supported = choiceConstructor.newInstance(21, true, true);
        Object unsupported = choiceConstructor.newInstance(25, false, false);
        assertEquals("Java 21 LTS（推奨）", supported.toString(), "recommended LTS label");
        assertEquals("Java 25 LTS（未対応）", unsupported.toString(), "unsupported LTS label");
        Field supportedField = choiceClass.getDeclaredField("supported");
        supportedField.setAccessible(true);
        if ((boolean) supportedField.get(unsupported)) {
            throw new AssertionError("unsupported LTS became selectable");
        }

        Field testedLts = updater.getDeclaredField("TESTED_LTS");
        testedLts.setAccessible(true);
        String set = testedLts.get(null).toString();
        if (!set.contains("17") || !set.contains("21") || set.contains("25")) {
            throw new AssertionError("TESTED_LTS policy is inconsistent: " + set);
        }

        System.out.println("NicoCacheUpdater Java unit tests passed");
    }

    private static void assertInt(Method method, int expected, String left, String right) throws Exception {
        int actual = Integer.signum((Integer) method.invoke(null, left, right));
        if (actual != expected) {
            throw new AssertionError(left + " vs " + right + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
