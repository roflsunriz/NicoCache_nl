package dareka;

import dareka.NLMain.GUILauncher;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

/**
 * Real Swing event-dispatch tests for the primary log GUI.
 */
public final class GuiEndToEndTestMain {
    private static final List<String> FAILURES = new ArrayList<>();

    private final Path sandbox;
    private final Path preview;
    private final AtomicReference<Throwable> uncaught = new AtomicReference<>();
    private GUILauncher launcher;

    private GuiEndToEndTestMain(Path sandbox, Path preview) {
        this.sandbox = sandbox;
        this.preview = preview;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: GuiEndToEndTestMain <sandbox> <preview>");
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new AssertionError(
                    "GUI E2E requires a graphical environment");
        }
        GuiEndToEndTestMain suite = new GuiEndToEndTestMain(
                Path.of(args[0]).toAbsolutePath().normalize(),
                Path.of(args[1]).toAbsolutePath().normalize());
        suite.execute();
    }

    private void execute() throws Exception {
        prepare();
        try {
            launcher = new GUILauncher();
            launcher.init();
            Thread.setDefaultUncaughtExceptionHandler(
                    (thread, error) -> {
                        uncaught.compareAndSet(null, error);
                        error.printStackTrace(System.err);
                    });
            run("GUI stable component identities", this::testComponentIdentity);
            run("GUI geometry and responsive rendering",
                    this::testGeometryAndRendering);
            run("GUI menus, tabs, and state transitions",
                    this::testMenusAndTabs);
            run("GUI log retention, dedupe, and progress",
                    this::testLogBehaviour);
            run("GUI tab-specific live log search and history",
                    this::testLogSearch);
            run("GUI persistence and resource cleanup",
                    this::testPersistenceAndCleanup);
        } finally {
            if (launcher != null && GUILauncher.logWindow != null
                    && GUILauncher.logWindow.frame != null) {
                launcher.close();
            }
            for (Window window : Window.getWindows()) {
                if (window.isDisplayable()) {
                    SwingUtilities.invokeAndWait(window::dispose);
                }
            }
        }

        if (uncaught.get() != null) {
            FAILURES.add("uncaught GUI exception: " + uncaught.get());
            uncaught.get().printStackTrace(System.err);
        }
        if (!FAILURES.isEmpty()) {
            System.err.println("GUI end-to-end failures: " + FAILURES.size());
            for (String failure : FAILURES) {
                System.err.println("  - " + failure);
            }
            throw new AssertionError("GUI end-to-end tests failed");
        }
        System.out.println("GUI end-to-end tests passed: 6");
    }

    private void prepare() throws IOException {
        Path application = sandbox.resolve("application");
        Path data = sandbox.resolve("data");
        Files.createDirectories(application);
        Files.createDirectories(data);
        Files.createDirectories(preview);
        System.setProperty("nicocache.applicationRoot", application.toString());
        System.setProperty("nicocache.dataRoot", data.toString());
        System.setProperty("dareka.debug", "true");
        System.setProperty("shutdownTimeout", "1000");

        Files.writeString(data.resolve("NicoCacheGUI.property"),
                String.join("\n",
                        "LogWindowX=-99999",
                        "LogWindowY=-99999",
                        "LogWindowW=1",
                        "LogWindowH=1",
                        "LogWindowAlwaysOnTop=false",
                        "LogWindowLineWrap=invalid",
                        "DebugMode=true",
                        "ExitOnClose=false",
                        "FlipColor=false",
                        "FontName=Monospaced",
                        "FontSize=0",
                        "HideWindow=false",
                        "MaxLines=-3",
                        "MaxLinesHard=5",
                        ""),
                StandardCharsets.ISO_8859_1);
    }

    private void testComponentIdentity() throws Exception {
        onEdt(() -> {
            assertEquals("log.frame",
                    GUILauncher.logWindow.frame.getRootPane().getName(),
                    "frame identity");
            assertEquals("log.tabs",
                    GUILauncher.logWindow.tabbedPane.getName(),
                    "tab identity");
            assertEquals("log.main.text",
                    GUILauncher.logWindow.mainPane.textArea.getName(),
                    "main text identity");
            assertEquals("log.debug.text",
                    GUILauncher.logWindow.debugPane.textArea.getName(),
                    "debug text identity");
            assertEquals("log.main.query",
                    GUILauncher.logWindow.mainPane.searchPanel
                            .queryField.getName(),
                    "main search identity");
            assertEquals("log.main.regex",
                    GUILauncher.logWindow.mainPane.searchPanel
                            .regexCheckBox.getName(),
                    "main regex identity");
            assertEquals("log.debug.history",
                    GUILauncher.logWindow.debugPane.searchPanel
                            .historyCombo.getName(),
                    "debug history identity");
            assertMenuIdentity(GUILauncher.logWindow.mainPane.popup,
                    "log.copy", "log.select-all", "log.wrap",
                    "log.always-on-top");
            assertMenuIdentity(GUILauncher.logWindow.debugPane.popup,
                    "log.copy", "log.select-all");
            assertTrue(GUILauncher.logWindow.mainPane.textArea.getLineWrap(),
                    "invalid line-wrap value must use the true default");
            assertEquals(14,
                    GUILauncher.logWindow.mainPane.textArea.getFont().getSize(),
                    "invalid font size must use the positive default");
            if (GUILauncher.tray != null) {
                assertMenuIdentity(GUILauncher.logWindow.mainPane.popup,
                        "log.hide-on-start", "log.exit-on-close");
            }
        });
    }

    private void testGeometryAndRendering() throws Exception {
        onEdt(() -> {
            Rectangle bounds = GUILauncher.logWindow.frame.getBounds();
            assertTrue(bounds.width >= 320,
                    "window width must remain usable: " + bounds);
            assertTrue(bounds.height >= 200,
                    "window height must remain usable: " + bounds);
            assertTrue(intersectsPhysicalScreen(bounds),
                    "window must remain on a visible screen: " + bounds);
            assertTrue(GUILauncher.logWindow.frame.getMinimumSize().width >= 320,
                    "minimum width");
            assertTrue(GUILauncher.logWindow.frame.getMinimumSize().height >= 200,
                    "minimum height");

            render(320, 200, preview.resolve("log-window-minimum.png"));
            assertNamedComponentsWithinBounds(
                    GUILauncher.logWindow.frame.getContentPane(),
                    GUILauncher.logWindow.frame.getContentPane());
            render(1024, 768, preview.resolve("log-window-standard.png"));
            assertNamedComponentsWithinBounds(
                    GUILauncher.logWindow.frame.getContentPane(),
                    GUILauncher.logWindow.frame.getContentPane());
        });
    }

    private void testMenusAndTabs() throws Exception {
        onEdt(() -> {
            GUILauncher.LogPane main = GUILauncher.logWindow.mainPane;
            main.textArea.setText("alpha\nbeta\ngamma");
            JMenuItem selectAll = menu(main.popup, "log.select-all");
            selectAll.doClick();
            assertEquals(main.textArea.getText(),
                    main.textArea.getSelectedText(), "Select all action");

            JCheckBoxMenuItem wrap = (JCheckBoxMenuItem) menu(
                    main.popup, "log.wrap");
            boolean initialWrap = wrap.isSelected();
            wrap.doClick();
            assertEquals(!initialWrap, main.textArea.getLineWrap(),
                    "main line-wrap state");
            assertEquals(!initialWrap,
                    GUILauncher.logWindow.debugPane.textArea.getLineWrap(),
                    "debug line-wrap state");
            assertEquals(!initialWrap,
                    GUILauncher.config.getBoolean("LogWindowLineWrap"),
                    "line-wrap configuration");

            JCheckBoxMenuItem top = (JCheckBoxMenuItem) menu(
                    main.popup, "log.always-on-top");
            top.doClick();
            assertEquals(top.isSelected(),
                    GUILauncher.config.getBoolean("LogWindowAlwaysOnTop"),
                    "always-on-top configuration");
            if (GUILauncher.logWindow.frame.isAlwaysOnTopSupported()) {
                assertEquals(top.isSelected(),
                        GUILauncher.logWindow.frame.isAlwaysOnTop(),
                        "always-on-top frame state");
            }

            if (GUILauncher.tray != null) {
                JCheckBoxMenuItem hide = (JCheckBoxMenuItem) menu(
                        main.popup, "log.hide-on-start");
                hide.doClick();
                assertEquals(hide.isSelected(),
                        GUILauncher.config.getBoolean("HideWindow"),
                        "hide-on-start configuration");
                JCheckBoxMenuItem exitOnClose = (JCheckBoxMenuItem) menu(
                        main.popup, "log.exit-on-close");
                if (exitOnClose.isSelected()) {
                    exitOnClose.doClick();
                }
                GUILauncher.logWindow.frame.setVisible(true);
                GUILauncher.logWindow.frame.dispatchEvent(new WindowEvent(
                        GUILauncher.logWindow.frame,
                        WindowEvent.WINDOW_CLOSING));
                assertFalse(GUILauncher.logWindow.frame.isVisible(),
                        "Close must hide the window while tray mode is active");
                GUILauncher.logWindow.frame.setVisible(true);
            }

            GUILauncher.LogPane extension = launcher.addExtPane(
                    "fixture", "E2E extension tab");
            assertEquals(3, GUILauncher.logWindow.tabbedPane.getTabCount(),
                    "extension tab count");
            assertEquals("log.extension-2.text", extension.textArea.getName(),
                    "extension text identity");
            GUILauncher.logWindow.tabbedPane.setSelectedIndex(2);
            assertEquals("NicoCache_nl：デバッグモード",
                    GUILauncher.logWindow.frame.getTitle(),
                    "extension tab title");
            GUILauncher.logWindow.tabbedPane.setSelectedIndex(0);
        });
    }

    private void testLogBehaviour() throws Exception {
        onEdt(() -> {
            GUILauncher.LogPane pane = GUILauncher.logWindow.mainPane;
            pane.maxLines = 3;
            pane.clearLog();
            pane.setDedupe(false);
            for (String value : List.of("one", "two", "three", "four")) {
                pane.append(value);
            }
            pane.refreshDisplay();
            assertFalse(pane.textArea.getText().contains("one"),
                    "normal log must trim the oldest line");
            assertContains(pane.textArea.getText(), "four",
                    "normal log must retain newest line");
            assertTrue(pane.textArea.getLineCount() <= 4,
                    "normal log line limit");

            pane.clearLog();
            pane.setDedupe(true);
            pane.append("same");
            pane.append("same");
            pane.append("same");
            pane.refreshDisplay();
            assertEquals("same\n++", pane.textArea.getText(),
                    "deduplicated log");

            pane.clearLog();
            pane.setDedupe(false);
            pane.append("caching sm1: 10%");
            pane.append("caching sm1: 80%");
            pane.refreshDisplay();
            assertFalse(pane.textArea.getText().contains("10%"),
                    "stale cache progress must be replaced");
            assertEquals(1, occurrences(
                    pane.textArea.getText(), "caching sm1:"),
                    "cache progress line count");

            GUILauncher.LogPane primary = GUILauncher.logWindow.debugPane;
            primary.setBackLog(true);
            primary.updateLogWindowTitle();
            assertContains(GUILauncher.logWindow.frame.getTitle(),
                    "バックログ", "backlog title");
            launcher.activatePrimaryTab();
            assertFalse(primary.isBackLog(),
                    "primary tab activation must leave backlog mode");
        });
    }

    private void testLogSearch() throws Exception {
        onEdt(() -> {
            GUILauncher.LogPane pane = GUILauncher.logWindow.mainPane;
            pane.maxLines = 20;
            pane.setDedupe(false);
            pane.clearLog();
            pane.append("INFO startup");
            pane.append("error-42 first");
            pane.append("ERROR-77 second");
            pane.append("literal.value");
            pane.refreshDisplay();

            LogSearchPanel search = pane.searchPanel;
            search.queryField.setText("error");
            search.refreshNow();
            assertFalse(pane.textArea.getText().contains("INFO"),
                    "literal search must hide non-matching lines");
            assertContains(pane.textArea.getText(), "error-42",
                    "literal search lower-case result");
            assertContains(pane.textArea.getText(), "ERROR-77",
                    "literal search must be case-insensitive by default");
            assertEquals("2 / 4 行", search.statusLabel.getText(),
                    "literal search status");

            search.caseSensitiveCheckBox.setSelected(true);
            search.refreshNow();
            assertContains(pane.textArea.getText(), "error-42",
                    "case-sensitive matching result");
            assertFalse(pane.textArea.getText().contains("ERROR-77"),
                    "case-sensitive search must exclude upper-case result");

            search.regexCheckBox.setSelected(true);
            search.caseSensitiveCheckBox.setSelected(false);
            search.queryField.setText("error-\\d+");
            search.refreshNow();
            assertEquals(2, occurrences(pane.textArea.getText(), "error-")
                            + occurrences(pane.textArea.getText(), "ERROR-"),
                    "regular expression line count");

            pane.append("warning ignored");
            pane.append("error-99 appended");
            pane.refreshDisplay();
            assertFalse(pane.textArea.getText().contains("warning ignored"),
                    "active filter must apply to appended logs");
            assertContains(pane.textArea.getText(), "error-99 appended",
                    "active filter must include matching appended logs");

            search.queryField.setText("[");
            search.refreshNow();
            assertContains(search.statusLabel.getText(), "正規表現エラー",
                    "invalid regular-expression status");
            assertContains(pane.textArea.getText(), "INFO startup",
                    "invalid regular expression must preserve raw log view");

            search.queryField.setText("error-\\d+");
            search.refreshNow();
            search.commitCurrentSearch();
            assertTrue(search.historyCombo.getItemCount() > 0,
                    "search history entry");
            Component rendered = search.historyCombo.getRenderer()
                    .getListCellRendererComponent(
                            new javax.swing.JList<>(),
                            search.historyCombo.getItemAt(0),
                            0, false, false);
            assertTrue(rendered instanceof JLabel,
                    "history renderer component");
            assertTrue(((JLabel) rendered).getText().matches(
                            "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*"),
                    "history must show date and time");

            search.clearQuery();
            assertContains(pane.textArea.getText(), "warning ignored",
                    "clear must restore all raw logs");
            search.historyCombo.setSelectedIndex(0);
            long originalTimestamp =
                    search.historyCombo.getItemAt(0).getTimestamp();
            assertEquals("error-\\d+", search.queryField.getText(),
                    "history selection must replace query");
            assertTrue(search.regexCheckBox.isSelected(),
                    "history selection must restore regex mode");
            assertFalse(pane.textArea.getText().contains("warning ignored"),
                    "history selection must immediately search");
            search.commitCurrentSearch();
            assertEquals(originalTimestamp,
                    search.historyCombo.getItemAt(0).getTimestamp(),
                    "history selection must preserve original timestamp");

            GUILauncher.LogPane extension = launcher.addExtPane(
                    "search-fixture", "search history isolation");
            assertEquals(0, extension.searchPanel.historyCombo.getItemCount(),
                    "extension search history must start independently");
            extension.append("extension-only");
            extension.refreshDisplay();
            extension.searchPanel.queryField.setText("extension");
            extension.searchPanel.refreshNow();
            extension.searchPanel.commitCurrentSearch();
            assertEquals(1,
                    extension.searchPanel.historyCombo.getItemCount(),
                    "extension search history entry");
            assertTrue(search.historyCombo.getItemCount() > 0,
                    "main history must remain intact");

            search.clearButton.doClick();
            assertEquals("", search.queryField.getText(),
                    "clear button");
            search.queryField.setText("temporary");
            search.queryField.getActionMap().get("clear-search")
                    .actionPerformed(null);
            assertEquals("", search.queryField.getText(),
                    "Escape action");
        });
    }

    private void testPersistenceAndCleanup() throws Exception {
        Path propertyFile = sandbox.resolve("data/NicoCacheGUI.property");
        onEdt(() ->
                GUILauncher.logWindow.frame.setBounds(40, 50, 700, 500));
        launcher.close();
        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(propertyFile)) {
            saved.load(input);
        }
        assertEquals("40", saved.getProperty("LogWindowX"), "saved X");
        assertEquals("50", saved.getProperty("LogWindowY"), "saved Y");
        assertEquals("700", saved.getProperty("LogWindowW"), "saved width");
        assertEquals("500", saved.getProperty("LogWindowH"), "saved height");
        assertEquals("1", saved.getProperty("LogSearchHistory.Version"),
                "search history schema version");
        boolean savedQuery = saved.stringPropertyNames().stream()
                .filter(name -> name.endsWith(".Query"))
                .map(saved::getProperty)
                .anyMatch(value -> "error-\\d+".equals(value));
        boolean savedTimestamp = saved.stringPropertyNames().stream()
                .filter(name -> name.endsWith(".Timestamp"))
                .map(saved::getProperty)
                .anyMatch(value -> value != null
                        && value.matches("\\d{10,}"));
        assertTrue(savedQuery, "search query persistence");
        assertTrue(savedTimestamp, "search timestamp persistence");
        assertTrue(GUILauncher.logWindow.frame == null,
                "frame reference must be cleared");
        assertTrue(GUILauncher.tray == null,
                "tray reference must be cleared");
    }

    private void render(int width, int height, Path output) {
        Container content = GUILauncher.logWindow.frame.getContentPane();
        content.setMinimumSize(new Dimension(320, 200));
        content.setSize(width, height);
        layoutRecursively(content);
        BufferedImage image = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.MAGENTA);
            graphics.fillRect(0, 0, width, height);
            content.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        try {
            ImageIO.write(image, "png", output.toFile());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        assertTrue(image.getRGB(width / 2, height / 2)
                        != Color.MAGENTA.getRGB(),
                "render must paint the content at " + width + "x" + height);
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutRecursively((Container) child);
            }
        }
    }

    private static void assertNamedComponentsWithinBounds(
            Container root, Container container) {
        for (Component child : container.getComponents()) {
            if (child.isVisible() && child.getName() != null) {
                Rectangle bounds = SwingUtilities.convertRectangle(
                        child.getParent(), child.getBounds(), root);
                assertTrue(bounds.x >= 0 && bounds.y >= 0,
                        "component starts outside window: " + child.getName()
                        + " " + bounds);
                assertTrue(bounds.x + bounds.width <= root.getWidth(),
                        "component exceeds window width: " + child.getName()
                        + " " + bounds);
                assertTrue(bounds.y + bounds.height <= root.getHeight(),
                        "component exceeds window height: " + child.getName()
                        + " " + bounds);
            }
            if (child instanceof Container) {
                assertNamedComponentsWithinBounds(
                        root, (Container) child);
            }
        }
    }

    private static boolean intersectsPhysicalScreen(Rectangle bounds) {
        for (var device : GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices()) {
            if (bounds.intersects(
                    device.getDefaultConfiguration().getBounds())) {
                return true;
            }
        }
        return false;
    }

    private static JMenuItem menu(JPopupMenu popup, String name) {
        for (Component component : popup.getComponents()) {
            if (component instanceof JMenuItem
                    && name.equals(component.getName())) {
                return (JMenuItem) component;
            }
        }
        throw new AssertionError("menu item is missing: " + name);
    }

    private static void assertMenuIdentity(
            JPopupMenu popup, String... expectedNames) {
        for (String name : expectedNames) {
            JMenuItem item = menu(popup, name);
            assertEquals(name,
                    item.getAccessibleContext().getAccessibleName(),
                    "accessible menu identity");
        }
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(part, offset)) >= 0) {
            count++;
            offset += part.length();
        }
        return count;
    }

    private static void onEdt(CheckedRunnable operation) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                operation.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            Throwable error = failure.get();
            if (error instanceof Exception) {
                throw (Exception) error;
            }
            if (error instanceof Error) {
                throw (Error) error;
            }
            throw new RuntimeException(error);
        }
    }

    private static void run(String name, CheckedRunnable test) {
        try {
            test.run();
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            FAILURES.add(name + ": " + error.getMessage());
            error.printStackTrace(System.err);
        }
    }

    private static void assertEquals(
            Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertContains(
            String actual, String expected, String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(
                    message + ": expected to contain=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
