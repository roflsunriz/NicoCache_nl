package nicocache.diagnostics;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Resolves application and writable user-data locations without core classes. */
final class DiagnosticsPaths {
    private final Path applicationRoot;
    private final Path dataRoot;

    private DiagnosticsPaths(Path applicationRoot, Path dataRoot) {
        this.applicationRoot = applicationRoot;
        this.dataRoot = dataRoot;
    }

    static DiagnosticsPaths resolve(Path explicitApplicationRoot,
            Path explicitDataRoot) {
        Path application = explicitApplicationRoot == null
                ? discoverApplicationRoot()
                : explicitApplicationRoot.toAbsolutePath().normalize();
        Path data = explicitDataRoot == null
                ? discoverDataRoot(application)
                : explicitDataRoot.toAbsolutePath().normalize();
        return new DiagnosticsPaths(application, data);
    }

    private static Path discoverApplicationRoot() {
        String configured = System.getProperty("nicocache.applicationRoot");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        try {
            URI location = DiagnosticsMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path source = Path.of(location).toAbsolutePath().normalize();
            Path directory = Files.isDirectory(source) ? source : source.getParent();
            if (directory != null) {
                return directory;
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // Fall back to the process working directory.
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static Path discoverDataRoot(Path applicationRoot) {
        Path config = applicationRoot.resolve("config.properties");
        if (Files.isRegularFile(config)) {
            try {
                String raw = readRawDataRoot(config);
                Properties properties = new Properties();
                try (var input = Files.newInputStream(config)) {
                    properties.load(input);
                }
                String configured = raw == null
                        ? properties.getProperty("userDataRoot") : raw;
                if (configured != null && !configured.isBlank()) {
                    Path candidate = Path.of(configured);
                    return (candidate.isAbsolute()
                            ? candidate : applicationRoot.resolve(candidate))
                            .toAbsolutePath().normalize();
                }
            } catch (IOException | RuntimeException ignored) {
                // The GUI will expose the fallback and collection error later.
            }
        }
        return defaultDataRoot(applicationRoot);
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
            if (separator <= 0 || !"userDataRoot".equals(
                    trimmed.substring(0, separator).trim())) {
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

    private static Path defaultDataRoot(Path applicationRoot) {
        if (Files.exists(applicationRoot.resolve("portable.flag"))) {
            return applicationRoot;
        }
        Path home = Path.of(System.getProperty("user.home", "."))
                .toAbsolutePath().normalize();
        String os = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return home.resolve("Library/Application Support/NicoCache_nl");
        }
        if (os.contains("linux")) {
            String xdg = System.getenv("XDG_DATA_HOME");
            Path base = xdg == null || xdg.isBlank()
                    ? home.resolve(".local/share") : Path.of(xdg);
            return base.resolve("NicoCache_nl").toAbsolutePath().normalize();
        }
        return home.resolve("NicoCache_nl").normalize();
    }

    Path applicationRoot() { return applicationRoot; }
    Path dataRoot() { return dataRoot; }
    Path controlStatus() { return dataRoot.resolve("data/nicocache-control.properties"); }
    Path expectedStop() { return dataRoot.resolve("data/nicocache-expected-stop.properties"); }
    Path incidentsRoot() { return dataRoot.resolve("diagnostics/incidents"); }
    Path diagnosticsStatus() { return dataRoot.resolve("data/nicocache-diagnostics-status.properties"); }
    Path diagnosticsLock() { return dataRoot.resolve("data/nicocache-diagnostics.lock"); }
    Path diagnosticsShutdownRequest() {
        return dataRoot.resolve("data/nicocache-diagnostics-shutdown.request");
    }

    Path jcmdExecutable() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin",
                windows ? "jcmd.exe" : "jcmd")
                .toAbsolutePath().normalize();
    }
}
