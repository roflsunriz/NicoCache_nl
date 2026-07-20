package dareka;

import java.io.File;
import java.io.IOException;
import java.net.Socket;

import dareka.common.Config;
import dareka.common.DefaultLoggerHandler;
import dareka.common.DiskFreeSpace;
import dareka.common.Logger;
import dareka.common.TextUtil;
import dareka.processor.impl.Cache;
import dareka.processor.impl.EasyRewriter;
import dareka.processor.impl.ThumbProcessor;
import dareka.processor.impl.ThumbProcessor2;
import dareka.processor.impl.ViewableLoggerHandler;
import dareka.processor.impl.RewriterProcessor;
import dareka.processor.impl.SecureCookieStripper;

public class Main {

    // 2024-03-24 他に開発が続いているNicoCacheは見つからない. バージョン表記を
    // シンプルにする. 変更前のバージョン表記は以下. +mod表記はChangeLog参照.
    // "NicoCache_nl+150304mod+231111mod (eR) (based on NicoCache v0.45)"

    // public so that external tools can read.
    public static final String VER_STRING = "NicoCache_nl version 2026-07-13";

    // accessor for avoiding static link
    public static String getVersion() {
        return VER_STRING;
    }

    private static Server server;
    private static TlsEndPoint tlsEndPoint;
    private static Thread cleanerHookThread;          // [nl]
    private static DirectoryWatcher directoryWatcher; // [nl]

    public static void stop() {
        if (directoryWatcher != null) { // [nl]
            directoryWatcher.interrupt();
        }
        if (server != null) {
            server.stop();
        }
    }

    public static void main(String[] args) {
        try {
            mainBody();
            if (cleanerHookThread.isAlive()) { // [nl]
                cleanerHookThread.interrupt();
            }
        } catch (Exception e) {
            Logger.error(e);
        }
        cleanerHookThread = null;
    }

    static boolean isDone() { // [nl]
        return server != null && cleanerHookThread == null;
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
        // [nl] iniにしたい人用。でも正確にはiniファイルじゃないよ
        File configFile = new File("config.ini");
        if (configFile.exists() == false) {
            configFile = new File("config.properties");
        }

        // 設定ファイルがなくてデフォルト設定ファイルがあるならリネームして使う
        if (configFile.exists() == false) {
            File defFile = new File("config.properties.default");
            if (defFile.exists()) {
                defFile.renameTo(configFile);
            }
        }

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
        Logger.info("    Running with Java %s(%s) on %s",
                System.getProperty("java.version"),
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

        // [nl] 接続先制限
        if (Boolean.getBoolean("niconicoMode")) {
            Logger.info(" => Only nico|smilevideo.jp domains are processed.");
        }

        // [nl] 速度制限
        int speedLimit = Integer.getInteger("speedLimit", 0);
        if (speedLimit > 0) {
            Logger.info(" => Transfer Speed Limit: %,dMbps", speedLimit);
        }

        Logger.info("title=" + Boolean.getBoolean("title"));

        if (Boolean.getBoolean("resumeDownload")) {
            Logger.info("Resume suspended download: On");
        }

        if (Boolean.getBoolean(("touchCache"))) {
            Logger.info("Touch Cache File: On");
        }

        if (Boolean.getBoolean("dareka.debug")) {
            Logger.info("debug mode");
        }

        // [nl] スクリプト埋め込み設定
        if (Integer.getInteger("scriptOn", 0) != 0) {
            String uri = System.getProperty("scriptTarget", "no_uri_to_replace");
            String text = System.getProperty("scriptText", "");
            if (!uri.equals("no_uri_to_replace") && text.length() > 0) {
                Logger.info("Script replace: On");
                // 簡易フィルタに登録(夏.01)
                EasyRewriter.addSystemFilter("script",
                        "^https?://www\\.nicovideo\\.jp" + uri + "$",
                        "</body>", text + "<CRLF></body>");
            }
        }

        // [nl] ローカルFLVサーバ
        if (Boolean.getBoolean("localFlv")) {
            Logger.info("LocalFlv Server: On");
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
        String cacheFolder = System.getProperty("cacheFolder");
        if (!cacheFolder.equals("cache")) {
            Logger.info("Cache Folder: " + cacheFolder);
        }

        Cache.init();
        Cache.cleanup();
        if (Boolean.getBoolean("displayCacheSizeOnInitialize"))
            Logger.info("total cache size = %s", TextUtil.bytesToString(Cache.size()));

        // [nl] ディスク空き容量の表示
        long freeSize = DiskFreeSpace.get(cacheFolder);
        if (freeSize != Long.MAX_VALUE) {
            int neededSize = Integer.getInteger("needFreeSpace");
            Logger.info("cache folder free space = %s (at least %,d MB)",
                    TextUtil.bytesToString(freeSize), neededSize);
        } else {
            Logger.info("Can't read disk free size. os.name = '%s'",
                    System.getProperty("os.name"));
        }

        // [nl] キャッシュ領域の事前確保
        if (Boolean.getBoolean("cacheAllocateFirst")) {
            Logger.info("Allocate cache space before download: On");
        }

        // [nl] サムネイルキャッシュ
        if (Boolean.getBoolean("cacheThumbnail")) {
            String message = "Thumbnail Cache: On";
            if (!"folder".equals(System.getProperty("thcacheMode"))) {
                Logger.info(message);
                ThumbProcessor.init();
            } else {
                Logger.info(String.format("%s (folder=%s)",
                        message, System.getProperty("thcacheFolder")));
                ThumbProcessor2.init();
            }
        }

        // [nl] api/getthumbinfoキャッシュ
        if (Boolean.getBoolean("cacheGetThumbInfo")) {
            Logger.info("GetThumbInfo Memory Cache: On");
        }

        // [nl] 外部サムネキャッシュ
        if (Boolean.getBoolean("cacheExtThumb")) {
            Logger.info("ExtThumb Memory Cache: On");
        }

        // [nl] SWFキャッシュ関係
        if (Boolean.getBoolean("swfConvert")) {
            Logger.info("SWFCache Convert: On");
        }

        Logger.info("----------");

        CorsLiarManager.getInstance().load();
        SecureCookieStripper.register();

        registerShutdownHook(Thread.currentThread());

        startupDirectoryWatcher(config); // [nl]

        server = new Server(config);
        tlsEndPoint = new TlsEndPoint();
        if (Boolean.getBoolean("enableMitm")) {
            if (!tlsEndPoint.init()) {
                // 終了してしまうとGUIの場合エラーメッセージが読めないので
                // 何もしないループを回しておく
                Logger.info("TLS MitM機能の有効化に失敗したため動作を停止します．");
                server.startNop();
                return;
            }
        }

        TlsClientContextFactory.init();

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
