package dareka.processor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import dareka.TlsClientContextFactory;
import dareka.common.CloseUtil;
import dareka.common.Config;
import dareka.common.FileUtil;
import dareka.common.HttpIOException;
import dareka.common.Logger;

public class URLResource extends Resource {
    /**
     * Border of using FixedLengthStreamingMode.
     * At this time, all transfer with body use FixedLengthStreamingMode,
     * so BUFFERED_POST_MAX is 0. In some environments, it may cause
     * socket error. If it happens, this should have some amount of size.
     * (5MB or and so on. within half of free heap size.)
     */
    private static final int BUFFERED_POST_MAX = 0;//5 * 1024 * 1024;

    static {
        if (Boolean.getBoolean("useWorkaroundForEncoding")) {
            Workarounds.dirtyChangeHttpURLConnectionImplEncoding();
        }
        if (Boolean.getBoolean("useWorkaroundForAllowedMethods")) {
            Workarounds.dirtyChangeHttpURLConnectionImplAllowedMethods();
        }

        // See http://stackoverflow.com/questions/8335501/
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    private URL url;
    // proxy must let browser know redirection.
    private boolean followRedirects = false;
    private Proxy proxy;
    private long contentLength = -1;
    private boolean canContinue = true;
    private volatile URLConnection con;

    // [nl]
    private Proxy defaultProxy;
    private boolean proxyMyself;
    private HttpResponseHeader captureResponseHeader;
    private byte[] captureResponseBody;
    private int transferTimeout;
    private IOException transferException;
    private int readTimeout;
    private boolean alreadyRequested;

    public URLResource(String resource) throws IOException {
        url = new URL(resource);

        // Do not remember proxy so that it is enable to change proxy
        // configuration on runtime.
        String proxyHost = System.getProperty("proxyHost");
        int proxyPort = Integer.getInteger("proxyPort");
        setProxyNoOverride(proxyHost, proxyPort);
        defaultProxy = proxy;
        readTimeout = Integer.getInteger("readTimeout");
    }

    public void setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }

    /**
     * [nl] 転送タイムアウトを設定。
     * 転送開始からここで設定した時間を過ぎると強制的に切断して例外を発生する。
     * @param transferTimeout タイムアウト時間(ミリ秒)
     */
    public void setTransferTimeout(int transferTimeout) {
        if (transferTimeout < 0) {
            throw new IllegalArgumentException(
                    "transferTimeout must not be a negative value");
        }
        this.transferTimeout = transferTimeout;
        this.transferException = null;
    }

    public void setReadTimeout(int readTimeout) {
        if (readTimeout < 0) {
            throw new IllegalArgumentException(
                    "readTimeout must not be a negative value");
        }
        this.readTimeout = readTimeout;
    }

    @Override
    public boolean endEnsuredTransferTo(Socket receiver,
            HttpRequestHeader requestHeader, Config config) throws IOException {
        return transferTo(receiver.getInputStream(),
                receiver.getOutputStream(), requestHeader, config);
    }

    /**
     * [nl] 選択的に転送を行う。
     * 転送タイムアウトが設定されている場合、転送用スレッドを起動して転送を行う。
     * 主にRewriter等でレスポンスボディのみ転送したい場合に利用する。
     *
     * @param receiverIn 受信側の入力ストリーム
     * @param receiverOut 受信側の出力ストリーム
     * @param requestHeaderArg リクエストヘッダ
     * @param sendResponseHeader レスポンスヘッダも転送するならtrue
     * @return 続けて転送できるならtrue
     * @throws IOException 転送中に何らかの問題が発生した
     * @see #setTransferTimeout(int)
     * @see #transferTo(InputStream, OutputStream, HttpRequestHeader, Config)
     */
    public boolean selectTransferTo(InputStream receiverIn, OutputStream receiverOut,
            HttpRequestHeader requestHeaderArg, boolean sendResponseHeader)
            throws IOException {
        if (transferTimeout == 0L || captureResponseBody != null) {
            return doTransferTo(receiverIn, receiverOut,
                    requestHeaderArg, sendResponseHeader);
        }
        final InputStream in = receiverIn;
        final OutputStream out = receiverOut;
        final HttpRequestHeader rh = requestHeaderArg;
        final boolean send = sendResponseHeader;
        new Thread() {
            @Override
            public void run() {
                try {
                    doTransferTo(in, out, rh, send);
                } catch (IOException e) {
                    transferException = e;
                }
            }
            @Override
            public void start() {
                super.start();
                try {
                    join(transferTimeout);
                } catch (InterruptedException e) {}
                if (isAlive() && con != null) {
                    Workarounds.dirtyCloseHttpURLConnectionImplSocket(con);
                    transferException = new HttpIOException(String.format(
                            "transfer timeout: %,dms", transferTimeout));
                }
            }
        }.start();
        if (transferException != null) {
            throw transferException;
        }
        return canContinue;
    }

