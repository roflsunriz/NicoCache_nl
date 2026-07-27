package dareka.updater;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Pure-Java dependency resolver, verifier and transactional installer. */
final class DependencyEngine {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Pattern JSON_URL = Pattern.compile(
            "\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern JSON_NAME = Pattern.compile(
            "\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern JSON_PUBLISHED = Pattern.compile(
            "\\\"published_at\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ADOPTIUM_SEMVER = Pattern.compile(
            "\\\"semver\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ADOPTIUM_PACKAGE = Pattern.compile(
            "\\\"package\\\"\\s*:\\s*\\{.*?\\\"checksum\\\"\\s*:\\s*\\\"([0-9a-fA-F]{64})\\\".*?"
                    + "\\\"link\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
            Pattern.DOTALL);
    private static final Pattern MAVEN_RELEASE = Pattern.compile("<release>([^<]+)</release>");
    private static final Pattern ANT_ZIP = Pattern.compile("apache-ant-(\\d+\\.\\d+\\.\\d+)-bin\\.zip");
    private static final Pattern SHA256 = Pattern.compile("(?i)\\b([0-9a-f]{64})\\b");
    private static final Pattern SHA512 = Pattern.compile("(?i)\\b([0-9a-f]{128})\\b");
    private static final int MAX_ARCHIVE_ENTRIES = 200_000;
    private static final long MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024;

    private final Path applicationRoot;
    private final Path stateRoot;

    DependencyEngine(Path applicationRoot) throws IOException {
        this.applicationRoot = applicationRoot.toAbsolutePath().normalize();
        this.stateRoot = managed(".runtime-dependency-updater");
        Files.createDirectories(stateRoot);
    }

    String checkAll(int javaMajor) throws Exception {
        List<Release> releases = resolveAll(javaMajor);
        StringBuilder output = new StringBuilder();
        output.append("対象: ").append(applicationRoot).append(System.lineSeparator());
        for (Release release : releases) {
            output.append(release.displayName).append(": ")
                    .append(release.version).append(" [取得可能]")
                    .append(System.lineSeparator());
        }
        return output.toString();
    }

    String updateAll(int javaMajor) throws Exception {
        List<Release> releases = resolveAll(javaMajor);
        StringBuilder output = new StringBuilder();
        for (Release release : releases) {
            install(release);
            output.append(release.displayName).append(": ")
                    .append(release.version).append(" に更新しました")
                    .append(System.lineSeparator());
        }
        return output.toString();
    }

    private List<Release> resolveAll(int javaMajor) throws Exception {
        List<Release> releases = new ArrayList<>();
        releases.add(resolveTemurin(javaMajor));
        releases.add(resolveFfmpeg());
        releases.add(resolveBouncyCastle());
        releases.add(resolveAnt());
        releases.add(resolveSevenZip());
        return releases;
    }

    private Release resolveTemurin(int major) throws Exception {
        if (major != 17 && major != 21) {
            throw new IOException("未検証のTemurin LTSです: " + major);
        }
        URI uri = URI.create("https://api.adoptium.net/v3/assets/latest/" + major
                + "/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse");
        String json = text(uri, "application/json");
        Matcher semver = ADOPTIUM_SEMVER.matcher(json);
        Matcher pkg = ADOPTIUM_PACKAGE.matcher(json);
        if (!semver.find() || !pkg.find()) {
            throw new IOException("Adoptium APIの応答を解釈できません");
        }
        return Release.archive("temurin", "Eclipse Temurin OpenJDK", semver.group(1),
                managed("runtime"), URI.create(unescape(pkg.group(2))), pkg.group(3),
                pkg.group(1), "SHA-256", ArchiveType.ZIP, true);
    }

    private Release resolveFfmpeg() throws Exception {
        String json = text(URI.create(
                "https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/tags/latest"),
                "application/vnd.github+json");
        Asset zip = findAsset(json, Pattern.compile("^ffmpeg-master-latest-win64-gpl\\.zip$"));
        Asset checksums = findAsset(json, Pattern.compile("^checksums\\.sha256$"));
        String checksumText = text(checksums.url, "text/plain");
        Pattern line = Pattern.compile("(?m)^([0-9a-fA-F]{64})\\s+\\*?"
                + Pattern.quote(zip.name) + "\\s*$");
        Matcher match = line.matcher(checksumText);
        if (!match.find()) throw new IOException("FFmpegのSHA-256が見つかりません");
        Matcher published = JSON_PUBLISHED.matcher(json);
        String version = published.find() ? published.group(1) : "latest";
        return Release.archive("ffmpeg", "FFmpeg", version, managed("tools/ffmpeg"),
                zip.url, zip.name, match.group(1), "SHA-256", ArchiveType.ZIP, false);
    }

