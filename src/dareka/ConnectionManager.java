package dareka;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import dareka.common.CloseUtil;
import dareka.common.Config;
import dareka.common.HttpIOException;
import dareka.common.Logger;
import dareka.extensions.RequestFilter;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;

public class ConnectionManager implements Runnable {
    private Server server;
    private SocketWrapper browser; // [nl]
    private Config config;
    private String processingMethod;
    private String processingURI;
    private HttpRequestHeader processingRequestHeader;
    private volatile Resource processingResource;
    private volatile boolean stopped = false;

    private List<ProcessorEntry> processorEntries = new ArrayList<>();
    //[nl] リクエストフィルタ
    private List<RequestFilter> filterEntries = new ArrayList<>();

    private boolean tlsLoopback;

    public ConnectionManager(Config config, Socket browser, boolean tlsLoopback) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (browser == null) {
            throw new IllegalArgumentException("browser must not be null");
        }

        this.config = config;
        this.browser = new SocketWrapper(browser); // [nl]
        this.tlsLoopback = tlsLoopback;
    }

    @Override
    public void run() {
        try {
            while (processAPairOfMessages()) {
                // loop until the method returns false.
                processingMethod = null;
                processingURI = null;
                processingRequestHeader = null;
            }
            Logger.debugWithThread("loop end");
        } catch (ConnectException e) {
            // アウトバウンド側に接続失敗
            Logger.debugWithThread(e);
            printWarning(e);
            sendBadGateway();
        } catch (UnresolvedAddressException e) {
            // 存在しないドメインにhttps接続しようとした時
            Logger.debugWithThread(e);
            printWarning(e);
            sendBadGateway();
        } catch (SocketException e) {
            Logger.debugWithThread(e);

            // Connection reset はよくあるので通常はログに出さない
            // CONNECT処理中の Socket is closed も同様
            if (!isConnectionReset(e) &&
                    !(isConnectMethod() && isSocketIsClosed(e))) {
                printWarning(e);
            }
        } catch (IllegalBlockingModeException e) {
            Logger.debugWithThread(e);
            // 通信が終わってkeep-alive中のTLSセッションがあるときに
            // 本体をシャットダウンするとよく出るのでログには出さない
        } catch (SSLException e) {
            Logger.debugWithThread(e);

            // Connection reset や Broken pipe はよくあるので通常はログに出さない
            // またリクエスト処理中以外に発生した例外もログに出さない
            if (!isConnectionReset(e) && !isBrokenPipe(e) && isProcessing()) {
                printWarning(e);
            }
        } catch (HttpIOException e) {
            Logger.debugWithThread(e.toString());     // [nl] 頻繁に出るので短縮
        } catch (IOException e) {
            // NIOを使っているとConnection resetではなく以下のメッセージを持った
            // IOExceptionになる。
            // 「既存の接続はリモート ホストに強制的に切断されました。」
            // 「確立された接続がホスト コンピュータのソウトウェアによって中止されました。」
            // UnknownHostExceptionも通常ログには出さない
            if (isConnectionReset(e) || isBrokenPipe(e)) {
                Logger.debugWithThread(e.toString()); // [nl] 頻繁に出るので短縮
            } else {
                Logger.debugWithThread(e);
            }
        } catch (CancelledKeyException e) {
            // read()中に別スレッドからstop()で読み込みを中断させると
            // CancelledKeyExceptionになる。
            // ただしJava実行環境の実装依存。
            // 意図したエラーなのでログには出さない。
            Logger.debugWithThread(e);
        } catch (ClosedSelectorException e) {
            // 別スレッドからstop()でSelectorをclose()すると
            // ClosedSelectorExceptionになる。
            // 意図したエラーなのでログには出さない。
            Logger.debugWithThread(e);
        } catch (Exception e) {
            Logger.debugWithThread(e);
            printWarningWithStacktrace(e);
        } finally {
            if (!browser.isClosed()) {
                consumeBrowserInput();
                CloseUtil.close(browser);
            }

            notifyCompletion();
        }
    }

    private void printWarning(Exception e) {
        Throwable cause = e.getCause();
        Logger.warning("failed to process: " + processingURI + "\n\t"
                + e.toString()
                + (cause != null ? "\n\tCaused by " + cause.toString() : ""));
    }

    private void sendBadGateway() {
        if (processingRequestHeader == null || browser.isClosed()) {
            return;
        }
        try {
            Resource resource = StringResource.getBadGateway(
                    "Upstream connection failed");
            resource.setResponseHeader(
                    HttpHeader.CONTENT_TYPE, "text/plain; charset=UTF-8");
            resource.transferTo(browser, processingRequestHeader, config);
        } catch (Exception error) {
            Logger.debugWithThread(error);
        }
    }

    private void printWarningWithStacktrace(Exception e) {
        Logger.warning("failed to process: " + processingURI + "\n\t"
                + getStackTrace(e));
    }

    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private boolean isConnectMethod() {
        return "CONNECT".equalsIgnoreCase(processingMethod);
    }

    private boolean isProcessing() {
        return processingURI != null;
    }

    private boolean isConnectionReset(/*Socket*/Exception e) { // [nl]
        // この判定方法でいいかは…
        Throwable cause = e.getCause();
        String message = (cause != null ? cause : e).getMessage();
        if (message == null) return false;
        // 2024-04-04検証 java11, java17, java19で"Connection reset by peer"とい
        // う文字列が入って来る.
        return message.startsWith("Connection reset");
        // 2024-04-04: 以下のWorkaroundをコメントアウト.
        // // Java7で何故かローカライズされたメッセージが返ってくるので…
        // || message.startsWith("接続が相手からリセットされました")
        // || message.startsWith("Software caused connection abort")
        // || message.startsWith("確立された接続が")
        //    // JDKのバグ(JDK-8200243)で文字化けして返ってくる．治ったら消す
        //    // new String("確立された接続が".getBytes("UTF-8"), "SJIS");
        // || message.startsWith("遒ｺ遶九＆繧後◆謗")
        // || message.startsWith("An established connection was aborted");
        // 2024-03-27追記.
        // - (getLocalizedMessageではない)getMessageが、ローカライズメッセージ
        //   を返すことが第1のバグ. 文字化けを返すことが第2のバグ.
    }

    private boolean isBrokenPipe(Exception e) { // [nl]
        Throwable cause = e.getCause();
        String message = (cause != null ? cause : e).getMessage();
        if (message == null) return false;
        return message.startsWith("Broken pipe");
    }

    private boolean isSocketIsClosed(Exception e) {
        Throwable cause = e.getCause();
        String message = (cause != null ? cause : e).getMessage();
        if (message == null) return false;
        return message.startsWith("Socket is closed");
    }

    /**
     * 余分なデータを送ってくるブラウザ対策。
     */
    private void consumeBrowserInput() {
        try {
            // java.net APIで処理する。
            // そのために非ブロックモードを解除。
            SocketChannel bc = browser.getChannel();
            // bcはnullにはならない。
            bc.configureBlocking(true);

            // 次の読み込みでデッドロックを避けるため出力は停止。
            // (FINを送信)
            browser.shutdownOutput();

            // IEはPOSTリクエストの時に余分にCRLFを送って来ているので
            // IEが送信を正常終了できるように読み飛ばしてやる。
            // (FINの受信まで待つ)
            // これを読み飛ばしてやらないでSocket#close()すると
            // 「メッセージサーバーに接続できませんでした。」などになる
            // 参考: IEが余分なCRLFを送信することについて触れられ
            // ている公式の文書
            // http://support.microsoft.com/kb/823099/
            while (browser.getInputStream().read() != -1) {
                // no nothing
            }
        } catch (Exception e) {
            // IOExceptionのConnection reset系のエラーやCancelledKeyExceptionが
            // 来る。
            // 処理中でresetされていた場合はここでもまた例外になるが、
            // 実際にread()してみないと区別が付かないので仕方ない。
            Logger.debugWithThread(e.toString() + "(consuming)");
        }
    }

    static final Pattern URL_FOR_DEBUG = Pattern.compile("^(.*/)([^?/]*)([?].*)?$");
    public static String abbrurl(HttpRequestHeader requestHeader) {
        String rhash = String.format("%x", requestHeader.hashCode());
        String url = requestHeader.getURI();
        Matcher m = URL_FOR_DEBUG.matcher(url);
        if (m.matches()) {
            String o = m.group(1) + m.group(3);
            String name = m.group(2);
            // ファイル名部分/それ以外部分のハッシュ//:requestHeaderのハッシュ
            return String.format("%s/%x//:%s", name, o.hashCode(), rhash);
        };
        return rhash + "//" + url;
    };

    private boolean processAPairOfMessages() throws IOException {
        browser.deleteInputStreamBuffer(); // [nl]
        HttpRequestHeader requestHeader =
                new HttpRequestHeader(browser.getInputStream(), tlsLoopback);
        processingRequestHeader = requestHeader;
        processingMethod = requestHeader.getMethod();
        processingURI = requestHeader.getURI();

        Logger.debugWithThread(requestHeader.getMethod() + " "
                + requestHeader.getURI());
        if (Boolean.getBoolean("showRequestHeader")) { // [nl]
            String header = requestHeader.toString();
            Logger.info(header.substring(0, header.length() - 2));
        }

        // debugリソースの処理
        Resource debugResource = getDebugResource(requestHeader);
        if (debugResource != null) {
            return debugResource.transferTo(browser, requestHeader, config);
        }


        // [nl] 設定ファイルの更新チェック
        // processAPairOfMessages()としての振舞いではないが
        // new HttpRequestHeaderは典型的なブロック場所なので
        // その直後に書くことにする。
        if (!Main.isDirectoryWatching()) {
            config.reload();
        }

        // CORS偽装
        CorsLiar corsLiar = CorsLiarManager.getInstance().match(requestHeader);
        if (corsLiar != null) {
            Resource resource = corsLiar.processPreflight(requestHeader);
            if (resource != null) {
                return useResource(requestHeader, resource);
            }

            corsLiar.applyToRequest(requestHeader);
        }

        // [nl] リクエストヘッダの処理
        for (RequestFilter filter : filterEntries) {
            int ret = filter.onRequest(requestHeader);
            // ドロップする場合は強制的に接続を切る
            if (ret == RequestFilter.DROP) {
                Logger.debugWithThread("request dropped");
                return false;
            }
        }

        // [nl] ローカルリソースの処理
        Resource localResource = getLocalResource(requestHeader);
        if (localResource != null) {
            return localResource.transferTo(browser, requestHeader, config);
        }

        // [nl] 外部パラメータの取得と除去
        String xnlParams = requestHeader.getMessageHeader(HttpHeader.X_NICOCACHE_NL);
        if (xnlParams != null) {
            requestHeader.removeMessageHeader(HttpHeader.X_NICOCACHE_NL);
        }

        // [nl] POSTなら入力ボディをバッファリングして再利用できるように
        if (HttpHeader.POST.equals(requestHeader.getMethod()) ||
                HttpHeader.PUT.equals(requestHeader.getMethod())) {
            long contentLength = requestHeader.getContentLength();
            if (0L < contentLength &&
                    contentLength <= Long.getLong("postSizeMax", 16*1024*1024)) {
                browser.newInputStreamBuffer((int) contentLength);
            }
        }

        // 対応するProcessorを探して処理
        for (ProcessorEntry entry : processorEntries) {
            if (isMatchToEntry(entry, requestHeader)) {
                // [nl] Processorがnullを返した時は次のProcessorで処理する
                //      Extensionで選択的にProcessor処理したい場合等に有効
                browser.resetInputStreamBuffer();
                Resource resource;
                try {
                    resource =
                            entry.getProcessor().onRequest(requestHeader, browser);
                } catch (SSLException ex) {
                    resource = StringResource.getBadGateway("TLS Error:\n" + ex.toString());
                    resource.setResponseHeader("Content-Type", "text/plain");
                }
                if (resource == null) {
                    if (entry.isStopper()) {
                        resource = StringResource.getInternalError("Error");
                    } else if (browser.isResettable() ||
                            !HttpHeader.POST.equals(requestHeader.getMethod()) &&
                            !HttpHeader.PUT.equals(requestHeader.getMethod())) {
                        continue;
                    } else {
                        resource = StringResource.getPayloadTooLarge();
                    }
                }
                if (corsLiar != null) {
                    corsLiar.applyToResource(requestHeader, resource);
                }
                boolean canContinue = false;
                try {
                    canContinue = useResource(requestHeader, resource);
                } catch (SSLHandshakeException ex) {
                    resource = StringResource.getBadGateway("TLS Error:\n" + ex.toString());
                    resource.setResponseHeader("Content-Type", "text/plain");
                    canContinue = useResource(requestHeader, resource);
                }

                Logger.debugWithThread("end");

                // [nl] 自分自身からの場合は待っても無駄なので継続しない
                if (xnlParams != null && xnlParams.contains(HttpHeader.XNL_PROXY_MYSELF))
                    canContinue = false;
                return canContinue;
            }
        }

        throw new HttpIOException("request cannot be processed:\r\n"
                + requestHeader);
    }

    private Resource getDebugResource(HttpRequestHeader requestHeader) throws IOException {
        if (!requestHeader.getHost().equals("DEBUG")) {
            return null;
        }
        String path = requestHeader.getPath();
        if (path == null) {
            return null;
        }

        if (path.equals("/debug/dump-stack")) {
            java.lang.management.ThreadMXBean threadMxBean =
                    java.lang.management.ManagementFactory.getThreadMXBean();

            try (BufferedWriter bw = new BufferedWriter(new FileWriter("./debug-dump-stack.txt"))) {
                for (java.lang.management.ThreadInfo ti : threadMxBean.dumpAllThreads(true, true)) {
                    dumpThreadInfo(bw, ti);
                }
            }
            Resource r = new StringResource("OK");
            r.setResponseHeader(HttpHeader.CONTENT_TYPE,
                    "text/plain; charset=UTF-8");
            return r;
        }

        return StringResource.getNotFound();
    }

    // ThreadInfo.toString()にスタックトレースの深さ制限があるため再実装
    private static void dumpThreadInfo(BufferedWriter bw, java.lang.management.ThreadInfo ti)
            throws IOException {
        Thread.State state = ti.getThreadState();
        StackTraceElement[] stacktrace = ti.getStackTrace();
        java.lang.management.LockInfo waitingOn = ti.getLockInfo();
        String lockOwnerName = ti.getLockOwnerName();
        long lockOwnerId = ti.getLockOwnerId();
        java.lang.management.MonitorInfo[] monitors = ti.getLockedMonitors();
        java.lang.management.LockInfo[] syncs = ti.getLockedSynchronizers();

        bw.append('"').append(ti.getThreadName()).append("\" ");
        bw.append("Id=" + ti.getThreadId() + " ");
        bw.append(state.toString());
        if (waitingOn != null) {
            bw.append(" on ").append(waitingOn.toString());
        }
        if (lockOwnerName != null) {
            bw.append(" owned by \"").append(lockOwnerName)
                    .append("\" Id=" + lockOwnerId);
        }
        if (ti.isSuspended()) { bw.append(" (suspended)"); }
        if (ti.isInNative()) { bw.append(" (in native)"); }
        bw.append('\n');

        for (int i = 0; i < stacktrace.length; i++) {
            bw.append("\tat ").append(stacktrace[i].toString()).append("\n");
            if (i == 0 && waitingOn != null &&
                    (state == Thread.State.BLOCKED
                    || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING)) {
                bw.append("\t- waiting on ").append(waitingOn.toString()).append('\n');
            }

            for (java.lang.management.MonitorInfo monitor : monitors) {
                if (monitor.getLockedStackDepth() == i) {
                    bw.append("\t- locked ").append(monitor.toString()).append('\n');
                }
            }
        }

        if (syncs.length > 0) {
            bw.append('\n');
            bw.append("\tNumber of locked synchronizers = " + syncs.length + "\n");
            for (java.lang.management.LockInfo sync : syncs) {
                bw.append("\t- ").append(sync.toString()).append('\n');
            }
        }

        bw.append('\n');
    }

    // [nl] ローカルへのリクエストなら対応するResourceを返す
    private Resource getLocalResource(HttpRequestHeader requestHeader
            ) throws IOException {
        if (requestHeader.getHost().equals("LOCAL")) {
            File localFile;
            String path = requestHeader.getPath();
            if (path.length() > 1 && path.startsWith("/")) {
                File appDir = NicoCachePaths.dataRoot().toFile()
                        .getCanonicalFile();
                File certsDir = NicoCachePaths.userFile("certs")
                        .getCanonicalFile();
                localFile = new File(appDir, path.substring(1));
                // canonical pathで比較したいがシンボリックリンクが効かなくなるので
                boolean ok = false;
                for (File tmp = localFile; tmp != null; tmp = tmp.getParentFile()) {
                    if (tmp.getName().equals("..")) {
                        return StringResource.getNotFound();
                    }
                    if (certsDir.equals(tmp)) {
                        return StringResource.getForbidden();
                    }
                    if (appDir.equals(tmp)) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    return StringResource.getNotFound();
                }
            } else {
                localFile = NicoCachePaths.userFile("index.html");
                if (!localFile.exists()) {
                    Resource r = new StringResource(Main.VER_STRING);
                    r.setResponseHeader(HttpHeader.CONTENT_TYPE,
                            "text/plain; charset=UTF-8");
                    return r;
                }
            }
            return Main.getRewriterProcessor().localRewriter(
                    requestHeader.getURI(), localFile, requestHeader);
        }
        if (requestHeader.getURI().startsWith("http")) {
            return null; // httpリクエストなら即返す
        }
        String type = requestHeader.getHost().toLowerCase();
        switch (type) {
        case "redirect":
            String path = requestHeader.getPath();
            if (path.length() > 1) {
                path = path.substring(1);
            }
            return StringResource.getRedirect(path);
        case "notmodified":
            return StringResource.getNotModified();
        case "badrequest":
            return StringResource.getBadRequest();
        case "notfound":
            return StringResource.getNotFound();
        }
        return null;
    }

