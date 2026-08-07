package nicocache.build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * JDK-only build application for NicoCache_nl and its companion JARs.
 *
 * <p>The small PowerShell wrapper only bootstraps this JAR when it is not
 * available yet. The actual source compilation and packaging are therefore
 * platform-neutral Java operations.</p>
 */
public final class BuildMain {
    private static final String RELEASE = "11";

    private BuildMain() {
    }

    public static void main(String[] args) {
        try {
            BuildOptions options = BuildOptions.parse(args);
            new BuildMainRunner(options).run();
        } catch (Exception error) {
            System.err.println("NicoCacheBuild: "
                    + (error.getMessage() == null
                    ? error.toString() : error.getMessage()));
            System.exit(1);
        }
    }

    private static final class BuildMainRunner {
        private final BuildOptions options;
        private final Path root;
        private final Path buildRoot;
        private final Path classesRoot;
        private final Path outputRoot;
        private final JavaCompiler compiler;

        BuildMainRunner(BuildOptions options) {
            this.options = options;
            this.root = options.root;
            this.buildRoot = root.resolve(".build/nicocache");
            this.classesRoot = buildRoot.resolve("classes");
            this.outputRoot = options.outputRoot;
            this.compiler = ToolProvider.getSystemJavaCompiler();
        }

        void run() throws Exception {
            if (compiler == null) {
                throw new IllegalStateException(
                        "JDKのJavaCompilerが見つかりません。JREではなくJDKを使用してください");
            }
            Files.createDirectories(buildRoot);
            if (options.clean) {
                deleteTree(classesRoot);
            }
            Files.createDirectories(outputRoot);
            Path mainClasses = classesRoot.resolve("main");
            Path caClasses = classesRoot.resolve("ca");
            Path launcherClasses = classesRoot.resolve("launcher");
            Path buildClasses = classesRoot.resolve("build");
            deleteTree(mainClasses);
            deleteTree(caClasses);
            deleteTree(launcherClasses);
            deleteTree(buildClasses);

            List<Path> codecLibraries = List.of(
                    options.libraryRoot.resolve("brotli-dec.jar"),
                    options.libraryRoot.resolve("zstd-jni.jar"));
            requireLibraries(codecLibraries, "HTTP圧縮展開ライブラリ");
            compile(javaSources(root.resolve("src/dareka")), mainClasses,
                    codecLibraries);
            copyCoreResources(mainClasses);

            List<Path> bcLibraries = List.of(
                    options.libraryRoot.resolve("bcpkix.jar"),
                    options.libraryRoot.resolve("bcprov.jar"),
                    options.libraryRoot.resolve("bcutil.jar"));
            requireLibraries(bcLibraries, "Bouncy Castleライブラリ");
            compile(javaSources(root.resolve("src/nicocacheca")), caClasses,
                    bcLibraries);
            compile(javaSources(root.resolve(
                    "tools/nicocache-launcher/src/main/java")), launcherClasses,
                    List.of());
            copyDirectory(root.resolve(
                    "tools/nicocache-launcher/src/main/resources"),
                    launcherClasses);
            compile(javaSources(root.resolve(
                    "tools/nicocache-build/src/main/java")), buildClasses,
                    List.of());

            createJar(outputRoot.resolve("NicoCache_nl.jar"), mainClasses,
                    "dareka.UserDataMain",
                    "sqlite-jdbc.jar igo.jar library.jar NicoCacheCA.jar"
                            + " lib/bcpkix.jar lib/bcprov.jar lib/bcutil.jar"
                            + " lib/brotli-dec.jar lib/zstd-jni.jar",
                    root.resolve("src/native"));
            createJar(outputRoot.resolve("NicoCacheCA.jar"), caClasses,
                    "nicocacheca.NicoCacheCA",
                    "lib/bcpkix.jar lib/bcprov.jar lib/bcutil.jar", null);
            createJar(outputRoot.resolve("NicoCacheLauncher.jar"),
                    launcherClasses, "nicocache.launcher.LauncherMain", "", null);
            Path buildJar = outputRoot.resolve("NicoCacheBuild.jar");
            if (!isRunningBuildJar(buildJar)) {
                createJar(buildJar, buildClasses,
                        "nicocache.build.BuildMain", "", null);
            }

            System.out.println("NicoCache_nl.jar="
                    + outputRoot.resolve("NicoCache_nl.jar"));
            System.out.println("NicoCacheCA.jar="
                    + outputRoot.resolve("NicoCacheCA.jar"));
            System.out.println("NicoCacheLauncher.jar="
                    + outputRoot.resolve("NicoCacheLauncher.jar"));
            System.out.println("NicoCacheBuild.jar="
                    + outputRoot.resolve("NicoCacheBuild.jar"));
        }

