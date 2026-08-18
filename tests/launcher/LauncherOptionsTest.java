package nicocache.launcher;

/** Regression tests for explicit GUI and core startup options. */
public final class LauncherOptionsTest {
    private LauncherOptionsTest() {
    }

    public static void main(String[] args) {
        LauncherOptions normal = LauncherOptions.parse(new String[0]);
        assertEquals(LauncherOptions.Action.GUI, normal.getAction(),
                "double-click launch action");
        assertEquals(LauncherOptions.WindowMode.NORMAL, normal.getWindowMode(),
                "double-click window mode");
        assertFalse(normal.shouldStartCore(),
                "double-click must not start the core implicitly");

        assertWindowMode(new String[] { "--tray" },
                LauncherOptions.WindowMode.TRAY, false);
        assertWindowMode(new String[] { "--tray", "--start" },
                LauncherOptions.WindowMode.TRAY, true);
        assertWindowMode(new String[] { "--start", "--tray" },
                LauncherOptions.WindowMode.TRAY, true);
        assertWindowMode(new String[] { "--minimized" },
                LauncherOptions.WindowMode.MINIMIZED, false);
        assertWindowMode(new String[] { "--minimized", "--start" },
                LauncherOptions.WindowMode.MINIMIZED, true);

        LauncherOptions asynchronous = LauncherOptions.parse(
                new String[] { "--start" });
        assertEquals(LauncherOptions.Action.START, asynchronous.getAction(),
                "standalone start action");
        assertFalse(asynchronous.shouldStartCore(),
                "non-GUI start must use its action instead of GUI state");

        LauncherOptions launcherOnlyStop = LauncherOptions.parse(
                new String[] { "--headless", "--launcher-only-stop" });
        assertEquals(LauncherOptions.Action.LAUNCHER_ONLY_STOP,
                launcherOnlyStop.getAction(),
                "launcher-only stop action");

        assertRejected(new String[] { "--tray", "--minimized" },
                "conflicting window modes");
        assertRejected(new String[] { "--headless", "--tray" },
                "headless tray mode");
        assertRejected(new String[] { "--tray", "--status" },
                "window mode with another action");
        assertRejected(new String[] { "--start", "--status" },
                "multiple core actions");
        assertRejected(new String[] { "--launcher-only-stop", "--stop" },
                "launcher-only and core stop conflict");
        System.out.println("Launcher option tests passed");
    }

    private static void assertWindowMode(String[] arguments,
            LauncherOptions.WindowMode expectedMode, boolean expectedStart) {
        LauncherOptions options = LauncherOptions.parse(arguments);
        assertEquals(LauncherOptions.Action.GUI, options.getAction(),
                "window mode action");
        assertEquals(expectedMode, options.getWindowMode(), "window mode");
        if (expectedStart) {
            assertTrue(options.shouldStartCore(),
                    "--start must start the core from the selected GUI mode");
        } else {
            assertFalse(options.shouldStartCore(),
                    "GUI mode alone must not start the core");
        }
    }

    private static void assertRejected(String[] arguments, String message) {
        try {
            LauncherOptions.parse(arguments);
            throw new AssertionError(message + " must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
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
