package dareka.updater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Verifies archive updates preserve user state and reject unsafe ZIP paths. */
public final class ArchiveApplicationInstallerTest {
    private ArchiveApplicationInstallerTest() {
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("archive-installer-test-");
        try {
            Path target = work.resolve("installed");
            Files.createDirectories(target.resolve("lib/app"));
            Files.createDirectories(target.resolve("bin"));
            Files.writeString(target.resolve("bin/NicoCache_nl"), "old-launcher",
                    StandardCharsets.UTF_8);
            Files.writeString(target.resolve("lib/app/NicoCache_nl.jar"), "old-jar",
                    StandardCharsets.UTF_8);
            Files.writeString(target.resolve("lib/app/NicoCache_nl.cfg"),
                    "java-options=-Djpackage.app-version=1.0.0\n", StandardCharsets.UTF_8);
            Files.writeString(target.resolve("config.properties"), "userDataRoot=/data\n",
                    StandardCharsets.UTF_8);
            Files.createDirectories(target.resolve("data"));
            Files.writeString(target.resolve("data/user-state.txt"), "keep",
                    StandardCharsets.UTF_8);
            Files.writeString(target.resolve("stale-product.txt"), "remove",
                    StandardCharsets.UTF_8);

            Path archive = work.resolve("NicoCache_nl-2.0.0-linux-x64.zip");
            writeArchive(archive, false);
            ArchiveApplicationInstaller.install(archive, target, UpdaterPlatform.Kind.LINUX);

            assertEquals("new-launcher", Files.readString(target.resolve("bin/NicoCache_nl")),
                    "launcher was replaced");
            assertEquals("userDataRoot=/data\n",
                    Files.readString(target.resolve("config.properties")),
                    "application config was preserved");
            assertEquals("keep", Files.readString(target.resolve("data/user-state.txt")),
                    "user data was preserved");
            assertTrue(Files.exists(target.resolve("new-product.txt")),
                    "new product file was installed");
            assertTrue(!Files.exists(target.resolve("stale-product.txt")),
                    "stale product file was removed");
            assertTrue(!Files.exists(target.resolve("data/package-sample.txt")),
                    "package sample did not overwrite user data");

            Path unsafeTarget = work.resolve("unsafe-installed");
            Files.createDirectories(unsafeTarget.resolve("lib/app"));
            Files.createDirectories(unsafeTarget.resolve("bin"));
            Files.writeString(unsafeTarget.resolve("bin/NicoCache_nl"), "launcher",
                    StandardCharsets.UTF_8);
            Files.writeString(unsafeTarget.resolve("lib/app/NicoCache_nl.jar"), "jar",
                    StandardCharsets.UTF_8);
            Path unsafeArchive = work.resolve("unsafe.zip");
            writeArchive(unsafeArchive, true);
            expectFailure(() -> ArchiveApplicationInstaller.install(unsafeArchive,
                    unsafeTarget, UpdaterPlatform.Kind.LINUX), "unsafe ZIP path was accepted");
            assertTrue(!Files.exists(work.resolve("escape.txt")),
                    "unsafe ZIP escaped the extraction directory");
        } finally {
            deleteTree(work);
        }
        System.out.println("Archive application installer tests passed");
    }

    private static void writeArchive(Path archive, boolean unsafe) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            if (unsafe) {
                output.putNextEntry(new ZipEntry("../escape.txt"));
                output.write("escape".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
                return;
            }
            add(output, "NicoCache_nl/bin/NicoCache_nl", "new-launcher");
            add(output, "NicoCache_nl/lib/app/NicoCache_nl.jar", "new-jar");
            add(output, "NicoCache_nl/lib/app/NicoCache_nl.cfg",
                    "java-options=-Djpackage.app-version=2.0.0\n");
            add(output, "NicoCache_nl/new-product.txt", "new");
            add(output, "NicoCache_nl/data/package-sample.txt", "sample");
        }
    }

    private static void add(ZipOutputStream output, String name, String content)
            throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static void expectFailure(Action action, String message) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
