package dareka.updater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs command-line dependencies for the current Windows user. */
final class SystemDependencyManager {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Pattern JSON_NAME = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern JSON_URL = Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern JSON_DIGEST = Pattern.compile("\\\"digest\\\"\\s*:\\s*\\\"sha256:([0-9a-fA-F]{64})\\\"");
    private static final Pattern JSON_PUBLISHED = Pattern.compile("\\\"published_at\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ADOPTIUM_SEMVER = Pattern.compile("\\\"semver\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ADOPTIUM_PACKAGE = Pattern.compile(
            "\\\"package\\\"\\s*:\\s*\\{.*?\\\"checksum\\\"\\s*:\\s*\\\"([0-9a-fA-F]{64})\\\".*?"
                    + "\\\"link\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
            Pattern.DOTALL);
    private static final Pattern ANT_ZIP = Pattern.compile("apache-ant-(\\d+\\.\\d+\\.\\d+)-bin\\.zip");
    private static final Pattern SHA256 = Pattern.compile("(?i)^[0-9a-f]{64}$");
    private static final Pattern SHA512 = Pattern.compile("(?i)^[0-9a-f]{128}$");
    private static final int MAX_ARCHIVE_ENTRIES = 200_000;
    private static final long MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024;

    private final Path userProgramsRoot;
    private final Map<String, String> environment;

    SystemDependencyManager() throws IOException {
        this(resolveUserProgramsRoot(), System.getenv());
    }

    SystemDependencyManager(Path userProgramsRoot, Map<String, String> environment) throws IOException {
        this.userProgramsRoot = userProgramsRoot.toAbsolutePath().normalize();
        this.environment = new LinkedHashMap<String, String>(environment);
        Files.createDirectories(this.userProgramsRoot);
    }

    String checkAll(int javaMajor) throws Exception {
        StringBuilder output = new StringBuilder();
        output.append("導入方式: WinGet優先、公式配布APIフォールバック").append(System.lineSeparator());
        output.append("対象スコープ: 現在のWindowsユーザー").append(System.lineSeparator());
        output.append("WinGet: ").append(isWingetAvailable() ? "利用可能" : "利用不可（フォールバックを使用）")
                .append(System.lineSeparator());
        for (Tool tool : tools(javaMajor)) {
            CommandResult detected = probe(tool.probeArguments);
            output.append(tool.displayName).append(": ")
                    .append(detected.exitCode == 0 ? firstLine(detected.output) : "未導入またはPATH未登録")
                    .append(" [winget id: ").append(tool.wingetId).append(']')
                    .append(System.lineSeparator());
        }
        return output.toString();
    }

    String updateAll(int javaMajor) throws Exception {
        StringBuilder output = new StringBuilder();
        boolean winget = isWingetAvailable();
        for (Tool tool : tools(javaMajor)) {
            boolean installed = false;
            if (winget) {
                CommandResult result = runWinget(tool);
                installed = result.exitCode == 0 && probe(tool.probeArguments).exitCode == 0;
                output.append(tool.displayName).append(": WinGet ")
                        .append(installed ? "成功" : "不成立")
                        .append(System.lineSeparator());
                if (!installed && !result.output.isBlank()) {
                    output.append(indent(result.output)).append(System.lineSeparator());
                }
            }
            if (!installed) {
                Path command = installFallback(tool);
                exposeToUser(tool, command);
                verifyExecutable(command, tool.probeArguments);
                output.append(tool.displayName).append(": 公式配布APIからユーザー領域へ導入しました: ")
                        .append(command).append(System.lineSeparator());
            }
        }
        output.append("新しいCMD/PowerShellを開くと各コマンドを使用できます。").append(System.lineSeparator());
        return output.toString();
    }

