package nicocache.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

/** File-based authenticated control channel for the resident launcher. */
final class LauncherControl implements AutoCloseable {
    private static final String VERSION = "1";
    private static final long WAIT_INTERVAL_MILLIS = 100L;

    private final Path controlFile;
    private final Path requestFile;
    private final String token;

    private LauncherControl(Path controlFile, Path requestFile,
            String token) {
        this.controlFile = controlFile;
        this.requestFile = requestFile;
        this.token = token;
    }

    static LauncherControl register(LauncherPaths paths) throws IOException {
        Path controlFile = paths.getLauncherControlFile();
        Path requestFile = paths.getLauncherExitRequestFile();
        Files.createDirectories(controlFile.getParent());
        String token = UUID.randomUUID().toString();
        Properties state = new Properties();
        state.setProperty("version", VERSION);
        state.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        state.setProperty("token", token);
        Files.deleteIfExists(requestFile);
        writePropertiesAtomically(controlFile, state);
        return new LauncherControl(controlFile, requestFile, token);
    }

    boolean consumeExitRequest() throws IOException {
        if (!Files.isRegularFile(requestFile)) {
            return false;
        }
        String requestedToken = Files.readString(
                requestFile, StandardCharsets.UTF_8).trim();
        Files.deleteIfExists(requestFile);
        return token.equals(requestedToken);
    }

    static void requestExit(LauncherPaths paths, Duration timeout)
            throws IOException, InterruptedException {
        Path controlFile = paths.getLauncherControlFile();
        Properties state = readProperties(controlFile);
        if (!VERSION.equals(state.getProperty("version"))) {
            throw new IOException("常駐ランチャーの制御情報が不正です");
        }
        String token = state.getProperty("token", "").trim();
        long pid = parsePid(state.getProperty("pid"));
        ProcessHandle process = ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .orElseThrow(() -> new IOException(
                        "常駐ランチャーが見つかりません: pid=" + pid));
        if (token.isEmpty()) {
            throw new IOException("常駐ランチャーの認証情報がありません");
        }

        writeStringAtomically(paths.getLauncherExitRequestFile(), token);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(WAIT_INTERVAL_MILLIS);
        }
        if (process.isAlive()) {
            throw new IOException("常駐ランチャーの終了がタイムアウトしました: pid="
                    + pid);
        }
    }

    private static long parsePid(String value) throws IOException {
        try {
            long pid = Long.parseLong(value == null ? "" : value.trim());
            if (pid <= 0) {
                throw new NumberFormatException("non-positive PID");
            }
            return pid;
        } catch (NumberFormatException error) {
            throw new IOException("常駐ランチャーのPIDが不正です", error);
        }
    }

    private static Properties readProperties(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("常駐ランチャーが見つかりません: " + path);
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void writePropertiesAtomically(Path destination,
            Properties properties) throws IOException {
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".part");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, null);
            }
            restrictFile(temporary);
            moveAtomically(temporary, destination);
            restrictFile(destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeStringAtomically(Path destination, String value)
            throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".part");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            restrictFile(temporary);
            moveAtomically(temporary, destination);
            restrictFile(destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveAtomically(Path source, Path destination)
            throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(source, destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrictFile(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs on the user data root remain the protection there.
        }
    }

    @Override
    public void close() {
        deleteOwnedFile(requestFile, false);
        deleteOwnedFile(controlFile, true);
    }

    private void deleteOwnedFile(Path path, boolean propertiesFile) {
        try {
            if (!Files.isRegularFile(path)) {
                return;
            }
            String owner = propertiesFile
                    ? readProperties(path).getProperty("token", "")
                    : Files.readString(path, StandardCharsets.UTF_8).trim();
            if (token.equals(owner)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // The launcher is already exiting; stale state is validated by PID.
        }
    }
}
