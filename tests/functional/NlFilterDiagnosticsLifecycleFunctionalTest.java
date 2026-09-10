package functional;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import dareka.common.Logger;
import dareka.common.LoggerHandler;
import dareka.processor.impl.EasyRewriter;

/** Verifies that shutdown cannot revive or lose a nlFilter diagnostic scope. */
public final class NlFilterDiagnosticsLifecycleFunctionalTest {
    private static final String USER_ROOT = "nicocache.userDataRoot";
    private static final String APPLICATION_ROOT = "nicocache.applicationRoot";

    private NlFilterDiagnosticsLifecycleFunctionalTest() {
        // utility class
    }

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        String originalUserRoot = System.getProperty(USER_ROOT);
        String originalApplicationRoot = System.getProperty(APPLICATION_ROOT);
        LoggerHandler originalLogger = Logger.getHandler();
        RecordingLogger logger = new RecordingLogger();
        Path root = Files.createTempDirectory("nicocache-nlfilter-lifecycle-");
        System.setProperty(USER_ROOT, root.toString());
        System.setProperty(APPLICATION_ROOT, root.toString());
        Files.createDirectories(root.resolve("nlFilters"));
        Logger.setHandler(logger);
        try {
            testClosePreventsLaterLoadFromCreatingAScope(logger);
            testLoadAndCloseAreSerializedAndOldRunStillReports(logger);
        } finally {
            Logger.setHandler(originalLogger);
            restoreProperty(USER_ROOT, originalUserRoot);
            restoreProperty(APPLICATION_ROOT, originalApplicationRoot);
            deleteTree(root);
        }
    }

    private static void testClosePreventsLaterLoadFromCreatingAScope(
            RecordingLogger logger) throws Exception {
        EasyRewriter rewriter = newRewriter();
        Object filtersBeforeClose = filterLists(rewriter);
        Object diagnosticsBeforeClose = diagnostics(filtersBeforeClose);
        Object oldRun = begin(diagnosticsBeforeClose, "close-before-load.txt",
                "close before load");

        closeInstanceDiagnostics(rewriter);
        assertTrue(isClosed(diagnosticsBeforeClose),
                "close must close the active diagnostics scope");
        assertTrue(diagnosticsClosing(rewriter),
                "close must mark the rewriter as permanently closing");

        invokeLoad(rewriter);
        assertSame(filtersBeforeClose, filterLists(rewriter),
                "load after close must not install a new diagnostics generation");
        assertSame(diagnosticsBeforeClose, diagnostics(filterLists(rewriter)),
                "load after close must retain the closed scope");

        logger.clear();
        finish(oldRun, true);
        assertContains(logger.warningText(), "MATCH_ZERO",
                "a delayed old request must be emitted after its scope closes");
    }

    private static void testLoadAndCloseAreSerializedAndOldRunStillReports(
            RecordingLogger logger) throws Exception {
        EasyRewriter rewriter = newRewriter();
        Object initialFilters = filterLists(rewriter);
        Object initialDiagnostics = diagnostics(initialFilters);
        Object oldRun = begin(initialDiagnostics, "parallel-close.txt",
                "parallel close");
        Method load = privateMethod(EasyRewriter.class, "load");
        Method close = privateMethod(EasyRewriter.class,
                "closeInstanceDiagnostics");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch closerStarted = new CountDownLatch(1);

        Thread loader = callWhileHoldingMonitor("nlfilter-load-test", rewriter,
                load, loaderStarted, failure);
        Thread closer = callWhileHoldingMonitor("nlfilter-close-test", rewriter,
                close, closerStarted, failure);
        synchronized (rewriter) {
            loader.start();
            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS),
                    "load worker must start");
            awaitBlocked(loader, "load");
            closer.start();
            assertTrue(closerStarted.await(5, TimeUnit.SECONDS),
                    "close worker must start");
            awaitBlocked(closer, "close");
        }
        loader.join(5_000);
        closer.join(5_000);
        assertTrue(!loader.isAlive() && !closer.isAlive(),
                "load and close workers must both finish");
        if (failure.get() != null) {
            throw new AssertionError("concurrent load/close failed", failure.get());
        }

        Object activeFilters = filterLists(rewriter);
        Object activeDiagnostics = diagnostics(activeFilters);
        assertTrue(diagnosticsClosing(rewriter),
                "the serialized close must leave the rewriter closed");
        assertTrue(isClosed(activeDiagnostics),
                "the active scope must be closed after concurrent load and close");
        assertTrue(isClosed(initialDiagnostics),
                "the scope captured by an in-flight request must be closed");

        invokeLoad(rewriter);
        assertSame(activeFilters, filterLists(rewriter),
                "a post-close load must not revive a diagnostics scope");

        logger.clear();
        finish(oldRun, true);
        assertContains(logger.warningText(), "MATCH_ZERO",
                "a delayed run from the old scope must not remain unreported");
    }

    private static Thread callWhileHoldingMonitor(String name,
            EasyRewriter rewriter, Method method, CountDownLatch started,
            AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            started.countDown();
            try {
                invoke(method, rewriter);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, name);
    }

    private static void awaitBlocked(Thread worker, String operation) {
        for (int attempts = 0; attempts < 10_000; attempts++) {
            if (worker.getState() == Thread.State.BLOCKED) {
                return;
            }
            if (!worker.isAlive()) {
                throw new AssertionError(operation + " worker ended before monitor contention");
            }
            Thread.yield();
        }
        throw new AssertionError(operation + " worker did not block on the rewriter monitor");
    }

    private static EasyRewriter newRewriter() throws Exception {
        Constructor<EasyRewriter> constructor =
                EasyRewriter.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object filterLists(EasyRewriter rewriter) throws Exception {
        Field field = EasyRewriter.class.getDeclaredField("filterLists");
        field.setAccessible(true);
        return field.get(rewriter);
    }

    private static Object diagnostics(Object filters) throws Exception {
        Field field = filters.getClass().getDeclaredField("diagnostics");
        field.setAccessible(true);
        return field.get(filters);
    }

    private static boolean diagnosticsClosing(EasyRewriter rewriter)
            throws Exception {
        Field field = EasyRewriter.class.getDeclaredField("diagnosticsClosing");
        field.setAccessible(true);
        return field.getBoolean(rewriter);
    }

    private static void closeInstanceDiagnostics(EasyRewriter rewriter)
            throws Exception {
        invoke(privateMethod(EasyRewriter.class, "closeInstanceDiagnostics"),
                rewriter);
    }

    private static void invokeLoad(EasyRewriter rewriter) throws Exception {
        invoke(privateMethod(EasyRewriter.class, "load"), rewriter);
    }

    private static Method privateMethod(Class<?> type, String name)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static Object begin(Object diagnostics, String file, String name)
            throws Exception {
        String diagnosticsType = diagnostics.getClass().getName();
        Class<?> sourceType = Class.forName(diagnosticsType + "$Source");
        Class<?> contextType = Class.forName(diagnosticsType + "$Context");
        Constructor<?> constructor = sourceType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object source = constructor.newInstance();
        set(source, "file", file);
        set(source, "name", name);
        set(source, "sectionLine", 1);
        set(source, "operationLine", 2);
        Method begin = diagnostics.getClass().getDeclaredMethod("begin", sourceType,
                contextType);
        begin.setAccessible(true);
        return invoke(begin, diagnostics, source, null);
    }

    private static boolean isClosed(Object diagnostics) throws Exception {
        Field field = diagnostics.getClass().getDeclaredField("closed");
        field.setAccessible(true);
        return field.getBoolean(diagnostics);
    }

    private static void finish(Object run, boolean expectMatch) throws Exception {
        Method method = run.getClass().getDeclaredMethod("finish", boolean.class);
        method.setAccessible(true);
        invoke(method, run, expectMatch);
    }

    private static void set(Object target, String fieldName, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
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

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static void deleteTree(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            List<Path> reverseOrder = paths.sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList());
            for (Path path : reverseOrder) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void assertContains(String actual, String expected,
            String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertSame(Object expected, Object actual,
            String message) {
        if (expected != actual) {
            throw new AssertionError(message);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RecordingLogger implements LoggerHandler {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void debug(String message) {
            // not needed here
        }

        @Override
        public void debug(Throwable error) {
            // not needed here
        }

        @Override
        public void debugWithThread(String message) {
            // not needed here
        }

        @Override
        public void debugWithThread(Throwable error) {
            // not needed here
        }

        @Override
        public void info(String message) {
            // not needed here
        }

        @Override
        public void info(String format, Object... arguments) {
            // not needed here
        }

        @Override
        public synchronized void warning(String message) {
            warnings.add(message);
        }

        @Override
        public void error(Throwable error) {
            // diagnostics use safe string messages on the warning channel
        }

        synchronized void clear() {
            warnings.clear();
        }

        synchronized String warningText() {
            return String.join("\n", warnings);
        }
    }
}
