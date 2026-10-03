package functional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dareka.processor.impl.ThumbProcessor2;

/** Isolated core startup and configuration-observer regressions. */
public final class ThumbnailCacheStartupTest {
    private ThumbnailCacheStartupTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--observer".equals(args[0])) {
            testObserver();
        } else if (args.length == 2) {
            run(Path.of(args[0]), Path.of(args[1]), System.getProperty("java.class.path"));
        } else {
            throw new IllegalArgumentException("<repository> <sandbox> or --observer");
        }
    }

    public static void run(Path repository, Path sandbox, String classpath)
            throws Exception {
        for (boolean enabled : List.of(false, true)) {
            for (String layout : List.of("missing", "existing", "nested",
                    "file", "parent-file", "ref-file")) {
                testStartup(repository, sandbox.resolve(enabled + "-" + layout),
                        classpath, enabled, layout);
            }
        }
        Path observer = sandbox.resolve("observer");
        Files.createDirectories(observer);
        Path log = observer.resolve("observer.log");
        Process process = new ProcessBuilder(javaExecutable(),
                "-Dnicocache.userDataRoot=" + observer,
                "-cp", classpath, ThumbnailCacheStartupTest.class.getName(),
                "--observer").redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new AssertionError("thumbnail observer failed: " + log);
            }
        } finally {
            stop(process);
        }
        System.out.println("PASS thumbnail startup layouts (12) and configuration observer");
    }

    private static void testStartup(Path repository, Path sandbox,
            String classpath, boolean enabled, String layout) throws Exception {
        Path app = sandbox.resolve("application");
        Path data = sandbox.resolve("user-data");
        Files.createDirectories(app.resolve("defaults"));
        try (var files = Files.list(repository.resolve("defaults"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.copy(file, app.resolve("defaults").resolve(file.getFileName()));
            }
        }
        for (String name : List.of("cache", "cvcache", "local", "nlFilters",
                "extensions", "data/tlsclient")) {
            Files.createDirectories(data.resolve(name));
        }
        Files.copy(repository.resolve("data/tlsclient/cacerts2"),
                data.resolve("data/tlsclient/cacerts2"));
        String folder = "nested".equals(layout) || "parent-file".equals(layout)
                ? "nested/thcache" : "thcache";
        Path thumbnail = data.resolve(folder);
        Path sentinel = null;
        if ("existing".equals(layout) || "ref-file".equals(layout)) {
            Files.createDirectories(thumbnail);
            sentinel = "ref-file".equals(layout)
                    ? thumbnail.resolve("ref") : thumbnail.resolve("keep.bin");
        } else if ("file".equals(layout)) {
            sentinel = thumbnail;
        } else if ("parent-file".equals(layout)) {
            sentinel = thumbnail.getParent();
        }
        if (sentinel != null) {
            Files.writeString(sentinel, "preserve-cache-fixture");
        }
        int port;
        try (var socket = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        Files.writeString(app.resolve("config.properties"),
                "listenPort=" + port + "\ncacheThumbnail=" + enabled
                        + "\nthcacheFolder=" + folder
                        + "\nenableMitm=false\ndisableDirectoryWatcher=true"
                        + "\nneedFreeSpace=0\nlocalRewriter=false\n"
                        + "cacheGetThumbInfo=false\ncacheExtThumb=false\n",
                StandardCharsets.UTF_8);
        Path log = sandbox.resolve("core.log");
        Process process = new ProcessBuilder(javaExecutable(),
                "--enable-native-access=ALL-UNNAMED",
                "-Dnicocache.applicationRoot=" + app,
                "-Dnicocache.userDataRoot=" + data,
                "-cp", classpath, "dareka.Main")
                .directory(app.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        boolean collision = layout.endsWith("file");
        boolean shouldStart = !enabled || !collision;
        try {
            boolean ready = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                    ready = true;
                    break;
                } catch (IOException error) {
                    Thread.sleep(30);
                }
            }
            if (ready != shouldStart) {
                throw new AssertionError("startup " + enabled + "/" + layout
                        + ": ready=" + ready + "; log=" + log);
            }
            if (shouldStart && enabled && !Files.isDirectory(thumbnail.resolve("ref"))) {
                throw new AssertionError("enabled cache must create ref directory: " + log);
            }
            if (!enabled && ("missing".equals(layout) || "nested".equals(layout))
                    && Files.exists(thumbnail)) {
                throw new AssertionError("disabled cache must not create its directory");
            }
            if (!shouldStart) {
                String output = Files.readString(log);
                if (!output.contains("Cannot initialize thumbnail cache")
                        || !output.contains(thumbnail.toString())) {
                    throw new AssertionError("creation failure must name cache path: " + log);
                }
            }
            if (sentinel != null
                    && !"preserve-cache-fixture".equals(Files.readString(sentinel))) {
                throw new AssertionError("existing file was modified: " + sentinel);
            }
        } finally {
            stop(process);
        }
    }

    private static void testObserver() throws Exception {
        Path data = Path.of(System.getProperty("nicocache.userDataRoot"));
        System.setProperty("cacheThumbnail", "true");
        System.setProperty("thcacheFolder", "original");
        ThumbProcessor2 processor = new ThumbProcessor2();
        // Preserve migration of old per-file references when ref/ is absent.
        Path oldThumbnail = data.resolve("original/001/00001.12345.jpg");
        Files.createDirectories(oldThumbnail.getParent());
        Files.writeString(oldThumbnail, "legacy-thumbnail");
        processor.update(null);
        if (!Files.isRegularFile(data.resolve("original/ref/001/00001.ref"))) {
            throw new AssertionError("existing thumbnail reference must be imported");
        }
        System.setProperty("cacheThumbnail", "false");
        System.setProperty("thcacheFolder", "disabled/missing");
        processor.update(null);
        if (Files.exists(data.resolve("disabled"))) {
            throw new AssertionError("disabled observer must not create cache paths");
        }
        Path blocked = data.resolve("blocked");
        Files.writeString(blocked, "keep-file");
        System.setProperty("thcacheFolder", "blocked/cache");
        processor.update(null);
        System.setProperty("cacheThumbnail", "true");
        try {
            processor.update(null);
            throw new AssertionError("enabled observer must reject file collision");
        } catch (UncheckedIOException expected) {
            if (!expected.getMessage().contains("blocked")) {
                throw new AssertionError("failure must name configured path", expected);
            }
        }
        System.setProperty("thcacheFolder", "enabled/new/cache");
        processor.update(null);
        if (!Files.isDirectory(data.resolve("enabled/new/cache/ref"))) {
            throw new AssertionError("re-enabled observer must create new configured path");
        }
        testPermissions(processor, data);
    }

    private static void testPermissions(ThumbProcessor2 processor, Path data)
            throws Exception {
        Path locked = Files.createDirectory(data.resolve("locked"));
        AclFileAttributeView acl = Files.getFileAttributeView(locked,
                AclFileAttributeView.class);
        PosixFileAttributeView posix = Files.getFileAttributeView(locked,
                PosixFileAttributeView.class);
        List<AclEntry> oldAcl = acl == null ? null : acl.getAcl();
        var oldPermissions = posix == null ? null : posix.readAttributes().permissions();
        try {
            if (acl != null) {
                List<AclEntry> denied = new ArrayList<>(oldAcl);
                denied.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY)
                        .setPrincipal(locked.getFileSystem().getUserPrincipalLookupService()
                                .lookupPrincipalByName(System.getProperty("user.name")))
                        .setPermissions(EnumSet.of(AclEntryPermission.WRITE_DATA,
                                AclEntryPermission.APPEND_DATA,
                                AclEntryPermission.WRITE_NAMED_ATTRS,
                                AclEntryPermission.WRITE_ATTRIBUTES)).build());
                acl.setAcl(denied);
            } else if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString("r-x------"));
            } else {
                throw new AssertionError("no fixture permission mechanism available");
            }
            if (Files.isWritable(locked)) {
                throw new AssertionError("fixture must actually deny writes");
            }
            System.setProperty("cacheThumbnail", "false");
            System.setProperty("thcacheFolder", "locked");
            processor.update(null);
            System.setProperty("cacheThumbnail", "true");
            for (String folder : List.of("locked", "locked/nested/cache")) {
                System.setProperty("thcacheFolder", folder);
                try {
                    processor.update(null);
                    throw new AssertionError("enabled unwritable cache must fail: " + folder);
                } catch (UncheckedIOException expected) {
                    if (!expected.getMessage().contains("locked")) {
                        throw new AssertionError("failure must name unwritable path", expected);
                    }
                }
            }
        } finally {
            if (acl != null) {
                acl.setAcl(oldAcl);
            } else if (posix != null) {
                posix.setPermissions(oldPermissions);
            }
        }
    }

    private static void stop(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test process did not stop");
                }
            }
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
