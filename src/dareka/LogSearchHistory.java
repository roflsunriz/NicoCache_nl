package dareka;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import dareka.common.Logger;

/**
 * タブ別のログ検索履歴を専用ファイルへ保存する。
 */
final class LogSearchHistory {
    private static final String PREFIX = "LogSearchHistory.";
    private static final String VERSION_KEY = PREFIX + "Version";
    private static final String VERSION = "1";
    private static final int MAXIMUM_ENTRIES = 30;
    private static final int MAXIMUM_QUERY_LENGTH = 4096;

    private final Path historyFile;
    private final Properties properties = new Properties();

    LogSearchHistory(NLMain.GUILauncher.ConfigGUI config) {
        historyFile = NicoCachePaths.logSearchHistoryFile()
                .toPath().toAbsolutePath().normalize();
        boolean changed = load();
        changed |= ensureCurrentVersion();
        Properties legacy = copyPropertiesWithPrefix(
                config.properties, PREFIX);
        if (!hasStoredEntries() && !legacy.isEmpty()) {
            for (String key : legacy.stringPropertyNames()) {
                properties.setProperty(key, legacy.getProperty(key));
            }
            changed = true;
        }
        boolean persisted = !changed || save();
        if (persisted && !legacy.isEmpty()
                && removePropertiesWithPrefix(
                        config.properties, PREFIX)) {
            config.changed = true;
            config.save();
        }
    }

    synchronized List<Entry> load(String tabKey) {
        String keyPrefix = tabPrefix(tabKey);
        int count = parseBoundedCount(
                properties.getProperty(keyPrefix + "Count"));
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String itemPrefix = keyPrefix + index + ".";
            String query = properties.getProperty(itemPrefix + "Query");
            long timestamp = parseTimestamp(
                    properties.getProperty(itemPrefix + "Timestamp"));
            if (query == null || query.isBlank()
                    || query.length() > MAXIMUM_QUERY_LENGTH
                    || timestamp < 0L) {
                continue;
            }
            entries.add(new Entry(
                    query,
                    timestamp,
                    Boolean.parseBoolean(
                            properties.getProperty(itemPrefix + "Regex")),
                    Boolean.parseBoolean(
                            properties.getProperty(
                                    itemPrefix + "CaseSensitive"))));
        }
        entries.sort(Comparator.comparingLong(Entry::getTimestamp).reversed());
        if (entries.size() > MAXIMUM_ENTRIES) {
            return new ArrayList<>(entries.subList(0, MAXIMUM_ENTRIES));
        }
        return entries;
    }

    synchronized void record(String tabKey, String query,
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
        save();
    }

    private void write(String tabKey, List<Entry> entries) {
        String keyPrefix = tabPrefix(tabKey);
        List<String> previousKeys = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(keyPrefix)) {
                previousKeys.add(key);
            }
        }
        for (String key : previousKeys) {
            properties.remove(key);
        }
        properties.setProperty(keyPrefix + "Count",
                Integer.toString(entries.size()));
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            String itemPrefix = keyPrefix + index + ".";
            properties.setProperty(itemPrefix + "Query", entry.getQuery());
            properties.setProperty(itemPrefix + "Timestamp",
                    Long.toString(entry.getTimestamp()));
            properties.setProperty(itemPrefix + "Regex",
                    Boolean.toString(entry.isRegularExpression()));
            properties.setProperty(itemPrefix + "CaseSensitive",
                    Boolean.toString(entry.isCaseSensitive()));
        }
    }

    private boolean ensureCurrentVersion() {
        String existingVersion = properties.getProperty(VERSION_KEY);
        if (VERSION.equals(existingVersion)) {
            return false;
        }

        properties.clear();
        properties.setProperty(VERSION_KEY, VERSION);
        return true;
    }

    private boolean hasStoredEntries() {
        String tabPrefix = PREFIX + "Tab.";
        return properties.stringPropertyNames().stream()
                .anyMatch(key -> key.startsWith(tabPrefix));
    }

    private static Properties copyPropertiesWithPrefix(
            Properties source, String prefix) {
        Properties copied = new Properties();
        for (String key : source.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                copied.setProperty(key, source.getProperty(key));
            }
        }
        return copied;
    }

    private static boolean removePropertiesWithPrefix(
            Properties target, String prefix) {
        List<String> matchedKeys = new ArrayList<>();
        for (String key : target.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                matchedKeys.add(key);
            }
        }
        for (String key : matchedKeys) {
            target.remove(key);
        }
        return !matchedKeys.isEmpty();
    }

    private boolean load() {
        if (!Files.isRegularFile(historyFile)) {
            return false;
        }
        try (InputStream input = Files.newInputStream(historyFile)) {
            properties.load(input);
            return false;
        } catch (IOException | IllegalArgumentException error) {
            Logger.error(error);
            properties.clear();
            return true;
        }
    }

    private boolean save() {
        Path parent = historyFile.getParent();
        Path temporary = historyFile.resolveSibling(
                historyFile.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = Files.newOutputStream(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                properties.store(
                        output, "NicoCache_nl Log Search History");
            }
            try {
                Files.move(
                        temporary,
                        historyFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(
                        temporary,
                        historyFile,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            Logger.error(error);
            return false;
        }
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
