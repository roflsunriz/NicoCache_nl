package dareka.processor.impl;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.Main;
import dareka.common.json.Json;
import dareka.common.json.JsonArray;
import dareka.common.json.JsonObject;
import dareka.common.json.JsonString;
import dareka.common.json.JsonValue;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.LocalFileResource;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.impl.CacheDirProcessor.Specifier;
import dareka.processor.util.AudioExtractor;
import dareka.processor.util.Hls2SingleConverter;

/** NicoCache_nl専用仮想ホストのWeb UIとREST API。 */
public final class NicoCacheWebProcessor implements Processor {
    public static final String HOST = "nicocachenl.test";
    private static final String API_PREFIX = "/api/v1";
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^https?://" + Pattern.quote(HOST) + "(?:/|$)");
    private static final Pattern VIDEO_ROUTE = Pattern.compile(
            "^/api/v1/videos/([a-z]{2}[0-9]+)/(cache-entries|temporary-cache-entries|media|exports/(video|audio|comments))$");
    private static final Pattern ENTRY_ROUTE = Pattern.compile(
            "^/api/v1/(temporary-)?cache-entries/(.+)$");
    private static final Pattern SNAPSHOT_ROUTE = Pattern.compile(
            "^/api/v1/diagnostic-snapshots/([0-9a-f-]+)(/thread-dump)?$");
    private static final String[] SUPPORTED_METHODS = { null };

