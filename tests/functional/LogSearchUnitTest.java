package functional;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * GUIを起動せずログ検索の変換と原本保持を検証する。
 */
public final class LogSearchUnitTest {
    private LogSearchUnitTest() {
    }

    public static void run() throws Exception {
        testLiteralAndRegularExpressionFiltering();
        testCaseSensitivityAndInvalidExpression();
        testBufferRetentionDedupeAndProgress();
    }

    private static void testLiteralAndRegularExpressionFiltering()
            throws Exception {
        String source = String.join("\n",
                "INFO startup",
                "literal.value",
                "literalXvalue",
                "error-42",
                "");
        Object literal = applyFilter(
                source, "literal.", false, false);
        assertEquals("literal.value\n", resultText(literal),
                "plain text must quote regular-expression characters");
        assertEquals(1, resultInteger(literal, "getMatchedLines"),
                "literal matched lines");
        assertEquals(4, resultInteger(literal, "getTotalLines"),
                "literal total lines");

        Object regex = applyFilter(
                source, "error-\\d+", true, false);
        assertEquals("error-42\n", resultText(regex),
                "regular-expression result");
        assertTrue(resultBoolean(regex, "isValid"),
                "valid regular expression");
    }

    private static void testCaseSensitivityAndInvalidExpression()
            throws Exception {
        String source = "error lower\nERROR upper\n";
        Object insensitive = applyFilter(
                source, "error", false, false);
        assertEquals(2, resultInteger(insensitive, "getMatchedLines"),
                "case-insensitive result");

        Object sensitive = applyFilter(
                source, "error", false, true);
        assertEquals("error lower\n", resultText(sensitive),
                "case-sensitive result");

        Object invalid = applyFilter(
                source, "[", true, false);
        assertFalse(resultBoolean(invalid, "isValid"),
                "invalid regular expression status");
        assertEquals(source, resultText(invalid),
                "invalid expression must not destroy the raw display");
    }

    private static void testBufferRetentionDedupeAndProgress()
            throws Exception {
        Object buffer = newBuffer();
        append(buffer, "same", true, 10);
        append(buffer, "same", true, 10);
        append(buffer, "same", true, 10);
        assertEquals("same\n++", bufferText(buffer),
                "deduplicated representation");

        clear(buffer);
        append(buffer, "caching sm1: 10%", false, 10);
        append(buffer, "caching sm1: 90%", false, 10);
        assertFalse(bufferText(buffer).contains("10%"),
                "stale progress");
        assertEquals(1, occurrences(bufferText(buffer), "caching sm1:"),
                "progress line count");

        clear(buffer);
        append(buffer, "one", false, 3);
        append(buffer, "two", false, 3);
        append(buffer, "three", false, 3);
        append(buffer, "four", false, 3);
        assertFalse(bufferText(buffer).contains("one"),
                "oldest line retention");
        assertTrue(bufferText(buffer).contains("four"),
                "newest line retention");
    }

    private static Object applyFilter(String source, String query,
            boolean regex, boolean caseSensitive) throws Exception {
        Class<?> filter = Class.forName("dareka.LogFilter");
        Method apply = filter.getDeclaredMethod(
                "apply", String.class, String.class,
                boolean.class, boolean.class);
        apply.setAccessible(true);
        return apply.invoke(null, source, query, regex, caseSensitive);
    }

    private static String resultText(Object result) throws Exception {
        return (String) invoke(result, "getText");
    }

    private static int resultInteger(Object result, String method)
            throws Exception {
        return (Integer) invoke(result, method);
    }

    private static boolean resultBoolean(Object result, String method)
            throws Exception {
        return (Boolean) invoke(result, method);
    }

    private static Object newBuffer() throws Exception {
        Class<?> type = Class.forName("dareka.LogBuffer");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void append(Object buffer, String message,
            boolean dedupe, int maximumLineCount) throws Exception {
        Method method = buffer.getClass().getDeclaredMethod(
                "append", String.class, boolean.class, int.class);
        method.setAccessible(true);
        method.invoke(buffer, message, dedupe, maximumLineCount);
    }

    private static void clear(Object buffer) throws Exception {
        invoke(buffer, "clear");
    }

    private static String bufferText(Object buffer) throws Exception {
        return (String) invoke(buffer, "getText");
    }

    private static Object invoke(Object target, String methodName)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(part, offset)) >= 0) {
            count++;
            offset += part.length();
        }
        return count;
    }

    private static void assertEquals(
            Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }
}
