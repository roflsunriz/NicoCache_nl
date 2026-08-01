package dareka;

import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import dareka.common.CloseUtil;
import dareka.common.Config;
import dareka.common.Logger;
import dareka.extensions.CompleteCache;
import dareka.extensions.Extension2;
import dareka.extensions.Extension;
import dareka.extensions.ExtensionManager;
import dareka.extensions.RequestFilter;
import dareka.extensions.Rewriter;
import dareka.extensions.SystemEventListener;
import dareka.processor.Processor;
import dareka.processor.impl.CacheDirProcessor;
import dareka.processor.impl.CmafCachingProcessor;
import dareka.processor.impl.CommentSavingProcessor;
import dareka.processor.impl.ConnectProcessor;
import dareka.processor.impl.DefaultRequestFilter;
import dareka.processor.impl.EasyRewriter;
import dareka.processor.impl.ExtThumbProcessor;
import dareka.processor.impl.GetPostProcessor;
import dareka.processor.impl.GetThumbInfoProcessor;
import dareka.processor.impl.LocalDirProcessor;
import dareka.processor.impl.RewriterProcessor;
import dareka.processor.impl.ThumbProcessor2;
import dareka.processor.impl.ThumbProcessor;
import dareka.processor.impl.WorkaroundProcessor;

interface AddrPolicy {
    public boolean isAllowAddress(byte[] ip);
}

class AddrPolicyFactory {
    public static AddrPolicy getAddrPolicy(String addr) {
        switch (addr) {
        case "local":
            return null;
        case "all":
            return ip -> true;
        case "lan":
        case "lanC":
            return ip -> ip[0] == -64/*192*/ && ip[1] == -88/*168*/;
        case "lanB":
            return ip -> ip[0] == -84/*172*/ && 16 <= ip[1] && ip[1] < 32;
        case "lanA":
            return ip -> ip[0] == 10;
        }
        return null;
    }
}

public class Server {
    private static final int MAX_WAITING_TIME = 10;

    private Config config;
    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Set<ConnectionManager> liveWorkers =
            Collections.synchronizedSet(new HashSet<>());
    private volatile boolean stopped = false;

    // they are able to be shared among threads.
    private Processor connectProcessor = new ConnectProcessor();
    private Processor getPostProcessor = new GetPostProcessor();
    private RewriterProcessor rewriterProcessor; // [nl]
    private WorkaroundProcessor workaroundProcessor = new WorkaroundProcessor();

    public Server(Config config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        this.config = config;

        // use channel to make it available Socket#getChannel() for non blocking
        // I/O.
        ServerSocketChannel serverCh = ServerSocketChannel.open();
        serverSocket = serverCh.socket();

        // [nl] 拡張Processorのロード
        rewriterProcessor = new RewriterProcessor(config);
        initClassLoader();
// UserFilterはExtensionの後に追加するように変更
        rewriterProcessor.addUserFilter();
    }

    /**
     * Receive an event which indicates completion of worker.
     */
    public void completed(ConnectionManager o) {
        synchronized (liveWorkers) {
            if (liveWorkers.remove(o) == false) {
                Logger.warning("internal error: live worker mismatch");
            }
            if (stopped) {
                // This message may be printed before finalizing, but
                // decided not to make its own flag because this is
                // just looking issue.
                Logger.info("remaining worker=" + liveWorkers.size());
            }
        }
    }

    /**
     * Start the server. The thread which call this method is blocked until
     * stop() is called or some errors occurred.
     */
    public void start() {
        if (stopped) {
            return;
        }

        try { // ensure cleanup
            bindServerSocket();
            acceptServerSocket();
        } finally {
            Logger.info("finalizing");
            try {
                Logger.debugWithThread("stopping extensions"); // [nl]
                cleanupExtensions();
            } catch (Throwable t) {
                Logger.error(t);
            }
            try {
                Logger.debugWithThread("stopping accepting request");
                cleanupServerSocket();
            } catch (Throwable t) {
                Logger.error(t);
            }
            try {
                Logger.debugWithThread("stopping processing request");
                cleanupWorkers();
            } catch (Throwable t) {
                Logger.error(t);
            }
            try {
                Logger.debugWithThread("stopping threads");
                cleanupExecutor();
            } catch (Throwable t) {
                Logger.error(t);
            }
            Logger.info("finalized");

            if (liveWorkers.size() > 0) {
                Logger.warning("internal error: remaining live workers: "
                        + liveWorkers.size());
            }
        }
    }

