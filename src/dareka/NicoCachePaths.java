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
public final class NicoCachePaths {
    public static final String DATA_ROOT_PROPERTY = "nicocache.dataRoot";
    public static final String APPLICATION_ROOT_PROPERTY =
            "nicocache.applicationRoot";
    public static final String PORTABLE_FLAG = "portable.flag";

    private NicoCachePaths() {
    }

    public static Path applicationRoot() {
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

    public static boolean isPortable() {
        return Files.exists(applicationRoot().resolve(PORTABLE_FLAG));
    }

    public static Path dataRoot() {
        String configured = System.getProperty(DATA_ROOT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        if (isPortable()) {
            return applicationRoot();
        }

        Path documents;
        try {
            documents = FileSystemView.getFileSystemView()
                    .getDefaultDirectory()
                    .toPath();
        } catch (RuntimeException error) {
            documents = Path.of(System.getProperty("user.home"));
        }
        return documents.resolve("NicoCache_nl").toAbsolutePath().normalize();
    }

    public static Path userPath(String relativePath) {
        return dataRoot().resolve(relativePath).normalize();
    }

    public static File configFile() {
        return userPath("config.properties").toFile();
    }

    public static File guiPropertyFile() {
        return userPath("NicoCacheGUI.property").toFile();
    }

    public static File proxyPacFile() {
        return userPath("proxy.pac").toFile();
    }

    public static File localDirectory() {
        return userPath("local").toFile();
    }

    public static File nlFiltersDirectory() {
        return userPath("nlFilters").toFile();
    }

    public static File configuredFile(String value, String defaultRelativePath) {
        String selected = value;
        if (selected == null || selected.isBlank()) {
            selected = defaultRelativePath;
        }
        Path path = Path.of(selected);
        if (!path.isAbsolute()) {
            path = dataRoot().resolve(path);
        }
        return path.normalize().toFile();
    }

    public static File configuredFile(String propertyKey,
            String defaultRelativePath, boolean readSystemProperty) {
        String value = readSystemProperty
                ? System.getProperty(propertyKey)
                : propertyKey;
        return configuredFile(value, defaultRelativePath);
    }

    public static File cacheDirectory() {
        return configuredFile(System.getProperty("cacheFolder"), "cache");
    }

    public static File thumbnailCacheDirectory() {
        return configuredFile(System.getProperty("thcacheFolder"), "thcache");
    }

    public static File convertedCacheDirectory() {
        return configuredFile(System.getProperty("convertedCacheFolder"),
                "cvcache");
    }
}
