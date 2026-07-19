package dareka.processor.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.Main;
import dareka.NLConfig;
import dareka.SocketWrapper;
import dareka.common.Config;
import dareka.common.FileUtil;
import dareka.common.Pair;
import dareka.common.Logger;
import dareka.extensions.Rewriter;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.HttpUtil;
import dareka.processor.LocalFileResource;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.URLResource;

public class RewriterProcessor implements Processor {
    private static final String[] SUPPORTED_METHODS = new String[] {
        HttpHeader.GET, HttpHeader.POST };

    protected Config config;
    protected ArrayList<Rewriter> rewriterList = new ArrayList<>();
    private Rewriter getFlvRewriter;
    private EasyRewriter easyRewriter;

    public RewriterProcessor(Config config) {
        this.config = config;

        // システム用簡易フィルタを登録
        addRewriter(EasyRewriter.getInstance_sys());

        // デフォルトのRewriterを登録
        addRewriter(new WatchRewriter());
        addRewriter(new SearchRewriter());
        addRewriter(getFlvRewriter = new GetFlvRewriter());
        addRewriter(new ThumbWatchRewriter());
    }

// UserFilterの追加用
    public void addUserFilter() {
        // 簡易フィルタ(SP1.11)
        addRewriter(easyRewriter = EasyRewriter.getInstance());
    }

    public void addRewriter(Rewriter rewriter) {
        rewriterList.add(rewriter);
    }

    // Processor interface
    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

// 対象をすべてのURLに拡大
    private static final Pattern SUPPORTED_PATTERN = Pattern.compile(".+");
    private static final Pattern NICOVIDEO_PATTERN = Pattern.compile(
            "https?://[^/]+\\.(?:nicovideo|nimg|simg)\\.jp/");

