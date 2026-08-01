package functional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dareka.common.LRUMap;
import dareka.processor.URLResource;
import dareka.processor.URLResourceCache;
import dareka.processor.impl.CmafCachingProcessor;
import dareka.processor.impl.DomandCVIEntry;
import dareka.processor.util.LocalFlvTemplate;

public final class FunctionalTestMain {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> FAILURES = new ArrayList<>();
    private static int executedTests;

    private final Path repository;
    private final Path sandbox;
    private final Path application;
    private final Path classes;
    private final boolean apiOnly;
    private HttpServer upstream;
    private ExecutorService upstreamExecutor;
    private Process nicocache;
    private int upstreamPort;
    private int nicocachePort;
    private final Map<String, AtomicInteger> upstreamRequests = new ConcurrentHashMap<>();

    private FunctionalTestMain(Path repository, Path sandbox, Path classes,
            boolean apiOnly) {
        this.repository = repository;
        this.sandbox = sandbox;
        this.application = sandbox.resolve("application");
        this.classes = classes;
        this.apiOnly = apiOnly;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4
                || args.length == 4 && !"--api-only".equals(args[3])) {
            throw new IllegalArgumentException(
                    "usage: FunctionalTestMain <repository> <sandbox> <classes> [--api-only]");
        }

        FunctionalTestMain suite = new FunctionalTestMain(
                Path.of(args[0]).toAbsolutePath().normalize(),
                Path.of(args[1]).toAbsolutePath().normalize(),
                Path.of(args[2]).toAbsolutePath().normalize(),
                args.length == 4);
        suite.execute();
    }

    private void execute() throws Exception {
        try {
            prepareSandbox();
            startUpstream();
            startNicoCache();

            if (apiOnly) {
                run("control API contract and authentication", this::testControlApiContract);
                run("core cache API contract and validation", this::testCacheApiContract);
                run("control force-shutdown contract", this::testControlForceShutdown);
            } else {
                run("URL resource cache response policies", this::testUrlResourceCachePolicies);
                run("template reload and CMAF utility validation",
                        this::testTemplateAndCmafUtility);
                run("CMAF cache progress size stability",
                        CmafCachingProgressUnitTest::run);
                run("LRU map minimum capacity and eviction",
                        this::testLruMapCapacity);
                run("GUI log filtering primitives", LogSearchUnitTest::run);
                run("forward proxy GET/POST/HEAD and upstream status", this::testForwardProxy);
                run("forward proxy byte range", this::testForwardProxyRange);
                run("HTTPS CONNECT and TLS loopback", this::testHttpsMitmLocalFile);
                run("conditional retrieval and upstream connection failure",
                        this::testConditionalAndUpstreamFailure);
                run("local file GET/range/method handling", this::testLocalFiles);
                run("system and user nlFilter execution order",
                        this::testLayeredNlFilters);
                run("response rewriting and Extension request filtering",
                        this::testResponseRewriteAndRequestFilter);
                run("thumbnail fetch and cache reuse", this::testThumbnailCache);
                run("nvcomment response saving", this::testCommentSaving);
                run("hlsext encrypted CMAF completion and playback",
                        this::testHlsextEncryptedCmafFlow);
                run("shlsbid playlist refresh keeps segment decrypt generation",
                        this::testShlsbidPlaylistRefreshDecryptInfo);
                run("DOMAND/CMAF completion and offline playback", this::testCmafMasterFlow);
                run("cache info and legacy cache playback", this::testCacheInfoAndPlayback);
                run("cache removal API and validation", this::testCacheRemoval);
                run("Extension and Extension2 registrations and events", this::testExtensionDispatch);
            }
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

        if (!apiOnly) {
            run("Extension exception does not interrupt graceful shutdown", () ->
                    assertFileContains(
                            sandbox.resolve("extension-system-exit.txt"),
                            "system-exit"));
        }

        if (!FAILURES.isEmpty()) {
            System.err.println("Functional test failures: " + FAILURES.size());
            for (String failure : FAILURES) {
                System.err.println("  - " + failure);
            }
            throw new AssertionError("functional tests failed");
        }
        System.out.println("Functional tests passed: " + executedTests);
    }

    private void prepareSandbox() throws Exception {
        Files.createDirectories(application.resolve("defaults"));
        Files.createDirectories(application.resolve("local"));
        Files.createDirectories(application.resolve("nlFilters"));
        Files.createDirectories(sandbox.resolve("nlFilters"));
        Files.createDirectories(sandbox.resolve("local"));
        Files.createDirectories(sandbox.resolve("cache"));
        Files.createDirectories(sandbox.resolve("cvcache"));
        Files.createDirectories(sandbox.resolve("thcache"));
        Files.createDirectories(sandbox.resolve("data/tlsclient"));
        Files.createDirectories(sandbox.resolve("certs"));

        try (var stream = Files.list(repository.resolve("defaults"))) {
            stream.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .forEach(path -> copy(path, application.resolve("defaults")
                            .resolve(path.getFileName())));
        }

        copy(repository.resolve("local/mime.types.default"),
                application.resolve("local/mime.types.default"));
        copy(repository.resolve("nlFilter_sys.txt"),
                application.resolve("nlFilter_sys.txt"));
        Files.writeString(sandbox.resolve("local/fixture.txt"),
                "local-functional-content", StandardCharsets.UTF_8);
        Files.writeString(application.resolve("local/system-only.txt"),
                "system-local-content", StandardCharsets.UTF_8);
        Files.writeString(application.resolve("local/overlay.txt"),
                "system-overlay", StandardCharsets.UTF_8);
        Files.writeString(sandbox.resolve("local/overlay.txt"),
                "user-overlay", StandardCharsets.UTF_8);
        Path linkedTarget = sandbox.resolve("linked-local-target");
        Files.createDirectories(linkedTarget);
        Files.writeString(linkedTarget.resolve("linked.txt"),
                "linked-local-content", StandardCharsets.UTF_8);
        createDirectoryLink(
                sandbox.resolve("local/features"), linkedTarget);
        Files.writeString(
                application.resolve("nlFilters/01_system_test.txt"),
                layeredFilter(
                        "system layer", "layer-base", "layer-system"),
                StandardCharsets.UTF_8);
        Files.writeString(
                sandbox.resolve("nlFilters/99_user_test.txt"),
                layeredFilter(
                        "user layer", "layer-system", "layer-user"),
                StandardCharsets.UTF_8);

        Files.write(sandbox.resolve("cache/sm900001_Functional.mp4"),
                "legacy-mp4-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900002[720p,128]_Functional.mp4"),
                "dmc-mp4-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900004_Functional.flv"),
                "legacy-flv-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900005_Functional.swf"),
                "legacy-swf-content".getBytes(StandardCharsets.UTF_8));
        Path hls = sandbox.resolve("cache/sm900003[720p,128]_Functional.hls");
        Files.createDirectories(hls);
        Files.writeString(hls.resolve("master.m3u8"),
                "#EXTM3U\n#EXT-X-VERSION:7\nsegment.ts\n", StandardCharsets.UTF_8);
        Files.writeString(hls.resolve("segment.ts"),
                "legacy-hls-segment", StandardCharsets.UTF_8);
        Path lowerHls = sandbox.resolve("cache/sm900003[360p,64]_Functional.hls");
        Files.createDirectories(lowerHls);
        Files.writeString(lowerHls.resolve("master.m3u8"),
                "#EXTM3U\n#EXT-X-VERSION:7\nsegment.ts\n", StandardCharsets.UTF_8);
        Files.writeString(lowerHls.resolve("segment.ts"),
                "lower-quality-hls-segment", StandardCharsets.UTF_8);

        Files.createDirectories(sandbox.resolve("cache/api-target"));
        Files.createDirectories(sandbox.resolve("list"));
        Files.write(sandbox.resolve("cache/api-target/sm900020_Target.mp4"),
                "api-target-seed".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900006_Api.mp4"),
                "api-rm-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/nltmp_sm900007_Api.mp4"),
                "api-rmtmp-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/nltmp_sm900021_Api.mp4"),
                "api-legacy-rmtmp-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900008_Api.mp4"),
                "api-rmall-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/nltmp_sm900008_Api.mp4"),
                "api-rmall-tmp-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900009_Api.mp4"),
                "api-redirect-rm-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/nltmp_sm900013_Api.mp4"),
                "api-redirect-rmtmp-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900014_Api.mp4"),
                "api-redirect-rmall-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/nltmp_sm900014_Api.mp4"),
                "api-redirect-rmall-tmp-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900015_Api.mp4"),
                "api-move-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900016_Api.mp4"),
                "api-topmove-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900017_Api.mp4"),
                "api-title-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900018_Api.mp4"),
                "api-legacy-rm-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900019_Api.mp4"),
                "api-legacy-rmall-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900022_Api.mp4"),
                "api-ajax-legacy-rm-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/nltmp_sm900023_Api.mp4"),
                "api-ajax-legacy-rmtmp-content".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("cache/sm900024_Api.mp4"),
                "api-ajax-legacy-rmall-content".getBytes(StandardCharsets.UTF_8));
        Files.writeString(sandbox.resolve("list/api.txt"),
                "beta\nalpha\nalpha\n", StandardCharsets.UTF_8);

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, "NicoCache".toCharArray());
        try (OutputStream output = Files.newOutputStream(sandbox.resolve("data/tlsclient/cacerts2"))) {
            keyStore.store(output, "NicoCache".toCharArray());
        }
        prepareMitmKeyStore();
    }

    private void prepareMitmKeyStore() throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "keytool.exe" : "keytool");
        Path keyStore = sandbox.resolve("certs/site.jks");
        Process process = new ProcessBuilder(
                keytool.toString(), "-genkeypair", "-alias", "site",
                "-keyalg", "RSA", "-keysize", "2048",
                "-keystore", keyStore.toString(), "-storetype", "JKS",
                "-storepass", "NicoCache", "-keypass", "NicoCache",
                "-dname", "CN=www.nicovideo.jp",
                "-ext", "SAN=dns:www.nicovideo.jp",
                "-validity", "2", "-noprompt")
                .redirectErrorStream(true)
                .start();
        byte[] output = readAll(process.getInputStream());
        if (process.waitFor() != 0) {
            throw new IOException("keytool failed: "
                    + new String(output, StandardCharsets.UTF_8));
        }
        Files.writeString(sandbox.resolve("certs/site.targets"),
                "www.nicovideo.jp\n", StandardCharsets.UTF_8);
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
            int requestNumber = upstreamRequests
                    .computeIfAbsent(path, ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (path.startsWith("/resource-cache/")) {
                if (path.endsWith("/max-age")) {
                    exchange.getResponseHeaders().set("Cache-Control", "max-age=60");
                } else if (path.endsWith("/no-store")) {
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                } else if (path.endsWith("/stale-age")) {
                    exchange.getResponseHeaders().set("Cache-Control", "max-age=60");
                    exchange.getResponseHeaders().set("Age", "120");
                }
                byte[] body = (path + "-" + requestNumber)
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
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
            if ("/two-layer".equals(path)) {
                byte[] body = "layer-base".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set(
                        "Content-Type", "text/plain; charset=utf-8");
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
            if ("/watch/9000100001".equals(path) || "/watch/9000110001".equals(path)
                    || "/watch/9000120001".equals(path)) {
                String smid = path.contains("900012") ? "sm900012"
                        : path.contains("900011") ? "sm900011" : "sm900010";
                byte[] body = ("{\"video\":{\"id\":\"" + smid + "\","
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
                String masterUrl = path.contains("sm900012") ? shlsbidMasterUrl()
                        : path.contains("sm900011") ? hlsextMasterUrl() : cmafMasterUrl();
                byte[] body = ("{\"data\":{\"contentUrl\":\""
                        + masterUrl + "?session=functional\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if (path.contains("/playlists/variants/")) {
                String route = path.contains("/hlsext/") ? "hlsext"
                        : path.contains("/shlsbid/") ? "shlsbid" : "hlsbid";
                String base = cmafBase(route);
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
                boolean rotating = path.contains("/hlsext/");
                boolean refreshing = path.contains("/shlsbid/");
                String rawQuery = exchange.getRequestURI().getRawQuery();
                boolean secondGeneration = refreshing && rawQuery != null
                        && rawQuery.contains("generation=2");
                String route = rotating ? "hlsext" : refreshing ? "shlsbid" : "hlsbid";
                String base = cmafBase(route);
                String firstKeyToken = rotating || refreshing
                        ? (secondGeneration ? "k2" : "k1") : "k";
                int firstIv = secondGeneration ? 2 : 1;
                String firstSegmentToken = secondGeneration ? "c2" : "c1";
                String playlist = "#EXTM3U\n"
                        + "#EXT-X-KEY:IV=0x0000000000000000000000000000000"
                        + firstIv + ",URI=\""
                        + base + "/keys/" + sourceId + ".key?token="
                        + firstKeyToken + "\",METHOD=AES-128\n"
                        + "#EXT-X-MAP:URI=\"" + base + "/" + mediaType + "/1/"
                        + sourceId + "/init001." + extension + "?token=i\"\n"
                        + "#EXTINF:1.0,\n" + base + "/" + mediaType + "/1/"
                        + sourceId + "/01." + extension + "?token="
                        + firstSegmentToken + "\n"
                        + (rotating
                                ? "#EXT-X-KEY:METHOD=AES-128,URI=\""
                                + base + "/keys/" + sourceId + ".key?token=k2\","
                                + "IV=0x00000000000000000000000000000002\n"
                                + "#EXTINF:1.0,\n" + base + "/" + mediaType + "/1/"
                                + sourceId + "/02." + extension + "?token=c2\n"
                                : "")
                        + "#EXT-X-ENDLIST\n";
                byte[] body = playlist.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if (path.contains("/keys/")) {
                byte[] body = new byte[16];
                boolean secondKey = (path.contains("/hlsext/") || path.contains("/shlsbid/"))
                        && exchange.getRequestURI().getRawQuery() != null
                        && exchange.getRequestURI().getRawQuery().contains("token=k2");
                boolean duplicateHlsbidKey = path.contains("/hlsbid/")
                        && exchange.getRequestURI().getRawQuery() != null
                        && exchange.getRequestURI().getRawQuery().contains("token=k")
                        && requestNumber > 1;
                Arrays.fill(body, secondKey || duplicateHlsbidKey
                        ? (byte) 0x2b : (byte) 0x2a);
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
                    String rawQuery = exchange.getRequestURI().getRawQuery();
                    boolean secondSegment = (path.contains("/hlsext/")
                            && path.substring(path.lastIndexOf('/') + 1).startsWith("02."))
                            || (path.contains("/shlsbid/") && rawQuery != null
                            && rawQuery.contains("token=c2"));
                    body = encryptCmafSegment(
                            mediaType + "-segment" + (secondSegment ? "-2" : ""),
                            secondSegment ? (byte) 0x2b : (byte) 0x2a,
                            secondSegment ? 2 : 1);
                }
                boolean chunkedCmaf = path.contains("/hlsbid/") || path.contains("/shlsbid/");
                exchange.sendResponseHeaders(200, chunkedCmaf ? 0 : body.length);
                if ((path.contains("/hlsext/") || path.contains("/hlsbid/"))
                        && path.substring(path.lastIndexOf('/') + 1).startsWith("01.")) {
                    try {
                        Thread.sleep(150L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted duplicate CMAF fixture", e);
                    }
                }
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

    private static byte[] encryptCmafSegment(
            String content, byte keyFill, int ivLastByte) throws IOException {
        try {
            byte[] key = new byte[16];
            Arrays.fill(key, keyFill);
            byte[] iv = new byte[16];
            iv[15] = (byte) ivLastByte;
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
                "enableMitm=true",
                "mitmHostPort=www.nicovideo.jp",
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
        config += "userDataRoot="
                + sandbox.toString().replace("\\", "\\\\") + "\n";
        Files.writeString(application.resolve("config.properties"),
                config, StandardCharsets.UTF_8);

        Path log = sandbox.resolve("nicocache-functional.log");
        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable(),
                "-Dnicocache.applicationRoot=" + application,
                "-cp", classes.toString(), "dareka.Main");
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

    private void testUrlResourceCachePolicies() throws Exception {
        String oldProxyHost = System.getProperty("proxyHost");
        String oldProxyPort = System.getProperty("proxyPort");
        String oldReadTimeout = System.getProperty("readTimeout");
        try {
            System.setProperty("proxyHost", "");
            System.setProperty("proxyPort", "0");
            System.setProperty("readTimeout", "10000");

            URLResourceCache cache = new URLResourceCache(10, 10 * 60 * 1000);
            String base = "http://127.0.0.1:" + upstreamPort + "/resource-cache/";

            URLResource maxAgeFirst = cache.cacheAndGet("max-age", base + "max-age");
            assertContains(resourceBody(maxAgeFirst), "max-age-1",
                    "max-age initial response");
            URLResource maxAgeSecond = cache.cacheAndGet("max-age", base + "max-age");
            assertContains(resourceBody(maxAgeSecond), "max-age-1",
                    "max-age cached response");
            assertEquals(1, upstreamRequestCount("/resource-cache/max-age"),
                    "max-age upstream request count");

            URLResource noDirectivesFirst = cache.cacheAndGet(
                    "no-directives", base + "no-directives");
            assertContains(resourceBody(noDirectivesFirst), "no-directives-1",
                    "response without Cache-Control initial response");
            URLResource noDirectivesSecond = cache.cacheAndGet(
                    "no-directives", base + "no-directives");
            assertContains(resourceBody(noDirectivesSecond), "no-directives-1",
                    "response without Cache-Control cached response");
            assertEquals(1, upstreamRequestCount("/resource-cache/no-directives"),
                    "response without Cache-Control upstream request count");

            URLResource noStoreFirst = cache.cacheAndGet("no-store", base + "no-store");
            assertContains(resourceBody(noStoreFirst), "no-store-1",
                    "no-store initial response");
            assertFalse(cache.isCached("no-store"),
                    "no-store response must not remain cached");
            URLResource noStoreSecond = cache.cacheAndGet("no-store", base + "no-store");
            assertContains(resourceBody(noStoreSecond), "no-store-2",
                    "no-store repeated response");
            assertEquals(2, upstreamRequestCount("/resource-cache/no-store"),
                    "no-store upstream request count");

            URLResource staleFirst = cache.cacheAndGet("stale-age", base + "stale-age");
            assertContains(resourceBody(staleFirst), "stale-age-1",
                    "stale Age initial response");
            assertFalse(cache.isCached("stale-age"),
                    "stale Age response must not remain cached");
            URLResource staleSecond = cache.cacheAndGet("stale-age", base + "stale-age");
            assertContains(resourceBody(staleSecond), "stale-age-2",
                    "stale Age repeated response");
        } finally {
            restoreProperty("proxyHost", oldProxyHost);
            restoreProperty("proxyPort", oldProxyPort);
            restoreProperty("readTimeout", oldReadTimeout);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private void testTemplateAndCmafUtility() throws Exception {
        Path templatePath = sandbox.resolve("local/template-functional.html");
        Files.writeString(templatePath, "first-${value}", StandardCharsets.UTF_8);
        LocalFlvTemplate template = new LocalFlvTemplate(templatePath.toFile());
        assertEquals(true, template.assign("value", "resolved"),
                "template variable assignment");
        assertContains(template.execute(), "first-resolved", "initial template");

        Files.writeString(templatePath, "second-${value}", StandardCharsets.UTF_8);
        assertContains(template.execute(), "second-resolved",
                "updated template must invalidate cache");
        Files.writeString(templatePath, "trailing-$", StandardCharsets.UTF_8);
        assertContains(template.execute(), "trailing-$",
                "trailing dollar must remain literal");

        assertEquals(true, Arrays.equals(new byte[] { 0x01, (byte)0xaf },
                CmafCachingProcessor.hexStringToByteArray("01af")),
                "hex conversion");
        try {
            CmafCachingProcessor.hexStringToByteArray("abc");
            throw new AssertionError("odd-length hex must be rejected");
        } catch (NumberFormatException expected) {
            // expected
        }

        DomandCVIEntry decryptInfo = new DomandCVIEntry(
                "functional", "sm", "900012", 0, 0, "0p", null,
                "audio-aac-128kbps", false, ".hls", null, null, null);
        AtomicInteger lateListenerCalls = new AtomicInteger();
        decryptInfo.setAudioIV(new byte[16]);
        decryptInfo.setAudioKey(new byte[16]);
        decryptInfo.addGotAudioDecryptInfoListeners(lateListenerCalls::incrementAndGet);
        assertEquals(1, lateListenerCalls.get(),
                "late decrypt listener registration must not be lost");
    }

    private void testLruMapCapacity() {
        LRUMap<Integer, String> cache = new LRUMap<>(20);
        for (int index = 0; index < 20; index++) {
            cache.put(index, "value-" + index);
        }

        cache.setMaxEntries(1);
        assertEquals(12, cache.getMaxEntries(),
                "minimum configured capacity");
        assertEquals(12, cache.size(),
                "shrinking below the minimum must retain minimum capacity");
        assertEquals(null, cache.get(7),
                "oldest entry must be evicted");
        assertEquals("value-8", cache.get(8),
                "first retained entry");

        cache.put(20, "value-20");
        assertEquals(12, cache.size(),
                "insertion must preserve normalized capacity");
        assertEquals(null, cache.get(9),
                "least recently used entry must be evicted after access");
    }

    private static String resourceBody(URLResource resource) throws IOException {
        if (resource == null || resource.getResponseBody() == null) {
            throw new AssertionError("URLResource body is missing");
        }
        return new String(resource.getResponseBody(), StandardCharsets.UTF_8);
    }

    private void testForwardProxyRange() throws Exception {
        Response response = request("GET http://example.invalid/range HTTP/1.1\r\n"
                + "Host: example.invalid\r\nRange: bytes=2-5\r\nConnection: close\r\n\r\n");
        assertEquals(206, response.status, "range status");
        assertEquals("bytes 2-5/10", response.header("content-range"), "content range");
        assertEquals("2345", response.bodyText(), "range body");
    }

    private void testHttpsMitmLocalFile() throws Exception {
        try (Socket proxy = new Socket()) {
            proxy.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), nicocachePort), 2000);
            proxy.setSoTimeout(8000);
            proxy.getOutputStream().write(("CONNECT www.nicovideo.jp:443 HTTP/1.1\r\n"
                    + "Host: www.nicovideo.jp:443\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            proxy.getOutputStream().flush();
            String connectResponse = readHeader(proxy.getInputStream());
            assertContains(connectResponse, " 200 ", "CONNECT status");

            TrustManager[] trustAll = new TrustManager[] { new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }
            } };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, null);
            try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(
                    proxy, "www.nicovideo.jp", 443, true)) {
                tls.setUseClientMode(true);
                tls.startHandshake();
                tls.getOutputStream().write(("GET /local/fixture.txt HTTP/1.1\r\n"
                        + "Host: www.nicovideo.jp\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                tls.getOutputStream().flush();
                Response response = Response.parse(readHttpResponse(tls.getInputStream(), false));
                assertEquals(200, response.status, "HTTPS local status");
                assertEquals("local-functional-content", response.bodyText(), "HTTPS local body");
            }
        }
    }

    private static String readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = input.read();
            if (value < 0) {
                throw new IOException("connection closed before header completed");
            }
            output.write(value);
            byte expected = new byte[] {'\r', '\n', '\r', '\n'}[matched];
            matched = value == expected ? matched + 1 : value == '\r' ? 1 : 0;
        }
        return output.toString(StandardCharsets.ISO_8859_1);
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
            Response unavailable = request(
                    "GET http://example.invalid/unavailable HTTP/1.1\r\n"
                    + "Host: example.invalid\r\nConnection: close\r\n\r\n");
            assertEquals(502, unavailable.status,
                    "upstream connection failure status");
            assertFalse(unavailable.bodyText().isBlank(),
                    "upstream connection failure response body");
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

        Response system = request(nicoRequest(
                "GET", "/local/system-only.txt", ""));
        assertEquals(200, system.status, "system local status");
        assertEquals("system-local-content", system.bodyText(),
                "system local fallback");

        Response overlay = request(nicoRequest(
                "GET", "/local/overlay.txt", ""));
        assertEquals(200, overlay.status, "overlay local status");
        assertEquals("user-overlay", overlay.bodyText(),
                "user local must override system local");

        Response linked = request(nicoRequest(
                "GET", "/local/features/linked.txt", ""));
        assertEquals(200, linked.status, "symbolic-link local status");
        assertEquals("linked-local-content", linked.bodyText(),
                "symbolic-link local body");
    }

    private void testLayeredNlFilters() throws Exception {
        Response response = request(
                "GET http://example.invalid/two-layer HTTP/1.1\r\n"
                + "Host: example.invalid\r\nConnection: close\r\n\r\n");
        assertEquals(200, response.status, "layered nlFilter status");
        assertEquals("layer-user", response.bodyText(),
                "user nlFilter must run after system nlFilter");
    }

    private static String layeredFilter(
            String name, String match, String replacement) {
        return String.join(System.lineSeparator(),
                "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)",
                "[Replace]",
                "Name = " + name,
                "URL = example.invalid/two-layer",
                "ContentType = text/plain",
                "Match<",
                match,
                ">",
                "Replace<",
                replacement,
                ">",
                "");
    }

    private static void createDirectoryLink(Path link, Path target)
            throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (IOException linkError) {
            if (!System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("windows")) {
                throw linkError;
            }
            Process process = new ProcessBuilder(
                    "cmd.exe", "/c", "mklink", "/J",
                    link.toString(), target.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (process.waitFor() != 0 || !Files.isDirectory(link)) {
                throw linkError;
            }
        }
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
                "/cache/info/v2?sm900001,sm900002,sm900003,sm900004,sm900005", ""));
        assertEquals(200, info.status, "cache info status");
        assertContains(info.bodyText(), "sm900001_Functional.mp4", "legacy cache info");
        assertContains(info.bodyText(), "sm900002[720p,128]_Functional.mp4", "DMC cache info");
        assertContains(info.bodyText(), "sm900003[720p,128]_Functional.hls", "HLS cache info");
        assertContains(info.bodyText(), "sm900004_Functional.flv", "FLV cache info");
        assertContains(info.bodyText(), "sm900005_Functional.swf", "SWF cache info");

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

        Response swf = request(nicoRequest("GET", "/cache/sm900005.swf", ""));
        assertEquals(200, swf.status, "SWF playback status");
        assertEquals("legacy-swf-content", swf.bodyText(), "SWF playback body");

        String hlsBase = "/cache/file/nicocachenl_refcache=sm900003//";
        Response hlsMaster = request(nicoRequest("GET", hlsBase + "master.m3u8", ""));
        assertEquals(200, hlsMaster.status, "legacy HLS master status");
        assertContains(hlsMaster.bodyText(), "segment.ts", "legacy HLS master body");
        Response hlsSegment = request(nicoRequest("GET", hlsBase + "segment.ts", ""));
        assertEquals(200, hlsSegment.status, "legacy HLS segment status");
        assertEquals("legacy-hls-segment", hlsSegment.bodyText(), "legacy HLS segment body");

        String exactHlsBase = "/cache/file/nicocachenl_refcache="
                + "sm900003[360p,64].hls//";
        Response exactHlsSegment = request(nicoRequest(
                "GET", exactHlsBase + "segment.ts", ""));
        assertEquals(200, exactHlsSegment.status, "quality-specific HLS status");
        assertEquals("lower-quality-hls-segment", exactHlsSegment.bodyText(),
                "quality-specific HLS body");

        String exactHlsWithoutPostfix = "/cache/file/nicocachenl_refcache="
                + "sm900003[360p,64]//segment.ts";
        Response exactHlsWithoutPostfixResponse = request(nicoRequest(
                "GET", exactHlsWithoutPostfix, ""));
        assertEquals(200, exactHlsWithoutPostfixResponse.status,
                "quality-specific HLS status without postfix");
        assertEquals("lower-quality-hls-segment",
                exactHlsWithoutPostfixResponse.bodyText(),
                "quality-specific HLS body without postfix");
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

        exerciseCmafMedia("sm900010", "hlsbid", "audio", "audio-aac-128kbps", "cmfa");
        exerciseCmafMedia("sm900010", "hlsbid", "video", "video-h264-720p", "cmfv");

        Path completed = waitForCompletedCmafCache("sm900010", Duration.ofSeconds(8));
        assertCmafProgressLogHasPositiveSizes("sm900010");
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

    private void testHlsextEncryptedCmafFlow() throws Exception {
        Response watch = request("GET http://www.nicovideo.jp/watch/9000110001 HTTP/1.1\r\n"
                + "Host: www.nicovideo.jp\r\nConnection: close\r\n\r\n");
        assertEquals(200, watch.status, "hlsext watch status");
        assertContains(watch.bodyText(), "sm900011", "hlsext watch body");

        Response accessRights = request("GET http://nvapi.nicovideo.jp/v1/watch/sm900011/access-rights/hls"
                + "?actionTrackId=hlsext HTTP/1.1\r\n"
                + "Host: nvapi.nicovideo.jp\r\nConnection: close\r\n\r\n");
        assertEquals(200, accessRights.status, "hlsext access-rights status");
        assertContains(accessRights.bodyText(), hlsextMasterUrl(), "hlsext contentUrl");

        Response master = request(absoluteRequest(
                hlsextMasterUrl() + "?session=hlsext", "delivery.domand.nicovideo.jp"));
        assertEquals(200, master.status, "hlsext master status");
        assertContains(master.bodyText(), "nicocachenl_domandcvikey=", "hlsext rewritten key");

        exerciseCmafMedia("sm900011", "hlsext", "audio", "audio-aac-128kbps", "cmfa");
        exerciseCmafMedia("sm900011", "hlsext", "video", "video-h264-720p", "cmfv");
        waitForCompletedCmafCache("sm900011", Duration.ofSeconds(8));

        String localBase = "/cache/file/nicocachenl_refcache=sm900011//";
        Response video = request(nicoRequest("GET", localBase + "video/01.cmfv", ""));
        assertEquals(200, video.status, "hlsext decrypted video status");
        assertEquals("video-segment", video.bodyText(), "hlsext decrypted video body");
        Response videoSecond = request(nicoRequest("GET", localBase + "video/02.cmfv", ""));
        assertEquals(200, videoSecond.status, "hlsext second decrypted video status");
        assertEquals("video-segment-2", videoSecond.bodyText(),
                "hlsext second decrypted video body");
        Response audio = request(nicoRequest("GET", localBase + "audio/01.cmfa", ""));
        assertEquals(200, audio.status, "hlsext decrypted audio status");
        assertEquals("audio-segment", audio.bodyText(), "hlsext decrypted audio body");
        Response audioSecond = request(nicoRequest("GET", localBase + "audio/02.cmfa", ""));
        assertEquals(200, audioSecond.status, "hlsext second decrypted audio status");
        assertEquals("audio-segment-2", audioSecond.bodyText(),
                "hlsext second decrypted audio body");
    }

    private void testShlsbidPlaylistRefreshDecryptInfo() throws Exception {
        Response watch = request("GET http://www.nicovideo.jp/watch/9000120001 HTTP/1.1\r\n"
                + "Host: www.nicovideo.jp\r\nConnection: close\r\n\r\n");
        assertEquals(200, watch.status, "shlsbid watch status");

        Response accessRights = request("GET http://nvapi.nicovideo.jp/v1/watch/sm900012/access-rights/hls"
                + "?actionTrackId=shlsbid HTTP/1.1\r\n"
                + "Host: nvapi.nicovideo.jp\r\nConnection: close\r\n\r\n");
        assertEquals(200, accessRights.status, "shlsbid access-rights status");
        assertContains(accessRights.bodyText(), shlsbidMasterUrl(), "shlsbid contentUrl");

        Response master = request(absoluteRequest(
                shlsbidMasterUrl() + "?session=shlsbid", "delivery.domand.nicovideo.jp"));
        assertEquals(200, master.status, "shlsbid master status");

        String sourceId = "audio-aac-128kbps";
        String key = "sm900012" + sourceId;
        String base = cmafBase("shlsbid");
        String internal = "&nicocachenl_domandcvikey=" + key;

        Response firstPlaylist = request(absoluteRequest(base + "/playlists/media/"
                + sourceId + ".m3u8?generation=1" + internal,
                "delivery.domand.nicovideo.jp"));
        assertEquals(200, firstPlaylist.status, "shlsbid first playlist status");
        Response secondPlaylist = request(absoluteRequest(base + "/playlists/media/"
                + sourceId + ".m3u8?generation=2" + internal,
                "delivery.domand.nicovideo.jp"));
        assertEquals(200, secondPlaylist.status, "shlsbid refreshed playlist status");

        Response firstKey = request(absoluteRequest(base + "/keys/" + sourceId
                + ".key?token=k1" + internal, "delivery.domand.nicovideo.jp"));
        assertEquals(16, firstKey.body.length, "shlsbid first key length");
        Response secondKey = request(absoluteRequest(base + "/keys/" + sourceId
                + ".key?token=k2" + internal, "delivery.domand.nicovideo.jp"));
        assertEquals(16, secondKey.body.length, "shlsbid second key length");

        Response init = request(absoluteRequest(base + "/audio/1/" + sourceId
                + "/init001.cmfa?token=i" + internal, "delivery.domand.nicovideo.jp"));
        assertEquals(200, init.status, "shlsbid init status");
        Response firstSegment = request(absoluteRequest(base + "/audio/1/" + sourceId
                + "/01.cmfa?token=c1" + internal, "delivery.domand.nicovideo.jp"));
        assertEquals(200, firstSegment.status, "shlsbid first-generation segment status");

        Path cachedSegment = waitForCmafSegment("sm900012", "audio/01.cmfa",
                Duration.ofSeconds(5));
        assertFileContains(cachedSegment, "audio-segment");
    }

    private void testControlApiContract() throws Exception {
        Properties status = readControlProperties();
        String token = status.getProperty("token");

        Response unauthorized = controlRequest(status, "GET",
                "/api/control/status", null, null);
        assertEquals(401, unauthorized.status,
                "control status without authentication");

        Response authorized = controlRequest(status, "GET",
                "/api/control/status", token, null);
        assertEquals(200, authorized.status, "control status response");
        assertContains(authorized.bodyText(), "\"status\":\"running\"",
                "control status state");
        assertContains(authorized.bodyText(), "\"pid\":",
                "control status pid");
        assertContains(authorized.bodyText(), "\"proxyPort\":",
                "control status proxy port");

        Response ping = controlRequest(status, "GET", "/api/control/ping",
                token, null);
        assertEquals(200, ping.status, "control ping response");
        assertEquals("{\"status\":\"ok\"}", ping.bodyText(),
                "control ping body");

        Response unknown = controlRequest(status, "GET",
                "/api/control/unknown", token, null);
        assertEquals(404, unknown.status, "control unknown endpoint");

        Response wrongMethod = controlRequest(status, "POST",
                "/api/control/status", token, null);
        assertEquals(404, wrongMethod.status, "control method validation");
    }

    private void testControlForceShutdown() throws Exception {
        Properties status = readControlProperties();
        Response forcing = controlRequest(status, "POST",
                "/api/control/force-shutdown", status.getProperty("token"), null);
        assertEquals(202, forcing.status, "control force-shutdown response");
        assertEquals("{\"status\":\"forcing\"}", forcing.bodyText(),
                "control force-shutdown body");
        assertTrue(nicocache.waitFor(STOP_TIMEOUT.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS),
                "control force-shutdown must terminate the process");
    }

    private void testCacheApiContract() throws Exception {
        Response emptyInfo = request(nicoRequest("GET", "/cache/info", ""));
        assertEquals(200, emptyInfo.status, "empty cache info status");
        assertEquals("{}", emptyInfo.bodyText(), "empty cache info response");

        Response infoPost = request(nicoRequestWithBody(
                "POST", "/cache/info", "sm900001,sm900002"));
        assertEquals(200, infoPost.status, "cache info POST status");
        assertContains(infoPost.bodyText(), "\"sm900001\"",
                "cache info POST response");

        Response emptyInfoV2 = request(nicoRequest("GET", "/cache/info/v2", ""));
        assertEquals(200, emptyInfoV2.status, "empty cache info v2 status");
        assertEquals("{}", emptyInfoV2.bodyText(), "empty cache info v2 response");

        Response infoV2Post = request(nicoRequestWithBody(
                "POST", "/cache/info/v2", "sm900001"));
        assertEquals(200, infoV2Post.status, "cache info v2 POST status");
        assertContains(infoV2Post.bodyText(), "\"sm900001\"",
                "cache info v2 POST response");

        Response ajaxInfo = request(nicoRequest(
                "GET", "/cache/ajax_info?sm900001", ""));
        assertEquals(200, ajaxInfo.status, "ajax_info status");
        assertContains(ajaxInfo.bodyText(), "OK", "ajax_info response");
        Response ajaxInfoPost = request(nicoRequest(
                "POST", "/cache/ajax_info?sm900001", ""));
        assertEquals(405, ajaxInfoPost.status, "ajax_info method validation");

        Response oldInfo = request(nicoRequest(
                "GET", "/cache/oldinfo?sm900001", ""));
        assertEquals(200, oldInfo.status, "legacy cache info status");
        assertContains(oldInfo.bodyText(), "\"sm900001\"",
                "legacy cache info response");
        Response oldInfoV2 = request(nicoRequest(
                "GET", "/cache/oldinfo/v2?sm900001", ""));
        assertEquals(200, oldInfoV2.status, "legacy cache info v2 status");
        Response oldInfoPost = request(nicoRequestWithBody(
                "POST", "/cache/oldinfo", "sm900001"));
        assertEquals(200, oldInfoPost.status, "legacy cache info POST status");

        Response echo = request(nicoRequest(
                "GET", "/cache/echo?sm900001%22", ""));
        assertEquals(200, echo.status, "cache echo status");
        assertContains(echo.bodyText(), "sm900001\\\"",
                "cache echo must escape quotes as JSON");
        Response emptyEcho = request(nicoRequest("GET", "/cache/echo", ""));
        assertEquals(400, emptyEcho.status, "cache echo parameter validation");

        Response search = request(nicoRequest(
                "GET", "/cache/search/Api", ""));
        assertEquals(200, search.status, "cache search status");
        assertContains(search.bodyText(), "sm900006",
                "cache search response");
        Response regexSearch = request(nicoRequest(
                "GET", "/cache/rsearch/Api?order=d", ""));
        assertEquals(200, regexSearch.status, "cache regex search status");

        for (String path : List.of("/cache/cachelist.json", "/cache/templist.json",
                "/cache/dirlist.json", "/cache/flvlist.json")) {
            Response list = request(nicoRequest("GET", path, ""));
            assertEquals(200, list.status, path + " status");
            assertContains(list.header("content-type"), "application/json",
                    path + " content type");
        }
        Response ajaxList = request(nicoRequest("GET", "/cache/ajax", ""));
        assertEquals(200, ajaxList.status, "cache ajax list status");
        assertContains(ajaxList.bodyText(), "dirList", "cache ajax list response");
        Response flvList = request(nicoRequest("GET", "/cache/flvlist", ""));
        assertEquals(200, flvList.status, "cache flv list status");
        assertContains(flvList.bodyText(), "# my cache", "cache flv list response");
        Response cachePage = request(nicoRequest("GET", "/cache/", ""));
        assertEquals(200, cachePage.status, "cache management page status");
        Response logPage = request(nicoRequest("GET", "/cache/log", ""));
        assertEquals(200, logPage.status, "cache log page status");
        Response xml = request(nicoRequest(
                "GET", "/cache/getxml?type=dirlist", ""));
        assertEquals(200, xml.status, "cache XML list status");
        assertContains(xml.header("content-type"), "application/xml",
                "cache XML list content type");
        assertContains(xml.bodyText(), "dirList", "cache XML list response");
        for (String type : List.of("templist", "cachelist", "cachelistall")) {
            Response typedXml = request(nicoRequest(
                    "GET", "/cache/getxml?type=" + type, ""));
            assertEquals(200, typedXml.status, "cache XML " + type + " status");
            assertContains(typedXml.header("content-type"), "application/xml",
                    "cache XML " + type + " content type");
        }

        Response title = request(nicoRequest(
                "GET", "/cache/ajax_title?sm900017-API%20title", ""));
        assertEquals(200, title.status, "cache title status");
        assertContains(title.bodyText(), "OK", "cache title response");
        Response move = request(nicoRequest(
                "GET", "/cache/ajax_move?sm900015-/api-target", ""));
        assertEquals(200, move.status, "cache move status");
        assertEquals("OK", move.bodyText(), "cache move response");
        assertTrue(Files.exists(sandbox.resolve("cache/api-target/sm900015_Api.mp4")),
                "cache move destination");
        Response topMove = request(nicoRequest(
                "GET", "/cache/ajax_topmove?sm900016-/api-target", ""));
        assertEquals(200, topMove.status, "cache topmove status");
        assertEquals("OK", topMove.bodyText(), "cache topmove response");
        assertTrue(Files.exists(sandbox.resolve("cache/api-target/sm900016_Api.mp4")),
                "cache topmove destination");

        Response addList = request(nicoRequest(
                "GET", "/cache/ajax_addlist/api.txt?gamma", ""));
        assertEquals(200, addList.status, "cache addlist status");
        assertEquals("OK", addList.bodyText(), "cache addlist response");
        Response trimList = request(nicoRequest(
                "GET", "/cache/ajax_trimlist/api.txt?type=smid", ""));
        assertEquals(200, trimList.status, "cache trimlist status");
        assertEquals("OK", trimList.bodyText(), "cache trimlist response");
        Response unsafeList = request(nicoRequest(
                "GET", "/cache/ajax_addlist/../escaped.txt?bad", ""));
        assertEquals(400, unsafeList.status, "cache list path validation");
        assertFalse(Files.exists(sandbox.resolve("escaped.txt")),
                "unsafe cache list path must not write outside list");

        Response invalidInfo = request(nicoRequest(
                "GET", "/cache/info?not-an-id", ""));
        assertEquals(400, invalidInfo.status, "cache info invalid parameter");
        Response invalidRemove = request(nicoRequest("GET", "/cache/ajax_rm", ""));
        assertEquals(400, invalidRemove.status, "cache rm invalid parameter");
        Response invalidTempRemove = request(nicoRequest(
                "GET", "/cache/ajax_rmtmp?not-an-id", ""));
        assertEquals(400, invalidTempRemove.status, "cache rmtmp invalid parameter");
        Response invalidAllRemove = request(nicoRequest(
                "GET", "/cache/ajax_rmall?sm900001[720p,128]", ""));
        assertEquals(400, invalidAllRemove.status, "cache rmall invalid parameter");
        Response wrongRemoveMethod = request(nicoRequest(
                "POST", "/cache/ajax_rm?sm900001", ""));
        assertEquals(405, wrongRemoveMethod.status, "cache rm method validation");

        Response rm = request(nicoRequest(
                "GET", "/cache/ajax_rm?sm900006", ""));
        assertEquals(200, rm.status, "cache ajax_rm status");
        assertEquals("OK", rm.bodyText(), "cache ajax_rm response");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900006_Api.mp4")),
                "cache ajax_rm deletion");

        Response rmtmp = request(nicoRequest(
                "GET", "/cache/ajax_rmtmp?sm900007", ""));
        assertEquals(200, rmtmp.status, "cache ajax_rmtmp status");
        assertEquals("OK", rmtmp.bodyText(), "cache ajax_rmtmp response");
        assertFalse(Files.exists(sandbox.resolve("cache/nltmp_sm900007_Api.mp4")),
                "cache ajax_rmtmp deletion");

        Response rmall = request(nicoRequest(
                "GET", "/cache/ajax_rmall?sm900008", ""));
        assertEquals(200, rmall.status, "cache ajax_rmall status");
        assertEquals("OK", rmall.bodyText(), "cache ajax_rmall response");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900008_Api.mp4")),
                "cache ajax_rmall deletion");

        Response redirectRm = request(nicoRequest(
                "GET", "/cache/rm?sm900009", ""));
        assertEquals(302, redirectRm.status, "cache rm redirect status");
        assertEquals("http://www.nicovideo.jp/", redirectRm.header("location"),
                "cache rm redirect target");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900009_Api.mp4")),
                "cache rm deletion");
        Response redirectRmtmp = request(nicoRequest(
                "GET", "/cache/rmtmp?sm900013", ""));
        assertEquals(302, redirectRmtmp.status, "cache rmtmp redirect status");
        assertFalse(Files.exists(sandbox.resolve("cache/nltmp_sm900013_Api.mp4")),
                "cache rmtmp deletion");
        Response redirectRmall = request(nicoRequest(
                "GET", "/cache/rmall?sm900014", ""));
        assertEquals(302, redirectRmall.status, "cache rmall redirect status");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900014_Api.mp4")),
                "cache rmall deletion");

        Response legacyRemove = request(nicoRequest(
                "GET", "/cache/oldrm?sm900018", ""));
        assertEquals(302, legacyRemove.status, "legacy cache rm redirect status");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900018_Api.mp4")),
                "legacy cache rm deletion");
        Response legacyRemoveAll = request(nicoRequest(
                "GET", "/cache/oldrmall?sm900019", ""));
        assertEquals(302, legacyRemoveAll.status, "legacy cache rmall redirect status");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900019_Api.mp4")),
                "legacy cache rmall deletion");

        Response legacyTempRemove = request(nicoRequest(
                "GET", "/cache/oldrmtmp?sm900021", ""));
        assertEquals(302, legacyTempRemove.status,
                "legacy cache rmtmp redirect status");
        assertFalse(Files.exists(sandbox.resolve("cache/nltmp_sm900021_Api.mp4")),
                "legacy cache rmtmp deletion");

        for (String id : List.of("sm900022", "sm900023", "sm900024")) {
            String operation = id.equals("sm900022") ? "oldrm"
                    : id.equals("sm900023") ? "oldrmtmp" : "oldrmall";
            Response ajaxLegacy = request(nicoRequest(
                    "GET", "/cache/ajax_" + operation + "?" + id, ""));
            assertEquals(200, ajaxLegacy.status,
                    "ajax legacy " + operation + " status");
            assertEquals("OK", ajaxLegacy.bodyText(),
                    "ajax legacy " + operation + " response");
        }
        assertFalse(Files.exists(sandbox.resolve("cache/sm900022_Api.mp4")),
                "ajax legacy cache rm deletion");
        assertFalse(Files.exists(sandbox.resolve("cache/nltmp_sm900023_Api.mp4")),
                "ajax legacy cache rmtmp deletion");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900024_Api.mp4")),
                "ajax legacy cache rmall deletion");
    }

    private Path waitForCmafSegment(String smid, String relativePath, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (var caches = Files.list(sandbox.resolve("cache"))) {
                Path cache = caches
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("nltmp_" + smid))
                        .findFirst().orElse(null);
                if (cache != null) {
                    Path segment = cache.resolve(relativePath);
                    if (Files.isRegularFile(segment)) {
                        return segment;
                    }
                }
            }
            Thread.sleep(50L);
        }
        throw new AssertionError(smid + " segment was not decrypted: " + relativePath);
    }

    private Path waitForCompletedCmafCache(String smid, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (var caches = Files.list(sandbox.resolve("cache"))) {
                Path completed = caches
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith(smid))
                        .filter(path -> path.getFileName().toString().endsWith(".hls"))
                        .findFirst().orElse(null);
                if (completed != null) {
                    return completed;
                }
            }
            Thread.sleep(50L);
        }
        throw new AssertionError(smid + " CMAF cache was not completed after all encrypted segments");
    }

    private void assertCmafProgressLogHasPositiveSizes(String smid) throws IOException {
        String prefix = "caching " + smid + ": ";
        boolean found = false;
        String log = new String(Files.readAllBytes(sandbox.resolve("nicocache-functional.log")),
                StandardCharsets.ISO_8859_1);
        for (String line : log.split("\\R")) {
            int start = line.indexOf(prefix);
            if (start < 0) {
                continue;
            }
            found = true;
            start += prefix.length();
            int end = line.indexOf(" (", start);
            if (end < 0) {
                throw new AssertionError("CMAF progress size is malformed: " + line);
            }
            String sizeText = line.substring(start, end).trim();
            double size = Double.parseDouble(sizeText.substring(0, sizeText.indexOf(' ')));
            assertFalse(size <= 0, "CMAF progress size must be positive: " + line);
        }
        assertFalse(!found, "CMAF progress log is missing: " + smid);
    }

    private void exerciseCmafMedia(String smid, String route, String mediaType,
            String sourceId, String extension)
            throws Exception {
        String key = smid + sourceId;
        String base = cmafBase(route);
        String query = "?token=functional&nicocachenl_domandcvikey=" + key;
        boolean rotating = "hlsext".equals(route);

        Response playlist = request(absoluteRequest(base + "/playlists/media/"
                + sourceId + ".m3u8" + query, "delivery.domand.nicovideo.jp"));
        assertEquals(200, playlist.status, "CMAF " + mediaType + " playlist status");
        assertContains(playlist.bodyText(), "nicocachenl_domandcvikey=" + key,
                "CMAF " + mediaType + " playlist URL injection");

        String firstKeyQuery = (rotating ? "?token=k1" : "?token=k")
                + "&nicocachenl_domandcvikey=" + key;
        Response keyResponse = request(absoluteRequest(base + "/keys/" + sourceId
                + ".key" + firstKeyQuery, "delivery.domand.nicovideo.jp"));
        assertEquals(200, keyResponse.status, "CMAF " + mediaType + " key status");
        assertEquals(16, keyResponse.body.length, "CMAF " + mediaType + " key length");
        assertEquals(0x2a, keyResponse.body[0] & 0xff,
                "CMAF " + mediaType + " first key value");
        if ("hlsbid".equals(route)) {
            Response duplicateKeyResponse = request(absoluteRequest(base + "/keys/" + sourceId
                    + ".key" + firstKeyQuery, "delivery.domand.nicovideo.jp"));
            assertEquals(200, duplicateKeyResponse.status,
                    "CMAF " + mediaType + " duplicate key status");
            assertEquals(16, duplicateKeyResponse.body.length,
                    "CMAF " + mediaType + " duplicate key length");
            assertEquals(0x2b, duplicateKeyResponse.body[0] & 0xff,
                    "CMAF " + mediaType + " duplicate key value");
        }
        if (rotating) {
            Response secondKeyResponse = request(absoluteRequest(base + "/keys/" + sourceId
                    + ".key?token=k2&nicocachenl_domandcvikey=" + key,
                    "delivery.domand.nicovideo.jp"));
            assertEquals(200, secondKeyResponse.status,
                    "CMAF " + mediaType + " second key status");
            assertEquals(16, secondKeyResponse.body.length,
                    "CMAF " + mediaType + " second key length");
        }

        Response init = request(absoluteRequest(base + "/" + mediaType + "/1/"
                + sourceId + "/init001." + extension + "?token=i"
                + "&nicocachenl_domandcvikey=" + key,
                "delivery.domand.nicovideo.jp"));
        assertEquals(200, init.status, "CMAF " + mediaType + " init chunk status");
        assertContains(init.bodyText(), mediaType + "-init", "CMAF " + mediaType + " init chunk");

        String segmentRequest = absoluteRequest(base + "/" + mediaType + "/1/"
                + sourceId + "/01." + extension + "?token=c1"
                + "&nicocachenl_domandcvikey=" + key,
                "delivery.domand.nicovideo.jp");
        boolean duplicateRequest = rotating || "hlsbid".equals(route);
        if (duplicateRequest) {
            Response[] duplicateSegments = requestConcurrently(segmentRequest);
            String segmentPath = URI.create(base + "/" + mediaType + "/1/"
                    + sourceId + "/01." + extension).getPath();
            assertEquals(2, upstreamRequestCount(segmentPath),
                    "CMAF duplicate media segment upstream request count");
            for (Response duplicateSegment : duplicateSegments) {
                assertEquals(200, duplicateSegment.status,
                        "CMAF " + mediaType + " duplicate media segment status");
                assertFalse(duplicateSegment.bodyText().contains(mediaType + "-segment"),
                        "upstream duplicate CMAF media segment must remain encrypted on the wire");
            }
        } else {
            Response segment = request(segmentRequest);
            assertEquals(200, segment.status, "CMAF " + mediaType + " media segment status");
            assertFalse(segment.bodyText().contains(mediaType + "-segment"),
                    "upstream CMAF media segment must remain encrypted on the wire");
        }
        if (rotating) {
            Response secondSegment = request(absoluteRequest(base + "/" + mediaType + "/1/"
                    + sourceId + "/02." + extension + "?token=c2"
                    + "&nicocachenl_domandcvikey=" + key,
                    "delivery.domand.nicovideo.jp"));
            assertEquals(200, secondSegment.status,
                    "CMAF " + mediaType + " second media segment status");
            assertFalse(secondSegment.bodyText().contains(mediaType + "-segment"),
                    "upstream CMAF second media segment must remain encrypted on the wire");
        }
    }

    private Response[] requestConcurrently(String requestText) throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try {
            Future<Response> first = clients.submit(() -> request(requestText));
            Future<Response> second = clients.submit(() -> request(requestText));
            return new Response[] { first.get(), second.get() };
        } finally {
            clients.shutdownNow();
        }
    }

    private static String absoluteRequest(String url, String host) {
        return "GET " + url + " HTTP/1.1\r\nHost: " + host
                + "\r\nConnection: close\r\n\r\n";
    }

    private static String cmafMasterUrl() {
        return cmafBase("hlsbid") + "/playlists/variants/bbbbbbbbbbbbbbbb.m3u8";
    }

    private static String hlsextMasterUrl() {
        return cmafBase("hlsext") + "/playlists/variants/cccccccccccccccc.m3u8";
    }

    private static String shlsbidMasterUrl() {
        return cmafBase("shlsbid") + "/playlists/variants/eeeeeeeeeeeeeeee.m3u8";
    }

    private static String cmafBase(String route) {
        return "http://delivery.domand.nicovideo.jp/" + route + "/"
                + ("hlsext".equals(route) ? "dddddddddddddddddddddddd"
                        : "shlsbid".equals(route) ? "eeeeeeeeeeeeeeeeeeeeeeee"
                        : "aaaaaaaaaaaaaaaaaaaaaaaa");
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

        for (String id : List.of("sm900002", "sm900003", "sm900004", "sm900005")) {
            Response legacyRemoved = request(nicoRequest("GET", "/cache/ajax_rmall?" + id, ""));
            assertEquals(200, legacyRemoved.status, id + " removal status");
            assertEquals("OK", legacyRemoved.bodyText(), id + " removal response");
        }
        assertFalse(Files.exists(sandbox.resolve("cache/sm900002[720p,128]_Functional.mp4")),
                "DMC MP4 cache must be removed");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900003[720p,128]_Functional.hls")),
                "legacy HLS cache must be removed");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900003[360p,64]_Functional.hls")),
                "lower-quality HLS cache must be removed");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900004_Functional.flv")),
                "FLV cache must be removed");
        assertFalse(Files.exists(sandbox.resolve("cache/sm900005_Functional.swf")),
                "SWF cache must be removed");
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
            if (apiOnly) {
                requestControlGracefulShutdown();
            } else {
                request(nicoRequest("GET", "/functional/stop", ""));
            }
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

    private void requestControlGracefulShutdown() throws Exception {
        Properties status = readControlProperties();
        Response response = controlRequest(status, "POST",
                "/api/control/graceful-shutdown", status.getProperty("token"), null);
        if (response.status != 202 && response.status != 200) {
            throw new AssertionError(
                    "control graceful shutdown status: " + response.status);
        }
        assertContains(response.bodyText(), "\"status\":\"stopping\"",
                "control graceful shutdown response");
    }

    private Properties readControlProperties() throws IOException {
        Properties status = new Properties();
        try (InputStream input = Files.newInputStream(
                sandbox.resolve("data/nicocache-control.properties"))) {
            status.load(input);
        }
        return status;
    }

    private Response controlRequest(Properties status, String method, String path,
            String token, String body) throws Exception {
        byte[] bodyBytes = body == null
                ? new byte[0]
                : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1\r\n");
        if (token != null) {
            request.append("Authorization: Bearer ").append(token).append("\r\n");
        }
        request.append("Content-Length: ").append(bodyBytes.length)
                .append("\r\nConnection: close\r\n\r\n");

        int controlPort = Integer.parseInt(status.getProperty("port"));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    controlPort), 2000);
            socket.setSoTimeout(8000);
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            output.write(bodyBytes);
            output.flush();
            return Response.parse(readHttpResponse(socket.getInputStream(), false));
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

    private static String nicoRequestWithBody(String method, String path, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return method + " http://www.nicovideo.jp" + path + " HTTP/1.1\r\n"
                + "Host: www.nicovideo.jp\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Connection: close\r\n\r\n" + body;
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
        executedTests++;
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

    private static void assertTrue(boolean actual, String message) {
        if (!actual) {
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
