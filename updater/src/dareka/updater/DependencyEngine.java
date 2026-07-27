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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Coordinates user-wide command-line tools and the NicoCache_nl-local Bouncy Castle libraries. */
final class DependencyEngine {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Pattern MAVEN_RELEASE = Pattern.compile("<release>([^<]+)</release>");
    private static final Pattern SHA256 = Pattern.compile("(?i)^[0-9a-f]{64}$");

    private final Path applicationRoot;
    private final SystemDependencyManager systemDependencies;

    DependencyEngine(Path applicationRoot) throws IOException {
        this.applicationRoot = applicationRoot.toAbsolutePath().normalize();
        this.systemDependencies = new SystemDependencyManager();
    }

    String checkAll(int javaMajor) throws Exception {
        StringBuilder output = new StringBuilder(systemDependencies.checkAll(javaMajor));
        output.append(checkBouncyCastle());
        return output.toString();
    }

    String updateAll(int javaMajor) throws Exception {
        StringBuilder output = new StringBuilder(systemDependencies.updateAll(javaMajor));
        output.append(updateBouncyCastle());
        return output.toString();
    }

    String selfTestTransactions() throws Exception {
        return systemDependencies.selfTest();
    }

    private String checkBouncyCastle() throws Exception {
        BouncyRelease release = resolveBouncyCastle();
        return "Bouncy Castle: " + release.version
                + " [NicoCache_nl専用、SHA-256検証情報あり]" + System.lineSeparator();
    }

    private String updateBouncyCastle() throws Exception {
        TargetRootResolver.requireInstallation(applicationRoot);
        BouncyRelease release = resolveBouncyCastle();
        Path lib = applicationRoot.resolve("lib").normalize();
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
        String metadata = text(URI.create(base + "bcprov-jdk18on/maven-metadata.xml"), "application/xml");
        Matcher release = MAVEN_RELEASE.matcher(metadata);
        if (!release.find()) throw new IOException("Bouncy Castleの最新版を取得できません");
        String version = release.group(1);
        List<Artifact> artifacts = new ArrayList<Artifact>();
        String[][] names = {
                {"bcprov-jdk18on", "bcprov.jar"},
                {"bcpkix-jdk18on", "bcpkix.jar"},
                {"bcutil-jdk18on", "bcutil.jar"}
        };
        for (String[] name : names) {
            String url = base + name[0] + "/" + version + "/" + name[0] + "-" + version + ".jar";
            String checksum = text(URI.create(url + ".sha256"), "text/plain").trim().split("\\s+")[0];
            if (!SHA256.matcher(checksum).matches()) throw new IOException("Bouncy Castle SHA-256が不正です");
            artifacts.add(new Artifact(URI.create(url), name[1], checksum));
        }
        return new BouncyRelease(version, artifacts);
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
