package dareka.processor;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.CloseUtil;
import dareka.common.Config;
import dareka.common.ConfigObserver;
import dareka.common.FileUtil;
import dareka.common.Logger;

/**
 *  [nl] ローカルファイルを扱うリソース
 * @since NicoCache_nl+101219mod
 */
public class LocalFileResource extends Resource implements ConfigObserver {
    private File file;
    private long start, end, decodedLength;
    private String statusline;
    private boolean clientCanKeepAlive;
    private boolean needsSendingBody, needsDecodingBody;
    private boolean setLastModified = true;
    private HttpResponseHeader responseHeader;

    private static final Map<String, String> mimeTypes =
         Collections.synchronizedMap(new LinkedHashMap<>());
    private static File mimeTypesFile;
    private static volatile long mimeTypesLastModified;

    static {
        Config.addObserver(new LocalFileResource());
    }
    private LocalFileResource() {}

    /**
     * ローカルファイルを扱うリソースのインスタンスを生成する。
     *
     * @param file ローカルファイル
     * @throws IOException 入出力エラーが発生した
     */
    public LocalFileResource(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (Boolean.getBoolean("localPathCheck")) {
            // カレントディレクトリを含まないパスは禁止
            String dataRoot = System.getProperty("nicocache.dataRoot");
            File workingRoot = dataRoot == null || dataRoot.isBlank()
                    ? new File(".")
                    : new File(dataRoot);
            String cwd = workingRoot.getCanonicalPath() + File.separator;
            String path = file.getCanonicalPath();
            if (!path.startsWith(cwd)) {
                throw new SecurityException("Invalid local path: " + path);
            }
        }
        if (!file.exists()) {
            File gzFile = new File(file.getPath() + ".gz");
            if (gzFile.exists()) {
                file = gzFile;
            }
        }
        this.file = file;
        this.end = file.length() - 1L;
    }

    /**
     * file = new File(path)
     * @param path ローカルファイルのパス文字列
     * @see #LocalFileResource(File)
     */
    public LocalFileResource(String path) throws IOException {
        this(new File(path));
    }

    /**
     * @param setLastModified Last-Modifiedヘッダを付加するか？
     * @see #LocalFileResource(File)
     */
    public LocalFileResource(File file, boolean setLastModified)
            throws IOException {
        this(file);
        this.setLastModified = setLastModified;
    }

    public File getFile() {
        return file;
    }

    public long getLength() {
        if (needsDecodingBody) {
            return getDecodedLength();
        }
        return end - start + 1L;
    }

    /**
     * ファイルが転送可能か？(ファイルが存在して、かつ読み込み可能)
     *
     * @return ファイルが転送可能ならtrue
     */
    public boolean isValid() {
        return file.isFile() && file.canRead();
    }

    /**
     * レスポンスヘッダを取得する。
     *
     * @param requestHeader リクエストヘッダ(null ならデフォルトヘッダを用いる)
     * @param setMandatoryHeader trueなら必須ヘッダ(Content-Length, Keep-Alive)を付加する
     * @return 生成したレスポンスヘッダ
     * @throws IOException レスポンスヘッダの生成に失敗
     */
    public HttpResponseHeader getResponseHeader(HttpRequestHeader requestHeader,
            boolean setMandatoryHeader) throws IOException {
        needsSendingBody = needsDecodingBody = false;

        String contentEncoding = null;
        String path = file.getPath();
        if (requestHeader == null) {
            requestHeader = new HttpRequestHeader(HttpHeader.GET + " " +
                    "LOCAL/" + path + " HTTP/1.1\r\n\r\n");
        }
        if (path.endsWith(".gz")) {
            path = path.substring(0, path.length() - 3);
            contentEncoding = HttpHeader.CONTENT_ENCODING_GZIP;
            if (!requestHeader.isGzipAccepted()) {
                needsDecodingBody = true;
            }
        }

        if (isValid()) {
            statusline = "HTTP/1.1 200 OK";
            String method = requestHeader.getMethod();
            if (HttpHeader.GET.equalsIgnoreCase(method)) {
                if (checkIfModifiedSince(requestHeader)) {
                    statusline = "HTTP/1.1 304 Not Modified";
                } else if (checkIfPartialContent(requestHeader)){
                    needsSendingBody = true;
                    statusline = "HTTP/1.1 206 Partial Content";
                } else {
                    needsSendingBody = true;
                }
            } else if (HttpHeader.HEAD.equalsIgnoreCase(method)) {
                // do nothing.
            } else if (HttpHeader.POST.equalsIgnoreCase(method)) {
                statusline = "HTTP/1.1 405 Method Not Allowed";
            } else {
                statusline = "HTTP/1.1 501 Not Implemented";
            }
        } else {
            statusline = "HTTP/1.1 404 Not Found";
        }

        responseHeader = new HttpResponseHeader(statusline + "\r\n\r\n");
        if (contentEncoding != null) {
            responseHeader.setMessageHeader(HttpHeader.CONTENT_ENCODING,
                    contentEncoding);
        }
        String contentType = getMimeType(path);
        if (contentType != null) {
            responseHeader.setMessageHeader(HttpHeader.CONTENT_TYPE,
                    contentType);
        }
        clientCanKeepAlive = isClientCanKeepAlive(requestHeader);
        if (setMandatoryHeader) {
            doSetMandatoryResponseHeader(responseHeader);
        }
        return responseHeader;
    }

