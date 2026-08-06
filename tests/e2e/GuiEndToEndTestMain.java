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
import java.nio.charset.Charset;
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

import dareka.common.Logger;
import dareka.processor.HttpUtil;

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
            NLMain.setDebugMode(Boolean.getBoolean("dareka.debug"));
            NLMain.extLoggerHandler = new NLMain.ExtLoggerHandler();
            Logger.setHandler(NLMain.extLoggerHandler);
            launcher.init();
            Thread.setDefaultUncaughtExceptionHandler(
                    (thread, error) -> {
                        uncaught.compareAndSet(null, error);
                        error.printStackTrace(System.err);
                    });
            run("GUI stable component identities", this::testComponentIdentity);
            run("GUI shutdown after failed initialization",
                    this::testShutdownAfterFailedInitialization);
            run("GUI debug file logging and retention",
                    this::testDebugFileLogging);
            run("GUI geometry and responsive rendering",
                    this::testGeometryAndRendering);
            run("GUI menus, tabs, and state transitions",
                    this::testMenusAndTabs);
            run("GUI log retention, dedupe, and progress",
                    this::testLogBehaviour);
            run("unknown content encoding GUI warning",
                    this::testUnknownContentEncodingWarning);
            run("GUI tab-specific live log search and history",
                    this::testLogSearch);
            run("GUI WebSocket burst delivery",
                    this::testWebSocketBurstDelivery);
            run("GUI persistence and resource cleanup",
                    this::testPersistenceAndCleanup);
        } finally {
            if (NLMain.extLoggerHandler != null) {
                NLMain.extLoggerHandler.close();
                NLMain.extLoggerHandler = null;
            }
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
        System.out.println("GUI end-to-end tests passed: 10");
    }

    private void prepare() throws IOException {
        Path application = sandbox.resolve("application");
        Path data = sandbox.resolve("data");
        Files.createDirectories(application);
        Files.createDirectories(data);
        Files.createDirectories(preview);
        System.setProperty("nicocache.applicationRoot", application.toString());
        System.setProperty("nicocache.userDataRoot", data.toString());
        System.setProperty("guiLogQueueCapacity", "512");
        System.setProperty("dareka.debug", "true");
        System.setProperty("shutdownTimeout", "1000");
        Files.writeString(
                application.resolve("config.properties"),
                "userDataRoot="
                        + data.toString().replace("\\", "\\\\")
                        + System.lineSeparator(),
                StandardCharsets.ISO_8859_1);

        Files.writeString(data.resolve("NicoCacheGUI.property"),
                String.join("\n",
                        "LogWindowX=-99999",
                        "LogWindowY=-99999",
                        "LogWindowW=1",
                        "LogWindowH=1",
                        "LogWindowAlwaysOnTop=false",
                        "LogWindowLineWrap=invalid",
                        "DebugMode=true",
                        "DebugLog=legacy-custom.log",
                        "LogSearchHistory.Tab.ZGVidWc.Count=1",
                        "LogSearchHistory.Tab.ZGVidWc.0.Query=legacy-debug-query",
                        "LogSearchHistory.Tab.ZGVidWc.0.Timestamp=1700000000001",
                        "LogSearchHistory.Tab.ZGVidWc.0.Regex=false",
                        "LogSearchHistory.Tab.ZGVidWc.0.CaseSensitive=false",
                        "ExitOnClose=false",
                        "FlipColor=false",
                        "FontName=Monospaced",
                        "FontSize=0",
                        "HideWindow=false",
                        "MaxLines=-3",
                        "MaxLinesHard=5",
                        "LogSearchHistory.Version=1",
                        "LogSearchHistory.Tab.bWFpbg.Count=1",
                        "LogSearchHistory.Tab.bWFpbg.0.Query=legacy-query",
                        "LogSearchHistory.Tab.bWFpbg.0.Timestamp=1700000000000",
                        "LogSearchHistory.Tab.bWFpbg.0.Regex=false",
                        "LogSearchHistory.Tab.bWFpbg.0.CaseSensitive=false",
                        ""),
                StandardCharsets.ISO_8859_1);
        Files.writeString(
                data.resolve("NicoCacheGUI.search-history.properties"),
                String.join("\n",
                        "LogSearchHistory.Version=0",
                        "LogSearchHistory.Tab.aW52YWxpZA.Count=1",
                        "LogSearchHistory.Tab.aW52YWxpZA.0.Query=obsolete-query",
                        "LogSearchHistory.Tab.aW52YWxpZA.0.Timestamp=1",
                        ""),
                StandardCharsets.ISO_8859_1);
        Files.writeString(
                application.resolve("debug.log"),
                "PREEXISTING_OLDEST\n"
                        + "x".repeat((int) BoundedLogFile.DEFAULT_MAX_BYTES + 4096)
                        + "\nPREEXISTING_NEWEST\n",
                Charset.defaultCharset());
    }

    private void testComponentIdentity() throws Exception {
        onEdt(() -> {
            assertEquals("log.frame",
                    GUILauncher.logWindow.frame.getRootPane().getName(),
                    "frame identity");
            assertEquals("log.tabs",
                    GUILauncher.logWindow.tabbedPane.getName(),
                    "tab identity");
            assertEquals("log.debug-file",
                    GUILauncher.logWindow.debugLoggingCheckBox.getName(),
                    "debug file checkbox identity");
            LogSearchPanel mainSearch =
                    GUILauncher.logWindow.mainPane.searchPanel;
            assertEquals(1, mainSearch.historyCombo.getItemCount(),
                    "legacy search history migration count");
            assertEquals("legacy-query",
                    mainSearch.historyCombo.getItemAt(0).getQuery(),
                    "legacy search history migration value");
            assertEquals("log.main.text",
                    GUILauncher.logWindow.mainPane.textArea.getName(),
                    "main text identity");
            assertEquals(1,
                    GUILauncher.logWindow.tabbedPane.getTabCount(),
                    "only the main tab must be present initially");
            assertEquals("log.main.query",
                    GUILauncher.logWindow.mainPane.searchPanel
                            .queryField.getName(),
                    "main search identity");
            assertEquals("log.main.regex",
                    GUILauncher.logWindow.mainPane.searchPanel
                            .regexCheckBox.getName(),
                    "main regex identity");
            assertMenuIdentity(GUILauncher.logWindow.mainPane.popup,
                    "log.copy", "log.select-all", "log.wrap",
                    "log.always-on-top");
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

    private void testDebugFileLogging() throws Exception {
        Path debugLog = sandbox.resolve("application/debug.log");
        assertTrue(Files.isRegularFile(debugLog),
                "debug.log must be created beside the application JAR");
        assertTrue(Files.size(debugLog) <= BoundedLogFile.DEFAULT_MAX_BYTES,
                "oversized debug.log must be trimmed when debug mode starts");
        String retained = Files.readString(debugLog, Charset.defaultCharset());
        assertFalse(retained.contains("PREEXISTING_OLDEST"),
                "oldest debug history must be trimmed");
        assertTrue(retained.contains("PREEXISTING_NEWEST"),
                "newest debug history must be retained");

        onEdt(() -> {
            assertTrue(GUILauncher.logWindow.debugLoggingCheckBox.isSelected(),
                    "DebugMode=true must select the checkbox");
            GUILauncher.logWindow.mainPane.clearLog();
            GUILauncher.logWindow.debugLoggingCheckBox.doClick();
        });
        assertFalse(NLMain.isDebugMode(),
                "clearing the checkbox must disable debug mode");
        assertFalse(GUILauncher.config.getBoolean("DebugMode"),
                "clearing the checkbox must update GUI properties");
        long disabledSize = Files.size(debugLog);
        Logger.debug("DISABLED_DEBUG_MARKER");
        assertEquals(disabledSize, Files.size(debugLog),
                "disabled debug logging must not append to debug.log");
        onEdt(() -> assertFalse(
                GUILauncher.logWindow.mainPane.textArea.getText()
                        .contains("DISABLED_DEBUG_MARKER"),
                "disabled debug messages must not reach Main"));

        onEdt(() -> GUILauncher.logWindow.debugLoggingCheckBox.doClick());
        assertTrue(NLMain.isDebugMode(),
                "selecting the checkbox must enable debug mode");
        assertTrue(GUILauncher.config.getBoolean("DebugMode"),
                "selecting the checkbox must update GUI properties");
        Logger.debug("ENABLED_DEBUG_MARKER");
        String enabled = Files.readString(debugLog, Charset.defaultCharset());
        assertTrue(enabled.contains("ENABLED_DEBUG_MARKER"),
                "enabled debug logging must append to debug.log");
        assertFalse(enabled.contains("DISABLED_DEBUG_MARKER"),
                "disabled debug messages must not appear after reopening");
        waitForGuiText("ENABLED_DEBUG_MARKER", 5000L);
        onEdt(() -> {
            GUILauncher.logWindow.mainPane.refreshDisplay();
            assertContains(GUILauncher.logWindow.mainPane.textArea.getText(),
                    "ENABLED_DEBUG_MARKER",
                    "enabled debug messages must reach Main");
        });
        assertTrue(Files.size(debugLog) <= BoundedLogFile.DEFAULT_MAX_BYTES,
                "debug.log must remain within the 1 MiB limit");

        Path rolloverLog = sandbox.resolve("application/rollover-test.log");
        try (BoundedLogFile rollover = new BoundedLogFile(
                rolloverLog, StandardCharsets.UTF_8, 256)) {
            for (int index = 0; index < 40; index++) {
                rollover.println("history-" + index + "-"
                        + "z".repeat(24));
            }
            assertTrue(rollover.size() <= 256,
                    "repeated appends must remain within the byte limit");
            rollover.println("🙂".repeat(200));
            assertTrue(rollover.size() <= 256,
                    "a single oversized entry must remain within the byte limit");
        }
        assertTrue(Files.size(rolloverLog) <= 256,
                "closed rollover log must remain within the byte limit");
    }

    private void testWebSocketBurstDelivery() throws Exception {
        assertTrue(GUILauncher.logTransport != null,
                "GUI log WebSocket transport must start");
        List<GuiLogEvent> decoded = GuiLogBatchCodec.decode(
                java.nio.ByteBuffer.wrap(GuiLogBatchCodec.encode(List.of(
                        new GuiLogEvent("codec", "日本語🙂\nsecond line")))));
        assertEquals(1, decoded.size(), "WebSocket batch codec event count");
        assertEquals("codec", decoded.get(0).getChannel(),
                "WebSocket batch codec channel");
        assertEquals("日本語🙂\nsecond line", decoded.get(0).getMessage(),
                "WebSocket batch codec Unicode and newline preservation");
        onEdt(() -> {
            GUILauncher.LogPane pane = GUILauncher.logWindow.mainPane;
            pane.maxLines = 2000;
            pane.setDedupe(false);
            pane.clearLog();
        });

        int eventCount = 20000;
        long started = System.nanoTime();
        for (int index = 0; index < eventCount; index++) {
            GUILauncher.append("WS_BURST_" + index);
        }
        long publishMillis = java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(System.nanoTime() - started);
        assertTrue(publishMillis < 2000L,
                "20,000 log publications must not block producers: "
                + publishMillis + " ms");
        assertTrue(GUILauncher.logTransport.pendingEventCount() <= 512,
                "GUI log queue must remain bounded");
        assertTrue(GUILauncher.displayQueue.size() <= 512,
                "GUI display queue must remain bounded");

        waitForGuiText("WS_BURST_19999", 10000L);
        onEdt(() -> {
            GUILauncher.logWindow.mainPane.refreshDisplay();
            String displayed =
                    GUILauncher.logWindow.mainPane.textArea.getText();
            assertContains(displayed, "WS_BURST_19999",
                    "WebSocket delivery must retain the newest burst event");
            assertTrue(GUILauncher.logWindow.mainPane.textArea.getLineCount()
                            <= 2001,
                    "WebSocket burst display must honor the line limit");
        });

        GUILauncher.LogPane extension = launcher.addExtPane(
                "ws-test", "WebSocket extension delivery");
        GUILauncher.append(extension, "WS_EXTENSION_MARKER");
        waitForGuiText(extension, "WS_EXTENSION_MARKER", 5000L);
    }

    private void testShutdownAfterFailedInitialization() throws Exception {
        String previousTimeout = System.getProperty("shutdownTimeout");
        System.setProperty("shutdownTimeout", "1000");
        try {
            long started = System.nanoTime();
            NLMain.shutdown();
            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS
                    .toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 500,
                    "shutdown after failed initialization took too long: "
                    + elapsedMillis + "ms");
        } finally {
            if (previousTimeout == null) {
                System.clearProperty("shutdownTimeout");
            } else {
                System.setProperty("shutdownTimeout", previousTimeout);
            }
        }
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
            assertEquals(2, GUILauncher.logWindow.tabbedPane.getTabCount(),
                    "extension tab count");
            assertEquals("log.extension-1.text", extension.textArea.getName(),
                    "extension text identity");
            GUILauncher.logWindow.tabbedPane.setSelectedIndex(1);
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

            pane.setBackLog(true);
            pane.updateLogWindowTitle();
            assertContains(GUILauncher.logWindow.frame.getTitle(),
                    "バックログ", "backlog title");
            launcher.activatePrimaryTab();
            assertFalse(pane.isBackLog(),
                    "main tab activation must leave backlog mode");
        });
    }

    private void testUnknownContentEncodingWarning() throws Exception {
        onEdt(() -> {
            GUILauncher.LogPane pane = GUILauncher.logWindow.mainPane;
            pane.maxLines = 20;
            pane.setDedupe(false);
            pane.clearLog();
            pane.refreshDisplay();
        });

        HttpUtil.getDecodedInputStream(new byte[] { 1, 2, 3 }, "futurezip");
        waitForGuiText("未対応のContent-Encoding「futurezip」", 5_000L);
        waitForGuiText("NicoCache_nl側で対応を追加してください", 5_000L);
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
        Path historyFile = sandbox.resolve(
                "data/NicoCacheGUI.search-history.properties");
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
        assertEquals(null, saved.getProperty("DebugLog"),
                "legacy custom debug path must be removed");
        assertFalse(saved.stringPropertyNames().stream()
                        .anyMatch(name ->
                                name.startsWith("LogSearchHistory.")),
                "GUI properties must not contain search history");

        Properties history = new Properties();
        try (InputStream input = Files.newInputStream(historyFile)) {
            history.load(input);
        }
        assertEquals("1", history.getProperty("LogSearchHistory.Version"),
                "dedicated search history schema version");
        boolean migratedQuery = history.stringPropertyNames().stream()
                .filter(name -> name.endsWith(".Query"))
                .map(history::getProperty)
                .anyMatch(value -> "legacy-query".equals(value));
        boolean obsoleteQuery = history.stringPropertyNames().stream()
                .filter(name -> name.endsWith(".Query"))
                .map(history::getProperty)
                .anyMatch(value -> "obsolete-query".equals(value));
        boolean savedQuery = history.stringPropertyNames().stream()
                .filter(name -> name.endsWith(".Query"))
                .map(history::getProperty)
                .anyMatch(value -> "error-\\d+".equals(value));
        boolean savedTimestamp = history.stringPropertyNames().stream()
                .filter(name -> name.endsWith(".Timestamp"))
                .map(history::getProperty)
                .anyMatch(value -> value != null
                        && value.matches("\\d{10,}"));
        assertTrue(migratedQuery, "legacy search query migration");
        assertFalse(history.stringPropertyNames().stream()
                        .anyMatch(name ->
                                name.startsWith("LogSearchHistory.Tab.ZGVidWc.")),
                "removed debug tab history");
        assertFalse(obsoleteQuery, "obsolete history schema recovery");
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

    private static void waitForGuiText(String expected, long timeoutMillis)
            throws Exception {
        waitForGuiText(
                GUILauncher.logWindow.mainPane, expected, timeoutMillis);
    }

    private static void waitForGuiText(GUILauncher.LogPane pane,
            String expected, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS
                        .toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> found = new AtomicReference<>(false);
            onEdt(() -> {
                pane.refreshDisplay();
                found.set(pane.textArea.getText().contains(expected));
            });
            if (found.get()) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError(
                "timed out waiting for GUI log text: " + expected);
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
