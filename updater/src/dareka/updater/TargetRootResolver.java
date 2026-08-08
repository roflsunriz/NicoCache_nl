package dareka.updater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Resolves, validates and remembers the NicoCache_nl installation selected by the user. */
final class TargetRootResolver {
    private static final String ROOT_KEY = "applicationRoot";

    private TargetRootResolver() {}

    static Path resolve(String explicitRoot) {
        if (explicitRoot != null && !explicitRoot.isBlank()) {
            return prepare(normalize(Path.of(explicitRoot)));
        }
        String saved = readSavedRoot();
        if (!saved.isBlank()) {
            Path candidate = normalize(Path.of(saved));
            if (isInstallation(candidate)) return prepare(candidate);
        }
        Path preferred = defaultRoot();
        if (UpdaterPlatform.current() == UpdaterPlatform.Kind.WINDOWS) {
            return prepare(selectInstalledRoot(preferred, windowsProgramsRoot(), legacyRoot()));
        }
        return prepare(preferred);
    }

    static Path defaultRoot() {
        return normalize(UpdaterPlatform.defaultRoot());
    }

    static Path legacyRoot() {
        String systemDrive = System.getenv("SystemDrive");
        return normalize(Path.of(systemDrive == null || systemDrive.isBlank() ? "C:" : systemDrive,
                "NicoCache_nl"));
    }

    static Path selectInstalledRoot(Path preferred, Path... alternatives) {
        Path normalizedPreferred = normalize(preferred);
        if (isInstallation(normalizedPreferred)) return normalizedPreferred;
        for (Path alternative : alternatives) {
            Path candidate = normalize(alternative);
            if (isInstallation(candidate)) return candidate;
        }
        return normalizedPreferred;
    }

    static Path resolveAfterApplicationUpdate(Path previousRoot) throws IOException {
        Path selected = selectPostUpdateRoot(previousRoot, defaultRoot(),
                windowsProgramsRoot(), UpdaterPlatform.current());
        remember(selected);
        return selected;
    }

    static Path selectPostUpdateRoot(Path previousRoot, Path preferredRoot,
            Path releasedProgramsRoot, UpdaterPlatform.Kind platform) throws IOException {
        Path previous = normalize(previousRoot);
        Path preferred = normalize(preferredRoot);
        Path releasedPrograms = normalize(releasedProgramsRoot);
        if (platform == UpdaterPlatform.Kind.WINDOWS
                && previous.equals(releasedPrograms)
                && isInstallation(preferred)) {
            return prepare(preferred);
        }
        if (isInstallation(previous)) return prepare(previous);
        if (isInstallation(preferred)) return prepare(preferred);
        throw new IOException("更新後のNicoCache_nlインストール先を検出できません: "
                + previous + " / " + preferred);
    }

    private static Path windowsProgramsRoot() {
        Path localAppData;
        String configured = System.getenv("LOCALAPPDATA");
        if (configured == null || configured.isBlank()) {
            localAppData = UpdaterPlatform.homeDirectory().resolve("AppData/Local");
        } else {
            localAppData = Path.of(configured);
        }
        return normalize(localAppData.resolve("Programs/NicoCache_nl"));
    }

    static void remember(Path root) throws IOException {
        Path normalized = requireInstallation(root);
        Path settings = settingsFile();
        Files.createDirectories(settings.getParent());
        Properties properties = new Properties();
        properties.setProperty(ROOT_KEY, normalized.toString());
        Path temporary = settings.resolveSibling(settings.getFileName() + ".tmp");
        try (java.io.OutputStream output = Files.newOutputStream(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, "NicoCache_nl updater settings");
        }
        try {
            Files.move(temporary, settings, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(temporary, settings, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Path requireInstallation(Path root) throws IOException {
        Path normalized = prepare(normalize(root));
        if (!isInstallation(normalized)) {
            throw new IOException("NicoCache_nlのインストール先ではありません: " + normalized);
        }
        return normalized;
    }

    static boolean isInstallation(Path root) {
        if (root == null) return false;
        Path normalized = UpdaterPlatform.normalizeApplicationRoot(root);
        if (!Files.isDirectory(normalized)) return false;
        Path applicationDirectory = UpdaterPlatform.applicationDirectory(normalized);
        Path resourceDirectory = UpdaterPlatform.resourceDirectory(normalized);
        return Files.isRegularFile(normalized.resolve("NicoCache_nl.jar"))
                || Files.isRegularFile(normalized.resolve("NicoCache_nl.exe"))
                || Files.isRegularFile(normalized.resolve("NicoCache_nl"))
                || Files.isRegularFile(normalized.resolve("bin/NicoCache_nl"))
                || Files.isRegularFile(normalized.resolve("MacOS/NicoCache_nl"))
                || (Files.isRegularFile(applicationDirectory.resolve("NicoCache_nl.jar"))
                    && (Files.isRegularFile(normalized.resolve("bin/NicoCache_nl"))
                        || Files.isRegularFile(normalized.resolve("MacOS/NicoCache_nl"))))
                || Files.isRegularFile(resourceDirectory.resolve("version.txt"))
                || Files.isRegularFile(applicationDirectory.resolve(".jpackage.xml"));
    }

    private static Path prepare(Path root) {
        Path resourceDirectory = UpdaterPlatform.resourceDirectory(root);
        if (!Files.isDirectory(root)
                || Files.isRegularFile(resourceDirectory.resolve("version.txt"))) return root;
        String detected = InstalledVersionDetector.detect(root);
        if ("不明".equals(detected)) return root;
        try {
            Files.writeString(resourceDirectory.resolve("version.txt"),
                    detected + System.lineSeparator(),
                    StandardCharsets.US_ASCII);
        } catch (IOException ignored) {
            // The GUI can still read app/.jpackage.xml directly after this fallback is wired.
        }
        return root;
    }

    private static Path normalize(Path path) {
        return UpdaterPlatform.normalizeApplicationRoot(path);
    }

    private static String readSavedRoot() {
        Path settings = settingsFile();
        if (!Files.isRegularFile(settings)) return "";
        Properties properties = new Properties();
        try (java.io.InputStream input = Files.newInputStream(settings)) {
            properties.load(input);
            return properties.getProperty(ROOT_KEY, "");
        } catch (IOException error) {
            return "";
        }
    }

    private static Path settingsFile() {
        Path home = UpdaterPlatform.homeDirectory();
        switch (UpdaterPlatform.current()) {
        case WINDOWS:
            String localAppData = System.getenv("LOCALAPPDATA");
            return Path.of(localAppData == null || localAppData.isBlank()
                    ? home.resolve("AppData/Local").toString() : localAppData,
                    "NicoCache_nl", "updater.properties").toAbsolutePath().normalize();
        case MACOS:
            return home.resolve("Library/Application Support/NicoCache_nl/updater.properties")
                    .toAbsolutePath().normalize();
        case LINUX:
        case OTHER:
        default:
            String configHome = System.getenv("XDG_CONFIG_HOME");
            Path config = configHome == null || configHome.isBlank()
                    ? home.resolve(".config") : Path.of(configHome);
            return config.resolve("NicoCache_nl/updater.properties")
                    .toAbsolutePath().normalize();
        }
    }
}
