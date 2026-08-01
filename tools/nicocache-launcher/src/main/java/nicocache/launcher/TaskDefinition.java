package nicocache.launcher;

import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

final class TaskDefinition {
    enum Schedule {
        ON_LOGON,
        INTERVAL;

        static Schedule parse(String value) {
            if ("on-logon".equalsIgnoreCase(value)
                    || "on_logon".equalsIgnoreCase(value)
                    || "logon".equalsIgnoreCase(value)) {
                return ON_LOGON;
            }
            if ("interval".equalsIgnoreCase(value)) {
                return INTERVAL;
            }
            throw new IllegalArgumentException(
                    "schedule は on-logon または interval です: " + value);
        }

        String serialized() {
            return this == ON_LOGON ? "on-logon" : "interval";
        }
    }

    private final String name;
    private final Schedule schedule;
    private final int intervalMinutes;
    private final boolean enabled;

    TaskDefinition(String name, Schedule schedule, int intervalMinutes,
            boolean enabled) {
        this.name = validateName(name);
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        if (intervalMinutes < 1 || intervalMinutes > 10080) {
            throw new IllegalArgumentException(
                    "intervalMinutes は1〜10080の範囲で指定してください");
        }
        this.intervalMinutes = intervalMinutes;
        this.enabled = enabled;
    }

    static TaskDefinition fromProperties(Properties properties, String prefix) {
        String name = properties.getProperty(prefix + "name");
        if (name == null) {
            throw new IllegalArgumentException("タスク名がありません: " + prefix);
        }
        Schedule schedule = Schedule.parse(properties.getProperty(
                prefix + "schedule", "on-logon"));
        int interval;
        try {
            interval = Integer.parseInt(properties.getProperty(
                    prefix + "intervalMinutes", "60"));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("タスク間隔が不正です: " + name,
                    error);
        }
        boolean enabled = Boolean.parseBoolean(properties.getProperty(
                prefix + "enabled", "true"));
        return new TaskDefinition(name, schedule, interval, enabled);
    }

    void writeProperties(Properties properties, String prefix) {
        properties.setProperty(prefix + "name", name);
        properties.setProperty(prefix + "schedule", schedule.serialized());
        properties.setProperty(prefix + "intervalMinutes",
                Integer.toString(intervalMinutes));
        properties.setProperty(prefix + "enabled", Boolean.toString(enabled));
    }

    String getName() {
        return name;
    }

    Schedule getSchedule() {
        return schedule;
    }

    int getIntervalMinutes() {
        return intervalMinutes;
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
        return name + " (" + schedule.serialized() + ", "
                + intervalMinutes + " min, enabled=" + enabled + ")";
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