//    private boolean useProcessor(HttpRequestHeader requestHeader,
//            Processor processor) throws IOException {
//        processingResource = processor.onRequest(requestHeader, browser);

    // [nl] Resourceの取得をProcessorループ内で行うように変更
    private boolean useResource(HttpRequestHeader requestHeader,
            Resource resource) throws IOException {
        processingResource = resource;

        if (stopped) {
            // この停止要求チェックはprocessingResource取得より
            // 後に無ければならない
            return false;
        }

        if (processingResource == null) {
            throw new HttpIOException(
                    "request processor failed to handle request:\r\n"
                            + requestHeader);
        }

        try { // ensure (processingResource == null) after the transfer.
            requestHeader.removeHopByHopHeaders();
            return processingResource.transferTo(browser, requestHeader, config);
        } finally {
            processingResource = null;
        }
    }

    private boolean isMatchToEntry(ProcessorEntry entry,
            HttpRequestHeader requestHeader) {
        if (entry.getMethod() != null) {
            if (!entry.getMethod().equals(requestHeader.getMethod())) {
                return false;
            }
        }

        if (entry.getUri() != null) {
            Matcher m = entry.getUri().matcher(requestHeader.getURI());
            if (!m.lookingAt()) {
                return false;
            }
            // Processor APIへMatcherを持ち込むとExtension ABIが変わるため、
            // ここでは登録条件の判定だけを行う。
        }

        return true;
    }

    public void setServer(Server server) {
        this.server = server;
    }

    private void notifyCompletion() {
        if (server != null) {
            server.completed(this);
        }
    }

    public void addProcessor(String method, Pattern url, Processor processor) {
        addProcessor(method, url, processor, false);
    }

    public void addProcessor(String method, Pattern url, Processor processor, boolean stopper) {
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }

        ProcessorEntry entry = new ProcessorEntry(method, url, processor, stopper);

        processorEntries.add(entry);
    }

    /**
     * stop blocking operation.
     */
    public void stop() {
        stopped = true;

        CloseUtil.close(browser);

        try {
            processingResource.stopTransfer();
        } catch (NullPointerException npe) {
            // processingResourceの書き換えは別スレッドで行われるので
            // nullチェックするなら呼び出しをアトミックに行わなければならないが
            // それだとロック管理が複雑になるのでoptimisticに行う
            // nullだった場合は既に転送終了しているのでOK
            Logger.debugWithThread(npe);
        }
    }

    // [nl]
    public void addRequestFilter(RequestFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("filter must not be null");
        }
        filterEntries.add(filter);
    }
}
