package dareka.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Replaces a Unix/macOS app image while preserving user-owned state. */
final class ArchiveApplicationInstaller {
    private static final int MAX_ENTRIES = 200_000;
    private static final long MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024;
    private static final String[] PRESERVED_ROOT_ENTRIES = {
        "config.properties",
        "portable.flag",
        "data",
        "cache",
        "cvcache",
        "thcache",
        "certs",
        "list",
        "local",
        "nlFilters",
        "extensions",
        "NicoCacheGUI.property",
        "NicoCacheGUI.search-history.properties",
        "debug.log"
    };

    private ArchiveApplicationInstaller() {
    }

    static void install(Path archive, Path targetRoot, UpdaterPlatform.Kind platform)
            throws IOException {
        if (platform != UpdaterPlatform.Kind.LINUX
                && platform != UpdaterPlatform.Kind.MACOS) {
            throw new IOException("アーカイブ更新はLinux/macOS専用です");
        }
        if (!Files.isRegularFile(archive)) {
            throw new IOException("更新アーカイブが見つかりません: " + archive);
        }
        Path target = TargetRootResolver.requireInstallation(targetRoot);
        Path parent = target.getParent();
        if (parent == null) throw new IOException("更新対象の親ディレクトリを解決できません");
        Files.createDirectories(parent);

        Path work = Files.createTempDirectory(parent, ".nicocache-update-");
        Path extracted = work.resolve("extracted");
        Path replacement = work.resolve("replacement");
        Path preserved = work.resolve("preserved");
        Path backup = parent.resolve("." + target.getFileName() + ".backup-"
                + Long.toUnsignedString(System.nanoTime()));
        List<String> movedPreserved = new ArrayList<String>();
        boolean targetMoved = false;
        boolean rollbackComplete = true;
        try {
            Files.createDirectories(extracted);
            extract(archive, extracted);
            Path packageRoot = findPackageRoot(extracted, platform);
            restoreUnixExecutableBits(packageRoot, platform);
            copyTree(packageRoot, replacement);
            validatePackageRoot(replacement, platform);

            Files.createDirectories(preserved);
            for (String entry : PRESERVED_ROOT_ENTRIES) {
                Path source = target.resolve(entry).normalize();
                if (!source.startsWith(target) || !Files.exists(source)) continue;
                movePath(source, preserved.resolve(entry));
                movedPreserved.add(entry);
            }

            movePath(target, backup);
            targetMoved = true;
            movePath(replacement, target);
            restorePreserved(preserved, target);
            validatePackageRoot(target, platform);
        } catch (Exception error) {
            try {
                if (Files.exists(target) && targetMoved) {
                    Path failed = work.resolve("failed-replacement");
                    movePath(target, failed);
                    reclaimPreserved(failed, preserved, movedPreserved);
                }
                if (targetMoved && Files.exists(backup)) movePath(backup, target);
                if (Files.isDirectory(preserved)) restorePreserved(preserved, target);
            } catch (Exception rollbackError) {
                rollbackComplete = false;
                error.addSuppressed(new IOException(
                        "更新前のバックアップを保持しています: " + backup
                                + " (作業領域: " + work + ")",
                        rollbackError));
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("アーカイブ更新に失敗しました", error);
        } finally {
            if (rollbackComplete) {
                deleteTreeQuietly(work);
                if (Files.exists(backup)) deleteTreeQuietly(backup);
            }
        }
    }

    private static void extract(Path archive, Path destination) throws IOException {
        Set<Path> entries = new HashSet<Path>();
        long expandedBytes = 0;
        int entryCount = 0;
        try (InputStream input = Files.newInputStream(archive);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES) {
                    throw new IOException("更新アーカイブのエントリー数が上限を超えています");
                }
                Path relative = safeRelativePath(entry.getName());
                if (!entries.add(relative)) throw new IOException("重複するZIPエントリーです: " + relative);
                Path output = destination.resolve(relative).normalize();
                if (!output.startsWith(destination)) throw new IOException("ZIPパスが展開先を脱出します");
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                Files.createDirectories(output.getParent());
                long entryBytes = 0;
                try (java.io.OutputStream stream = Files.newOutputStream(output,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        entryBytes += read;
                        expandedBytes += read;
                        if (entryBytes > MAX_EXPANDED_BYTES
                                || expandedBytes > MAX_EXPANDED_BYTES) {
                            throw new IOException("更新アーカイブの展開サイズが上限を超えています");
                        }
                        stream.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static Path safeRelativePath(String name) throws IOException {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0
                || name.startsWith("/") || name.startsWith("\\")
                || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("ZIPパスが不正です: " + name);
        }
        String normalizedSeparators = name.replace('\\', '/');
        Path relative = Path.of(normalizedSeparators).normalize();
        if (relative.isAbsolute() || relative.getNameCount() == 0
                || relative.startsWith("..")) {
            throw new IOException("ZIPパスが不正です: " + name);
        }
        return relative;
    }

    private static Path findPackageRoot(Path extracted, UpdaterPlatform.Kind platform)
            throws IOException {
        if (isPackageRoot(extracted, platform)) return extracted;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(extracted)) {
            Path match = null;
            for (Path candidate : stream) {
                if (!Files.isDirectory(candidate) || !isPackageRoot(candidate, platform)) continue;
                if (match != null) throw new IOException("複数のアプリイメージがZIPに含まれています");
                match = candidate;
            }
            if (match != null) return platform == UpdaterPlatform.Kind.MACOS
                    && match.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".app")
                    ? match.resolve("Contents") : match;
        }
        throw new IOException("認識できるNicoCache_nlアプリイメージがZIPにありません");
    }

    private static boolean isPackageRoot(Path root, UpdaterPlatform.Kind platform) {
        if (!Files.isDirectory(root)) return false;
        Path candidate = root;
        if (platform == UpdaterPlatform.Kind.MACOS
                && root.getFileName() != null
                && root.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".app")) {
            candidate = root.resolve("Contents");
        }
        if (!Files.isDirectory(candidate)) return false;
        if (!Files.isRegularFile(candidate.resolve("app/NicoCache_nl.jar"))) return false;
        if (platform == UpdaterPlatform.Kind.MACOS) {
            return Files.isRegularFile(candidate.resolve("MacOS/NicoCache_nl"));
        }
        return Files.isRegularFile(candidate.resolve("bin/NicoCache_nl"))
                || Files.isRegularFile(candidate.resolve("NicoCache_nl"));
    }

