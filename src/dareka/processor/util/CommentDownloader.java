package dareka.processor.util;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import dareka.common.CloseUtil;
import dareka.common.FileUtil;
import dareka.common.Logger;
import dareka.common.json.*;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.URLResource;
import dareka.processor.impl.Cache;
import dareka.processor.impl.NicoApiUtil;
import dareka.processor.impl.WatchVars;

public class CommentDownloader {

    private static void debug(String message) {
        Logger.debug("CommentDownloader: " + message);
    }

    // 該当するリクエストの際に呼ばれる
    @Deprecated
    public static Resource getResource(String smid,
            HttpRequestHeader requestHeader, Socket browser) throws IOException {
        return getResource(smid, "0", requestHeader, browser);
    }

    // 原宿時代の遺物
    @Deprecated
    public static Resource getResource(String smid, String fork,
            HttpRequestHeader requestHeader, Socket browser) throws IOException {
        String content = NicoApiUtil.getFlapiContent(
                "getflv?v=".concat(smid), requestHeader, null);
        Map<String, String> map = NicoApiUtil.query2map(content);
        String ms = map.get("ms");
        String threadId = map.get("thread_id");
        String userId = map.get("user_id");
        boolean isError = map.containsKey("error");

        if (isError || ms == null || threadId == null || userId == null)
            return StringResource.getNotFound();

        String extra = getExtraParams(map, requestHeader, false);
        String postData = "<thread no_compress=\"0\" fork=\"" + fork + "\" user_id=\"" + userId
                + "\" " + "when=\"0\" waybackkey=\"0\" res_from=\"-1000\" "
                + "version=\"20061206\" thread=\"" + threadId + "\"" + extra + " />";
        debug("postData=" + postData);

        URLResource r = NicoApiUtil.getURLResource(ms, requestHeader, postData);
        setContentFilename(r, ("1".equals(fork) ? "_" : "") + smid);
        return r;
    }

    private static void setContentFilename(URLResource r, String filename
            ) throws IOException {
        // 既に受信済みのヘッダに対して設定する
        r.getResponseHeader(null, null).setMessageHeader("Content-Disposition",
                "attachment; filename=\"" + filename + ".xml");
    }

    /**
     * GINZA方式でコメントを取得するための URLResource を返す。
     * 通常はIDを指定して内部で getflv を呼び出して情報を取得するが、
     * 既に getflv の内容を取得している場合はそちらから情報を取得する。
     *
     * @param target 取得対象のID(動画ID or スレッドID)、もしくは getflv の取得内容
     * @param hasOwnerThread 投稿者コメントがあるか？
     * @param requestHeader リクエストヘッダ
     * @return コメントの URLResource (レスポンスヘッダ受信済み)、失敗した場合は null
     * @throws IOException コメント取得中にエラーが発生した
     * @see #store(File, byte[], String, int)
     * @since NicoCache_nl+111111mod
     */
    public static URLResource getURLResource(String target, boolean hasOwnerThread,
            HttpRequestHeader requestHeader) throws IOException {
        Map<String, String> map = getflv(target, requestHeader);
        if (map == null) {
            return null;
        }
        int max = 1000, end = 1000;
        try {
            int length = Integer.parseInt(map.get("l"));
            if (length < 60) {
                max = 100;
            } else if (length < 300) {
                max = 250;
            } else if (length < 600) {
                max = 500;
            }
            end =  length / 60 + 1;
        } catch (NumberFormatException e) {
            Logger.error(e);
        }
        // 旧XMLコメントAPIではoptional_thread_idが指定済みなら、
        // owner threadを重ねて要求しない。現行nvcommentは別Processorで扱う。

        String thread_id = map.get("thread_id");
        String user_id = map.get("user_id");
        String needs_key = map.get("needs_key");
        String userkey = map.get("userkey");
        String extra = getExtraParams(map, requestHeader, true);
        String postData = "<packet>" +
            "<thread thread=\"" + thread_id + "\" version=\"20090904\""
                + (needs_key == null || !needs_key.equals("1") ? " userkey=\"" + userkey + "\"" : "")
                + " user_id=\"" + user_id + "\"" + extra + " nicoru=\"1\" with_global=\"1\"/>" +
            "<thread_leaves thread=\"" + thread_id + "\""
                + (needs_key == null || !needs_key.equals("1") ? " userkey=\"" + userkey + "\"" : "")
                + " user_id=\"" + user_id + "\"" + extra + " nicoru=\"1\">0-" + end + ":100," + max + "</thread_leaves>" +
        (!hasOwnerThread || map.containsKey("optional_thread_id") ? "" :
            "<thread thread=\"" + thread_id + "\" version=\"20061206\" " +
                "res_from=\"-1000\" fork=\"1\" click_revision=\"-1\"/>"
        ) + "</packet>";
        debug("postData=" + postData);

        requestHeader.setMessageHeader("Content-Type", "text/xml");

        URLResource r = NicoApiUtil.getURLResource(map.get("ms"), requestHeader, postData);
        setContentFilename(r, target.matches("\\w{2}\\d+") ? target : thread_id);
        return r;
    }

