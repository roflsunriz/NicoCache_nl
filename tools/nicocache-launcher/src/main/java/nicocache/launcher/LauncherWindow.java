package nicocache.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.text.MessageFormat;
import java.util.List;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

final class LauncherWindow {
    private final LauncherPaths paths;
    private final CoreProcess core;
    private final DiagnosticsProcess diagnostics;
    private final LauncherLifecycle lifecycle;
    private final TaskScheduler scheduler;
    private final ResourceBundle messages;
    private final JFrame frame = new JFrame();
    private final JLabel statusLabel = new JLabel();
    private final JLabel dataRootStatusLabel = new JLabel();
    private final DefaultListModel<TaskDefinition> taskModel =
            new DefaultListModel<>();
    private final JList<TaskDefinition> taskList = new JList<>(taskModel);
    private final AtomicBoolean closing = new AtomicBoolean();
    private TrayIcon trayIcon;
    private Timer statusTimer;

    LauncherWindow(LauncherPaths paths, ResourceBundle messages) {
        this.paths = paths;
        this.core = new CoreProcess(paths);
        this.diagnostics = new DiagnosticsProcess(paths);
        this.lifecycle = new LauncherLifecycle(core::gracefulStop,
                core::forceStop, this::closeLauncher);
        this.scheduler = new TaskScheduler(paths);
        this.messages = messages;
        buildWindow();
        createTray();
        runAsync(diagnostics::startIfNeeded);
        refreshDataRoot();
        refreshTasks();
        statusTimer = new Timer(1000, event -> refreshStatus());
        statusTimer.start();
    }

    void show(LauncherOptions.WindowMode mode, boolean startCore) {
        switch (mode) {
        case TRAY:
            if (trayIcon == null) {
                showMinimized();
            } else {
                frame.setVisible(false);
            }
            break;
        case MINIMIZED:
            showMinimized();
            break;
        case NORMAL:
        default:
            frame.setExtendedState(frame.getExtendedState() & ~JFrame.ICONIFIED);
            frame.setVisible(true);
            break;
        }
        if (startCore) {
            startCoreAsync();
        }
    }

    private void showMinimized() {
        frame.setExtendedState(frame.getExtendedState() | JFrame.ICONIFIED);
        frame.setVisible(true);
    }

