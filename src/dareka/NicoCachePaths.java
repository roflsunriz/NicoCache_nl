package dareka;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Properties;


/**
 * Central path resolver for application files, user-managed files and
 * configurable storage locations.
 *
 * <p>User data root precedence is:</p>
 * <ol>
 * <li>the {@code userDataRoot} value in the application config,</li>
 * <li>the application directory for portable and development launches,</li>
 * <li>the platform user-documents directory for packaged launches.</li>
 * </ol>
 */
final class NicoCachePaths {
    static final String USER_DATA_ROOT_KEY = "userDataRoot";
    static final String USER_DATA_ROOT_PROPERTY = "nicocache.userDataRoot";
    static final String APPLICATION_ROOT_PROPERTY =
            "nicocache.applicationRoot";
    static final String PORTABLE_FLAG = "portable.flag";

    private NicoCachePaths() {
    }

    static Path applicationRoot() {
        String configured = System.getProperty(APPLICATION_ROOT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }

        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null && !launcher.isBlank()) {
            return PlatformSupport.applicationRootFromLauncher(
                    Path.of(launcher), PlatformSupport.current());
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    static boolean isPackaged() {
        String launcher = System.getProperty("jpackage.app-path");
        return launcher != null && !launcher.isBlank();
    }

    static boolean isPortable() {
        return Files.exists(applicationRoot().resolve(PORTABLE_FLAG));
    }

    static Path dataRoot() {
        String configured = readConfiguredDataRoot();
        if (configured != null && !configured.isBlank()) {
            Path selected;
            try {
                selected = Path.of(configured);
            } catch (InvalidPathException error) {
                throw new IllegalStateException(
                        USER_DATA_ROOT_KEY + " のパスが不正です: "
                                + configured,
                        error);
            }
            if (!selected.isAbsolute()) {
                selected = applicationRoot().resolve(selected);
            }
            return selected.toAbsolutePath().normalize();
        }
        return defaultDataRoot();
    }

    static Path defaultDataRoot() {
        if (isPortable() || !isPackaged()
                && System.getProperty(APPLICATION_ROOT_PROPERTY) == null) {
            return applicationRoot();
        }

        return PlatformSupport.defaultDataRoot(
                PlatformSupport.current(), applicationRoot(), isPackaged(),
                isPortable());
    }

    private static String readConfiguredDataRoot() {
        String override = System.getProperty(USER_DATA_ROOT_PROPERTY);
        if (override != null && !override.isBlank()) {
            return override;
        }
        File config = configFile();
        if (!Files.isRegularFile(config.toPath())) {
            return null;
        }
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(config)) {
            properties.load(input);
            String raw = readRawDataRoot(config.toPath());
            return raw == null
                    ? properties.getProperty(USER_DATA_ROOT_KEY)
                    : raw;
        } catch (IOException error) {
            throw new IllegalStateException(
                    "設定ファイルを読み取れません: " + config,
                    error);
        }
    }

    private static String readRawDataRoot(Path config) throws IOException {
        for (String line : Files.readAllLines(config,
                StandardCharsets.ISO_8859_1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")
                    || trimmed.startsWith("!")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                separator = trimmed.indexOf(':');
            }
            if (separator <= 0
                    || !USER_DATA_ROOT_KEY.equals(trimmed.substring(0,
                            separator).trim())) {
                continue;
            }
            String value = trimmed.substring(separator + 1).trim();
            if (value.indexOf('\\') >= 0
                    && !value.matches(".*\\\\u[0-9a-fA-F]{4}.*")) {
                return value.replace("\\\\", "\\");
            }
            return null;
        }
        return null;
    }

    static void publishDataRoot(Path root) {
        System.setProperty(
                USER_DATA_ROOT_PROPERTY,
                root.toAbsolutePath().normalize().toString());
    }

    static Path applicationPath(String relativePath) {
        return resolveChild(applicationRoot(), relativePath);
    }

    static Path userPath(String relativePath) {
        return resolveChild(dataRoot(), relativePath);
    }

    private static Path resolveChild(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException(
                    "relativePath must not be absolute: " + relativePath);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(
                    "relativePath escapes its root: " + relativePath);
        }
        return resolved;
    }

    static File applicationFile(String relativePath) {
        return applicationPath(relativePath).toFile();
    }

    static File userFile(String relativePath) {
        return userPath(relativePath).toFile();
    }

    static File configFile() {
        return applicationFile("config.properties");
    }

    static File legacyConfigFile() {
        return applicationFile("config.ini");
    }

    static File defaultConfigFile() {
        return applicationFile("config.properties.default");
    }

    static File guiPropertyFile() {
        return userFile("NicoCacheGUI.property");
    }

    static File logSearchHistoryFile() {
        return userFile("NicoCacheGUI.search-history.properties");
    }

    static File debugLogFile() {
        return applicationFile("debug.log");
    }

    static File proxyPacFile() {
        return userFile("proxy.pac");
    }

    static File tlsClientCacertsFile() {
        File userStore = userFile("data/tlsclient/cacerts2");
        return Files.isRegularFile(userStore.toPath())
                ? userStore
                : applicationFile("data/tlsclient/cacerts2");
    }

    static File localDirectory() {
        return userFile("local");
    }

    static File systemLocalDirectory() {
        return applicationFile("local");
    }

    static File nlFiltersDirectory() {
        return userFile("nlFilters");
    }

    static File systemNlFiltersDirectory() {
        return applicationFile("nlFilters");
    }

    static File configuredFile(String value, String defaultRelativePath) {
        String selected = value;
        if (selected == null || selected.isBlank()) {
            selected = defaultRelativePath;
        }
        if (selected == null || selected.isBlank()) {
            return null;
        }
        Path path = Path.of(selected);
        if (!path.isAbsolute()) {
            path = dataRoot().resolve(path);
        }
        return path.normalize().toFile();
    }

    static File configuredFile(String propertyKey,
            String defaultRelativePath, boolean readSystemProperty) {
        String value = readSystemProperty
                ? System.getProperty(propertyKey)
                : propertyKey;
        return configuredFile(value, defaultRelativePath);
    }

    static File cacheDirectory() {
        return configuredFile(System.getProperty("cacheFolder"), "cache");
    }

    static File thumbnailCacheDirectory() {
        return configuredFile(System.getProperty("thcacheFolder"), "thcache");
    }

    static File convertedCacheDirectory() {
        return configuredFile(System.getProperty("convertedCacheFolder"),
                "cvcache");
    }

}