    /**
     * HTML5のWatchVarsからXMLでコメントを取得するための URLResource を返す。
     *
     * @param target 取得対象のID(動画ID or スレッドID)
     * @param wv 対象ページのWatchVars
     * @param requestHeader リクエストヘッダ
     * @return コメントの URLResource (レスポンスヘッダ受信済み)、失敗した場合は null
     * @throws IOException コメント取得中にエラーが発生した
     */
    public static URLResource getURLResource(String target, WatchVars wv,
            HttpRequestHeader requestHeader) throws IOException {
        if (wv == null) {
            return null;
        }
        int length = -1;
        String user_id;
        String userkey;
        ArrayList<JsonObject> threads = new ArrayList<>();
        String owner_thread_id = null;
        boolean hasOwnerThread;
        JsonObject json = wv.getJsonObject();
        String serverUrl;
        switch (wv.getType()) {
        case Html5: {
            user_id = json.get("viewer", "id").toString();
            userkey = json.getString("comment", "keys", "userKey");

            JsonArray threadsJson = json.getArray("comment", "threads");
            if (threadsJson == null) {
                Logger.warning("comment.threads not found.");
                return null;
            }
            for (JsonValue entry : threadsJson.getList()) {
                JsonObject thread = (JsonObject)entry;
                if (thread.getBoolean("isOwnerThread")) {
                    owner_thread_id = thread.get("id").toString();
                } else {
                    threads.add(thread);
                }
            }

            JsonValue duration = json.get("video", "duration");
            if (duration instanceof JsonNumber) {
                length = (int)((JsonNumber)duration).getLong();
            } else {
                Logger.warning("No duration");
            }

            hasOwnerThread = owner_thread_id != null;

            serverUrl = json.getString("comment", "server", "url");
            break;
        }
        default: {
            Logger.warning(wv.getType().name() + "はサポートされていません。HTML5でwatchページをリロードしてください。");
            return null;
        }
        }
        int max = 1000, end = 1000;
        if (length >= 0) {
            if (length < 60) {
                max = 100;
            } else if (length < 300) {
                max = 250;
            } else if (length < 600) {
                max = 500;
            }
            end =  length / 60 + 1;
        }

        StringBuilder postData = new StringBuilder();
        postData.append("<packet>");
        for (JsonObject thread : threads) {
            String thread_id = thread.get("id").toString();
            boolean isThreadkeyRequired = thread.getBoolean("isThreadkeyRequired");
            String threadkey_params = "";
            if (isThreadkeyRequired) {
                threadkey_params = getThreadkeyParams(thread_id, requestHeader);
            }
            postData.append(
                    "<thread thread=\"" + thread_id + "\" version=\"20090904\""
                            + (!isThreadkeyRequired ? " userkey=\"" + userkey + "\"" : "")
                            + " user_id=\"" + user_id + "\" score=\"1\"" + threadkey_params + " nicoru=\"1\" with_global=\"1\"/>"
            );
            postData.append(
                    "<thread_leaves thread=\"" + thread_id + "\""
                            + (!isThreadkeyRequired ? " userkey=\"" + userkey + "\"" : "")
                            + " user_id=\"" + user_id + "\" score=\"1\"" + threadkey_params + " nicoru=\"1\">0-" + end + ":100," + max + "</thread_leaves>"
            );
        }
        if (hasOwnerThread && owner_thread_id != null) {
            postData.append(
                    "<thread thread=\"" + owner_thread_id + "\" version=\"20061206\" " +
                            "res_from=\"-1000\" fork=\"1\" click_revision=\"-1\"/>"
            );
        }
        postData.append("</packet>");
        debug("postData=" + postData.toString());

        requestHeader.setMessageHeader("Content-Type", "text/xml");

        URLResource r = NicoApiUtil.getURLResource(serverUrl, requestHeader, postData.toString());
        setContentFilename(r, target);
        return r;
    }

