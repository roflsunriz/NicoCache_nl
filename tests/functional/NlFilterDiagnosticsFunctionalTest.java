package functional;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dareka.common.Logger;
import dareka.common.LoggerHandler;
import dareka.extensions.RequestFilter;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.impl.EasyRewriter;

/**
 * Exercises the production nlFilter parser and execution path used by the
 * diagnostic tests below.
 */
public final class NlFilterDiagnosticsFunctionalTest {
    private static final String URL =
            "https://www.nicovideo.jp/nlfilter-diagnostics";

    private NlFilterDiagnosticsFunctionalTest() {
        // utility class
    }

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        LoggerHandler original = Logger.getHandler();
        RecordingLogger logger = new RecordingLogger();
        Path directory = Files.createTempDirectory("nicocache-nlfilter-diagnostic-");
        Logger.setHandler(logger);
        try {
            testNormalReplacementStillUsesParsedFilter();
            testResponseGatesPermitNormalReplacement(directory, logger);
            testEachLinePartialMismatchIsDebugOnly(directory, logger);
            testMultiCompletionIsNotReportedAsZero(directory, logger);
            testAppendTargetMissing(directory, logger, "[Style]",
                    "style append target missing", "<html><body>fixture");
            testAppendTargetMissing(directory, logger, "[Script]",
                    "script append target missing", "<html><head>fixture");
            testUrlAppendTargetMissing(directory, logger);
            testUnresolvedVariableAfterMatch(directory, logger);
            testBodyReplacementFailureIsAnError(directory, logger);
            testSuccessfulSideEffectsAndIdGroupSkip(directory, logger);
            testRequestHeaderDiagnostics(directory, logger);
            testUnusedConfigDoesNotReportMatchZero(directory, logger);
            testDiagnosticLogsRedactQuerySecrets(directory, logger);
            NlFilterHealthFunctionalTest.run();
        } finally {
            Logger.setHandler(original);
            deleteTree(directory);
        }
    }

    private static void testNormalReplacementStillUsesParsedFilter()
            throws Exception {
        Path directory = Files.createTempDirectory("nicocache-nlfilter-test-");
        Path source = directory.resolve("normal-replacement.txt");
        try {
            Files.writeString(source, String.join("\n",
                    "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                    "[Replace]",
                    "Name = normal replacement",
                    "URL = example\\.invalid/normal",
                    "Match<",
                    "before-token",
                    ">",
                    "Replace<",
                    "after-token",
                    ">",
                    ""), StandardCharsets.UTF_8);

            EasyRewriter rewriter = newRewriter();
            Object file = filterFile(source);
            ArrayList<?> parsed = parse(rewriter, file);
            assertEquals(1, parsed.size(), "normal filter must parse");
            install(rewriter, parsed);

            String url = "https://example.invalid/normal";
            HttpResponseHeader responseHeader = new HttpResponseHeader(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n");
            ArrayList<?> matched = matched(rewriter, url, null, responseHeader);
            assertEquals(1, matched.size(), "normal filter must pass URL selection");

            String result = apply(rewriter, url, "before-token", null,
                    responseHeader, matched);
            assertEquals("after-token", result,
                    "parsed nlFilter must retain its normal replacement result");
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(directory);
        }
    }

    private static void testResponseGatesPermitNormalReplacement(Path directory,
            RecordingLogger logger) throws Exception {
        String name = "response gates normal replacement";
        Path source = writeFilter(directory, "response-gates-zero.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = " + name,
                "URL = www\\.nicovideo\\.jp/",
                "RequireHeader = X-Filter-Gate: allowed",
                "ContentType = text/html",
                "StatusCode = 201",
                "MatchLocal = TRUE",
                "Require = required-marker",
                "Match<",
                "required-marker",
                ">",
                "Replace<",
                "gate-replaced",
                ">"));
        EasyRewriter rewriter = newRewriter();
        parseAndInstall(rewriter, source);
        String localUrl = "https://www.nicovideo.jp/local/nlfilter-diagnostics";
        HttpRequestHeader request = request(localUrl, "X-Filter-Gate: allowed\r\n");
        HttpResponseHeader response = response(201, "text/html; charset=UTF-8");
        ArrayList<?> matched = matched(rewriter, localUrl, request, response);
        assertEquals(1, matched.size(),
                "all response gates must pass before a match is diagnosed");

        logger.clear();
        String result = apply(rewriter, localUrl,
                "required-marker", request, response, matched);
        assertEquals("gate-replaced", result,
                "all response gates must preserve ordinary replacement behavior");
        assertNoWarningCode(logger, "MATCH_ZERO",
                "a successful response-gated replacement must not warn");
        assertNoDebugCode(logger, "MATCH_ZERO",
                "MATCH_ZERO must not be emitted as debug output either");
    }

    private static void testEachLinePartialMismatchIsDebugOnly(Path directory,
            RecordingLogger logger) throws Exception {
        Path ordinarySource = writeFilter(directory, "each-line-ordinary.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = ordinary each line partial",
                "URL = www\\.nicovideo\\.jp/",
                "EachLine = TRUE",
                "Match<",
                "first-line",
                "missing-line",
                ">",
                "Replace<",
                "replaced-first-line",
                "unused-replacement",
                ">"));
        EasyRewriter ordinary = newRewriter();
        parseAndInstall(ordinary, ordinarySource);
        HttpResponseHeader response = response(200, "text/html");
        ArrayList<?> ordinaryMatched = matched(ordinary, URL, null, response);
        logger.clear();
        apply(ordinary, URL, "first-line\nother-line", null,
                response, ordinaryMatched);
        assertNoWarningCode(logger, "MATCH_ZERO",
                "a partial EachLine operation must not be a normal warning");

        Path debugSource = writeFilter(directory, "each-line-debug.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = debug each line partial",
                "URL = www\\.nicovideo\\.jp/",
                "EachLine = TRUE",
                "Debug = TRUE",
                "Match<",
                "first-line",
                "missing-line",
                ">",
                "Replace<",
                "replaced-first-line",
                "unused-replacement",
                ">"));
        EasyRewriter debug = newRewriter();
        parseAndInstall(debug, debugSource);
        ArrayList<?> debugMatched = matched(debug, URL, null, response);
        logger.clear();
        String result = apply(debug, URL, "first-line\nother-line",
                null, response, debugMatched);
        assertContains(result, "replaced-first-line",
                "the matching EachLine member must still be replaced");
        assertNoWarningCode(logger, "MATCH_ZERO",
                "debug-level partial mismatch must not escalate to MATCH_ZERO");
        assertTrue(logger.infoText().contains("[Debug]")
                        && logger.infoText().contains("noMatch(line="),
                "Debug=TRUE must expose the unmatched EachLine member in debug output");
    }

    private static void testMultiCompletionIsNotReportedAsZero(Path directory,
            RecordingLogger logger) throws Exception {
        Path source = writeFilter(directory, "multi-completion.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = multi completion",
                "URL = www\\.nicovideo\\.jp/",
                "Multi = TRUE",
                "Match<",
                "multi-token",
                ">",
                "Replace<",
                "replaced-token",
                ">"));
        EasyRewriter rewriter = newRewriter();
        parseAndInstall(rewriter, source);
        HttpResponseHeader response = response(200, "text/html");
        ArrayList<?> matched = matched(rewriter, URL, null, response);
        logger.clear();
        String result = apply(rewriter, URL,
                "multi-token multi-token", null, response, matched);
        assertEquals("replaced-token replaced-token", result,
                "Multi must replace every matching occurrence");
        assertNoWarningCode(logger, "MATCH_ZERO",
                "the terminal Multi search must not become a zero-match warning");
    }

    private static void testAppendTargetMissing(Path directory,
            RecordingLogger logger, String section, String name, String content)
            throws Exception {
        Path source = writeFilter(directory,
                section.equals("[Style]") ? "style-append-missing.txt"
                        : "script-append-missing.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                section,
                "Name = " + name,
                "URL = www\\.nicovideo\\.jp/",
                "Append<",
                "fixture-body",
                ">"));
        EasyRewriter rewriter = newRewriter();
        parseAndInstall(rewriter, source);
        HttpResponseHeader response = response(200, "text/html");
        ArrayList<?> matched = matched(rewriter, URL, null, response);
        logger.clear();
        String result = apply(rewriter, URL, content, null, response,
                matched);
        assertEquals(content, result,
                "a missing Append target must leave content intact");
        assertNoWarningCode(logger, "APPEND_TARGET_MISSING",
                "an initial Append miss must remain pending before scope close");
        closeDiagnostics(rewriter);
        assertWarningSource(logger, "APPEND_TARGET_MISSING", source, name);
    }

    private static void testUrlAppendTargetMissing(Path directory,
            RecordingLogger logger) throws Exception {
        String name = "URL append target missing";
        Path source = writeFilter(directory, "url-append-missing.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Script]",
                "Name = " + name,
                "URL = www\\.nicovideo\\.jp/",
                "Append<",
                "https://www.nicovideo.jp/local/fixture.js",
                ">"));
        EasyRewriter rewriter = newRewriter();
        parseAndInstall(rewriter, source);
        HttpResponseHeader response = response(200, "text/html");
        ArrayList<?> matched = matched(rewriter, URL, null, response);
        logger.clear();
        String content = "<html><head>fixture";
        String result = apply(rewriter, URL, content, null, response,
                matched);
        assertEquals(content, result,
                "a URL-form Append missing its target must leave content intact");
        assertNoWarningCode(logger, "APPEND_TARGET_MISSING",
                "a URL-form Append miss must remain pending before scope close");
        closeDiagnostics(rewriter);
        assertWarningSource(logger, "APPEND_TARGET_MISSING", source, name);
    }

    private static void testUnresolvedVariableAfterMatch(Path directory,
            RecordingLogger logger) throws Exception {
        String name = "unresolved variable";
        Path source = writeFilter(directory, "unresolved-variable.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = " + name,
                "URL = www\\.nicovideo\\.jp/",
                "Match<",
                "known-token",
                ">",
                "Replace<",
                "<nlVar:unassigned-diagnostic-variable>",
                ">"));
        EasyRewriter rewriter = newRewriter();
        parseAndInstall(rewriter, source);
        HttpResponseHeader response = response(200, "text/html");
        ArrayList<?> matched = matched(rewriter, URL, null, response);
        logger.clear();
        String result = apply(rewriter, URL, "known-token", null,
                response, matched);
        assertEquals("known-token", result,
                "an unresolved variable must leave the matched content intact");
        assertWarningContains(logger, "UNRESOLVED_VARIABLE", name,
                "the missing variable must be reported after its match");
        assertNoWarningCode(logger, "MATCH_ZERO",
                "a matched unresolved-variable failure is not a zero match");
    }

    private static void testBodyReplacementFailureIsAnError(Path directory,
            RecordingLogger logger) throws Exception {
        Path source = writeFilter(directory, "body-replacement-error.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = body replacement error",
                "URL = www\\.nicovideo\\.jp/",
                "Match<",
                "known-token",
                ">",
                "Replace<",
                "$9",
                ">"));
        EasyRewriter rewriter = newRewriter();
        parseAndInstall(rewriter, source);
        HttpResponseHeader response = response(200, "text/html");
        ArrayList<?> matched = matched(rewriter, URL, null, response);
        logger.clear();
        String result = apply(rewriter, URL, "known-token", null, response,
                matched);
        assertEquals("known-token", result,
                "a failed body replacement must preserve the source content");
        assertTrue(logger.hasErrorDiagnostic(),
                "a body replacement failure must be an error diagnostic");
    }

    private static void testSuccessfulSideEffectsAndIdGroupSkip(Path directory,
            RecordingLogger logger) throws Exception {
        Path list = directory.resolve("successful-add-list.txt");
        Path source = writeFilter(directory, "successful-side-effects.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = add variable",
                "URL = www\\.nicovideo\\.jp/",
                "AddVariable = stored-diagnostic-variable",
                "Match<",
                "capture-token",
                ">",
                "Replace<",
                "stored-variable-value",
                ">",
                "[Replace]",
                "Name = consume variable",
                "URL = www\\.nicovideo\\.jp/",
                "Match<",
                "target-token",
                ">",
                "Replace<",
                "<nlVar:stored-diagnostic-variable>",
                ">",
                "[Replace]",
                "Name = add list",
                "URL = www\\.nicovideo\\.jp/",
                "AddList = " + list,
                "Match<",
                "list-token",
                ">",
                "Replace<",
                "stored-list-value",
                ">"));
        EasyRewriter rewriter = newRewriter();
        parseAndInstall(rewriter, source);
        HttpResponseHeader response = response(200, "text/html");
        ArrayList<?> matched = matched(rewriter, URL, null, response);
        assertEquals(3, matched.size(), "all side-effect filters must match");
        logger.clear();
        String result = apply(rewriter, URL,
                "capture-token target-token list-token", null, response, matched);
        assertContains(result, "stored-variable-value",
                "AddVariable must make its successful value observable");
        assertContains(Files.readString(list, StandardCharsets.UTF_8),
                "stored-list-value", "AddList must persist the matched value");
        assertNoWarningCode(logger, "MATCH_ZERO",
                "successful AddVariable and AddList are not zero matches");

        Path idGroupSource = writeFilter(directory, "id-group-condition.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = id group cache condition",
                "URL = www\\.nicovideo\\.jp/",
                "idGroup = 1",
                "Match<",
                "(sm99999999)",
                ">",
                "Replace<",
                "cached-token",
                ">"));
        EasyRewriter idGroup = newRewriter();
        parseAndInstall(idGroup, idGroupSource);
        ArrayList<?> idGroupMatched = matched(idGroup, URL, null, response);
        logger.clear();
        String unchanged = apply(idGroup, URL, "sm99999999", null,
                response, idGroupMatched);
        assertEquals("sm99999999", unchanged,
                "an unmet idGroup cache condition must preserve the content");
        assertNoWarningCode(logger, "MATCH_ZERO",
                "an idGroup cache-condition skip is not a zero match");
    }

    private static void testRequestHeaderDiagnostics(Path directory,
            RecordingLogger logger) throws Exception {
        Path gateSource = writeFilter(directory, "request-header-gate.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[RequestHeader]",
                "Name = request header URL gate",
                "Match<",
                "https://example\\.invalid/selected",
                ">",
                "Replace<",
                "https://example.invalid/rewritten",
                ">"));
        EasyRewriter gate = newRewriter();
        parseAndInstall(gate, gateSource);
        preventReload(gate);
        HttpRequestHeader skipped = request("https://example.invalid/other", "");
        logger.clear();
        assertEquals(RequestFilter.OK, gate.onRequest(skipped),
                "an unmatched RequestHeader filter must keep the request");
        assertEquals("https://example.invalid/other", skipped.getURI(),
                "an unmatched RequestHeader filter must not change the URI");
        assertNoWarningCode(logger, "MATCH_ZERO",
                "RequestHeader Match is a URL gate and must not warn on mismatch");

        Path failureSource = writeFilter(directory, "request-header-error.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[RequestHeader]",
                "Name = request header replacement error",
                "Match<",
                "https://example\\.invalid/selected",
                ">",
                "Replace<",
                "$9",
                ">"));
        EasyRewriter failure = newRewriter();
        parseAndInstall(failure, failureSource);
        preventReload(failure);
        HttpRequestHeader selected = request("https://example.invalid/selected", "");
        logger.clear();
        assertEquals(RequestFilter.DROP, failure.onRequest(selected),
                "a request-header replacement error must retain the DROP contract");
        assertTrue(logger.hasErrorDiagnostic(),
                "a request-header replacement failure must be an error diagnostic");
    }

    private static void testUnusedConfigDoesNotReportMatchZero(Path directory,
            RecordingLogger logger) throws Exception {
        Path source = writeFilter(directory, "unused-config.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Config]",
                "Name = unused config",
                "Match<",
                "config-token",
                ">",
                "Replace<",
                "config-value",
                ">"));
        EasyRewriter rewriter = newRewriter();
        logger.clear();
        parseAndInstall(rewriter, source);
        assertNoWarningCode(logger, "MATCH_ZERO",
                "an unused Config definition must not report a match warning");
    }

    private static void testDiagnosticLogsRedactQuerySecrets(Path directory,
            RecordingLogger logger) throws Exception {
        String secret = "secret-query-value-8729";
        String url = "https://example.invalid/redacted?token=" + secret;
        Path source = writeFilter(directory, "redacted-query.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = redacted query",
                "FullURL = https://example\\.invalid/redacted\\?token=" + secret,
                "Match<",
                "known-token",
                ">",
                "Replace<",
                "<nlVar:unassigned-redaction-variable>",
                ">"));
        EasyRewriter rewriter = newRewriter();
        parseAndInstall(rewriter, source);
        HttpResponseHeader response = response(200, "text/html");
        ArrayList<?> matched = matched(rewriter, url, null, response);
        assertEquals(1, matched.size(), "redaction fixture must reach the filter");
        logger.clear();
        apply(rewriter, url, "known-token", null, response, matched);
        assertWarningContains(logger, "UNRESOLVED_VARIABLE", "redacted query",
                "redaction fixture must emit an immediate variable diagnostic");
        assertNotContains(logger.warningText(), secret,
                "normal diagnostic logs must redact query secrets");
    }

    private static Object filterFile(Path source) throws Exception {
        Class<?> type = nested("FilterFile");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(source.toString());
    }

    private static ArrayList<?> parseAndInstall(EasyRewriter rewriter,
            Path source) throws Exception {
        ArrayList<?> filters = parse(rewriter, filterFile(source));
        install(rewriter, filters);
        return filters;
    }

    private static ArrayList<?> parse(EasyRewriter rewriter, Object file)
            throws Exception {
        Method parse = EasyRewriter.class.getDeclaredMethod("parseFilterFile",
                file.getClass());
        parse.setAccessible(true);
        parse.invoke(rewriter, file);
        Field parsed = file.getClass().getDeclaredField("parsed");
        parsed.setAccessible(true);
        Object value = parsed.get(file);
        if (!(value instanceof ArrayList<?>)) {
            throw new AssertionError("parsed filter list must be an ArrayList");
        }
        return (ArrayList<?>) value;
    }

    private static EasyRewriter newRewriter() throws Exception {
        Constructor<EasyRewriter> constructor =
                EasyRewriter.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void install(EasyRewriter rewriter,
            ArrayList<?> filters) throws Exception {
        Field field = EasyRewriter.class.getDeclaredField("filterLists");
        field.setAccessible(true);
        Object lists = field.get(rewriter);
        Method addFilter = EasyRewriter.class.getDeclaredMethod("addFilter",
                nested("UserFilter"), lists.getClass());
        addFilter.setAccessible(true);
        for (Object filter : filters) {
            addFilter.invoke(rewriter, filter, lists);
        }
    }

    private static ArrayList<?> matched(EasyRewriter rewriter, String url,
            HttpRequestHeader request, HttpResponseHeader response)
            throws Exception {
        Method method = EasyRewriter.class.getDeclaredMethod(
                "getMatchedUserFilter", String.class, HttpRequestHeader.class,
                HttpResponseHeader.class);
        method.setAccessible(true);
        Object value = method.invoke(rewriter, url, request, response);
        if (!(value instanceof ArrayList<?>)) {
            throw new AssertionError("matched filter list must be an ArrayList");
        }
        return (ArrayList<?>) value;
    }

    private static String apply(EasyRewriter rewriter, String url,
            String content, HttpRequestHeader request,
            HttpResponseHeader response, ArrayList<?> filters) throws Exception {
        Method method = EasyRewriter.class.getDeclaredMethod("applyUserFilter",
                String.class, String.class, HttpRequestHeader.class,
                HttpResponseHeader.class, ArrayList.class);
        method.setAccessible(true);
        return (String) method.invoke(rewriter, url, content, request, response,
                filters);
    }

    private static void preventReload(EasyRewriter rewriter) throws Exception {
        Field nextCheckTime = EasyRewriter.class.getDeclaredField("nextCheckTime");
        nextCheckTime.setAccessible(true);
        nextCheckTime.setLong(rewriter, Long.MAX_VALUE);
    }

    private static void closeDiagnostics(EasyRewriter rewriter) throws Exception {
        Method close = EasyRewriter.class.getDeclaredMethod(
                "closeInstanceDiagnostics");
        close.setAccessible(true);
        close.invoke(rewriter);
    }

    private static Class<?> nested(String name) throws ClassNotFoundException {
        return Class.forName(EasyRewriter.class.getName() + "$" + name);
    }

    private static Path writeFilter(Path directory, String filename,
            String contents) throws IOException {
        Path source = directory.resolve(filename);
        Files.writeString(source, contents, StandardCharsets.UTF_8);
        return source;
    }

    private static String filter(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static HttpResponseHeader response(int status, String contentType)
            throws IOException {
        return new HttpResponseHeader("HTTP/1.1 " + status + " Fixture\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n");
    }

    private static HttpRequestHeader request(String uri, String extraHeaders)
            throws IOException {
        String headers = extraHeaders == null ? "" : extraHeaders;
        if (!headers.isEmpty() && !headers.endsWith("\r\n")) {
            headers += "\r\n";
        }
        return new HttpRequestHeader("GET " + uri + " HTTP/1.1\r\n"
                + "Host: example.invalid\r\n" + headers + "\r\n");
    }

    private static void assertWarningSource(RecordingLogger logger,
            String code, Path source, String name) {
        String message = logger.firstWarningWith(code);
        if (message == null) {
            throw new AssertionError("missing warning code: " + code
                    + ", warnings=" + logger.warningText());
        }
        assertContains(message, source.getFileName().toString(),
                "append diagnostic must retain its source file");
        assertContains(message, name,
                "append diagnostic must retain its original Name");
        Pattern line = Pattern.compile("(?is)(?:\\bline\\b|行)\\D{0,16}\\d+"
                + "|\\.txt\\s*(?:[:#(]\\s*)\\d+");
        assertTrue(line.matcher(message).find(),
                "append diagnostic must retain a source line: " + message);
    }

    private static void assertWarningContains(RecordingLogger logger,
            String code, String expected, String message) {
        String diagnostic = logger.firstWarningWith(code);
        if (diagnostic == null) {
            throw new AssertionError(message + ": missing code=" + code
                    + ", warnings=" + logger.warningText());
        }
        assertContains(diagnostic, expected, message);
    }

    private static void assertNoWarningCode(RecordingLogger logger,
            String code, String message) {
        if (logger.warningCodeCount(code) != 0) {
            throw new AssertionError(message + ": warnings="
                    + logger.warningText());
        }
    }

    private static void assertNoDebugCode(RecordingLogger logger, String code,
            String message) {
        if (logger.debugText().contains(code)) {
            throw new AssertionError(message + ": debug=" + logger.debugText());
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> reverseOrder = paths.sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            for (Path path : reverseOrder) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class RecordingLogger implements LoggerHandler {
        private final List<String> debug = new ArrayList<>();
        private final List<String> info = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();

        @Override
        public synchronized void debug(String message) {
            debug.add(message);
        }

        @Override
        public synchronized void debug(Throwable error) {
            errors.add(error);
        }

        @Override
        public synchronized void debugWithThread(String message) {
            debug.add(message);
        }

        @Override
        public synchronized void debugWithThread(Throwable error) {
            errors.add(error);
        }

        @Override
        public synchronized void info(String message) {
            info.add(message);
        }

        @Override
        public synchronized void info(String format, Object... arguments) {
            info.add(String.format(format, arguments));
        }

        @Override
        public synchronized void warning(String message) {
            warnings.add(message);
        }

        @Override
        public synchronized void error(Throwable error) {
            errors.add(error);
        }

        synchronized void clear() {
            debug.clear();
            info.clear();
            warnings.clear();
            errors.clear();
        }

        synchronized int warningCodeCount(String code) {
            int count = 0;
            for (String warning : warnings) {
                if (warning.contains(code)) {
                    count++;
                }
            }
            return count;
        }

        synchronized String firstWarningWith(String code) {
            for (String warning : warnings) {
                if (warning.contains(code)) {
                    return warning;
                }
            }
            return null;
        }

        synchronized boolean hasErrorDiagnostic() {
            if (!errors.isEmpty()) {
                return true;
            }
            for (String warning : warnings) {
                if (warning.contains("[ERROR]")) {
                    return true;
                }
            }
            return false;
        }

        synchronized String warningText() {
            return String.join("\n", warnings);
        }

        synchronized String debugText() {
            return String.join("\n", debug);
        }

        synchronized String infoText() {
            return String.join("\n", info);
        }
    }

    private static void assertContains(String actual, String expected,
            String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + ": expected fragment=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertNotContains(String actual, String unexpected,
            String message) {
        if (actual != null && actual.contains(unexpected)) {
            throw new AssertionError(message + ": unexpected fragment="
                    + unexpected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual,
            String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }
}
