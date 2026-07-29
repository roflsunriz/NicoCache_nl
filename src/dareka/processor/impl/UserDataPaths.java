package dareka.processor.impl;

import java.io.File;
import java.nio.file.Path;

/**
 * Internal path access for processor implementations.
 */
final class UserDataPaths {
    private static final String DATA_ROOT_PROPERTY = "nicocache.userDataRoot";
    private static final String APPLICATION_ROOT_PROPERTY =
            "nicocache.applicationRoot";

    private UserDataPaths() {
    }

    static File userFile(String relativePath) {
        return root(DATA_ROOT_PROPERTY).resolve(relativePath)
                .normalize().toFile();
    }

    static File applicationFile(String relativePath) {
        return root(APPLICATION_ROOT_PROPERTY).resolve(relativePath)
                .normalize().toFile();
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
            path = root(DATA_ROOT_PROPERTY).resolve(path);
        }
        return path.normalize().toFile();
    }

    private static Path root(String property) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            return Path.of("").toAbsolutePath().normalize();
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }
}