    private void buildWindow() {
        frame.setTitle(messages.getString("window.title"));
        frame.setMinimumSize(new Dimension(600, 440));
        frame.setSize(720, 540);
        frame.setLocationByPlatform(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setIconImage(createIcon(32));
        frame.setContentPane(createContent());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (trayIcon != null) {
                    frame.setVisible(false);
                } else {
                    requestLauncherExit();
                }
            }
        });
    }

    private JPanel createContent() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel(new BorderLayout(8, 8));
        statusLabel.setName("launcher.status");
        top.add(statusLabel, BorderLayout.CENTER);
        JPanel topButtons = new JPanel(new GridLayout(1, 0, 6, 6));
        JButton refresh = new JButton(messages.getString("button.refresh"));
        refresh.setName("launcher.refresh");
        refresh.addActionListener(event -> refreshStatus());
        topButtons.add(refresh);
        JButton dataRootCheck = new JButton(
                messages.getString("button.checkDataRoot"));
        dataRootCheck.setName("launcher.data-root.check");
        dataRootCheck.addActionListener(event -> showDataRootInspection());
        topButtons.add(dataRootCheck);
        top.add(topButtons, BorderLayout.EAST);
        content.add(top, BorderLayout.NORTH);

        JPanel controls = new JPanel(new GridLayout(1, 0, 6, 6));
        controls.setBorder(BorderFactory.createTitledBorder(
                messages.getString("core.title")));
        addControlButton(controls, "button.start", "launcher.start",
                this::startCoreAsync);
        JButton stop = addControlButton(controls, "button.stop",
                "launcher.stop", () -> runAsync(
                        lifecycle::gracefulStopCore));
        stop.setToolTipText(messages.getString("button.stop.tooltip"));
        JButton forceStop = addControlButton(controls, "button.forceStop",
                "launcher.forceStop", () -> runAsync(
                        lifecycle::forceStopCore));
        forceStop.setToolTipText(messages.getString(
                "button.forceStop.tooltip"));

        JPanel tasks = new JPanel(new BorderLayout(6, 6));
        tasks.setBorder(BorderFactory.createTitledBorder(
                messages.getString("tasks.title")));
        taskList.setName("launcher.tasks");
        taskList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tasks.add(new JScrollPane(taskList), BorderLayout.CENTER);
        JPanel taskButtons = new JPanel(new GridLayout(1, 0, 6, 6));
        addControlButton(taskButtons, "button.add", "launcher.task.add",
                this::addTask);
        addControlButton(taskButtons, "button.edit", "launcher.task.edit",
                this::editTask);
        addControlButton(taskButtons, "button.remove", "launcher.task.remove",
                this::removeTask);
        tasks.add(taskButtons, BorderLayout.SOUTH);

        JPanel dataRootPanel = new JPanel(new BorderLayout(6, 6));
        dataRootPanel.setBorder(BorderFactory.createTitledBorder(
                messages.getString("dataRoot.title")));
        dataRootStatusLabel.setName("launcher.data-root");
        dataRootPanel.add(dataRootStatusLabel, BorderLayout.CENTER);

        JPanel lifecyclePanel = new JPanel(new BorderLayout(6, 6));
        lifecyclePanel.setBorder(BorderFactory.createTitledBorder(
                messages.getString("lifecycle.title")));
        JLabel lifecycleDescription = new JLabel(
                messages.getString("lifecycle.description"));
        lifecycleDescription.setName("launcher.lifecycle.description");
        lifecyclePanel.add(lifecycleDescription, BorderLayout.CENTER);
        JButton exitLauncher = addControlButton(lifecyclePanel,
                "button.exitLauncher", "launcher.exit",
                this::requestLauncherExit, BorderLayout.EAST);
        exitLauncher.setToolTipText(messages.getString(
                "button.exitLauncher.tooltip"));

        JPanel upper = new JPanel(new BorderLayout(8, 8));
        upper.add(controls, BorderLayout.NORTH);
        upper.add(dataRootPanel, BorderLayout.CENTER);
        upper.add(lifecyclePanel, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(upper, BorderLayout.NORTH);
        center.add(tasks, BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);

        JLabel pathLabel = new JLabel("<html>" + paths.getApplicationRoot()
                + "<br>" + paths.getDataRoot() + "</html>");
        pathLabel.setName("launcher.paths");
        pathLabel.setForeground(Color.DARK_GRAY);
        content.add(pathLabel, BorderLayout.SOUTH);
        return content;
    }

    private JButton addControlButton(JPanel parent, String labelKey, String name,
            Runnable action) {
        JButton button = new JButton(messages.getString(labelKey));
        button.setName(name);
        button.addActionListener(event -> action.run());
        parent.add(button);
        return button;
    }

    private JButton addControlButton(JPanel parent, String labelKey,
            String name, Runnable action, Object constraints) {
        JButton button = new JButton(messages.getString(labelKey));
        button.setName(name);
        button.addActionListener(event -> action.run());
        parent.add(button, constraints);
        return button;
    }

    private void startCoreAsync() {
        runAsync(core::startGui);
    }

    private void refreshStatus() {
        refreshDataRoot();
        Properties status = ControlClient.readStatusIfPresent(
                paths.getControlStatusFile());
        if (!ControlClient.isAlive(status)) {
            statusLabel.setText(messages.getString("status.stopped"));
            return;
        }
        if ("degraded".equals(status.getProperty("state"))) {
            statusLabel.setText(MessageFormat.format(
                    messages.getString("status.degraded"),
                    status.getProperty("problem", "unknown")));
            return;
        }
        if (!"running".equals(status.getProperty("state"))) {
            statusLabel.setText(MessageFormat.format(
                    messages.getString("status.starting"),
                    status.getProperty("state", "unknown")));
            return;
        }
        try {
            HttpResponse<String> response = ControlClient.getStatus(paths);
            statusLabel.setText(MessageFormat.format(
                    messages.getString("status.running"),
                    response.statusCode()));
        } catch (Exception error) {
            statusLabel.setText(messages.getString("status.unreachable"));
        }
    }

    private void refreshDataRoot() {
        DataRootInspection inspection = DataRootInspector.inspect(
                paths.getApplicationRoot(), paths.getDataRoot());
        dataRootStatusLabel.setText(
                DataRootInspectionFormatter.summary(inspection, messages));
        switch (inspection.getState()) {
        case BLOCKED:
            dataRootStatusLabel.setForeground(new Color(170, 0, 0));
            break;
        case ATTENTION:
            dataRootStatusLabel.setForeground(new Color(145, 90, 0));
            break;
        case COMPLETE:
        default:
            dataRootStatusLabel.setForeground(new Color(0, 105, 45));
            break;
        }
    }

    private void showDataRootInspection() {
        DataRootInspection inspection = DataRootInspector.inspect(
                paths.getApplicationRoot(), paths.getDataRoot());
        JTextArea details = new JTextArea(
                DataRootInspectionFormatter.details(inspection, messages));
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(details);
        scroll.setPreferredSize(new Dimension(820, 420));
        JOptionPane.showMessageDialog(frame, scroll,
                messages.getString("dataRoot.dialog.title"),
                inspection.getState() == DataRootInspection.OverallState.BLOCKED
                        ? JOptionPane.ERROR_MESSAGE
                        : JOptionPane.INFORMATION_MESSAGE);
    }

    private void refreshTasks() {
        try {
            taskModel.clear();
            for (TaskDefinition task : scheduler.list()) {
                taskModel.addElement(task);
            }
        } catch (IOException error) {
            showError(error);
        }
    }

    private void addTask() {
        TaskDefinition task = editTaskDialog(null);
        if (task == null) {
            return;
        }
        runAsync(() -> {
            scheduler.install(task);
            SwingUtilities.invokeLater(this::refreshTasks);
        });
    }

    private void editTask() {
        TaskDefinition oldTask = taskList.getSelectedValue();
        if (oldTask == null) {
            return;
        }
        TaskDefinition newTask = editTaskDialog(oldTask);
        if (newTask == null) {
            return;
        }
        runAsync(() -> {
            scheduler.update(oldTask, newTask);
            SwingUtilities.invokeLater(this::refreshTasks);
        });
    }

    private void removeTask() {
        TaskDefinition task = taskList.getSelectedValue();
        if (task == null) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(frame,
                messages.getString("tasks.remove.confirm"),
                messages.getString("tasks.remove.title"),
                JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        runAsync(() -> {
            scheduler.remove(task);
            SwingUtilities.invokeLater(this::refreshTasks);
        });
    }

    private TaskDefinition editTaskDialog(TaskDefinition current) {
        JTextField name = new JTextField(current == null
                ? "NicoCache_nl" : current.getName(), 24);
        JPanel panel = new JPanel(new GridLayout(0, 2, 6, 6));
        panel.add(new JLabel(messages.getString("tasks.name")));
        panel.add(name);
        panel.add(new JLabel(messages.getString("tasks.trigger")));
        panel.add(new JLabel(messages.getString("tasks.onLogon")));
        int result = JOptionPane.showConfirmDialog(frame, panel,
                messages.getString(current == null
                        ? "tasks.add.title" : "tasks.edit.title"),
                JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        try {
            return new TaskDefinition(name.getText(), true);
        } catch (IllegalArgumentException error) {
            showError(error);
            return null;
        }
    }

    private void runAsync(ThrowingAction action) {
        Thread thread = new Thread(() -> {
            try {
                action.run();
                SwingUtilities.invokeLater(this::refreshStatus);
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> showError(error));
            }
        }, "nicocache-launcher-action");
        thread.setDaemon(true);
        thread.start();
    }

    private void showError(Exception error) {
        JOptionPane.showMessageDialog(frame,
                error.getMessage() == null ? error.toString() : error.getMessage(),
                messages.getString("error.title"),
                JOptionPane.ERROR_MESSAGE);
    }

    private void createTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();
            addTrayItem(menu, "tray.show", () -> {
                frame.setVisible(true);
                frame.toFront();
            });
            addTrayItem(menu, "tray.start", this::startCoreAsync);
            addTrayItem(menu, "tray.stop", () -> runAsync(
                    lifecycle::gracefulStopCore));
            menu.addSeparator();
            addTrayItem(menu, "tray.exit", this::requestLauncherExit);
            trayIcon = new TrayIcon(createIcon(16),
                    messages.getString("window.title"), menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(event -> {
                frame.setVisible(true);
                frame.toFront();
            });
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception error) {
            trayIcon = null;
        }
    }

    private void addTrayItem(PopupMenu menu, String labelKey, Runnable action) {
        MenuItem item = new MenuItem(messages.getString(labelKey));
        item.addActionListener(event -> action.run());
        menu.add(item);
    }

    private void requestLauncherExit() {
        int result = JOptionPane.showConfirmDialog(frame,
                messages.getString("launcher.exit.confirm"),
                messages.getString("launcher.exit.title"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            lifecycle.exitLauncher();
        }
    }

    private void closeLauncher() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        if (statusTimer != null) {
            statusTimer.stop();
        }
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
        frame.dispose();
    }

    private Image createIcon(int size) {
        BufferedImage image = new BufferedImage(size, size,
                BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(13, 25, 101));
        graphics.fillRoundRect(1, 1, size - 2, size - 2, size / 3, size / 3);
        graphics.setColor(new Color(142, 164, 255));
        int left = Math.max(3, size / 4);
        int right = Math.max(left + 1, size - Math.max(3, size / 4));
        for (int row = 1; row <= 3; row++) {
            int y = Math.max(2, row * size / 4);
            graphics.fillRoundRect(left, y - 1, right - left, 2,
                    Math.max(1, size / 16), Math.max(1, size / 16));
        }
        graphics.setColor(Color.WHITE);
        int dot = Math.max(2, size / 6);
        for (int row = 1; row <= 3; row++) {
            int y = row * size / 4 - dot / 2;
            graphics.fillOval(left - dot / 2, y, dot, dot);
        }
        graphics.setColor(new Color(255, 194, 49));
        int block = Math.max(2, size / 6);
        for (int row = 1; row <= 3; row++) {
            int y = row * size / 4 - block / 2;
            graphics.fillRoundRect(right - block / 2, y, block, block,
                    Math.max(1, size / 12), Math.max(1, size / 12));
        }
        graphics.dispose();
        return image;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