    /**
     * レスポンスボディを取得する。先にレスポンスヘッダを取得する必要がある。
     * レスポンスヘッダの生成結果によってはレスポンスボディが空の場合もある。
     *
     * @return レスポンスボディ(空の場合はサイズ0のバイト配列)
     */
    public byte[] getResponseBody() {
        InputStream in = null;
        try {
            if (needsSendingBody) {
                in = getDecodedInputStream();
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                if (FileUtil.copy(in, bout) > 0) {
                    return bout.toByteArray();
                }
            }
        } catch (IOException e) {
            Logger.error(e);
        } finally {
            CloseUtil.close(in);
        }
        return new byte[0];
    }

    private InputStream getDecodedInputStream() throws IOException {
        InputStream in = new FileInputStream(file);
        String contentEncoding = null;
        if (responseHeader != null) {
            contentEncoding = responseHeader.getMessageHeader(
                    HttpHeader.CONTENT_ENCODING);
        }
        return HttpUtil.getDecodedInputStream(in, contentEncoding);
    }

    private long getDecodedLength() {
        if (decodedLength == 0L) {
            InputStream in = null;
            try {
                in = getDecodedInputStream();
                while (in.read() != -1) {
                    decodedLength++;
                }
            } catch (IOException e) {
                Logger.error(e);
            } finally {
                CloseUtil.close(in);
            }
        }
        return decodedLength;
    }

    private InputStream getRangeBody() {
        FileInputStream fin = null;
        try {
            if (needsSendingBody) {
                fin = new FileInputStream(file);
                return new RangedInputStream(fin, start, getLength());
            }
        } catch (IOException e) {
            Logger.error(e);
            if (fin != null) {
                CloseUtil.close(fin);
            }
        }
        return new ByteArrayInputStream(new byte[0]);
    }

