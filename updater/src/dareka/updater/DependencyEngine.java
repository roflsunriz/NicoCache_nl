package dareka.updater;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
    private static final Pattern JSON_DIGEST = Pattern.compile(
            "\\\"digest\\\"\\s*:\\s*\\\"sha256:([0-9a-fA-F]{64})\\\"");
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
    private static final Pattern SHA256 = Pattern.compile("(?i)^[0-9a-f]{64}$");
    private static final Pattern SHA512 = Pattern.compile("(?i)^[0-9a-f]{128}$");
    private static final int MAX_ARCHIVE_ENTRIES = 200_000;
    private static final long MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024;

    private final Path applicationRoot;
    private final Path stateRoot;

    DependencyEngine(Path applicationRoot) throws IOException {
        this.applicationRoot = applicationRoot.toAbsolutePath().normalize();
        Files.createDirectories(this.applicationRoot);
        this.stateRoot = managed(".runtime-dependency-updater");
        Files.createDirectories(stateRoot);
    }

    String checkAll(int javaMajor) throws Exception {
        List<Release> releases = resolveAll(javaMajor);
        StringBuilder output = new StringBuilder();
        output.append("対象: ").append(applicationRoot).append(System.lineSeparator());
        for (Release release : releases) {
            output.append(release.displayName).append(": ")
                    .append(release.version).append(" [取得可能・検証情報あり]")
                    .append(System.lineSeparator());
        }
        return output.toString();
    }

    String updateAll(int javaMajor) throws Exception {
        try (OperationLock ignored = acquireOperationLock()) {
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
    }

    /**
     * Packaged, offline transaction E2E. It exercises HTTP download, hash validation,
     * ZIP extraction, zip-slip rejection, backup, replacement and rollback invariants.
     */
    String selfTestTransactions() throws Exception {
        try (OperationLock ignored = acquireOperationLock()) {
            Path destination = managed("tools/selftest");
            deleteTree(destination);
            Files.createDirectories(destination);
            Files.writeString(destination.resolve("marker.txt"), "old", StandardCharsets.UTF_8);

            byte[] goodZip = zipBytes("payload/marker.txt", "new");
            byte[] evilZip = zipBytes("../escaped.txt", "escape");
            Map<String, byte[]> payloads = new HashMap<>();
            payloads.put("/good.zip", goodZip);
            payloads.put("/evil.zip", evilZip);
            try (FixtureServer server = new FixtureServer(payloads, 3)) {
                URI good = server.uri("/good.zip");
                Release successful = Release.archive("selftest-good", "Self Test", "1",
                        destination, good, "good.zip", digest(goodZip, "SHA-256"),
                        "SHA-256", ArchiveType.ZIP, false);
                install(successful);
                String marker = Files.readString(destination.resolve("marker.txt"), StandardCharsets.UTF_8);
                if (!"new".equals(marker)) throw new IOException("自己診断の置換結果が不正です");
                if (!hasBackupWithMarker("selftest-good", "old")) {
                    throw new IOException("自己診断でバックアップが作成されませんでした");
                }

                Release badHash = Release.archive("selftest-hash", "Self Test", "2",
                        destination, good, "good.zip", repeat('0', 64),
                        "SHA-256", ArchiveType.ZIP, false);
                expectFailure(() -> install(badHash), "ハッシュ不一致が受理されました");
                marker = Files.readString(destination.resolve("marker.txt"), StandardCharsets.UTF_8);
                if (!"new".equals(marker)) throw new IOException("ハッシュ失敗後に既存内容が破損しました");

                URI evil = server.uri("/evil.zip");
                Release zipSlip = Release.archive("selftest-zipslip", "Self Test", "3",
                        destination, evil, "evil.zip", digest(evilZip, "SHA-256"),
                        "SHA-256", ArchiveType.ZIP, false);
                Path escaped = stateRoot.resolve("escaped.txt");
                Files.deleteIfExists(escaped);
                expectFailure(() -> install(zipSlip), "ZIP traversalが受理されました");
                if (Files.exists(escaped)) throw new IOException("ZIP traversalで管理外へ書き込みました");
            } finally {
                deleteTree(destination);
            }
            return "TRANSACTION_E2E_OK hash zip-slip backup rollback";
        }
    }

    private List<Release> resolveAll(int javaMajor) throws Exception {
        List<Release> releases = new ArrayList<>();
        releases.add(resolveTemurin(javaMajor));
        releases.add(resolveFfmpeg());
        releases.add(resolveBouncyCastle());
        releases.add(resolveAnt());
        releases.add(resolveSevenZip());
        for (Release release : releases) {
            for (Artifact artifact : release.artifacts) {
                if (artifact.checksum == null || artifact.algorithm == null) {
                    throw new IOException(release.displayName + "に検証可能なハッシュがありません: "
                            + artifact.fileName);
                }
            }
        }
        return releases;
    }

    private Release resolveTemurin(int major) throws Exception {
        if (major != 17 && major != 21) throw new IOException("未検証のTemurin LTSです: " + major);
        URI uri = URI.create("https://api.adoptium.net/v3/assets/latest/" + major
                + "/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse");
        String json = text(uri, "application/json");
        Matcher semver = ADOPTIUM_SEMVER.matcher(json);
        Matcher pkg = ADOPTIUM_PACKAGE.matcher(json);
        if (!semver.find() || !pkg.find()) throw new IOException("Adoptium APIの応答を解釈できません");
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
        requireDigest(archive, "7-Zip archive");
        requireDigest(bootstrap, "7-Zip bootstrap");
        String version = archive.name.replaceAll("^7z([0-9]+)-extra\\.7z$", "$1");
        List<Artifact> artifacts = new ArrayList<>();
        artifacts.add(new Artifact(archive.url, archive.name, archive.digest, "SHA-256"));
        artifacts.add(new Artifact(bootstrap.url, bootstrap.name, bootstrap.digest, "SHA-256"));
        return new Release("7zip", "7-Zip", version, managed("tools/7zip"),
                artifacts, ArchiveType.SEVEN_ZIP, false);
    }

    private static void requireDigest(Asset asset, String label) throws IOException {
        if (asset.digest == null || !SHA256.matcher(asset.digest).matches()) {
            throw new IOException(label + "にGitHub SHA-256 digestがありません");
        }
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
                process.getInputStream().transferTo(OutputStream.nullOutputStream());
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
        if (artifact.checksum == null || artifact.algorithm == null) {
            throw new IOException("ハッシュ未検証の配布物を拒否しました: " + artifact.fileName);
        }
        Path destination = directory.resolve(artifact.fileName).normalize();
        assertInside(directory, destination);
        HttpRequest.Builder builder = HttpRequest.newBuilder(artifact.url).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "NicoCache_nl Updater");
        addGitHubToken(builder, artifact.url);
        HttpResponse<Path> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        verify(destination, artifact.checksum, artifact.algorithm);
        return destination;
    }

    private void transactionalReplace(Path staged, Path destination, String id) throws Exception {
        assertInside(applicationRoot, destination);
        assertNoReparseEscape(applicationRoot, destination);
        Path backups = stateRoot.resolve("backups");
        Files.createDirectories(backups);
        Path backup = backups.resolve(id + "-" + Instant.now().toEpochMilli());
        boolean hadExisting = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
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
                    try (OutputStream out = Files.newOutputStream(output)) {
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
        String actual = digest(Files.readAllBytes(file), algorithm);
        if (!MessageDigest.isEqual(expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("ハッシュが一致しません: " + file.getFileName());
        }
    }

    private static String digest(byte[] bytes, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm.replace("-", ""));
        digest.update(bytes);
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format("%02x", value & 0xff));
        return actual.toString();
    }

    private static String text(URI uri, String accept) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "NicoCache_nl Updater").header("Accept", accept);
        addGitHubToken(builder, uri);
        HttpResponse<String> response = HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + uri);
        return response.body();
    }

    private static void addGitHubToken(HttpRequest.Builder builder, URI uri) {
        if (!"api.github.com".equalsIgnoreCase(uri.getHost())) return;
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        builder.header("X-GitHub-Api-Version", "2022-11-28");
    }

    private static Asset findAsset(String json, Pattern namePattern) throws IOException {
        Matcher names = JSON_NAME.matcher(json);
        while (names.find()) {
            String name = unescape(names.group(1));
            if (!namePattern.matcher(name).matches()) continue;
            int urlStart = json.lastIndexOf("\"browser_download_url\"", names.start());
            if (urlStart < 0) continue;
            int nextUrl = json.indexOf("\"browser_download_url\"", urlStart + 1);
            int end = nextUrl < 0 ? Math.min(json.length(), names.end() + 3000) : nextUrl;
            String segment = json.substring(urlStart, end);
            Matcher url = JSON_URL.matcher(segment);
            if (!url.find()) continue;
            Matcher digest = JSON_DIGEST.matcher(segment);
            String hash = digest.find() ? digest.group(1).toLowerCase(Locale.ROOT) : null;
            return new Asset(name, URI.create(unescape(url.group(1))), hash);
        }
        throw new IOException("Release assetが見つかりません: " + namePattern);
    }

    private Path managed(String relative) throws IOException {
        Path path = applicationRoot.resolve(relative).normalize();
        assertInside(applicationRoot, path);
        assertNoReparseEscape(applicationRoot, path);
        return path;
    }

    private static void assertInside(Path root, Path candidate) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot) || normalized.equals(normalizedRoot)) {
            throw new IOException("管理対象外のパスです: " + normalized);
        }
    }

    private static void assertNoReparseEscape(Path root, Path candidate) throws IOException {
        Path realRoot = root.toRealPath();
        Path current = candidate;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        if (current != null) {
            Path real = current.toRealPath();
            if (!real.startsWith(realRoot)) throw new IOException("リンク経由の管理外パスです: " + real);
        }
        Path relative = root.toAbsolutePath().normalize().relativize(candidate.toAbsolutePath().normalize());
        Path walking = root.toAbsolutePath().normalize();
        for (Path part : relative) {
            walking = walking.resolve(part);
            if (Files.isSymbolicLink(walking)) throw new IOException("シンボリックリンクを拒否しました: " + walking);
        }
    }

    private OperationLock acquireOperationLock() throws IOException {
        FileChannel channel = FileChannel.open(stateRoot.resolve("engine.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            throw new IOException("別の依存関係更新処理が実行中です");
        }
        return new OperationLock(channel, lock);
    }

    private boolean hasBackupWithMarker(String id, String expected) throws IOException {
        Path backups = stateRoot.resolve("backups");
        if (!Files.isDirectory(backups)) return false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backups, id + "-*")) {
            for (Path backup : stream) {
                Path marker = backup.resolve("marker.txt");
                if (Files.isRegularFile(marker)
                        && expected.equals(Files.readString(marker, StandardCharsets.UTF_8))) return true;
            }
        }
        return false;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(root)) {
            Files.deleteIfExists(root);
            return;
        }
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

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }

    private static byte[] zipBytes(String name, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void expectFailure(CheckedAction action, String message) throws Exception {
        boolean failed = false;
        try {
            action.run();
        } catch (Exception expected) {
            failed = true;
        }
        if (!failed) throw new IOException(message);
    }

    private interface CheckedAction { void run() throws Exception; }

    private enum ArchiveType { ZIP, SEVEN_ZIP, FILES }

    private static final class Asset {
        final String name;
        final URI url;
        final String digest;
        Asset(String name, URI url, String digest) {
            this.name = name;
            this.url = url;
            this.digest = digest;
        }
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

    private static final class OperationLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        OperationLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }
        @Override
        public void close() throws IOException {
            lock.release();
            channel.close();
        }
    }

    private static final class FixtureServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final Map<String, byte[]> payloads;
        private final int expectedRequests;
        private volatile IOException failure;

        FixtureServer(Map<String, byte[]> payloads, int expectedRequests) throws IOException {
            this.payloads = payloads;
            this.expectedRequests = expectedRequests;
            this.server = new ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::serve, "dependency-engine-fixture");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.getLocalPort() + path);
        }

        private void serve() {
            try {
                for (int i = 0; i < expectedRequests; i++) {
                    try (Socket socket = server.accept()) {
                        InputStream input = socket.getInputStream();
                        ByteArrayOutputStream header = new ByteArrayOutputStream();
                        int previous = -1;
                        int current;
                        int matched = 0;
                        while ((current = input.read()) >= 0) {
                            header.write(current);
                            if ((previous == '\r' && current == '\n') || current == '\n') matched++;
                            else if (current != '\r') matched = 0;
                            previous = current;
                            if (matched >= 2) break;
                        }
                        String request = header.toString(StandardCharsets.US_ASCII);
                        String first = request.split("\\r?\\n", 2)[0];
                        String path = first.split(" ")[1];
                        byte[] body = payloads.get(path);
                        if (body == null) body = new byte[0];
                        OutputStream output = socket.getOutputStream();
                        String response = "HTTP/1.1 " + (body.length == 0 ? "404 Not Found" : "200 OK")
                                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                        output.write(response.getBytes(StandardCharsets.US_ASCII));
                        output.write(body);
                        output.flush();
                    }
                }
            } catch (IOException error) {
                if (!server.isClosed()) failure = error;
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(5000);
            if (failure != null) throw failure;
        }
    }
}