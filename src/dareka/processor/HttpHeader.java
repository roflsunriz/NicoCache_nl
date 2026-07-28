package dareka.processor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.HttpIOException;
import dareka.common.Logger;

/**
 * HTTP-messageの抽象化。
 *
 */
public class HttpHeader {
    public static final String GET = "GET";
    public static final String POST = "POST";
    public static final String HEAD = "HEAD";
    public static final String PUT = "PUT";
    public static final String DELETE = "DELETE";
    public static final String PATCH = "PATCH";
    public static final String OPTIONS = "OPTIONS";

    public static final String ACCEPT_ENCODING = "Accept-Encoding"; // [nl]
    public static final String CONNECTION = "Connection";
    public static final String CONNECTION_CLOSE = "close";
    public static final String CONNECTION_KEEP_ALIVE = "keep-alive";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_LENGTH = "Content-Length";
    public static final String CONTENT_ENCODING = "Content-Encoding";
    public static final String CONTENT_ENCODING_DEFLATE = "deflate";
    public static final String CONTENT_ENCODING_GZIP = "gzip";
    public static final String COOKIE = "Cookie";
    public static final String IDENTITY = "identity";
    public static final String LOCATION = "Location";

    // [nl] 日付を扱うヘッダ
    public static final String DATE = "Date";
    public static final String EXPIRES = "Expires";
    public static final String IF_MODIFIED_SINCE = "If-Modified-Since";
    public static final String IF_UNMODIFIED_SINCE = "If-Unmodified-Since";
    public static final String LAST_MODIFIED = "Last-Modified";

    // [nl] 外部からパラメータを受け渡すためのヘッダ(リクエスト前に除去される)
    public static final String X_NICOCACHE_NL = "X-NicoCache_nl";
    public static final String XNL_PROXY_MYSELF = "proxy-myself";
    public static final String XNL_TLS = "tls";