    // ここをオーバーライドする時は、selectTransferToとgetResponseHeaderも
    // オーバーライドして無効化しておいた方がいい
    public boolean transferTo(InputStream receiverIn, OutputStream receiverOut,
            HttpRequestHeader requestHeaderArg, Config config)
            throws IOException {
        return selectTransferTo(receiverIn, receiverOut, requestHeaderArg, true);
    }

    // 本家とのdiffを取りやすくするためにこの位置で定義
    /**
     * [nl] レスポンスヘッダを取得する。
     *
     * このメソッドを呼び出した後に
     * {@link #transferTo(InputStream, OutputStream, HttpRequestHeader, Config) transferTo},
     * {@link #selectTransferTo(InputStream, OutputStream, HttpRequestHeader, boolean) selectTransferTo}
     * を呼び出すと、受信済ヘッダの送信とボディ部分の通信を行う。
     * <p>
     * このメソッドを2回以上呼び出した場合は、既に受信済のレスポンスヘッダを返す。
     * その場合、全てのパラメーター引数を無視する。
     *
     * @param receiverIn POSTの場合は入力ストリームを指定、GETの場合はnull
     * @param requestHeaderArg ヘッダの受信に用いるリクエストヘッダ、
     * nullを指定した場合はデフォルトのリクエストヘッダを使用する
     * @return 取得したレスポンスヘッダ
     * @throws IOException ヘッダの取得中に問題が発生した
     */
    public HttpResponseHeader getResponseHeader(InputStream receiverIn,
            HttpRequestHeader requestHeaderArg) throws IOException {
        if (captureResponseHeader != null) {
            return captureResponseHeader;
        }
        if (alreadyRequested) {
            // すでにリクエストを送っている場合はPOSTデータを
            // 再送できないなどの問題が生じるため処理しない
            return null;
        }
        HttpRequestHeader requestHeader;
        if (requestHeaderArg == null) {
            requestHeader =
                    new HttpRequestHeader(HttpHeader.GET + " " + url.toString()
                            + " HTTP/1.1\r\n\r\n");
            requestHeader.setMessageHeader("User-Agent",
                    "Mozilla/4.0 (compatible; MSIE 6.0)");
        } else {
            requestHeader = requestHeaderArg;
        }

        REDIRECT:
        for (int _redirect = 0; _redirect < 3; _redirect++) {
            if (proxy != defaultProxy) {
                Logger.debugWithThread("proxy change detected");
                if (proxyMyself) {
                    if (requestHeader.getURI().startsWith("https://")) {
                        // https接続をNicoCache_nlで再処理する場合には
                        // ヘッダに本来httpsで通信することを示したうえでhttpで接続する．
                        // これによりCAをjavaに認識させるのが不要になる．ついでに速くなる．
                        String newUrl = "http" + requestHeader.getURI().substring(5);
                        url = new URL(newUrl);
                        requestHeader.setURI(newUrl);
                        // XNL_TLSはsetURIの挙動を変えてしまうのでsetURI後に行うこと
                        requestHeader.setMessageHeader(
                                HttpHeader.X_NICOCACHE_NL,
                                HttpHeader.XNL_PROXY_MYSELF + ", " +
                                        HttpHeader.XNL_TLS);
                    } else {
                        requestHeader.setMessageHeader(
                                HttpHeader.X_NICOCACHE_NL, HttpHeader.XNL_PROXY_MYSELF);
                    }
                }
            }
            con = url.openConnection(proxy);
            if (isStopped()) {
                return null;
            }
            prepareForConnect(requestHeader, receiverIn, con);
            applyRequestHook(requestHeader, con);

            alreadyRequested = true;
            con.setReadTimeout(readTimeout);
            con.connect();
            try {
                sendBodyIfNeccessary(receiverIn, requestHeader, con);
                contentLength = con.getContentLengthLong();

                boolean knownLengthContent = isKnownLengthContent(con);
                boolean clientCanKeepAlive = isClientCanKeepAlive(requestHeader);
                if (!knownLengthContent || !clientCanKeepAlive) {
                    canContinue = false;
                }

                captureResponseHeader =
                        getResponseHeader(con, requestHeader);

            } catch (IOException e) {
                Logger.debugWithThread(e.toString()); // [nl]
                canContinue = false;
                consumeErrorStream(con);
                break REDIRECT;
            } finally {
                // XNL_TLSはsetURIの挙動を変えてしまうので
                // redirectの処理でsetURIをする前に戻す
                requestHeader.removeMessageHeader(HttpHeader.X_NICOCACHE_NL);
            }
            int statusCode = captureResponseHeader.getStatusCode();
            if (!followRedirects || statusCode / 100 != 3) {
                break REDIRECT;
            }
            String location = captureResponseHeader.getMessageHeader(HttpHeader.LOCATION);
            if (location == null) break REDIRECT;
            // 書き換える前にヘッダを複製
            if (requestHeader == requestHeaderArg) {
                requestHeader = new HttpRequestHeader(requestHeader.toString());
            }
            // 元のリクエストがPOSTだった場合
            //   301, 302, 307, 308の時はPOST
            //   303の時はGET
            // でリダイレクトすべきだが
            // 主要ブラウザは301, 302をGETで処理するのでそれに合わせる
            // http://kiririmode.hatenablog.jp/entry/20131202/p1
            switch (statusCode) {
            case 301:
            case 302:
            case 303:
                // GETでリダイレクト
                if (requestHeader.getMethod().equals(HttpHeader.POST) ||
                        requestHeader.getMethod().equals(HttpHeader.PUT)) {
                    requestHeader.setMethod(HttpHeader.GET);
                    requestHeader.removeMessageHeader(HttpHeader.CONTENT_LENGTH);
                    requestHeader.removeMessageHeader(HttpHeader.CONTENT_TYPE);
                }
                break;
            case 307:
            case 308:
                // POSTでリダイレクト
                if (requestHeader.getMethod().equals(HttpHeader.POST) ||
                        requestHeader.getMethod().equals(HttpHeader.PUT)) {
                    if (receiverIn.markSupported()) {
                        receiverIn.reset();
                    } else {
                        Logger.info("POSTデータが大きすぎるためリダイレクトできませんでした: %s", requestHeader.getURI());
                        break REDIRECT;
                    }
                }
                break;
            default:
                break REDIRECT;
            }
            String prevHost = requestHeader.getHost();
            if (location.startsWith("/")) {
                String newURI = requestHeader.getScheme() + "://" +
                        requestHeader.getHost() +
                        ":" + requestHeader.getPort() +
                        location;
                requestHeader.setURI(newURI);
                url = new URL(newURI);
            } else if (location.startsWith("http://") || location.startsWith("https://")) {
                requestHeader.setURI(location);
                url = new URL(location);
            } else {
                // RFC7231で相対パスも許容されたらしいが面倒なので非対応
                Logger.warning("相対パスのLocationは非対応です．対応が必要なためスレへご報告ください: "
                        + requestHeader.getURI() + " -> " + location);
                break REDIRECT;
            }
            String nextHost = requestHeader.getHost();
            if (!getSecondLevelDomain(prevHost).equalsIgnoreCase(getSecondLevelDomain(nextHost))) {
                requestHeader.removeMessageHeader(HttpHeader.COOKIE);
            }
        }
        return captureResponseHeader;
    }

