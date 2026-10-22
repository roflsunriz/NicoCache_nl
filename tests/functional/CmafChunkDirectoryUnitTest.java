package functional;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dareka.processor.impl.CmafCachingProcessor;

/** CMAFの初期チャンクと先頭セグメントが並行要求された場合の保存先作成。 */
public final class CmafChunkDirectoryUnitTest {
    private CmafChunkDirectoryUnitTest() {
    }

    public static void run() throws Exception {
        Method createChunkDirectory = CmafCachingProcessor.class.getDeclaredMethod(
                "createChunkDirectory", File.class);
        createChunkDirectory.setAccessible(true);

        ContendedDirectory directory = new ContendedDirectory();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(
                    () -> invoke(createChunkDirectory, directory));
            Future<Boolean> second = executor.submit(
                    () -> invoke(createChunkDirectory, directory));
            equal(true, first.get(), "first concurrent directory creation");
            equal(true, second.get(), "competing directory creation");
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new AssertionError("directory creation executor did not terminate");
            }
        }

        equal(false, invoke(createChunkDirectory, new FailedDirectory()),
                "actual directory creation failure");
    }

    private static boolean invoke(Method method, File directory) throws Exception {
        try {
            return (Boolean) method.invoke(null, directory);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw error;
        }
    }

    private static final class ContendedDirectory extends File {
        private static final long serialVersionUID = 1L;
        private final transient CountDownLatch mkdirCalls = new CountDownLatch(2);
        private final transient AtomicBoolean created = new AtomicBoolean();

        ContendedDirectory() {
            super("contended-cmaf-directory");
        }

        @Override
        public boolean mkdir() {
            mkdirCalls.countDown();
            try {
                if (!mkdirCalls.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("concurrent mkdir calls did not overlap");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("concurrent mkdir interrupted", error);
            }
            return created.compareAndSet(false, true);
        }

        @Override
        public boolean isDirectory() {
            return created.get();
        }
    }

    private static final class FailedDirectory extends File {
        private static final long serialVersionUID = 1L;

        FailedDirectory() {
            super("failed-cmaf-directory");
        }

        @Override
        public boolean mkdir() {
            return false;
        }

        @Override
        public boolean isDirectory() {
            return false;
        }
    }

    private static void equal(boolean expected, boolean actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
