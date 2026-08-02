package dareka.updater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dependency checks and explicit package-manager installs for Linux/macOS. */
final class UnixDependencyManager implements DependencyProvider {
    private static final Pattern VERSION = Pattern.compile(
            "(?i)(?:version|7-zip|gpac)\\s*[\\\"']?v?([0-9]+(?:[.-][0-9]+){1,3})");
    private static final Pattern CANDIDATE = Pattern.compile(
            "(?im)^\\s*(?:candidate|version)\\s*:\\s*([^\\s]+)");

    @Override
    public String checkAll(int javaMajor) throws Exception {
        StringBuilder output = new StringBuilder();
        for (DependencyStatus status : inspectAll(javaMajor)) {
            output.append(formatStatus(status)).append(System.lineSeparator());
        }
        return output.toString();
    }

    @Override
    public List<DependencyStatus> inspectAll(int javaMajor) throws Exception {
        validateJavaMajor(javaMajor);
        PackageManager manager = packageManager();
        List<DependencyStatus> result = new ArrayList<DependencyStatus>();
        for (Tool tool : tools(javaMajor)) {
            try {
                CommandResult probe = run(tool.probe, Duration.ofSeconds(10));
                String installed = probe.exitCode == 0
                        ? parseVersion(probe.output) : null;
                String latest = resolveLatest(manager, tool, installed);
                boolean update = installed == null
                        || latest != null && compareVersions(installed, latest) < 0;
                boolean installable = manager != PackageManager.NONE
                        && (latest != null || installed == null);
                String message = manager == PackageManager.NONE
                        ? tool.hint : manager.label + "で確認済み";
                result.add(new DependencyStatus(tool.id, tool.name, installed,
                        latest, message + (update ? "（更新あり）" : "（最新）"),
                        true, update, installable));
            } catch (Exception error) {
                result.add(DependencyStatus.failure(tool.id, tool.name,
                        error.getMessage() == null ? error.toString() : error.getMessage()));
            }
        }
        return result;
    }

    @Override
    public String updateAll(int javaMajor) throws Exception {
        StringBuilder output = new StringBuilder();
        boolean installed = false;
        for (DependencyStatus status : inspectAll(javaMajor)) {
            if (!status.canInstall()) continue;
            installed = true;
            output.append(install(status.id, javaMajor));
        }
        if (!installed) {
            output.append("Linux/macOSに新しい外部依存関係はありません。\n");
        }
        return output.toString();
    }

    @Override
    public String install(String dependencyId, int javaMajor) throws Exception {
        Tool selected = null;
        for (Tool tool : tools(javaMajor)) {
            if (tool.id.equals(dependencyId)) {
                selected = tool;
                break;
            }
        }
        if (selected == null) throw new IOException("未対応の外部依存関係です: " + dependencyId);
        PackageManager manager = packageManager();
        if (manager == PackageManager.NONE) {
            throw new IOException("利用可能なLinux/macOSパッケージ管理がありません。"
                    + "各OSのパッケージ管理から導入して再確認してください");
        }
        boolean alreadyInstalled = run(selected.probe, Duration.ofSeconds(10)).exitCode == 0;
        CommandResult result = installPackage(manager, selected, alreadyInstalled);
        if (result.exitCode != 0) {
            throw new IOException(selected.name + "のインストールに失敗しました: "
                    + result.output.trim());
        }
        return selected.name + ": " + manager.label + "でインストールしました\n";
    }

    @Override
    public String selfTest() throws Exception {
        for (Tool tool : tools(25)) {
            if (tool.probe.isEmpty() || tool.packageName.isBlank()) {
                throw new IOException("Unix依存関係の自己診断情報が空です: " + tool.id);
            }
        }
        return "SYSTEM_DEPENDENCY_SELF_TEST_OK unix-package-manager-explicit-install gpac";
    }

    private static void validateJavaMajor(int javaMajor) throws IOException {
        if (javaMajor != 17 && javaMajor != 21 && javaMajor != 25) {
            throw new IOException("未検証のTemurin LTSです: " + javaMajor);
        }
    }

    private static List<Tool> tools(int javaMajor) {
        return Arrays.asList(
                new Tool("temurin", "Eclipse Temurin JDK", "temurin@" + javaMajor,
                        "openjdk-" + javaMajor + "-jdk", true,
                        Arrays.asList("java", "-version"), "JDK 17/21/25をOSのパッケージ管理から導入"),
                new Tool("ffmpeg", "FFmpeg", "ffmpeg", "ffmpeg", false,
                        Arrays.asList("ffmpeg", "-version"), "FFmpegをOSのパッケージ管理から導入"),
                new Tool("ant", "Apache Ant", "ant", "ant", false,
                        Arrays.asList("ant", "-version"), "Apache AntをOSのパッケージ管理から導入"),
                new Tool("7zip", "7-Zip", "sevenzip", "7zip", false,
                        Arrays.asList("7z", "--help"), "7-Zipまたは7zzをOSのパッケージ管理から導入"),
                new Tool("gpac", "GPAC / MP4Box", "gpac", "gpac", false,
                        Arrays.asList("MP4Box", "-version"), "GPAC（MP4Box）をOSのパッケージ管理から導入"));
    }

