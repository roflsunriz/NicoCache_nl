package dareka.updater;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarFile;

/** Coordinates user-wide command-line tools and the NicoCache_nl-local Bouncy Castle libraries. */
final class DependencyEngine {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Pattern MAVEN_VERSION = Pattern.compile("<version>([^<]+)</version>");
    private static final Pattern BOUNCY_EXPORT_VERSION = Pattern.compile(
            "(?i)\\borg\\.bouncycastle\\s*;\\s*version\\s*=\\s*\\\"?"
                    + "([0-9]+(?:\\.[0-9]+){1,3})");
    private static final Pattern SHA256 = Pattern.compile("(?i)^[0-9a-f]{64}$");

    private final Path applicationRoot;
    private final DependencyProvider platformDependencies;

    DependencyEngine(Path applicationRoot) throws IOException {
        this.applicationRoot = applicationRoot.toAbsolutePath().normalize();
        this.platformDependencies = DependencyProvider.forPlatform(UpdaterPlatform.current());
    }

    String checkAll(int javaMajor) throws Exception {
        StringBuilder output = new StringBuilder();
        if (UpdaterPlatform.current() == UpdaterPlatform.Kind.WINDOWS) {
            output.append("WinGet/公式配布API: Windows依存関係の導入方式\n");
        }
        for (DependencyStatus status : inspectAll(javaMajor)) {
            output.append(formatStatus(status)).append(System.lineSeparator());
        }
        return output.toString();
    }

    String updateAll(int javaMajor) throws Exception {
        StringBuilder output = new StringBuilder();
        boolean installed = false;
        for (DependencyStatus status : inspectAll(javaMajor)) {
            if (!status.canInstall()) continue;
            installed = true;
            output.append(installCheckedDependency(status.id, javaMajor));
        }
        if (!installed) output.append("新しい外部依存関係はありません。\n");
        return output.toString();
    }

    List<DependencyStatus> inspectAll(int javaMajor) throws Exception {
        List<DependencyStatus> statuses = new ArrayList<DependencyStatus>(
                platformDependencies.inspectAll(javaMajor));
        String installed = installedBouncyCastleVersion();
        try {
            statuses.add(checkBouncyCastleStatus(installed));
        } catch (Exception error) {
            statuses.add(bouncyCastleFailureStatus(installed,
                    error.getMessage() == null ? error.toString() : error.getMessage()));
        }
        return statuses;
    }

    DependencyStatus inspectDependency(String dependencyId, int javaMajor)
            throws Exception {
        for (DependencyStatus status : inspectAll(javaMajor)) {
            if (status.id.equals(dependencyId)) return status;
        }
        throw new IOException("未対応の外部依存関係です: " + dependencyId);
    }

    String installDependency(String dependencyId, int javaMajor) throws Exception {
        DependencyStatus status = inspectDependency(dependencyId, javaMajor);
        if (!status.canInstall()) {
            throw new IOException(status.displayName
                    + "にインストール可能な新バージョンがありません");
        }
        return installCheckedDependency(dependencyId, javaMajor);
    }

    private String installCheckedDependency(String dependencyId, int javaMajor)
            throws Exception {
        if ("bouncycastle".equals(dependencyId)) {
            String result = updateBouncyCastle();
            if (UpdaterPlatform.current() == UpdaterPlatform.Kind.WINDOWS) {
                UserToolAliasRepair.repair();
            }
            return result;
        }
        String result = platformDependencies.install(dependencyId, javaMajor);
        if (UpdaterPlatform.current() == UpdaterPlatform.Kind.WINDOWS) {
            UserToolAliasRepair.repair();
        }
        return result;
    }

    String selfTestTransactions() throws Exception {
        return platformDependencies.selfTest();
    }

