package dareka.updater;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Platform and architecture policy shared by the standalone updater. */
final class UpdaterPlatform {
    enum Kind {
        WINDOWS,
        LINUX,
        MACOS,
        OTHER
    }

    private UpdaterPlatform() {
    }

    static Kind current() {
        return detect(System.getProperty("os.name", ""));
    }

    static Kind detect(String osName) {
        String normalized = osName == null
                ? ""
                : osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("win")) return Kind.WINDOWS;
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            return Kind.MACOS;
        }
        if (normalized.contains("linux")) return Kind.LINUX;
        return Kind.OTHER;
    }

    static String platformId(Kind kind) {
        switch (kind) {
        case WINDOWS: return "windows";
        case LINUX: return "linux";
        case MACOS: return "macos";
        case OTHER:
        default: return "unknown";
        }
    }

    static String architecture() {
        String value = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (value.equals("amd64") || value.equals("x86_64")
                || value.equals("x64")) return "x64";
        if (value.equals("aarch64") || value.equals("arm64")) return "arm64";
        if (value.equals("x86") || value.equals("i386")
                || value.equals("i686")) return "x86";
        return value.isBlank() ? "unknown" : value;
    }

    static Path normalizeApplicationRoot(Path selected) {
        Path normalized = selected.toAbsolutePath().normalize();
        if (current() == Kind.MACOS) {
            String name = fileName(normalized);
            if (name.toLowerCase(Locale.ROOT).endsWith(".app")
                    && java.nio.file.Files.isDirectory(normalized.resolve("Contents"))) {
                return normalized.resolve("Contents");
            }
        }
        return normalized;
    }

    static Path applicationRootFromLauncher(Path launcher) {
        return applicationRootFromLauncher(launcher, current());
    }

    static Path applicationRootFromLauncher(Path launcher, Kind kind) {
        Path normalized = launcher.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) return normalized;
        if (kind == Kind.MACOS
                && "macos".equalsIgnoreCase(fileName(parent))
                && parent.getParent() != null
                && "contents".equalsIgnoreCase(fileName(parent.getParent()))) {
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
            return applicationRoot.resolve("MacOS/NicoCache_nl");
        case LINUX:
            return applicationRoot.resolve("bin/NicoCache_nl");
        case OTHER:
        default:
            return applicationRoot.resolve("NicoCache_nl");
        }
    }

    static Path applicationDirectory(Path applicationRoot) {
        Path normalized = applicationRoot.toAbsolutePath().normalize();
        Path linuxDirectory = normalized.resolve("lib/app");
        return Files.isDirectory(linuxDirectory) ? linuxDirectory : normalized.resolve("app");
    }

    static Path resourceDirectory(Path applicationRoot) {
        Path normalized = applicationRoot.toAbsolutePath().normalize();
        Path resources = normalized.resolve("Resources");
        return Files.isDirectory(resources) ? resources : normalized;
    }

    static Path runtimeDirectory(Path applicationRoot) {
        Path normalized = applicationRoot.toAbsolutePath().normalize();
        Path macDirectory = normalized.resolve("runtime/Contents/Home");
        if (Files.isDirectory(macDirectory)) return macDirectory;
        Path linuxDirectory = normalized.resolve("lib/runtime");
        return Files.isDirectory(linuxDirectory) ? linuxDirectory : normalized.resolve("runtime");
    }

    static Path defaultRoot() {
        Path home = homeDirectory();
        switch (current()) {
        case WINDOWS:
            String localAppData = System.getenv("LOCALAPPDATA");
            return Path.of(localAppData == null || localAppData.isBlank()
                    ? home.resolve("AppData/Local").toString() : localAppData,
                    "NicoCache_nl").toAbsolutePath().normalize();
        case MACOS:
            Path[] macCandidates = new Path[] {
                Path.of("/Applications/NicoCache_nl.app/Contents"),
                home.resolve("Applications/NicoCache_nl.app/Contents")
            };
            for (Path candidate : macCandidates) {
                if (java.nio.file.Files.isDirectory(candidate)) return candidate;
            }
            return macCandidates[0].toAbsolutePath().normalize();
        case LINUX:
            Path[] linuxCandidates = new Path[] {
                home.resolve(".local/opt/NicoCache_nl"),
                Path.of("/opt/NicoCache_nl"),
                Path.of("/opt/nicocache-nl"),
                Path.of("/usr/local/lib/NicoCache_nl"),
                Path.of("/usr/local/lib/nicocache-nl")
            };
            for (Path candidate : linuxCandidates) {
                if (java.nio.file.Files.isDirectory(candidate)) return candidate;
            }
            return linuxCandidates[0].toAbsolutePath().normalize();
        case OTHER:
        default:
            return home.resolve("NicoCache_nl");
        }
    }

    static String packageDescription() {
        switch (current()) {
        case WINDOWS: return "Windows Installer (MSI)";
        case LINUX: return "LinuxアプリイメージZIP";
        case MACOS: return "macOSアプリイメージZIP";
        case OTHER:
        default: return "対応プラットフォームの配布物";
        }
    }

    static Path homeDirectory() {
        return Path.of(System.getProperty("user.home", "."))
                .toAbsolutePath().normalize();
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }
}
