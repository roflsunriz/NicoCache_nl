package dareka.processor;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.HttpIOException;

public class HttpRequestHeader extends HttpHeader {
    /**
     * Request-Line
     *
     * <pre>
     * Request-Line   = Method SP Request-URI SP HTTP-Version CRLF
     * </pre>
     */
    private static final Pattern PROXY_REQUEST_LINE_PATTERN = // [nl] host部にnullを許容する
            Pattern.compile("^([A-Z]+) ((?:(https?)://)?([^/:]+)?(?::(\\d+))?(/\\S*)?) (HTTP/1\\.[01])\r\n");
    private static final Pattern GZIP_ACCEPTED_PATTERN = // [nl]
            Pattern.compile("(^|,\\s*)gzip(\\s*,|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOSTPORT_PATTERN = Pattern.compile("([^:]++)(?::(\\d++))?");
    private String method;
    private String uri;
    private String scheme;
    private String host;
    private int port;
    private String path;
    private String version;

    @SuppressWarnings("this-escape")
    public HttpRequestHeader(InputStream source) throws IOException {
        super(source);
        // private init と final の解析用アクセサーだけを呼び出す。
        init(false, true);
    }

    @SuppressWarnings("this-escape")
    public HttpRequestHeader(InputStream source, boolean tlsLoopback) throws IOException {
        super(source);
        init(tlsLoopback, true);
    }

    @SuppressWarnings("this-escape")
    public HttpRequestHeader(String source) throws IOException {
        super(source);
        init(false, false);
    }

    private void init(
            boolean tlsLoopback, boolean validateIncomingRequest)
            throws HttpIOException {
        if (validateIncomingRequest) {
            validateFramingHeaders();
        }
        Matcher m = PROXY_REQUEST_LINE_PATTERN.matcher(getParsedStartLine());
        if (m.find()) {
            method = m.group(1);
            uri = m.group(2);
            scheme = m.group(3) == null ? "http" : m.group(3);
            host = m.group(4);
            port = m.group(5) == null ? "https".equals(scheme) ? 443 : 80
                    : Integer.parseInt(m.group(5));
            path = m.group(6) == null ? "" : m.group(6);
            version = m.group(7);
            if (validateIncomingRequest && "HTTP/1.1".equals(version)) {
                validateHostHeader();
            }

            if (host == null) {
                String hostport;
                if (tlsLoopback && (hostport = getMessageHeader("Host")) != null) {
                    // TLS MitM機能
                    if (hostport.isEmpty() || hostport.contains("@")
                            || hostport.contains("/") || !path.startsWith("/")) {
                        throw new HttpIOException(
                                "invalid tlsLooback request:\r\n" + super.toString());
                    }
                    scheme = "https";
                    uri = "https://" + hostport + path;
                    Matcher m2 = HOSTPORT_PATTERN.matcher(hostport);
                    if (!m2.matches()) {
                        throw new HttpIOException(
                                "invalid tlsLooback request:\r\n" + super.toString());
                    }
                    host = m2.group(1);
                    if (m2.group(2) != null) {
                        port = Integer.parseInt(m2.group(2));
                    } else {
                        port = 443;
                    }
                } else if (path.startsWith("/debug/")) {
                    host = "DEBUG";
                    uri = "http://" + host + path;
                } else if (Boolean.getBoolean("localFileServer")) {
                    // [nl] ローカルファイルサーバ機能
                    host = "LOCAL";
                    uri = "http://" + host + path;
                } else {
                    throw new HttpIOException(
                            "invalid proxy request:\r\n" + super.toString());
                }
            } else {
                // setProxyMyself()時に本来httpsだった場合
                String xnlParam = getMessageHeader(X_NICOCACHE_NL);
                if (xnlParam != null && xnlParam.contains(XNL_TLS)) {
                    scheme = "https";
                    uri = "https" + uri.substring(4);
                }
            }
        } else {
            throw new HttpIOException("invalid request:\r\n" + super.toString());
        }
    }

    private void validateFramingHeaders() throws HttpIOException {
        java.util.List<String> contentLengths =
                getMessageHeadersOfName(CONTENT_LENGTH);
        if (contentLengths == null) {
            contentLengths = java.util.Collections.emptyList();
        }
        if (contentLengths.size() > 1) {
            throw new HttpIOException(
                    "multiple Content-Length fields are not allowed");
        }
        String contentLength = contentLengths.isEmpty()
                ? null : contentLengths.get(0);
        if (contentLength != null) {
            if (!contentLength.matches("0|[1-9][0-9]*")) {
                throw new HttpIOException("invalid Content-Length");
            }
            try {
                Long.parseLong(contentLength);
            } catch (NumberFormatException error) {
                throw new HttpIOException("Content-Length is too large");
            }
        }
        if (getMessageHeader("Transfer-Encoding") != null) {
            throw new HttpIOException(
                    "Transfer-Encoding requests are not supported");
        }
    }

    private void validateHostHeader() throws HttpIOException {
        java.util.List<String> hosts = getMessageHeadersOfName("Host");
        if (hosts == null || hosts.size() != 1
                || hosts.get(0).isBlank()) {
            throw new HttpIOException(
                    "HTTP/1.1 requests require exactly one Host field");
        }
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
        updateStartLine();
    }

    public boolean isGetMethod() {return method.equals("GET");};
    public boolean isPostMethod() {return method.equals("POST");};
    public boolean isHeadMethod() {return method.equals("HEAD");};
    public boolean isPutMethod() {return method.equals("PUT");};
    public boolean isDeleteMethod() {return method.equals("DELETE");};
    public boolean isPatchMethod() {return method.equals("PATCH");};
    public boolean isOptionsMethod() {return method.equals("OPTIONS");};

    public String getURI() {
        // The return value is usually used by HttpURLConnection.
        // HttpURLConnection (more specifically, sun.net.NetworkClient) uses
        // default encoding to convert Java string to byte sequence.
        // Some UAs violate RFC, and send URI of non-ASCII.
        // To make these UAs work, it may be necessary to convert the encoding.
        return uri;
    }

    public void setURI(String uri) {
        this.uri = uri;
        updateStartLine();
        // [nl] 内部変数(pathとか)にも反映する
        try {
            init(false, false);
            updateHostHeader();
        } catch (HttpIOException e) {
            // ignore
        }
    }

    public String getScheme() {
        return scheme;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /**
     * HTTPヘッダのrequest-targetを返す.
     *
     * そのためpathだけでなくqueryを含んで返す可能性がある.
     */
    public String getPath() {
        return path;
    }

    /**
     * getPath()のpath部の最後の'/'以降の部分を返す.
     *
     * query部分である'?'以降を含まない.
     * pathが'/'で終わる場合は空文字列を返す.
     * 例: "/aa/bb/cc?q1=xx&q2=yy" => "cc"
     */
    public String getPathBasename() {
        int question = path.indexOf('?');
        if (0 <= question) {
            path = path.substring(0, question);
        };
        int slash = path.lastIndexOf('/');
        if (0 <= slash) {
            path = path.substring(slash + 1);
        };
        return path;
    };

    /**
     * getPath()で得られる値のうちquery以降を削除して返す.
     */
    public String getPathWithoutQuery() {
        int question = path.indexOf('?');
        if (0 <= question) {
            return path.substring(0, question);
        };
        return path;
    };

    /**
     * @deprecated As of 2026-06-28, replaced by {@link #getPathWithoutQuery()}
     */
    @Deprecated
    public String getPathWithoutSearch() {
        return getPathWithoutQuery();
    };

    /**
     * getPath()で得られる値のうちのquery部分を返す.
     *
     * 例: "/aa/bb" => null
     * 例: "/aa/bb/cc?p1=xx&p2=yy%20zz" => "p1=xx&p2=yy%20zz"
     *
     * @return: query文字列(最初の'?'を含まない),
     *          あるいはgetPath()が'?'を含まない場合はnull.
     */
    public String getQuery() {
        int question = path.indexOf('?');
        if (0 > question) {
            return null;
        };
        return path.substring(question+1);
    };

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
        updateStartLine();
    }

    public void replaceUriWithPath() {
        if (GET.equals(method) || POST.equals(method) ||
                PUT.equals(method) || DELETE.equals(method) || PATCH.equals(method)) {
            uri = path;
            updateStartLine();
        }
    }

    private void updateStartLine() {
        setStartLine(method + " " + uri + " " + version + "\r\n");
    }

    private void updateHostHeader() {
        if (host == null || scheme == null ||
                host.equals("DEBUG") || host.equals("LOCAL"))
            return;
        String hostvalue = host;
        if (scheme.equals("http") && port != 80 ||
                scheme.equals("https") && port != 443) {
            hostvalue = host + ":" + port;
        }
        setMessageHeader("Host", hostvalue);
    }

    /**
     * [nl] Accept-Encoding に gzip が含まれているか？
     * @return gzip が含まれていれば true
     * @since NicoCache_nl+111111mod
     */
    public boolean isGzipAccepted() {
        String acceptEncoding = getMessageHeader(ACCEPT_ENCODING);
        if (acceptEncoding == null) {
            return false;
        }
        return GZIP_ACCEPTED_PATTERN.matcher(acceptEncoding).find();
    }

}