    private static PackageManager packageManager() {
        if (UpdaterPlatform.current() == UpdaterPlatform.Kind.MACOS
                && commandExists("brew")) {
            return new PackageManager("Homebrew", "brew", false);
        }
        if (UpdaterPlatform.current() == UpdaterPlatform.Kind.LINUX) {
            if (commandExists("apt-get")) return new PackageManager("APT", "apt-get", false);
            if (commandExists("dnf")) return new PackageManager("DNF", "dnf", false);
            if (commandExists("pacman")) return new PackageManager("pacman", "pacman", false);
        }
        return PackageManager.NONE;
    }

    private static String resolveLatest(PackageManager manager, Tool tool,
            String installed) {
        if (manager == PackageManager.NONE) return installed;
        try {
            CommandResult result;
            if ("brew".equals(manager.command)) {
                List<String> args = new ArrayList<String>(Arrays.asList(
                        "brew", "info", "--json=v2"));
                if (tool.brewCask) args.add("--cask");
                args.add(tool.packageName);
                result = run(args, Duration.ofSeconds(30));
                Matcher stable = Pattern.compile("\\\"stable\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                        .matcher(result.output);
                if (result.exitCode == 0 && stable.find()) return normalize(stable.group(1));
                Matcher caskVersion = Pattern.compile("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                        .matcher(result.output);
                if (result.exitCode == 0 && caskVersion.find()) {
                    return normalize(caskVersion.group(1));
                }
            } else {
                List<String> query = new ArrayList<String>();
                if ("apt-get".equals(manager.command)) {
                    query.add("apt-cache");
                    query.add("policy");
                } else if ("dnf".equals(manager.command)) {
                    query.add("dnf");
                    query.add("info");
                } else {
                    query.add("pacman");
                    query.add("-Si");
                }
                query.add(tool.linuxPackage);
                result = run(query, Duration.ofSeconds(30));
                Matcher candidate = CANDIDATE.matcher(result.output);
                if (result.exitCode == 0 && candidate.find()
                        && !"(none)".equalsIgnoreCase(candidate.group(1))) {
                    return normalize(candidate.group(1));
                }
            }
        } catch (Exception ignored) {
            // The installed command remains useful even when package metadata is unavailable.
        }
        return installed;
    }

    private static CommandResult installPackage(PackageManager manager, Tool tool,
            boolean alreadyInstalled) throws Exception {
        List<String> command = new ArrayList<String>();
        if ("brew".equals(manager.command)) {
            command.add("brew");
            command.add(alreadyInstalled ? "upgrade" : "install");
            if (tool.brewCask) command.add("--cask");
            command.add(tool.packageName);
        } else {
            boolean root = "root".equals(System.getProperty("user.name", ""));
            if (!root && commandExists("sudo")) command.add("sudo");
            command.add(manager.command);
            if ("pacman".equals(manager.command)) {
                command.add("-S");
                command.add("--noconfirm");
            } else {
                command.add("install");
                command.add("-y");
            }
            command.add(tool.linuxPackage);
        }
        return run(command, Duration.ofMinutes(30));
    }

    private static CommandResult run(List<String> command, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copy(process.getInputStream(), output),
                "nicocache-unix-dependency-reader");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("コマンドがタイムアウトしました: " + command);
        }
        reader.join(5000L);
        return new CommandResult(process.exitValue(), output.toString("UTF-8"));
    }

    private static boolean commandExists(String command) {
        try {
            return run(Arrays.asList(command, "--version"), Duration.ofSeconds(5)).exitCode == 0;
        } catch (Exception error) {
            return false;
        }
    }

    private static String parseVersion(String output) {
        Matcher matcher = VERSION.matcher(output == null ? "" : output);
        return matcher.find() ? normalize(matcher.group(1)) : null;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+){0,3})").matcher(value.replace('-', '.'));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            int leftValue = index < a.length ? Integer.parseInt(a[index]) : 0;
            int rightValue = index < b.length ? Integer.parseInt(b[index]) : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private static String formatStatus(DependencyStatus status) {
        return status.displayName + ": 導入版=" + status.installedLabel()
                + ", 最新版=" + status.latestLabel() + " " + status.message
                + (status.canInstall() ? " [インストール可能]" : " [インストール不可]");
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        try (InputStream source = input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = source.read(buffer)) >= 0) output.write(buffer, 0, read);
        } catch (IOException ignored) {
            // The process exit code remains authoritative.
        }
    }

    private static final class Tool {
        final String id, name, packageName, linuxPackage, hint;
        final boolean brewCask;
        final List<String> probe;
        Tool(String id, String name, String packageName, String linuxPackage,
                boolean brewCask, List<String> probe, String hint) {
            this.id = id;
            this.name = name;
            this.packageName = packageName;
            this.linuxPackage = linuxPackage;
            this.brewCask = brewCask;
            this.probe = new ArrayList<String>(probe);
            this.hint = hint;
        }
    }

    private static final class PackageManager {
        static final PackageManager NONE = new PackageManager("なし", "", false);
        final String label;
        final String command;
        final boolean unused;
        PackageManager(String label, String command, boolean unused) {
            this.label = label;
            this.command = command;
            this.unused = unused;
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
