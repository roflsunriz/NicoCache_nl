package dareka;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import dareka.common.Config;
import dareka.common.DefaultLoggerHandler;
import dareka.common.DiskFreeSpace;
import dareka.common.Logger;
import dareka.common.TextUtil;
import dareka.processor.impl.Cache;
import dareka.processor.impl.ThumbProcessor2;
import dareka.processor.impl.ViewableLoggerHandler;
import dareka.processor.impl.RewriterProcessor;
import dareka.processor.impl.SecureCookieStripper;

public class Main {

    // 2024-03-24 他に開発が続いているNicoCacheは見つからない. バージョン表記を
    // シンプルにする. 変更前のバージョン表記は以下. +mod表記はChangeLog参照.
    // "NicoCache_nl+150304mod+231111mod (eR) (based on NicoCache v0.45)"

    // public so that external tools can read.
    public static final String VER_STRING = "NicoCache_nl version 2026-08-19 (v1.7.2)";

    // accessor for avoiding static link
    public static String getVersion() {
        return VER_STRING;
    }

    private static volatile Server server;
    private static volatile TlsEndPoint tlsEndPoint;
    private static volatile Thread cleanerHookThread;          // [nl]
    private static volatile DirectoryWatcher directoryWatcher; // [nl]
    private static volatile ControlServer controlServer;
    private static volatile boolean done = true;

    public static void stop() {
        if (directoryWatcher != null) { // [nl]
            directoryWatcher.interrupt();
        }
        if (server != null) {
            server.stop();
        }
        shutdownProcessorSchedulers();
    }

    static void markExpectedStop(String mode) {
        ControlServer current = controlServer;
        if (current != null) {
            current.markExpectedStop(mode);
        }
    }

    public static void main(String[] args) {
        done = false;
        try {
            mainBody();
        } catch (Throwable e) {
            Logger.error(e);
        } finally {
            try {
                if (controlServer != null) {
                    controlServer.close();
                }
            } catch (Throwable e) {
                Logger.error(e);
            }
            try {
                if (cleanerHookThread != null
                        && cleanerHookThread.isAlive()) { // [nl]
                    cleanerHookThread.interrupt();
                }
            } catch (Throwable e) {
                Logger.error(e);
            }
            try {
                stop();
            } catch (Throwable e) {
                Logger.error(e);
            }
            cleanerHookThread = null;
            done = true;
        }
    }

    /** Immediately terminate the process after closing active resources. */
    static void forceStop() {
        DiagnosticsLifecycle.stopPlanned("force");
        if (directoryWatcher != null) {
            directoryWatcher.interrupt();
        }
        if (server != null) {
            server.forceStop();
        }
        shutdownProcessorSchedulers();
        Runtime.getRuntime().halt(0);
    }

    private static void shutdownProcessorSchedulers() {
        for (String className : new String[] {
                "dareka.processor.impl.ExtThumbProcessor",
                "dareka.processor.impl.GetThumbInfoProcessor" }) {
            try {
                Class<?> processor = Class.forName(className);
                java.lang.reflect.Method method =
                        processor.getDeclaredMethod("shutdownScheduler");
                method.setAccessible(true);
                method.invoke(null);
            } catch (ReflectiveOperationException | SecurityException error) {
                Logger.debug(error);
            }
        }
    }

    static boolean isDone() { // [nl]
        return done;
    }

    static void disconnect() { // [nl]
        if (server != null) {
            server.cleanupWorkers();
        }
    }

    /**
     * [nl] ディレクトリの更新を監視しているか？(Java7 以降のみ有効)
     * @return ディレクトリの更新を監視していれば true
     * @since NicoCache_nl+111225mod
     */
    public static boolean isDirectoryWatching() {
        return directoryWatcher != null;
    }