    private Release resolveBouncyCastle() throws Exception {
        String base = "https://repo.maven.apache.org/maven2/org/bouncycastle/";
        String metadata = text(URI.create(base + "bcprov-jdk18on/maven-metadata.xml"), "application/xml");
        Matcher release = MAVEN_RELEASE.matcher(metadata);
        if (!release.find()) throw new IOException("Bouncy Castleの最新版を取得できません");
        String version = release.group(1);
        List<Artifact> artifacts = new ArrayList<>();
        String[][] names = {
                {"bcprov-jdk18on", "bcprov.jar"},
                {"bcpkix-jdk18on", "bcpkix.jar"},
                {"bcutil-jdk18on", "bcutil.jar"}
        };
        for (String[] name : names) {
            String url = base + name[0] + "/" + version + "/" + name[0] + "-" + version + ".jar";
            String checksum = text(URI.create(url + ".sha256"), "text/plain").trim().split("\\s+")[0];
            if (!SHA256.matcher(checksum).matches()) throw new IOException("Bouncy Castle SHA-256不正");
            artifacts.add(new Artifact(URI.create(url), name[1], checksum, "SHA-256"));
        }
        return Release.files("bouncycastle", "Bouncy Castle", version, managed("lib"), artifacts);
    }

    private Release resolveAnt() throws Exception {
        URI base = URI.create("https://downloads.apache.org/ant/binaries/");
        String html = text(base, "text/html");
        Matcher matcher = ANT_ZIP.matcher(html);
        String best = null;
        String file = null;
        while (matcher.find()) {
            if (best == null || compareVersions(matcher.group(1), best) > 0) {
                best = matcher.group(1);
                file = matcher.group(0);
            }
        }
        if (file == null) throw new IOException("Apache Ant ZIPを検出できません");
        URI url = base.resolve(file);
        String checksum = text(URI.create(url + ".sha512"), "text/plain").trim().split("\\s+")[0];
        if (!SHA512.matcher(checksum).matches()) throw new IOException("Apache Ant SHA-512不正");
        return Release.archive("ant", "Apache Ant", best, managed("tools/ant"), url, file,
                checksum, "SHA-512", ArchiveType.ZIP, false);
    }

    private Release resolveSevenZip() throws Exception {
        String json = text(URI.create("https://api.github.com/repos/ip7z/7zip/releases/latest"),
                "application/vnd.github+json");
        Asset archive = findAsset(json, Pattern.compile("^7z[0-9]+-extra\\.7z$"));
        Asset bootstrap = findAsset(json, Pattern.compile("^7zr\\.exe$"));
        String version = archive.name.replaceAll("^7z([0-9]+)-extra\\.7z$", "$1");
        List<Artifact> artifacts = new ArrayList<>();
        artifacts.add(new Artifact(archive.url, archive.name, null, null));
        artifacts.add(new Artifact(bootstrap.url, bootstrap.name, null, null));
        return new Release("7zip", "7-Zip", version, managed("tools/7zip"),
                artifacts, ArchiveType.SEVEN_ZIP, false);
    }