    private boolean checkIfModifiedSince(HttpRequestHeader requestHeader) {
        if (requestHeader != null) {
            long lm = file.lastModified();
            String ims = requestHeader.getMessageHeader(HttpHeader.IF_MODIFIED_SINCE);
            if (ims != null && (lm - HttpHeader.parseDateString(ims)) < 1000L) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "(?i)bytes\\s*=\\s*(\\d+)\\s*-\\s*(\\d+)?");

    private boolean checkIfPartialContent(HttpRequestHeader requestHeader) {
        if (requestHeader != null && !needsDecodingBody) {
            String range = requestHeader.getMessageHeader("Range");
            if (range != null) {
                Matcher m = RANGE_PATTERN.matcher(range);
                if (m.find()) {
                    start = Long.parseLong(m.group(1));
                    if (m.group(2) != null) {
                        long n = Long.parseLong(m.group(2));
                        if (start < n && n < end) {
                            end = n;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean endEnsuredTransferTo(Socket receiver,
            HttpRequestHeader requestHeader, Config config) throws IOException {
        OutputStream out = receiver.getOutputStream();
        HttpResponseHeader rh = getResponseHeader(requestHeader, false);

        execSendingHeaderSequence(out, rh);

        if (needsSendingBody) {
            String contentType = rh.getMessageHeader(HttpHeader.CONTENT_TYPE);
            boolean useRange = getLength() < file.length();
            InputStream in = null;
            try {
                StringBuilder sb = new StringBuilder(file.getPath());
                if (useRange) {
                    in = getRangeBody();
                    sb.append(String.format("[range=%d-%d]", start, end));
                } else if (needsDecodingBody) {
                    in = getDecodedInputStream();
                } else {
                    in = new FileInputStream(file);
                }
                sb.append(" ").append(contentType);
                Logger.debugWithThread(sb.toString());
                execSendingBodySequence(out, in, rh.getContentLength());
            } finally {
                CloseUtil.close(in);
            }
        }
        return clientCanKeepAlive;
    }

    @Override
    protected void doSetMandatoryResponseHeader(HttpResponseHeader responseHeader) {
        long contentLength = 0L;
        if (isValid()) {
            if (needsSendingBody) {
                contentLength = getLength();
            }
            if (setLastModified) {
                responseHeader.setMessageHeader(HttpHeader.LAST_MODIFIED,
                        HttpHeader.getDateString(file.lastModified()));
            }
        }
        responseHeader.setContentLength(contentLength);
        responseHeader.setMessageHeader(HttpHeader.DATE,
                HttpHeader.getDateString(System.currentTimeMillis()));

        if (responseHeader.getStatusCode() != 200) {
            responseHeader.removeMessageHeader("Cache-Control");
            responseHeader.removeMessageHeader("Expires");
            responseHeader.removeMessageHeader("Pragma");
        }
        if (responseHeader.getStatusCode() == 206) {
            responseHeader.setMessageHeader("Accept-Ranges", "bytes");
            responseHeader.setMessageHeader("Content-Range",
                    String.format("bytes %d-%d/%d", start, end, file.length()));
        }

        if (clientCanKeepAlive) {
            responseHeader.setMessageHeader(HttpHeader.CONNECTION,
                    HttpHeader.CONNECTION_KEEP_ALIVE);
        } else {
            responseHeader.setMessageHeader(HttpHeader.CONNECTION,
                    HttpHeader.CONNECTION_CLOSE);
        }
    }

    /**
     * ファイルの拡張子に対応する MimeType を取得する。
     * 大文字と小文字は区別はしない。
     *
     * @param path 取得対象のファイルのパス名
     * @return 対応するMimeType、存在しない場合はnull
     * @since NicoCache_nl+110219mod
     */
    public static String getMimeType(String path) {
        if (path == null || path.length() == 0) {
            return null;
        }
        loadMimeTypes();
        int pos = path.lastIndexOf('.');
        if (pos != -1) {
            String ext = path.substring(pos + 1).toLowerCase();
            return mimeTypes.get(ext);
        }
        return null;
    }

    /**
     * 拡張子(key)とMimeType(value)の読み取り専用マップを取得する。
     *
     * @return 拡張子とMimeTypeの対応マップ、存在しない場合は空マップ
     * @since NicoCache_nl+110219mod
     */
    public static Map<String, String> getMimeTypes() {
        loadMimeTypes();
        return Collections.unmodifiableMap(mimeTypes);
    }

    @Override
    public void update(Config config) {
        String mimeTypesPath = System.getProperty("mimeTypes", "");
        if (mimeTypesPath.length() > 0) {
            mimeTypesFile = new File(mimeTypesPath);
            if (!mimeTypesFile.exists()) {
                File defaultFile = new File(mimeTypesPath + ".default");
                if (defaultFile.exists()) {
                    mimeTypesFile = defaultFile;
                }
            }
        } else {
            mimeTypesFile = null;
            clearMimeTypes();
        }
    }

    private static void loadMimeTypes() {
        if (mimeTypesFile == null) {
            return;
        }
        long lm = mimeTypesFile.lastModified();
        if (mimeTypesLastModified < lm) {
            synchronized (mimeTypes) {
                if (mimeTypesLastModified == lm) {
                    return; // 他のスレッドで既にロード済み
                }
                Logger.debugWithThread("loading " + mimeTypesFile.getPath());
                BufferedReader br = null;
                try {
                    //br = new BufferedReader(new FileReader(mimeTypesFile));
                    br = new BufferedReader(new InputStreamReader(
                            new FileInputStream(mimeTypesFile), "UTF-8"));
                    parseMimeTypes(br);
                } catch (IOException e) {
                    Logger.error(e);
                } finally {
                    CloseUtil.close(br);
                }
                mimeTypesLastModified = lm;
            }
        } else if (lm == 0L) {
            clearMimeTypes();
        }
    }

    private static void clearMimeTypes() {
        mimeTypes.clear();
        mimeTypesLastModified = 0L;
    }

    private static final Pattern MIME_LINE_PATTERN = Pattern.compile(
            "^([^#\\r\\n\\t]+)\\t++(.+)$");

    private static void parseMimeTypes(BufferedReader br) throws IOException {
        Map<String, String> newMimeTypes = new LinkedHashMap<>();
        String line;
        while ((line = br.readLine()) != null) {
            Matcher m = MIME_LINE_PATTERN.matcher(line);
            if (!m.matches()) continue;
            String mimeType = m.group(1);
            for (String ext : m.group(2).split("\\s+")) {
                if (ext.startsWith(".")) ext = ext.substring(1);
                newMimeTypes.put(ext, mimeType);
            }
        }
        mimeTypes.clear();
        mimeTypes.putAll(newMimeTypes);
    }

}

class RangedInputStream extends FilterInputStream {

    private long rest;

    public RangedInputStream(InputStream in, long start, long length)
            throws IOException {
        super(in);
        this.rest = length;
        in.skip(start);
    }

    @Override
    public int available() throws IOException {
        int result = super.available();
        int ceil;
        if (rest > Integer.MAX_VALUE) {
            ceil = Integer.MAX_VALUE;
        } else {
            ceil = (int)rest;
        }
        return Math.min(result, ceil);
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public long skip(long n) throws IOException {
        if (this.rest < n) {
            n = this.rest;
        }
        long result = super.skip(n);
        this.rest -= result;
        return result;
    }

    @Override
    public int read() throws IOException {
        if (this.rest == 0) {
            return -1;
        }
        int result = super.read();
        if (result != -1) {
            this.rest -= 1;
        }
        return result;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (this.rest == 0) {
            return -1;
        }
        if (this.rest < len) {
            len = (int)this.rest;
        }
        int result = super.read(buf, off, len);
        if (result != -1)  {
            this.rest -= result;
        }
        return result;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }
}
