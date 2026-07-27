package dareka;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Packaged launcher that separates user-managed files from application files.
 */
public final class UserDataMain {
    private static final List<String> USER_FILES = List.of(
            "config.properties",
            "NicoCacheGUI.property",
            "proxy.pac");
    private static final List<String> USER_DIRECTORIES = List.of(
            "local",
            "nlFilters");
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
        Path applicationRoot = executable.getParent();
        if (applicationRoot == null) {
            NLMain.main(args);
            return;
        }
        System.setProperty(NicoCachePaths.APPLICATION_ROOT_PROPERTY,
                applicationRoot.toString());

        if (NicoCachePaths.isPortable()) {
            NLMain.main(args);
            return;
        }

        try {
            Path dataRoot = NicoCachePaths.dataRoot();
            prepareDataRoot(applicationRoot, dataRoot);

            System.setProperty(NicoCachePaths.DATA_ROOT_PROPERTY,
                    dataRoot.toString());
            System.clearProperty("jpackage.app-path");
            System.setProperty("user.dir", dataRoot.toString());

            Path previousDirectory = Path.of("").toAbsolutePath().normalize();
            if (!previousDirectory.equals(dataRoot)) {
                relaunchFromDataRoot(executable, dataRoot, args);
                return;
            }
        } catch (IOException error) {
            System.err.println("利用者データ領域の準備に失敗しました: " + error);
            NLMain.main(args);
            return;
        }

        NLMain.main(args);
    }

    static void prepareDataRoot(Path applicationRoot, Path dataRoot)
            throws IOException {
        Files.createDirectories(dataRoot);

        for (String name : USER_FILES) {
            migrateIfMissing(applicationRoot.resolve(name),
                    NicoCachePaths.userPath(name));
        }
        for (String name : USER_DIRECTORIES) {
            migrateDirectoryIfMissing(applicationRoot.resolve(name),
                    NicoCachePaths.userPath(name));
        }

        for (String name : DISTRIBUTION_FILES) {
            copyIfMissing(applicationRoot.resolve(name),
                    NicoCachePaths.userPath(name));
        }
        for (String name : DISTRIBUTION_DIRECTORIES) {
            copyDirectoryIfMissing(applicationRoot.resolve(name),
                    NicoCachePaths.userPath(name));
        }

        Files.writeString(dataRoot.resolve(".data-layout-version"), "3\n");
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
