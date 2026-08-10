package nicocache.launcher;

import java.util.concurrent.atomic.AtomicInteger;

/** Regression tests for independent process lifecycle operations. */
public final class LauncherLifecycleTest {
    private LauncherLifecycleTest() {
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger coreGracefulStops = new AtomicInteger();
        AtomicInteger coreForceStops = new AtomicInteger();
        AtomicInteger launcherExits = new AtomicInteger();
        LauncherLifecycle lifecycle = new LauncherLifecycle(
                coreGracefulStops::incrementAndGet,
                coreForceStops::incrementAndGet,
                launcherExits::incrementAndGet);

        lifecycle.exitLauncher();
        assertEquals(1, launcherExits.get(),
                "launcher exit action must run");
        assertEquals(0, coreGracefulStops.get(),
                "launcher exit must not gracefully stop the core");
        assertEquals(0, coreForceStops.get(),
                "launcher exit must not force-stop the core");

        lifecycle.gracefulStopCore();
        assertEquals(1, coreGracefulStops.get(),
                "graceful stop must target only the core");
        assertEquals(1, launcherExits.get(),
                "core stop must not exit the launcher");

        lifecycle.forceStopCore();
        assertEquals(1, coreForceStops.get(),
                "force stop must target only the core");
        assertEquals(1, launcherExits.get(),
                "force stop must not exit the launcher");
        System.out.println("Launcher lifecycle tests passed");
    }

    private static void assertEquals(int expected, int actual,
            String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }
}
