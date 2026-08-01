package nicocache.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

final class LauncherPaths {
    enum Platform {
        WINDOWS,
        MACOS,
        LINUX,
        OTHER
    }

    private final Path applicationRoot;
    private final Path dataRoot;
    private final Path coreJar;
    private final Path launcherJar;
    private final Path launcherExecutable;

    private LauncherPaths(Path applicationRoot, Path dataRoot, Path coreJar,
            Path launcherJar, Path launcherExecutable) {
        this.applicationRoot = applicationRoot;
        this.dataRoot = dataRoot;
        this.coreJar = coreJar;
        this.launcherJar = launcherJar;
        this.launcherExecutable = launcherExecutable;
    }

    static LauncherPaths resolve(Path explicitApplicationRoot,
            Path explicitDataRoot) {
        Path applicationRoot = explicitApplicationRoot == null
                ? discoverApplicationRoot()
                : explicitApplicationRoot.toAbsolutePath().normalize();
        Path dataRoot = explicitDataRoot == null
                ? discoverDataRoot(applicationRoot)
                : explicitDataRoot.toAbsolutePath().normalize();
        Path coreJar = firstRegularFile(
                applicationRoot.resolve("NicoCache_nl.jar"),
                applicationRoot.resolve("app/NicoCache_nl.jar"),
                applicationRoot.resolve("lib/app/NicoCache_nl.jar"));
        if (coreJar == null) {
            throw new IllegalStateException(
                    "NicoCache_nl.jar が見つかりません: " + applicationRoot);
        }
        Path codeSource = codeSourceDirectory();
        Path launcherJar = firstRegularFile(
                codeSource == null ? null : codeSource.resolve("NicoCacheLauncher.jar"),
                codeSource == null ? null : codeSource.resolve("nicocache-launcher.jar"),
                applicationRoot.resolve("NicoCacheLauncher.jar"),
                applicationRoot.resolve("app/NicoCacheLauncher.jar"),
                applicationRoot.resolve("lib/app/NicoCacheLauncher.jar"));
        if (launcherJar == null && codeSource != null
                && Files.isRegularFile(codeSource.resolve("LauncherMain.class"))) {
            launcherJar = codeSource;
        }
        Path executable = jpackageExecutable();
        return new LauncherPaths(applicationRoot, dataRoot, coreJar,
                launcherJar, executable);
    }