    /**
     * Message Headers.
     *
     * from RFC2616 4.2 Message Headers:
     *
     * <pre>
     *        message-header = field-name &quot;:&quot; [ field-value ]
     *        field-name     = token
     *        field-value    = *( field-content | LWS )
     *        field-content  = &lt;the OCTETs making up the field-value
     *                         and consisting of either *TEXT or combinations
     *                         of token, separators, and quoted-string&gt;
     * </pre>
     *
     *
     * 2.2 Basic Rules:
     * <pre>
     *        OCTET          = &lt;any 8-bit sequence of data&gt;
     *        CHAR           = &lt;any US-ASCII character (octets 0 - 127)&gt;
     *        TEXT           = &lt;any OCTET except CTLs,
     *                         but including LWS&gt;
     *        CTL            = &lt;any US-ASCII control character
     *                         (octets 0 - 31) and DEL (127)&gt;
     *        LWS            = [CRLF] 1*( SP | HT )
     *        token          = 1*&lt;any CHAR except CTLs or separators&gt;
     *        separators     = &quot;(&quot; | &quot;)&quot; | &quot;&lt;&quot; | &quot;&gt;&quot; | &quot;@&quot;
     *                       | &quot;,&quot; | &quot;;&quot; | &quot;:&quot; | &quot;\&quot; | &lt;&quot;&gt;
     *                       | &quot;/&quot; | &quot;[&quot; | &quot;]&quot; | &quot;?&quot; | &quot;=&quot;
     *                       | &quot;{&quot; | &quot;}&quot; | SP | HT
     *        quoted-string  = ( &lt;&quot;&gt; *(qdtext | quoted-pair ) &lt;&quot;&gt; )
     *        qdtext         = &lt;any TEXT except &lt;&quot;&gt;&gt;
     *        quoted-pair    = &quot;\&quot; CHAR
     * </pre>
     */
    private static final Pattern MESSAGE_HEADER_PATTERN =
            Pattern.compile("^([^:]+):\\s*(.*)\r?\n");
    private static final Pattern FIELD_NAME_PATTERN =
            Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final int DEFAULT_MAX_HEADER_BYTES = 64 * 1024;
    private static final int DEFAULT_MAX_HEADER_LINE_BYTES = 16 * 1024;
    /*
     * 文字エンコーディングの扱いについてメモ:
     *
     * field-contentは日本語などの非ASCII文字を取り得る。これにどう対応するか。
     *
     * HTTPのほとんどのfield-contentはASCII文字しか取らないので
     * ISO-8859-1, MS932, UTF-8等主要な文字コードのどれを使っても大抵は動く。
     * しかし、一部のブラウザは非ASCII文字を含めたURLを送ってくる。
     * (Request-LineやRefererフィールドなど。)
     * これはRFC3986違反なのだが、そうはいっても対応する必要がある。
     * また、非標準ヘッダだがContent-Dispositionも非ASCII文字を含み得る。
     *
     * 一方、NicoCacheの内部では URLConnection#addRequestProperty(String, String)
     * を使っていることや利便性のためにfield-contentはStringとして扱っている。
     * そのため、field-contentのバイトシーケンスとStringのマッピングを
     * どの文字エンコーディングに基づいて行うかを考える必要がある。
     *
     * どの文字エンコーディングを使うのが良いかは簡単ではない。
     *
     * 単にブラウザ-サーバ間の通信を中継するのであれば、
     * field-contentについては特に意識せずにそのまま横流しすればよい。
     * これを行うには文字エンコーディングはISO-8859-1を使えばよい。
     * 実際のバイトシーケンスがMS932やUTF-8の文字だった場合は、
     * JavaのStringとしては化けた文字列になるが、それをISO-8859-1で
     * バイトシーケンスに戻すと元のバイトシーケンスと同じになるので、
     * 横流しするだけなら問題は起きない。
     *
     * しかし、field-contentを URLConnection#addRequestProperty(String, String)
     * に渡す場合はISO-8859-1だと問題が起きる。
     * 具体的にはブラウザからのリクエストを受けて、
     * URLResourceでサーバにリクエストを投げる場合。
     * URLResouce、具体的にはHttpURLConnection、さらにその内部実装の
     * sun.net.NetworkClient はStringをバイトシーケンスに変換する際に
     * file.encodingを使う。日本語WindowsではこれはMS932になる。
     * バイトシーケンスをISO-8859-1でStringにして、それをMS932でバイトシーケンス
     * にすると、当然元とは異なるものになる。
     * 仮にStringにする際にMS932を使ったとしても、
     * UTF-8の日本語などでMS932の対応範囲外のバイトが含まれていた場合は
     * 情報が失われてしまい(「?」に置換される)、やはり元には戻らなくなる。
     * 通常の対策としては、file.encodingを変更するか、
     * URLConnectionを使うのをやめるかしか解決策がないが、
     * どちらも影響が大きく大変。
     *
     * さらに厄介な問題として、Request-Lineとfield-contentに異なる
     * 文字エンコーディングを使ってくる場合がある。
     * Request-LineはSJIS or MS932、field-contentはUTF-8など。
     * そのためヘッダ全体で1つの文字エンコーディングを使うわけにはいかない。
     *
     * 中継ではなく、NicoCacheがクライアントとして振る舞う場合は
     * 非ASCII文字を使う必要は現状無いので問題ない。
     *
     * 中継ではなく、NicoCacheがoriginサーバとして振る舞う場合は
     * URLConnectionを使わないので好きに文字エンコーディングを決められる。
     * 影響するfield-contentは基本的にContent-Dispositionのみ。
     * ブラウザによって解釈できる文字コードが異なるので、
     * HttpHeaderの外部から指定できる必要がある。
     * 各field-contentごとに文字コードが異なる可能性があるので、
     * Stringからバイトシーケンスに変換する際の文字エンコーディングで
     * 対応するためには各field-contentごとに文字エンコーディングを
     * 保持しなければならない。そこで、Stringからバイトシーケンスへの
     * 変換はISO-8859-1に固定してしまい、setMessageHeader の際に
     * ISO-8859-1で変換すると望むバイト列になる化けたStringとして
     * 設定できるようにする。
     *
     * 結論。
     *
     * このクラス内では、バイトシーケンスとStringの変換には
     * 情報が失われないISO-8859-1を使う。
     * 非ASCII文字が化ける対策として、文字エンコーディングを指定して
     * field-contentを読み書きするメソッドを用意する。
     * ヘッダをバイトシーケンスとして取得するgetBytes()を用意する。
     * URLConnectionは別途URLConnectionを使っているところで何とかする。
     */
    private static final Pattern MULTI_TOKEN_SPLIT =
            Pattern.compile("\\s*,\\s*");
    private static final String ISO_8859_1 = "ISO-8859-1";

