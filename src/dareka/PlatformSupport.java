package dareka;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small, shared platform policy used by the launcher and first-run setup. */
final class PlatformSupport {
    enum Kind {
        WINDOWS,
        MACOS,
        LINUX,
        OTHER
    }

    private PlatformSupport() {
    }

    static Kind current() {
        return detect(System.getProperty("os.name", ""));
    }

    static Kind detect(String osName) {
        String normalized = osName == null
                ? ""
                : osName.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("win")) {
            return Kind.WINDOWS;
        }
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            return Kind.MACOS;
        }
        if (normalized.contains("linux")) {
            return Kind.LINUX;
        }
        return Kind.OTHER;
    }

    static boolean hasDesktop() {
        return !GraphicsEnvironment.isHeadless();
    }

    static Path applicationRootFromLauncher(Path launcher, Kind kind) {
        if (launcher == null) {
            return Path.of("").toAbsolutePath().normalize();
        }
        Path normalized = launcher.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            return normalized;
        }
        if (kind == Kind.MACOS
                && "MacOS".equalsIgnoreCase(fileName(parent))
                && parent.getParent() != null
                && "Contents".equalsIgnoreCase(fileName(parent.getParent()))) {
            Path contents = parent.getParent();
            Path resources = contents.resolve("Resources");
            return Files.isDirectory(resources) ? resources : contents;
        }
        if (kind == Kind.LINUX
                && "bin".equalsIgnoreCase(fileName(parent))
                && parent.getParent() != null) {
            return parent.getParent();
        }
        return parent;
    }

    static Path launcherPath(Path applicationRoot, Kind kind) {
        switch (kind) {
        case WINDOWS:
            return applicationRoot.resolve("NicoCache_nl.exe");
        case MACOS:
            Path contents = applicationRoot;
            if ("Resources".equalsIgnoreCase(fileName(applicationRoot))
                    && applicationRoot.getParent() != null
                    && "Contents".equalsIgnoreCase(fileName(applicationRoot.getParent()))) {
                contents = applicationRoot.getParent();
            }
            return contents.resolve("MacOS").resolve("NicoCache_nl");
        case LINUX:
            return applicationRoot.resolve("bin").resolve("NicoCache_nl");
        case OTHER:
        default:
            return applicationRoot.resolve("NicoCache_nl");
        }
    }

    static Path defaultDataRoot(Kind kind, Path applicationRoot,
            boolean packaged, boolean portable) {
        if (portable || (!packaged
                && System.getProperty(
                        NicoCachePaths.APPLICATION_ROOT_PROPERTY) == null)) {
            return applicationRoot.toAbsolutePath().normalize();
        }

        Path home = userHome();
        switch (kind) {
        case MACOS:
            return home.resolve("Library")
                    .resolve("Application Support")
                    .resolve("NicoCache_nl")
                    .toAbsolutePath().normalize();
        case LINUX:
            String xdgDataHome = System.getenv("XDG_DATA_HOME");
            Path dataHome = xdgDataHome == null || xdgDataHome.isBlank()
                    ? home.resolve(".local").resolve("share")
                    : Path.of(xdgDataHome);
            return dataHome.resolve("NicoCache_nl")
                    .toAbsolutePath().normalize();
        case WINDOWS:
            try {
                return javax.swing.filechooser.FileSystemView
                        .getFileSystemView()
                        .getDefaultDirectory()
                        .toPath()
                        .resolve("NicoCache_nl")
                        .toAbsolutePath().normalize();
            } catch (RuntimeException error) {
                return home.resolve("NicoCache_nl")
                        .toAbsolutePath().normalize();
            }
        case OTHER:
        default:
            return home.resolve("NicoCache_nl")
                    .toAbsolutePath().normalize();
        }
    }

    static Path userHome() {
        String value = System.getProperty("user.home", "");
        return value.isBlank()
                ? Path.of("").toAbsolutePath().normalize()
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }
}
