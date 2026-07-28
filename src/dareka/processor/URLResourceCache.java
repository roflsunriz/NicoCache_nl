package dareka.processor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.Logger;
import dareka.extensions.SystemEventListener;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.URLResource;
import dareka.processor.impl.NLEventSource;
import dareka.processor.impl.NLShared;

/**
 * [nl] URLResource とキーを関連付けてメモリ内に保持するクラス。
 *
 * 手軽に受信内容をキャッシュするための手段を提供する。
 * 実装方法については {@link dareka.processor.impl.GetThumbInfoProcessor} を参照のこと。
 */
public class URLResourceCache {

    private class LRUMap extends LinkedHashMap<String, URLResource> {
        private static final long serialVersionUID = 1L;
        public LRUMap() {
            super(maxEntries > 12 ? (int)(maxEntries * 1.4) : 16, 0.75f, true);
        }
        protected boolean removeEldestEntry(Map.Entry<String, URLResource> eldest) {
            boolean remove = size() > updateMaxEntries();
            if (remove) {
                debugMes("url cache removed", eldest.getKey(), eldest.getValue());
            }
            return remove;
        }
    }

    private static final int MAX_ENTRIES_DEFAULT = 10;
    private static final long EXPIRE_TIME_DEFAULT = 10 * 60 * 1000; // 10分
    private static final long ERROR_EXPIRE_TIME = 30 * 1000;
    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String AGE = "Age";
    private static final Pattern MAX_AGE_PATTERN = Pattern.compile(
            "(?i)(?:^|,)\\s*max-age\\s*=\\s*\"?(\\d+)\"?\\s*(?=,|$)");
    private static final NLShared NLSHARED = NLShared.getInstance();
    private int maxEntries;
    private long expireTime;
    private String maxEntriesConfigKey;
    private String expireTimeConfigKey;
    private Map<String, URLResource> resources;


    /**
     * 設定を固定で URLResourceCache を作成する。
     *
     * @param maxEntries キャッシュ内に保持する最大数
     * @param expireTime 生成されてから破棄するまでの時間(単位はミリ秒)
     * @see dareka.processor.URLResource
     * @see #expires()
     */
    public URLResourceCache(int maxEntries, long expireTime) {
        init(maxEntries, expireTime);
    }

    /**
     * 設定を変更可能な URLResourceCache を作成する。
     *
     * @param maxEntriesConfigKey maxEntries の値を取得するシステムプロパティ名
     * @param expireTimeConfigKey expireTime の値を取得するシステムプロパティ名(単位は秒)
     */
    public URLResourceCache(String maxEntriesConfigKey, String expireTimeConfigKey) {
        this.maxEntriesConfigKey = maxEntriesConfigKey;
        this.expireTimeConfigKey = expireTimeConfigKey;
        init(updateMaxEntries(), updateExpireTime());
    }

    private void init(int maxEntries, long expireTime) {
        this.maxEntries = maxEntries < 0 ? MAX_ENTRIES_DEFAULT : maxEntries;
        this.expireTime = expireTime < 0 ? EXPIRE_TIME_DEFAULT : expireTime;
        resources = Collections.synchronizedMap(new LRUMap());
    }

    private int updateMaxEntries() {
        if (maxEntriesConfigKey != null) {
            maxEntries = Integer.getInteger(maxEntriesConfigKey, -1);
            if (maxEntries < 0) maxEntries = MAX_ENTRIES_DEFAULT;
        }
        return maxEntries;
    }

    private long updateExpireTime() {
        if (expireTimeConfigKey != null) {
            expireTime = Long.getLong(expireTimeConfigKey, -1) * 1000;
            if (expireTime < 0) expireTime = EXPIRE_TIME_DEFAULT;
        }
        return expireTime;
    }

    /**
     * キャッシュから URLResource を取得する。
     *
     * @param key 取得する URLResource に対応するキー
     * @return 取得した URLResource、キャッシュに存在しなければ null
     */
    public URLResource get(String key) {
        URLResource r = resources.get(key);
        if (r != null) {
            synchronized (r) { // 受信完了するまでブロックされる
                if (remainTime(r) == 0) {
                    debugMes("url cache expired", key, r);
                    resources.remove(key);
                    r = null;
                } else {
                    debugMes("using url cache", key, r);
                }
            }
        }
        return r;
    }

    /**
     * キャッシュから URLResource を取得する。
     * キャッシュに存在しなければ指定 URL から受信して返す(GETのみ)。
     *
     * @param key 取得する URLResource に対応するキー
     * @param url キャッシュに存在しない場合に受信する URL
     * @return 取得した URLResource、受信できなければ null
     */
    public URLResource cacheAndGet(String key, String url) {
        return cacheAndGet(key, url, null, null);
    }