    private final Pattern IPADDR_PATTERN = Pattern.compile("[0-9]++(?:\\.[0-9]++)*+|\\[[0-9a-fA-F:]+\\]");
    private final Pattern SECOND_LEVEL_DOMAIN_PATTERN = Pattern.compile(".*?([a-zA-Z0-9-]+\\.[a-zA-Z0-9-]+)\\.?");
    private String getSecondLevelDomain(String host) {
        Matcher m;
        if ((m = IPADDR_PATTERN.matcher(host)).matches()) {
            return host;
        } else if ((m = SECOND_LEVEL_DOMAIN_PATTERN.matcher(host)).matches()) {
            return m.group(1);
        } else {
            // TLDしかなかった場合など
            return host;
        }
    }

    // [nl] 転送処理の本体。転送タイムアウトで別スレッドから呼び出すために分離。
    private boolean doTransferTo(InputStream receiverIn, OutputStream receiverOut,
            HttpRequestHeader requestHeaderArg, boolean sendResponseHeader)
            throws IOException {
        // [nl] ヘッダを受信していないなら受信して保存しておく
        if (captureResponseHeader == null) {
            captureResponseHeader = getResponseHeader(receiverIn, requestHeaderArg);
            if (captureResponseHeader == null) return false;
        }

        try {
            // [nl] ヘッダも送信するか(ブラウザ用)、
            // ヘッダは送信せずにBodyだけ送信するか(Rewriterとかで処理用)
            if (sendResponseHeader) {
                execSendingHeaderSequence(receiverOut, captureResponseHeader);
            }
            // [nl] HEADの場合はヘッダの送信で終了
            if (requestHeaderArg != null &&
                    HttpHeader.HEAD.equals(requestHeaderArg.getMethod())) {
                return canContinue;
            }

            InputStream in;
            // [nl] 既に受信済のボディがあればそちらを転送して終了
            if (captureResponseBody != null) {
                in = new ByteArrayInputStream(captureResponseBody);
                execSendingBodySequence(receiverOut, in, contentLength);
                return canContinue;
            }
            try {
                // On HttpURLConnection, even if FileNotFoundException occurred
                // above, it appears here.
                // The original FileNotFoundException is included as cause.
                in = con.getInputStream();
            } catch (IOException e) {
                Logger.debugWithThread(e.toString()); // [nl]
                // treat error responses in the same way as normal responses.
                // ad hoc treaing for 407
                if (con instanceof HttpURLConnection) {
                    HttpURLConnection hcon = (HttpURLConnection) con;
                    in = hcon.getErrorStream();
                    if (in == null) {
                        return canContinue;
                    }
                } else {
                    throw e;
                }
            }

            // below, assume IOException does not mean error response, so
            // it is not necessary to consume errorStream.
            try {
                execSendingBodySequence(receiverOut, in, contentLength);
            } catch (IOException e) {
                Logger.debugWithThread(e.toString()); // [nl]
                canContinue = false;
                try {
                    // if the data is too large, give up to cleanup.
                    if (contentLength < 100 * 1024) {
                        consumeInputStream(in);
                    }
                } catch (IOException e1) {
                    Logger.debugWithThread(e1);
                }
            } finally {
                CloseUtil.close(in);
            }
        } catch (IOException e) {
            Logger.debugWithThread(e);
            canContinue = false;
            consumeErrorStream(con);
        }

        return canContinue;
    }

