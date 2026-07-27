package dareka;

import java.io.IOException;
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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import dareka.common.Logger;

/**
 * Packaged Windows launcher that checks GitHub Releases without blocking startup.
 */
public final class UpdateAwareMain {
    private static final URI LATEST_RELEASE_URI = URI.create(
            "https://api.github.com/repos/roflsunriz/NicoCache_nl/releases/latest");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern TAG_PATTERN = Pattern.compile(
            "\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern DOWNLOAD_PATTERN = Pattern.compile(
            "\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^v?(\\d+(?:\\.\\d+){1,3})$");
    private static final Pattern SHA256_PATTERN = Pattern.compile(
            "(?i)\\b([0-9a-f]{64})\\b");

    private UpdateAwareMain() {
    }

    public static void main(String[] args) {
        if (shouldCheck(args)) {
            Thread checker = new Thread(UpdateAwareMain::checkForUpdate,
                    "NicoCache_nl update checker");
            checker.setDaemon(true);
            checker.start();
        }
        NLMain.main(args);
    }

    static boolean shouldCheck(String[] args) {
        if (System.getProperty("jpackage.app-path") == null) {
            return false;
        }
        if (Boolean.getBoolean("nicocache.update.disabled")) {
            return false;
        }
        for (String argument : args) {
            if ("--headless".equals(argument) || "--setup".equals(argument)) {
                return false;
            }
        }
        return true;
    }

    private static void checkForUpdate() {
        try {
            Thread.sleep(Long.getLong("nicocache.update.delayMillis", 5000L));
            String currentVersion = System.getProperty("jpackage.app-version", "0.0.0");
            Release release = fetchLatestRelease(LATEST_RELEASE_URI);
            if (compareVersions(release.version, currentVersion) <= 0) {
                return;
            }
            SwingUtilities.invokeLater(() -> confirmAndInstall(currentVersion, release));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            Logger.warning("最新版の確認に失敗しました: " + error.getMessage());
        }
    }

    static Release fetchLatestRelease(URI endpoint)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NicoCache_nl updater")
                .build();
        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API returned HTTP " + response.statusCode());
        }
        return parseRelease(response.body());
    }

    static Release parseRelease(String json) throws IOException {
        Matcher tagMatcher = TAG_PATTERN.matcher(json);
        if (!tagMatcher.find()) {
            throw new IOException("release tag is missing");
        }
        String tag = unescapeJson(tagMatcher.group(1));
        Matcher versionMatcher = VERSION_PATTERN.matcher(tag);
        if (!versionMatcher.matches()) {
            throw new IOException("unsupported release tag: " + tag);
        }

        String msiUrl = null;
        String checksumUrl = null;
        Matcher downloadMatcher = DOWNLOAD_PATTERN.matcher(json);
        while (downloadMatcher.find()) {
            String url = unescapeJson(downloadMatcher.group(1));
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".msi")) {
                msiUrl = url;
            } else if (lower.endsWith(".sha256") || lower.endsWith(".sha256.txt")) {
                checksumUrl = url;
            }
        }
        if (msiUrl == null || checksumUrl == null) {
            throw new IOException("MSI or SHA-256 asset is missing");
        }
        return new Release(versionMatcher.group(1), URI.create(msiUrl),
                URI.create(checksumUrl));
    }

    private static void confirmAndInstall(String currentVersion, Release release) {
        int answer = JOptionPane.showConfirmDialog(
                null,
                "NicoCache_nl の新しいバージョンがあります。\n\n"
                        + "現在: " + currentVersion + "\n"
                        + "新版: " + release.version + "\n\n"
                        + "ダウンロードして更新しますか？",
                "NicoCache_nl の更新",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }

        Thread installer = new Thread(() -> {
            try {
                Path msi = downloadAndVerify(release);
                scheduleInstaller(msi);
                NLMain.shutdown();
            } catch (Exception error) {
                Logger.warning("更新の準備に失敗しました: " + error.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        null,
                        "更新の準備に失敗しました。\n" + error.getMessage(),
                        "NicoCache_nl の更新",
                        JOptionPane.ERROR_MESSAGE));
            }
        }, "NicoCache_nl update downloader");
        installer.setDaemon(true);
        installer.start();
    }

    static Path downloadAndVerify(Release release)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        Path directory = Files.createTempDirectory("NicoCache_nl-update-");
        Path partial = directory.resolve("NicoCache_nl.msi.download");
        Path msi = directory.resolve("NicoCache_nl-" + release.version + ".msi");

        HttpRequest msiRequest = HttpRequest.newBuilder(release.msiUri)
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "NicoCache_nl updater")
                .build();
        HttpResponse<Path> msiResponse = client.send(
                msiRequest, HttpResponse.BodyHandlers.ofFile(partial));
        if (msiResponse.statusCode() != 200) {
            throw new IOException("MSI download returned HTTP "
                    + msiResponse.statusCode());
        }

        HttpRequest checksumRequest = HttpRequest.newBuilder(release.checksumUri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "NicoCache_nl updater")
                .build();
        HttpResponse<String> checksumResponse = client.send(
                checksumRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (checksumResponse.statusCode() != 200) {
            throw new IOException("checksum download returned HTTP "
                    + checksumResponse.statusCode());
        }
        Matcher checksumMatcher = SHA256_PATTERN.matcher(checksumResponse.body());
        if (!checksumMatcher.find()) {
            throw new IOException("SHA-256 value is missing");
        }
        String expected = checksumMatcher.group(1).toLowerCase(Locale.ROOT);
        String actual = sha256(partial);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            Files.deleteIfExists(partial);
            throw new IOException("MSIのSHA-256が一致しません");
        }
        return Files.move(partial, msi, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void scheduleInstaller(Path msi) throws IOException {
        long pid = ProcessHandle.current().pid();
        String escapedPath = msi.toAbsolutePath().toString().replace("'", "''");
        String command = "Wait-Process -Id " + pid
                + "; Start-Process msiexec.exe -ArgumentList '/i','"
                + escapedPath + "'";
        new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-Command", command)
                .start();
    }

    static int compareVersions(String left, String right) {
        int[] leftParts = parseVersion(left);
        int[] rightParts = parseVersion(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.length ? leftParts[index] : 0;
            int rightValue = index < rightParts.length ? rightParts[index] : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static int[] parseVersion(String value) {
        Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return new int[] {0};
        }
        String[] parts = matcher.group(1).split("\\.");
        int[] result = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            result[index] = Integer.parseInt(parts[index]);
        }
        return result;
    }

    private static String unescapeJson(String value) {
        return value.replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    static final class Release {
        final String version;
        final URI msiUri;
        final URI checksumUri;

        Release(String version, URI msiUri, URI checksumUri) {
            this.version = version;
            this.msiUri = msiUri;
            this.checksumUri = checksumUri;
        }
    }
}
