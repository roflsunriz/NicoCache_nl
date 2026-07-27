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
    private static final Pattern JPACKAGE_VERSION = Pattern.compile("<app-version>([^<]+)</app-version>");

    private InstalledVersionDetector() {}

    static String detect(Path applicationRoot) {
        String fromMarker = readPlainVersion(applicationRoot.resolve("version.txt"));
        if (fromMarker != null) return fromMarker;

        String fromPackage = readJpackageVersion(applicationRoot.resolve("app").resolve(".jpackage.xml"));
        if (fromPackage != null) return fromPackage;

        return "不明";
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