    @Override
    public void stopTransfer() {
        super.stopTransfer();

        if (Boolean.getBoolean("useWorkaroundFastFinalize")) {
            Workarounds.dirtyCloseHttpURLConnectionImplSocket(con);
        }
    }

    /**
     * [nl] レスポンスボディをデコードして取得する。
     *
     * @return レスポンスボディのバイト配列、レスポンスヘッダを取得していなければ null
     * @throws IOException ボディの取得中に問題が発生した
     * @see #getResponseBody(boolean)
     */
    public byte[] getResponseBody() throws IOException {
        return getResponseBody(true);
    }

    /**
     * [nl] レスポンスボディを取得する。
     *
     * このメソッドを呼び出す前に
     * {@link #getResponseHeader(InputStream, HttpRequestHeader) getResponseHeader}
     * を呼び出しておく必要がある。
     *
     * また、このメソッドを呼び出した後に
     * {@link #transferTo(InputStream, OutputStream, HttpRequestHeader, Config)},
     * {@link #selectTransferTo(InputStream, OutputStream, HttpRequestHeader, boolean)}
     * を呼び出すと、既に受信済のヘッダとボディを再送信することになる。
     *
     * このメソッドを複数呼び出した場合は、既に受信済のレスポンスボディを返す。
     * <p>
     * なお、レスポンスボディは全てメモリ内に保持するので、サイズの大きなものを
     * 取得するとメモリを圧迫するので注意すること。
     * <b>動画ファイル等をこのメソッドを使って取得するべきではない。</b>
     *
     * レスポンスボディがエンコードされている場合は needsDecode の値に従う。
     * サポート外のエンコード(bzip2など)の場合、エンコードされたものをそのまま返す。
     * また、デコードした場合はレスポンスヘッダを適切に修正する。
     *
     * @param needsDecode レスポンスボディをデコードするか？
     * @return レスポンスボディのバイト配列、レスポンスヘッダを取得していなければ null
     * @throws IOException ボディの取得中に問題が発生した
     */
    public byte[] getResponseBody(boolean needsDecode) throws IOException {
        if (captureResponseBody != null) {
            return captureResponseBody;
        }
        if (captureResponseHeader == null) {
            throw new HttpIOException("response header not captured");
        }
        if (Integer.MAX_VALUE < contentLength) {
            throw new HttpIOException("contentLength too large: " + contentLength);
        }
        ByteArrayOutputStream bout = new ByteArrayOutputStream(
                contentLength > 0 ? (int)contentLength : BUF_SIZE);
        selectTransferTo(null, bout, null, false);
        con = null; // 受信完了したら必要無くなるので解放しておく

        if (needsDecode) {
            captureResponseBody = decodeResponseBody(bout);
            contentLength = captureResponseBody.length;
            doSetMandatoryResponseHeader(captureResponseHeader);
        } else {
            captureResponseBody = bout.toByteArray();
        }
        return captureResponseBody;
    }

