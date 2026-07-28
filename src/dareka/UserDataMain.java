package dareka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Packaged launcher that separates user-managed files from application files.
 */
final class UserDataMain {
    private static final String LAYOUT_VERSION = "1";
    private static final String LAYOUT_VERSION_FILE = ".data-layout-version";
    private static final List<String> USER_FILES = List.of(
            "config.properties",
            "config.ini",
            "NicoCacheGUI.property",
            "proxy.pac");
    private static final List<String> USER_DIRECTORIES = List.of(
            "local",
            "extensions",
            "nlFilters");

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

        if (NicoCachePaths.isPortable() && !hasConfiguredDataRoot()) {
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
        } catch (IOException | RuntimeException error) {
            System.err.println("利用者データ領域の準備に失敗しました: " + error);
            NLMain.main(args);
            return;
        }

        NLMain.main(args);
    }

    private static boolean hasConfiguredDataRoot() {
        String property = System.getProperty(
                NicoCachePaths.DATA_ROOT_PROPERTY);
        if (property != null && !property.isBlank()) {
            return true;
        }
        String environment = System.getenv(
                NicoCachePaths.DATA_ROOT_ENVIRONMENT);
        return environment != null && !environment.isBlank();
    }

    static void prepareDataRoot(Path applicationRoot, Path dataRoot)
            throws IOException {
        Path normalizedApplicationRoot =
                applicationRoot.toAbsolutePath().normalize();
        Path normalizedDataRoot = dataRoot.toAbsolutePath().normalize();
        if (normalizedApplicationRoot.equals(normalizedDataRoot)) {
            return;
        }
        Files.createDirectories(normalizedDataRoot);

        for (String name : USER_FILES) {
            migrateIfMissing(normalizedApplicationRoot.resolve(name),
                    normalizedDataRoot.resolve(name));
        }
        for (String name : USER_DIRECTORIES) {
            migrateDirectoryIfMissing(normalizedApplicationRoot.resolve(name),
                    normalizedDataRoot.resolve(name));
        }

        Path versionFile = normalizedDataRoot.resolve(LAYOUT_VERSION_FILE);
        Path temporary = normalizedDataRoot.resolve(
                LAYOUT_VERSION_FILE + ".tmp");
        Files.writeString(
                temporary,
                LAYOUT_VERSION + System.lineSeparator(),
                StandardCharsets.US_ASCII);
        try {
            Files.move(
                    temporary,
                    versionFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(
                    temporary,
                    versionFile,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void migrateIfMissing(Path source, Path destination)
            throws IOException {
        if (Files.exists(destination) || !Files.isRegularFile(source)) {
            return;
        }
        Files.createDirectories(destination.getParent());
        Path temporary = migrationTemporary(destination);
        Files.deleteIfExists(temporary);
        try {
            Files.copy(
                    source,
                    temporary,
                    StandardCopyOption.COPY_ATTRIBUTES);
            moveMigrationResult(temporary, destination);
        } catch (IOException error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
        logMigration(source, destination);
    }

    private static void migrateDirectoryIfMissing(Path source, Path destination)
            throws IOException {
        if (Files.exists(destination)) {
            return;
        }
        if (Files.isDirectory(source)) {
            Path temporary = migrationTemporary(destination);
            deleteTreeIfExists(temporary);
            try {
                copyTree(source, temporary);
                moveMigrationResult(temporary, destination);
            } catch (IOException error) {
                deleteTreeIfExists(temporary);
                throw error;
            }
            logMigration(source, destination);
        } else {
            Files.createDirectories(destination);
        }
    }

    private static Path migrationTemporary(Path destination) {
        return destination.resolveSibling(
                destination.getFileName() + ".migration.tmp");
    }

    private static void moveMigrationResult(Path source, Path destination)
            throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicError) {
            try {
                Files.move(source, destination);
            } catch (IOException moveError) {
                moveError.addSuppressed(atomicError);
                throw moveError;
            }
        }
    }

    private static void deleteTreeIfExists(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            Path[] entries = paths.sorted(java.util.Comparator.reverseOrder())
                    .toArray(Path[]::new);
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static void copyTree(Path source, Path destination)
            throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
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

    private static void logMigration(Path source, Path destination) {
        System.out.println("利用者データを移行しました: "
                + source + " -> " + destination);
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
