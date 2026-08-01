package nicocache.launcher;

import java.util.Locale;
import java.util.Properties;

final class TaskDefinition {
    static final String LOGON_SCHEDULE = "on-logon";

    private final String name;
    private final boolean enabled;

    TaskDefinition(String name, boolean enabled) {
        this.name = validateName(name);
        this.enabled = enabled;
    }

    static TaskDefinition fromProperties(Properties properties, String prefix) {
        String name = properties.getProperty(prefix + "name");
        if (name == null) {
            throw new IllegalArgumentException("タスク名がありません: " + prefix);
        }
        boolean enabled = Boolean.parseBoolean(properties.getProperty(
                prefix + "enabled", "true"));
        return new TaskDefinition(name, enabled);
    }

    static boolean needsMigration(Properties properties, String prefix) {
        String schedule = properties.getProperty(prefix + "schedule",
                LOGON_SCHEDULE);
        return !isLogonSchedule(schedule);
    }

    private static boolean isLogonSchedule(String value) {
        return LOGON_SCHEDULE.equalsIgnoreCase(value)
                || "on_logon".equalsIgnoreCase(value)
                || "logon".equalsIgnoreCase(value);
    }

    void writeProperties(Properties properties, String prefix) {
        properties.setProperty(prefix + "name", name);
        properties.setProperty(prefix + "schedule", LOGON_SCHEDULE);
        properties.setProperty(prefix + "enabled", Boolean.toString(enabled));
    }

    String getName() {
        return name;
    }

    boolean isEnabled() {
        return enabled;
    }

    String getId() {
        String normalized = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isEmpty()) {
            normalized = "task";
        }
        return "nicocache-nl-" + normalized + "-"
                + Integer.toHexString(name.hashCode());
    }

    @Override
    public String toString() {
        return name + " (" + LOGON_SCHEDULE + ", enabled=" + enabled + ")";
    }

    private static String validateName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("タスク名は空にできません");
        }
        if (value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("タスク名が長すぎるか不正です");
        }
        return value.trim();
    }
}
