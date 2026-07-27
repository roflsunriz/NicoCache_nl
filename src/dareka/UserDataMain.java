package dareka;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import javax.swing.filechooser.FileSystemView;

/**
 * Packaged launcher that separates mutable user data from application files.
 *
 * <p>The packaged application keeps immutable distribution files beside the
 * executable while mutable files live in a user-visible NicoCache_nl folder
 * under the operating system's default documents directory. A portable launch
 * remains available by placing {@code portable.flag} beside the executable.</p>
 */
public final class UserDataMain {
    private static final String DATA_ROOT_PROPERTY = "nicocache.dataRoot";
    private static final String PORTABLE_FLAG = "portable.flag";
    private static final List<String> USER_FILES = List.of(
            "config.properties",
            "proxy.pac");
    private static final List<String> USER_DIRECTORIES = List.of(
            "local",
            "nlFilters",
            "cache",
            "certs",
            "cvcache",
            "data",
            "list",
            "thcache");
    private static final List<String> DISTRIBUTION_DIRECTORIES = List.of(
            "defaults",
            "extensions",
            "lib",
            "setup");
    private static final List<String> DISTRIBUTION_FILES = List.of(
            "config.properties.default",
            "certificate-targets.txt",
            "NicoCacheCA.jar",
            "NicoCacheGUI_native.dll",
            "NicoCacheGUI_native64.dll",
            "nlFilter_sys.txt",
            "proxy_sample.pac",
            "THIRD-PARTY-NOTICES.txt");

    private UserDataMain() {
    }

    public static void main(String[] args) {
        String packagedLauncher = System.getProperty("jpackage.app-path");
        if (packagedLauncher == null || packagedLauncher.isBlank()) {
            NLMain.main(args);
            return;
        }

        Path executable = Path.of(packagedLauncher).toAbsolutePath().normalize();
        Path applicationDirectory = executable.getParent();
        if (applicationDirectory == null
                || Files.exists(applicationDirectory.resolve(PORTABLE_FLAG))) {
            NLMain.main(args);
            return;
        }

        try {
            Path dataRoot = resolveDataRoot();
            prepareDataRoot(applicationDirectory, dataRoot);

            // NLMain normally restores user.dir to the packaged application
            // directory. Clear this marker after the required files have been
            // copied so all existing relative-path code uses the data root.
            System.setProperty(DATA_ROOT_PROPERTY, dataRoot.toString());
            System.clearProperty("jpackage.app-path");
            System.setProperty("user.dir", dataRoot.toString());

            Path previousDirectory = Path.of("").toAbsolutePath().normalize();
            if (!previousDirectory.equals(dataRoot)) {
                relaunchFromDataRoot(executable, dataRoot, args);
                return;
            }
        } catch (IOException error) {
            System.err.println("利用者データ領域の準備に失敗しました: " + error);
            // Do not make an existing installation unusable if migration fails.
            NLMain.main(args);
            return;
        }

        NLMain.main(args);
    }

    static Path resolveDataRoot() throws IOException {
        String override = System.getProperty(DATA_ROOT_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override).toAbsolutePath().normalize();
            Files.createDirectories(path);
            return path;
        }

        Path documentsDirectory;
        try {
            documentsDirectory = FileSystemView.getFileSystemView()
                    .getDefaultDirectory()
                    .toPath();
        } catch (RuntimeException error) {
            documentsDirectory = Path.of(System.getProperty("user.home"));
        }

        Path root = documentsDirectory.resolve("NicoCache_nl");
        Files.createDirectories(root);
        return root.toAbsolutePath().normalize();
    }

    static void prepareDataRoot(Path applicationDirectory, Path dataRoot)
            throws IOException {
        Files.createDirectories(dataRoot);

        for (String name : USER_FILES) {
            migrateIfMissing(applicationDirectory.resolve(name),
                    dataRoot.resolve(name));
        }
        for (String name : USER_DIRECTORIES) {
            migrateDirectoryIfMissing(applicationDirectory.resolve(name),
                    dataRoot.resolve(name));
        }

        for (String name : DISTRIBUTION_FILES) {
            copyIfMissing(applicationDirectory.resolve(name),
                    dataRoot.resolve(name));
        }
        for (String name : DISTRIBUTION_DIRECTORIES) {
            copyDirectoryIfMissing(applicationDirectory.resolve(name),
                    dataRoot.resolve(name));
        }

        Files.writeString(dataRoot.resolve(".data-layout-version"), "1\n");
    }

    private static void migrateIfMissing(Path source, Path destination)
            throws IOException {
        if (Files.exists(destination) || !Files.isRegularFile(source)) {
            return;
        }
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void migrateDirectoryIfMissing(Path source, Path destination)
            throws IOException {
        if (Files.exists(destination)) {
            return;
        }
        if (Files.isDirectory(source)) {
            copyTree(source, destination);
        } else {
            Files.createDirectories(destination);
        }
    }

    private static void copyIfMissing(Path source, Path destination)
            throws IOException {
        if (!Files.exists(destination) && Files.isRegularFile(source)) {
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void copyDirectoryIfMissing(Path source, Path destination)
            throws IOException {
        if (!Files.exists(destination) && Files.isDirectory(source)) {
            copyTree(source, destination);
        }
    }

    private static void copyTree(Path source, Path destination)
            throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void relaunchFromDataRoot(
            Path executable,
            Path dataRoot,
            String[] args) throws IOException {
        String[] command = new String[args.length + 1];
        command[0] = executable.toString();
        System.arraycopy(args, 0, command, 1, args.length);

        new ProcessBuilder(command)
                .directory(dataRoot.toFile())
                .inheritIO()
                .start();
    }
}
