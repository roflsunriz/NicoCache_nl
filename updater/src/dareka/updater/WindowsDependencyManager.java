package dareka.updater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs Windows command-line dependencies for the current user. */
final class WindowsDependencyManager implements DependencyProvider {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Pattern JSON_NAME = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern JSON_URL = Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern JSON_DIGEST = Pattern.compile("\\\"digest\\\"\\s*:\\s*\\\"sha256:([0-9a-fA-F]{64})\\\"");
    private static final Pattern JSON_TAG = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ADOPTIUM_SEMVER = Pattern.compile("\\\"semver\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ADOPTIUM_PACKAGE = Pattern.compile(
            "\\\"package\\\"\\s*:\\s*\\{.*?\\\"checksum\\\"\\s*:\\s*\\\"([0-9a-fA-F]{64})\\\".*?"
                    + "\\\"link\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
            Pattern.DOTALL);
    private static final Pattern ANT_ZIP = Pattern.compile("apache-ant-(\\d+\\.\\d+\\.\\d+)-bin\\.zip");
    private static final Pattern WINGET_VERSION = Pattern.compile(
            "(?im)^\\s*(?:Package\\s+)?(?:Version|バージョン)\\s*:\\s*v?"
                    + "([0-9]+(?:\\.[0-9]+){1,3})\\s*$");
    private static final Pattern SHA512 = Pattern.compile("(?i)^[0-9a-f]{128}$");
    private static final int MAX_ARCHIVE_ENTRIES = 200_000;
    private static final long MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024;

    private final Path userProgramsRoot;
    private final Map<String, String> environment;
    private final String wingetExecutable;

    WindowsDependencyManager() throws IOException {
        this(resolveUserProgramsRoot(), System.getenv());
    }

    WindowsDependencyManager(Path userProgramsRoot, Map<String, String> environment) throws IOException {
        this.userProgramsRoot = userProgramsRoot.toAbsolutePath().normalize();
        this.environment = new LinkedHashMap<String, String>(environment);
        this.wingetExecutable = resolveWingetExecutable(this.environment);
        Files.createDirectories(this.userProgramsRoot);
    }

