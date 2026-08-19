package dareka.processor.impl;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.URLResource;

/** 視聴ページを取得し、コメント取得に必要な現行WatchVarsを生成する。 */
final class NicoWatchPageFetcher {
    private static final String DEFAULT_ENDPOINT =
            "https://www.nicovideo.jp/watch/";
    private static final int MAX_WATCH_PAGE_BYTES = 4 * 1024 * 1024;

    private NicoWatchPageFetcher() {
    }

    static WatchVars fetch(String videoId, String userAgent) throws IOException {
        Endpoint endpoint = resolveEndpoint();
        String url = endpoint.baseUrl + videoId;
        HttpRequestHeader upstreamRequest = newRequestHeader(url, userAgent);
        String cookie = NicoSessionCookieStore.current();
        if (cookie != null) {
            upstreamRequest.setMessageHeader("Cookie", cookie);
        }

        URLResource resource = new URLResource(url);
        if (endpoint.direct) {
            resource.setProxy("", 0);
        }
        // Cookieを別ホストへ持ち越さないため、リダイレクトは追跡しない。
        resource.setFollowRedirects(false);
        HttpResponseHeader response = resource.getResponseHeader(
                null, upstreamRequest);
        if (response == null) {
            throw new IOException("watch page returned no response");
        }
        int status = response.getStatusCode();
        if (status / 100 == 4) {
            return null;
        }
        if (status / 100 != 2) {
            throw new IOException("watch page returned status " + status);
        }
        long contentLength = response.getContentLength();
        if (contentLength > MAX_WATCH_PAGE_BYTES) {
            throw new IOException("watch page body is too large");
        }

        byte[] body = resource.getResponseBody();
        if (body == null || body.length == 0
                || body.length > MAX_WATCH_PAGE_BYTES) {
            throw new IOException("invalid watch page body size");
        }

        WatchVars watchVars;
        try {
            watchVars = WatchVars.get(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw new IOException("invalid watch page response", error);
        }
        if (watchVars == null || !videoId.equals(watchVars.getVideoId())) {
            return null;
        }
        return watchVars;
    }

    private static HttpRequestHeader newRequestHeader(String url,
            String userAgent) throws IOException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException error) {
            throw new IOException("invalid watch page URL", error);
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            throw new IOException("watch page URL has no authority");
        }
        HttpRequestHeader request = new HttpRequestHeader(
                "GET " + url + " HTTP/1.1\r\n"
                + "Host: " + authority + "\r\n"
                + "Accept: text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n\r\n");
        if (userAgent != null && !userAgent.isBlank()) {
            request.setMessageHeader("User-Agent", userAgent);
        }
        return request;
    }

    private static Endpoint resolveEndpoint() throws IOException {
        String configured = System.getProperty(
                "commentWatchPageEndpoint", "").trim();
        if (configured.isEmpty()) {
            return new Endpoint(DEFAULT_ENDPOINT, false);
        }
        URI uri;
        try {
            uri = new URI(configured);
        } catch (URISyntaxException error) {
            throw new IOException("invalid comment watch page endpoint", error);
        }
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!loopback || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !configured.endsWith("/")) {
            throw new IOException("invalid comment watch page endpoint");
        }
        return new Endpoint(configured, true);
    }

    private static final class Endpoint {
        private final String baseUrl;
        private final boolean direct;

        private Endpoint(String baseUrl, boolean direct) {
            this.baseUrl = baseUrl;
            this.direct = direct;
        }
    }
}
