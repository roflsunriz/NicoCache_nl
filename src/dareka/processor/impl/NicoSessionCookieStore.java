package dareka.processor.impl;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import dareka.processor.HttpRequestHeader;

/**
 * ニコニコ動画への直近HTTPS要求でブラウザーが送ったCookieを一時保持する。
 *
 * <p>コメント書き出し時に同じ利用者の権限で視聴ページを取得するためのもので、
 * ファイルやログには保存しない。</p>
 */
final class NicoSessionCookieStore {
    private static final long MAX_COOKIE_LENGTH = 64L * 1024L;
    private static final long MAX_AGE_NANOS = Duration.ofHours(12).toNanos();

    private static final AtomicReference<Snapshot> latest =
            new AtomicReference<>();

    private NicoSessionCookieStore() {
    }

    static void capture(HttpRequestHeader request) {
        if (!"https".equalsIgnoreCase(request.getScheme())
                || !"www.nicovideo.jp".equalsIgnoreCase(request.getHost())) {
            return;
        }

        String cookie = request.getMessageHeader("Cookie");
        if (cookie == null || cookie.isBlank()
                || cookie.length() > MAX_COOKIE_LENGTH) {
            latest.set(null);
            return;
        }
        latest.set(new Snapshot(cookie, System.nanoTime() + MAX_AGE_NANOS));
    }

    static String current() {
        Snapshot snapshot = latest.get();
        if (snapshot == null) {
            return null;
        }
        if (System.nanoTime() - snapshot.expiresAtNanos >= 0) {
            latest.compareAndSet(snapshot, null);
            return null;
        }
        return snapshot.cookie;
    }

    private static final class Snapshot {
        private final String cookie;
        private final long expiresAtNanos;

        private Snapshot(String cookie, long expiresAtNanos) {
            this.cookie = cookie;
            this.expiresAtNanos = expiresAtNanos;
        }
    }
}
