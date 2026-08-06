package dareka;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.text.DefaultCaret;

import dareka.common.CloseUtil;
import dareka.common.Config;
import dareka.common.ConfigObserver;
import dareka.common.DefaultLoggerHandler;
import dareka.common.Logger;
import dareka.common.LoggerHandler;
import dareka.extensions.Extension2;
import dareka.processor.impl.Cache;
import dareka.processor.impl.NLShared;

/**
 * NicoCache_nl 用 CUI/GUI ランチャー
 * @since NicoCache_nl+110118mod
 */
public class NLMain {
    /** このパッケージ内で NLShared を使うためのインスタンス */
    static final NLShared SHARED = NLShared.getInstance();

    static ExtLoggerHandler extLoggerHandler;
    static GUILauncher guiLauncher;
    private static boolean debugMode;
    private static Thread mainThread;

    public static void main(String[] args) {
        LaunchOptions launchOptions = LaunchOptions.parse(args);
        if (launchOptions.getError() != null) {
            System.err.println(launchOptions.getError());
            System.exit(2);
            return;
        }
        if (launchOptions.isHeadless()) {
            System.setProperty("dareka.gui", "false");
        }
        Path appDirectory = NicoCachePaths.applicationRoot();
        Path dataDirectory = NicoCachePaths.dataRoot();
        NicoCachePaths.publishDataRoot(dataDirectory);
        System.setProperty("user.dir", dataDirectory.toString());
        if (launchOptions.isSetup()) {
            int exitCode = FirstRunSetup.runHeadless(
                    appDirectory,
                    dataDirectory,
                    launchOptions.getSetupOptions());
            if (exitCode != 0) {
                System.exit(exitCode);
            }
            return;
        }
        args = launchOptions.getForwardedArgs();
        boolean startGUI = isStartGUI();
        if (startGUI && !FirstRunSetup.runIfRequired(
                appDirectory, dataDirectory)) {
            return;
        }
        dataDirectory = NicoCachePaths.dataRoot();
        NicoCachePaths.publishDataRoot(dataDirectory);
        System.setProperty("user.dir", dataDirectory.toString());
        GUILauncher.refreshUserFiles();
        if (startGUI) {
            try {
                guiLauncher = new GUILauncher();
            } catch (Throwable t) {
                Logger.error(t);
                System.exit(-1);
            }
        }
        debugMode = Boolean.getBoolean("dareka.debug");
        mainThread = Thread.currentThread();

        DefaultLoggerHandler.setDebug(debugMode);
        Logger.setHandler(extLoggerHandler = new ExtLoggerHandler());
        if (guiLauncher != null) {
            guiLauncher.init();
        }

        Main.main(args);

        synchronized (NLMain.class) { // shutdownから抜けるまで待つ
            boolean startedGUI = guiLauncher != null;
            try {
                if (extLoggerHandler != null) {
                    extLoggerHandler.close();
                }
                if (startedGUI) {
                    guiLauncher.close();
                }
            } catch (Throwable t) {
                Logger.error(t);
            } finally {
                if (startedGUI || launchOptions.isHeadless()) {
                    System.exit(0);
                }
            }
        }
    }

    static boolean isStartGUI() {
        // オプションが指定されているならそちらに従う
        String dareka_gui = System.getProperty("dareka.gui");
        if (dareka_gui != null) {
            return Boolean.parseBoolean(dareka_gui);
        }
        // オプションが指定されていないならコンソールの有無で判断
        try {
            return System.console() == null;
        } catch (NoSuchMethodError e) {
            Logger.debugWithThread(e);
        }
        return false;
    }

    static native void setNativeWindowProc(JFrame frame, String params);