    /**
     * キャッシュから URLResource を取得する。
     * キャッシュに存在しなければ指定リクエストヘッダを元に受信して返す(POST対応)。
     *
     * @param key 取得する URLResource に対応するキー
     * @param receiverIn POST の場合の入力ストリーム
     * @param requestHeader リクエストヘッダ
     * @return 取得した URLResource、受信できなければ null
     */
    public URLResource cacheAndGet(String key,
            InputStream receiverIn, HttpRequestHeader requestHeader) {
        return cacheAndGet(key, null, receiverIn, requestHeader);
    }

    // URL末尾のパラメータが数字にマッチするパターン(再取得用)
    private static final Pattern PARAMS_REGET_PATTERN = Pattern.compile(
            "^(https?://.+)[\\?&]\\d+$");

    /**
     * キャッシュから Rewriter 処理した Resource を取得する。
     * キャッシュに存在しなければ指定リクエストヘッダを元に受信して返す(POST対応)。
     * URL 末尾のパラメータが数字で終わる場合、キャッシュを破棄して再取得する。
     *
     * @param key 取得する URLResource に対応するキー
     * @param receiverIn POST の場合の入力ストリーム
     * @param requestHeader リクエストヘッダ
     * @return Rewriter 処理した Resource、受信できなければ null
     * @throws IOException 入出力エラーが発生した場合
     */
    public Resource cacheAndRewrite(String key,
            InputStream receiverIn, HttpRequestHeader requestHeader) throws IOException {
        String uri = requestHeader.getURI();
        Matcher m = PARAMS_REGET_PATTERN.matcher(uri);
        if (m.matches()) {
            uri = m.group(1);
            remove(key); // キャッシュを破棄
        }
        URLResource r = cacheAndGet(key, uri, null, requestHeader);
        if (r != null) {
            HttpResponseHeader rh = r.getResponseHeader(null, null);
            if (rh.getStatusCode() != 200)
                return r; // 200以外ならRewriter処理せずそのまま返す
            byte[] original = r.getResponseBody();
            if (original != null && original.length > 0) {
                // Rewriterにはパラメータ込みで渡す
                byte[] replaced = dareka.Main.getRewriterProcessor().contentRewriter(
                        uri, original, requestHeader, rh);
                if (original != replaced) {
                    return StringResource.getRawResource(rh, replaced);
                }
            }
            return r;
        }
        return Resource.get(Resource.Type.URL, uri);
    }

    private URLResource cacheAndGet(String key, String url,
            InputStream receiverIn, HttpRequestHeader requestHeader) {
        URLResource r = get(key);
        if (r == null) {
            if (url == null && requestHeader != null) {
                url = requestHeader.getURI();
            }
            if (url == null) return null;
            try {
                r = new URLResource(url); // ロックオブジェクトを兼ねる
                synchronized (r) {
                    // とりあえずマップに登録して多重受信しないようにする
                    resources.put(key, r);

                    doCache(key, url, receiverIn, requestHeader, r);
                }
            } catch (IOException e) {
                resources.remove(key);
                r = null;
                Logger.error(e);
            }
        }
        return r;
    }

    /**
     * @throws IOException
     */
    private void doCache(String key, String url,
            InputStream receiverIn, HttpRequestHeader requestHeader,
            URLResource r) throws IOException {
        r.setFollowRedirects(true);
        if (null != requestHeader) {
            HttpHeaderUtil.adjustAcceptEncoding(requestHeader);
        };

        HttpResponseHeader responseHeader = r.getResponseHeader(
                receiverIn, requestHeader);
        if (responseHeader != null) {
            String date = responseHeader.getMessageHeader(HttpHeader.DATE);
            if (date == null || HttpHeader.parseDateString(date) < 0) {
                // ExpireのためにDateヘッダを付加する
                date = HttpHeader.getDateString(System.currentTimeMillis());
                responseHeader.setMessageHeader(HttpHeader.DATE, date);
            }
            r.getResponseBody();

            boolean retained = remainTime(r) > 0;
            debugMes(retained ? "url cached" : "url not cached", key, r);
            if (!retained) {
                // no-store/no-cache/max-age=0 の応答は呼出元には返すが保持しない。
                resources.remove(key, r);
            }

            if (retained && NLSHARED.countSystemEventListeners() > 0) {
                NLSHARED.notifySystemEvent(
                        SystemEventListener.URL_MEMCACHED,
                        new NLEventSource(url, requestHeader, r), false);
            }
        } else {
            resources.remove(key); // ヘッダが受信できなければ破棄
        }
    }