    @Override
    public Pattern getSupportedURLAsPattern() {
        return SUPPORTED_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    private static final Pattern CHARSET_PATTERN = Pattern.compile(
            "(?i)charset\\s*=\\s*([0-9a-z\\-_]+)");
    private static final Pattern HTML_CHARSET_PATTERN = Pattern.compile(
            "(?i)<meta[^>]*charset['\"\\s]*=['\"\\s]*([0-9a-z\\-_]+)");

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {
        String uri = requestHeader.getURI();
        InputStream in = browser.getInputStream();

        if (isDebugURL(uri)) {
            Logger.info("RewriterURL = " + uri);
        }

// POSTの書き換えに対応
        String postString = null;
        ArrayList<Pair<Rewriter, Matcher>> postRW = null;
        if (HttpHeader.POST.equals(requestHeader.getMethod())) {
            String postURI = uri.replaceFirst("^(https?)://", "$1://POST/");
            postRW = getMatchedRewriter(postURI);
            long contentLength = requestHeader.getContentLength();
            if (postRW.size() > 0 &&
                    contentLength <= Long.getLong("postSizeMax", 16*1024*1024)) {
                postString = getPostString(requestHeader, browser, "UTF-8");
                String replaced = doRewriter(postURI, postString,
                        requestHeader, getDummyResponseHeader(), postRW, null);
                if (postString != replaced) {
                    byte[] postBuf = replaced.getBytes("UTF-8");
                    requestHeader.setContentLength(postBuf.length);
                    in = new ByteArrayInputStream(postBuf);
                }
                if (Boolean.getBoolean("debugRewriterOriginal")) {
                    debugOutputBody(uri, "PostOrgBody", postString);
                }
                debugOutputBody(uri, "RequestBody", replaced);
            }
        }

// getflvだけは、postの内容がRewriterに必要なので特別扱い
// getflvの帰ってこないIDだとcontentだけでIDがわからないので、
// Rewriterにパラメータを与える為にURIを捏造
// (鯖へのアクセスはRequestHeaderからなので元のまま)
        if (getFlvRewriter != null &&
                postString != null &&
                getFlvRewriter.getRewriterSupportedURLAsPattern().matcher(uri).lookingAt()) {
            uri += "&" + postString;
        }

        ArrayList<Pair<Rewriter, Matcher>> rewriters = getMatchedRewriter(uri);
        int rwSize = rewriters.size();
        if (rwSize == 0 && (postRW == null || postRW.isEmpty())) {
            // 置換対象でなければそのまま返す
            return Resource.get(Resource.Type.URL, requestHeader.getURI());
        }

        // 置換対象があれば一旦ダウンロード後にRewriterで処理する
        requestHeader.removeHopByHopHeaders();
// 置換する場合は解凍できないEncodingは削除
        String acceptEncoding = requestHeader.getMessageHeader(HttpHeader.ACCEPT_ENCODING);
        if (rwSize > 0 && acceptEncoding != null) {
            acceptEncoding = acceptEncoding.toLowerCase().replaceAll(
                "(?: *, *)?(?:bzip2|sdch|br|compress|zstd|dcb|dcz)(?:;[^,]*)?", "");
            acceptEncoding = acceptEncoding.replaceFirst("^ *, *", "");
            requestHeader.setMessageHeader(HttpHeader.ACCEPT_ENCODING, acceptEncoding);
        }

        // ヘッダを受信して、Bodyも受信するか判断
        URLResource r = new URLResource(requestHeader.getURI());
        HttpResponseHeader responseHeader = r.getResponseHeader(in, requestHeader);
        if (responseHeader == null) {
            Logger.warning("failed to rewrite: " + uri + " (no responseHeader)");
            return r;
        }

        // nlFilter拡張に対応するためEasyRewriterを自前で処理する
        ArrayList<EasyRewriter.UserFilter> userFilters = null;
        if (rwSize > 0 && rewriters.get(rwSize - 1).first == easyRewriter) {
            userFilters = easyRewriter.getMatchedUserFilter(
                    uri, requestHeader, responseHeader);
            if (userFilters.isEmpty()) {
                // 適用するフィルタが無ければEasyRewriterを削除
                rewriters.remove(rwSize - 1);
            }
        }

        // URLに.phpは一応書き換え対象
        // その他のマッチは外部に移動
        String contentType = responseHeader.getMessageHeader(HttpHeader.CONTENT_TYPE);
        if (rwSize > 0 &&
            (responseHeader.getStatusCode() == 200 ||
             responseHeader.getStatusCode() / 100 == 4 ||
             responseHeader.getStatusCode() / 100 == 5)
            && (uri.contains(".php") || matchContentType(contentType, userFilters)))
        {
            // Bodyの取得
            byte[] bcontent = r.getResponseBody();
            if (bcontent == null) {
                Logger.warning("failed to rewrite: " + uri + " (no responseBody)");
                return r;
            }
            if (responseHeader.getMessageHeader(HttpHeader.CONTENT_ENCODING) != null) {
                Logger.warning("failed to rewrite: " + uri + " (cannot decode)");
                return r;
            }
            responseHeader.removeMessageHeader("Vary");
            responseHeader.removeMessageHeader("Accept-Ranges");
            responseHeader.removeHopByHopHeaders();

            // nlパラメータのコピー(rc2.03)
            for (Map.Entry<String, String> e : requestHeader.getParameters().entrySet()) {
                responseHeader.setParameter(e.getKey(), e.getValue());
            }
            bcontent = doRewriter(uri, bcontent,
                    requestHeader, responseHeader, rewriters, userFilters);
            return StringResource.getRawResource(responseHeader, bcontent);
        }
        else {
            // ヘッダから違うと判定したものは改変なしで送信
            responseHeader.removeMessageHeader("Vary");
            responseHeader.removeMessageHeader("Accept-Ranges");
            Logger.debugWithThread("rewriter content-type: " + contentType);
        }

        return r;
    }

    private boolean isDebugURL(String url) {
        if (Boolean.getBoolean("debugRewriter")) {
            Matcher m = NLConfig.getMatcher("debugRewriterURLPattern", url);
            return m == null || m.find();
        }
        return false;
    }

    private void debugOutputBody(String url, String prefix, String content) {
        if (isDebugURL(url)) {
            int length = Integer.getInteger("debugRewriterMaxLength", 0);
            if (length > 0 && content.length() > length) {
                content = content.substring(0, length);
            }
            Logger.info(prefix + ": " + content);
        }
    }

    /**
     * リクエストがPOSTの場合に、その入力内容を指定文字セットで取得した文字列を返す。
     * <p>入力文字セット名にnullを指定した場合は ISO-8859-1 で取得して、更に入力が
     * <b>application/x-www-form-urlencoded</b>であれば UTF-8 でデコードも行う。
     * <p>nl本体側で入力をバッファリングして取得前の状態に戻すので、本メソッドを
     * {@link Processor#onRequest(HttpRequestHeader, Socket)}
     * 内から呼び出しても、それ以降の処理で再び入力内容を取得できる。
     *
     * @param requestHeader リクエストヘッダ
     * @param browser ソケット
     * @param charsetName 入力文字セット名、nullなら"ISO-8859-1"でかつデコード有り
     * @return 次のいずれかの値を返す
     * <ul>
     * <li>リクエストがPOSTなら入力内容を指定文字セットで取得した文字列
     * <li>リクエストがPOSTでは無い場合はnull
     * <li>入力のContent-Lengthが不正の場合は空文字列
     * </ul>
     * @throws IOException 入出力エラーが発生した場合
     * @since NicoCache_nl+110522mod
     */
    public String getPostString(HttpRequestHeader requestHeader, Socket browser,
            String charsetName) throws IOException {
        if (!HttpHeader.POST.equals(requestHeader.getMethod())) {
            return null;
        }
        long contentLength = requestHeader.getContentLength();
        if (contentLength <= 0L || Integer.MAX_VALUE < contentLength) {
            Logger.debugWithThread("invalid POST contentLength: " + contentLength);
            return "";
        }
        byte[] buf = new byte[(int) contentLength];
        int pos = 0;
        while (pos < buf.length) {
            int len = buf.length - pos;
            pos += browser.getInputStream().read(buf, pos, len);
        }
        if (browser instanceof SocketWrapper) {
            ((SocketWrapper) browser).resetInputStreamBuffer();
        }
        if (charsetName == null) {
            String postString = new String(buf, "ISO-8859-1");
            if ("application/x-www-form-urlencoded".equalsIgnoreCase(
                    requestHeader.getMessageHeader(HttpHeader.CONTENT_TYPE))) {
                postString = URLDecoder.decode(postString, "UTF-8");
            }
            return postString;
        }
        return new String(buf, charsetName);
    }

    // Content-TypeからRewriter処理を実行しても良いかを判定する
    private boolean matchContentType(
            String contentType, ArrayList<EasyRewriter.UserFilter> userFilters) {
        if (contentType == null) {
            return true; // Content-Typeが無い場合は内容が分からないので許可
        }
        if (NLConfig.find("rewriterContentType", contentType)) {
            return true;
        }
        // nlFilterのContentType=指定があるならそちらもチェック
        if (userFilters != null && userFilters.size() > 0) {
            for (EasyRewriter.UserFilter u : userFilters) {
                if (u.contentType != null) {
                    return true; // 既にマッチ済なので存在するなら無条件で許可
                }
            }
        }
        return false;
    }

    /**
     * URLにマッチするRewriterを返す。パラメータ部は取り除いてマッチングを行う。
     * 特定の拡張子(rewriterIgnoreExt)で終わるURLの場合は常にマッチしない。
     *
     * @param url マッチ対象のURL
     * @return マッチしたRewriterのリスト、マッチしなければ空リスト
     * @since NicoCache_nl+101219mod
     */
    ArrayList<Pair<Rewriter, Matcher>> getMatchedRewriter(String url) {
        ArrayList<Pair<Rewriter, Matcher>> matched =
                new ArrayList<>(rewriterList.size());
        if (!isIgnoreExt(url)) {
            Matcher m;
            if (LocalDirProcessor.isSupportedURL(url)) {
                // ローカルの場合はnlFilterのみ対象
                m = easyRewriter.getRewriterSupportedURLAsPattern().matcher(url);
                if (m.lookingAt()) {
                    matched.add(new Pair<>(easyRewriter, m));
                    return matched;
                }
            }
            for (Rewriter rw : rewriterList) {
                m = rw.getRewriterSupportedURLAsPattern().matcher(url);
                if (m.lookingAt()) {
                    matched.add(new Pair<>(rw, m));
                }
            }
        }
        return matched;
    }

    private boolean isIgnoreExt(String url) {
        int end = url.indexOf('?'); // パラメータ部分は無視
        if (end < 0) end = url.length();
        for (int i = end - 1; i >= 0; i--) {
            if (url.charAt(i) == '.') {
                String ext = url.substring(i + 1, end);
                if (NLConfig.matches("rewriterExtIgnore", ext)) {
                    return true;
                }
                break;
            } else if (url.charAt(i) == '/') {
                break;
            }
        }
        return false;
    }

    // Rewriter処理(コンテンツ用)
    private byte[] doRewriter(String url, byte[] bcontent,
            HttpRequestHeader requestHeader, HttpResponseHeader responseHeader,
            ArrayList<Pair<Rewriter, Matcher>> rewriters,
            ArrayList<EasyRewriter.UserFilter> userFilters) throws IOException {
        String original = null;
        String charset = getDefaultCharset(url);
        String contentType = responseHeader.getMessageHeader(HttpHeader.CONTENT_TYPE);

        if (contentType != null) {
            Matcher m = CHARSET_PATTERN.matcher(contentType);
            boolean found = m.find();
            if (found && Charset.isSupported(m.group(1))) {
                charset = m.group(1);
            } else if (contentType.startsWith("text/html")) {
                original = new String(bcontent, "ISO-8859-1");
                m = HTML_CHARSET_PATTERN.matcher(original);
                if (m.find() && Charset.isSupported(m.group(1))) {
                    charset = m.group(1);
                }
                if (!"ISO-8859-1".equalsIgnoreCase(charset)) {
                    original = null;
                }
            }
            if (!found && charset != null) {
                contentType += "; charset=" + charset;
            }
        }

        Logger.debugWithThread("rewriter content-type: " + contentType);
        if (charset == null) {
            Logger.debugWithThread("no rewriter content");
            return bcontent;
        } else if (original == null) {
            original = new String(bcontent, charset);
        }
        String replaced = doRewriter(url, original,
                requestHeader, responseHeader, rewriters, userFilters);
        if (original != replaced) {
            bcontent = replaced.getBytes(charset);
            // 置換処理した場合はLast-Modifiedを除去してブラウザが積極的に
            // キャッシュしないようにする(Cache-Controlまではしない)
            responseHeader.removeMessageHeader(HttpHeader.LAST_MODIFIED);
        }
        if (Boolean.getBoolean("debugRewriterOriginal")) {
            debugOutputBody(url, "OriginalBody", original);
        }
        debugOutputBody(url, "ResponseBody", replaced);

        return bcontent;
    }

    private String getDefaultCharset(String url) {
        // ニコニコ内ならUTF-8で決め打ち
        if (NICOVIDEO_PATTERN.matcher(url).lookingAt()) {
            return "UTF-8";
        }
        String charset = System.getProperty("rewriterDefaultCharset", "");
        if (charset.length() > 0 || Charset.isSupported(charset)) {
            return charset;
        }
        return null;
    }

    // Rewriter処理(文字列用)
    private String doRewriter(String url, String content,
            HttpRequestHeader requestHeader, HttpResponseHeader responseHeader,
            ArrayList<Pair<Rewriter, Matcher>> rewriters,
            ArrayList<EasyRewriter.UserFilter> userFilters) throws IOException {
        if (EasyRewriter.checkDebugMode()) {
            Logger.info("[Debug]Filter Processing start. URL: " + url);
        }
        long startTime = System.nanoTime();
        if (rewriters == null) {
            rewriters = getMatchedRewriter(url);
        }
        String original = content;
        for (Pair<Rewriter, Matcher> entry : rewriters) {
            if (entry.first != easyRewriter) {
                content = entry.first.onMatch(entry.second, responseHeader, content);
            } else {
                // EasyRewriterを直接処理する
                if (userFilters == null) {
                    userFilters = easyRewriter.getMatchedUserFilter(
                            url, requestHeader, responseHeader);
                }
                content = easyRewriter.applyUserFilter(
                        url, content, requestHeader, responseHeader, userFilters);
            }
        }
        if (EasyRewriter.checkDebugMode() || Boolean.getBoolean("dareka.debug")) {
            long elapsedTime = System.nanoTime() - startTime;
            if (EasyRewriter.checkDebugMode()) {
                Logger.info("[Debug]Filter Processing end. time: %,dms",
                        (int)(elapsedTime / 1000000));
            }
            String result = original != content ? " replaced" : "";
            Logger.debugWithThread(String.format(
                    "rewriter elapsed time: %,dns%s", elapsedTime, result));
        }
        return content;
    }

    private HttpResponseHeader getDummyResponseHeader() throws IOException {
        return new HttpResponseHeader(
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n\r\n");
    }

    /**
     * 文字列をRewriterで置換する。
     * <p>
     * リクエストヘッダおよびレスポンスヘッダはnlFilterが置換条件(RequestHeader,
     * ContentType 等)を処理するために必要なので、なるべく指定すること。
     * なお、レスポンスヘッダにnullを渡した場合は"text/plain"として扱われる。
     *
     * @param url Rewriter処理対象のURL
     * @param content 置換対象の文字列
     * @param requestHeader リクエストヘッダ、不要ならnull
     * @param responseHeader レスポンスヘッダ、不要ならnull
     * @return 置換した文字列、置換対象で無ければcontentをそのまま返す
     * @since NicoCache_nl+101219mod
     */
    public String stringRewriter(String url, String content,
            HttpRequestHeader requestHeader, HttpResponseHeader responseHeader)
            throws IOException {
        ArrayList<Pair<Rewriter, Matcher>> rewriters = getMatchedRewriter(url);
        if (rewriters.size() > 0) {
            if (responseHeader == null) {
                responseHeader = getDummyResponseHeader();
            }
            content = doRewriter(url, content,
                    requestHeader, responseHeader, rewriters, null);
        }
        return content;
    }

    /**
     * こちらはnlFilterの置換条件(RequireHeader, ContentType 等)に対応できないので
     * HttpRequestHeader, HttpResponseHeader 引数のある版を使ってください。
     * @see #stringRewriter(String, String, HttpRequestHeader, HttpResponseHeader)
     */
    @Deprecated
    public String stringRewriter(String url, String content) throws IOException {
        return stringRewriter(url, content, null, null);
    }

    /**
     * コンテンツ(バイト配列)をRewriterで置換する。文字コードは適切に処理する。
     *
     * @param url Rewriter処理対象のURL
     * @param bcontent 置換対象のコンテンツ
     * @param requestHeader リクエストヘッダ
     * @param responseHeader レスポンスヘッダ
     * @return 置換したコンテンツ、置換対象で無ければbcontentをそのまま返す
     * @see #stringRewriter(String, String, HttpRequestHeader, HttpResponseHeader)
     * @since NicoCache_nl+101219mod
     */
    public byte[] contentRewriter(String url, byte[] bcontent,
            HttpRequestHeader requestHeader, HttpResponseHeader responseHeader) {
        if (url == null && requestHeader != null) {
            url = requestHeader.getURI();
        }
        return contentRewriter(url, bcontent,
                requestHeader, responseHeader, getMatchedRewriter(url), null);
    }

    private byte[] contentRewriter(String url, byte[] bcontent,
            HttpRequestHeader requestHeader, HttpResponseHeader responseHeader,
            ArrayList<Pair<Rewriter, Matcher>> rewriters,
            ArrayList<EasyRewriter.UserFilter> userFilters) {
        if (rewriters != null && bcontent != null) {
            try {
                return doRewriter(url, decode(bcontent, responseHeader),
                        requestHeader, responseHeader, rewriters, userFilters);
            } catch (IOException e) {
                Logger.error(e);
            }
        }
        return bcontent;
    }

    private byte[] decode(byte[] bcontent, HttpResponseHeader responseHeader)
            throws IOException {
        if (responseHeader != null) {
            String enc = responseHeader.getMessageHeader(HttpHeader.CONTENT_ENCODING);
            if (enc != null) {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                FileUtil.copy(HttpUtil.getDecodedInputStream(bcontent, enc), bout);
                bcontent = bout.toByteArray();
                responseHeader.removeMessageHeader(HttpHeader.CONTENT_ENCODING);
            }
        }
        return bcontent;
    }

    /**
     * ローカルファイルをRewriterで置換する(localRewriter=trueの場合のみ)。
     * 処理した内容をリソースとして返す。
     *
     * @param url Rewriter処理対象のURL
     * @param file 置換対象のローカルファイル
     * @param requestHeader リクエストヘッダ
     * @return 置換処理したリソース
     * @see #stringRewriter(String, String, HttpRequestHeader, HttpResponseHeader)
     * @since NicoCache_nl+101219mod
     */
    public Resource localRewriter(String url, File file,
            HttpRequestHeader requestHeader) throws IOException {
        if (file == null || file.isDirectory()) {
            return StringResource.getNotFound();
        }
        LocalFileResource r = new LocalFileResource(file);
        if (!Boolean.getBoolean("localRewriter")) {
            return r;
        }
        RewriterProcessor rewriter = Main.getRewriterProcessor();
        ArrayList<Pair<Rewriter, Matcher>> rewriters = rewriter.getMatchedRewriter(url);
        if (rewriters.isEmpty()) {
            return r;
        }
//      // 必ず書き換えるためにIf-Modified-Sinceは削除
//      requestHeader.removeMessageHeader(HttpHeader.IF_MODIFIED_SINCE);
        HttpResponseHeader responseHeader = r.getResponseHeader(requestHeader, true);
        ArrayList<EasyRewriter.UserFilter> userFilters =
            easyRewriter.getMatchedUserFilter(url, requestHeader, responseHeader);
        if (userFilters.isEmpty()) {
            return r;
        }
        // 必ず書き換えるためにIf-Modified-Sinceを削除して再取得
        requestHeader.removeMessageHeader(HttpHeader.IF_MODIFIED_SINCE);
        responseHeader = r.getResponseHeader(requestHeader, true);
        byte[] bcontent = rewriter.contentRewriter(url, r.getResponseBody(),
                requestHeader, responseHeader, rewriters, userFilters);
        return StringResource.getRawResource(responseHeader, bcontent);
    }

    /**
     * file = new File(path)
     * @param path ローカルファイルのパス
     * @see #localRewriter(String, File, HttpRequestHeader)
     */
    public Resource localRewriter(String uri, String path,
            HttpRequestHeader requestHeader) throws IOException {
        return localRewriter(uri, new File(path), requestHeader);
    }
}
