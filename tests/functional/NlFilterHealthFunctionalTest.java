package functional;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dareka.common.Logger;
import dareka.common.LoggerHandler;
import dareka.processor.HttpResponseHeader;
import dareka.processor.impl.EasyRewriter;

/**
 * Verifies that broad filters are considered healthy after one confirmed
 * match per parsed source, while real failures stay visible.
 */
public final class NlFilterHealthFunctionalTest {
    private static final String JS_URL_PREFIX =
            "https://www.nicovideo.jp/assets/diagnostic-";

    private NlFilterHealthFunctionalTest() {
        // utility class
    }

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        LoggerHandler original = Logger.getHandler();
        RecordingLogger logger = new RecordingLogger();
        Path directory = Files.createTempDirectory("nicocache-nlfilter-health-");
        Logger.setHandler(logger);
        try {
            testBroadUrlMissHitMissAndHitMiss(directory, logger);
            testEachLineMultiAndIdGroupConfirmHealth(directory, logger);
            testNormalAndUrlAppendConfirmHealth(directory, logger);
            testObservedMissAggregationAndLatestSafeContext();
            testConfirmationErrorsAndScopeReset();
            testImmediateErrorRateLimitSurvivesConfirmation();
            testAppendAndClosedScopeHealth();
            testSameFileAndNameSourcesStayIndependent();
            testEightUnconfirmedMissesFlushOnceOnClose();
            testConcurrentHitAndMissConfirmHealth();
        } finally {
            Logger.setHandler(original);
            deleteTree(directory);
        }
    }

    private static void testBroadUrlMissHitMissAndHitMiss(Path directory,
            RecordingLogger logger) throws Exception {
        Path source = writeFilter(directory, "broad-js-health.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = broad JavaScript health",
                "URL = www\\.nicovideo\\.jp/assets/diagnostic-",
                "ContentType = application/javascript",
                "Multi = TRUE",
                "Match<",
                "health-token",
                ">",
                "Replace<",
                "health-replaced",
                ">"));
        EasyRewriter missHitMiss = newRewriter();
        parseAndInstall(missHitMiss, source);
        HttpResponseHeader response = response(200, "application/javascript");

        logger.clear();
        assertEquals("plain source", apply(missHitMiss, JS_URL_PREFIX + "a.js",
                "plain source", response), "first broad URL miss leaves response intact");
        assertNoDiagnostic(logger, "MATCH_ZERO",
                "the first unconfirmed miss must stay pending");
        assertEquals("health-replaced health-replaced",
                apply(missHitMiss, JS_URL_PREFIX + "b.js",
                        "health-token health-token", response),
                "one JavaScript response confirms the broad filter");
        assertNoDiagnostic(logger, "MATCH_CONFIRMED",
                "a match before the first warning must stay quiet");
        assertEquals("another plain source", apply(missHitMiss,
                JS_URL_PREFIX + "c.js", "another plain source", response),
                "later broad URL miss leaves response intact");
        assertNoDiagnostic(logger, "MATCH_ZERO",
                "miss-hit-miss must not warn after the first confirmed match");

        EasyRewriter hitMiss = newRewriter();
        parseAndInstall(hitMiss, source);
        logger.clear();
        apply(hitMiss, JS_URL_PREFIX + "d.js", "health-token", response);
        apply(hitMiss, JS_URL_PREFIX + "e.js", "plain source", response);
        assertNoDiagnostic(logger, "MATCH_ZERO",
                "hit-miss must not warn after the source is known healthy");

        EasyRewriter onlyMiss = newRewriter();
        parseAndInstall(onlyMiss, source);
        logger.clear();
        apply(onlyMiss, JS_URL_PREFIX + "f.js", "plain source", response);
        assertNoDiagnostic(logger, "MATCH_ZERO",
                "an unconfirmed miss must remain pending before scope close");
        closeDiagnostics(onlyMiss);
        assertDiagnostic(logger, "MATCH_ZERO",
                "closing an always-missing real filter must flush one warning");
    }

    private static void testEachLineMultiAndIdGroupConfirmHealth(Path directory,
            RecordingLogger logger) throws Exception {
        HttpResponseHeader response = response(200, "application/javascript");
        Path eachLine = writeFilter(directory, "each-line-health.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = each line multi health",
                "URL = www\\.nicovideo\\.jp/assets/diagnostic-",
                "EachLine = TRUE",
                "Multi = TRUE",
                "Match<",
                "first-token",
                "second-token",
                ">",
                "Replace<",
                "first-replaced",
                "second-replaced",
                ">"));
        EasyRewriter eachLineRewriter = newRewriter();
        parseAndInstall(eachLineRewriter, eachLine);
        logger.clear();
        assertEquals("first-replaced second-replaced", apply(eachLineRewriter,
                JS_URL_PREFIX + "each.js", "first-token second-token", response),
                "EachLine and Multi matches must confirm the source");
        apply(eachLineRewriter, JS_URL_PREFIX + "each-miss.js", "plain", response);
        assertNoDiagnostic(logger, "MATCH_ZERO",
                "a later EachLine/Multi miss must not warn after a match");

        Path idGroup = writeFilter(directory, "id-group-health.txt", filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = idGroup health",
                "URL = www\\.nicovideo\\.jp/assets/diagnostic-",
                "idGroup = 1",
                "Match<",
                "(sm99999999)",
                ">",
                "Replace<",
                "cached-value",
                ">"));
        EasyRewriter idGroupRewriter = newRewriter();
        parseAndInstall(idGroupRewriter, idGroup);
        logger.clear();
        assertEquals("sm99999999", apply(idGroupRewriter,
                JS_URL_PREFIX + "id-group.js", "sm99999999", response),
                "an unmet idGroup condition preserves the source response");
        apply(idGroupRewriter, JS_URL_PREFIX + "id-group-miss.js", "plain", response);
        assertNoDiagnostic(logger, "MATCH_ZERO",
                "an idGroup match confirms health even when its cache action is skipped");
    }

    private static void testNormalAndUrlAppendConfirmHealth(Path directory,
            RecordingLogger logger) throws Exception {
        testAppendConfirmation(directory, logger, "[Style]",
                "normal append health", "fixture-style", "</head>",
                "normal-append-health.txt");
        testAppendConfirmation(directory, logger, "[Script]",
                "URL append health", "https://www.nicovideo.jp/local/fixture.js",
                "</body>", "url-append-health.txt");
    }

    private static void testAppendConfirmation(Path directory,
            RecordingLogger logger, String section, String name, String append,
            String target, String filename) throws Exception {
        Path source = writeFilter(directory, filename, filter(
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                section,
                "Name = " + name,
                "URL = www\\.nicovideo\\.jp/assets/diagnostic-",
                "Append<",
                append,
                ">"));
        EasyRewriter rewriter = newRewriter();
        parseAndInstall(rewriter, source);
        HttpResponseHeader response = response(200, "text/html");
        logger.clear();
        apply(rewriter, JS_URL_PREFIX + filename, "<html><body>plain", response);
        assertNoDiagnostic(logger, "APPEND_TARGET_MISSING",
                "an initial Append target miss must remain pending");
        String matched = apply(rewriter, JS_URL_PREFIX + filename + "-hit",
                "<html><head></head><body>" + target + "</body></html>", response);
        assertContains(matched, append.startsWith("http") ? "<script" : append,
                "a real append target confirms the source");
        apply(rewriter, JS_URL_PREFIX + filename + "-later",
                "<html><body>plain", response);
        closeDiagnostics(rewriter);
        assertNoDiagnostic(logger, "APPEND_TARGET_MISSING",
                "normal and URL Append misses stay silent after confirmation");
    }

    private static void testObservedMissAggregationAndLatestSafeContext()
            throws Exception {
        AtomicLong clock = new AtomicLong();
        List<String> messages = new ArrayList<>();
        DiagnosticsHarness diagnostics = new DiagnosticsHarness(clock::get,
                messages::add);
        Object source = diagnostics.source("observation.txt", "observation", false);
        diagnostics.miss(source, "https://example.invalid/first?token=first-secret");
        clock.set(diagnostics.observationIntervalMillis() - 1);
        diagnostics.miss(source, "https://example.invalid/second?token=second-secret");
        assertEquals(0, messages.size(),
                "unconfirmed misses must be quiet before the observation interval");
        clock.set(diagnostics.observationIntervalMillis());
        diagnostics.miss(source, "https://example.invalid/last?token=last-secret");
        assertEquals(1, messages.size(),
                "the next miss at 60 seconds emits one aggregate warning");
        assertContains(messages.get(0), "MATCH_ZERO",
                "the observed warning retains its reason code");
        assertContains(messages.get(0), "未一致応答計=3件",
                "the observed warning reports every pending response");
        assertContains(messages.get(0), "/last", "the last response context is retained");
        assertNotContains(messages.get(0), "last-secret",
                "the last response query stays redacted");

        clock.incrementAndGet();
        diagnostics.miss(source, "https://example.invalid/fourth?token=fourth-secret");
        assertEquals(1, messages.size(), "the next interval starts pending again");
        clock.set(diagnostics.observationIntervalMillis() * 2L);
        diagnostics.miss(source, "https://example.invalid/fifth?token=fifth-secret");
        assertEquals(2, messages.size(), "the second interval emits one repeat");
        assertContains(messages.get(1), "未一致応答計=5件",
                "the total count remains cumulative within the scope");
        assertContains(messages.get(1), "追加2回",
                "the repeat message reports only newly pending responses");

        Object other = diagnostics.source("other-definition.txt", "other", false);
        diagnostics.miss(other, "https://example.invalid/other");
        diagnostics.close();
        assertEquals(3, messages.size(),
                "a different source gets an independent close-time aggregate");
        assertContains(messages.get(2), "other-definition.txt",
                "independent definitions must not be mixed together");
    }

    private static void testConfirmationErrorsAndScopeReset() throws Exception {
        AtomicLong clock = new AtomicLong();
        List<String> messages = new ArrayList<>();
        DiagnosticsHarness diagnostics = new DiagnosticsHarness(clock::get,
                messages::add);
        Object source = diagnostics.source("confirmation.txt", "confirmation", false);
        diagnostics.miss(source, "https://example.invalid/miss");
        clock.set(diagnostics.observationIntervalMillis());
        diagnostics.miss(source, "https://example.invalid/notified");
        assertEquals(1, messages.size(), "the source has now produced its first warning");
        diagnostics.hit(source, "https://example.invalid/hit");
        assertEquals(2, messages.size(),
                "a hit after a warning emits one MATCH_CONFIRMED info message");
        assertContains(messages.get(1), "[INFO][nlFilter][MATCH_CONFIRMED]",
                "confirmation uses the info channel and stable code");
        diagnostics.hit(source, "https://example.invalid/hit-again");
        diagnostics.miss(source, "https://example.invalid/later-miss");
        diagnostics.close();
        assertEquals(2, messages.size(),
                "confirmed sources suppress later misses and duplicate confirmations");

        List<String> quietMessages = new ArrayList<>();
        DiagnosticsHarness quiet = new DiagnosticsHarness(clock::get,
                quietMessages::add);
        Object quietSource = quiet.source("quiet-confirmation.txt", "quiet", false);
        quiet.miss(quietSource, "https://example.invalid/first-miss");
        quiet.hit(quietSource, "https://example.invalid/first-hit");
        quiet.close();
        assertEquals(0, quietMessages.size(),
                "a hit before a warning is completely quiet");

        List<String> errorMessages = new ArrayList<>();
        DiagnosticsHarness errorDiagnostics = new DiagnosticsHarness(clock::get,
                errorMessages::add);
        Object errorSource = errorDiagnostics.source("errors.txt", "errors", false);
        errorDiagnostics.hitWithProblem(errorSource,
                "https://example.invalid/error", "PROCESSING_ERROR");
        assertEquals(1, errorMessages.size(),
                "a processing error remains immediate after a confirmed match");
        assertContains(errorMessages.get(0), "[ERROR][nlFilter][PROCESSING_ERROR]",
                "real errors are never hidden by health confirmation");

        List<String> newScopeMessages = new ArrayList<>();
        DiagnosticsHarness newScope = new DiagnosticsHarness(clock::get,
                newScopeMessages::add);
        newScope.miss(quietSource, "https://example.invalid/new-scope-miss");
        newScope.close();
        assertEquals(1, newScopeMessages.size(),
                "a new diagnostics scope must require confirmation again");
        assertContains(newScopeMessages.get(0), "MATCH_ZERO",
                "new scopes reset health without changing the definition identity");
    }

    private static void testAppendAndClosedScopeHealth() throws Exception {
        AtomicLong clock = new AtomicLong();
        List<String> messages = new ArrayList<>();
        DiagnosticsHarness diagnostics = new DiagnosticsHarness(clock::get,
                messages::add);
        Object append = diagnostics.source("append.txt", "append", true);
        diagnostics.miss(append, "https://example.invalid/append-miss");
        diagnostics.hit(append, "https://example.invalid/append-hit");
        diagnostics.close();
        assertEquals(0, messages.size(),
                "a confirmed Append source must not emit APPEND_TARGET_MISSING on close");

        List<String> missingAppendMessages = new ArrayList<>();
        DiagnosticsHarness missingAppend = new DiagnosticsHarness(clock::get,
                missingAppendMessages::add);
        Object missing = missingAppend.source("missing-append.txt", "missing", true);
        missingAppend.miss(missing, "https://example.invalid/append-only-miss");
        missingAppend.close();
        assertEquals(1, missingAppendMessages.size(),
                "an unconfirmed Append source flushes at close");
        assertContains(missingAppendMessages.get(0), "APPEND_TARGET_MISSING",
                "Append sources retain their dedicated reason code");

        List<String> oldRunMessages = new ArrayList<>();
        DiagnosticsHarness oldRun = new DiagnosticsHarness(clock::get,
                oldRunMessages::add);
        Object known = oldRun.source("known-old-run.txt", "known", false);
        oldRun.hit(known, "https://example.invalid/known-hit");
        oldRun.close();
        oldRun.miss(known, "https://example.invalid/known-late-miss");
        assertEquals(0, oldRunMessages.size(),
                "closed scopes keep confirmed health for late old runs");

        Object unknown = oldRun.source("unknown-old-run.txt", "unknown", false);
        oldRun.miss(unknown, "https://example.invalid/unknown-late-miss");
        assertEquals(1, oldRunMessages.size(),
                "a late unconfirmed old run is flushed immediately after close");
        assertContains(oldRunMessages.get(0), "MATCH_ZERO",
                "late unconfirmed old runs must not be lost");
    }

    private static void testImmediateErrorRateLimitSurvivesConfirmation()
            throws Exception {
        AtomicLong clock = new AtomicLong();
        List<String> messages = new ArrayList<>();
        DiagnosticsHarness diagnostics = new DiagnosticsHarness(clock::get,
                messages::add);
        Object source = diagnostics.source("unresolved.txt", "unresolved", false);
        diagnostics.hitWithProblem(source, "https://example.invalid/first",
                "UNRESOLVED_VARIABLE");
        assertEquals(1, messages.size(),
                "an unresolved variable must be reported immediately");
        assertContains(messages.get(0), "UNRESOLVED_VARIABLE",
                "the immediate error keeps its reason code");
        clock.incrementAndGet();
        diagnostics.hitWithProblem(source, "https://example.invalid/second",
                "UNRESOLVED_VARIABLE");
        assertEquals(1, messages.size(),
                "ordinary error repeats remain rate limited after confirmation");
        clock.set(diagnostics.repeatIntervalMillis());
        diagnostics.hitWithProblem(source, "https://example.invalid/third",
                "UNRESOLVED_VARIABLE");
        assertEquals(2, messages.size(),
                "the next error at 60 seconds flushes its pending repeats");
        assertContains(messages.get(1), "追加2回",
                "error aggregation retains every pending repeat");
        diagnostics.close();
        assertEquals(2, messages.size(),
                "closing after an error flush must not duplicate it");
    }

    private static void testConcurrentHitAndMissConfirmHealth() throws Exception {
        List<String> messages = new ArrayList<>();
        DiagnosticsHarness diagnostics = new DiagnosticsHarness(() -> 0L,
                messages::add);
        Object source = diagnostics.source("parallel.txt", "parallel", false);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                tasks.add(() -> {
                    diagnostics.miss(source, "https://example.invalid/parallel-miss");
                    return null;
                });
            }
            tasks.add(() -> {
                diagnostics.hit(source, "https://example.invalid/parallel-hit");
                return null;
            });
            for (Future<Void> result : executor.invokeAll(tasks)) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }
        diagnostics.close();
        assertEquals(0, messages.size(),
                "a concurrent confirmed match suppresses pending misses safely");
    }

    private static void testSameFileAndNameSourcesStayIndependent()
            throws Exception {
        List<String> messages = new ArrayList<>();
        DiagnosticsHarness diagnostics = new DiagnosticsHarness(() -> 0L,
                messages::add);
        Object firstDefinition = diagnostics.source("same-source.txt",
                "same Name", false);
        Object secondDefinition = diagnostics.source("same-source.txt",
                "same Name", false);
        diagnostics.hit(firstDefinition, "https://example.invalid/first-success");
        diagnostics.miss(secondDefinition, "https://example.invalid/second-miss");
        diagnostics.close();
        assertEquals(1, messages.size(),
                "a successful sibling definition must not hide another definition's miss");
        assertContains(messages.get(0), "MATCH_ZERO",
                "the independently missing definition must retain MATCH_ZERO");
        assertContains(messages.get(0), "same-source.txt",
                "the independent warning still identifies the shared source file");
    }

    private static void testEightUnconfirmedMissesFlushOnceOnClose()
            throws Exception {
        List<String> messages = new ArrayList<>();
        DiagnosticsHarness diagnostics = new DiagnosticsHarness(() -> 0L,
                messages::add);
        Object source = diagnostics.source("eight-misses.txt", "eight", false);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                final String url = "https://example.invalid/miss-" + index;
                tasks.add(() -> {
                    diagnostics.miss(source, url);
                    return null;
                });
            }
            for (Future<Void> result : executor.invokeAll(tasks)) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, messages.size(),
                "all early unconfirmed misses remain pending before close");
        diagnostics.close();
        assertEquals(1, messages.size(),
                "close must emit one aggregate warning for eight misses");
        assertContains(messages.get(0), "未一致応答計=8件",
                "the close aggregate must retain every unconfirmed miss");
        diagnostics.close();
        assertEquals(1, messages.size(),
                "a second close must not duplicate an already flushed aggregate");
    }

    private static EasyRewriter newRewriter() throws Exception {
        Constructor<EasyRewriter> constructor =
                EasyRewriter.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void parseAndInstall(EasyRewriter rewriter, Path source)
            throws Exception {
        Object file = filterFile(source);
        Method parse = EasyRewriter.class.getDeclaredMethod("parseFilterFile",
                file.getClass());
        parse.setAccessible(true);
        parse.invoke(rewriter, file);
        Field parsed = file.getClass().getDeclaredField("parsed");
        parsed.setAccessible(true);
        Object value = parsed.get(file);
        if (!(value instanceof ArrayList<?>)) {
            throw new AssertionError("parsed filters must be an ArrayList");
        }
        Field filterLists = EasyRewriter.class.getDeclaredField("filterLists");
        filterLists.setAccessible(true);
        Object lists = filterLists.get(rewriter);
        Method addFilter = EasyRewriter.class.getDeclaredMethod("addFilter",
                nested("UserFilter"), lists.getClass());
        addFilter.setAccessible(true);
        for (Object filter : (ArrayList<?>) value) {
            addFilter.invoke(rewriter, filter, lists);
        }
    }

    private static Object filterFile(Path source) throws Exception {
        Class<?> type = nested("FilterFile");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(source.toString());
    }

    private static String apply(EasyRewriter rewriter, String url,
            String content, HttpResponseHeader response) throws Exception {
        Method matched = EasyRewriter.class.getDeclaredMethod(
                "getMatchedUserFilter", String.class,
                Class.forName("dareka.processor.HttpRequestHeader"),
                HttpResponseHeader.class);
        matched.setAccessible(true);
        Object filters = matched.invoke(rewriter, url, null, response);
        Method apply = EasyRewriter.class.getDeclaredMethod("applyUserFilter",
                String.class, String.class,
                Class.forName("dareka.processor.HttpRequestHeader"),
                HttpResponseHeader.class, ArrayList.class);
        apply.setAccessible(true);
        return (String) apply.invoke(rewriter, url, content, null, response,
                filters);
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

    private static void deleteTree(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> reverseOrder = paths.sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            for (Path path : reverseOrder) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void assertDiagnostic(RecordingLogger logger, String code,
            String message) {
        if (!logger.allText().contains(code)) {
            throw new AssertionError(message + ": logs=" + logger.allText());
        }
    }

    private static void assertNoDiagnostic(RecordingLogger logger, String code,
            String message) {
        if (logger.allText().contains(code)) {
            throw new AssertionError(message + ": logs=" + logger.allText());
        }
    }

    private static void assertContains(String actual, String expected,
            String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertNotContains(String actual, String unexpected,
            String message) {
        if (actual != null && actual.contains(unexpected)) {
            throw new AssertionError(message + ": unexpected=" + unexpected
                    + ", actual=" + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual,
            String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static final class RecordingLogger implements LoggerHandler {
        private final List<String> info = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> debug = new ArrayList<>();
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
            info.clear();
            warnings.clear();
            debug.clear();
            errors.clear();
        }

        synchronized String allText() {
            List<String> values = new ArrayList<>();
            values.addAll(info);
            values.addAll(warnings);
            values.addAll(debug);
            for (Throwable error : errors) {
                values.add(error.getClass().getSimpleName());
            }
            return String.join("\n", values);
        }
    }

    /** Reflects package-private diagnostic internals without adding ABI surface. */
    private static final class DiagnosticsHarness {
        private static final String TYPE =
                "dareka.processor.impl.NlFilterDiagnostics";

        private final Object diagnostics;
        private final Class<?> sourceType;
        private final Class<?> contextType;
        private final Class<?> reasonType;
        private final Constructor<?> sourceConstructor;
        private final Constructor<?> contextConstructor;
        private final Method begin;
        private final Method close;
        private final long observationIntervalMillis;
        private final long repeatIntervalMillis;

        DiagnosticsHarness(LongSupplier clock, Consumer<String> sink)
                throws Exception {
            Class<?> diagnosticType = Class.forName(TYPE);
            Constructor<?> constructor = diagnosticType.getDeclaredConstructor(
                    LongSupplier.class, Consumer.class);
            constructor.setAccessible(true);
            diagnostics = constructor.newInstance(clock, sink);
            sourceType = Class.forName(TYPE + "$Source");
            contextType = Class.forName(TYPE + "$Context");
            reasonType = Class.forName(TYPE + "$Reason");
            sourceConstructor = sourceType.getDeclaredConstructor();
            sourceConstructor.setAccessible(true);
            contextConstructor = contextType.getDeclaredConstructor(String.class,
                    HttpResponseHeader.class);
            contextConstructor.setAccessible(true);
            begin = diagnosticType.getDeclaredMethod("begin", sourceType,
                    contextType);
            begin.setAccessible(true);
            close = diagnosticType.getDeclaredMethod("close");
            close.setAccessible(true);
            Field interval = diagnosticType.getDeclaredField(
                    "OBSERVATION_INTERVAL_MILLIS");
            interval.setAccessible(true);
            observationIntervalMillis = interval.getLong(null);
            Field repeatInterval = diagnosticType.getDeclaredField(
                    "REPEAT_INTERVAL_MILLIS");
            repeatInterval.setAccessible(true);
            repeatIntervalMillis = repeatInterval.getLong(null);
        }

        Object source(String file, String name, boolean append) throws Exception {
            Object source = sourceConstructor.newInstance();
            set(source, "file", file);
            set(source, "name", name);
            set(source, "sectionLine", 1);
            set(source, "operationLine", 2);
            set(source, "append", append);
            if (append) {
                set(source, "appendTarget", "</body>");
            }
            return source;
        }

        long observationIntervalMillis() {
            return observationIntervalMillis;
        }

        long repeatIntervalMillis() {
            return repeatIntervalMillis;
        }

        void miss(Object source, String url) throws Exception {
            finish(run(source, url), true);
        }

        void hit(Object source, String url) throws Exception {
            Object run = run(source, url);
            set(run, "matches", 1L);
            set(run, "applied", 1L);
            finish(run, true);
        }

        void hitWithProblem(Object source, String url, String reason)
                throws Exception {
            Object run = run(source, url);
            set(run, "matches", 1L);
            Object value = reason(reason);
            Method problem = run.getClass().getDeclaredMethod("problem",
                    reasonType, int.class, String.class);
            problem.setAccessible(true);
            invoke(problem, run, value, -1, "test failure");
            finish(run, true);
        }

        void close() throws Exception {
            invoke(close, diagnostics);
        }

        private Object run(Object source, String url) throws Exception {
            Object context = contextConstructor.newInstance(url,
                    response(200, "text/html; boundary=ignored"));
            return invoke(begin, diagnostics, source, context);
        }

        private Object reason(String name) {
            for (Object value : reasonType.getEnumConstants()) {
                if (name.equals(((Enum<?>) value).name())) {
                    return value;
                }
            }
            throw new AssertionError("unknown diagnostic reason: " + name);
        }

        private static void finish(Object run, boolean expectMatch)
                throws Exception {
            Method method = run.getClass().getDeclaredMethod("finish",
                    boolean.class);
            method.setAccessible(true);
            invoke(method, run, expectMatch);
        }

        private static void set(Object target, String name, Object value)
                throws Exception {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }

        private static Object invoke(Method method, Object target,
                Object... arguments) throws Exception {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new AssertionError(cause);
            }
        }
    }
}