    String selfTest() throws Exception {
        Path root = Files.createTempDirectory(userProgramsRoot, "self-test-");
        try {
            Path bin = root.resolve("bin");
            Files.createDirectories(bin);
            Path command = bin.resolve("fixture.exe");
            Files.write(command, new byte[] {0, 1, 2});
            String original = "C:\\Windows\\System32;" + bin + ";" + bin.toString().toUpperCase(Locale.ROOT) + "\\";
            String merged = mergePath(original, bin);
            int occurrences = 0;
            for (String item : merged.split(";")) {
                if (normalizePathEntry(item).equals(normalizePathEntry(bin.toString()))) occurrences++;
            }
            if (occurrences != 1) throw new IOException("PATH重複排除の自己診断に失敗しました");
            return "SYSTEM_DEPENDENCY_SELF_TEST_OK winget-first fallback user-path command-verification";
        } finally {
            deleteTree(root);
        }
    }

    private List<Tool> tools(int javaMajor) throws IOException {
        if (javaMajor != 17 && javaMajor != 21) throw new IOException("未検証のTemurin LTSです: " + javaMajor);
        List<Tool> tools = new ArrayList<Tool>();
        tools.add(new Tool("temurin", "Eclipse Temurin JDK", "EclipseAdoptium.Temurin." + javaMajor + ".JDK",
                Arrays.asList("java", "-version"), "java.exe", true, javaMajor));
        tools.add(new Tool("ffmpeg", "FFmpeg", "Gyan.FFmpeg",
                Arrays.asList("ffmpeg", "-version"), "ffmpeg.exe", false, 0));
        tools.add(new Tool("ant", "Apache Ant", "Apache.Ant",
                Arrays.asList("ant", "-version"), "ant.bat", false, 0));
        tools.add(new Tool("7zip", "7-Zip", "7zip.7zip",
                Arrays.asList("7z"), "7z.exe", false, 0));
        return tools;
    }

    private CommandResult runWinget(Tool tool) throws Exception {
        List<String> common = Arrays.asList("--id", tool.wingetId, "--exact", "--scope", "user", "--silent",
                "--accept-package-agreements", "--accept-source-agreements", "--disable-interactivity");
        List<String> upgrade = new ArrayList<String>();
        upgrade.add("winget");
        upgrade.add("upgrade");
        upgrade.addAll(common);
        CommandResult result = run(upgrade, Duration.ofMinutes(20));
        if (result.exitCode == 0 && probe(tool.probeArguments).exitCode == 0) return result;
        List<String> install = new ArrayList<String>();
        install.add("winget");
        install.add("install");
        install.addAll(common);
        install.add("--force");
        return run(install, Duration.ofMinutes(20));
    }

    private Path installFallback(Tool tool) throws Exception {
        Release release;
        if ("temurin".equals(tool.id)) release = resolveTemurin(tool.javaMajor);
        else if ("ffmpeg".equals(tool.id)) release = resolveFfmpeg();
        else if ("ant".equals(tool.id)) release = resolveAnt();
        else if ("7zip".equals(tool.id)) release = resolveSevenZip();
        else throw new IOException("未知の依存関係です: " + tool.id);

        Path toolRoot = userProgramsRoot.resolve(release.id).resolve(sanitize(release.version)).normalize();
        Path staging = userProgramsRoot.resolve(".staging-" + release.id + "-" + System.nanoTime());
        deleteTree(staging);
        Files.createDirectories(staging);
        try {
            if (release.type == ArchiveType.ZIP) {
                Path archive = download(release.artifacts.get(0), staging);
                Path extracted = staging.resolve("expanded");
                Files.createDirectories(extracted);
                unzip(archive, extracted);
                flattenSingleDirectory(extracted);
                replaceDirectory(extracted, toolRoot);
            } else {
                Path archive = download(release.artifacts.get(0), staging);
                Path bootstrap = download(release.artifacts.get(1), staging);
                Path extracted = staging.resolve("expanded");
                Files.createDirectories(extracted);
                CommandResult result = run(Arrays.asList(bootstrap.toString(), "x", "-y", "-o" + extracted,
                        archive.toString()), Duration.ofMinutes(10));
                if (result.exitCode != 0) throw new IOException("7-Zipフォールバック展開に失敗しました: " + result.output);
                flattenSingleDirectory(extracted);
                replaceDirectory(extracted, toolRoot);
            }
        } finally {
            deleteTree(staging);
        }
        Path command = findCommand(toolRoot, tool.commandFileName);
        if (command == null) throw new IOException(tool.displayName + "のコマンドが配布物内にありません");
        return command;
    }