    /**
     * コメントを指定ディレクトリに保存する。
     * 最も新しい保存済みコメントから minInterval 秒以内であれば保存しない。
     *
     * @param dir 保存先ディレクトリ、null を指定した場合は cacheCommentExtension
     * 互換モードで保存 (cacheFolder 直下の "#comment" ディレクトリ)
     * @param bcontent 保存するコメント内容
     * @param theBeginning コメント先頭文字列、事前に取得していない場合は null
     * @param minInterval 最小の保存間隔(秒)、0なら常に保存する
     * @return 保存したコメントファイル、失敗した場合は null
     * @see #getURLResource(String, boolean, HttpRequestHeader)
     * @see #getTheBeginning(byte[])
     * @since NicoCache_nl+111111mod
     */
    public static File store(File dir, byte[] bcontent, String theBeginning,
            int minInterval) {
        boolean ccCompat = false;
        if (dir == null) {
            dir = getCacheCommentDir();
            ccCompat = true;
        }
        if (theBeginning == null) {
            theBeginning = getTheBeginning(bcontent);
        }
        String baseName = getBaseName(dir, theBeginning, minInterval, ccCompat);
        if (baseName != null) {
            if (!dir.exists()) dir.mkdirs();
            OutputStream out = null;
            try {
                File commFile = new File(dir, baseName +
                        (isGzipped(bcontent) ? ".xml.gz" : ".xml"));
                out = new FileOutputStream(commFile);
                out.write(bcontent);
                return commFile;
            } catch (IOException e) {
                Logger.error(e);
            } finally {
                CloseUtil.close(out);
            }
        }
        return null;
    }

    private static File getCacheCommentDir() {
        return new File(Cache.getCacheDir(), "#comment");
    }

    /**
     * 対象のコメントを取得して指定ディレクトリに無条件で保存する。
     *
     * このメソッドは簡易取得のためのヘルパーメソッドなので、条件付き保存したい場合は
     * {@link #getURLResource(String, boolean, HttpRequestHeader) getURLResource}
     * を使って取得してから条件判断した後に、
     * {@link #store(File, byte[], String, int) store}
     * を使って保存する。
     *
     * @param dir 保存先ディレクトリ、null なら cacheCommentExtension 互換
     * @param target 取得対象のID(動画ID or スレッドID)、もしくは getflv の取得内容
     * @param hasOwnerThread 投稿者コメントがあるか？
     * @param requestHeader リクエストヘッダ
     * @return 保存したコメントファイル、失敗した場合は null
     * @since NicoCache_nl+111111mod
     */
    public static File download(File dir, String target, boolean hasOwnerThread,
            HttpRequestHeader requestHeader) {
        try {
            requestHeader.setMessageHeader(HttpHeader.ACCEPT_ENCODING,
                    HttpHeader.CONTENT_ENCODING_GZIP);
            URLResource r = getURLResource(target, hasOwnerThread, requestHeader);
            if (r != null) {
                return store(dir, r.getResponseBody(dir == null), null, 0);
            }
        } catch (IOException e) {
            Logger.error(e);
        }
        return null;
    }

    /**
     * コメントの主要な情報が含まれている、最初の chat タグ直前までの文字列を展開して返す。
     * gzip 圧縮されている場合は、chat タグが見つかるまで順次展開しながら検索するので、
     * コメントの大部分を占める chat タグ部分の gzip 展開は行わない。
     *
     * @param bcontent メッセージ鯖から返されたコンテンツ本体
     * @return 最初の chat タグ直前までの文字列(chat タグが無い場合は全ての文字列)、
     * 何らかのエラーが発生した場合は null
     */
    public static String getTheBeginning(byte[] bcontent) {
        try {
            InputStream in = new ByteArrayInputStream(bcontent);
            if (isGzipped(bcontent)) {
                in = new GZIPInputStream(in);
            }
            return readHeader(in);
        } catch (IOException e) {
            Logger.error(e);
        }
        return null;
    }

    /**
     * @param commentFile 取得対象のコメントファイル(gzip判定は拡張子のみ)
     * @see #getTheBeginning(byte[])
     * @since NicoCache_nl+111123mod
     */
    public static String getTheBeginning(File commentFile) {
        InputStream in = null;
        try {
            return readHeader(in = getInputStream(commentFile));
        } catch (IOException e) {
            Logger.error(e);
        } finally {
            CloseUtil.close(in);
        }
        return null;
    }