    private static void mainBody() throws IOException {
        NicoCachePaths.publishDataRoot(NicoCachePaths.dataRoot());
        // [nl] iniにしたい人用。でも正確にはiniファイルじゃないよ
        File configFile = NicoCachePaths.legacyConfigFile();
        if (configFile.exists() == false) {
            configFile = NicoCachePaths.configFile();
        }

        // 設定ファイルがなくてデフォルト設定ファイルがあるならコピーして使う
        if (configFile.exists() == false) {
            File defFile = NicoCachePaths.defaultConfigFile();
            if (defFile.exists()) {
                File parent = configFile.getParentFile();
                if (parent != null) {
                    Files.createDirectories(parent.toPath());
                }
                Files.copy(
                        defFile.toPath(),
                        configFile.toPath(),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }

        UserTextEncodingMigrator.migrate(configFile.toPath());
        Config config = configure(configFile);

        // ログ表示重複排除設定
        DefaultLoggerHandler.setDedupe(Boolean.getBoolean("dedupeLogMessage"));

        // [nl] ログハンドラを差し替える(夏.??)
        if (Boolean.getBoolean("enableLogHandler")) {
            ViewableLoggerHandler newHandler =
                new ViewableLoggerHandler(Logger.getHandler());
            Logger.setHandler(newHandler);
        }

        Logger.info(VER_STRING);
        Logger.info("    Running with Java %s / %s / %s (%s) on %s",
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("java.vm.name"),
                System.getProperty("os.arch"),
                System.getProperty("os.name"));

        Logger.info("port=" + Integer.getInteger("listenPort"));
        if (System.getProperty("proxyHost").equals("")) {
            Logger.info("direct mode (no secondary proxy)");
        } else {
            Logger.info("proxy host=" + System.getProperty("proxyHost"));
            Logger.info("proxy port=" + Integer.getInteger("proxyPort"));
        }

        // [nl] IP制限
        String allowFrom = System.getProperty("allowFrom");
        if (allowFrom.equals("local")) {
            Logger.info(" => Only localhost Allowed");
        } else if (allowFrom.equals("all")) {
            Logger.info(" => Anyone can access NicoCache");
        } else if (allowFrom.startsWith("lan")) {
            Logger.info(" => Only LAN Address can access NicoCache (mode: %s)",
                    allowFrom);
        } else {
            Logger.info(" => Invalid allowFrom setting. Assumes 'local' mode.");
        }

        // [nl] 速度制限
        int speedLimit = Integer.getInteger("speedLimit", 0);
        if (speedLimit > 0) {
            Logger.info(" => Transfer Speed Limit: %,dMbps", speedLimit);
        }

        Logger.info("title=" + Boolean.getBoolean("title"));

        if (Boolean.getBoolean(("touchCache"))) {
            Logger.info("Touch Cache File: On");
        }

        if (Boolean.getBoolean("dareka.debug")) {
            Logger.info("debug mode");
        }

        // [nl] ローカルファイルサーバ
        if (Boolean.getBoolean("localFileServer")) {
            Logger.info("Local File Server: On");
        }

        // [nl] ローカル書き換え
        if (Boolean.getBoolean("localRewriter")) {
            Logger.info("Local Rewriter: On");
        }

        // [nl] 簡易振り分け機能
        if (Boolean.getBoolean("storeFilter"))
            Logger.info("Storing Folder Filter: On");

        // [nl] キャッシュフォルダの指定
        File cacheFolder = NicoCachePaths.cacheDirectory();
        if (!"cache".equals(System.getProperty("cacheFolder"))) {
            Logger.info("Cache Folder: " + cacheFolder);
        }

        Cache.init();
        Cache.cleanup();
        if (Boolean.getBoolean("displayCacheSizeOnInitialize"))
            Logger.info("total cache size = %s", TextUtil.bytesToString(Cache.size()));

        // [nl] ディスク空き容量の表示
        long freeSize = DiskFreeSpace.get(cacheFolder.getPath());
        if (freeSize != Long.MAX_VALUE) {
            int neededSize = Integer.getInteger("needFreeSpace");
            Logger.info("cache folder free space = %s (at least %,d MB)",
                    TextUtil.bytesToString(freeSize), neededSize);
        } else {
            Logger.info("Can't read disk free size. os.name = '%s'",
                    System.getProperty("os.name"));
        }

        // [nl] サムネイルキャッシュ
        if (Boolean.getBoolean("cacheThumbnail")) {
            Logger.info(String.format("Thumbnail Cache: On (folder=%s)",
                    NicoCachePaths.thumbnailCacheDirectory()));
            ThumbProcessor2.init();
        }

        // [nl] api/getthumbinfoキャッシュ
        if (Boolean.getBoolean("cacheGetThumbInfo")) {
            Logger.info("GetThumbInfo Memory Cache: On");
        }

        // [nl] 外部サムネキャッシュ
        if (Boolean.getBoolean("cacheExtThumb")) {
            Logger.info("ExtThumb Memory Cache: On");
        }

        Logger.info("----------");

        CorsLiarManager.getInstance().load();
        SecureCookieStripper.register();

        registerShutdownHook(Thread.currentThread());

        startupDirectoryWatcher(config); // [nl]

        ProxyPacUpdater.update();
        server = new Server(config);
        controlServer = ControlServer.start(
                NicoCachePaths.dataRoot(), NLMain::shutdown, Main::forceStop);
        Logger.info("controlPort=" + controlServer.getPort());
        tlsEndPoint = new TlsEndPoint();
        if (Boolean.getBoolean("enableMitm")) {
            if (!tlsEndPoint.init()) {
                // 終了してしまうとGUIの場合エラーメッセージが読めないので
                // 何もしないループを回しておく
                Logger.info("TLS MitM機能の有効化に失敗したため動作を停止します．");
                controlServer.markDegraded("tls-keystore-missing-or-invalid");
                server.startNop();
                return;
            }
        }

        if (!TlsClientContextFactory.init()) {
            Logger.info("TLSクライアント証明書ストアの初期化に失敗したため動作を停止します．");
            controlServer.markDegraded("tls-client-keystore-missing-or-invalid");
            server.startNop();
            return;
        }

        controlServer.markReady();
        server.start();
    }

    private static Config configure(File configFile) throws IOException {
        Config config = new NLConfig(configFile);
        Config.setConfig(config);

        return config;
    }

    private static void registerShutdownHook(Thread serverThread) {
        Runtime.getRuntime().addShutdownHook(
                cleanerHookThread = new CleanerHookThread(serverThread));
    }

    // [nl] Java7 ならフォルダ監視スレッドを起動
    private static void startupDirectoryWatcher(Config config) {
        if (Boolean.getBoolean("disableDirectoryWatcher")) {
            return;
        }
        try {
            directoryWatcher = new DirectoryWatcher(config);
            directoryWatcher.start();
        } catch (Throwable t) {
            Logger.error(t);
            directoryWatcher = null;
        }
    }

    /**
     * [nl] RewriterProcessorを返す(主にExtensionから呼ばれる)。
     * 全てのスレッドで共有されているので、不必要な操作は行わないこと。
     *
     * @return RewriterProcessorのインスタンス
     */
    public static RewriterProcessor getRewriterProcessor() {
        return server.getRewriterProcessor();
    }

    public static TlsEndPoint getTlsEndPoint() {
        return tlsEndPoint;
    }

    public static void handleTlsLoopback(Socket client) {
        server.handleTlsLoopback(client);
    }
}
