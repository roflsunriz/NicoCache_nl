package nicocache.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

final class TaskScheduler {
    private final LauncherPaths paths;

    TaskScheduler(LauncherPaths paths) {
        this.paths = paths;
    }

    List<TaskDefinition> list() throws IOException {
        Path store = paths.getTaskStore();
        if (!Files.isRegularFile(store)) {
            return new ArrayList<>();
        }
        Properties properties = new Properties();
        try (var input = Files.newInputStream(store)) {
            properties.load(input);
        }
        boolean migrate = !"2".equals(properties.getProperty("version"));
        int count;
        try {
            count = Integer.parseInt(properties.getProperty("count", "0"));
        } catch (NumberFormatException error) {
            throw new IOException("タスク一覧の件数が不正です: " + store, error);
        }
        if (count < 0 || count > 100) {
            throw new IOException("タスク一覧の件数が不正です: " + count);
        }
        List<TaskDefinition> tasks = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String prefix = "task." + index + ".";
            try {
                migrate |= TaskDefinition.needsMigration(properties, prefix);
                tasks.add(TaskDefinition.fromProperties(properties, prefix));
            } catch (IllegalArgumentException error) {
                throw new IOException("タスク一覧を読み取れません: " + prefix,
                        error);
            }
        }
        tasks.sort(Comparator.comparing(TaskDefinition::getName,
                String.CASE_INSENSITIVE_ORDER));
        if (migrate) {
            migrateLegacyTasks(tasks);
        }
        return tasks;
    }

    void install(TaskDefinition task) throws IOException {
        List<TaskDefinition> tasks = list();
        removeNative(task);
        installNative(task);
        tasks.removeIf(existing -> existing.getId().equals(task.getId()));
        tasks.add(task);
        save(tasks);
    }

    void update(TaskDefinition oldTask, TaskDefinition newTask)
            throws IOException {
        List<TaskDefinition> tasks = list();
        removeNative(oldTask);
        try {
            installNative(newTask);
        } catch (IOException error) {
            try {
                installNative(oldTask);
            } catch (IOException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw error;
        }
        tasks.removeIf(existing -> existing.getId().equals(oldTask.getId())
                || existing.getId().equals(newTask.getId()));
        tasks.add(newTask);
        save(tasks);
    }

    void remove(TaskDefinition task) throws IOException {
        List<TaskDefinition> tasks = list();
        removeNative(task);
        tasks.removeIf(existing -> existing.getId().equals(task.getId()));
        save(tasks);
    }

    private void migrateLegacyTasks(List<TaskDefinition> tasks)
            throws IOException {
        for (TaskDefinition task : tasks) {
            removeNative(task);
        }
        save(tasks);
        for (TaskDefinition task : tasks) {
            installNative(task);
        }
    }

    private void save(List<TaskDefinition> tasks) throws IOException {
        Path store = paths.getTaskStore();
        Path parent = store.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties properties = new Properties();
        properties.setProperty("version", "2");
        properties.setProperty("count", Integer.toString(tasks.size()));
        for (int index = 0; index < tasks.size(); index++) {
            tasks.get(index).writeProperties(properties, "task." + index + ".");
        }
        Path temporary = store.resolveSibling(store.getFileName() + ".tmp");
        try (var output = Files.newOutputStream(temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, "NicoCache_nl launcher tasks");
        }
        try {
            Files.move(temporary, store, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, store, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void installNative(TaskDefinition task) throws IOException {
        if (!task.isEnabled()) {
            return;
        }
        switch (paths.getPlatform()) {
        case WINDOWS:
            installWindows(task);
            return;
        case MACOS:
            installMacos(task);
            return;
        case LINUX:
            installLinux(task);
            return;
        default:
            throw new IOException("このOSのタスクスケジューラには対応していません");
        }
    }

    private void removeNative(TaskDefinition task) throws IOException {
        switch (paths.getPlatform()) {
        case WINDOWS:
            runOptional("schtasks", "/Delete", "/TN", windowsTaskName(task), "/F");
            return;
        case MACOS:
            Path plist = macosDirectory().resolve(task.getId() + ".plist");
            runOptional("launchctl", "bootout", "gui/" + userId(), plist.toString());
            Files.deleteIfExists(plist);
            return;
        case LINUX:
            Path autostart = linuxAutostartDirectory()
                    .resolve(task.getId() + ".desktop");
            Path service = linuxSystemdDirectory()
                    .resolve(task.getId() + ".service");
            Path timer = linuxSystemdDirectory()
                    .resolve(task.getId() + ".timer");
            runOptional("systemctl", "--user", "disable", "--now",
                    task.getId() + ".timer");
            Files.deleteIfExists(autostart);
            Files.deleteIfExists(service);
            Files.deleteIfExists(timer);
            runOptional("systemctl", "--user", "daemon-reload");
            return;
        default:
            throw new IOException("このOSのタスクスケジューラには対応していません");
        }
    }

    private void installWindows(TaskDefinition task) throws IOException {
        List<String> arguments = new ArrayList<>();
        arguments.add("/Create");
        arguments.add("/TN");
        arguments.add(windowsTaskName(task));
        arguments.add("/SC");
        arguments.add("ONLOGON");
        arguments.add("/TR");
        arguments.add(renderWindowsCommand());
        arguments.add("/RL");
        arguments.add("LIMITED");
        arguments.add("/F");
        runChecked("schtasks", arguments);
    }

    private void installMacos(TaskDefinition task) throws IOException {
        Path directory = macosDirectory();
        Files.createDirectories(directory);
        Path plist = directory.resolve(task.getId() + ".plist");
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\"")
                .append(" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
                .append("<plist version=\"1.0\"><dict>\n")
                .append("<key>Label</key><string>")
                .append(xml(task.getId())).append("</string>\n")
                .append("<key>ProgramArguments</key><array>\n");
        for (String argument : paths.getTaskCommand()) {
            xml.append("<string>").append(xml(argument)).append("</string>\n");
        }
        xml.append("</array>\n");
        xml.append("<key>RunAtLoad</key><true/>\n");
        xml.append("</dict></plist>\n");
        Files.writeString(plist, xml, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        runOptional("launchctl", "bootstrap", "gui/" + userId(),
                plist.toString());
    }

    private void installLinux(TaskDefinition task) throws IOException {
        Path directory = linuxAutostartDirectory();
        Files.createDirectories(directory);
        StringBuilder desktop = new StringBuilder();
        desktop.append("[Desktop Entry]\nType=Application\n")
                .append("Name=NicoCache_nl\nX-GNOME-Autostart-enabled=true\n")
                .append("Exec=").append(renderDesktopCommand()).append("\n");
        Files.writeString(directory.resolve(task.getId() + ".desktop"), desktop,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private String windowsTaskName(TaskDefinition task) {
        return "\\NicoCache_nl\\" + task.getId();
    }

    private String renderWindowsCommand() {
        return paths.getTaskCommand().stream()
                .map(TaskScheduler::windowsQuote)
                .collect(Collectors.joining(" "));
    }

    private String renderDesktopCommand() {
        return paths.getTaskCommand().stream()
                .map(TaskScheduler::desktopQuote)
                .collect(Collectors.joining(" "));
    }

    private Path macosDirectory() {
        return Path.of(System.getProperty("user.home"), "Library/LaunchAgents");
    }

    private Path linuxAutostartDirectory() {
        String config = System.getenv("XDG_CONFIG_HOME");
        Path root = config == null || config.isBlank()
                ? Path.of(System.getProperty("user.home"), ".config")
                : Path.of(config);
        return root.resolve("autostart");
    }

    private Path linuxSystemdDirectory() {
        String config = System.getenv("XDG_CONFIG_HOME");
        Path root = config == null || config.isBlank()
                ? Path.of(System.getProperty("user.home"), ".config")
                : Path.of(config);
        return root.resolve("systemd/user");
    }

    private String userId() {
        try {
            CommandResult result = run("id", List.of("-u"), true);
            String value = result.output.trim();
            return value.isEmpty() ? "0" : value;
        } catch (IOException error) {
            return "0";
        }
    }

    private void runChecked(String command, List<String> arguments)
            throws IOException {
        CommandResult result = run(command, arguments, true);
        if (result.exitCode != 0) {
            throw new IOException(command + " に失敗しました (ExitCode: "
                    + result.exitCode + ")"
                    + (result.output.isBlank() ? "" : ": " + result.output.trim()));
        }
    }

    private void runOptional(String command, String... arguments) {
        try {
            run(command, List.of(arguments), true);
        } catch (IOException ignored) {
            // Removing an already absent task and unavailable session loaders
            // are both safe after the native file has been updated.
        }
    }

    private CommandResult run(String command, List<String> arguments,
            boolean waitForExit) throws IOException {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(arguments);
        Process process;
        try {
            process = new ProcessBuilder(commandLine)
                    .redirectErrorStream(true).start();
        } catch (IOException error) {
            throw new IOException("OSのタスク管理コマンドを起動できません: "
                    + command, error);
        }
        if (!waitForExit) {
            return new CommandResult(0, "");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copy(process.getInputStream(), output),
                "nicocache-task-command-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("OSのタスク管理コマンドが時間内に終了しませんでした: "
                        + command);
            }
            reader.join(5000L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("OSのタスク管理コマンドが中断されました: "
                    + command, error);
        }
        return new CommandResult(process.exitValue(),
                output.toString(StandardCharsets.UTF_8));
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        try (InputStream source = input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
            // The process exit code remains authoritative.
        }
    }

    private static String windowsQuote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static String desktopQuote(String value) {
        if (value.matches("[A-Za-z0-9_./:=+-]+")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
