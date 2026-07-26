package dareka;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Font;
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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Properties;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
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
        args = configureLaunchMode(args);
        boolean startGUI = isStartGUI();
        if (startGUI && !FirstRunSetup.runIfRequired(
                Path.of("").toAbsolutePath())) {
            return;
        }
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
            extLoggerHandler.close();
            if (guiLauncher != null) {
                guiLauncher.close();
                System.exit(0);
            }
        }
    }

    private static String[] configureLaunchMode(String[] args) {
        int forwardedCount = 0;
        for (String arg : args) {
            if (!"--headless".equals(arg)) {
                forwardedCount++;
            }
        }
        if (forwardedCount == args.length) {
            return args;
        }

        System.setProperty("dareka.gui", "false");
        String[] forwardedArgs = new String[forwardedCount];
        int forwardedIndex = 0;
        for (String arg : args) {
            if (!"--headless".equals(arg)) {
                forwardedArgs[forwardedIndex++] = arg;
            }
        }
        return forwardedArgs;
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
        if (guiLauncher != null) {
            guiLauncher.activatePrimaryTab();
        }
        Logger.debugWithThread("NLMain.shutdown() called");

        Main.stop();

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
            // do nothing.
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
        static PrintWriter writer;

        public ExtLoggerHandler() {
            if (writer == null) reset();
        }

        void reset() {
            close();
            String logfile = System.getProperty("dareka.logfile");
            if (logfile != null && logfile.length() > 0) {
                try {
                    writer = new PrintWriter(new BufferedWriter(
                            new FileWriter(logfile)), true);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        void close() {
            if (writer != null) {
                writer.close(); writer = null;
            }
        }

        @Override
        public void debug(String message) {
            super.debug(message);
            if (NLMain.isDebugMode()) {
                String debugMes = "DEBUG: " + message;
                if (writer != null) writer.println(withTimestamp(debugMes));
                GUILauncher.appendDebug(debugMes);
            }
        }

        @Override
        public void info(String message) {
            this.info(message, true);
        }

        protected void info(String message, boolean appendGUI) {
            super.info(message);
            if (writer != null) writer.println(withTimestamp(message));
            if (appendGUI) GUILauncher.append(message);
        }

        @Override
        public void warning(String message) {
            super.warning(message);
            if (writer != null) writer.println(withTimestamp(message));
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
                    // GUIの場合はdebugPaneのみに出力する
                    GUILauncher.appendDebug(debugMes);
                    SwingUtilities.invokeLater(() ->
                            extPane.append("DEBUG: " + message));
                }
            }
        }

        @Override
        public void info(String message) {
            if (!guiOnly) super.info(prefix + message);
            if (extPane != null)
                SwingUtilities.invokeLater(() -> extPane.append(message));
        }

        @Override
        public void warning(String message) {
            if (!guiOnly) super.warning(prefix + message);
            if (extPane != null)
                SwingUtilities.invokeLater(() -> extPane.append(message));
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
}

class GUILauncher {
    static File propFile    = new File("NicoCacheGUI.property");
    static File extIconFile = new File("NicoCacheGUI_Icon.gif");
    static ConfigGUI config = new ConfigGUI();
    static Image iconImage;
    static {
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

    static LauncherTray tray;
    static LogWindow logWindow;
    static Rectangle logWindowRect;

    public GUILauncher() {
        boolean debugMode = config.getBoolean("DebugMode");
        if (System.getProperty("dareka.debug") != null) { // コマンドライン優先
            debugMode = Boolean.getBoolean("dareka.debug");
        }
        if (debugMode) {
            System.setProperty("dareka.debug", "true");
            if (System.getProperty("dareka.logfile") == null) {
                System.setProperty("dareka.logfile", config.getProperty("DebugLog"));
            }
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
        } catch (InterruptedException | InvocationTargetException e) {
            Logger.error(e);
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
            if (new File(libname + ".dll").exists()) {
                try {
                    System.loadLibrary(libname);
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
        Runnable f = () -> {
            if (logWindow.debugPane != null) {
                getTabbedPane().setSelectedIndex(1);
                logWindow.debugPane.setBackLog(false);
            } else {
                getTabbedPane().setSelectedIndex(0);
                logWindow.mainPane.setBackLog(false);
            }
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
        try {
            SwingUtilities.invokeAndWait(() -> {
                if (tray != null) {
                    tray.close(); tray = null;
                }
                if (logWindow.frame != null) {
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
            });
        } catch (InterruptedException | InvocationTargetException e) {
            Logger.error(e);
        }
    }

    JTabbedPane getTabbedPane() {
        return logWindow.tabbedPane;
    }

    LogPane addExtPane(String title, String tip) {
        LogPane[] result = { null };
        try {
            SwingUtilities.invokeAndWait(() -> {
                 result[0] = logWindow.addTab(title, tip, config.getInteger("MaxLines") / 2,
                         Boolean.getBoolean("dedupeLogMessage"));
            });
        } catch (InterruptedException | InvocationTargetException e) {
            Logger.error(e);
        }
        return result[0];
    }

    static void append(String log) {
        if (logWindow != null) {
            SwingUtilities.invokeLater(() -> logWindow.mainPane.append(log));
            appendDebug(log);
        }
    }

    static void appendDebug(String log) {
        if (logWindow != null && logWindow.debugPane != null) {
            SwingUtilities.invokeLater(() -> logWindow.debugPane.append(log));
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
            defaults.setProperty("DebugMode", "falae");
            defaults.setProperty("DebugLog", "debug.log");
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
            return Boolean.parseBoolean(properties.getProperty(key));
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
            SystemTray.getSystemTray().remove(trayIcon);
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
        LogPane mainPane, debugPane;
        String windowTitle = "NicoCache_nl";

        LogWindow(boolean debugMode) {
            int maxLines = config.getInteger("MaxLines");
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
            if (debugMode) {
                debugPane = addTab("debug", "デバッグログ", maxLines * 10, false);
                windowTitle += "：デバッグモード";
            }
            tabbedPane.addChangeListener((ChangeEvent e) -> {
                LogPane pane = tabs.get(tabbedPane.getSelectedIndex());
                if (pane != null) {
                    pane.updateLogWindowTitle();
                } else {
                    appendTitle(null); // Extension独自タブ
                }
            });
            frame.add(tabbedPane);
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
            frame.setBounds(
                    config.getInteger("LogWindowX"),
                    config.getInteger("LogWindowY"),
                    config.getInteger("LogWindowW"),
                    config.getInteger("LogWindowH"));
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setIconImage(iconImage);
            frame.setTitle(windowTitle);
        }

        void appendTitle(String append) {
            if (append != null) {
                frame.setTitle(windowTitle + " (" + append + ")");
            } else {
                frame.setTitle(windowTitle);
            }
        }

        LogPane addTab(String title, String tip, int maxLines, boolean dedupe) {
            final LogPane pane = new LogPane(title, tip, maxLines, dedupe, this);
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
            tabbedPane.addTab(title, null, pane.scrollPane, tip);

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
            menuItem.addChangeListener(listener);
            popup.add(menuItem);
        }

        boolean isSelected(ChangeEvent e) {
            return ((JCheckBoxMenuItem)e.getSource()).isSelected();
        }
    }

    static class LogPane {
        static final Font FONT = new Font(
                config.getProperty("FontName"),
                Font.PLAIN,
                config.getInteger("FontSize"));
        JTextArea textArea = new JTextArea();
        JPopupMenu popup = new JPopupMenu();
        JScrollPane scrollPane = new JScrollPane(textArea,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        int maxLines;
        int maxLinesInBacklog;
        boolean dedupe;
        LogWindow logWindow;

        LogPane(String title, String tip,
                int maxLines, boolean dedupe, LogWindow logWindow) {
            this.maxLines = Math.min(maxLines, config.getInteger("MaxLinesHard"));
            this.dedupe = dedupe;
            this.logWindow = logWindow;
            this.maxLinesInBacklog = config.getInteger("MaxLinesHard");
            setupTextArea();
            setupPopupMenu();
            setupScrollPane();
        }

        void setupTextArea() {
            textArea.setEditable(false);
            textArea.setFont(new Font(
                    config.getProperty("FontName"),
                    Font.PLAIN,
                    config.getInteger("FontSize")));
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
            menuItem.addActionListener((ActionEvent e) -> {
                textArea.copy();
            });
            popup.add(menuItem);

            menuItem = new JMenuItem("全選択");
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
            if (replaceCachingProgress(log)) {
                return;
            }
            if (dedupeMessage(log)) {
                textArea.append(log + "\n");
            }

            // 最大ログ行数を超えたら先頭から順に削除
            int threashold = isBackLog() ? maxLinesInBacklog : maxLines;
            if (textArea.getLineCount() > threashold) {
                try {
                    int offset = textArea.getLineEndOffset(
                            textArea.getLineCount() - threashold - 1);
                    textArea.replaceRange(null, 0, offset);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // CMAFキャッシュの進捗は同じ動画IDの行を更新し、ログ行を増やさない.
        private boolean replaceCachingProgress(String message) {
            if (message == null || !message.startsWith("caching ")) {
                return false;
            }
            int separator = message.indexOf(": ");
            if (separator < 0) {
                return false;
            }

            String prefix = message.substring(0, separator + 1);
            javax.swing.text.Element root =
                textArea.getDocument().getDefaultRootElement();
            try {
                for (int i = root.getElementCount() - 1; i >= 0; --i) {
                    javax.swing.text.Element line = root.getElement(i);
                    int start = line.getStartOffset();
                    int end = Math.min(
                        line.getEndOffset(), textArea.getDocument().getLength());
                    if (end > start && "\n".equals(textArea.getText(end - 1, 1))) {
                        --end;
                    }
                    String existing = textArea.getText(start, end - start).strip();
                    if (existing.startsWith(prefix)) {
                        textArea.replaceRange(message, start, end);
                        lastMessage = message;
                        needNewline = false;
                        return true;
                    }
                }
            } catch (Exception e) {
                return false;
            }
            return false;
        }

        void setDedupe(boolean dedupe) {
            this.dedupe = dedupe;
        }

        String lastMessage = null;
        boolean needNewline = false;
        boolean dedupeMessage(String message) {
            if (!dedupe) {
                return true;
            }

            if (lastMessage == null || message == null) {
                lastMessage = message;
                if (needNewline) {
                    textArea.append("\n");
                    needNewline = false;
                }
                return true;
            }
            boolean same = lastMessage.equals(message);
            lastMessage = message;
            if (same) {
                textArea.append("+");
                needNewline = true;
                return false;
            } else {
                if (needNewline) {
                    textArea.append("\n");
                    needNewline = false;
                }
                return true;
            }
        }
    }
}