    private void exposeToUser(Tool tool, Path command) throws Exception {
        Path bin = command.getParent();
        String userPath = readUserEnvironment("Path");
        writeUserEnvironment("Path", mergePath(userPath, bin));
        if (tool.javaTool) writeUserEnvironment("JAVA_HOME", deriveJavaHome(command).toString());
        notifyEnvironmentChanged();
    }

    private static Path deriveJavaHome(Path javaExe) {
        Path bin = javaExe.getParent();
        return bin != null && bin.getParent() != null ? bin.getParent() : bin;
    }

    private String readUserEnvironment(String name) throws Exception {
        CommandResult result = run(Arrays.asList("reg.exe", "query", "HKCU\\Environment", "/v", name),
                Duration.ofSeconds(20));
        if (result.exitCode != 0) return "";
        for (String line : result.output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + " ")) {
                String[] parts = trimmed.split("\\s+", 3);
                if (parts.length == 3) return parts[2];
            }
        }
        return "";
    }

    private void writeUserEnvironment(String name, String value) throws Exception {
        CommandResult result = run(Arrays.asList("reg.exe", "add", "HKCU\\Environment", "/v", name,
                "/t", "REG_EXPAND_SZ", "/d", value, "/f"), Duration.ofSeconds(20));
        if (result.exitCode != 0) throw new IOException("ユーザー環境変数" + name + "の保存に失敗しました: " + result.output);
    }

    private static void notifyEnvironmentChanged() {
        try {
            new ProcessBuilder("rundll32.exe", "user32.dll,UpdatePerUserSystemParameters", "1", "True").start();
        } catch (IOException ignored) { }
    }

    static String mergePath(String existing, Path addition) {
        Set<String> normalized = new LinkedHashSet<String>();
        List<String> values = new ArrayList<String>();
        if (existing != null && !existing.isBlank()) {
            for (String entry : existing.split(";")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) continue;
                String key = normalizePathEntry(trimmed);
                if (normalized.add(key)) values.add(trimmed);
            }
        }
        String value = addition.toAbsolutePath().normalize().toString();
        if (normalized.add(normalizePathEntry(value))) values.add(value);
        return String.join(";", values);
    }

    static String normalizePathEntry(String value) {
        String normalized = value.trim().replace('/', '\\');
        while (normalized.endsWith("\\") && normalized.length() > 3) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private void verifyExecutable(Path command, List<String> probeArguments) throws Exception {
        List<String> direct = new ArrayList<String>();
        direct.add(command.toString());
        direct.addAll(probeArguments.subList(1, probeArguments.size()));
        CommandResult result = run(direct, Duration.ofMinutes(2));
        if (result.exitCode != 0) throw new IOException("導入したコマンドの実行確認に失敗しました: " + command + "\n" + result.output);
    }

    private CommandResult probe(List<String> arguments) throws Exception {
        return run(arguments, Duration.ofMinutes(2));
    }

    private boolean isWingetAvailable() {
        try {
            return run(Arrays.asList("winget", "--version"), Duration.ofSeconds(30)).exitCode == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private Release resolveTemurin(int major) throws Exception {
        URI uri = URI.create("https://api.adoptium.net/v3/assets/latest/" + major
                + "/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse");
        String json = text(uri, "application/json");
        Matcher semver = ADOPTIUM_SEMVER.matcher(json);
        Matcher pkg = ADOPTIUM_PACKAGE.matcher(json);
        if (!semver.find() || !pkg.find()) throw new IOException("Adoptium APIの応答を解釈できません");
        return Release.zip("temurin", semver.group(1), URI.create(unescape(pkg.group(2))), pkg.group(3),
                pkg.group(1), "SHA-256");
    }

    private Release resolveFfmpeg() throws Exception {
        String json = text(URI.create("https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/tags/latest"),
                "application/vnd.github+json");
        Asset zip = findAsset(json, Pattern.compile("^ffmpeg-master-latest-win64-gpl\\.zip$"));
        Asset checksums = findAsset(json, Pattern.compile("^checksums\\.sha256$"));
        String checksumText = text(checksums.url, "text/plain");
        Matcher match = Pattern.compile("(?m)^([0-9a-fA-F]{64})\\s+\\*?" + Pattern.quote(zip.name) + "\\s*$")
                .matcher(checksumText);
        if (!match.find()) throw new IOException("FFmpeg SHA-256が見つかりません");
        Matcher published = JSON_PUBLISHED.matcher(json);
        return Release.zip("ffmpeg", published.find() ? published.group(1) : "latest", zip.url, zip.name,
                match.group(1), "SHA-256");
    }

    private Release resolveAnt() throws Exception {
        URI base = URI.create("https://downloads.apache.org/ant/binaries/");
        String html = text(base, "text/html");
        Matcher matcher = ANT_ZIP.matcher(html);
        String version = null;
        String file = null;
        while (matcher.find()) {
            if (version == null || compareVersions(matcher.group(1), version) > 0) {
                version = matcher.group(1);
                file = matcher.group(0);
            }
        }
        if (file == null) throw new IOException("Apache Ant ZIPを検出できません");
        URI url = base.resolve(file);
        String checksum = text(URI.create(url + ".sha512"), "text/plain").trim().split("\\s+")[0];
        if (!SHA512.matcher(checksum).matches()) throw new IOException("Apache Ant SHA-512が不正です");
        return Release.zip("ant", version, url, file, checksum, "SHA-512");
    }

    private Release resolveSevenZip() throws Exception {
        String json = text(URI.create("https://api.github.com/repos/ip7z/7zip/releases/latest"),
                "application/vnd.github+json");
        Asset archive = findAsset(json, Pattern.compile("^7z[0-9]+-extra\\.7z$"));
        Asset bootstrap = findAsset(json, Pattern.compile("^7zr\\.exe$"));
        if (archive.digest == null || bootstrap.digest == null) throw new IOException("7-Zip配布物のSHA-256がありません");
        String version = archive.name.replaceAll("^7z([0-9]+)-extra\\.7z$", "$1");
        return Release.sevenZip("7zip", version,
                new Artifact(archive.url, archive.name, archive.digest, "SHA-256"),
                new Artifact(bootstrap.url, bootstrap.name, bootstrap.digest, "SHA-256"));
    }

    private Path download(Artifact artifact, Path directory) throws Exception {
        Path destination = directory.resolve(artifact.fileName).normalize();
        HttpRequest.Builder builder = HttpRequest.newBuilder(artifact.url).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "NicoCache_nl Updater");
        addGitHubToken(builder, artifact.url);
        HttpResponse<Path> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + artifact.url);
        verify(destination, artifact.checksum, artifact.algorithm);
        return destination;
    }

    private static String text(URI uri, String accept) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "NicoCache_nl Updater").header("Accept", accept);
        addGitHubToken(builder, uri);
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + uri);
        return response.body();
    }

    private static void addGitHubToken(HttpRequest.Builder builder, URI uri) {
        if (!"api.github.com".equalsIgnoreCase(uri.getHost())) return;
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        builder.header("X-GitHub-Api-Version", "2022-11-28");
    }

    private static Asset findAsset(String json, Pattern pattern) throws IOException {
        Matcher names = JSON_NAME.matcher(json);
        while (names.find()) {
            String name = unescape(names.group(1));
            if (!pattern.matcher(name).matches()) continue;
            int next = json.indexOf("\"name\"", names.end());
            String segment = json.substring(names.start(), next < 0 ? json.length() : next);
            Matcher url = JSON_URL.matcher(segment);
            Matcher digest = JSON_DIGEST.matcher(segment);
            if (!url.find()) throw new IOException("Release asset URLがありません: " + name);
            return new Asset(name, URI.create(unescape(url.group(1))), digest.find() ? digest.group(1) : null);
        }
        throw new IOException("Release assetが見つかりません: " + pattern);
    }

    private static void unzip(Path archive, Path destination) throws Exception {
        int entries = 0;
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES) throw new IOException("ZIPエントリ数上限超過");
                Path output = destination.resolve(entry.getName()).normalize();
                if (!output.startsWith(destination)) throw new IOException("ZIP traversalを拒否しました: " + entry.getName());
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
            List<Path> children = new ArrayList<Path>();
            for (Path child : stream) children.add(child);
            if (children.size() != 1 || !Files.isDirectory(children.get(0))) return;
            Path only = children.get(0);
            try (DirectoryStream<Path> nested = Files.newDirectoryStream(only)) {
                for (Path child : nested) Files.move(child, root.resolve(child.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.delete(only);
        }
    }

    private static void replaceDirectory(Path staged, Path destination) throws IOException {
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path backup = parent.resolve(destination.getFileName() + ".previous");
        deleteTree(backup);
        if (Files.exists(destination)) Files.move(destination, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            if (Files.exists(backup)) Files.move(backup, destination, StandardCopyOption.REPLACE_EXISTING);
            throw error;
        }
    }

    private static Path findCommand(Path root, String name) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        }
    }

    private static void verify(Path file, String expected, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm.replace("-", ""));
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

    private CommandResult run(List<String> command, Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) { input.transferTo(output); }
            catch (IOException ignored) { }
        }, "tool-output-reader");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("コマンドがタイムアウトしました: " + command);
        }
        reader.join(5000);
        return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private static Path resolveUserProgramsRoot() {
        String override = System.getProperty("nicocache.updater.userProgramsRoot", "");
        if (!override.isBlank()) return Path.of(override);
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) local = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
        return Path.of(local, "Programs", "NicoCache_nl Dependencies");
    }

    private static String sanitize(String value) { return value.replaceAll("[^0-9A-Za-z._-]", "_"); }
    private static String unescape(String value) { return value.replace("\\/", "/").replace("\\u0026", "&"); }
    private static String firstLine(String value) { return value.lines().findFirst().orElse(value).trim(); }
    private static String indent(String value) { return "  " + value.replace("\n", "\n  ").trim(); }

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

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }

    private enum ArchiveType { ZIP, SEVEN_ZIP }

    private static final class Tool {
        final String id;
        final String displayName;
        final String wingetId;
        final List<String> probeArguments;
        final String commandFileName;
        final boolean javaTool;
        final int javaMajor;
        Tool(String id, String displayName, String wingetId, List<String> probeArguments,
                String commandFileName, boolean javaTool, int javaMajor) {
            this.id = id;
            this.displayName = displayName;
            this.wingetId = wingetId;
            this.probeArguments = probeArguments;
            this.commandFileName = commandFileName;
            this.javaTool = javaTool;
            this.javaMajor = javaMajor;
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
        final String version;
        final List<Artifact> artifacts;
        final ArchiveType type;
        Release(String id, String version, List<Artifact> artifacts, ArchiveType type) {
            this.id = id;
            this.version = version;
            this.artifacts = artifacts;
            this.type = type;
        }
        static Release zip(String id, String version, URI url, String fileName, String checksum, String algorithm) {
            return new Release(id, version, Arrays.asList(new Artifact(url, fileName, checksum, algorithm)), ArchiveType.ZIP);
        }
        static Release sevenZip(String id, String version, Artifact archive, Artifact bootstrap) {
            return new Release(id, version, Arrays.asList(archive, bootstrap), ArchiveType.SEVEN_ZIP);
        }
    }

    private static final class Asset {
        final String name;
        final URI url;
        final String digest;
        Asset(String name, URI url, String digest) { this.name = name; this.url = url; this.digest = digest; }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;
        CommandResult(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }
}
