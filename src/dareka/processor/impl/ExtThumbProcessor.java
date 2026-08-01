package dareka.processor.impl;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.processor.HttpRequestHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.URLResourceCache;

// 外部サムネをメモリにキャッシュする
public class ExtThumbProcessor implements Processor {

    private static final String[] SUPPORTED_METHODS = new String[] { "GET" };
    private static final Pattern EXTTHUMB_URL_PATTERN = Pattern.compile(
            "^(https?:)//(?:ext(?:|\\.seiga)|live)\\.nicovideo\\.jp/" +
                "((?:thumb(?:|_[^/]+)|embed)/\\w{2}\\d+)(\\?\\w+)?$");

    private static final URLResourceCache resources =
        new URLResourceCache("cacheExtThumbMax", "cacheExtThumbExpire");

    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return EXTTHUMB_URL_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {
        String uri = requestHeader.getURI();
        Matcher m = EXTTHUMB_URL_PATTERN.matcher(uri);
        if (!m.matches()) {
            Resource.get(Resource.Type.URL, uri);
        }
        return resources.cacheAndRewrite(m.group(1) + m.group(2), null, requestHeader);
    }

    // 定期的に期限切れのキャッシュを消去してメモリを有効活用
    private final static ScheduledExecutorService scheduler;
    static {
        scheduler = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable,
                    "nicocache-extthumb-expirer");
            thread.setDaemon(true);
            return thread;
        });
        long expire = Long.getLong("cacheExtThumbExpire", 0);
        long period = expire / 3;
        if (period >= 10) {
            scheduler.scheduleAtFixedRate(() -> {
                resources.expires();
            }, period, period, TimeUnit.SECONDS);
        }
    }

    static void shutdownScheduler() {
        scheduler.shutdownNow();
    }
}