        private void compile(List<Path> sources, Path destination,
                List<Path> classpath) throws IOException {
            if (sources.isEmpty()) {
                throw new IOException("Javaソースが見つかりません: " + destination);
            }
            Files.createDirectories(destination);
            List<String> arguments = new ArrayList<>();
            arguments.add("--release");
            arguments.add(RELEASE);
            arguments.add("-encoding");
            arguments.add("UTF-8");
            arguments.add("-Xlint:-options");
            arguments.add("-d");
            arguments.add(destination.toString());
            if (!classpath.isEmpty()) {
                arguments.add("-classpath");
                arguments.add(classpath.stream().map(Path::toString)
                        .collect(Collectors.joining(java.io.File.pathSeparator)));
            }
            try (StandardJavaFileManager fileManager =
                    compiler.getStandardFileManager(null, null, null)) {
                Iterable<? extends JavaFileObject> units =
                        fileManager.getJavaFileObjectsFromFiles(
                                sources.stream().map(Path::toFile)
                                        .collect(Collectors.toList()));
                Boolean success = compiler.getTask(null, fileManager, null,
                        arguments, null, units).call();
                if (!Boolean.TRUE.equals(success)) {
                    throw new IOException("Javaのコンパイルに失敗しました: "
                            + destination);
                }
            }
        }

        private static void requireLibraries(List<Path> libraries,
                String description) throws IOException {
            for (Path library : libraries) {
                if (!Files.isRegularFile(library)) {
                    throw new IOException(description + "がありません: " + library);
                }
            }
        }

        private void createJar(Path jarPath, Path classes, String mainClass,
                String classPath, Path extraDirectory) throws IOException {
            Files.createDirectories(jarPath.toAbsolutePath().getParent());
            Path temporary = jarPath.resolveSibling(jarPath.getFileName()
                    + ".tmp");
            Manifest manifest = new Manifest();
            Attributes attributes = manifest.getMainAttributes();
            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
            if (!classPath.isBlank()) {
                attributes.put(Attributes.Name.CLASS_PATH, classPath);
            }
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
                    JarOutputStream jar = new JarOutputStream(output, manifest)) {
                addDirectory(jar, classes, classes);
                if (extraDirectory != null && Files.isDirectory(extraDirectory)) {
                    addDirectory(jar, extraDirectory, extraDirectory.getParent());
                }
            }
            Files.move(temporary, jarPath, StandardCopyOption.REPLACE_EXISTING);
        }

