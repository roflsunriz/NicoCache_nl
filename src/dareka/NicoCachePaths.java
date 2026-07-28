package dareka;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.filechooser.FileSystemView;

/**
 * Central path resolver for application files, user-managed files and
 * configurable storage locations.
 *
 * <p>Path precedence is:</p>
 * <ol>
 * <li>an explicit absolute path from configuration,</li>
 * <li>an explicit relative path resolved from the user data root,</li>
 * <li>the default relative location under the user data root.</li>
 * </ol>
 */
final class NicoCachePaths {
    static final String DATA_ROOT_PROPERTY = "nicocache.dataRoot";
    static final String DATA_ROOT_ENVIRONMENT = "NICOCACHE_DATA_ROOT";
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
            Path parent = Path.of(launcher).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                return parent;
            }
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
        String configured = System.getProperty(DATA_ROOT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(DATA_ROOT_ENVIRONMENT);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        if (isPortable() || !isPackaged()
                && System.getProperty(APPLICATION_ROOT_PROPERTY) == null) {
            return applicationRoot();
        }

        Path documents;
        try {
            documents = FileSystemView.getFileSystemView()
                    .getDefaultDirectory()
                    .toPath();
        } catch (RuntimeException error) {
            String userHome = System.getProperty("user.home");
            documents = userHome == null || userHome.isBlank()
                    ? applicationRoot()
                    : Path.of(userHome);
        }
        return documents.resolve("NicoCache_nl").toAbsolutePath().normalize();
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
        return userFile("config.properties");
    }

    static File legacyConfigFile() {
        return userFile("config.ini");
    }

    static File defaultConfigFile() {
        return applicationFile("config.properties.default");
    }

    static File guiPropertyFile() {
        return userFile("NicoCacheGUI.property");
    }

    static File proxyPacFile() {
        return userFile("proxy.pac");
    }

    static File localDirectory() {
        return userFile("local");
    }

    static File nlFiltersDirectory() {
        return userFile("nlFilters");
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