    private String startLine = null;
    private HttpMessageHeaderHolder messageHeaders =
            new HttpMessageHeaderHolder();

    /**
     * [nl] RFC2822形式の日付/時刻文字列を取得する。
     * @param time 取得対象のエポックからの累積時間(ミリ秒)
     * @return 日付/時刻文字列。timeが負の値ならnull
     */
    public static String getDateString(long time) {
        if (time < 0) {
            return null;
        }

        DateFormat df = getDateFormatForDateField();
        return df.format(new Date(time));
    }

    /**
     * [nl] 日付/時刻文字列をパースしてエポックからの累積時間(ミリ秒)で返す。
     * @param date 日付/時刻文字列(RFC2822)
     * @return エポックからの累積時間(ミリ秒)。パース出来なければ-1
     */
    public static long parseDateString(String date) {
        if (date != null) {
            DateFormat df = getDateFormatForDateField();
            try {
                return df.parse(date).getTime();
            } catch (ParseException e) {
                // ignore
            }
        }

        return -1L;
    }

    // HTTP-date(RFC2822 3.3. Date and Time Specification)
    private static DateFormat getDateFormatForDateField() {
        // RFC2822 allows to omit seconds, but this method does not.
        // "Z" also accepts obsoleted time zone form such as "JST".
        DateFormat df =
                new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("GMT"));

