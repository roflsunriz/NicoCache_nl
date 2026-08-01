package nicocache.launcher;

import java.util.Properties;

/** Regression tests for the logon-only task contract. */
public final class LauncherTaskTest {
    private LauncherTaskTest() {
    }

    public static void main(String[] args) {
        Properties legacy = new Properties();
        legacy.setProperty("task.0.name", "Legacy task");
        legacy.setProperty("task.0.schedule", "interval");
        legacy.setProperty("task.0.intervalMinutes", "15");
        legacy.setProperty("task.0.enabled", "true");
        assertTrue(TaskDefinition.needsMigration(legacy, "task.0."),
                "legacy interval task must be migrated");
        TaskDefinition task = TaskDefinition.fromProperties(legacy,
                "task.0.");
        Properties current = new Properties();
        task.writeProperties(current, "task.0.");
        assertEquals("on-logon", current.getProperty("task.0.schedule"),
                "migrated task schedule");
        assertTrue(!current.containsKey("task.0.intervalMinutes"),
                "migrated task must not persist an interval");
        assertTrue(task.toString().contains("on-logon"),
                "task display must state the logon trigger");

        boolean rejected = false;
        try {
            LauncherOptions.parse(new String[] {
                    "--headless", "--task-install", "--interval-minutes=15" });
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected, "interval CLI option must be removed");
        System.out.println("Launcher task tests passed");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(String expected, String actual,
            String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }
}