    private DependencyStatus checkBouncyCastleStatus(String installed) throws Exception {
        BouncyRelease release = resolveBouncyCastle();
        boolean update = installed == null || compareVersions(installed, release.version) < 0;
        return new DependencyStatus("bouncycastle", "Bouncy Castle", installed,
                release.version, "NicoCache_nl専用、SHA-256検証情報あり"
                        + (update ? "（更新あり）" : "（最新）"), true, update,
                TargetRootResolver.isInstallation(applicationRoot));
    }

    static DependencyStatus bouncyCastleFailureStatus(String installed, String message) {
        return new DependencyStatus("bouncycastle", "Bouncy Castle", installed,
                null, message, true, false, false);
    }

    private String installedBouncyCastleVersion() {
        Path jar = bouncyCastleLibraryDirectory(applicationRoot)
                .resolve("bcprov.jar").normalize();
        if (!jar.startsWith(applicationRoot) || !Files.isRegularFile(jar)) return null;
        return readBouncyCastleVersion(jar);
    }

    static Path bouncyCastleLibraryDirectory(Path applicationRoot) {
        Path normalizedRoot = applicationRoot.toAbsolutePath().normalize();
        Path applicationDirectory = UpdaterPlatform.applicationDirectory(normalizedRoot);
        Path[] candidates = {
            applicationDirectory.resolve("lib").normalize(),
            normalizedRoot.resolve("lib").normalize()
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("bcprov.jar"))) return candidate;
        }
        return Files.isDirectory(applicationDirectory) ? candidates[0] : candidates[1];
    }

    static String readBouncyCastleVersion(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            if (file.getManifest() == null
                    || file.getManifest().getMainAttributes() == null) return null;
            java.util.jar.Attributes attributes = file.getManifest().getMainAttributes();
            for (String key : new String[] {
                    "Implementation-Version", "Bundle-Version", "Specification-Version" }) {
                String version = attributes.getValue(key);
                if (version != null && !version.isBlank()) return version.trim();
            }
            String exportPackages = attributes.getValue("Export-Package");
            if (exportPackages != null) {
                Matcher matcher = BOUNCY_EXPORT_VERSION.matcher(exportPackages);
                if (matcher.find()) return matcher.group(1);
            }
            return null;
        } catch (IOException error) {
            return null;
        }
    }

    private String updateBouncyCastle() throws Exception {
        TargetRootResolver.requireInstallation(applicationRoot);
        BouncyRelease release = resolveBouncyCastle();
        Path lib = bouncyCastleLibraryDirectory(applicationRoot).normalize();
        if (!lib.startsWith(applicationRoot)) throw new IOException("Bouncy Castleの導入先が不正です");
        Files.createDirectories(lib);
        Path staging = Files.createTempDirectory(applicationRoot, ".bouncycastle-update-");
        Path backup = applicationRoot.resolve(".runtime-dependency-updater").resolve("backups")
                .resolve("bouncycastle-" + System.currentTimeMillis());
        Files.createDirectories(backup);
        List<Path> installed = new ArrayList<Path>();
        List<Path> replaced = new ArrayList<Path>();
        try {
            for (Artifact artifact : release.artifacts) {
                Path downloaded = download(artifact, staging);
                Path target = lib.resolve(artifact.fileName).normalize();
                if (!target.startsWith(lib)) throw new IOException("Bouncy Castleのファイル名が不正です");
                if (Files.exists(target)) {
                    Files.move(target, backup.resolve(target.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    replaced.add(target);
                }
                Files.move(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
                installed.add(target);
            }
        } catch (Exception error) {
            for (Path target : installed) Files.deleteIfExists(target);
            for (Path target : replaced) {
                Path saved = backup.resolve(target.getFileName());
                if (Files.exists(saved)) Files.move(saved, target, StandardCopyOption.REPLACE_EXISTING);
            }
            throw error;
        } finally {
            deleteTree(staging);
        }
        return "Bouncy Castle: " + release.version + " に更新しました（NicoCache_nl専用）"
                + System.lineSeparator();
    }

    private BouncyRelease resolveBouncyCastle() throws Exception {
        String base = "https://repo.maven.apache.org/maven2/org/bouncycastle/";
        String[][] names = {
                {"bcprov-jdk18on", "bcprov.jar"},
                {"bcpkix-jdk18on", "bcpkix.jar"},
                {"bcutil-jdk18on", "bcutil.jar"}
        };
        List<String> metadata = new ArrayList<String>();
        for (String[] name : names) {
            metadata.add(text(URI.create(base + name[0] + "/maven-metadata.xml"),
                    "application/xml"));
        }
        String version = latestCommonBouncyCastleVersion(metadata);
        List<Artifact> artifacts = new ArrayList<Artifact>();
        for (String[] name : names) {
            String url = base + name[0] + "/" + version + "/" + name[0] + "-" + version + ".jar";
            String checksum = text(URI.create(url + ".sha256"), "text/plain").trim().split("\\s+")[0];
            if (!SHA256.matcher(checksum).matches()) throw new IOException("Bouncy Castle SHA-256が不正です");
            artifacts.add(new Artifact(URI.create(url), name[1], checksum));
        }
        return new BouncyRelease(version, artifacts);
    }

    static String latestCommonBouncyCastleVersion(List<String> metadataDocuments)
            throws IOException {
        if (metadataDocuments == null || metadataDocuments.isEmpty()) {
            throw new IOException("Bouncy CastleのMavenメタデータがありません");
        }
        Set<String> common = null;
        for (String metadata : metadataDocuments) {
            Set<String> versions = new LinkedHashSet<String>();
            Matcher matcher = MAVEN_VERSION.matcher(metadata == null ? "" : metadata);
            while (matcher.find()) {
                String version = matcher.group(1).trim();
                if (version.matches("\\d+(?:\\.\\d+){1,3}")) versions.add(version);
            }
            if (versions.isEmpty()) {
                throw new IOException("Bouncy CastleのMavenメタデータに版番号がありません");
            }
            if (common == null) {
                common = new LinkedHashSet<String>(versions);
            } else {
                common.retainAll(versions);
            }
        }
        if (common == null || common.isEmpty()) {
            throw new IOException("Bouncy Castle 3成果物の共通公開版がありません");
        }
        String latest = null;
        for (String version : common) {
            if (latest == null || compareVersions(version, latest) > 0) latest = version;
        }
        return latest;
    }

    private static Path download(Artifact artifact, Path directory) throws Exception {
        Path destination = directory.resolve(artifact.fileName).normalize();
        HttpRequest request = HttpRequest.newBuilder(artifact.url).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "NicoCache_nl Updater").build();
        HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        verify(destination, artifact.sha256);
        return destination;
    }

    private static String text(URI uri, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "NicoCache_nl Updater").header("Accept", accept).build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + uri);
        return response.body();
    }

    private static void verify(Path file, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format("%02x", value & 0xff));
        if (!MessageDigest.isEqual(expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                actual.toString().getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("ハッシュが一致しません: " + file.getFileName());
        }
    }

    private static String formatStatus(DependencyStatus status) {
        return status.displayName + ": 導入版=" + status.installedLabel()
                + ", 最新版=" + status.latestLabel() + " " + status.message
                + (status.canInstall() ? " [インストール可能]" : " [インストール不可]");
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.replace('-', '.').split("\\.");
        String[] b = right.replace('-', '.').split("\\.");
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            int leftValue = index < a.length ? numericPart(a[index]) : 0;
            int rightValue = index < b.length ? numericPart(b[index]) : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private static int numericPart(String value) {
        Matcher matcher = Pattern.compile("^\\d+").matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class Artifact {
        final URI url;
        final String fileName;
        final String sha256;
        Artifact(URI url, String fileName, String sha256) {
            this.url = url;
            this.fileName = fileName;
            this.sha256 = sha256;
        }
    }

    private static final class BouncyRelease {
        final String version;
        final List<Artifact> artifacts;
        BouncyRelease(String version, List<Artifact> artifacts) {
            this.version = version;
            this.artifacts = artifacts;
        }
    }
}
