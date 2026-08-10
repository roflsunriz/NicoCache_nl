package nicocache.launcher;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Entry point for the cross-platform NicoCache_nl process manager. */
public final class LauncherMain {
    private LauncherMain() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            LauncherOptions options = LauncherOptions.parse(args);
            if (options.getAction() == LauncherOptions.Action.HELP) {
                printUsage();
                return;
            }
            if (options.getAction() == LauncherOptions.Action.GUI) {
                if (GraphicsEnvironment.isHeadless()) {
                    throw new IllegalStateException(
                            "画面のない環境では --headless を指定してください");
                }
                startGui(options);
                return;
            }
            exitCode = runHeadless(options);
        } catch (Exception error) {
            System.err.println("NicoCacheLauncher: "
                    + (error.getMessage() == null
                    ? error.toString() : error.getMessage()));
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void startGui(LauncherOptions options) {
        ResourceBundle messages = messages();
        SwingUtilities.invokeLater(() -> {
            try {
                LauncherPaths paths = LauncherPaths.resolve(
                        options.getApplicationRoot(), options.getDataRoot());
                if (!Files.isRegularFile(paths.getApplicationRoot()
                        .resolve("config.properties"))) {
                    if (!LauncherSetupDialog.showAndApply(paths, messages)) {
                        return;
                    }
                    paths = LauncherPaths.resolve(
                            options.getApplicationRoot(), options.getDataRoot());
                }
                new LauncherWindow(paths, messages).show(
                        options.getWindowMode(), options.shouldStartCore());
            } catch (Exception error) {
                JOptionPane.showMessageDialog(null,
                        error.getMessage() == null ? error.toString()
                                : error.getMessage(),
                        messages.getString("error.title"),
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static int runHeadless(LauncherOptions options) throws Exception {
        if (options.getAction() == LauncherOptions.Action.SETUP) {
            LauncherPaths paths = LauncherPaths.resolve(
                    options.getApplicationRoot(), options.getDataRoot());
            List<String> setupArguments = options.getSetupArguments();
            if (!setupArguments.contains("--headless")) {
                setupArguments = new java.util.ArrayList<>(setupArguments);
                setupArguments.add("--headless");
            }
            return new CoreProcess(paths).runSetup(setupArguments, true);
        }
        LauncherPaths paths = LauncherPaths.resolve(
                options.getApplicationRoot(), options.getDataRoot());
        CoreProcess core = new CoreProcess(paths);
        switch (options.getAction()) {
        case FOREGROUND:
            return core.startAndWait();
        case START:
            core.startHeadless(false);
            System.out.println("NicoCache_nl started");
            return 0;
        case STOP:
            core.gracefulStop();
            System.out.println("NicoCache_nl core stopped gracefully; "
                    + "resident launcher and diagnostics unchanged");
            return 0;
        case FORCE_STOP:
            core.forceStop();
            System.out.println("NicoCache_nl core force-stop requested; "
                    + "resident launcher and diagnostics unchanged");
            return 0;
        case STATUS:
            printStatus(paths);
            return 0;
        case CHECK_DATA_ROOT:
            return printDataRootInspection(paths, messages());
        case TASK_LIST:
            for (TaskDefinition task : new TaskScheduler(paths).list()) {
                System.out.println(task);
            }
            return 0;
        case TASK_INSTALL:
            new TaskScheduler(paths).install(new TaskDefinition(
                    options.getTaskName(), true));
            System.out.println("task installed: " + options.getTaskName());
            return 0;
        case TASK_REMOVE:
            return removeTask(paths, options.getTaskName());
        case TASK_UPDATE:
            return updateTask(paths, options);
        default:
            throw new IllegalArgumentException(
                    "headless 操作が不正です: " + options.getAction());
        }
    }

    private static int removeTask(LauncherPaths paths, String name)
            throws Exception {
        TaskScheduler scheduler = new TaskScheduler(paths);
        for (TaskDefinition task : scheduler.list()) {
            if (task.getName().equals(name)) {
                scheduler.remove(task);
                System.out.println("task removed: " + name);
                return 0;
            }
        }
        throw new IllegalArgumentException("タスクが見つかりません: " + name);
    }

    private static int updateTask(LauncherPaths paths, LauncherOptions options)
            throws Exception {
        TaskScheduler scheduler = new TaskScheduler(paths);
        for (TaskDefinition oldTask : scheduler.list()) {
            if (oldTask.getName().equals(options.getTaskName())) {
                TaskDefinition newTask = new TaskDefinition(
                        options.getTaskName(), true);
                scheduler.update(oldTask, newTask);
                System.out.println("task updated: " + options.getTaskName());
                return 0;
            }
        }
        throw new IllegalArgumentException("タスクが見つかりません: "
                + options.getTaskName());
    }

    private static void printStatus(LauncherPaths paths) throws Exception {
        Properties status = ControlClient.readStatus(paths.getControlStatusFile());
        System.out.println("state=" + status.getProperty("state", "unknown"));
        System.out.println("pid=" + status.getProperty("pid"));
        System.out.println("host=" + status.getProperty("host"));
        System.out.println("port=" + status.getProperty("port"));
        System.out.println("proxyPort=" + status.getProperty("proxyPort", "8080"));
        String problem = status.getProperty("problem");
        if (problem != null && !problem.isBlank()) {
            System.out.println("problem=" + problem);
        }
        System.out.println("version=" + status.getProperty("version"));
    }

    private static int printDataRootInspection(LauncherPaths paths,
            ResourceBundle messages) {
        DataRootInspection inspection = DataRootInspector.inspect(
                paths.getApplicationRoot(), paths.getDataRoot());
        System.out.print(DataRootInspectionFormatter.details(
                inspection, messages));
        return inspection.getExitCode();
    }

    private static ResourceBundle messages() {
        return ResourceBundle.getBundle("nicocache.launcher.messages",
                Locale.getDefault());
    }

    private static void printUsage() {
        System.out.println("NicoCacheLauncher");
        System.out.println("Usage: java -jar NicoCacheLauncher.jar [options]");
        System.out.println("  --start / --stop / --force-stop / --status");
        System.out.println("    core stop only; resident launcher and "
                + "diagnostics continue");
        System.out.println("  --tray [--start]  タスクトレイで起動");
        System.out.println("  --minimized [--start]  最小化して起動");
        System.out.println("  --check-data-root  ユーザーデータルートを診断");
        System.out.println("  --headless [--start]  起動して待機（--startは非同期）");
        System.out.println("  --task-list / --task-install / --task-update / --task-remove");
        System.out.println("  --task-name=<name>  (ログオン時に1回だけ実行)");
        System.out.println("  --setup --headless <初回セットアップ引数>");
    }
}
