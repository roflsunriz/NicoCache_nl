package dareka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * タブ別のログ検索履歴をNicoCacheGUI.propertyへ保存する。
 */
final class LogSearchHistory {
    private static final String PREFIX = "LogSearchHistory.";
    private static final String VERSION_KEY = PREFIX + "Version";
    private static final String VERSION = "1";
    private static final int MAXIMUM_ENTRIES = 30;
    private static final int MAXIMUM_QUERY_LENGTH = 4096;

    private final NLMain.GUILauncher.ConfigGUI config;

    LogSearchHistory(NLMain.GUILauncher.ConfigGUI config) {
        this.config = config;
        ensureCurrentVersion();
    }

    List<Entry> load(String tabKey) {
        String keyPrefix = tabPrefix(tabKey);
        int count = parseBoundedCount(
                config.getProperty(keyPrefix + "Count"));
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String itemPrefix = keyPrefix + index + ".";
            String query = config.getProperty(itemPrefix + "Query");
            long timestamp = parseTimestamp(
                    config.getProperty(itemPrefix + "Timestamp"));
            if (query == null || query.isBlank()
                    || query.length() > MAXIMUM_QUERY_LENGTH
                    || timestamp < 0L) {
                continue;
            }
            entries.add(new Entry(
                    query,
                    timestamp,
                    Boolean.parseBoolean(
                            config.getProperty(itemPrefix + "Regex")),
                    Boolean.parseBoolean(
                            config.getProperty(itemPrefix + "CaseSensitive"))));
        }
        entries.sort(Comparator.comparingLong(Entry::getTimestamp).reversed());
        if (entries.size() > MAXIMUM_ENTRIES) {
            return new ArrayList<>(entries.subList(0, MAXIMUM_ENTRIES));
        }
        return entries;
    }

    void record(String tabKey, String query,
            boolean regularExpression, boolean caseSensitive) {
        if (query == null || query.isBlank()
                || query.length() > MAXIMUM_QUERY_LENGTH) {
            return;
        }

        List<Entry> entries = load(tabKey);
        entries.removeIf(entry ->
                entry.getQuery().equals(query)
                && entry.isRegularExpression() == regularExpression
                && entry.isCaseSensitive() == caseSensitive);
        entries.add(0, new Entry(
                query, Instant.now().toEpochMilli(),
                regularExpression, caseSensitive));
        if (entries.size() > MAXIMUM_ENTRIES) {
            entries = new ArrayList<>(
                    entries.subList(0, MAXIMUM_ENTRIES));
        }
        write(tabKey, entries);
        config.save();
    }

    private void write(String tabKey, List<Entry> entries) {
        String keyPrefix = tabPrefix(tabKey);
        config.setProperty(keyPrefix + "Count",
                Integer.toString(entries.size()));
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            String itemPrefix = keyPrefix + index + ".";
            config.setProperty(itemPrefix + "Query", entry.getQuery());
            config.setProperty(itemPrefix + "Timestamp",
                    Long.toString(entry.getTimestamp()));
            config.setProperty(itemPrefix + "Regex",
                    Boolean.toString(entry.isRegularExpression()));
            config.setProperty(itemPrefix + "CaseSensitive",
                    Boolean.toString(entry.isCaseSensitive()));
        }
    }

    private void ensureCurrentVersion() {
        String existingVersion = config.getProperty(VERSION_KEY);
        if (VERSION.equals(existingVersion)) {
            return;
        }

        Properties properties = config.properties;
        List<String> obsoleteKeys = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(PREFIX)) {
                obsoleteKeys.add(key);
            }
        }
        for (String key : obsoleteKeys) {
            properties.remove(key);
        }
        config.setProperty(VERSION_KEY, VERSION);
    }

    private static String tabPrefix(String tabKey) {
        String safeKey = tabKey == null ? "" : tabKey;
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                safeKey.getBytes(StandardCharsets.UTF_8));
        return PREFIX + "Tab." + encoded + ".";
    }

    private static int parseBoundedCount(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(0, Math.min(parsed, MAXIMUM_ENTRIES));
        } catch (RuntimeException error) {
            return 0;
        }
    }

    private static long parseTimestamp(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0L ? parsed : -1L;
        } catch (RuntimeException error) {
            return -1L;
        }
    }

    static final class Entry {
        private final String query;
        private final long timestamp;
        private final boolean regularExpression;
        private final boolean caseSensitive;

        Entry(String query, long timestamp,
                boolean regularExpression, boolean caseSensitive) {
            this.query = query;
            this.timestamp = timestamp;
            this.regularExpression = regularExpression;
            this.caseSensitive = caseSensitive;
        }

        String getQuery() {
            return query;
        }

        long getTimestamp() {
            return timestamp;
        }

        boolean isRegularExpression() {
            return regularExpression;
        }

        boolean isCaseSensitive() {
            return caseSensitive;
        }

        @Override
        public String toString() {
            return query;
        }
    }
}
