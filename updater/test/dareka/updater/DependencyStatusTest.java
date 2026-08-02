package dareka.updater;

/** Regression tests for per-dependency check and install button state. */
public final class DependencyStatusTest {
    private DependencyStatusTest() {
    }

    public static void main(String[] args) {
        DependencyStatus update = new DependencyStatus("gpac", "GPAC / MP4Box",
                "2.4", "26.07", "更新あり", true, true, true);
        assertTrue(update.canInstall(), "newer checked dependency must be installable");

        DependencyStatus current = new DependencyStatus("gpac", "GPAC / MP4Box",
                "26.07", "26.07", "最新", true, false, true);
        assertFalse(current.canInstall(), "current dependency must disable install");

        DependencyStatus unchecked = new DependencyStatus("gpac", "GPAC / MP4Box",
                "2.4", "26.07", "未確認", false, true, true);
        assertFalse(unchecked.canInstall(), "unchecked dependency must disable install");

        DependencyStatus unavailable = new DependencyStatus("gpac", "GPAC / MP4Box",
                "2.4", "26.07", "WinGetがありません", true, true, false);
        assertFalse(unavailable.canInstall(), "uninstallable dependency must disable install");
        System.out.println("Dependency status button-state tests passed");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }
}
