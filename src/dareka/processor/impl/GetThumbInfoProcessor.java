package dareka.processor.impl;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.URLResource;
import dareka.processor.util.GetThumbInfoUtil;

// getthumbinfoをメモリにキャッシュする
public class GetThumbInfoProcessor implements Processor {

    private static final String[] SUPPORTED_METHODS = new String[] { "GET" };
    private static final Pattern GETTHUMBINFO_URL_PATTERN = Pattern.compile(
            "^(https?://ext\\.nicovideo\\.jp/api/getthumbinfo/)(\\w{2}\\d+)(\\?nlFilter)?([\\?&]\\d+)?$");

    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return GETTHUMBINFO_URL_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {
        // Processorで受けるとRewriterが処理されなので自前で呼び出す
        RewriterProcessor rw = dareka.Main.getRewriterProcessor();
        String uri = requestHeader.getURI();
        Matcher m = GETTHUMBINFO_URL_PATTERN.matcher(uri);
        if (m.find()) {
            String smid = m.group(2);
            if (m.group(4) != null) {
                // 数字パラメータ付きならキャッシュを削除して再取得
                GetThumbInfoUtil.remove(smid);
            }
            URLResource r = GetThumbInfoUtil.getURLResource(smid);
            if (r != null) {
                HttpResponseHeader rh = r.getResponseHeader(null, null);
                if (rh.getStatusCode() != 200)
                    return r; // 200以外ならRewriter処理せずそのまま返す
                byte[] original = r.getResponseBody();
                if (original != null && original.length > 0) {
                    byte[] replaced = rw.contentRewriter(uri, original, requestHeader, rh);
                    if (original != replaced) {
                        return StringResource.getRawResource(rh, replaced);
                    } else {
                        // 書き換えられていなければURLResourceをそのまま返す
                        return r;
                    }
                }
            } else {
                // GetThumbInfoUtilで取得出来なければパラメータを除去して直接取得
                requestHeader.setURI(m.group(1) + m.group(2));
            }
        }
        return rw.onRequest(requestHeader, browser);
    }

    // 定期的に期限切れのキャッシュを消去してメモリを有効活用
    private final static ScheduledExecutorService scheduler;
    static {
        scheduler = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable,
                    "nicocache-getthumbinfo-expirer");
            thread.setDaemon(true);
            return thread;
        });
        long expire = Long.getLong("cacheGetThumbInfoExpire", 0);
        long period = expire / 3;
        if (period >= 10) {
            scheduler.scheduleAtFixedRate(() -> {
                GetThumbInfoUtil.expires();
            }, period, period, TimeUnit.SECONDS);
        }
    }

    static void shutdownScheduler() {
        scheduler.shutdownNow();
    }
}