    private static boolean isGzipped(byte[] buf) {
        return buf != null && buf.length > 1 &&
               buf[0] == (byte) 0x1f && buf[1] == (byte) 0x8b;
    }

    private static InputStream getInputStream(File file) throws IOException {
        InputStream in =  new BufferedInputStream(new FileInputStream(file));
        if (file.getName().endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        return in;
    }

    private static String readHeader(InputStream in) {
        try {
            StringBuilder sb = new StringBuilder();
            InputStreamReader r = new InputStreamReader(in, "UTF-8");
            char[] breaker = "<chat ".toCharArray();
            int c, n = 0;
            while ((c = r.read()) != -1) {
                sb.append((char) c);
                if (breaker[n] != c) {
                    n = 0;
                } else if (++n == breaker.length) {
                    sb.setLength(sb.length() - breaker.length);
                    break;
                }
            }
            return sb.toString();
        } catch (IOException e) {
            Logger.error(e);
        }
        return null;
    }

    //公式動画(1000コメ)
    //<packet><thread thread="1313637197" version="20090904" user_id="1" threadkey="1314382908.dLa_KxOOAjv9yWas5BRCzjo0dQ0" force_184="1" scores="1" nicoru="1" with_global="1"/>
    //<thread_leaves thread="1313637197" user_id="1" threadkey="1314382908.dLa_KxOOAjv9yWas5BRCzjo0dQ0" force_184="1" scores="1" nicoru="1">0-25:100,1000</thread_leaves></packet>
    //一般動画(250コメ) 投稿者コメントあり
    //<packet><thread thread="1197027961" version="20061206" res_from="-1000" fork="1" click_revision="-1" scores="1"/>
    //<thread thread="1197027961" version="20090904" userkey="digits.hogehoge" user_id="1" scores="1" nicoru="1" with_global="1"/>
    //<thread_leaves thread="1197027961" userkey="digits.hogehoge" user_id="1" scores="1" nicoru="1">0-5:100,250</thread_leaves></packet>

    private static Map<String, String> getflv(String target,
            HttpRequestHeader requestHeader) {
        String content = target;
        if (target.matches("\\w{2}\\d+")) {
            content = NicoApiUtil.getFlapiContent(
                    "getflv", requestHeader, "v=" + target);
            if (content == null) {
                debug("no getflv: v=" + target);
                return null;
            }
        }
        Map<String, String> map = NicoApiUtil.query2map(content);
        if (map.containsKey("error") || !map.containsKey("ms") ||
                !map.containsKey("thread_id") || !map.containsKey("user_id")) {
            debug("getflv error: " + content);
            return null;
        }
        return map;
    }

    private static String getExtraParams(Map<String, String> getflv,
            HttpRequestHeader requestHeader, boolean scores) {
        String extra = "";
        String thread_id = getflv.get("thread_id");
        if (thread_id != null && getflv.containsKey("needs_key")) {
            extra += getThreadkeyParams(thread_id, requestHeader);
        }
        if (scores) {
            extra += " scores=\"1\""; // NG共有機能対応(111005)
        }
        return extra;
    }

    private static String getThreadkeyParams(String thread_id,
            HttpRequestHeader requestHeader) {
        String part = "";
        Map<String, String> getthreadkey = getThreadkey(thread_id, requestHeader);
        if (getthreadkey != null) {
            String threadKey = getthreadkey.get("threadkey");
            if (threadKey != null) {
                part += " threadkey=\"" + threadKey + "\"";
                if (getthreadkey.containsKey("force_184")) {
                    part += " force_184=\"1\"";
                }
            }
        }
        return part;
    }

    private static Map<String, String> getThreadkey(String thread_id,
            HttpRequestHeader requestHeader) {
        String content = NicoApiUtil.getFlapiContent(
                "getthreadkey?thread=" + thread_id, requestHeader, null);
        if (content != null) {
            return NicoApiUtil.query2map(content);
        } else {
            return null;
        }
    }



    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "<view_counter [^>]*id=\"([a-z]{2}\\d+)\"");
    private static final Pattern SERVER_TIME_PATTERN = Pattern.compile(
            "<thread [^>]*server_time=\"(\\d{10})\"");
    private static final Pattern COMMENT_FILENAME_PATTERN = Pattern.compile(
            "([a-z]{2}\\d+)_(\\d{1,10}|\\d{12}z)(\\.xml(?:|\\.gz))");