    private static Path discoverApplicationRoot() {
        String configured = System.getProperty("nicocache.launcher.root");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String jpackagePath = System.getProperty("jpackage.app-path");
        if (jpackagePath != null && !jpackagePath.isBlank()) {
            Path launcher = Path.of(jpackagePath).toAbsolutePath().normalize();
            Path parent = launcher.getParent();
            if (parent != null) {
                Platform platform = currentPlatform();
                if (platform == Platform.MACOS
                        && "MacOS".equalsIgnoreCase(fileName(parent))
                        && parent.getParent() != null) {
                    Path contents = parent.getParent();
                    Path resources = contents.resolve("Resources");
                    if (Files.isDirectory(resources)) {
                        return resources;
                    }
                    return contents;
                }
                if (platform == Platform.LINUX
                        && "bin".equalsIgnoreCase(fileName(parent))
                        && parent.getParent() != null) {
                    return parent.getParent();
                }
                return parent;
            }
        }
        Path codeSource = codeSourceDirectory();
        if (codeSource != null) {
            Path candidate = codeSource;
            if (Files.isRegularFile(candidate.resolve("NicoCache_nl.jar"))) {
                return candidate;
            }
            Path parent = candidate.getParent();
            if (parent != null
                    && Files.isRegularFile(parent.resolve("NicoCache_nl.jar"))) {
                return parent;
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static Path discoverDataRoot(Path applicationRoot) {
        Path config = applicationRoot.resolve("config.properties");
        if (Files.isRegularFile(config)) {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(config)) {
                properties.load(input);
                String configured = readRawDataRoot(config);
                if (configured == null) {
                    configured = properties.getProperty("userDataRoot");
                }
                if (configured != null && !configured.isBlank()) {
                    Path value = Path.of(configured);
                    return (value.isAbsolute()
                            ? value
                            : applicationRoot.resolve(value))
                            .toAbsolutePath().normalize();
                }
            } catch (IOException | java.nio.file.InvalidPathException error) {
                throw new IllegalStateException(
                        "設定ファイルのユーザーデータ先を読み取れません: "
                                + config, error);
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
            if (separator <= 0
                    || !"userDataRoot".equals(trimmed.substring(0,
                            separator).trim())) {
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

    static Path defaultDataRoot(Path applicationRoot) {
        if (Files.exists(applicationRoot.resolve("portable.flag"))) {
            return applicationRoot.toAbsolutePath().normalize();
        }
        Path home = Path.of(System.getProperty("user.home", "."))
                .toAbsolutePath().normalize();
        switch (currentPlatform()) {
        case MACOS:
            return home.resolve("Library/Application Support/NicoCache_nl")
                    .normalize();
        case LINUX:
            String xdg = System.getenv("XDG_DATA_HOME");
            Path dataHome = xdg == null || xdg.isBlank()
                    ? home.resolve(".local/share")
                    : Path.of(xdg);
            return dataHome.resolve("NicoCache_nl").toAbsolutePath().normalize();
        case WINDOWS:
            try {
                return javax.swing.filechooser.FileSystemView
                        .getFileSystemView().getDefaultDirectory().toPath()
                        .resolve("NicoCache_nl").toAbsolutePath().normalize();
            } catch (RuntimeException error) {
                return home.resolve("NicoCache_nl").normalize();
            }
        case OTHER:
        default:
            return home.resolve("NicoCache_nl").normalize();
        }
    }

    private static Path codeSourceDirectory() {
        try {
            URI location = LauncherMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path path = Path.of(location).toAbsolutePath().normalize();
            return Files.isDirectory(path) ? path : path.getParent();
        } catch (URISyntaxException | RuntimeException error) {
            return null;
        }
    }

    private static Path jpackageExecutable() {
        String value = System.getProperty("jpackage.app-path");
        if (value == null || value.isBlank()) {
            return null;
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        return Files.isRegularFile(path) ? path : null;
    }

    private static Path firstRegularFile(Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static Platform currentPlatform() {
        String name = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return Platform.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return Platform.MACOS;
        }
        if (name.contains("linux")) {
            return Platform.LINUX;
        }
        return Platform.OTHER;
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    Path getApplicationRoot() {
        return applicationRoot;
    }

    Path getDataRoot() {
        return dataRoot;
    }

    Path getCoreJar() {
        return coreJar;
    }

    Path getLauncherJar() {
        return launcherJar;
    }

    Path getLauncherExecutable() {
        return launcherExecutable;
    }

    Path getControlStatusFile() {
        return dataRoot.resolve("data/nicocache-control.properties");
    }

    Path getTaskStore() {
        return dataRoot.resolve("data/launcher-tasks.properties");
    }

    Platform getPlatform() {
        return currentPlatform();
    }

    List<String> getTaskCommand() {
        List<String> command = new ArrayList<>();
        if (launcherExecutable != null) {
            command.add(launcherExecutable.toString());
            command.add("--headless");
            command.add("--start");
            return command;
        }
        Path java = Path.of(System.getProperty("java.home"), "bin",
                getPlatform() == Platform.WINDOWS ? "java.exe" : "java");
        command.add(java.toString());
        command.add("-jar");
        if (launcherJar == null) {
            throw new IllegalStateException(
                    "タスク登録に必要なランチャーJARが見つかりません");
        }
        command.add(launcherJar.toString());
        command.add("--headless");
        command.add("--start");
        command.add("--app-root=" + applicationRoot);
        command.add("--data-root=" + dataRoot);
        return command;
    }

    String describe() {
        return "applicationRoot=" + applicationRoot + System.lineSeparator()
                + "dataRoot=" + dataRoot + System.lineSeparator()
                + "coreJar=" + coreJar;
    }
}