    private byte[] decodeResponseBody(ByteArrayOutputStream bout) throws IOException {
        byte[] responseBody = bout.toByteArray();
        if (responseBody.length == 0) {
            return responseBody;
        }

        InputStream in = null;
        String contentEncoding = captureResponseHeader.getMessageHeader(
                HttpHeader.CONTENT_ENCODING);
        if (contentEncoding != null) {
            in = HttpUtil.getDecodedInputStream(responseBody, contentEncoding);
        }
        if (in != null) {
            bout.reset();
            if (FileUtil.copy(in, bout) < 0) {
                return responseBody; // デコードできなければそのまま返す
            }
            responseBody = bout.toByteArray();
        }
        if (in != null || HttpHeader.IDENTITY.equalsIgnoreCase(contentEncoding)) {
            captureResponseHeader.removeMessageHeader(HttpHeader.CONTENT_ENCODING);
        }
        return responseBody;
    }

    /* (非 Javadoc)
     * @see dareka.processor.Resource#doSetMandatoryHeader(dareka.processor.HttpResponseHeader)
     */
    @Override
    protected void doSetMandatoryResponseHeader(
            HttpResponseHeader responseHeader) {
        if (contentLength == -1) {
            responseHeader.removeMessageHeader(HttpHeader.CONTENT_LENGTH);
        } else {
            responseHeader.setContentLength(contentLength);
        }

        if (canContinue) {
            responseHeader.setMessageHeader(HttpHeader.CONNECTION,
                    HttpHeader.CONNECTION_KEEP_ALIVE);

            if (contentLength != -1) {
                responseHeader.setContentLength(contentLength);
            }
        } else {
            responseHeader.setMessageHeader(HttpHeader.CONNECTION,
                    HttpHeader.CONNECTION_CLOSE);
        }
    }