    /**
     * Start the fake server.
     */
    public void startNop() {
        if (stopped) {
            return;
        }
        while (!stopped) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Stop the server. Please call this method from another thread which called
     * start().
     */
    public synchronized void stop() {
        stopped = true;

        if (periodicalCaller != null) {
            periodicalCaller.interrupt();
        }

        if (!serverSocket.isClosed()) {
            CloseUtil.close(serverSocket);
        }
        executor.shutdown();
    }

    synchronized void forceStop() {
        stopped = true;

        if (periodicalCaller != null) {
            periodicalCaller.interrupt();
        }
        cleanupServerSocket();
        cleanupWorkers();
        executor.shutdownNow();
    }

    private void bindServerSocket() {
        int port = Integer.getInteger("listenPort");
        String from = System.getProperty("allowFrom", "local");

        // [nl] 許可ホストの設定(夏.03)
        setAddrPolicy(from);

        try {
            // [nl] IP制限
            if(!from.equals("local")) {
                serverSocket.bind(new InetSocketAddress(port));
            } else {
                serverSocket.bind(new InetSocketAddress(
                        InetAddress.getByName(null), port));
            }
        } catch (BindException e) {
            Logger.info("ポート %d が既に使われているため起動できません。", port);
            stop();
        } catch (IOException e) {
            Logger.error(e);
            stop();
        }
    }

    // [nl] デフォルトはlocalhostのみ許可
    private AddrPolicy allowFrom = null;
    public void setAddrPolicy(String param/*AddrPolicy policy*/) {
        allowFrom = AddrPolicyFactory.getAddrPolicy(param);
    }

    private boolean checkIpAddr(InetAddress ia) {
        if (ia.isLoopbackAddress())
            return true;
        if (allowFrom == null)
            return false;
        byte[] ip = ia.getAddress();
        return allowFrom.isAllowAddress(ip);
    }

    private void acceptServerSocket() {
        try {
            boolean timeoutSupportedOrUnknown = true;
            int timeout = Integer.getInteger("readTimeout");

            while (!stopped) {
                Socket client = serverSocket.accept();

                try { // ensure client.close() even in errors.
                    if (timeoutSupportedOrUnknown) {
                        client.setSoTimeout(timeout);
                        if (client.getSoTimeout() != timeout) {
                            Logger.warning("read timeout is not supported");
                            timeoutSupportedOrUnknown = false;
                        }
                    }

                    // [nl] lanの判定
                    if (!checkIpAddr(client.getInetAddress())) {
                        Logger.info("Forbidden address: " +
                                client.getInetAddress().getHostAddress());
                        client.close();
                        continue;
                    }

                    synchronized (this) { // avoid conflicting with stop()
                        if (stopped) {
                            break;
                        }

                        ConnectionManager worker =
                                setupConnectionManager(client, false);

                        // Observation must be prepared before call execute()
                        // to avoid loss of event in case of immediate
                        // complete
                        prepareObservation(worker);

                        executor.execute(worker);
                    }
                } catch (Exception e) {
                    Logger.error(e);
                    CloseUtil.close(client);
                }
            }
        } catch (IOException e) {
            // stop() is called.
            // including AsynchronousCloseException (in NIO)
            Logger.debug(e);
        }
    }

    private ConnectionManager setupConnectionManager(Socket client, boolean tlsLoopback) {
        ConnectionManager worker;
        worker = new ConnectionManager(config, client, tlsLoopback);
        // 登録順がstopper、Extension、Rewriterの優先順位を決めるため、
        // 自動検出ではなく明示的な順序で登録する。

        boolean videoCacheEnabled = !Boolean.getBoolean("disableVideoCacheSystem");

        // [nl] デフォルトフィルタの登録
        worker.addRequestFilter(EasyRewriter.getInstance_sys());
        worker.addRequestFilter(new DefaultRequestFilter());

        // /watch/smXXXXXX[XXX,XXX,XXX].mp4 へのアクセスを防ぐ
        registerProcessor(workaroundProcessor, worker);
        worker.addRequestFilter(workaroundProcessor);

        // [nl] 拡張系の登録
        registerExtProcessors(worker);

        // [nl] ユーザヘッダフィルタの登録
        worker.addRequestFilter(EasyRewriter.getInstance());

        // [nl] Processorの登録
        registerProcessor(new LocalDirProcessor(), worker, true);
        registerProcessor(new CacheDirProcessor(), worker, true);

        if (Boolean.getBoolean("cacheThumbnail")) {
            if (!System.getProperty("thcacheMode").equals("folder")) {
                registerProcessor(new ThumbProcessor(), worker);
            } else {
                registerProcessor(new ThumbProcessor2(), worker);
            }
        }
        if (Boolean.getBoolean("cacheGetThumbInfo")) {
            registerProcessor(new GetThumbInfoProcessor(), worker);
        }
        if (Boolean.getBoolean("cacheExtThumb")) {
            registerProcessor(new ExtThumbProcessor(), worker);
        }
        if (videoCacheEnabled) {
            registerProcessor(new CmafCachingProcessor(executor), worker);
        }
        registerProcessor(new CommentSavingProcessor(executor), worker);
        registerProcessor(rewriterProcessor, worker);

        registerProcessor(getPostProcessor, worker);
        registerProcessor(connectProcessor, worker);

        return worker;
    }

    public void handleTlsLoopback(Socket client) {
        ConnectionManager worker = setupConnectionManager(client, true);
        worker.run();
    }

    private static void registerProcessor(Processor processor, ConnectionManager worker) {
        registerProcessor(processor, worker, false);
    }

    private static void registerProcessor(Processor processor, ConnectionManager worker, boolean stopper) {
        if (worker == null) {
            return;
        }

        Pattern p = processor.getSupportedURLAsPattern();
        if (p == null) {
            String url = processor.getSupportedURLAsString();
            if (url != null) {
                p = Pattern.compile(url, Pattern.LITERAL);
            }
        }

        String[] methods = processor.getSupportedMethods();
        if (methods == null) {
            return;
        }

        for (String method : methods) {
            worker.addProcessor(method, p, processor, stopper);
        }
    }

    private void prepareObservation(ConnectionManager worker) {
        liveWorkers.add(worker);
        worker.setServer(this);
    }

    private void cleanupServerSocket() {
        if (!serverSocket.isClosed()) {
            CloseUtil.close(serverSocket);
        }
    }

    /*private*/ void cleanupWorkers() { // [nl]
        synchronized (liveWorkers) {
            for (ConnectionManager worker : liveWorkers) {
                worker.stop();
            }
        }
    }

    private void cleanupExecutor() {
        for (int i = 0; i < 10 && !executor.isTerminated(); ++i) {
            try {
                Logger.debug("waiting for terminating threads...");
                executor.shutdownNow();
                if (executor.awaitTermination(MAX_WAITING_TIME,
                        TimeUnit.SECONDS)) {
                    Logger.debug("done");
                    break;
                } else {
                    Logger.debug("timed out");
                }
            } catch (InterruptedException e) {
                Logger.warning(e.toString());
            }
        }
    }

    // [nl] 拡張Processorのテスト版
    private URLClassLoader extLoader;
    private List<Extension> procExtensions = new ArrayList<>();
    private List<Extension> filtExtensions = new ArrayList<>();

    private final Extension.Type supportedProcType = Extension.Type.Processor1;
    private final Extension.Type supportedFiltType = Extension.Type.RequestFilter1;

    //[nl] Extension2
    private List<Extension2> newExtensions = new ArrayList<>();
    private PeriodicalCaller periodicalCaller;

    private void initClassLoader() {
        URL[] urls = new URL[1];
        try {
            urls[0] = NicoCachePaths.dataRoot().toUri().toURL();
        } catch (MalformedURLException e) { }
        extLoader = new URLClassLoader(urls);

        File extDir = NicoCachePaths.userFile("extensions");
        File[] files = extDir.listFiles((File dir, String name) -> {
            return name.endsWith(".class") && !name.contains("$") &&
                    // マージしたエクステンションは除外
                    !name.startsWith("nlThumbInfoRewriter") &&
                    !name.startsWith("nlSearchExtension") &&
                    !name.startsWith("dmc.");
        });

        if (files == null || files.length == 0) {
            return;
        }
        Arrays.sort(files);

        // ロード時に一度だけ実行する初期化レジスタ
        ExtensionManager rRegister = new ExtensionManager() {
            @Override
            public void registerProcessor(Processor p) {
                // セッション毎に登録するので初期化時は何もしない
            }
            @Override
            public void registerProcessor(Processor p, boolean stopper) {
                // セッション毎に登録するので初期化時は何もしない
            }
            @Override
            public void registerRewriter(Rewriter r) {
                rewriterProcessor.addRewriter(r);
            }
            @Override
            public void registerRequestFilter(RequestFilter f) {
                // セッション毎に登録するので初期化時は何もしない
            }
            @Override
            public void registerCompleteCache(CompleteCache c) {
                NLMain.SHARED.addCompleteCache(c);
            }
            @Override
            public void registerEventListener(EventListener l) {
                NLMain.SHARED.addEventListener(l);
            }
        };

        for (File file : files) {
            String className = file.getName().replace(".class", "");
            try {
                Object extObj = extLoader.loadClass(
                                "extensions." + className).getDeclaredConstructor().newInstance();
                //[nl] Extension2
                if (extObj instanceof Extension2) {
                    Extension2 ext2 = (Extension2) extObj;
                    Logger.info("Extension2: " + ext2.getVersionString());
                    System.setProperty("extension." + className, "true");
                    newExtensions.add(ext2);
                    ext2.registerExtensions(rRegister);
                    continue;
                }

                Extension extInterface = null;
                if (extObj instanceof Extension) {
                    extInterface = (Extension) extObj;
                } else {
                    continue;
                }

                Object obj = extInterface.queryInterface(Extension.Type.Processor1);
                if (obj instanceof Processor)
                {
                    Logger.info("Processor Extension: " + extInterface.getVersionString());
                    System.setProperty("extension." + className, "true");
                    procExtensions.add(extInterface);
                }
                obj = extInterface.queryInterface(Extension.Type.Rewriter1);
                if (obj instanceof Rewriter)
                {
                    Logger.info("Rewriter Extension: " + extInterface.getVersionString());
                    System.setProperty("extension." + className, "true");
                    rewriterProcessor.addRewriter((Rewriter) obj);
                }
                obj = extInterface.queryInterface(Extension.Type.RequestFilter1);
                if (obj instanceof RequestFilter)
                {
                    Logger.info("RequestFilter Extension: " + extInterface.getVersionString());
                    System.setProperty("extension." + className, "true");
                    filtExtensions.add(extInterface);
                }
            } catch (Throwable t) {
                Logger.error(t);
                Logger.warning("skip extension: " + className);
            }
        }

        if (NLMain.SHARED.countSystemEventListeners() > 0) {
            periodicalCaller = new PeriodicalCaller();
            periodicalCaller.start();
        }
    }

    class PeriodicalCaller extends Thread {
        static final long INTERVAL = 60 * 1000L;
        PeriodicalCaller() {
            super("PeriodicalCaller");
            this.setPriority(MIN_PRIORITY);
        }
        @Override
        public void run() {
            Logger.debugWithThread("started");

            long epoch = System.currentTimeMillis() / 1000L * 1000L;
            while (!stopped) {
                try {
                    NLMain.SHARED.notifySystemEvent(
                            SystemEventListener.PERIODIC_CALL, null, false);
                    long diff = (System.currentTimeMillis() - epoch) % INTERVAL;
                    Thread.sleep(INTERVAL - diff);
                } catch (Exception e) {
                    Logger.debugWithThread(e);
                }
            }
        }
    }

    private void cleanupExtensions() {
        for (Extension2 entry : newExtensions) {
            // リスナが実装されているかどうかで一方のみ呼び出す
            if (entry instanceof SystemEventListener) {
                try {
                    ((SystemEventListener) entry).onSystemEvent(
                            SystemEventListener.SYSTEM_EXIT, null);
                } catch (Throwable t) {
                    Logger.error(t);
                }
            } else {
                try { // onSystemExit() があれば呼び出す
                    entry.getClass().getMethod("onSystemExit").invoke(entry);
                } catch (NoSuchMethodException e) {
                    // Optional callback is not implemented.
                } catch (Exception e) {
                    Logger.error(e);
                }
            }
        }
    }

    public void registerExtProcessors(ConnectionManager worker) {
        ExtensionRegister eRegister = new ExtensionRegister(worker);
        // Extension2
        for (Extension2 entry : newExtensions) {
            entry.registerExtensions(eRegister);
        }

        // Processor
        for (Extension entry : procExtensions) {
            registerProcessor((Processor)
                    entry.queryInterface(supportedProcType), worker);
        }

        // RequestFilter
        for (Extension entry : filtExtensions) {
            registerRequestFilter((RequestFilter)
                    entry.queryInterface(supportedFiltType), worker);
        }
    }

    // ConnectionManagerの初期化に使用
    class ExtensionRegister implements ExtensionManager {
        ConnectionManager worker;
        ExtensionRegister(ConnectionManager worker) {
            this.worker = worker;
        }
        @Override
        public void registerProcessor(Processor p) {
            Server.registerProcessor(p, worker, false);
        }
        @Override
        public void registerProcessor(Processor p, boolean stopper) {
            Server.registerProcessor(p, worker, stopper);
        }
        @Override
        public void registerRewriter(Rewriter r) {
            // do nothing
        }
        @Override
        public void registerRequestFilter(RequestFilter f) {
            worker.addRequestFilter(f);
        }
        @Override
        public void registerCompleteCache(CompleteCache c) {
            // do nothing
        }
        @Override
        public void registerEventListener(EventListener e) {
            // do nothing
        }
    }

    private void registerRequestFilter(RequestFilter filter, ConnectionManager worker) {
        if (worker == null) {
            return;
        }

        worker.addRequestFilter(filter);
    }

    RewriterProcessor getRewriterProcessor() {
        return rewriterProcessor;
    }
}
