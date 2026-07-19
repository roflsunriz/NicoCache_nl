package dareka.processor.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import dareka.common.Logger;
import dareka.common.Pair;
import dareka.processor.FetchUtil;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.URLResource;
import dareka.processor.impl.Cache;
import dareka.processor.impl.NLShared;

public class CommentSavingProcessor implements Processor {

    private final Executor executor;

    private static final String[] PROCESSOR_SUPPORTED_METHODS = new String[]{ "POST" };

    // - たぶんこれ以前はxml仕様.
    // - この実装はcacheCommentExternsionとは関係がない.
    // - この実装はdareka.processor.util.CommentDownloaderとは関係がない.
    // - jsonでPOST要求され、jsonで応答される.
    // - httpsでしか要求されないからhttpを考慮しない.
    // - nvはnicovideo(たぶん), threadは掲示板用語のスレッドと同じ意味.
    // - (XML時代もv1じゃなかったか)
    // - thread idは一つの動画に一つ.
    // - 要求jsonパラメーターlanguageによって、言語ごとのコメントリストが応答される
    //   (既知は ja-jp, zh-tw, en-us).
    // - 稼働時期: 開始:不明, 確認:2024-11-13, end:不明.
    private static final Pattern NVCOMMENT_V1_URL_PATTERN = Pattern.compile(
        "^"
        + Pattern.quote("https://public.nvcomment.nicovideo.jp/v1/threads")
        + "$");

    @Override
    public String[] getSupportedMethods() {
        return PROCESSOR_SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return NVCOMMENT_V1_URL_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    public CommentSavingProcessor(Executor executor) {
        this.executor = executor;
    }

    private boolean isCommentSavingEnabled() {
        // - 設定値変更を想定して動作ごとに毎回判定する.

        // - この設定値はcacheCommentExtensionが使っていたもの.
        // - cacheCommentExtensionにおいては"all", "one", 数値が有効だった.
        // - この実装ではそれらの値は"true"と等価.
        String val = System.getProperty("autoCacheComment", "false");

        if (Boolean.valueOf(val)) {
            return true;
        };

        // - cacheCommentExtensionの有効値をtrueとして扱う.
        if (val == "one") {
            return true;
        }
        else if (val == "all"){
            return true;
        } else if (val.matches("[0-9]+")) {
            return true;
        };

        return false;
    };

    public Resource onRequest(HttpRequestHeader requestHeader, Socket requestBody)
        throws IOException {

        if (!isCommentSavingEnabled()) {
            return null;
        }

        InputStream requestBodyInputStream = requestBody.getInputStream();

        // 要求jsonは450bytes前後.
        byte[] buffer = new byte[1024];
        int readCount = requestBodyInputStream.read(buffer, 0, 1024);
        requestBodyInputStream.reset();

        String json = new String(buffer, 0, readCount, StandardCharsets.UTF_8);
        CommentInfo info = new CommentInfo();
        info.language = getLanguageFromNV1(json);
        info.threadId = getIdFromNV1(json);
        info.smid = NLShared.INSTANCE.thread2smid(info.threadId);

        if (info.language == null && info.threadId == null && info.smid == null) {
            return null;
        };

        return processResponse(requestHeader, requestBody, info);
    }

    private Resource processResponse
    (HttpRequestHeader requestHeader, Socket requestBody, CommentInfo info) {

        Pair<URLResource, byte[]> response;
        try {
            response = FetchUtil.fetchBinaryContent(
                requestHeader, requestBody.getInputStream());
        }
        catch(IOException e) {
            Logger.info("nvcomment通信エラー: " + info.smid);
            return null;
        };

        info.responseBody = response.second;

        // - 通信同期的に行なう作業ではないから非同期でsaveNVCommentへ.
        executor.execute(info);

        return response.first;
    };

    private static String getLanguageFromNV1(String nv1ReqJson) {
        return substringBetween(nv1ReqJson, "\"language\":\"", "\"");
    }

    private static String getIdFromNV1(String nv1ReqJson) {
        return substringBetween(nv1ReqJson, "\"id\":\"", "\"");
    }

    // substringBetween("123xxx456yyy789", "xxx", "yyy") => "456".
    private static String substringBetween(String str, String left, String right) {
        int p1 = str.indexOf(left);
        if (p1 < 0) {
            return null;
        };
        int p2 = p1 + left.length();
        int p3 = str.indexOf(right, p2);
        if (p3 < 0) {
            return null;
        };
        return str.substring(p2, p3);
    }
}

// - 変数まとめのための構造体.
class CommentInfo implements Runnable {
    public String language = null;
    public String threadId = null; // comment thread id.
    public String smid = null;
    public byte[] responseBody = null;

    @Override
    public void run() {
        saveNVComment();
    };

    private void saveNVComment() {
        // String json = new String(responseBody, StandardCharsets.UTF_8);
        File dir = new File(Cache.getCacheDir(), smid + ".data");
        if (!dir.exists()) {
            if (!dir.mkdir()) {
                Logger.info("(csp)error: mkdir failed: " + dir);
                return;
            };
        };
        File file = new File(dir, "comment.0." + language + ".json");

        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(responseBody);
        }
        catch (IOException e) {
            Logger.info("(csp)write error: " + smid);
        };
    }
};