    private void install(Release release) throws Exception {
        Path operation = stateRoot.resolve("operation-" + release.id + "-" + Instant.now().toEpochMilli());
        Path downloads = operation.resolve("downloads");
        Path staging = operation.resolve("staging");
        Files.createDirectories(downloads);
        Files.createDirectories(staging);
        try {
            if (release.archiveType == ArchiveType.FILES) {
                for (Artifact artifact : release.artifacts) {
                    Path downloaded = download(artifact, downloads);
                    Files.copy(downloaded, staging.resolve(artifact.fileName),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } else if (release.archiveType == ArchiveType.ZIP) {
                Artifact artifact = release.artifacts.get(0);
                Path downloaded = download(artifact, downloads);
                unzip(downloaded, staging);
                flattenSingleDirectory(staging);
            } else {
                Artifact archive = release.artifacts.get(0);
                Artifact bootstrap = release.artifacts.get(1);
                Path archivePath = download(archive, downloads);
                Path bootstrapPath = download(bootstrap, downloads);
                Process process = new ProcessBuilder(bootstrapPath.toString(), "x", "-y",
                        "-o" + staging, archivePath.toString()).redirectErrorStream(true).start();
                process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                if (process.waitFor() != 0) throw new IOException("7-Zip展開に失敗しました");
                flattenSingleDirectory(staging);
            }
            transactionalReplace(staging, release.destination, release.id);
        } catch (Exception error) {
            deleteTree(operation);
            throw error;
        }
        deleteTree(operation);
    }

    private Path download(Artifact artifact, Path directory) throws Exception {
        Path destination = directory.resolve(artifact.fileName).normalize();
        assertInside(directory, destination);
        HttpRequest request = HttpRequest.newBuilder(artifact.url).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "NicoCache_nl Updater").build();
        HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        if (artifact.checksum != null) verify(destination, artifact.checksum, artifact.algorithm);
        return destination;
    }

    private void transactionalReplace(Path staged, Path destination, String id) throws Exception {
        assertInside(applicationRoot, destination);
        Path backups = stateRoot.resolve("backups");
        Files.createDirectories(backups);
        Path backup = backups.resolve(id + "-" + Instant.now().toEpochMilli());
        boolean hadExisting = Files.exists(destination);
        if (hadExisting) Files.move(destination, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.createDirectories(destination.getParent());
            Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) {
            deleteTree(destination);
            if (hadExisting && Files.exists(backup)) {
                Files.move(backup, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            throw error;
        }
    }

    private static void unzip(Path archive, Path destination) throws Exception {
        int entries = 0;
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES) throw new IOException("ZIPエントリ数上限超過");
                Path output = destination.resolve(entry.getName()).normalize();
                assertInside(destination, output);
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    try (java.io.OutputStream out = Files.newOutputStream(output)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            expanded += read;
                            if (expanded > MAX_EXPANDED_BYTES) throw new IOException("ZIP展開サイズ上限超過");
                            out.write(buffer, 0, read);
                        }
                    }
                }
            }
        }
    }

    private static void flattenSingleDirectory(Path root) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            List<Path> children = new ArrayList<>();
            for (Path child : stream) children.add(child);
            if (children.size() != 1 || !Files.isDirectory(children.get(0))) return;
            Path only = children.get(0);
            try (DirectoryStream<Path> nested = Files.newDirectoryStream(only)) {
                for (Path child : nested) {
                    Files.move(child, root.resolve(child.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.delete(only);
        }
    }

    private static void verify(Path file, String expected, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm.replace("-", ""));
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
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

    private static String text(URI uri, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "NicoCache_nl Updater").header("Accept", accept).build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + uri);
        return response.body();
    }

    private static Asset findAsset(String json, Pattern namePattern) throws IOException {
        Matcher names = JSON_NAME.matcher(json);
        Matcher urls = JSON_URL.matcher(json);
        List<String> nameList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        while (names.find()) nameList.add(unescape(names.group(1)));
        while (urls.find()) urlList.add(unescape(urls.group(1)));
        for (String name : nameList) {
            if (!namePattern.matcher(name).matches()) continue;
            for (String url : urlList) {
                if (url.endsWith("/" + name)) return new Asset(name, URI.create(url));
            }
        }
        throw new IOException("Release assetが見つかりません: " + namePattern);
    }

    private Path managed(String relative) throws IOException {
        Path path = applicationRoot.resolve(relative).normalize();
        assertInside(applicationRoot, path);
        return path;
    }

    private static void assertInside(Path root, Path candidate) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot) || normalized.equals(normalizedRoot)) {
            throw new IOException("管理対象外のパスです: " + normalized);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? Integer.parseInt(a[i]) : 0;
            int bv = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static String unescape(String value) {
        return value.replace("\\/", "/").replace("\\u0026", "&");
    }

    private enum ArchiveType { ZIP, SEVEN_ZIP, FILES }

    private static final class Asset {
        final String name;
        final URI url;
        Asset(String name, URI url) { this.name = name; this.url = url; }
    }

    private static final class Artifact {
        final URI url;
        final String fileName;
        final String checksum;
        final String algorithm;
        Artifact(URI url, String fileName, String checksum, String algorithm) {
            this.url = url;
            this.fileName = fileName;
            this.checksum = checksum;
            this.algorithm = algorithm;
        }
    }

    private static final class Release {
        final String id;
        final String displayName;
        final String version;
        final Path destination;
        final List<Artifact> artifacts;
        final ArchiveType archiveType;
        final boolean requiresClosedApplication;

        Release(String id, String displayName, String version, Path destination,
                List<Artifact> artifacts, ArchiveType archiveType, boolean requiresClosedApplication) {
            this.id = id;
            this.displayName = displayName;
            this.version = version;
            this.destination = destination;
            this.artifacts = artifacts;
            this.archiveType = archiveType;
            this.requiresClosedApplication = requiresClosedApplication;
        }

        static Release archive(String id, String displayName, String version, Path destination,
                URI url, String fileName, String checksum, String algorithm,
                ArchiveType type, boolean requiresClosedApplication) {
            List<Artifact> artifacts = new ArrayList<>();
            artifacts.add(new Artifact(url, fileName, checksum, algorithm));
            return new Release(id, displayName, version, destination, artifacts, type,
                    requiresClosedApplication);
        }

        static Release files(String id, String displayName, String version, Path destination,
                List<Artifact> artifacts) {
            return new Release(id, displayName, version, destination, artifacts,
                    ArchiveType.FILES, false);
        }
    }
}
