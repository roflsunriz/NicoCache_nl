package functional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class FunctionalTestMain {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> FAILURES = new ArrayList<>();

    private final Path repository;
    private final Path sandbox;
    private final Path classes;
    private HttpServer upstream;
    private ExecutorService upstreamExecutor;
    private Process nicocache;
    private int upstreamPort;
    private int nicocachePort;
    private final Map<String, AtomicInteger> upstreamRequests = new ConcurrentHashMap<>();

    private FunctionalTestMain(Path repository, Path sandbox, Path classes) {
        this.repository = repository;
        this.sandbox = sandbox;
        this.classes = classes;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: FunctionalTestMain <repository> <sandbox> <classes>");
        }

        FunctionalTestMain suite = new FunctionalTestMain(
                Path.of(args[0]).toAbsolutePath().normalize(),
                Path.of(args[1]).toAbsolutePath().normalize(),
                Path.of(args[2]).toAbsolutePath().normalize());
        suite.execute();
    }

    private void execute() throws Exception {
        try {
            prepareSandbox();
            startUpstream();
            startNicoCache();

            run("forward proxy GET/POST/HEAD and upstream status", this::testForwardProxy);
            run("forward proxy byte range", this::testForwardProxyRange);
            run("conditional retrieval and upstream connection failure",
                    this::testConditionalAndUpstreamFailure);
            run("local file GET/range/method handling", this::testLocalFiles);
            run("response rewriting and Extension request filtering",
                    this::testResponseRewriteAndRequestFilter);
            run("thumbnail fetch and cache reuse", this::testThumbnailCache);
            run("nvcomment response saving", this::testCommentSaving);
            run("DOMAND/CMAF completion and offline playback", this::testCmafMasterFlow);
            run("cache info and legacy cache playback", this::testCacheInfoAndPlayback);
            run("cache removal API and validation", this::testCacheRemoval);
            run("Extension and Extension2 registrations and events", this::testExtensionDispatch);
        } finally {
            try {
                stopNicoCache();
            } catch (Throwable error) {
                FAILURES.add("NicoCache graceful shutdown: " + error.getMessage());
                error.printStackTrace(System.err);
            } finally {
                if (upstream != null) {
                    upstream.stop(0);
                }
                if (upstreamExecutor != null) {
                    upstreamExecutor.shutdownNow();
                }
            }
        }

        run("Extension system-exit callback", () -> assertFileContains(
                sandbox.resolve("extension-system-exit.txt"), "system-exit"));

        if (!FAILURES.isEmpty()) {
            System.err.println("Functional test failures: " + FAILURES.size());
            for (String failure : FAILURES) {
                System.err.println("  - " + failure);
            }
            throw new AssertionError("functional tests failed");
        }
        System.out.println("Functional tests passed: 12");
    }

    private void prepareSandbox() throws Exception {
        Files.createDirectories(sandbox.resolve("defaults"));
        Files.createDirectories(sandbox.resolve("local"));
        Files.createDirectories(sandbox.resolve("cache"));
        Files.createDirectories(sandbox.resolve("cvcache"));
        Files.createDirectories(sandbox.resolve("thcache"));
        Files.createDirectories(sandbox.resolve("data/tlsclient"));

        try (var stream = Files.list(repository.resolve("defaults"))) {
            stream.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .forEach(path -> copy(path, sandbox.resolve("defaults").resolve(path.getFileName())));
        }

        copy(repository.resolve("local/mime.types.default"), sandbox.resolve("local/mime.types"));
        copy(repository.resolve("nlFilter_sys.txt"), sandbox.resolve("nlFilter_sys.txt"));
        Files.writeString(sandbox.resolve("local/fixture.txt"),
                "local-functional-content", StandardCharsets.UTF_8);

        Files.write(sandbox.resolve("cache/sm900001_Functional.mp4"),
                "legacy-mp4-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900002[720p,128]_Functional.mp4"),
                "dmc-mp4-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900004_Functional.flv"),
                "legacy-flv-content".getBytes(StandardCharsets.UTF_8));
        Path hls = sandbox.resolve("cache/sm900003[720p,128]_Functional.hls");
        Files.createDirectories(hls);
        Files.writeString(hls.resolve("master.m3u8"),
                "#EXTM3U\n#EXT-X-VERSION:7\nsegment.ts\n", StandardCharsets.UTF_8);
        Files.writeString(hls.resolve("segment.ts"),
                "legacy-hls-segment", StandardCharsets.UTF_8);

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, "NicoCache".toCharArray());
        try (OutputStream output = Files.newOutputStream(sandbox.resolve("data/tlsclient/cacerts2"))) {
            keyStore.store(output, "NicoCache".toCharArray());
        }
    }

    private static void copy(Path source, Path target) {
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("failed to copy " + source + " to " + target, e);
        }
    }

    private void startUpstream() throws IOException {
        startUpstream(0);
    }

    private void startUpstream(int port) throws IOException {
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        upstream.createContext("/", this::handleUpstream);
        upstreamExecutor = Executors.newCachedThreadPool();
        upstream.setExecutor(upstreamExecutor);
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();
    }

    private void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
            upstream = null;
        }
        if (upstreamExecutor != null) {
            upstreamExecutor.shutdownNow();
            upstreamExecutor = null;
        }
    }

    private void handleUpstream(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            upstreamRequests.computeIfAbsent(path, ignored -> new AtomicInteger())
                    .incrementAndGet();
            if ("/conditional".equals(path)) {
                if ("\"functional-etag\"".equals(
                        exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    exchange.getResponseHeaders().set("ETag", "\"functional-etag\"");
                    exchange.sendResponseHeaders(304, -1);
                } else {
                    byte[] body = "conditional-content".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("ETag", "\"functional-etag\"");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                return;
            }
            if ("/rewrite".equals(path)) {
                byte[] body = "rewrite-original".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.getResponseHeaders().set("Last-Modified",
                        "Sun, 06 Nov 1994 08:49:37 GMT");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if ("/filtered".equals(path)) {
                String value = exchange.getRequestHeaders().getFirst("X-Functional-Filter");
                byte[] body = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if (path.startsWith("/thumbnails/")) {
                byte[] body = new byte[] {
                        (byte) 0xff, (byte) 0xd8, 0x46, 0x55, 0x4e, 0x43,
                        0x54, 0x49, 0x4f, 0x4e, (byte) 0xff, (byte) 0xd9 };
                exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
                exchange.getResponseHeaders().set("Last-Modified",
                        "Sun, 06 Nov 1994 08:49:37 GMT");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if ("/v1/threads".equals(path)) {
                byte[] body = "{\"comments\":[{\"body\":\"functional-comment\"}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if ("/api/getthumbinfo/9000100001".equals(path)) {
                byte[] body = ("<nicovideo_thumb_response status=\"ok\"><thumb>"
                        + "<video_id>sm900010</video_id><title>Functional CMAF</title>"
                        + "<thumbnail_url>https://tn.smilevideo.jp/smile?i=900010</thumbnail_url>"
                        + "</thumb></nicovideo_thumb_response>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if ("/watch/9000100001".equals(path)) {
                byte[] body = ("{\"video\":{\"id\":\"sm900010\","
                        + "\"title\":\"Functional CMAF\",\"duration\":1,"
                        + "\"isDeleted\":false},\"media\":{\"domand\":{"
                        + "\"videos\":[],\"audios\":[]}},\"system\":{"
                        + "\"isPeakTime\":false},\"viewer\":{\"isPremium\":false}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if ("/range".equals(path)) {
                sendRangeResponse(exchange);
                return;
            }
            if (path.endsWith("/access-rights/hls")) {
                byte[] body = ("{\"data\":{\"contentUrl\":\""
                        + cmafMasterUrl() + "?session=functional\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if (path.contains("/playlists/variants/")) {
                String base = "http://delivery.domand.nicovideo.jp/hlsbid/"
                        + "aaaaaaaaaaaaaaaaaaaaaaaa";
                String playlist = "#EXTM3U\n"
                        + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio-aac-128kbps\",NAME=\"128kbps\",URI=\""
                        + base + "/playlists/media/audio-aac-128kbps.m3u8?token=a\"\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO=\"audio-aac-128kbps\"\n"
                        + base + "/playlists/media/video-h264-720p.m3u8?token=v\n";
                byte[] body = playlist.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if (path.contains("/playlists/media/")) {
                boolean audio = path.contains("/audio-");
                String mediaType = audio ? "audio" : "video";
                String sourceId = audio ? "audio-aac-128kbps" : "video-h264-720p";
                String extension = audio ? "cmfa" : "cmfv";
                String base = "http://delivery.domand.nicovideo.jp/hlsbid/"
                        + "aaaaaaaaaaaaaaaaaaaaaaaa";
                String playlist = "#EXTM3U\n"
                        + "#EXT-X-KEY:METHOD=AES-128,URI=\"" + base + "/keys/"
                        + sourceId + ".key?token=k\",IV=0x00000000000000000000000000000001\n"
                        + "#EXT-X-MAP:URI=\"" + base + "/" + mediaType + "/1/"
                        + sourceId + "/init001." + extension + "?token=i\"\n"
                        + "#EXTINF:1.0,\n" + base + "/" + mediaType + "/1/"
                        + sourceId + "/01." + extension + "?token=c\n"
                        + "#EXT-X-ENDLIST\n";
                byte[] body = playlist.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if (path.contains("/keys/")) {
                byte[] body = new byte[16];
                Arrays.fill(body, (byte) 0x2a);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if (path.endsWith(".cmfa") || path.endsWith(".cmfv")) {
                String mediaType = path.endsWith(".cmfa") ? "audio" : "video";
                byte[] body;
                if (path.substring(path.lastIndexOf('/') + 1).startsWith("init")) {
                    body = (mediaType + "-init").getBytes(StandardCharsets.US_ASCII);
                } else {
                    body = encryptCmafSegment(mediaType + "-segment");
                }
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if ("/status/418".equals(path)) {
                byte[] body = "upstream-teapot".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("X-Upstream", "teapot");
                exchange.sendResponseHeaders(418, body.length);
                exchange.getResponseBody().write(body);
                return;
            }

            byte[] requestBody = readAll(exchange.getRequestBody());
            String response = exchange.getRequestMethod() + " " + path + " "
                    + new String(requestBody, StandardCharsets.UTF_8);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("X-Upstream", "yes");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        } finally {
            exchange.close();
        }
    }

    private static byte[] encryptCmafSegment(String content) throws IOException {
        try {
            byte[] key = new byte[16];
            Arrays.fill(key, (byte) 0x2a);
            byte[] iv = new byte[16];
            iv[15] = 1;
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            return cipher.doFinal(content.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IOException("failed to encrypt CMAF fixture", e);
        }
    }

    private static void sendRangeResponse(HttpExchange exchange) throws IOException {
        byte[] full = "0123456789".getBytes(StandardCharsets.US_ASCII);
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range == null) {
            exchange.sendResponseHeaders(200, full.length);
            exchange.getResponseBody().write(full);
            return;
        }

        byte[] part = Arrays.copyOfRange(full, 2, 6);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Accept-Ranges", "bytes");
        headers.set("Content-Range", "bytes 2-5/10");
        exchange.sendResponseHeaders(206, part.length);
        exchange.getResponseBody().write(part);
    }

    private void startNicoCache() throws Exception {
        nicocachePort = findFreePort();
        String config = String.join("\n",
                "# NicoCache_nl 設定ファイル",
                "listenPort=" + nicocachePort,
                "proxyHost=127.0.0.1",
                "proxyPort=" + upstreamPort,
                "allowFrom=local",
                "readTimeout=5000",
                "cacheFolder=cache",
                "convertedCacheFolder=cvcache",
                "needFreeSpace=0",
                "cacheAllocateFirst=false",
                "disableDirectoryWatcher=true",
                "disableVideoCacheSystem=false",
                "enableMitm=false",
                "cacheThumbnail=true",
                "thcacheMode=folder",
                "thcacheFolder=thcache",
                "cacheGetThumbInfo=false",
                "cacheExtThumb=false",
                "autoCacheComment=true",
                "useSearchExtension=false",
                "scriptOn=0",
                "localFileServer=true",
                "localRewriter=true",
                "rewriterDefaultCharset=UTF-8",
                "mimeTypes=local/mime.types",
                "touchCache=false",
                "title=true",
                "") ;
        Files.writeString(sandbox.resolve("config.properties"), config, StandardCharsets.UTF_8);

        Path log = sandbox.resolve("nicocache-functional.log");
        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable(), "-cp", classes.toString(), "dareka.Main");
        builder.directory(sandbox.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        nicocache = builder.start();

        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!nicocache.isAlive()) {
                throw new AssertionError("NicoCache exited before listening; log=" + log);
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), nicocachePort), 200);
                return;
            } catch (IOException e) {
                Thread.sleep(100L);
            }
        }
        throw new AssertionError("NicoCache did not listen within " + START_TIMEOUT + "; log=" + log);
    }

    private void testForwardProxy() throws Exception {
        Response get = request("GET http://example.invalid/echo HTTP/1.1\r\n"
                + "Host: example.invalid\r\nConnection: close\r\n\r\n");
        assertEquals(200, get.status, "GET status");
        assertEquals("yes", get.header("x-upstream"), "upstream header");
        assertEquals("GET /echo ", get.bodyText(), "GET body");

        Response post = request("POST http://example.invalid/echo HTTP/1.1\r\n"
                + "Host: example.invalid\r\nContent-Length: 7\r\nConnection: close\r\n\r\npayload");
        assertEquals(200, post.status, "POST status");
        assertEquals("POST /echo payload", post.bodyText(), "POST body");

        Response head = request("HEAD http://example.invalid/echo HTTP/1.1\r\n"
                + "Host: example.invalid\r\nConnection: close\r\n\r\n");
        assertEquals(200, head.status, "HEAD status");
        assertEquals(0, head.body.length, "HEAD body length");

        Response teapot = request("GET http://example.invalid/status/418 HTTP/1.1\r\n"
                + "Host: example.invalid\r\nConnection: close\r\n\r\n");
        assertEquals(418, teapot.status, "upstream status");
        assertEquals("upstream-teapot", teapot.bodyText(), "upstream error body");
    }

    private void testForwardProxyRange() throws Exception {
        Response response = request("GET http://example.invalid/range HTTP/1.1\r\n"
                + "Host: example.invalid\r\nRange: bytes=2-5\r\nConnection: close\r\n\r\n");
        assertEquals(206, response.status, "range status");
        assertEquals("bytes 2-5/10", response.header("content-range"), "content range");
        assertEquals("2345", response.bodyText(), "range body");
    }

    private void testConditionalAndUpstreamFailure() throws Exception {
        Response initial = request("GET http://example.invalid/conditional HTTP/1.1\r\n"
                + "Host: example.invalid\r\nConnection: close\r\n\r\n");
        assertEquals(200, initial.status, "conditional initial status");
        assertEquals("\"functional-etag\"", initial.header("etag"), "conditional ETag");
        assertEquals("conditional-content", initial.bodyText(), "conditional initial body");

        Response notModified = request("GET http://example.invalid/conditional HTTP/1.1\r\n"
                + "Host: example.invalid\r\nIf-None-Match: \"functional-etag\"\r\n"
                + "Connection: close\r\n\r\n");
        assertEquals(304, notModified.status, "conditional status");
        assertEquals(0, notModified.body.length, "conditional response body");

        int restartPort = upstreamPort;
        stopUpstream();
        try {
            byte[] unavailable = requestRaw("GET http://example.invalid/unavailable HTTP/1.1\r\n"
                    + "Host: example.invalid\r\nConnection: close\r\n\r\n");
            assertEquals(0, unavailable.length,
                    "upstream connection failure must close the client response");
        } finally {
            startUpstream(restartPort);
        }
    }

    private void testLocalFiles() throws Exception {
        Response get = request(nicoRequest("GET", "/local/fixture.txt", ""));
        assertEquals(200, get.status, "local status");
        assertEquals("local-functional-content", get.bodyText(), "local body");
        assertContains(get.header("content-type"), "text/plain", "local MIME");

        Response range = request(nicoRequest("GET", "/local/fixture.txt",
                "Range: bytes=6-15\r\n"));
        assertEquals(206, range.status, "local range status");
        assertEquals("functional", range.bodyText(), "local range body");

        Response post = request(nicoRequest("POST", "/local/fixture.txt", "Content-Length: 0\r\n"));
        assertEquals(405, post.status, "local POST status");
    }

    private void testResponseRewriteAndRequestFilter() throws Exception {
        Response rewritten = request("GET http://example.invalid/rewrite HTTP/1.1\r\n"
                + "Host: example.invalid\r\nConnection: close\r\n\r\n");
        assertEquals(200, rewritten.status, "rewriter status");
        assertEquals("rewrite-extension", rewritten.bodyText(), "rewriter body");
        assertEquals(null, rewritten.header("last-modified"),
                "rewriter must remove stale Last-Modified");

        Response filtered = request("GET http://example.invalid/filtered HTTP/1.1\r\n"
                + "Host: example.invalid\r\nConnection: close\r\n\r\n");
        assertEquals(200, filtered.status, "request filter status");
        assertEquals("registered", filtered.bodyText(), "request filter header");
    }

    private void testThumbnailCache() throws Exception {
        String path = "/thumbnails/900004/900004.1";
        String request = "GET http://nicovideo.cdn.nimg.jp" + path + " HTTP/1.1\r\n"
                + "Host: nicovideo.cdn.nimg.jp\r\nConnection: close\r\n\r\n";
        Response first = request(request);
        assertEquals(200, first.status, "thumbnail initial status");
        assertContains(first.header("content-type"), "image/jpeg", "thumbnail MIME");
        assertEquals(12, first.body.length, "thumbnail initial body length");

        Response cached = request(request);
        assertEquals(200, cached.status, "thumbnail cached status");
        assertEquals(12, cached.body.length, "thumbnail cached body length");
        assertEquals(1, upstreamRequestCount(path),
                "thumbnail second request must use the local thumbnail cache");
        try (var files = Files.walk(sandbox.resolve("thcache"))) {
            assertEquals(true, files.anyMatch(Files::isRegularFile),
                    "thumbnail cache file must be created");
        }
    }

    private void testCommentSaving() throws Exception {
        Response watch = request("GET http://www.nicovideo.jp/watch/9000100001 HTTP/1.1\r\n"
                + "Host: www.nicovideo.jp\r\nConnection: close\r\n\r\n");
        assertEquals(200, watch.status, "thread-to-video mapping watch status");
        assertContains(watch.bodyText(), "sm900010", "thread-to-video mapping watch body");

        String body = "{\"params\":{\"targets\":[{\"id\":\"9000100001\"}],"
                + "\"language\":\"ja-jp\"}}";
        Response response = request("POST http://public.nvcomment.nicovideo.jp/v1/threads"
                + " HTTP/1.1\r\nHost: public.nvcomment.nicovideo.jp\r\n"
                + "Content-Type: application/json\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\nConnection: close\r\n\r\n" + body);
        assertEquals(200, response.status, "comment status");
        assertContains(response.bodyText(), "functional-comment", "comment response");

        Path saved = sandbox.resolve("cache/sm900010.data/comment.0.ja-jp.json");
        waitForFileContaining(saved, "functional-comment", Duration.ofSeconds(3));
    }

    private void testCacheInfoAndPlayback() throws Exception {
        Response info = request(nicoRequest("GET",
                "/cache/info/v2?sm900001,sm900002,sm900003,sm900004", ""));
        assertEquals(200, info.status, "cache info status");
        assertContains(info.bodyText(), "sm900001_Functional.mp4", "legacy cache info");
        assertContains(info.bodyText(), "sm900002[720p,128]_Functional.mp4", "DMC cache info");
        assertContains(info.bodyText(), "sm900003[720p,128]_Functional.hls", "HLS cache info");
        assertContains(info.bodyText(), "sm900004_Functional.flv", "FLV cache info");

        Response playback = request(nicoRequest("GET", "/cache/sm900001.mp4", ""));
        assertEquals(200, playback.status, "cache playback status");
        assertEquals("legacy-mp4-content", playback.bodyText(), "cache playback body");

        Response range = request(nicoRequest("GET", "/cache/sm900001.mp4",
                "Range: bytes=7-9\r\n"));
        assertEquals(206, range.status, "cache range status");
        assertEquals("mp4", range.bodyText(), "cache range body");

        Response dmc = request(nicoRequest("GET", "/cache/sm900002.mp4", ""));
        assertEquals(200, dmc.status, "DMC MP4 playback status");
        assertEquals("dmc-mp4-content", dmc.bodyText(), "DMC MP4 playback body");

        Response flv = request(nicoRequest("GET", "/cache/sm900004.flv", ""));
        assertEquals(200, flv.status, "FLV playback status");
        assertEquals("legacy-flv-content", flv.bodyText(), "FLV playback body");

        String hlsBase = "/cache/file/nicocachenl_refcache=sm900003//";
        Response hlsMaster = request(nicoRequest("GET", hlsBase + "master.m3u8", ""));
        assertEquals(200, hlsMaster.status, "legacy HLS master status");
        assertContains(hlsMaster.bodyText(), "segment.ts", "legacy HLS master body");
        Response hlsSegment = request(nicoRequest("GET", hlsBase + "segment.ts", ""));
        assertEquals(200, hlsSegment.status, "legacy HLS segment status");
        assertEquals("legacy-hls-segment", hlsSegment.bodyText(), "legacy HLS segment body");
    }

    private void testCmafMasterFlow() throws Exception {
        Response accessRights = request("GET http://nvapi.nicovideo.jp/v1/watch/sm900010/access-rights/hls"
                + "?actionTrackId=functional HTTP/1.1\r\n"
                + "Host: nvapi.nicovideo.jp\r\nConnection: close\r\n\r\n");
        assertEquals(200, accessRights.status, "CMAF access-rights status");
        assertContains(accessRights.bodyText(), cmafMasterUrl(), "CMAF contentUrl");

        Response master = request("GET " + cmafMasterUrl() + "?session=functional HTTP/1.1\r\n"
                + "Host: delivery.domand.nicovideo.jp\r\nConnection: close\r\n\r\n");
        assertEquals(200, master.status, "CMAF master status");
        assertContains(master.bodyText(), "nicocachenl_domandcvikey=", "CMAF rewritten key");
        assertContains(master.bodyText(), "video-h264-720p.m3u8", "CMAF video playlist");
        assertContains(master.bodyText(), "audio-aac-128kbps.m3u8", "CMAF audio playlist");

        exerciseCmafMedia("audio", "audio-aac-128kbps", "cmfa");
        exerciseCmafMedia("video", "video-h264-720p", "cmfv");

        Path completed = waitForCompletedCmafCache(Duration.ofSeconds(8));
        assertFileContains(sandbox.resolve("extension-complete-cache.txt"), "sm900010");
        assertFileContains(sandbox.resolve("extension-event-6.txt"), "6");

        stopUpstream();
        String offlineBase = "/cache/file/nicocachenl_refcache=sm900010//";
        Response offlineMaster = request(nicoRequest("GET", offlineBase + "master.m3u8", ""));
        assertEquals(200, offlineMaster.status, "offline CMAF master status");
        assertContains(offlineMaster.bodyText(), "video", "offline CMAF master video reference");
        assertContains(offlineMaster.bodyText(), "audio", "offline CMAF master audio reference");

        Response offlineVideo = request(nicoRequest("GET", offlineBase + "video/01.cmfv", ""));
        assertEquals(200, offlineVideo.status, "offline CMAF video segment status");
        assertEquals("video-segment", offlineVideo.bodyText(), "offline CMAF video segment");
        Response offlineAudio = request(nicoRequest("GET", offlineBase + "audio/01.cmfa", ""));
        assertEquals(200, offlineAudio.status, "offline CMAF audio segment status");
        assertEquals("audio-segment", offlineAudio.bodyText(), "offline CMAF audio segment");
        assertContains(completed.getFileName().toString(), "sm900010",
                "completed CMAF cache directory");
    }

    private Path waitForCompletedCmafCache(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (var caches = Files.list(sandbox.resolve("cache"))) {
                Path completed = caches
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("sm900010"))
                        .filter(path -> path.getFileName().toString().endsWith(".hls"))
                        .findFirst().orElse(null);
                if (completed != null) {
                    return completed;
                }
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("CMAF cache was not completed after all encrypted segments");
    }

    private void exerciseCmafMedia(String mediaType, String sourceId, String extension)
            throws Exception {
        String key = "sm900010" + sourceId;
        String base = "http://delivery.domand.nicovideo.jp/hlsbid/"
                + "aaaaaaaaaaaaaaaaaaaaaaaa";
        String query = "?token=functional&nicocachenl_domandcvikey=" + key;

        Response playlist = request(absoluteRequest(base + "/playlists/media/"
                + sourceId + ".m3u8" + query, "delivery.domand.nicovideo.jp"));
        assertEquals(200, playlist.status, "CMAF " + mediaType + " playlist status");
        assertContains(playlist.bodyText(), "nicocachenl_domandcvikey=" + key,
                "CMAF " + mediaType + " playlist URL injection");

        Response keyResponse = request(absoluteRequest(base + "/keys/" + sourceId
                + ".key" + query, "delivery.domand.nicovideo.jp"));
        assertEquals(200, keyResponse.status, "CMAF " + mediaType + " key status");
        assertEquals(16, keyResponse.body.length, "CMAF " + mediaType + " key length");

        Response init = request(absoluteRequest(base + "/" + mediaType + "/1/"
                + sourceId + "/init001." + extension + query,
                "delivery.domand.nicovideo.jp"));
        assertEquals(200, init.status, "CMAF " + mediaType + " init chunk status");
        assertContains(init.bodyText(), mediaType + "-init", "CMAF " + mediaType + " init chunk");

        Response segment = request(absoluteRequest(base + "/" + mediaType + "/1/"
                + sourceId + "/01." + extension + query,
                "delivery.domand.nicovideo.jp"));
        assertEquals(200, segment.status, "CMAF " + mediaType + " media segment status");
        assertFalse(segment.bodyText().contains(mediaType + "-segment"),
                "upstream CMAF media segment must remain encrypted on the wire");
    }

    private static String absoluteRequest(String url, String host) {
        return "GET " + url + " HTTP/1.1\r\nHost: " + host
                + "\r\nConnection: close\r\n\r\n";
    }

    private static String cmafMasterUrl() {
        return "http://delivery.domand.nicovideo.jp/hlsbid/"
                + "aaaaaaaaaaaaaaaaaaaaaaaa/playlists/variants/bbbbbbbbbbbbbbbb.m3u8";
    }

    private void testCacheRemoval() throws Exception {
        Response invalid = request(nicoRequest("GET",
                "/cache/ajax_rmall?sm900001[720p,128]", ""));
        assertEquals(400, invalid.status, "invalid rmall status");

        Response removed = request(nicoRequest("GET", "/cache/ajax_rmall?sm900001", ""));
        assertEquals(200, removed.status, "rmall status");
        assertEquals("OK", removed.bodyText(), "rmall response");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900001_Functional.mp4")),
                "cache file must be removed only inside sandbox");

        for (String id : List.of("sm900002", "sm900003", "sm900004")) {
            Response legacyRemoved = request(nicoRequest("GET", "/cache/ajax_rmall?" + id, ""));
            assertEquals(200, legacyRemoved.status, id + " removal status");
            assertEquals("OK", legacyRemoved.bodyText(), id + " removal response");
        }
        assertFalse(Files.exists(sandbox.resolve("cache/sm900002[720p,128]_Functional.mp4")),
                "DMC MP4 cache must be removed");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900003[720p,128]_Functional.hls")),
                "legacy HLS cache must be removed");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900004_Functional.flv")),
                "FLV cache must be removed");
        assertFileContains(sandbox.resolve("extension-event-8.txt"), "8");
        assertFileContains(sandbox.resolve("extension-event-9.txt"), "9");
    }

    private void testExtensionDispatch() throws Exception {
        assertFileContains(sandbox.resolve("extension-registered.txt"), "registered");
        assertFileContains(sandbox.resolve("legacy-extension-loaded.txt"), "loaded");
        assertFileContains(sandbox.resolve("extension-event-2.txt"), "2");
        Response response = request(nicoRequest("GET", "/functional/extension", ""));
        assertEquals(200, response.status, "extension status");
        assertEquals("extension-ok", response.bodyText(), "extension body");
        Response legacy = request(nicoRequest("GET", "/functional/legacy", ""));
        assertEquals(200, legacy.status, "legacy extension status");
        assertEquals("legacy-extension-ok", legacy.bodyText(), "legacy extension body");
    }

    private void stopNicoCache() throws Exception {
        if (nicocache == null || !nicocache.isAlive()) {
            return;
        }
        try {
            request(nicoRequest("GET", "/functional/stop", ""));
            if (!nicocache.waitFor(STOP_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                dumpChildThreads();
                throw new AssertionError("NicoCache did not stop gracefully");
            }
        } finally {
            if (nicocache.isAlive()) {
                nicocache.destroy();
                if (!nicocache.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    nicocache.destroyForcibly();
                    nicocache.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        }
    }

    private void dumpChildThreads() {
        Path jcmd = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "jcmd.exe" : "jcmd");
        Path dump = sandbox.resolve("nicocache-thread-dump.txt");
        try {
            Process process = new ProcessBuilder(jcmd.toString(), Long.toString(nicocache.pid()), "Thread.print")
                    .redirectErrorStream(true)
                    .redirectOutput(dump.toFile())
                    .start();
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            try {
                Files.writeString(dump, "thread dump failed: " + e, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
    }

    private Response request(String request) throws Exception {
        return Response.parse(requestRaw(request));
    }

    private byte[] requestRaw(String request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), nicocachePort), 2000);
            socket.setSoTimeout(8000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return readHttpResponse(socket.getInputStream(), request.startsWith("HEAD "));
        }
    }

    private static byte[] readHttpResponse(InputStream input, boolean headRequest)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int headerEnd = -1;
        int contentLength = -1;
        boolean chunked = false;
        while (true) {
            int read = input.read(buffer);
            if (read == -1) {
                break;
            }
            output.write(buffer, 0, read);
            byte[] received = output.toByteArray();
            if (headerEnd < 0) {
                headerEnd = Response.indexOf(received,
                        "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                if (headerEnd >= 0) {
                    String headers = new String(received, 0, headerEnd,
                            StandardCharsets.ISO_8859_1);
                    for (String line : headers.split("\r\n")) {
                        int colon = line.indexOf(':');
                        if (colon <= 0) {
                            continue;
                        }
                        String name = line.substring(0, colon).trim();
                        String value = line.substring(colon + 1).trim();
                        if ("Content-Length".equalsIgnoreCase(name)) {
                            contentLength = Integer.parseInt(value);
                        } else if ("Transfer-Encoding".equalsIgnoreCase(name)
                                && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                            chunked = true;
                        }
                    }
                    if (headRequest) {
                        contentLength = 0;
                    }
                }
            }
            if (headerEnd >= 0 && contentLength >= 0
                    && received.length >= headerEnd + 4 + contentLength) {
                break;
            }
            if (headerEnd >= 0 && chunked
                    && (Response.indexOf(received,
                            "\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
                            headerEnd + 4) >= 0
                        || Response.indexOf(received,
                            "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
                            headerEnd + 4) == headerEnd + 4)) {
                break;
            }
        }
        return output.toByteArray();
    }

    private static String nicoRequest(String method, String path, String extraHeaders) {
        return method + " http://www.nicovideo.jp" + path + " HTTP/1.1\r\n"
                + "Host: www.nicovideo.jp\r\n" + extraHeaders + "Connection: close\r\n\r\n";
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private int upstreamRequestCount(String path) {
        AtomicInteger count = upstreamRequests.get(path);
        return count == null ? 0 : count.get();
    }

    private static void waitForFileContaining(Path path, String expected, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)
                    && Files.readString(path, StandardCharsets.UTF_8).contains(expected)) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("timed out waiting for file contents: " + path);
    }

    private static void run(String name, CheckedRunnable test) {
        try {
            test.run();
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            FAILURES.add(name + ": " + error.getMessage());
            error.printStackTrace(System.err);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertContains(String actual, String expected, String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + ": expected to contain=" + expected + ", actual=" + actual);
        }
    }

    private static void assertFalse(boolean actual, String message) {
        if (actual) {
            throw new AssertionError(message);
        }
    }

    private static void assertFileContains(Path path, String expected) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("marker file is missing: " + path);
        }
        assertContains(Files.readString(path, StandardCharsets.UTF_8), expected,
                "marker contents for " + path.getFileName());
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class Response {
        private final int status;
        private final Map<String, String> headers;
        private final byte[] body;

        private Response(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        private String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        private String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        private static Response parse(byte[] raw) throws IOException {
            byte[] separator = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            int headerEnd = indexOf(raw, separator);
            if (headerEnd < 0) {
                throw new IOException("response has no header terminator: "
                        + new String(raw, StandardCharsets.ISO_8859_1));
            }
            String headerText = new String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            String[] statusParts = lines[0].split(" ", 3);
            int status = Integer.parseInt(statusParts[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(colon + 1).trim());
                }
            }
            byte[] body = Arrays.copyOfRange(raw, headerEnd + separator.length, raw.length);
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                body = decodeChunked(body);
            }
            return new Response(status, headers, body);
        }

        private static byte[] decodeChunked(byte[] encoded) throws IOException {
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            int position = 0;
            while (position < encoded.length) {
                int lineEnd = indexOf(encoded, "\r\n".getBytes(StandardCharsets.US_ASCII), position);
                if (lineEnd < 0) {
                    throw new IOException("invalid chunk header");
                }
                String sizeText = new String(encoded, position, lineEnd - position,
                        StandardCharsets.US_ASCII).split(";", 2)[0];
                int size = Integer.parseInt(sizeText.trim(), 16);
                position = lineEnd + 2;
                if (size == 0) {
                    break;
                }
                if (position + size > encoded.length) {
                    throw new IOException("truncated chunk body");
                }
                decoded.write(encoded, position, size);
                position += size + 2;
            }
            return decoded.toByteArray();
        }

        private static int indexOf(byte[] data, byte[] needle) {
            return indexOf(data, needle, 0);
        }

        private static int indexOf(byte[] data, byte[] needle, int start) {
            outer:
            for (int i = start; i <= data.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (data[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }
}
