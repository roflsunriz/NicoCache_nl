package nicocache.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class LauncherOptions {
    enum WindowMode {
        NORMAL,
        MINIMIZED,
        TRAY
    }

    enum Action {
        GUI,
        FOREGROUND,
        START,
        STOP,
        FORCE_STOP,
        STATUS,
        CHECK_DATA_ROOT,
        TASK_LIST,
        TASK_INSTALL,
        TASK_REMOVE,
        TASK_UPDATE,
        SETUP,
        HELP
    }

    private final boolean headless;
    private final Action action;
    private final Path applicationRoot;
    private final Path dataRoot;
    private final String taskName;
    private final List<String> setupArguments;
    private final WindowMode windowMode;
    private final boolean startCore;

    private LauncherOptions(boolean headless, Action action,
            Path applicationRoot, Path dataRoot, String taskName,
            List<String> setupArguments, WindowMode windowMode,
            boolean startCore) {
        this.headless = headless;
        this.action = action;
        this.applicationRoot = applicationRoot;
        this.dataRoot = dataRoot;
        this.taskName = taskName;
        this.setupArguments = List.copyOf(setupArguments);
        this.windowMode = windowMode;
        this.startCore = startCore;
    }

    static LauncherOptions parse(String[] args) {
        boolean headless = false;
        boolean setup = false;
        boolean startRequested = false;
        boolean windowModeExplicit = false;
        Action selected = null;
        WindowMode windowMode = WindowMode.NORMAL;
        Path applicationRoot = null;
        Path dataRoot = null;
        String taskName = "NicoCache_nl";
        List<String> setupArguments = new ArrayList<>();

        for (String arg : args) {
            if ("--headless".equals(arg)) {
                headless = true;
            } else if ("--tray".equals(arg)) {
                windowMode = selectWindowMode(windowMode, windowModeExplicit,
                        WindowMode.TRAY);
                windowModeExplicit = true;
            } else if ("--minimized".equals(arg)) {
                windowMode = selectWindowMode(windowMode, windowModeExplicit,
                        WindowMode.MINIMIZED);
                windowModeExplicit = true;
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                selected = select(selected, Action.HELP);
            } else if ("--setup".equals(arg)) {
                setup = true;
                setupArguments.add(arg);
            } else if (arg.startsWith("--app-root=")) {
                applicationRoot = pathValue(arg, "--app-root=");
            } else if (arg.startsWith("--data-root=")) {
                dataRoot = pathValue(arg, "--data-root=");
            } else if ("--start".equals(arg)) {
                startRequested = true;
            } else if ("--stop".equals(arg) || "--graceful-stop".equals(arg)) {
                selected = select(selected, Action.STOP);
            } else if ("--force-stop".equals(arg)) {
                selected = select(selected, Action.FORCE_STOP);
            } else if ("--status".equals(arg)) {
                selected = select(selected, Action.STATUS);
            } else if ("--check-data-root".equals(arg)
                    || "--diagnose-data-root".equals(arg)) {
                selected = select(selected, Action.CHECK_DATA_ROOT);
            } else if ("--task-list".equals(arg)) {
                selected = select(selected, Action.TASK_LIST);
            } else if ("--task-install".equals(arg)) {
                selected = select(selected, Action.TASK_INSTALL);
            } else if ("--task-remove".equals(arg)) {
                selected = select(selected, Action.TASK_REMOVE);
            } else if ("--task-update".equals(arg)) {
                selected = select(selected, Action.TASK_UPDATE);
            } else if (arg.startsWith("--task-name=")) {
                taskName = requiredValue(arg, "--task-name=");
            } else if (arg.startsWith("--user-data-root=")
                    || arg.startsWith("--https=")
                    || arg.startsWith("--trust-certificate=")
                    || arg.startsWith("--proxy=")
                    || arg.startsWith("--autostart=")) {
                setup = true;
                setupArguments.add(arg);
                if (arg.startsWith("--user-data-root=")) {
                    dataRoot = pathValue(arg, "--user-data-root=");
                }
            } else if (setup) {
                setupArguments.add(arg);
            } else {
                throw new IllegalArgumentException("不明なオプションです: " + arg);
            }
        }

        if (setup) {
            selected = select(selected, Action.SETUP);
            if (!headless) {
                throw new IllegalArgumentException(
                        "--setup は --headless と同時に指定してください");
            }
            if (!setupArguments.contains("--setup")) {
                setupArguments.add(0, "--setup");
            }
        }
        if (selected == Action.HELP) {
            return new LauncherOptions(headless, selected, applicationRoot,
                    dataRoot, taskName, setupArguments, windowMode, false);
        }
        if (windowModeExplicit) {
            if (headless) {
                throw new IllegalArgumentException(
                        "--tray と --minimized は --headless と同時に指定できません");
            }
            if (selected != null) {
                throw new IllegalArgumentException(
                        "表示モードと別の操作を同時に指定できません: " + selected);
            }
            selected = Action.GUI;
        } else if (startRequested) {
            selected = select(selected, Action.START);
        }
        if (selected == null) {
            selected = headless ? Action.FOREGROUND : Action.GUI;
        }
        if (!setup && selected == Action.SETUP) {
            throw new IllegalArgumentException("--setup が必要です");
        }
        if (taskName.isBlank()) {
            throw new IllegalArgumentException("--task-name は空にできません");
        }
        return new LauncherOptions(headless, selected, applicationRoot,
                dataRoot, taskName, setupArguments, windowMode,
                selected == Action.GUI && startRequested);
    }

    private static WindowMode selectWindowMode(WindowMode current,
            boolean explicit, WindowMode next) {
        if (explicit && current != next) {
            throw new IllegalArgumentException(
                    "表示モードを複数指定できません: " + current + " / " + next);
        }
        return next;
    }

    private static Action select(Action current, Action next) {
        if (current != null && current != next && current != Action.HELP) {
            throw new IllegalArgumentException(
                    "操作を複数指定できません: " + current + " / " + next);
        }
        return next;
    }

    private static String requiredValue(String argument, String prefix) {
        String value = argument.substring(prefix.length()).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(prefix + " の値がありません");
        }
        return value;
    }

    private static Path pathValue(String argument, String prefix) {
        try {
            return Path.of(requiredValue(argument, prefix))
                    .toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException error) {
            throw new IllegalArgumentException("パスが不正です: " + argument, error);
        }
    }

    boolean isHeadless() {
        return headless;
    }

    Action getAction() {
        return action;
    }

    Path getApplicationRoot() {
        return applicationRoot;
    }

    Path getDataRoot() {
        return dataRoot;
    }

    String getTaskName() {
        return taskName;
    }

    List<String> getSetupArguments() {
        return setupArguments;
    }

    WindowMode getWindowMode() {
        return windowMode;
    }

    boolean shouldStartCore() {
        return startCore;
    }
}
