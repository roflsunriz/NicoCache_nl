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
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

final class LauncherWindow {
    private final LauncherPaths paths;
    private final CoreProcess core;
    private final TaskScheduler scheduler;
    private final ResourceBundle messages;
    private final JFrame frame = new JFrame();
    private final JLabel statusLabel = new JLabel();
    private final DefaultListModel<TaskDefinition> taskModel =
            new DefaultListModel<>();
    private final JList<TaskDefinition> taskList = new JList<>(taskModel);
    private final AtomicBoolean closing = new AtomicBoolean();
    private TrayIcon trayIcon;
    private Timer statusTimer;

    LauncherWindow(LauncherPaths paths, ResourceBundle messages) {
        this.paths = paths;
        this.core = new CoreProcess(paths);
        this.scheduler = new TaskScheduler(paths);
        this.messages = messages;
        buildWindow();
        createTray();
        refreshTasks();
        startCoreAsync();
        statusTimer = new Timer(1000, event -> refreshStatus());
        statusTimer.start();
    }

    void show() {
        frame.setVisible(true);
    }

    private void buildWindow() {
        frame.setTitle(messages.getString("window.title"));
        frame.setMinimumSize(new Dimension(540, 360));
        frame.setSize(700, 480);
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
                    exitApplication();
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
        JButton refresh = new JButton(messages.getString("button.refresh"));
        refresh.setName("launcher.refresh");
        refresh.addActionListener(event -> refreshStatus());
        top.add(refresh, BorderLayout.EAST);
        content.add(top, BorderLayout.NORTH);

        JPanel controls = new JPanel(new GridLayout(1, 0, 6, 6));
        addControlButton(controls, "button.start", "launcher.start",
                this::startCoreAsync);
        addControlButton(controls, "button.stop", "launcher.stop",
                () -> runAsync(() -> core.gracefulStop()));
        addControlButton(controls, "button.forceStop", "launcher.forceStop",
                () -> runAsync(() -> core.forceStop()));

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

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(controls, BorderLayout.NORTH);
        center.add(tasks, BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);

        JLabel pathLabel = new JLabel("<html>" + paths.getApplicationRoot()
                + "<br>" + paths.getDataRoot() + "</html>");
        pathLabel.setName("launcher.paths");
        pathLabel.setForeground(Color.DARK_GRAY);
        content.add(pathLabel, BorderLayout.SOUTH);
        return content;
    }

    private void addControlButton(JPanel parent, String labelKey, String name,
            Runnable action) {
        JButton button = new JButton(messages.getString(labelKey));
        button.setName(name);
        button.addActionListener(event -> action.run());
        parent.add(button);
    }

    private void startCoreAsync() {
        runAsync(() -> core.start(false));
    }

    private void refreshStatus() {
        Properties status = ControlClient.readStatusIfPresent(
                paths.getControlStatusFile());
        if (!ControlClient.isAlive(status)) {
            statusLabel.setText(messages.getString("status.stopped"));
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
        JComboBox<String> schedule = new JComboBox<>(new String[] {
            "on-logon", "interval"
        });
        schedule.setSelectedItem(current == null
                ? "on-logon" : current.getSchedule().serialized());
        JSpinner interval = new JSpinner(new SpinnerNumberModel(
                current == null ? 60 : current.getIntervalMinutes(), 1, 10080, 1));
        JPanel panel = new JPanel(new GridLayout(0, 2, 6, 6));
        panel.add(new JLabel(messages.getString("tasks.name")));
        panel.add(name);
        panel.add(new JLabel(messages.getString("tasks.schedule")));
        panel.add(schedule);
        panel.add(new JLabel(messages.getString("tasks.interval")));
        panel.add(interval);
        int result = JOptionPane.showConfirmDialog(frame, panel,
                messages.getString(current == null
                        ? "tasks.add.title" : "tasks.edit.title"),
                JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        try {
            return new TaskDefinition(name.getText(),
                    TaskDefinition.Schedule.parse((String) schedule.getSelectedItem()),
                    (Integer) interval.getValue(), true);
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
            addTrayItem(menu, "tray.stop", () -> runAsync(core::gracefulStop));
            menu.addSeparator();
            addTrayItem(menu, "tray.exit", this::exitApplication);
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

    private void exitApplication() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        if (statusTimer != null) {
            statusTimer.stop();
        }
        Thread exit = new Thread(() -> {
            try {
                core.gracefulStop();
            } catch (Exception error) {
                try {
                    core.forceStop();
                } catch (IOException ignored) {
                    // The launcher itself can still close.
                }
            } finally {
                SwingUtilities.invokeLater(() -> {
                    if (trayIcon != null) {
                        SystemTray.getSystemTray().remove(trayIcon);
                    }
                    frame.dispose();
                });
            }
        }, "nicocache-launcher-exit");
        exit.setDaemon(true);
        exit.start();
    }

    private Image createIcon(int size) {
        BufferedImage image = new BufferedImage(size, size,
                BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(38, 110, 190));
        graphics.fillRoundRect(1, 1, size - 2, size - 2, size / 3, size / 3);
        graphics.setColor(Color.WHITE);
        graphics.fillOval(size / 4, size / 4, size / 5, size / 5);
        graphics.fillOval(size / 2, size / 4, size / 5, size / 5);
        graphics.dispose();
        return image;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
