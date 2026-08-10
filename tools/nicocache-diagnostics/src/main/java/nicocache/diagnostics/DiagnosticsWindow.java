package nicocache.diagnostics;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Responsive Swing and tray surface for the continuously running watchdog. */
final class DiagnosticsWindow implements DiagnosticsService.Listener {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final DiagnosticsService service;
    private final DiagnosticsPaths paths;
    private final ResourceBundle messages;
    private final Runnable exitAction;
    private final JFrame frame = new JFrame();
    private final JLabel status = new JLabel();
    private final JLabel heartbeat = new JLabel();
    private final JLabel report = new JLabel();
    private final JTextArea timeline = new JTextArea();
    private final JButton collect = new JButton();
    private TrayIcon trayIcon;

    DiagnosticsWindow(DiagnosticsService service, DiagnosticsPaths paths,
            ResourceBundle messages, Runnable exitAction) {
        this.service = service;
        this.paths = paths;
        this.messages = messages;
        this.exitAction = exitAction;
        buildWindow();
        createTray();
        service.setListener(this);
    }

    void show() {
        frame.setVisible(true);
        frame.toFront();
    }

    void hide() {
        frame.setVisible(false);
    }

    private void buildWindow() {
        frame.setName("diagnostics.window");
        frame.setTitle(messages.getString("window.title"));
        frame.setMinimumSize(new Dimension(420, 300));
        frame.setSize(680, 480);
        frame.setLocationByPlatform(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setIconImage(createIcon(32, new Color(28, 116, 73)));
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (trayIcon == null) {
                    exitAction.run();
                } else {
                    hide();
                }
            }
        });

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel summary = new JPanel(new BorderLayout(6, 6));
        status.setName("diagnostics.status");
        status.setText(messages.getString("status.waiting"));
        status.setFont(status.getFont().deriveFont(18f));
        summary.add(status, BorderLayout.NORTH);
        heartbeat.setName("diagnostics.heartbeat");
        heartbeat.setText(messages.getString("heartbeat.none"));
        summary.add(heartbeat, BorderLayout.CENTER);
        report.setName("diagnostics.last-report");
        report.setText(messages.getString("report.none"));
        summary.add(report, BorderLayout.SOUTH);
        content.add(summary, BorderLayout.NORTH);

        timeline.setName("diagnostics.timeline");
        timeline.setEditable(false);
        timeline.setLineWrap(true);
        timeline.setWrapStyleWord(true);
        timeline.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,
                java.awt.Font.PLAIN, 12));
        content.add(new JScrollPane(timeline), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        collect.setName("diagnostics.collect");
        collect.setText(messages.getString("button.collect"));
        collect.addActionListener(event -> service.collectNow());
        buttons.add(collect);
        JButton reports = new JButton(messages.getString("button.reports"));
        reports.setName("diagnostics.reports");
        reports.addActionListener(event -> open(paths.incidentsRoot()));
        buttons.add(reports);
        JButton hide = new JButton(messages.getString("button.hide"));
        hide.setName("diagnostics.hide");
        hide.addActionListener(event -> hide());
        buttons.add(hide);
        content.add(buttons, BorderLayout.SOUTH);
        frame.setContentPane(content);
    }

    private void createTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem show = new MenuItem(messages.getString("tray.show"));
            show.addActionListener(event -> SwingUtilities.invokeLater(this::show));
            menu.add(show);
            MenuItem collectItem = new MenuItem(messages.getString("tray.collect"));
            collectItem.addActionListener(event -> service.collectNow());
            menu.add(collectItem);
            MenuItem reports = new MenuItem(messages.getString("tray.reports"));
            reports.addActionListener(event -> open(paths.incidentsRoot()));
            menu.add(reports);
            menu.addSeparator();
            MenuItem exit = new MenuItem(messages.getString("tray.exit"));
            exit.addActionListener(event -> exitAction.run());
            menu.add(exit);
            trayIcon = new TrayIcon(createIcon(16, new Color(28, 116, 73)),
                    messages.getString("window.title"), menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(event -> SwingUtilities.invokeLater(this::show));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception error) {
            trayIcon = null;
        }
    }

    @Override
    public void heartbeat(HeartbeatSample sample) {
        SwingUtilities.invokeLater(() -> {
            String key = "status." + sample.health.name().toLowerCase(
                    java.util.Locale.ROOT).replace('_', '-');
            status.setText(messages.containsKey(key)
                    ? messages.getString(key) : sample.health.name());
            Color color = sample.healthy() ? new Color(0, 120, 65)
                    : sample.health == HeartbeatSample.Health.STOPPED
                    || sample.health == HeartbeatSample.Health.STARTING
                    ? new Color(145, 90, 0) : new Color(175, 25, 40);
            status.setForeground(color);
            heartbeat.setText(messages.getString("heartbeat.prefix")
                    + " " + TIME.format(sample.capturedAt) + " / PID " + sample.pid
                    + " / control " + sample.controlMillis + " ms / proxy "
                    + sample.proxyMillis + " ms");
            timeline.append(TIME.format(sample.capturedAt) + "  "
                    + sample.health + (sample.detail.isBlank()
                    ? "" : "  " + sample.detail) + System.lineSeparator());
            trimTimeline();
        });
    }

    @Override
    public void collectionStarted(String reason) {
        SwingUtilities.invokeLater(() -> {
            collect.setEnabled(false);
            report.setText(messages.getString("report.collecting") + " " + reason);
        });
    }

    @Override
    public void collectionCompleted(Path reportPath) {
        SwingUtilities.invokeLater(() -> {
            collect.setEnabled(true);
            report.setText(messages.getString("report.created") + " " + reportPath);
            if (trayIcon != null) {
                trayIcon.displayMessage(messages.getString("window.title"),
                        messages.getString("notification.created"),
                        TrayIcon.MessageType.WARNING);
            }
        });
    }

    @Override
    public void collectionFailed(String message) {
        SwingUtilities.invokeLater(() -> {
            collect.setEnabled(true);
            report.setText(messages.getString("report.failed") + " " + message);
        });
    }

    @Override
    public void showRequested() {
        SwingUtilities.invokeLater(this::show);
    }

    private void trimTimeline() {
        int lines = timeline.getLineCount();
        if (lines <= 200) {
            return;
        }
        try {
            timeline.replaceRange("", 0, timeline.getLineEndOffset(lines - 200));
        } catch (javax.swing.text.BadLocationException ignored) {
            timeline.setText("");
        }
    }

    private void open(Path path) {
        try {
            Files.createDirectories(path);
            if (!Desktop.isDesktopSupported()) {
                throw new IOException(messages.getString("error.desktop"));
            }
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException error) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
                    error.getMessage(), messages.getString("error.title"),
                    JOptionPane.ERROR_MESSAGE));
        }
    }

    void dispose() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        frame.dispose();
    }

    private static Image createIcon(int size, Color color) {
        BufferedImage image = new BufferedImage(size, size,
                BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(color);
        graphics.fillOval(1, 1, size - 2, size - 2);
        graphics.setColor(Color.WHITE);
        int stroke = Math.max(2, size / 7);
        graphics.fillRect(size / 2 - stroke / 2, size / 5,
                stroke, size * 3 / 5);
        graphics.fillRect(size / 5, size / 2 - stroke / 2,
                size * 3 / 5, stroke);
        graphics.dispose();
        return image;
    }
}