    /**
     * Set proxy. For security reason, this method must be private because it is
     * called from constructor.
     *
     * @param proxyHost
     * @param proxyPort
     */
    private void setProxyNoOverride(String proxyHost, int proxyPort) {
        if (proxyHost == null || proxyHost.equals("")) {
            proxy = Proxy.NO_PROXY;
        } else {
            proxy =
                    new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost,
                            proxyPort));
        }
    }

    // [nl] プロキシを設定
    public void setProxy(String proxyHost, int proxyPort) {
        setProxyNoOverride(proxyHost, proxyPort);
    }

    /**
     * [nl] 自分自身をプロキシに設定する。transferToの前に呼び出す必要がある。
     * 主にExtensionでnl本体を通してURLを処理したい場合に使用する。
     */
    public void setProxyMyself() {
        setProxyNoOverride("localhost", Integer.getInteger("listenPort"));
        proxyMyself = true;
    }

    private boolean isKnownLengthContent(URLConnection con) throws IOException {
        if (contentLength == -1) {
            if (con instanceof HttpURLConnection) {
                HttpURLConnection hcon = (HttpURLConnection) con;
                int responseCode = hcon.getResponseCode();

                switch (responseCode) {
                case 204: // No Content
                case 205: // Reset Content
                case 304: // Not Modified
                    // the length is always 0.
                    return true;
                default:
                    // do nothing
                    break;
                }

                if (HttpHeader.HEAD.equals(hcon.getRequestMethod())) {
                    // the length is always 0.
                    return true;
                }
            }
            return false;
        } else {
            return true;
        }
    }

    private HttpResponseHeader getResponseHeader(URLConnection con,
            HttpRequestHeader requestHeader) throws IOException {
        HttpResponseHeader responseHeader;

        if (con instanceof HttpURLConnection) {
            responseHeader =
                    new HttpResponseHeader(con.getHeaderField(0) + "\r\n\r\n");
            copyResponseHeaderFrom(con, responseHeader);
        } else {
            responseHeader = new HttpResponseHeader("HTTP/1.1 200 OK\r\n\r\n");
            String contentType = con.getHeaderField(HttpHeader.CONTENT_TYPE);
            if (contentType != null) {
                responseHeader.setMessageHeader(HttpHeader.CONTENT_TYPE,
                        contentType);
            }
        }
        responseHeader.removeHopByHopHeaders();
        responseHeader.setVersion(requestHeader.getVersion());

        // [nl] CORS(Cross-Origin Resource Sharing)を常時許可する
        String origin = requestHeader.getMessageHeader("Origin");
        if (origin != null && Boolean.getBoolean("alwaysAllowOrigin")) {
            responseHeader.setMessageHeader("Access-Control-Allow-Credentials", "true");
            responseHeader.setMessageHeader("Access-Control-Allow-Origin", origin);
        }

        return responseHeader;
    }

    private void copyResponseHeaderFrom(URLConnection con,
            HttpResponseHeader responseHeader) {
        // The value from URLConnection#getHeaderFields() does not
        // preserve the order of the values in its List<String>. This
        // causes a problem on Set-Cookie. Some servers send multiple
        // Set-Cookie with different cookie. In this case, browsers
        // remember only the last Set-Cookie. Because of this behavior
        // of browsers, the order of headers is significant. It seems
        // that the List<String> has reverse order, but it may depend
        // on implementations.

        for (int i = 0;; i++) {
            String value = con.getHeaderField(i);
            if (value == null) {
                break;
            }

            String key = con.getHeaderFieldKey(i);
            if (key == null) {
                // start line
                continue;
            }

            responseHeader.addMessageHeader(key, value);
        }
    }

    private void prepareForConnect(HttpRequestHeader requestHeader,
            InputStream receiverIn, URLConnection con) throws ProtocolException {
        long requestContentLength = requestHeader.getContentLength();

        prepareConfiguration(con);
        prepareMethod(requestHeader, receiverIn, con, requestContentLength);
        prepareHeaders(requestHeader, con);
    }

    private void prepareConfiguration(URLConnection con) {
        if (con instanceof HttpURLConnection) {
            HttpURLConnection hcon = (HttpURLConnection) con;
            hcon.setInstanceFollowRedirects(false);
        }
        if (con instanceof HttpsURLConnection) {
            HttpsURLConnection scon = (HttpsURLConnection) con;
            SSLContext sslContext = TlsClientContextFactory.getContext();
            if (sslContext != null) {
                scon.setSSLSocketFactory(sslContext.getSocketFactory());
            }
        }
    }

    private void prepareMethod(HttpRequestHeader requestHeader,
            InputStream receiverIn, URLConnection con, long requestContentLength)
            throws ProtocolException {
        if (!(con instanceof HttpURLConnection)) {
            throw new IllegalArgumentException("con is not HttpURLConnection");
        };
        HttpURLConnection hcon = (HttpURLConnection) con;

        if (requestContentLength > 0L) {
            if (receiverIn == null) {
                throw new IllegalArgumentException(
                    "Content-Length(" + requestContentLength
                    + ") is greater than 0, but receiverIn is null");
            };
            con.setDoOutput(true);

            if (requestContentLength > BUFFERED_POST_MAX) {
                hcon.setFixedLengthStreamingMode(requestContentLength);
            }
        }

        hcon.setRequestMethod(requestHeader.getMethod());
    }

    private void prepareHeaders(HttpRequestHeader requestHeader,
            URLConnection con) {
        for (Map.Entry<String, List<String>> entry : requestHeader.getMessageHeaders().entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            for (String value : values) {
                // request properties are empty in the initial state
                // of URLConnection, so there is no concern about
                // duplication with default headers.
                con.addRequestProperty(key, value);
            }
        }
    }

    private void sendBodyIfNeccessary(InputStream receiverIn,
            HttpRequestHeader header, URLConnection con) throws IOException {
        if (con.getDoOutput()) {
            OutputStream out = con.getOutputStream();
            try { // ensure out.close()
                HttpUtil.sendBody(out, receiverIn, header.getContentLength());
            } finally {
                CloseUtil.close(out);
            }
        }
    }

    private void consumeErrorStream(URLConnection con) {
        try {
            if (con instanceof HttpURLConnection) {
                HttpURLConnection hcon = (HttpURLConnection) con;
                InputStream es = hcon.getErrorStream();
                if (es != null) {
                    try {
                        consumeInputStream(es);
                    } finally {
                        CloseUtil.close(es);
                    }
                }
            }
        } catch (IOException e) {
            Logger.debugWithThread(e);
        }
    }

    private void consumeInputStream(InputStream in) throws IOException {
        // in case of finishing this application
        Thread currentThread = Thread.currentThread();

        byte[] buf = new byte[128];
        while (!currentThread.isInterrupted() && in.read(buf) != -1) {
            // discard the input
        }
    }


    private static List<BiConsumer<HttpRequestHeader, URLConnection>> requestHooks = null;

    public static void registerRequestHook(BiConsumer<HttpRequestHeader, URLConnection> hook) {
        if (requestHooks == null) {
            requestHooks = new LinkedList<>();
        }
        requestHooks.add(hook);
    }

    private void applyRequestHook(HttpRequestHeader requestHeader, URLConnection con) {
        if (requestHooks == null) return;
        for (BiConsumer<HttpRequestHeader, URLConnection> hook : requestHooks) {
            hook.accept(requestHeader, con);
        }
    }
}