    private static void validatePackageRoot(Path root, UpdaterPlatform.Kind platform)
            throws IOException {
        if (!isPackageRoot(root, platform)) {
            throw new IOException("更新アーカイブのアプリイメージ構造が不正です");
        }
    }

    private static void restoreUnixExecutableBits(Path root, UpdaterPlatform.Kind platform)
            throws IOException {
        Path launcher = platform == UpdaterPlatform.Kind.MACOS
                ? root.resolve("MacOS/NicoCache_nl")
                : root.resolve("bin/NicoCache_nl");
        if (platform == UpdaterPlatform.Kind.LINUX && !Files.isRegularFile(launcher)) {
            launcher = root.resolve("NicoCache_nl");
        }
        setExecutable(launcher);
        Path runtimeBin = root.resolve("runtime/bin");
        if (Files.isDirectory(runtimeBin)) {
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(runtimeBin)) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) setExecutable(file);
                }
            }
        }
        setExecutable(root.resolve("runtime/lib/jspawnhelper"));
    }

    private static void setExecutable(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return;
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows-only unit tests can exercise Linux archive validation without POSIX permissions.
        }
    }

    private static void restorePreserved(Path preserved, Path target) throws IOException {
        if (!Files.isDirectory(preserved)) return;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(preserved)) {
            for (Path entry : stream) {
                Path destination = target.resolve(entry.getFileName());
                if (Files.exists(destination)) deleteTree(destination);
                movePath(entry, destination);
            }
        }
    }

    private static void reclaimPreserved(Path failed, Path preserved, List<String> entries)
            throws IOException {
        for (String entry : entries) {
            Path source = failed.resolve(entry);
            Path destination = preserved.resolve(entry);
            if (Files.exists(source) && !Files.exists(destination)) movePath(source, destination);
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path directory,
                    BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSymbolicLink(file)) throw new IOException("シンボリックリンクを更新アーカイブに含められません");
                Path output = destination.resolve(source.relativize(file));
                Files.createDirectories(output.getParent());
                Files.copy(file, output, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void movePath(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination);
        }
    }

    private static void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // The temporary backup is outside the active application after success.
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error)
                    throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