    static synchronized void shutdown() {
        try {
            if (guiLauncher != null) {
                guiLauncher.activatePrimaryTab();
            }
        } catch (Throwable t) {
            Logger.error(t);
        }
        Logger.debugWithThread("NLMain.shutdown() called");

        try {
            Main.stop();
        } catch (Throwable t) {
            Logger.error(t);
        }

        if (Thread.currentThread() == mainThread) {
            return;
        }
        try { // 終了するまで待つ
            long timeout = Long.getLong("shutdownTimeout", 60000L);
            long interval = 500L;
            while (!Main.isDone() && timeout > 0L) {
                Thread.sleep(interval);
                timeout -= interval;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static synchronized void disconnect() {
        Logger.debugWithThread("NLMain.disconnect() called");
        Main.disconnect();
    }

    /**
     * デバッグモードか？
     * @return デバッグモードならtrue
     */
    public static boolean isDebugMode() {
        return debugMode;
    }

    static synchronized void setDebugMode(boolean enabled) {
        debugMode = enabled;
        System.setProperty("dareka.debug", String.valueOf(enabled));
        DefaultLoggerHandler.setDebug(enabled);
        if (extLoggerHandler != null) {
            extLoggerHandler.setFileLogging(enabled);
        }
    }

    /**
     * GUI起動しているか？
     * @return GUI起動しているならtrue
     */
    public static boolean isLaunchGUI() {
        return guiLauncher != null;
    }

    /**
     * ログウィンドウにタブを追加する。GUI 起動していなければ何もしない。
     * @see javax.swing.JTabbedPane#addTab(String, Icon, Component, String)
     * @since NicoCache_nl+110122mod
     */
    public static synchronized void addTab(
            String title, Icon icon, Component component, String tip) {
        if (guiLauncher != null) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    guiLauncher.getTabbedPane().addTab(title, icon, component, tip);
                });
            } catch (InterruptedException | InvocationTargetException e) {
                Logger.error(e);
            }
        }
    }

    static class ExtLoggerHandler extends DefaultLoggerHandler {
        static BoundedLogFile writer;

        public ExtLoggerHandler() {
            if (writer == null
                    && (guiLauncher == null || NLMain.isDebugMode())) {
                reset();
            }
        }

        void reset() {
            synchronized (ExtLoggerHandler.class) {
                closeWriter();
                String logfile = System.getProperty("dareka.logfile");
                if (logfile != null && logfile.length() > 0) {
                    try {
                        writer = new BoundedLogFile(Path.of(logfile));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        void close() {
            synchronized (ExtLoggerHandler.class) {
                closeWriter();
            }
        }

        void setFileLogging(boolean enabled) {
            if (enabled) {
                reset();
            } else {
                close();
            }
        }

        private static void closeWriter() {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                writer = null;
            }
        }

        private static void writeLine(String message) {
            synchronized (ExtLoggerHandler.class) {
                if (writer == null) {
                    return;
                }
                try {
                    writer.println(message);
                } catch (IOException e) {
                    e.printStackTrace();
                    closeWriter();
                }
            }
        }

        @Override
        public void debug(String message) {
            super.debug(message);
            if (NLMain.isDebugMode()) {
                String debugMes = "DEBUG: " + message;
                writeLine(withTimestamp(debugMes));
                GUILauncher.append(debugMes);
            }
        }

        @Override
        public void info(String message) {
            this.info(message, true);
        }

        protected void info(String message, boolean appendGUI) {
            super.info(message);
            writeLine(withTimestamp(message));
            if (appendGUI) GUILauncher.append(message);
        }

        @Override
        public void warning(String message) {
            super.warning(message);
            writeLine(withTimestamp(message));
            GUILauncher.append(message);
        }

        @Override
        public void error(Throwable t) {
            this.info(getStackTraceString(t));
        }

        private static final SimpleDateFormat SDF =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        protected String withTimestamp(String message) {
            return String.format("[%s] %s", SDF.format(new Date()), message);
        }
    }

    static class ExtLogger extends ExtLoggerHandler {
        String prefix, debugKey;
        boolean guiOnly;
        GUILauncher.LogPane extPane;

        public ExtLogger(String prefix, String debugKey, String tip, boolean guiOnly) {
            if (guiLauncher != null) {
                String title = prefix;
                if (title == null) {
                    if (debugKey != null && debugKey.endsWith("Debug")) {
                        title = debugKey.substring(0, debugKey.length() - 5);
                    } else {
                        title = tip.split("[\\s\\+_-]+")[0];
                    }
                } else if (title.startsWith("TABONLY:")) {
                    title = title.substring(8);
                    prefix = null;
                }
                extPane = guiLauncher.addExtPane(title, tip);
            }
            if (prefix != null) {
                this.prefix = prefix + ": ";
            } else {
                this.prefix = "";
            }
            this.debugKey = debugKey;
            this.guiOnly = guiOnly;
        }

        @Override
        public void debug(String message) {
            if (Boolean.getBoolean(debugKey)) {
                String debugMes = "DEBUG: " + prefix + message;
                if (!guiOnly) super.info(debugMes, false);
                if (extPane != null) {
                    // GUIではデバッグログもMainへ出力する
                    GUILauncher.append(debugMes);
                    GUILauncher.append(extPane, "DEBUG: " + message);
                }
            }
        }

        @Override
        public void info(String message) {
            if (!guiOnly) super.info(prefix + message);
            if (extPane != null)
                GUILauncher.append(extPane, message);
        }

        @Override
        public void warning(String message) {
            if (!guiOnly) super.warning(prefix + message);
            if (extPane != null)
                GUILauncher.append(extPane, message);
        }

        @Override
        public void error(Throwable t) {
            this.info(getStackTraceString(t));
        }
    }

    /**
     * 拡張ロガーのインスタンスを取得する。GUI 起動の場合は専用タブを追加する。
     * なお、デバッグモードの出力は {@linkplain LoggerHandler#info(String)}
     * を使って通常ログとして出力するので注意。
     *
     * @param extension 呼び出し元の Extension
     * @param prefix ログに付加するプレフィックス(GUI 起動の場合はタブ名も兼ねる)。
     * プレフィックスが "TABONLY:" から始まる場合はタブ名としてのみ扱う。
     * また、null を指定した場合はログにプレフィックスは付加しない。
     * その場合のタブ名は debugKey から末尾の "Debug" を除いた文字列となる。
     * debugKey も null の場合は Extension のバージョン文字列を用いる。
     * @param debugKey Extension 固有のデバッグモードを判断するプロパティキー。
     * nullを指定した場合は prefix + "Debug" を使用する
     * @param guiOnly true なら GUI の専用タブのみに出力する
     * @return 拡張ロガーのインスタンス
     * @since NicoCache_nl+110125mod
     */
    public static LoggerHandler getExtLogger(
            Extension2 extension, String prefix, String debugKey, boolean guiOnly) {
        if (debugKey == null && prefix != null) {
            debugKey = prefix + "Debug";
        }
        return new ExtLogger(prefix, debugKey, extension.getVersionString(), guiOnly);
    }

    /**
     * guiOnly = false
     * @see #getExtLogger(Extension2, String, String, boolean)
     */
    public static LoggerHandler getExtLogger(
            Extension2 extension, String prefix, String debugKey) {
        return getExtLogger(extension, prefix, debugKey, false);
    }
    static class GUILauncher {
    static File propFile;
    static File extIconFile;
    static Image iconImage;
    static {
        refreshUserFiles();
        if (extIconFile.exists()) {
            ImageIcon icon = new ImageIcon(extIconFile.getPath());
            if (icon.getIconWidth() > 0) {
                iconImage = icon.getImage();
            }
        }
        if (iconImage == null) {
            URL url = GUILauncher.class.getResource("GUILauncherIcon.gif");
            iconImage = new ImageIcon(url).getImage();
        }
    }
    static ConfigGUI config = new ConfigGUI();

    static void refreshUserFiles() {
        propFile = NicoCachePaths.guiPropertyFile();
        extIconFile = NicoCachePaths.userFile("NicoCacheGUI_Icon.gif");
    }

    static LauncherTray tray;
    static LogWindow logWindow;
    static Rectangle logWindowRect;
    static GuiLogWebSocketTransport logTransport;
    static final GuiLogDisplayQueue displayQueue = new GuiLogDisplayQueue();
    static final AtomicBoolean displayDrainScheduled = new AtomicBoolean();
    static final int DISPLAY_BATCH_SIZE = 1024;

    public GUILauncher() {
        boolean debugMode = config.getBoolean("DebugMode");
        if (System.getProperty("dareka.debug") != null) { // コマンドライン優先
            debugMode = Boolean.getBoolean("dareka.debug");
        }
        if (System.getProperty("dareka.logfile") == null) {
            System.setProperty("dareka.logfile",
                    NicoCachePaths.debugLogFile().getAbsolutePath());
        }
        if (debugMode) {
            System.setProperty("dareka.debug", "true");
        }
        try {
            final boolean debugModeLocal = debugMode;
            SwingUtilities.invokeAndWait(() -> {
                try {
                    if (SystemTray.isSupported()) {
                        tray = new LauncherTray();
                    }
                } catch (NoClassDefFoundError e) {
                    Logger.debugWithThread(e);
                }
                logWindow = new LogWindow(debugModeLocal);
                logWindowRect = logWindow.frame.getBounds();
            });
            displayQueue.clear();
            displayDrainScheduled.set(false);
            logTransport = GuiLogWebSocketTransport.start(
                    GUILauncher::receiveLogBatch);
        } catch (InterruptedException | InvocationTargetException e) {
            Logger.error(e);
        } catch (IOException | RuntimeException e) {
            System.err.println(
                    "GUIログWebSocketを開始できないため直接表示へ切り替えます: "
                    + e);
        }
    }

    void init() {
        Logger.debug("launching NicoCacheGUI mode");

        // Windowsの場合は一度ウィンドウを表示してEventQueueThreadを走らせる
        // また、シャットダウン対策のためJNIを使ってWM_ENDSESSIONを処理する
        if (System.getProperty("os.name").startsWith("Windows")) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    logWindow.frame.setVisible(true);
                });
            } catch (InterruptedException | InvocationTargetException e) {
                Logger.error(e);
                return;
            }

            String libname = "NicoCacheGUI_native";
            if (System.getProperty("os.arch").equals("amd64")) {
                libname += "64";
            }
            File nativeLibrary =
                    NicoCachePaths.applicationFile(libname + ".dll");
            if (nativeLibrary.exists()) {
                try {
                    System.load(nativeLibrary.getAbsolutePath());
                    String params = config.getKeyValue("DisableSuspend");
                    NLMain.setNativeWindowProc(logWindow.frame, params);
                    Logger.debug(libname + " loaded");
                } catch (UnsatisfiedLinkError e) {
                    Logger.error(e);
                }
            }
        }
        try {
            SwingUtilities.invokeAndWait(() -> {
                logWindow.frame.setVisible(
                        tray == null || !config.getBoolean("HideWindow"));
                if (tray != null) {
                    Logger.debug("trayIconSize=" + tray.trayIcon.getSize());
                }
            });
        } catch (InterruptedException | InvocationTargetException e) {
            Logger.error(e);
        }
    }

    void activatePrimaryTab() {
        if (logWindow == null) {
            return;
        }
        Runnable f = () -> {
            getTabbedPane().setSelectedIndex(0);
            logWindow.mainPane.setBackLog(false);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            f.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(f);
            } catch (InterruptedException | InvocationTargetException e) {
                Logger.error(e);
            }
        }
    }

    void close() {
        Runnable closeGui = () -> {
                GuiLogWebSocketTransport transport = logTransport;
                logTransport = null;
                if (transport != null) {
                    transport.close();
                }
                if (tray != null) {
                    try {
                        tray.close();
                    } catch (Throwable t) {
                        Logger.error(t);
                    } finally {
                        tray = null;
                    }
                }
                if (logWindow != null && logWindow.frame != null) {
                    for (LogPane pane : logWindow.tabs.values()) {
                        pane.dispose();
                    }
                    Rectangle rect = logWindow.frame.getBounds();
                    if (!rect.equals(logWindowRect)) {
                        config.setInteger("LogWindowX", rect.x);
                        config.setInteger("LogWindowY", rect.y);
                        config.setInteger("LogWindowW", rect.width);
                        config.setInteger("LogWindowH", rect.height);
                    }
                    logWindow.frame.repaint();
                    logWindow.frame.dispose();
                    logWindow.frame = null;
                }
                config.save();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            closeGui.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(closeGui);
        } catch (InterruptedException | InvocationTargetException e) {
            Logger.error(e);
        }
    }

    JTabbedPane getTabbedPane() {
        return logWindow.tabbedPane;
    }

    LogPane addExtPane(String title, String tip) {
        LogPane[] result = { null };
        Runnable addPane = () -> {
            result[0] = logWindow.addTab(
                    title,
                    tip,
                    config.getPositiveInteger("MaxLines") / 2,
                    Boolean.getBoolean("dedupeLogMessage"));
        };
        if (SwingUtilities.isEventDispatchThread()) {
            addPane.run();
            return result[0];
        }
        try {
            SwingUtilities.invokeAndWait(addPane);
        } catch (InterruptedException | InvocationTargetException e) {
            Logger.error(e);
        }
        return result[0];
    }

    static void append(String log) {
        append("main", log);
    }

    static void append(LogPane pane, String log) {
        if (pane != null) {
            append(pane.channel, log);
        }
    }

    private static void append(String channel, String log) {
        GuiLogWebSocketTransport transport = logTransport;
        if (transport != null) {
            transport.publish(channel, log);
            return;
        }
        LogWindow window = logWindow;
        if (window != null && window.mainPane != null) {
            receiveLogBatch(List.of(new GuiLogEvent(channel, log)));
        }
    }

    private static void receiveLogBatch(List<GuiLogEvent> events) {
        if (logWindow == null || events.isEmpty()) {
            return;
        }
        displayQueue.offerAll(events);
        scheduleDisplayDrain();
    }

    private static void scheduleDisplayDrain() {
        if (displayDrainScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(GUILauncher::drainDisplayQueue);
        }
    }

    private static void drainDisplayQueue() {
        LogWindow window = logWindow;
        List<GuiLogEvent> events = displayQueue.drain(DISPLAY_BATCH_SIZE);
        if (window != null && window.mainPane != null) {
            Map<LogPane, List<String>> messagesByPane = new LinkedHashMap<>();
            for (GuiLogEvent event : events) {
                LogPane pane = window.channels.get(event.getChannel());
                if (pane == null) {
                    pane = window.mainPane;
                }
                messagesByPane.computeIfAbsent(
                        pane, ignored -> new ArrayList<>())
                        .add(event.getMessage());
            }
            for (Map.Entry<LogPane, List<String>> entry
                    : messagesByPane.entrySet()) {
                entry.getKey().appendBatch(entry.getValue());
            }
        }
        if (displayQueue.isEmpty()) {
            displayDrainScheduled.set(false);
            if (!displayQueue.isEmpty()) {
                scheduleDisplayDrain();
            }
        } else {
            SwingUtilities.invokeLater(GUILauncher::drainDisplayQueue);
        }
    }

    static class ConfigGUI {
        Properties defaults, properties;
        boolean changed = true;

        ConfigGUI() {
            defaults = new Properties();
            defaults.setProperty("LogWindowX", "16");
            defaults.setProperty("LogWindowY", "16");
            defaults.setProperty("LogWindowW", "640");
            defaults.setProperty("LogWindowH", "480");
            defaults.setProperty("LogWindowAlwaysOnTop", "true");
            defaults.setProperty("LogWindowLineWrap", "true");
            defaults.setProperty("DebugMode", "false");
            defaults.setProperty("ExitOnClose", "false");
            defaults.setProperty("FlipColor", "false");
            defaults.setProperty("FontName", Font.MONOSPACED);
            defaults.setProperty("FontSize", "14");
            defaults.setProperty("HideWindow", "false");
            defaults.setProperty("MaxLines", "1000");
            defaults.setProperty("MaxLinesHard", "30000");

            // 全ての値を書き出す必要は無いのでデフォルト値指定はしない
            properties = new Properties();
            for (Object o : defaults.keySet()) {
                String key = (String) o;
                properties.setProperty(key, defaults.getProperty(key));
            }
            if (propFile.exists()) {
                load();
            }
            if (properties.remove("DebugLog") != null) {
                changed = true;
            }
        }

        void load() {
            FileInputStream in = null;
            try {
                in = new FileInputStream(propFile);
                properties.load(in);
                changed = false;
            } catch (IOException e) {
                Logger.error(e);
            } finally {
                CloseUtil.close(in);
            }
        }

        void save() {
            if (!changed) return;

            FileOutputStream out = null;
            try {
                out = new FileOutputStream(propFile);
                properties.store(out, "NicoCache_nl GUI Properties");
                changed = false;
            } catch (IOException e) {
                Logger.error(e);
            } finally {
                CloseUtil.close(out);
            }
        }

        String getProperty(String key) {
            return properties.getProperty(key);
        }

        Object setProperty(String key, String value) {
            changed = true;
            return properties.setProperty(key, value);
        }

        boolean getBoolean(String key) {
            String value = properties.getProperty(key);
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            return Boolean.parseBoolean(defaults.getProperty(key));
        }

        void setBoolean(String key, boolean value) {
            changed = true;
            setProperty(key, String.valueOf(value));
        }

        int getInteger(String key) {
            try {
                return Integer.parseInt(properties.getProperty(key));
            } catch (Exception e) {
                Logger.error(e);
            }
            return Integer.parseInt(defaults.getProperty(key));
        }

        int getPositiveInteger(String key) {
            int value = getInteger(key);
            if (value > 0) {
                return value;
            }
            return Integer.parseInt(defaults.getProperty(key));
        }

        void setInteger(String key, int value) {
            setProperty(key, String.valueOf(value));
        }

        String getKeyValue(String key) {
            String value = getProperty(key);
            if (value == null) {
                return "";
            }
            return key + "=" + value + ";";
        }
    }

    static class LauncherTray {
        TrayIcon trayIcon;

        LauncherTray() {
            PopupMenu popup = new PopupMenu();

            addMenuItem("ニコニコ動画を開く", (ActionEvent e) -> {
                openBrowser("https://www.nicovideo.jp/");
            }, popup);

            addMenuItem("キャッシュページを開く", (ActionEvent e) -> {
                openBrowser("http://www.nicovideo.jp/cache/");
            }, popup);

            addMenuItem("キャッシュフォルダを開く", (ActionEvent e) -> {
                openFolder(new File(Cache.getCacheDir()));
            }, popup);

            addMenuItem("利用者データフォルダーを開く", (ActionEvent e) -> {
                openFolder(NicoCachePaths.dataRoot().toFile());
            }, popup);

            popup.addSeparator();

            addMenuItem("ログウインドウを表示", (ActionEvent e) -> {
                logWindow.frame.setVisible(true);
            }, popup);

            addMenuItem("プロキシ接続を全切断", (ActionEvent e) -> {
                NLMain.disconnect();
            }, popup);

            popup.addSeparator();

            addMenuItem("NicoCache_nl を終了", (ActionEvent e) -> {
                NLMain.shutdown();
            }, popup);

            trayIcon = new TrayIcon(iconImage, Main.getVersion(), popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener((ActionEvent e) -> {
                logWindow.frame.setVisible(true); // ダブルクリックでログを表示
            });
            try {
                SystemTray.getSystemTray().add(trayIcon);
            } catch (AWTException e) {
                Logger.error(e);
            }
        }

        void close() {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
                trayIcon = null;
            }
        }

        void addMenuItem(String label, ActionListener listener, PopupMenu popup) {
            MenuItem menuItem = new MenuItem(label);
            menuItem.addActionListener(listener);
            popup.add(menuItem);
        }

        void openBrowser(String url) {
            Desktop desk = Desktop.getDesktop();
            try {
                desk.browse(new URL(url).toURI());
            } catch (Exception e) {
                Logger.error(e);
            }
        }

        void openFolder(File file) {
            Desktop desk = Desktop.getDesktop();
            try {
                desk.open(file);
            } catch (Exception e) {
                Logger.error(e);
            }
        }
    }

    static class LogWindow {
        JFrame frame = new JFrame();
        JTabbedPane tabbedPane = new JTabbedPane();
        LinkedHashMap<Integer, LogPane> tabs = new LinkedHashMap<>();
        LinkedHashMap<String, LogPane> channels = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> extensionTitleOccurrences =
                new LinkedHashMap<>();
        LogSearchHistory searchHistory = new LogSearchHistory(config);
        JCheckBox debugLoggingCheckBox;
        LogPane mainPane;
        String windowTitle = "NicoCache_nl";

        LogWindow(boolean debugMode) {
            int maxLines = config.getPositiveInteger("MaxLines");
            frame.getRootPane().setName("log.frame");
            frame.getRootPane().getAccessibleContext()
                    .setAccessibleName("log.frame");
            tabbedPane.setName("log.tabs");
            tabbedPane.getAccessibleContext().setAccessibleName("log.tabs");
            debugLoggingCheckBox = new JCheckBox(
                    "デバッグログを debug.log に記録", debugMode);
            debugLoggingCheckBox.setName("log.debug-file");
            debugLoggingCheckBox.getAccessibleContext()
                    .setAccessibleName("log.debug-file");
            debugLoggingCheckBox.setToolTipText(
                    "アプリケーションフォルダーの debug.log に記録します");
            debugLoggingCheckBox.addActionListener((ActionEvent event) -> {
                boolean enabled = debugLoggingCheckBox.isSelected();
                config.setBoolean("DebugMode", enabled);
                NLMain.setDebugMode(enabled);
                setDebugModeTitle(enabled);
            });
            mainPane = addTab("main", "通常ログ", maxLines,
                    Boolean.getBoolean("dedupeLogMessage"));
            // このタイミングではconfigが読み込まれていない可能性が高いので
            // 読み込み完了通知を受け取って設定する
            Config.addObserver(new ConfigObserver() {
                @Override
                public void update(Config config) {
                    mainPane.setDedupe(Boolean.getBoolean("dedupeLogMessage"));
                    Config.removeObserver(this);
                }
            });
            setDebugModeTitle(debugMode);
            tabbedPane.addChangeListener((ChangeEvent e) -> {
                LogPane pane = tabs.get(tabbedPane.getSelectedIndex());
                if (pane != null) {
                    pane.updateLogWindowTitle();
                } else {
                    appendTitle(null); // Extension独自タブ
                }
            });
            JPanel content = new JPanel(new BorderLayout());
            content.add(debugLoggingCheckBox, BorderLayout.NORTH);
            content.add(tabbedPane, BorderLayout.CENTER);
            frame.add(content);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    if (tray == null || config.getBoolean("ExitOnClose")) {
                        NLMain.shutdown();
                    } else {
                        frame.setVisible(false);
                    }
                }
            });
            frame.setAlwaysOnTop(
                    config.getBoolean("LogWindowAlwaysOnTop"));
            frame.setMinimumSize(new Dimension(320, 200));
            frame.setBounds(visibleBounds(
                    config.getInteger("LogWindowX"),
                    config.getInteger("LogWindowY"),
                    config.getPositiveInteger("LogWindowW"),
                    config.getPositiveInteger("LogWindowH")));
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setIconImage(iconImage);
            frame.setTitle(windowTitle);
        }

        private static Rectangle visibleBounds(
                int x, int y, int width, int height) {
            Rectangle requested = new Rectangle(
                    x, y, Math.max(320, width), Math.max(200, height));
            Rectangle targetScreen = null;
            long largestIntersection = -1L;
            for (GraphicsDevice device : GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getScreenDevices()) {
                GraphicsConfiguration configuration =
                        device.getDefaultConfiguration();
                Rectangle candidate = configuration.getBounds();
                Rectangle intersection = requested.intersection(candidate);
                long area = intersection.isEmpty()
                        ? 0L
                        : (long) intersection.width * intersection.height;
                if (area > largestIntersection) {
                    largestIntersection = area;
                    targetScreen = candidate;
                }
            }
            if (targetScreen == null || targetScreen.isEmpty()) {
                return requested;
            }
            int usableWidth = Math.min(requested.width, targetScreen.width);
            int usableHeight = Math.min(requested.height, targetScreen.height);
            int maxX = targetScreen.x + targetScreen.width - usableWidth;
            int maxY = targetScreen.y + targetScreen.height - usableHeight;
            int visibleX = Math.max(
                    targetScreen.x, Math.min(x, maxX));
            int visibleY = Math.max(
                    targetScreen.y, Math.min(y, maxY));
            return new Rectangle(
                    visibleX, visibleY, usableWidth, usableHeight);
        }

        void appendTitle(String append) {
            if (append != null) {
                frame.setTitle(windowTitle + " (" + append + ")");
            } else {
                frame.setTitle(windowTitle);
            }
        }

        LogPane addTab(String title, String tip, int maxLines, boolean dedupe) {
            int tabIndex = tabbedPane.getTabCount();
            String historyKey;
            if ("main".equals(title)) {
                historyKey = title;
            } else {
                int occurrence = extensionTitleOccurrences.getOrDefault(
                        title, 0);
                extensionTitleOccurrences.put(title, occurrence + 1);
                historyKey = "extension:" + title + ":" + occurrence;
            }
            final LogPane pane = new LogPane(
                    title, tip, maxLines, dedupe, this, tabIndex,
                    searchHistory, historyKey);
            if ("main".equals(title)) {
                pane.popup.addSeparator();

                addCheckBoxMenuItem("右端で折り返す",
                        "LogWindowLineWrap", (ChangeEvent e) -> {
                    boolean value = isSelected(e);
                    for (LogPane pane1 : tabs.values()) {
                        pane1.textArea.setLineWrap(value);
                    }
                    config.setBoolean("LogWindowLineWrap", value);
                }, pane.popup);

                addCheckBoxMenuItem("常に最前面に表示",
                        "LogWindowAlwaysOnTop", (ChangeEvent e) -> {
                    boolean value = isSelected(e);
                    frame.setAlwaysOnTop(value);
                    config.setBoolean("LogWindowAlwaysOnTop", value);
                }, pane.popup);

                if (tray != null) {
                    pane.popup.addSeparator();

                    addCheckBoxMenuItem("起動時に隠す",
                            "HideWindow", pane.popup);
                    addCheckBoxMenuItem("「閉じる」で終了",
                            "ExitOnClose",pane.popup);
                } else {
                    Logger.warning("SystemTray not supported.");
                }
            }
            tabs.put(tabbedPane.getTabCount(), pane);
            channels.put(historyKey, pane);
            tabbedPane.addTab(title, null, pane.searchPanel.getComponent(), tip);

            return pane;
        }

        void addCheckBoxMenuItem(String label, String key, JPopupMenu popup) {
            final String configKey = key;
            addCheckBoxMenuItem(label, configKey, (ChangeEvent e) -> {
                config.setBoolean(configKey, isSelected(e));
            }, popup);
        }

        void addCheckBoxMenuItem(String label, String key,
                ChangeListener listener, JPopupMenu popup) {
            boolean checked = config.getBoolean(key);
            JCheckBoxMenuItem menuItem = new JCheckBoxMenuItem(label, checked);
            String componentName = menuComponentName(key);
            menuItem.setName(componentName);
            menuItem.getAccessibleContext().setAccessibleName(componentName);
            menuItem.addChangeListener(listener);
            popup.add(menuItem);
        }

        private void setDebugModeTitle(boolean enabled) {
            windowTitle = enabled
                    ? "NicoCache_nl：デバッグモード"
                    : "NicoCache_nl";
            appendTitle(null);
        }

        private static String menuComponentName(String key) {
            if ("LogWindowLineWrap".equals(key)) {
                return "log.wrap";
            }
            if ("LogWindowAlwaysOnTop".equals(key)) {
                return "log.always-on-top";
            }
            if ("HideWindow".equals(key)) {
                return "log.hide-on-start";
            }
            if ("ExitOnClose".equals(key)) {
                return "log.exit-on-close";
            }
            return "log.option." + key;
        }

        boolean isSelected(ChangeEvent e) {
            return ((JCheckBoxMenuItem)e.getSource()).isSelected();
        }
    }

    static class LogPane {
        JTextArea textArea = new JTextArea();
        JPopupMenu popup = new JPopupMenu();
        JScrollPane scrollPane = new JScrollPane(textArea,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        LogBuffer buffer = new LogBuffer();
        LogSearchPanel searchPanel;
        int maxLines;
        int maxLinesInBacklog;
        boolean dedupe;
        LogWindow logWindow;
        final String channel;

        LogPane(String title, String tip,
                int maxLines, boolean dedupe, LogWindow logWindow,
                int tabIndex, LogSearchHistory searchHistory,
                String historyKey) {
            String componentPrefix;
            if ("main".equals(title)) {
                componentPrefix = "log." + title;
            } else {
                componentPrefix = "log.extension-" + tabIndex;
            }
            textArea.setName(componentPrefix + ".text");
            textArea.getAccessibleContext()
                    .setAccessibleName(componentPrefix + ".text");
            popup.setName(componentPrefix + ".popup");
            popup.getAccessibleContext()
                    .setAccessibleName(componentPrefix + ".popup");
            scrollPane.setName(componentPrefix + ".scroll");
            scrollPane.getAccessibleContext()
                    .setAccessibleName(componentPrefix + ".scroll");
            this.maxLines = Math.min(
                    Math.max(1, maxLines),
                    config.getPositiveInteger("MaxLinesHard"));
            this.dedupe = dedupe;
            this.logWindow = logWindow;
            this.channel = historyKey;
            this.maxLinesInBacklog =
                    config.getPositiveInteger("MaxLinesHard");
            setupTextArea();
            setupPopupMenu();
            setupScrollPane();
            searchPanel = new LogSearchPanel(
                    textArea, scrollPane, buffer, searchHistory,
                    historyKey, componentPrefix);
        }

        void setupTextArea() {
            textArea.setEditable(false);
            textArea.setFont(new Font(
                    config.getProperty("FontName"),
                    Font.PLAIN,
                    config.getPositiveInteger("FontSize")));
            textArea.setLineWrap(
                    config.getBoolean("LogWindowLineWrap"));
            textArea.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    updateLogWindowTitle();
                }
            });
            if (config.getBoolean("FlipColor")) {
                Color bg = textArea.getBackground();
                Color fg = textArea.getForeground();
                textArea.setBackground(fg);
                textArea.setForeground(bg);
            }
        }

        void setupPopupMenu() {
            JMenuItem menuItem;

            menuItem = new JMenuItem("コピー");
            configureMenuIdentity(menuItem, "log.copy");
            menuItem.addActionListener((ActionEvent e) -> {
                textArea.copy();
            });
            popup.add(menuItem);

            menuItem = new JMenuItem("全選択");
            configureMenuIdentity(menuItem, "log.select-all");
            menuItem.addActionListener((ActionEvent e) -> {
                textArea.selectAll();
            });
            popup.add(menuItem);

            // ログウインドウ内の右クリックメニュー
            textArea.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    // 右クリックの場合はポップアップを表示
                    if (e.getButton() == MouseEvent.BUTTON3) {
                        popup.show((Component)(e.getSource()), e.getX(), e.getY());
                    }
                }
            });
        }

        private static void configureMenuIdentity(
                JMenuItem menuItem, String name) {
            menuItem.setName(name);
            menuItem.getAccessibleContext().setAccessibleName(name);
        }

        void setupScrollPane() {
            scrollPane.addMouseWheelListener(backLogListener);
            scrollPane.getVerticalScrollBar().addMouseListener(backLogListener);
            scrollPane.getViewport().addChangeListener(new ChangeListener() {
                boolean changedByMyself;
                int lastHeight;
                @Override
                public void stateChanged(ChangeEvent e) {
                    if (changedByMyself) {
                        changedByMyself = false;
                        return;
                    }
                    int height =  textArea.getHeight();
                    if (lastHeight != height && !isBackLog()) {
                        // Viewportを一番下に移動
                        Rectangle rect = scrollPane.getViewport().getViewRect();
                        rect.setLocation(rect.x, height - rect.height);
                        changedByMyself = true; // scrollRectToVisibleは時々stateChangedを発生させる
                        textArea.scrollRectToVisible(rect);
                        changedByMyself = true;
                    }
                    lastHeight = height;
                }
            });
        }

        DefaultCaret caret = new DefaultCaret();
        MouseAdapter backLogListener = new MouseAdapter() {
            JScrollBar scrollBar = scrollPane.getVerticalScrollBar();
            {
                caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
                textArea.setCaret(caret);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                updateBackLog(e);
            }
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                updateBackLog(e);
            }
            private void updateBackLog(MouseEvent e) {
                int max = scrollBar.getMaximum() - scrollBar.getVisibleAmount();
                setBackLog(scrollBar.getValue() < max);
                updateLogWindowTitle();
            }
        };

        boolean isBackLog() {
            return caret.getUpdatePolicy() == DefaultCaret.NEVER_UPDATE;
        }

        void setBackLog(boolean backlog) {
            if (backlog) {
                caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
            } else {
                caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
                caret.setDot(textArea.getDocument().getLength());
            }
        }

        void updateLogWindowTitle() {
            logWindow.appendTitle(isBackLog() ? "バックログ" : null);
        }

        void append(String log) {
            if (textArea == null) {
                return;
            }
            int threashold = isBackLog() ? maxLinesInBacklog : maxLines;
            buffer.append(log, dedupe, threashold);
            searchPanel.requestRefresh();
        }

        void appendBatch(List<String> logs) {
            if (textArea == null || logs.isEmpty()) {
                return;
            }
            int threshold = isBackLog() ? maxLinesInBacklog : maxLines;
            buffer.appendAll(logs, dedupe, threshold);
            searchPanel.requestRefresh();
        }

        void refreshDisplay() {
            searchPanel.refreshNow();
        }

        void setDedupe(boolean dedupe) {
            this.dedupe = dedupe;
        }

        void clearLog() {
            buffer.clear();
            searchPanel.refreshNow();
        }

        void dispose() {
            searchPanel.dispose();
        }
    }
    }
}