        return df;
    }

    public HttpHeader(InputStream source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }

        init(source);
    }

    public HttpHeader(String source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }

        //init(new ByteArrayInputStream(source.getBytes("UTF-8")));
        init(new ByteArrayInputStream(source.getBytes(ISO_8859_1)));
    }

    // [nl] リクエストの1行目以外はUTF-8で解釈する (FxでRefererがinvalidになる対策）(SP1.05)
    @SuppressWarnings("unused")
    private void initByStringImpl(InputStream source) throws IOException, HttpIOException {
        int ch;
        ByteArrayOutputStream line = new ByteArrayOutputStream(512);

        while ((ch = source.read()) != -1) {
            line.write(ch);

            if (ch != '\n') { // go next read() immediately for performance.
                continue;
            }

            // 1行目以降で最初の空行(CRLF)を見つけたらヘッダ終わり
            if (startLine != null && line.size() == 2 && line.toString().equals("\r\n")) {
                break;
            }

            if (startLine == null) {
                // IE sends additional CRLF after POST request.
                // see http://support.microsoft.com/kb/823099/
                // see http://httpd.apache.org/docs/1.3/misc/known_client_problems.html#trailing-crlf
                if (line.size() == 2) {
                    line.reset();
                    continue;
                }

                startLine = line.toString("ISO-8859-1");
            } else {
                Matcher m = MESSAGE_HEADER_PATTERN.matcher(line.toString("UTF-8"));
                if (m.find()) {
                    messageHeaders.add(m.group(1), m.group(2));
                } else {
                    Logger.warning("invalid header field: " + line);
                }
            }

            line.reset();
        }

        if (startLine == null || ch == -1) {
            throw new HttpIOException("premature end of header: "
                    + line);
        }
    }

    private void init(InputStream source) throws IOException, HttpIOException {
        int ch;
        int headerBytes = 0;
        int maxHeaderBytes = positiveIntegerProperty(
                "httpHeaderMaxBytes", DEFAULT_MAX_HEADER_BYTES);
        int maxLineBytes = Math.min(
                maxHeaderBytes,
                positiveIntegerProperty(
                        "httpHeaderMaxLineBytes",
                        DEFAULT_MAX_HEADER_LINE_BYTES));
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(512);

        while ((ch = source.read()) != -1) {
            headerBytes++;
            if (headerBytes > maxHeaderBytes) {
                throw new HttpIOException("HTTP header exceeds "
                        + maxHeaderBytes + " bytes");
            }
            lineBuf.write(ch);
            if (lineBuf.size() > maxLineBytes) {
                throw new HttpIOException("HTTP header line exceeds "
                        + maxLineBytes + " bytes");
            }

            if (ch != '\n') { // go next read() immediately for performance.
                continue;
            }

            // interpret as ISO-8859-1, because it can preserve
            // original byte sequence in Java string.
            String line = lineBuf.toString(ISO_8859_1);

            if (startLine == null) {
                // IE sends additional CRLF after POST request.
                // see http://support.microsoft.com/kb/823099/
                // see http://httpd.apache.org/docs/1.3/misc/known_client_problems.html#trailing-crlf
                if (line.equals("\r\n")) {
                    lineBuf.reset();
                    continue;
                }

                startLine = line;
            } else {
                if (line.equals("\r\n")) {
                    break;
                }

                Matcher m = MESSAGE_HEADER_PATTERN.matcher(line);
                if (m.find()
                        && FIELD_NAME_PATTERN.matcher(m.group(1)).matches()) {
                    messageHeaders.add(m.group(1), m.group(2));
                } else {
                    throw new HttpIOException("invalid HTTP header field");
                }
            }

            lineBuf.reset();
        }

        if (startLine == null || ch == -1) {
            String header = createIncompleteHeaderString(lineBuf);
            throw new HttpIOException("premature end of header: " + header);
        }
    }

    private static int positiveIntegerProperty(
            String name, int defaultValue) {
        int configured = Integer.getInteger(name, defaultValue);
        return configured > 0 ? configured : defaultValue;
    }

    private String createIncompleteHeaderString(ByteArrayOutputStream lineBuf)
            throws UnsupportedEncodingException {
        String header = messageHeaders.toString();

        if (startLine != null) {
            header = startLine + header;
        }

        if (lineBuf.size() > 0) {
            header = header + lineBuf.toString(ISO_8859_1);
        }

        return header;
    }

    /**
     * ヘッダ全体を文字列として返す。
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(startLine);
        result.append(messageHeaders.toString());
        result.append("\r\n");

        return result.toString();
    }

    /**
     * ヘッダ全体をバイトシーケンスとして返す。
     */
    public byte[] getBytes() {
        try {
            return toString().getBytes(ISO_8859_1);
        } catch (UnsupportedEncodingException e) {
            // never happen
            throw new IllegalStateException("cannot use " + ISO_8859_1);
        }
    }

    /**
     * Content-Lengthの値を返す。
     *
     * @return Content-Lengthの値。存在しなかった場合は-1。
     */
    public long getContentLength() {
        String value = getMessageHeader(CONTENT_LENGTH);
        if (value == null) {
            return -1;
        } else {
            return Long.parseLong(value);
        }
    }

    /**
     * Content-Lengthの値を変更する。
     *
     * @param contentLength
     */
    public void setContentLength(long contentLength) {
        messageHeaders.put(CONTENT_LENGTH, String.valueOf(contentLength));
    }

    /**
     * [nl] Last-Modifiedの値をエポックからの累積時間(ミリ秒)で返す。
     * @return Last-Modifiedの値。存在しなかった場合は-1
     */
    public long getLastModified() {
        String value = messageHeaders.get(LAST_MODIFIED);
        return parseDateString(value);
    }

    /**
     * [nl] Last-Modifiedの値を設定する。
     * @param time 設定するエポックからの累積時間(ミリ秒)。負の値なら設定しない
     */
    public void setLastModified(long time) {
        if (time < 0) {
            return;
        }

        DateFormat df = getDateFormatForDateField();
        messageHeaders.put(LAST_MODIFIED, df.format(new Date(time)));
    }

    /**
     * メッセージヘッダを返す。keyの大文字小文字は同一視する。
     * 同じkeyの複数のメッセージヘッダがある場合は最後のものを返す。
     * 存在しない場合はnull。
     *
     * @param key フィールド名
     * @return メッセージヘッダ。
     */
    public String getMessageHeader(String key) {
        return messageHeaders.get(key);
    }

    // 同じkeyの複数のメッセージヘッダの値をリスト形式で返す。
    public List<String> getMessageHeadersOfName(String key) {
        return messageHeaders.getAll(key);
    }

    /**
     * メッセージヘッダを指定された文字セットに基づいてデコードして返す。
     * keyの大文字小文字は同一視する。
     * 同じkeyの複数のメッセージヘッダがある場合は最後のものを返す。
     *
     * @param key
     * @param charsetName 文字セット名。存在しない文字セット名を指定した場合は
     * {@link HttpHeader#getMessageHeader(String)}と同じ動作。
     * @return メッセージヘッダ。
     */
    public String getMessageHeaderOnCharset(String key, String charsetName) {
        String encodedValue = getMessageHeader(key);
        return decodeString(encodedValue, charsetName);
    }

    /**
     * メッセージヘッダ全体を返す。
     *
     * @return メッセージヘッダ。
     */
    public HttpMessageHeaderHolder getMessageHeaders() {
        return messageHeaders;
    }

    /**
     * メッセージヘッダを設定する。既にあるヘッダを上書きする。
     *
    * @param key フィールド名
    * @param value フィールド値
     */
    public void setMessageHeader(String key, String value) {
        messageHeaders.put(key, value);
    }

    /**
    * メッセージヘッダを指定された文字セットに基づいた
    * バイトシーケンスとして設定する。
    * 既にあるヘッダを上書きする。
    *
    * @param key フィールド名
    * @param value フィールド値
    * @param charsetName 文字セット名。存在しない文字セット名を指定した場合は
    * {@link HttpHeader#setMessageHeader(String, String)}と同じ動作。
    */
    public void setMessageHeaderOnCharset(String key, String value,
            String charsetName) {
        String encodedValue = encodeString(value, charsetName);

        setMessageHeader(key, encodedValue);
    }

    /**
     * メッセージヘッダを追加する。
     * @param key
     * @param value
     */
    public void addMessageHeader(String key, String value) {
        messageHeaders.add(key, value);
    }

    /**
     * メッセージヘッダを指定された文字セットに基づいた
     * バイトシーケンスとして追加する。
     *
     * @param key フィールド名
     * @param value フィールド値
     * @param charsetName 文字セット名。存在しない文字セット名を指定した場合は
     * {@link HttpHeader#addMessageHeader(String, String)}と同じ動作。
     */
    public void addMessageHeaderOnCharset(String key, String value,
            String charsetName) {
        String encodedValue = encodeString(value, charsetName);

        addMessageHeader(key, encodedValue);
    }

    public static String encodeString(String normalStr, String charsetName) { // [nl]
        if (charsetName == null) { // [nl]
            return normalStr;
        }

        String encodedStr;
        try {
            encodedStr =
                    new String(normalStr.getBytes(charsetName), ISO_8859_1);
        } catch (UnsupportedEncodingException e) {
            encodedStr = normalStr;
        }
        return encodedStr;
    }

    private static String decodeString(String encodedStr, String charsetName) { // [nl]
        if (charsetName == null) { // [nl]
            return encodedStr;
        }

        String normalStr;
        try {
            normalStr =
                    new String(encodedStr.getBytes(ISO_8859_1), charsetName);
        } catch (UnsupportedEncodingException e) {
            normalStr = encodedStr;
        }

        return normalStr;
    }

    /**
     * メッセージヘッダを削除する。
     *
     * @param key
     */
    public void removeMessageHeader(String key) {
        messageHeaders.remove(key);
    }

    protected String getStartLine() {
        return startLine;
    }

    final String getParsedStartLine() {
        return startLine;
    }

    protected void setStartLine(String startLine) {
        this.startLine = startLine;
    }

    /**
     * Hop-by-hop Headersを削除する。RFC2616で定義されているのは以下:
     *
     * <pre>
     *               - Connection (とそれに列挙されているもの)
     *               - Keep-Alive
     *               - Proxy-Authenticate
     *               - Proxy-Authorization
     *               - TE
     *               - Trailer
     *               - Transfer-Encoding
     *               - Upgrade
     * </pre>
     *
     * 標準ではないがProxy-Connectionも。
     */
    public void removeHopByHopHeaders() {
        removeConnectionAndRelated();

        removeMessageHeader("Keep-Alive");
        // ad hoc treaing for 407
        //removeMessageHeader("Proxy-Authenticate");
        //removeMessageHeader("Proxy-Authorization");
        removeMessageHeader("TE");
        removeMessageHeader("Trailer");
        removeMessageHeader("Transfer-Encoding");
        removeMessageHeader("Upgrade");
        removeMessageHeader("Proxy-Connection");
    }

    protected void removeConnectionAndRelated() {
        String connection = getMessageHeader(CONNECTION);
        if (connection == null) {
            return;
        }

        String[] tokens = MULTI_TOKEN_SPLIT.split(connection);

        for (String token : tokens) {
            removeMessageHeader(token);
        }

        removeMessageHeader(CONNECTION);
    }

    // [nl] nl内部でのみ使用するパラメータを設定(rc2.03)
    private Map<String, String> nlParams = new TreeMap<>();
    public String getParameter(String key) {
        return nlParams.get(key);
    }
    public Map<String, String> getParameters() {
        return nlParams;
    }
    public void setParameter(String key, String value) {
        nlParams.put(key, value);
    }
    // [nl] end