    private static final Map<String, DiagnosticRecord> SNAPSHOTS =
            Collections.synchronizedMap(new LinkedHashMap<String, DiagnosticRecord>() {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, DiagnosticRecord> eldest) {
                    return size() > 8;
                }
            });

    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return URL_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    @Override
    public Resource onRequest(HttpRequestHeader request, Socket browser)
            throws IOException {
        if (browser == null || !browser.getInetAddress().isLoopbackAddress()) {
            return error(403, "Forbidden", "loopback_required",
                    "NicoCache_nlの管理サイトはローカル接続専用です");
        }

        String path = request.getPathWithoutQuery();
        if (path == null) {
            return error(400, "Bad Request", "invalid_path", "パスが不正です");
        }
        if (path.startsWith(API_PREFIX)) {
            return processApi(request, browser, path);
        }
        return processPage(request, path);
    }

    private Resource processPage(HttpRequestHeader request, String path)
            throws IOException {
        if (!request.isGetMethod() && !request.isHeadMethod()) {
            return methodNotAllowed("GET, HEAD");
        }

        String localPath;
        if ("/assets/app.js".equals(path)) {
            localPath = "nicocache-web/app.js";
        } else if ("/assets/styles.css".equals(path)) {
            localPath = "nicocache-web/styles.css";
        } else if ("/".equals(path) || "/cache".equals(path)
                || "/health".equals(path) || "/diagnostics".equals(path)
                || "/diagnostics/threads".equals(path)
                || path.matches("/videos/[a-z]{2}[0-9]+")) {
            localPath = "nicocache-web/index.html";
        } else {
            return error(404, "Not Found", "page_not_found",
                    "ページが見つかりません");
        }

        File file = LocalDirProcessor.getLocalFile(localPath, null);
        if (file == null || !file.isFile()) {
            return error(404, "Not Found", "asset_not_found",
                    "管理サイトのファイルが見つかりません");
        }
        return new LocalFileResource(file);
    }

    private Resource processApi(HttpRequestHeader request, Socket browser,
            String path) throws IOException {
        if ("/api/v1/health/live".equals(path)) {
            return requireGet(request, json(200, "OK",
                    "{\"status\":\"ok\"}"));
        }
        if ("/api/v1/health/ready".equals(path)) {
            return requireGet(request, json(200, "OK",
                    "{\"status\":\"ready\",\"version\":"
                    + quote(Main.getVersion()) + "}"));
        }
        if ("/api/v1/diagnostics/runtime".equals(path)) {
            return requireGet(request, json(200, "OK",
                    captureRuntime()));
        }
        if ("/api/v1/diagnostic-snapshots".equals(path)) {
            if (!request.isPostMethod()) {
                return methodNotAllowed("POST");
            }
            String id = UUID.randomUUID().toString();
            String snapshotJson = captureSnapshot();
            JsonObject parsed = Json.parseObject(snapshotJson);
            String threadDump = parsed == null ? null : parsed.getString("threadDump");
            if (threadDump == null) {
                return error(500, "Internal Server Error", "snapshot_failed",
                        "診断スナップショットを作成できませんでした");
            }
            SNAPSHOTS.put(id, new DiagnosticRecord(snapshotJson, threadDump));
            Resource response = json(201, "Created",
                    "{\"id\":" + quote(id) + ",\"location\":"
                    + quote(API_PREFIX + "/diagnostic-snapshots/" + id) + "}");
            response.setResponseHeader("Location",
                    API_PREFIX + "/diagnostic-snapshots/" + id);
            return response;
        }

        Matcher snapshot = SNAPSHOT_ROUTE.matcher(path);
        if (snapshot.matches()) {
            if (!request.isGetMethod()) {
                return methodNotAllowed("GET");
            }
            DiagnosticRecord record = SNAPSHOTS.get(snapshot.group(1));
            if (record == null) {
                return error(404, "Not Found", "snapshot_not_found",
                        "診断スナップショットが見つかりません");
            }
            return snapshot.group(2) == null
                    ? json(200, "OK", record.json)
                    : text(200, "OK", record.threadDump, "text/plain; charset=UTF-8");
        }

        if ("/api/v1/cache-entry-queries".equals(path)) {
            if (!request.isPostMethod()) {
                return methodNotAllowed("POST");
            }
            return processCacheEntryQuery(request, browser);
        }
        if ("/api/v1/cache-entries".equals(path)) {
            if (!request.isGetMethod()) {
                return methodNotAllowed("GET");
            }
            return processCacheEntries(request);
        }
        if ("/api/v1/cache-directories".equals(path)) {
            return requireGet(request, json(200, "OK", Cache.getDirListAsJson()));
        }

        Matcher videoRoute = VIDEO_ROUTE.matcher(path);
        if (videoRoute.matches()) {
            return processVideoRoute(request, videoRoute.group(1),
                    videoRoute.group(2));
        }
        Matcher entryRoute = ENTRY_ROUTE.matcher(path);
        if (entryRoute.matches()) {
            return processEntryRoute(request, entryRoute.group(1) != null,
                    decodePathSegment(entryRoute.group(2)));
        }

        return error(404, "Not Found", "api_not_found",
                "APIエンドポイントが見つかりません");
    }

    private Resource processCacheEntryQuery(HttpRequestHeader request,
            Socket browser) throws IOException {
        String body = Main.getRewriterProcessor().getPostString(
                request, browser, null);
        JsonObject object = Json.parseObject(body);
        JsonArray videoIds = object == null ? null : object.getArray("videoIds");
        if (videoIds == null || videoIds.size() > 256) {
            return error(400, "Bad Request", "invalid_video_ids",
                    "videoIdsは256件以下の配列で指定してください");
        }

        JsonObject response = new JsonObject();
        for (JsonValue value : videoIds.getList()) {
            if (!(value instanceof JsonString)) {
                return error(400, "Bad Request", "invalid_video_id",
                        "videoIdsには動画ID文字列だけを指定してください");
            }
            String videoId = ((JsonString) value).value();
            if (!isVideoId(videoId)) {
                return error(400, "Bad Request", "invalid_video_id",
                        "動画IDが不正です: " + videoId);
            }
            response.put(videoId, CmafCacheInfo.create(videoId));
        }
        return json(200, "OK", response.toJson());
    }

    private Resource processCacheEntries(HttpRequestHeader request) {
        String query = request.getParameter("query");
        String state = request.getParameter("state");
        if (query != null && !query.isBlank()) {
            String decoded = URLDecoder.decode(query, StandardCharsets.UTF_8);
            boolean descending = "desc".equalsIgnoreCase(
                    request.getParameter("order"));
            boolean regex = "regex".equalsIgnoreCase(
                    request.getParameter("mode"));
            StringBuilder output = new StringBuilder("{\n");
            for (VideoDescriptor video : SearchRewriter.searchVideo(
                    decoded, regex, descending).keySet()) {
                if (Cache.writeJSON(video, output)) {
                    output.append(",\n");
                }
            }
            finishJsonObject(output);
            return json(200, "OK", output.toString());
        }
        if ("complete".equalsIgnoreCase(state)) {
            return json(200, "OK", Cache.getCacheListAsJson());
        }
        if ("temporary".equalsIgnoreCase(state)) {
            return json(200, "OK", Cache.getTempListAsJson());
        }
        return json(200, "OK", "{\"complete\":"
                + Cache.getCacheListAsJson() + ",\"temporary\":"
                + Cache.getTempListAsJson() + "}");
    }

    private Resource processVideoRoute(HttpRequestHeader request,
            String videoId, String action) throws IOException {
        if ("cache-entries".equals(action)) {
            if (request.isGetMethod()) {
                return json(200, "OK", CmafCacheInfo.create(videoId).toJson());
            }
            if (request.isDeleteMethod()) {
                boolean removed = Cache.removeAll(videoId);
                return deletionResult(videoId, removed, false);
            }
            return methodNotAllowed("GET, DELETE");
        }
        if ("temporary-cache-entries".equals(action)) {
            if (!request.isDeleteMethod()) {
                return methodNotAllowed("DELETE");
            }
            boolean accepted = Cache.removeTmpAll(videoId);
            return deletionResult(videoId, accepted,
                    Cache.isTemporaryDeletionPending(videoId));
        }
        if (!request.isGetMethod()) {
            return methodNotAllowed("GET");
        }
        if ("media".equals(action)) {
            return media(videoId);
        }
        return export(request, videoId, action.substring("exports/".length()));
    }

    private Resource processEntryRoute(HttpRequestHeader request,
            boolean temporary, String cacheId) {
        if (!request.isDeleteMethod()) {
            return methodNotAllowed("DELETE");
        }
        Specifier specifier = new Specifier(cacheId);
        if (specifier.video == null || !specifier.altid.equals(cacheId)) {
            return error(400, "Bad Request", "invalid_cache_entry_id",
                    "キャッシュIDが不正です");
        }
        boolean removed = temporary
                ? Cache.removeTmp(specifier.video) : Cache.remove(specifier.video);
        if (!removed) {
            return error(404, "Not Found", "cache_entry_not_found",
                    "対象のキャッシュが見つからないか削除できませんでした");
        }
        return json(200, "OK", "{\"cacheEntryId\":" + quote(cacheId)
                + ",\"status\":\"deleted\"}");
    }

    private Resource export(HttpRequestHeader request, String videoId,
            String kind) throws IOException {
        if ("comments".equals(kind)) {
            WatchVars watchVars = WatchVars.get(videoId);
            if (watchVars == null) {
                return error(409, "Conflict", "watch_context_required",
                        "視聴ページを再読み込みしてから再試行してください");
            }
            try {
                Resource resource = NvCommentDownloader.getResource(
                        videoId, watchVars, request);
                return resource == null
                        ? error(409, "Conflict", "comment_context_unavailable",
                                "コメント取得情報がありません")
                        : resource;
            } catch (IOException error) {
                return error(502, "Bad Gateway", "comment_upstream_failed",
                        "コメント取得に失敗しました");
            }
        }

        VideoDescriptor video = Cache.getPreferredCachedVideo(videoId);
        if (video == null) {
            return error(404, "Not Found", "completed_cache_not_found",
                    "完成済みキャッシュが見つかりません");
        }
        Cache cache = new Cache(video);
        if (!cache.exists()) {
            return error(404, "Not Found", "completed_cache_not_found",
                    "完成済みキャッシュが見つかりません");
        }
        String userAgent = request.getMessageHeader("User-Agent");
        if ("audio".equals(kind)) {
            String extension = Cache.FLV.equals(cache.getPostfix())
                    || Cache.SWF.equals(cache.getPostfix()) ? ".mp3" : ".m4a";
            return new AudioExtractor(cache, videoId + extension, userAgent).extract();
        }
        if (!"video".equals(kind)) {
            return error(404, "Not Found", "export_not_found",
                    "エクスポート形式が見つかりません");
        }

        Resource resource;
        String downloadName;
        if (Cache.HLS.equals(cache.getPostfix())) {
            downloadName = videoId + ".mp4";
            resource = new Hls2SingleConverter(cache, downloadName, userAgent).convert();
        } else {
            downloadName = Cache.videoDescriptorToAltId(video);
            resource = new LocalFileResource(cache.getCacheFile());
            LimitFlvSpeedListener.addTo(resource);
        }
        if (resource instanceof LocalFileResource) {
            AudioExtractor.setContentDisposition(
                    resource, cache, userAgent, downloadName);
        }
        return resource;
    }

    private Resource media(String videoId) throws IOException {
        VideoDescriptor video = Cache.getPreferredCachedVideo(videoId);
        if (video == null) {
            return error(404, "Not Found", "completed_cache_not_found",
                    "完成済みキャッシュが見つかりません");
        }
        Cache cache = new Cache(video);
        if (!cache.exists()) {
            return error(404, "Not Found", "completed_cache_not_found",
                    "完成済みキャッシュが見つかりません");
        }
        if (Cache.HLS.equals(cache.getPostfix())) {
            return new Hls2SingleConverter(cache, videoId + ".mp4", null).convert();
        }
        Resource resource = new LocalFileResource(cache.getCacheFile());
        LimitFlvSpeedListener.addTo(resource);
        return resource;
    }

    private static Resource deletionResult(String videoId, boolean accepted,
            boolean pending) {
        if (!accepted) {
            return json(200, "OK", "{\"videoId\":" + quote(videoId)
                    + ",\"status\":\"not_found\"}");
        }
        return json(pending ? 202 : 200, pending ? "Accepted" : "OK",
                "{\"videoId\":" + quote(videoId) + ",\"status\":"
                + quote(pending ? "scheduled" : "deleted") + "}");
    }

    private static String captureRuntime() {
        Runtime runtime = Runtime.getRuntime();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        java.lang.management.MemoryMXBean memoryBean =
                ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
        long[] deadlocked = threadBean.findDeadlockedThreads();
        JsonObject result = new JsonObject()
                .put("capturedAt", new JsonString(Instant.now().toString()))
                .put("state", new JsonString("running"))
                .put("problem", new JsonString(""))
                .put("pid", new dareka.common.json.JsonNumber(
                        ProcessHandle.current().pid()))
                .put("uptimeMillis", new dareka.common.json.JsonNumber(
                        runtimeBean.getUptime()))
                .put("processors", new dareka.common.json.JsonNumber(
                        runtime.availableProcessors()))
                .put("heapUsed", new dareka.common.json.JsonNumber(heap.getUsed()))
                .put("heapCommitted", new dareka.common.json.JsonNumber(
                        heap.getCommitted()))
                .put("heapMax", new dareka.common.json.JsonNumber(heap.getMax()))
                .put("nonHeapUsed", new dareka.common.json.JsonNumber(
                        nonHeap.getUsed()))
                .put("threadCount", new dareka.common.json.JsonNumber(
                        threadBean.getThreadCount()))
                .put("peakThreadCount", new dareka.common.json.JsonNumber(
                        threadBean.getPeakThreadCount()))
                .put("daemonThreadCount", new dareka.common.json.JsonNumber(
                        threadBean.getDaemonThreadCount()))
                .put("deadlockedThreadCount", new dareka.common.json.JsonNumber(
                        deadlocked == null ? 0 : deadlocked.length))
                .put("javaVersion", new JsonString(
                        System.getProperty("java.version", "unknown")))
                .put("osName", new JsonString(
                        System.getProperty("os.name", "unknown")))
                .put("osArch", new JsonString(
                        System.getProperty("os.arch", "unknown")));
        return result.toJson();
    }

    private static String captureSnapshot() {
        JsonObject runtime = Json.parseObject(captureRuntime());
        runtime.put("threadDump", new JsonString(captureThreadDump()));
        return runtime.toJson();
    }

    private static String captureThreadDump() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        StringBuilder output = new StringBuilder(64 * 1024);
        for (ThreadInfo info : threads.dumpAllThreads(true, true)) {
            if (info == null) {
                continue;
            }
            output.append('"').append(info.getThreadName()).append("\" Id=")
                    .append(info.getThreadId()).append(' ')
                    .append(info.getThreadState()).append('\n');
            if (info.getLockInfo() != null) {
                output.append("\t- waiting on ").append(info.getLockInfo())
                        .append('\n');
            }
            for (StackTraceElement frame : info.getStackTrace()) {
                output.append("\tat ").append(frame).append('\n');
            }
            for (java.lang.management.LockInfo lock
                    : info.getLockedSynchronizers()) {
                output.append("\t- locked synchronizer ").append(lock)
                        .append('\n');
            }
            output.append('\n');
        }
        long[] deadlocked = threads.findDeadlockedThreads();
        output.append("Deadlocked thread count: ")
                .append(deadlocked == null ? 0 : deadlocked.length).append('\n');
        return output.toString();
    }

    private static Resource requireGet(HttpRequestHeader request,
            Resource response) {
        return request.isGetMethod() ? response : methodNotAllowed("GET");
    }

    private static Resource methodNotAllowed(String allowed) {
        Resource response = error(405, "Method Not Allowed",
                "method_not_allowed", "HTTPメソッドが許可されていません");
        response.setResponseHeader("Allow", allowed);
        return response;
    }

    private static Resource error(int status, String reason, String code,
            String message) {
        return json(status, reason, "{\"error\":{\"code\":" + quote(code)
                + ",\"message\":" + quote(message) + "}}");
    }

    private static Resource json(int status, String reason, String body) {
        return text(status, reason, body, "application/json; charset=UTF-8");
    }

    private static Resource text(int status, String reason, String body,
            String contentType) {
        Resource response = new StatusStringResource(status, reason, body);
        response.setResponseHeader(HttpHeader.CONTENT_TYPE, contentType);
        response.setResponseHeader("Cache-Control", "no-store");
        return response;
    }

    private static String decodePathSegment(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            return "";
        }
    }

    private static boolean isVideoId(String value) {
        return value != null && value.matches("[a-z]{2}[0-9]+");
    }

    private static void finishJsonObject(StringBuilder output) {
        if (output.length() > 2) {
            output.replace(output.length() - 2, output.length(), "\n}");
        } else {
            output.replace(0, output.length(), "{}");
        }
    }

    private static String quote(String value) {
        return new JsonString(value == null ? "" : value).toJson();
    }

    private static final class DiagnosticRecord {
        private final String json;
        private final String threadDump;

        private DiagnosticRecord(String json, String threadDump) {
            this.json = json;
            this.threadDump = threadDump;
        }
    }

    private static final class StatusStringResource extends StringResource {
        private StatusStringResource(int status, String reason, String body) {
            super("HTTP/1.1 " + status + " " + reason, body);
        }
    }
}
