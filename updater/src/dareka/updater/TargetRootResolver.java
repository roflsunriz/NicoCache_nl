package dareka.updater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/** Resolves, validates and remembers the NicoCache_nl installation selected by the user. */
final class TargetRootResolver {
    private static final String ROOT_KEY = "applicationRoot";
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(TargetRootResolver.class);

    private TargetRootResolver() {}

    static Path resolve(String explicitRoot) {
        if (explicitRoot != null && !explicitRoot.isBlank()) {
            return prepare(normalize(Path.of(explicitRoot)));
        }
        String saved = PREFERENCES.get(ROOT_KEY, "");
        if (!saved.isBlank()) {
            Path candidate = normalize(Path.of(saved));
            if (isInstallation(candidate)) return prepare(candidate);
        }
        return prepare(defaultRoot());
    }

    static Path defaultRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            String home = System.getProperty("user.home", ".");
            return normalize(Path.of(home, "AppData", "Local", "NicoCache_nl"));
        }
        return normalize(Path.of(localAppData, "NicoCache_nl"));
    }

    static Path legacyRoot() {
        String systemDrive = System.getenv("SystemDrive");
        return normalize(Path.of(systemDrive == null || systemDrive.isBlank() ? "C:" : systemDrive,
                "NicoCache_nl"));
    }

    static void remember(Path root) throws IOException {
        Path normalized = requireInstallation(root);
        PREFERENCES.put(ROOT_KEY, normalized.toString());
        try {
            PREFERENCES.flush();
        } catch (java.util.prefs.BackingStoreException error) {
            throw new IOException("更新対象の保存に失敗しました", error);
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
        if (root == null || !Files.isDirectory(root)) return false;
        return Files.isRegularFile(root.resolve("NicoCache_nl.jar"))
                || Files.isRegularFile(root.resolve("NicoCache_nl.exe"))
                || Files.isRegularFile(root.resolve("version.txt"))
                || Files.isRegularFile(root.resolve("app").resolve(".jpackage.xml"));
    }

    private static Path prepare(Path root) {
        if (!Files.isDirectory(root) || Files.isRegularFile(root.resolve("version.txt"))) return root;
        String detected = InstalledVersionDetector.detect(root);
        if ("不明".equals(detected)) return root;
        try {
            Files.writeString(root.resolve("version.txt"), detected + System.lineSeparator(),
                    StandardCharsets.US_ASCII);
        } catch (IOException ignored) {
            // The GUI can still read app/.jpackage.xml directly after this fallback is wired.
        }
        return root;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