// キャッシュのダウンロード用にヘッダのエンコードを指定できるように
//  private String headerEncode = null;
    /**
     * [nl] NicoCache v0.45 のマージに伴い、このメソッドは機能しなくなりました。
     * {@link #setMessageHeaderOnCharset(String, String, String)} を使って
     * ヘッダ個別に文字セットを指定してください。
     */
    @Deprecated
    public void setHeaderEncode(String encode) {
//      headerEncode = encode;
        Logger.warning("HttpHeader#setHeaderEncode: obsolete method, do not work.");
    }
    /**
     * [nl] NicoCache v0.45 のマージに伴い、このメソッドは機能しなくなりました。
     * @return 常に "ISO-8859-1" を返す
     */
    @Deprecated
    public String getHeaderEncode() {
//      return headerEncode;
        Logger.warning("HttpHeader#getHeaderEncode: obsolete method, do not work.");
        return ISO_8859_1;
    }

    /**
     * [nl] キャッシュ制御ヘッダを付加する。
     * @param maxAge キャッシュの有効秒数、0ならキャッシュ無効
     */
    public void addCacheControlHeaders(int maxAge) {
        if (maxAge > 0) {
            setMessageHeader("Cache-Control", "max-age=" + maxAge);
            setMessageHeader("Expires", HttpHeader.getDateString(
                    System.currentTimeMillis() + (long)maxAge * 1000L));
        } else if (maxAge == 0) {
            setMessageHeader("Cache-Control", "no-cache");
            setMessageHeader("Expires", "Thu, 01 Dec 1994 16:00:00 GMT");
            setMessageHeader("Pragma", "no-cache");
        }
    }
}