        private void copyCoreResources(Path destination) throws IOException {
            Path sourceRoot = root.resolve("src/dareka");
            for (String name : List.of("GUILauncherIcon.gif",
                    "setup_messages.properties", "setup_messages_ja.properties")) {
                Path source = sourceRoot.resolve(name);
                if (Files.isRegularFile(source)) {
                    Path target = destination.resolve("dareka").resolve(name);
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        private static List<Path> javaSources(Path sourceRoot) throws IOException {
            if (!Files.isDirectory(sourceRoot)) {
                throw new IOException("Javaソースディレクトリがありません: " + sourceRoot);
            }
            try (Stream<Path> stream = Files.walk(sourceRoot)) {
                return stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString()
                                .equals("package-info.java"))
                        .sorted().collect(Collectors.toList());
            }
        }

        private static boolean isRunningBuildJar(Path candidate) {
            try {
                Path location = Path.of(BuildMain.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI())
                        .toAbsolutePath().normalize();
                return Files.isRegularFile(location)
                        && Files.isSameFile(location,
                                candidate.toAbsolutePath().normalize());
            } catch (Exception error) {
                return false;
            }
        }

        private static void copyDirectory(Path source, Path destination)
                throws IOException {
            if (!Files.isDirectory(source)) {
                return;
            }
            try (Stream<Path> stream = Files.walk(source)) {
                for (Path path : stream.collect(Collectors.toList())) {
                    Path relative = source.relativize(path);
                    Path target = destination.resolve(relative);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        private static void addDirectory(JarOutputStream jar, Path directory,
                Path relativeRoot) throws IOException {
            try (Stream<Path> stream = Files.walk(directory)) {
                List<Path> files = stream.filter(Files::isRegularFile)
                        .sorted().collect(Collectors.toList());
                for (Path file : files) {
                    Path relative = relativeRoot.relativize(file);
                    String entryName = relative.toString().replace(File.separatorChar,
                            '/');
                    if (entryName.startsWith("native/")) {
                        String name = file.getFileName().toString();
                        if (!(name.endsWith(".cpp") || name.endsWith(".sln")
                                || name.endsWith(".vcproj"))) {
                            continue;
                        }
                    }
                    JarEntry entry = new JarEntry(entryName);
                    jar.putNextEntry(entry);
                    try (InputStream input = Files.newInputStream(file)) {
                        input.transferTo(jar);
                    }
                    jar.closeEntry();
                }
            }
        }

        private static void deleteTree(Path path) throws IOException {
            if (!Files.exists(path)) {
                return;
            }
            try (Stream<Path> stream = Files.walk(path)) {
                List<Path> paths = stream.sorted(Comparator.reverseOrder())
                        .collect(Collectors.toList());
                for (Path child : paths) {
                    Files.deleteIfExists(child);
                }
            }
        }
    }

    private static final class BuildOptions {
        final Path root;
        final Path outputRoot;
        final Path libraryRoot;
        final boolean clean;

        private BuildOptions(Path root, Path outputRoot, Path libraryRoot,
                boolean clean) {
            this.root = root;
            this.outputRoot = outputRoot;
            this.libraryRoot = libraryRoot;
            this.clean = clean;
        }

        static BuildOptions parse(String[] args) {
            Path root = Path.of("").toAbsolutePath().normalize();
            Path output = null;
            Path library = null;
            boolean clean = false;
            for (String arg : args) {
                if (arg.startsWith("--root=")) {
                    root = Path.of(requiredValue(arg, "--root="))
                            .toAbsolutePath().normalize();
                } else if (arg.startsWith("--output-dir=")) {
                    output = Path.of(requiredValue(arg, "--output-dir="))
                            .toAbsolutePath().normalize();
                } else if (arg.startsWith("--library-dir=")) {
                    library = Path.of(requiredValue(arg, "--library-dir="))
                            .toAbsolutePath().normalize();
                } else if ("--clean".equals(arg)) {
                    clean = true;
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    printUsage();
                    System.exit(0);
                } else {
                    throw new IllegalArgumentException("不明なオプションです: " + arg);
                }
            }
            return new BuildOptions(root, output == null ? root : output,
                    library == null ? root.resolve("lib") : library, clean);
        }

        private static String requiredValue(String arg, String prefix) {
            String value = arg.substring(prefix.length()).trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(prefix + " の値がありません");
            }
            return value;
        }

        private static void printUsage() {
            System.out.println("NicoCacheBuild");
            System.out.println("Usage: java -jar NicoCacheBuild.jar"
                    + " [--root=<repository>] [--output-dir=<directory>]"
                    + " [--library-dir=<directory>] [--clean]");
        }
    }
}
