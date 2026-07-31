package dareka.updater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the installed NicoCache_nl version from package metadata. */
final class InstalledVersionDetector {
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+(?:\\.\\d+){1,3})$");
    private static final Pattern CFG_VERSION = Pattern.compile(
            "(?m)^\\s*java-options\\s*=\\s*-Djpackage\\.app-version=([^\\s]+)\\s*$");
    private static final Pattern JPACKAGE_VERSION = Pattern.compile("<app-version>([^<]+)</app-version>");

    private InstalledVersionDetector() {}

    static String detect(Path applicationRoot) {
        Path app = UpdaterPlatform.applicationDirectory(applicationRoot);
        Path resourceDirectory = UpdaterPlatform.resourceDirectory(applicationRoot);
        Path launcherConfig = app.resolve("NicoCache_nl.cfg");

        String fromDistribution = readPlainVersion(
                resourceDirectory.resolve("NicoCache_nl.version"));
        if (fromDistribution == null && !resourceDirectory.equals(applicationRoot)) {
            fromDistribution = readPlainVersion(applicationRoot.resolve("NicoCache_nl.version"));
        }
        if (fromDistribution != null) return fromDistribution;

        String fromLauncher = readLauncherVersion(launcherConfig);
        if (fromLauncher != null) return fromLauncher;

        // A real installed app image always contains the launcher executable. In that
        // layout NicoCache_nl.cfg is authoritative, so missing or malformed launcher
        // metadata must be surfaced instead of hidden by stale compatibility markers.
        if (Files.isRegularFile(applicationRoot.resolve("NicoCache_nl.exe"))
                || Files.isRegularFile(applicationRoot.resolve("NicoCache_nl"))
                || Files.isRegularFile(applicationRoot.resolve("bin/NicoCache_nl"))
                || Files.isRegularFile(applicationRoot.resolve("MacOS/NicoCache_nl"))) {
            return "不明";
        }

        // Legacy and synthetic layouts without the real launcher retain compatibility
        // with the historical marker files.
        String fromMarker = readPlainVersion(resourceDirectory.resolve("version.txt"));
        if (fromMarker == null && !resourceDirectory.equals(applicationRoot)) {
            fromMarker = readPlainVersion(applicationRoot.resolve("version.txt"));
        }
        if (fromMarker != null) return fromMarker;

        String fromPackage = readJpackageVersion(app.resolve(".jpackage.xml"));
        if (fromPackage != null) return fromPackage;
        fromPackage = readJpackageVersion(applicationRoot.resolve(".jpackage.xml"));
        if (fromPackage != null) return fromPackage;

        return "不明";
    }

    static String readLauncherVersion(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            Matcher matcher = CFG_VERSION.matcher(Files.readString(file, StandardCharsets.UTF_8));
            return matcher.find() ? normalize(matcher.group(1).trim()) : null;
        } catch (IOException error) {
            return null;
        }
    }

    static String readPlainVersion(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            return normalize(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (IOException error) {
            return null;
        }
    }

    static String readJpackageVersion(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            Matcher matcher = JPACKAGE_VERSION.matcher(Files.readString(file, StandardCharsets.UTF_8));
            return matcher.find() ? normalize(matcher.group(1).trim()) : null;
        } catch (IOException error) {
            return null;
        }
    }

    private static String normalize(String value) {
        Matcher matcher = VERSION.matcher(value);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