    @Override
    public String checkAll(int javaMajor) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("導入方式: WinGet優先、公式配布APIフォールバック\n");
        out.append("対象スコープ: WinGetはユーザー優先（パッケージに応じてマシン）、公式配布APIは現在のWindowsユーザー\n");
        out.append("WinGet: ").append(isWingetAvailable() ? "利用可能" : "利用不可（フォールバックを使用）").append('\n');
        for (DependencyStatus status : inspectAll(javaMajor)) {
            out.append(formatStatus(status)).append('\n');
        }
        return out.toString();
    }

    @Override
    public List<DependencyStatus> inspectAll(int javaMajor) throws Exception {
        List<DependencyStatus> result = new ArrayList<DependencyStatus>();
        for (Tool tool : tools(javaMajor)) {
            try {
                Release latest = resolveLatest(tool);
                CommandResult current = probe(tool);
                String installed = installedVersion(tool, current);
                if (installed == null) installed = readMarkedVersion(tool.id);
                String normalizedInstalled = normalizeVersion(installed);
                boolean update = normalizedInstalled == null
                        || compareVersions(normalizedInstalled, latest.version) < 0;
                boolean installable = tool.wingetId == null
                        ? tool.fallbackSupported
                        : isWingetAvailable() || tool.fallbackSupported;
                String provider = tool.wingetId == null
                        ? "公式配布API"
                        : "WinGet" + (tool.fallbackSupported ? " / 公式配布API" : "");
                result.add(new DependencyStatus(tool.id, tool.displayName,
                        installed, latest.version,
                        provider + (update ? "（更新あり）" : "（最新）"),
                        true, update, installable));
            } catch (Exception error) {
                result.add(DependencyStatus.failure(tool.id, tool.displayName,
                        rootMessage(error)));
            }
        }
        return result;
    }

    @Override
    public String updateAll(int javaMajor) throws Exception {
        StringBuilder out = new StringBuilder();
        List<DependencyStatus> statuses = inspectAll(javaMajor);
        boolean installed = false;
        for (DependencyStatus status : statuses) {
            if (!status.canInstall()) continue;
            installed = true;
            Tool tool = findTool(status.id, javaMajor);
            out.append(installTool(tool, resolveLatest(tool)));
        }
        if (!installed) out.append("新しいWindows外部依存関係はありません。\n");
        return out.toString();
    }

    @Override
    public String install(String dependencyId, int javaMajor) throws Exception {
        Tool tool = findTool(dependencyId, javaMajor);
        Release release = resolveLatest(tool);
        return installTool(tool, release);
    }

    private static String formatStatus(DependencyStatus status) {
        return status.displayName + ": 導入版=" + status.installedLabel()
                + ", 最新版=" + status.latestLabel() + " " + status.message
                + (status.canInstall() ? " [インストール可能]" : " [インストール不可]");
    }

    private String installTool(Tool tool, Release release) throws Exception {
        StringBuilder out = new StringBuilder();
        boolean ready = false;
        if (isWingetAvailable() && tool.wingetId != null) {
            CommandResult result;
            try {
                result = runWinget(tool);
            } catch (Exception failure) {
                result = new CommandResult(1, failure.getMessage() == null
                        ? failure.toString() : failure.getMessage());
            }
            ready = result.exitCode == 0;
            out.append(tool.displayName).append(ready
                    ? ": WinGetでインストールしました\n"
                    : ": WinGet不成立、フォールバックへ移行\n");
            if (!ready && !result.output.isBlank()) {
                out.append("  ").append(result.output.replace("\n", "\n  ").trim()).append('\n');
            }
        } else if (tool.wingetId != null && !tool.fallbackSupported) {
            throw new IOException(tool.displayName
                    + "はWinGet（" + tool.wingetId + "）が必要です。WinGetを導入して再試行してください");
        }
        if (!ready) {
            Path command = installFallback(tool, release);
            exposeToUser(tool, command);
            verifyExecutable(command, tool);
            out.append(tool.displayName).append(": 公式配布APIから導入しました: ")
                    .append(command).append('\n');
        }
        markInstalledVersion(tool.id, release.version);
        return out.toString();
    }

    private static String rootMessage(Exception error) {
        Throwable value = error;
        while (value.getCause() != null) value = value.getCause();
        return value.getMessage() == null ? value.toString() : value.getMessage();
    }

    @Override
    public String selfTest() throws Exception {
        Path root = Files.createTempDirectory("NicoCache_nl-dependency-self-test-");
        try {
            Path bin = root.resolve("bin");
            Files.createDirectories(bin);
            String merged = mergePath("C:\\Windows\\System32;" + bin + ";" + bin.toString().toUpperCase(Locale.ROOT) + "\\", bin);
            int count = 0;
            for (String item : merged.split(";")) {
                if (normalizePathEntry(item).equals(normalizePathEntry(bin.toString()))) count++;
            }
            if (count != 1) throw new IOException("PATH重複排除の自己診断に失敗しました");
            if (probe(Arrays.asList("command-that-must-not-exist-nicocache", "--version")).exitCode == 0) {
                throw new IOException("不存在コマンドの自己診断に失敗しました");
            }
            List<String> winget = wingetArguments("winget", "install", "Example.Package");
            int source = winget.indexOf("--source");
            if (source < 0 || source + 1 >= winget.size() || !"winget".equals(winget.get(source + 1))) {
                throw new IOException("WinGet source固定の自己診断に失敗しました");
            }
            if (winget.contains("--scope")) {
                throw new IOException("WinGetスコープ自動選択の自己診断に失敗しました");
            }
            return "SYSTEM_DEPENDENCY_SELF_TEST_OK winget-source winget-auto-scope winget-first fallback user-path command-verification";
        } finally {
            deleteTree(root);
        }
    }

    private List<Tool> tools(int javaMajor) throws IOException {
        if (javaMajor != 17 && javaMajor != 21 && javaMajor != 25) {
            throw new IOException("未検証のTemurin LTSです: " + javaMajor);
        }
        Pattern labeledVersion = Pattern.compile(
                "(?i)\\bversion\\s+[\\\"']?v?([0-9]+(?:\\.[0-9]+){1,3})");
        Pattern sevenZipVersion = Pattern.compile(
                "(?i)\\b7-zip\\b.*?([0-9]+(?:\\.[0-9]+){1,3})");
        return Arrays.asList(
                new Tool("temurin", "Eclipse Temurin JDK", "EclipseAdoptium.Temurin." + javaMajor + ".JDK",
                        Arrays.asList("java", "-version"), "java.exe", true, javaMajor,
                        labeledVersion, true),
                new Tool("ffmpeg", "FFmpeg", "Gyan.FFmpeg", Arrays.asList("ffmpeg", "-version"),
                        "ffmpeg.exe", false, 0, labeledVersion, false),
                new Tool("ant", "Apache Ant", null, Arrays.asList("ant", "-version"),
                        "ant.bat", false, 0, labeledVersion, true),
                new Tool("7zip", "7-Zip", "7zip.7zip", Arrays.asList("7z"),
                        "7z.exe", false, 0, sevenZipVersion, true),
                new Tool("gpac", "GPAC / MP4Box", "GPAC.GPAC", Arrays.asList("MP4Box", "-version"),
                        "MP4Box.exe", false, 0, labeledVersion, false));
    }

    private Tool findTool(String dependencyId, int javaMajor) throws IOException {
        for (Tool tool : tools(javaMajor)) {
            if (tool.id.equals(dependencyId)) return tool;
        }
        throw new IOException("未対応の外部依存関係です: " + dependencyId);
    }

    private Release resolveLatest(Tool tool) throws Exception {
        if ("temurin".equals(tool.id)) return resolveTemurin(tool.javaMajor);
        if ("ffmpeg".equals(tool.id)) return resolveFfmpeg();
        if ("ant".equals(tool.id)) return resolveAnt();
        if ("7zip".equals(tool.id)) return resolveSevenZip();
        if ("gpac".equals(tool.id)) return resolveGpac();
        throw new IOException("Windows側で処理しない依存関係です: " + tool.id);
    }

    private static String installedVersion(Tool tool, CommandResult result) {
        if (result.exitCode != 0 || tool.versionPattern == null) return null;
        Matcher matcher = tool.versionPattern.matcher(result.output);
        return matcher.find() ? normalizeVersion(matcher.group(1)) : null;
    }

    static List<String> wingetArguments(String executable, String operation, String packageId) {
        List<String> command = new ArrayList<String>();
        command.add(executable);
        command.add(operation);
        command.add("--id");
        command.add(packageId);
        command.add("--exact");
        command.add("--source");
        command.add("winget");
        command.add("--silent");
        command.add("--accept-package-agreements");
        command.add("--accept-source-agreements");
        command.add("--disable-interactivity");
        return command;
    }

    static List<String> wingetShowArguments(String executable, String packageId) {
        List<String> command = new ArrayList<String>();
        command.add(executable);
        command.add("show");
        command.add("--id");
        command.add(packageId);
        command.add("--exact");
        command.add("--source");
        command.add("winget");
        command.add("--accept-source-agreements");
        command.add("--disable-interactivity");
        return command;
    }

    static String parseWingetVersion(String output) {
        if (output == null) return null;
        Matcher matcher = WINGET_VERSION.matcher(output);
        return matcher.find() ? normalizeVersion(matcher.group(1)) : null;
    }

    private CommandResult runWinget(Tool tool) throws Exception {
        StringBuilder output = new StringBuilder();
        List<String> upgrade = wingetArguments(wingetExecutable, "upgrade", tool.wingetId);
        CommandResult upgradeResult = run(upgrade, Duration.ofMinutes(20));
        output.append(upgradeResult.output);
        if (upgradeResult.exitCode == 0 || isWingetPackageInstalled(tool.wingetId)) {
            refreshEnvironmentAfterWinget();
            return new CommandResult(0, output.toString());
        }

        List<String> install = wingetArguments(wingetExecutable, "install", tool.wingetId);
        install.add("--force");
        CommandResult installResult = run(install, Duration.ofMinutes(20));
        if (!installResult.output.isBlank()) {
            if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') output.append('\n');
            output.append(installResult.output);
        }
        if (installResult.exitCode == 0 || isWingetPackageInstalled(tool.wingetId)) {
            refreshEnvironmentAfterWinget();
            return new CommandResult(0, output.toString());
        }
        return new CommandResult(installResult.exitCode, output.toString());
    }

    private boolean isWingetPackageInstalled(String packageId) {
        try {
            List<String> command = Arrays.asList(wingetExecutable, "list", "--id", packageId, "--exact",
                    "--source", "winget", "--accept-source-agreements", "--disable-interactivity");
            CommandResult result = run(command, Duration.ofMinutes(2));
            if (result.exitCode != 0) return false;
            String lower = result.output.toLowerCase(Locale.ROOT);
            return lower.contains(packageId.toLowerCase(Locale.ROOT));
        } catch (Exception failure) {
            return false;
        }
    }

    private void refreshEnvironmentAfterWinget() {
        try {
            String userPath = readUserEnvironment("Path");
            String currentPath = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
            if (!userPath.isBlank()) {
                String merged = mergePathStrings(currentPath, userPath);
                environment.put("PATH", merged);
                environment.put("Path", merged);
            }
            String javaHome = readUserEnvironment("JAVA_HOME");
            if (!javaHome.isBlank()) environment.put("JAVA_HOME", javaHome);
        } catch (Exception ignored) { }
    }

    static String mergePathStrings(String first, String second) {
        Set<String> normalized = new LinkedHashSet<String>();
        List<String> values = new ArrayList<String>();
        for (String source : Arrays.asList(first, second)) {
            if (source == null || source.isBlank()) continue;
            for (String entry : source.split(";")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty() && normalized.add(normalizePathEntry(trimmed))) values.add(trimmed);
            }
        }
        return String.join(";", values);
    }

    private Path installFallback(Tool tool, Release release) throws Exception {
        if (!tool.fallbackSupported || release.artifacts.isEmpty()) {
            throw new IOException(tool.displayName + "の公式配布フォールバックは利用できません");
        }
        Path destination = userProgramsRoot.resolve(release.id).resolve(sanitize(release.version));
        Path work = Files.createTempDirectory(userProgramsRoot, ".stage-" + release.id + "-");
        try {
            Path expanded = work.resolve("expanded");
            Files.createDirectories(expanded);
            if (release.type == ArchiveType.ZIP) {
                unzip(download(release.artifacts.get(0), work), expanded);
            } else {
                Path archive = download(release.artifacts.get(0), work);
                Path bootstrap = download(release.artifacts.get(1), work);
                CommandResult result = run(Arrays.asList(bootstrap.toString(), "x", "-y", "-o" + expanded, archive.toString()),
                        Duration.ofMinutes(10));
                if (result.exitCode != 0) throw new IOException("7-Zip展開に失敗しました: " + result.output);
            }
            flattenSingleDirectory(expanded);
            replaceDirectory(expanded, destination);
        } finally {
            deleteTree(work);
        }
        Path command = findCommand(destination, tool.commandFileName);
        if (command == null && "7zip".equals(tool.id)) command = findCommand(destination, "7za.exe");
        if (command == null) throw new IOException(tool.displayName + "のコマンドが配布物内にありません");
        return command;
    }

    private void exposeToUser(Tool tool, Path command) throws Exception {
        writeUserEnvironment("Path", mergePath(readUserEnvironment("Path"), command.getParent()));
        if (tool.javaTool) writeUserEnvironment("JAVA_HOME", command.getParent().getParent().toString());
        notifyEnvironmentChanged();
    }

    private String readUserEnvironment(String name) throws Exception {
        CommandResult result = run(Arrays.asList("reg.exe", "query", "HKCU\\Environment", "/v", name), Duration.ofSeconds(20));
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
        if ("Path".equalsIgnoreCase(name)) {
            // Keep machine PATH entries (including a WinGet Temurin install) in the
            // current updater process. The registry value contains only the user's
            // PATH, so assigning it directly would make later probes lose java.exe.
            String currentPath = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
            String effectivePath = mergePathStrings(currentPath, value);
            environment.put("Path", effectivePath);
            environment.put("PATH", effectivePath);
        } else {
            environment.put(name, value);
        }
    }

    private static void notifyEnvironmentChanged() {
        try { new ProcessBuilder("rundll32.exe", "user32.dll,UpdatePerUserSystemParameters", "1", "True").start(); }
        catch (IOException ignored) { }
    }

    static String mergePath(String existing, Path addition) {
        Set<String> normalized = new LinkedHashSet<String>();
        List<String> values = new ArrayList<String>();
        if (existing != null && !existing.isBlank()) {
            for (String entry : existing.split(";")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty() && normalized.add(normalizePathEntry(trimmed))) values.add(trimmed);
            }
        }
        String value = addition.toAbsolutePath().normalize().toString();
        if (normalized.add(normalizePathEntry(value))) values.add(value);
        return String.join(";", values);
    }

    static String normalizePathEntry(String value) {
        String normalized = value.trim().replace('/', '\\');
        while (normalized.endsWith("\\") && normalized.length() > 3) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.toLowerCase(Locale.ROOT);
    }

    private void verifyExecutable(Path command, Tool tool) throws Exception {
        CommandResult result = run(commandInvocation(command, tool.probe), Duration.ofMinutes(2));
        if (result.exitCode != 0) throw new IOException("導入したコマンドの実行確認に失敗しました: " + command + "\n" + result.output);
    }

    private CommandResult probe(Tool tool) {
        try {
            return run(toolInvocation(tool), Duration.ofMinutes(2));
        } catch (Exception missing) {
            return new CommandResult(127, missing.getMessage() == null ? missing.toString() : missing.getMessage());
        }
    }

    private CommandResult probe(List<String> arguments) {
        try {
            return run(arguments, Duration.ofMinutes(2));
        } catch (Exception missing) {
            return new CommandResult(127, missing.getMessage() == null ? missing.toString() : missing.getMessage());
        }
    }

    private static List<String> toolInvocation(Tool tool) {
        if (!isBatchFileName(tool.commandFileName)) return new ArrayList<String>(tool.probe);
        List<String> command = new ArrayList<String>();
        command.add("cmd.exe");
        command.add("/d");
        command.add("/c");
        command.add(batchCommandLine(tool.commandFileName, tool.probe.subList(1, tool.probe.size())));
        return command;
    }

    static List<String> commandInvocation(Path commandPath, List<String> probeArguments) {
        List<String> command = new ArrayList<String>();
        if (isBatchFileName(commandPath.getFileName().toString())) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
            command.add(batchCommandLine(commandPath.toString(),
                    probeArguments.subList(1, probeArguments.size())));
        } else {
            command.add(commandPath.toString());
            command.addAll(probeArguments.subList(1, probeArguments.size()));
        }
        return command;
    }

    private static String batchCommandLine(String commandPath, List<String> arguments) {
        StringBuilder value = new StringBuilder();
        if (commandPath.indexOf(' ') >= 0 || commandPath.indexOf('\t') >= 0) {
            value.append('"').append(commandPath).append('"');
        } else {
            value.append(commandPath);
        }
        for (String argument : arguments) {
            value.append(' ');
            if (argument.indexOf(' ') >= 0 || argument.indexOf('\t') >= 0) {
                value.append('"').append(argument).append('"');
            } else {
                value.append(argument);
            }
        }
        return value.toString();
    }

    private static boolean isBatchFileName(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".bat") || lower.endsWith(".cmd");
    }

    private boolean isWingetAvailable() {
        return wingetExecutable != null && probe(Arrays.asList(wingetExecutable, "--version")).exitCode == 0;
    }

    static String resolveWingetExecutable(Map<String, String> environment) {
        String localAppData = environment.get("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path alias = Path.of(localAppData, "Microsoft", "WindowsApps", "winget.exe");
            // App Execution Alias is a reparse point whose target is not exposed to
            // Files.exists() on some Windows builds, even though CreateProcess can run it.
            if (Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) return alias.toString();
        }
        return "winget";
    }

    private Release resolveTemurin(int major) throws Exception {
        String json = text(URI.create("https://api.adoptium.net/v3/assets/latest/" + major
                + "/hotspot?architecture=" + adoptiumArchitecture()
                + "&image_type=jdk&os=windows&vendor=eclipse"), "application/json");
        Matcher semver = ADOPTIUM_SEMVER.matcher(json);
        Matcher pkg = ADOPTIUM_PACKAGE.matcher(json);
        if (!semver.find() || !pkg.find()) throw new IOException("Adoptium APIの応答を解釈できません");
        return Release.zip("temurin", normalizeVersion(semver.group(1)),
                URI.create(unescape(pkg.group(2))), pkg.group(3), pkg.group(1), "SHA-256");
    }

    private Release resolveFfmpeg() throws Exception {
        if (!isWingetAvailable()) {
            throw new IOException("FFmpegの最新版はWinGet（Gyan.FFmpeg）から確認します。WinGetを導入して再試行してください");
        }
        CommandResult result = run(wingetShowArguments(wingetExecutable, "Gyan.FFmpeg"),
                Duration.ofMinutes(2));
        if (result.exitCode != 0) {
            throw new IOException("WinGetでFFmpeg（Gyan.FFmpeg）の最新版を確認できません: "
                    + result.output);
        }
        String version = parseWingetVersion(result.output);
        if (version == null) {
            throw new IOException("WinGetのFFmpeg版情報を解釈できません");
        }
        // FFmpegの公開日は版番号ではないため、WinGetの同じパッケージ版を比較対象にする。
        return new Release("ffmpeg", version, new ArrayList<Artifact>(), ArchiveType.ZIP);
    }

    private Release resolveAnt() throws Exception {
        URI base = URI.create("https://downloads.apache.org/ant/binaries/");
        Matcher matcher = ANT_ZIP.matcher(text(base, "text/html"));
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
        String json = text(URI.create("https://api.github.com/repos/ip7z/7zip/releases/latest"), "application/vnd.github+json");
        Asset archive = findAsset(json, Pattern.compile("^7z[0-9]+-extra\\.7z$"));
        Asset bootstrap = findAsset(json, Pattern.compile("^7zr\\.exe$"));
        if (archive.digest == null || bootstrap.digest == null) throw new IOException("7-Zip SHA-256がありません");
        String digits = archive.name.replaceAll("^7z([0-9]+)-extra\\.7z$", "$1");
        String version = digits.length() > 2
                ? digits.substring(0, digits.length() - 2) + "." + digits.substring(digits.length() - 2)
                : digits;
        return Release.sevenZip("7zip", version,
                new Artifact(archive.url, archive.name, archive.digest, "SHA-256"),
                new Artifact(bootstrap.url, bootstrap.name, bootstrap.digest, "SHA-256"));
    }

    private Release resolveGpac() throws Exception {
        String json = text(URI.create("https://api.github.com/repos/gpac/gpac/releases/latest"),
                "application/vnd.github+json");
        Matcher tag = JSON_TAG.matcher(json);
        if (!tag.find()) throw new IOException("GPAC公式Release APIに版番号がありません");
        String version = normalizeVersion(tag.group(1));
        if (version == null) throw new IOException("GPAC公式Releaseの版番号が不正です");
        return new Release("gpac", version, new ArrayList<Artifact>(), ArchiveType.ZIP);
    }

    private static String adoptiumArchitecture() throws IOException {
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (architecture.equals("amd64") || architecture.equals("x86_64")
                || architecture.equals("x64")) return "x64";
        if (architecture.equals("aarch64") || architecture.equals("arm64")) return "aarch64";
        if (architecture.equals("x86") || architecture.equals("i386")
                || architecture.equals("i686")) return "x86";
        throw new IOException("Temurinが対応していないCPUアーキテクチャです: " + architecture);
    }

    private Path download(Artifact artifact, Path directory) throws Exception {
        Path destination = directory.resolve(artifact.fileName);
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
                if (!output.startsWith(destination)) throw new IOException("ZIP traversalを拒否しました");
                if (entry.isDirectory()) Files.createDirectories(output);
                else {
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
        Files.createDirectories(destination.getParent());
        Path backup = destination.resolveSibling(destination.getFileName() + ".previous");
        deleteTree(backup);
        if (Files.exists(destination)) Files.move(destination, backup, StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException error) {
            if (Files.exists(backup)) Files.move(backup, destination, StandardCopyOption.REPLACE_EXISTING);
            throw error;
        }
    }

    private static Path findCommand(Path root, String name) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        }
    }

    private static void verify(Path file, String expected, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm.replace("-", ""));
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format("%02x", value & 0xff));
        if (!MessageDigest.isEqual(expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                actual.toString().getBytes(StandardCharsets.US_ASCII))) throw new IOException("ハッシュが一致しません: " + file);
    }

    private CommandResult run(List<String> command, Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) { input.transferTo(output); }
            catch (IOException ignored) { }
        });
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("コマンドがタイムアウトしました: " + command);
        }
        reader.join(5000);
        return new CommandResult(process.exitValue(),
                decodeCommandOutput(output.toByteArray(), commandOutputCharset()));
    }

    static String decodeCommandOutput(byte[] output, Charset charset) {
        if (output == null || output.length == 0) return "";
        if (startsWith(output, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)) {
            return new String(output, 3, output.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(output, (byte) 0xFF, (byte) 0xFE)) {
            return new String(output, 2, output.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(output, (byte) 0xFE, (byte) 0xFF)) {
            return new String(output, 2, output.length - 2, StandardCharsets.UTF_16BE);
        }
        if (isValidUtf8(output)) return new String(output, StandardCharsets.UTF_8);
        return new String(output, charset == null ? StandardCharsets.UTF_8 : charset);
    }

    static Charset commandOutputCharset() {
        for (String property : Arrays.asList(
                "native.encoding", "sun.jnu.encoding", "file.encoding",
                "stdout.encoding", "stderr.encoding")) {
            String value = System.getProperty(property);
            if (value == null || value.isBlank()) continue;
            try {
                return Charset.forName(value);
            } catch (IllegalArgumentException ignored) {
                // Try the next runtime-provided encoding name.
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean startsWith(byte[] value, byte... prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    private static boolean isValidUtf8(byte[] value) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value));
            return true;
        } catch (CharacterCodingException error) {
            return false;
        }
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
    private static String normalizeVersion(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replace('-', '.');
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+){0,3})").matcher(normalized);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Path versionMarkerFile() {
        return userProgramsRoot.resolve(".installed-versions.properties");
    }

    private String readMarkedVersion(String dependencyId) {
        Path marker = versionMarkerFile();
        if (!Files.isRegularFile(marker)) return null;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(marker)) {
            properties.load(input);
            return normalizeVersion(properties.getProperty(dependencyId));
        } catch (IOException error) {
            return null;
        }
    }

    private void markInstalledVersion(String dependencyId, String version) {
        if (version == null || version.isBlank()) return;
        Path marker = versionMarkerFile();
        Properties properties = new Properties();
        try {
            if (Files.isRegularFile(marker)) {
                try (InputStream input = Files.newInputStream(marker)) {
                    properties.load(input);
                }
            }
            properties.setProperty(dependencyId, version);
            try (OutputStream output = Files.newOutputStream(marker)) {
                properties.store(output, "NicoCache_nl updater dependency versions");
            }
        } catch (IOException ignored) {
            // A marker only improves detection; the command probe remains authoritative.
        }
    }

    private static int compareVersions(String left, String right) {
        String normalizedLeft = normalizeVersion(left);
        String normalizedRight = normalizeVersion(right);
        if (normalizedLeft == null || normalizedRight == null) return 0;
        String[] a = normalizedLeft.split("\\.");
        String[] b = normalizedRight.split("\\.");
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
        final String id, displayName, wingetId, commandFileName;
        final List<String> probe;
        final boolean javaTool;
        final int javaMajor;
        final Pattern versionPattern;
        final boolean fallbackSupported;
        Tool(String id, String displayName, String wingetId, List<String> probe, String commandFileName,
                boolean javaTool, int javaMajor, Pattern versionPattern,
                boolean fallbackSupported) {
            this.id = id;
            this.displayName = displayName;
            this.wingetId = wingetId;
            this.probe = probe;
            this.commandFileName = commandFileName;
            this.javaTool = javaTool;
            this.javaMajor = javaMajor;
            this.versionPattern = versionPattern;
            this.fallbackSupported = fallbackSupported;
        }
    }
    private static final class Artifact {
        final URI url;
        final String fileName, checksum, algorithm;
        Artifact(URI url, String fileName, String checksum, String algorithm) {
            this.url = url;
            this.fileName = fileName;
            this.checksum = checksum;
            this.algorithm = algorithm;
        }
    }
    private static final class Release {
        final String id, version;
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
        Asset(String name, URI url, String digest) {
            this.name = name;
            this.url = url;
            this.digest = digest;
        }
    }
    private static final class CommandResult {
        final int exitCode;
        final String output;
        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