    /**
     * URLResource がキャッシュされているか？
     *
     * @param key URLResource に対応するキー
     * @return key に対応する URLResource がキャッシュされていれば true
     * @since NicoCache_nl+110706mod
     */
    public boolean isCached(String key) {
        return resources.containsKey(key);
    }

    /**
     * キャッシュから特定の URLResource を削除する。
     *
     * @param key 削除する URLResource に対応するキー
     */
    public void remove(String key) {
        debugMes("url cache removed", key, null);
        resources.remove(key);
    }

    /**
     * キャッシュに保持しているURLResourceの数を返す。
     *
     * @return 保持しているURLResourceの数
     */
    public int size() {
        return resources.size();
    }

    /**
     * キャッシュをクリアする。
     */
    public void clear() {
        resources.clear();
    }

    /**
     * キャッシュ内の全ての URLResource について、expireTime に基づいて古ければ
     * 破棄する。なお、expireTime はレスポンスが生成されてからの経過時間であり、
     * キャッシュしてからの経過時間では無いので注意すること。
     *
     */
    public synchronized void expires() {
        synchronized (resources) {
            Iterator<Map.Entry<String, URLResource>> entries =
                    resources.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, URLResource> e = entries.next();
                if (remainTime(e.getValue()) == 0) {
                    debugMes("url cache expired", e.getKey(), e.getValue());
                    entries.remove();
                }
            }
        }
    }

    private long remainTime(URLResource resource) {
        long configuredLifetime = updateExpireTime();
        if (configuredLifetime <= 0 || resource == null) {
            return 0;
        }
        HttpResponseHeader rh = getResponseHeader(resource);
        if (rh == null) {
            return 0;
        }

        List<String> cacheControls = rh.getMessageHeadersOfName(CACHE_CONTROL);
        if (hasCacheDirective(cacheControls, "no-store")
                || hasCacheDirective(cacheControls, "no-cache")) {
            return 0;
        }

        long responseTime = HttpHeader.parseDateString(
                rh.getMessageHeader(HttpHeader.DATE));
        if (responseTime < 0) {
            return 0;
        }

        long freshnessLifetime = configuredLifetime;
        if (rh.getStatusCode() != 200) {
            freshnessLifetime = Math.min(freshnessLifetime, ERROR_EXPIRE_TIME);
        }

        Long maxAgeMillis = getMaxAgeMillis(cacheControls);
        if (maxAgeMillis != null) {
            freshnessLifetime = Math.min(freshnessLifetime, maxAgeMillis);
        } else {
            String expires = rh.getMessageHeader(HttpHeader.EXPIRES);
            if (expires != null) {
                long expiresAt = HttpHeader.parseDateString(expires);
                if (expiresAt < 0) {
                    return 0;
                }
                freshnessLifetime = Math.min(
                        freshnessLifetime, Math.max(0, expiresAt - responseTime));
            }
        }

        long now = System.currentTimeMillis();
        long currentAge = Math.max(0, now - responseTime);
        String age = rh.getMessageHeader(AGE);
        if (age != null) {
            try {
                currentAge = Math.max(currentAge,
                        secondsToMillis(Long.parseLong(age.trim())));
            } catch (NumberFormatException e) {
                // 不正なAgeはDateから計算した経過時間だけを使う。
            }
        }
        return Math.max(0, freshnessLifetime - currentAge);
    }

    private static boolean hasCacheDirective(List<String> headerValues,
            String expectedDirective) {
        for (String headerValue : headerValues) {
            for (String directive : headerValue.split(",")) {
                String name = directive.trim();
                int equals = name.indexOf('=');
                if (equals >= 0) {
                    name = name.substring(0, equals).trim();
                }
                if (expectedDirective.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Long getMaxAgeMillis(List<String> headerValues) {
        for (String headerValue : headerValues) {
            Matcher matcher = MAX_AGE_PATTERN.matcher(headerValue);
            if (matcher.find()) {
                try {
                    return secondsToMillis(Long.parseLong(matcher.group(1)));
                } catch (NumberFormatException e) {
                    return 0L;
                }
            }
        }
        return null;
    }

    private static long secondsToMillis(long seconds) {
        if (seconds > Long.MAX_VALUE / 1000) {
            return Long.MAX_VALUE;
        }
        return seconds * 1000;
    }

    private HttpResponseHeader getResponseHeader(URLResource r) {
        if (r != null) {
            try {
                return r.getResponseHeader(null, null);
            } catch (IOException e) {}
        }
        return null;
    }

    private void debugMes(String mes, String key, URLResource r) {
        if (Boolean.getBoolean("dareka.debug")) {
            long remain = remainTime(r);
            Logger.debugWithThread(mes + ": key=" + key + " remain=" + remain);
        }
    }

}