    private static String getBaseName(File dir, String theBeginning,
            int minInterval, boolean ccCompat) {
        if (dir == null || theBeginning == null) {
            return null;
        }
        Matcher m;
        if (!(m = VIDEO_ID_PATTERN.matcher(theBeginning)).find()) {
            return null;
        }
        String videoId = m.group(1);
        long time;
        if ((m = SERVER_TIME_PATTERN.matcher(theBeginning)).find()) {
            time = Long.parseLong(m.group(1));
        } else {
            time = System.currentTimeMillis() / 1000L;
        }
        if (dir.isDirectory() && minInterval > 0) {
            long latest = getLatestCommentFile(dir, videoId, null).time;
            if (latest + minInterval >= time) {
                debug("restInterval=" + (latest + minInterval - time));
                return null;
            }
        }
        if (ccCompat) {
            return videoId + "_" + new SimpleDateFormat(
                    "yyMMddHHmmss").format(new Date(time * 1000)) + "z";
        }
        return videoId + "_" + time;
    }

    /**
     * 指定ディレクトリからコメントファイルを検索する。
     *
     * @param dir 検索するディレクトリ、null なら cacheCommentExtension 互換
     * @param smid 検索するコメントの smid
     * @param suffix 一致するコメントの接尾辞、null なら最も新しいコメント
     * @return 見つかったコメントファイル、見つからなかった場合は null
     * @since NicoCache_nl+111111mod
     */
    public static File find(File dir, String smid, String suffix) {
        return getLatestCommentFile(dir, smid, suffix).file;
    }

    /**
     * コメントファイルを読み込み文字列で返す。指定のコメントファイルが
     * cacheCommentExtension 形式の場合、投稿者コメントがあれば末尾に追加する。
     *
     * @param commentFile コメントファイル
     * @return 読み込んだコメントファイルの文字列
     * @since NicoCache_nl+111111mod
     */
    public static String load(File commentFile) {
        InputStream in = null;
        try {
            in = getInputStream(commentFile);
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            if (FileUtil.copy(in, bout) <= 0) {
                return null;
            }
            String content = bout.toString("UTF-8");

            // cacheCommentExtension形式の投稿者コメントがあるか？
            String name = commentFile.getName();
            if (name.endsWith(".gz")) {
                name = name.substring(0, name.length() - 3);
            }
            if (name.endsWith("z.xml")) {
                File ownerCommentFile = new File(commentFile.getParentFile(),
                        name.replace("z.xml", "s.xml"));
                if (ownerCommentFile.isFile()) {
                    bout.reset();
                    if (FileUtil.copy(ownerCommentFile, bout) > 0) {
                        // 投稿者コメントを通常コメント末尾に追加(新プレ仕様)
                        String ownerContent = bout.toString("UTF-8");
                        int pos = ownerContent.indexOf("<thread ");
                        if (pos > 0) {
                            content = content.replace("</packet>",
                                    ownerContent.substring(pos));
                        }
                    }
                }
            }
            return content;
        } catch (IOException e) {
            Logger.error(e);
        } finally {
            CloseUtil.close(in);
        }
        return null;
    }

    private static class CommentFile {
        File file; long time;
    }

    private static CommentFile getLatestCommentFile(
            File dir, final String videoId, final String suffix) {
        if (dir == null) {
            dir = getCacheCommentDir();
        }
        CommentFile latest = new CommentFile();
        File[] found = dir.listFiles((dir1, name) -> {
            Matcher m = COMMENT_FILENAME_PATTERN.matcher(name);
            return m.matches() && videoId.equals(m.group(1)) &&
                    (suffix == null || suffix.equals(m.group(2)));
        });
        if (found == null || found.length == 0) {
            return latest;
        }
        for (File f : found) {
            Matcher m = COMMENT_FILENAME_PATTERN.matcher(f.getName());
            if (m.matches()) {
                String s = m.group(2); long t = 0L;
                if (s.length() > 10) {
                    try {
                        t = new SimpleDateFormat(
                                "yyMMddHHmmss'z'").parse(s).getTime() / 1000L;
                    } catch (ParseException e) {
                        Logger.error(e);
                    }
                } else {
                    t = Long.parseLong(s);
                }
                if (latest.time < t) {
                    latest.time = t; latest.file = f;
                }
            }
        }
        return latest;
    }

}
