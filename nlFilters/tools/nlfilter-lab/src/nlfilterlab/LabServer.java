package nlfilterlab;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LabServer {
    private static final int MAX_RENDER_BODY = 1024 * 1024;
    private static final int MAX_LOG_BODY = 64 * 1024;
    private static final String PREVIEW_CSP = "default-src 'self' data:; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; " +
            "form-action 'none'; frame-ancestors 'self'";

    private final Path repositoryRoot;
    private final Path labRoot;
    private final Path webRoot;
    private final Path fixtureRoot;
    private final Path localRoot;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final FilterParser parser = new FilterParser();
    private final FilterEngine engine;
    private final List<Path> trackedFilters;
    private final Map<String, Path> filtersByName = new LinkedHashMap<>();
    private final Map<String, ParserConformance.Report> conformanceByName = new LinkedHashMap<>();
    private final Map<String, Fixture> fixtures = new LinkedHashMap<>();
    private final Map<String, Preview> previews = new ConcurrentHashMap<>();

    LabServer(Path repositoryRoot, Path labRoot, int port) throws Exception {
        this.repositoryRoot = repositoryRoot;
        this.labRoot = labRoot;
        this.webRoot = labRoot.resolve("web").normalize();
        this.fixtureRoot = labRoot.resolve("fixtures").normalize();
        this.localRoot = repositoryRoot.getParent().resolve("local").normalize();
        this.engine = new FilterEngine(repositoryRoot);
        this.trackedFilters = RepositoryFilters.tracked(repositoryRoot);
        for (Path path : trackedFilters) {
            filtersByName.put(path.getFileName().toString(), path);
            conformanceByName.put(path.getFileName().toString(),
                    ParserConformance.compare(repositoryRoot, path, parser.parse(path)));
        }
        fixtures.put("watch", new Fixture("watch", "視聴ページ", "watch.html",
                "https://www.nicovideo.jp/watch/sm9", "text/html"));
        fixtures.put("search", new Fixture("search", "検索・タグページ", "search.html",
                "https://www.nicovideo.jp/search/nlfilter", "text/html"));
        fixtures.put("anime", new Fixture("anime", "Nアニメ FREE", "anime.html",
                "https://anime.nicovideo.jp/free/", "text/html"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(executor);
        server.createContext("/api/config", this::config);
        server.createContext("/api/render", this::render);
        server.createContext("/api/logs", this::logs);
        server.createContext("/api/client-log", this::clientLog);
        server.createContext("/preview/", this::preview);
        server.createContext("/cache/info/v2", this::cacheInfo);
        server.createContext("/cache/", this::blockedCacheMutation);
        server.createContext("/local/", this::localAsset);
        server.createContext("/thumbnails/", this::thumbnail);
        server.createContext("/lab/", this::webAsset);
        server.createContext("/", this::index);
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void index(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not Found");
            return;
        }
        serveFile(exchange, webRoot.resolve("index.html"), webRoot, false);
    }

    private void webAsset(HttpExchange exchange) throws IOException {
        String relative = exchange.getRequestURI().getPath().substring("/lab/".length());
        serveFile(exchange, webRoot.resolve(relative).normalize(), webRoot, false);
    }

    private void config(HttpExchange exchange) throws IOException {
        if (!guardOrigin(exchange)) {
            return;
        }
        if (!method(exchange, "GET")) {
            return;
        }
        StringBuilder json = new StringBuilder("{\"filters\":[");
        boolean first = true;
        for (Path filter : trackedFilters) {
            ParseResult parsed = parser.parse(filter);
            ParserConformance.Report conformance = conformanceByName.get(filter.getFileName().toString());
            long errors = parsed.diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count();
            long warnings = parsed.diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).count();
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"name\":").append(Json.quote(filter.getFileName().toString()))
                    .append(",\"rules\":").append(parsed.rules.size())
                    .append(",\"errors\":").append(errors)
                    .append(",\"warnings\":").append(warnings)
                    .append(",\"productionParser\":").append(Json.quote(conformance.status())).append('}');
        }
        json.append("],\"fixtures\":[");
        first = true;
        for (Fixture fixture : fixtures.values()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"id\":").append(Json.quote(fixture.id))
                    .append(",\"label\":").append(Json.quote(fixture.label))
                    .append(",\"url\":").append(Json.quote(fixture.url))
                    .append(",\"contentType\":").append(Json.quote(fixture.contentType)).append('}');
        }
        json.append("],\"parserCompatibility\":")
                .append(ParserCompatibility.toJson(ParserCompatibility.inspect(repositoryRoot, labRoot)))
                .append('}');
        sendJson(exchange, 200, json.toString());
    }

    private void render(HttpExchange exchange) throws IOException {
        if (!guardOrigin(exchange)) {
            return;
        }
        if (!method(exchange, "POST")) {
            return;
        }
        byte[] body = readBody(exchange, MAX_RENDER_BODY);
        if (body == null) {
            return;
        }
        Map<String, List<String>> form = parseForm(new String(body, StandardCharsets.UTF_8));
        Fixture fixture = fixtures.get(first(form, "fixture", "watch"));
        if (fixture == null) {
            sendJson(exchange, 400, "{\"error\":\"fixture が不正です\"}");
            return;
        }
        String url = first(form, "url", fixture.url);
        String contentType = first(form, "contentType", fixture.contentType);
        FilterRule.CacheState cacheState = FilterRule.CacheState.parse(first(form, "cacheState", "NONE"));
        boolean cacheApiFailure = Boolean.parseBoolean(first(form, "cacheApiFailure", "false"));
        String reencoded = first(form, "reencoded", "null");
        int reencodedBitrate;
        try {
            reencodedBitrate = Integer.parseInt(first(form, "reencodedBitrate", "0"));
        } catch (NumberFormatException exception) {
            sendJson(exchange, 400, "{\"error\":\"reencodedBitrate は整数で指定してください\"}");
            return;
        }
        List<String> selected = form.getOrDefault("file", List.of());
        List<Path> files = new ArrayList<>();
        for (String name : selected) {
            Path path = filtersByName.get(name);
            if (path == null) {
                sendJson(exchange, 400, "{\"error\":" + Json.quote("未追跡または不明なフィルターです: " + name) + "}");
                return;
            }
            files.add(path);
        }
        files.sort((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()));

        ParseResult parsed = new ParseResult();
        for (Path file : files) {
            ParseResult fileResult = parser.parse(file);
            ParserConformance.Report conformance = conformanceByName.get(file.getFileName().toString());
            if (conformance.status().equals("mismatch")) {
                fileResult.diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, file, 0,
                        "production-parser-mismatch", String.join("; ", conformance.differences())));
            } else if (conformance.status().equals("unavailable")) {
                fileResult.diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, file, 0,
                        "production-parser-unavailable", conformance.reason()));
            }
            parsed.merge(fileResult);
        }
        String original = Files.readString(fixtureRoot.resolve(fixture.file), StandardCharsets.UTF_8);
        SimulationRequest request = new SimulationRequest(fixture.id, url, contentType, 200, cacheState,
                cacheApiFailure, reencoded, reencodedBitrate);
        SimulationResult simulation = engine.simulate(parsed.rules, request, original);
        simulation.diagnostics.addAll(0, parsed.diagnostics);
        String token = UUID.randomUUID().toString();
        String previewHtml = simulation.rendered.replaceFirst("(?i)<head>",
                "<head>\r\n<script src=\"/lab/preview-bootstrap.js\"></script>");
        previews.put(token, new Preview(token, request, previewHtml));
        if (previews.size() > 30) {
            previews.keySet().stream().limit(previews.size() - 30L).forEach(previews::remove);
        }

        StringBuilder json = new StringBuilder("{\"token\":").append(Json.quote(token))
                .append(",\"previewUrl\":").append(Json.quote("/preview/" + token))
                .append(",\"original\":").append(Json.quote(simulation.original))
                .append(",\"rendered\":").append(Json.quote(simulation.rendered))
                .append(",\"traces\":[");
        boolean first = true;
        for (SimulationResult.Trace trace : simulation.traces) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"identifier\":").append(Json.quote(trace.identifier()))
                    .append(",\"section\":").append(Json.quote(trace.section()))
                    .append(",\"replacements\":").append(trace.replacements())
                    .append(",\"note\":").append(Json.quote(trace.note())).append('}');
        }
        json.append("],\"diagnostics\":[");
        first = true;
        for (Diagnostic diagnostic : simulation.diagnostics) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"severity\":").append(Json.quote(diagnostic.severity().toString()))
                    .append(",\"code\":").append(Json.quote(diagnostic.code()))
                    .append(",\"message\":").append(Json.quote(diagnostic.display(repositoryRoot))).append('}');
        }
        json.append("],\"state\":{\"effectiveUrl\":").append(Json.quote(simulation.effectiveUrl))
                .append(",\"variables\":{");
        first = true;
        for (Map.Entry<String, String> entry : simulation.variables.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append(Json.quote(entry.getKey())).append(':').append(Json.quote(entry.getValue()));
        }
        json.append("},\"listAdditions\":{");
        first = true;
        for (Map.Entry<String, List<String>> entry : simulation.listAdditions.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append(Json.quote(entry.getKey())).append(':').append(Json.stringArray(entry.getValue()));
        }
        json.append("}}}");
        sendJson(exchange, 200, json.toString());
    }

    private void preview(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        String token = exchange.getRequestURI().getPath().substring("/preview/".length());
        Preview preview = previews.get(token);
        if (preview == null) {
            send(exchange, 404, "text/plain; charset=utf-8", "プレビューの有効期限が切れました");
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy", PREVIEW_CSP);
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        send(exchange, 200, "text/html; charset=utf-8", preview.html);
    }

    private void logs(HttpExchange exchange) throws IOException {
        if (!guardOrigin(exchange)) {
            return;
        }
        if (!method(exchange, "GET")) {
            return;
        }
        String token = first(parseForm(exchange.getRequestURI().getRawQuery()), "token", "");
        Preview preview = previews.get(token);
        if (preview == null) {
            sendJson(exchange, 404, "{\"error\":\"preview not found\"}");
            return;
        }
        StringBuilder json = new StringBuilder("{\"logs\":[");
        boolean first = true;
        for (ClientLog log : preview.logs) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"level\":").append(Json.quote(log.level))
                    .append(",\"message\":").append(Json.quote(log.message)).append('}');
        }
        json.append("]}");
        sendJson(exchange, 200, json.toString());
    }

    private void clientLog(HttpExchange exchange) throws IOException {
        if (!guardOrigin(exchange)) {
            return;
        }
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!method(exchange, "POST")) {
            return;
        }
        byte[] body = readBody(exchange, MAX_LOG_BODY);
        if (body == null) {
            return;
        }
        Map<String, List<String>> form = parseForm(new String(body, StandardCharsets.UTF_8));
        Preview preview = previews.get(first(form, "token", ""));
        if (preview != null) {
            preview.logs.add(new ClientLog(first(form, "level", "log"), first(form, "message", "")));
            if (preview.logs.size() > 200) {
                preview.logs.remove(0);
            }
        }
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void cacheInfo(HttpExchange exchange) throws IOException {
        if (!guardOrigin(exchange)) {
            return;
        }
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!method(exchange, "GET")) {
            return;
        }
        String query = exchange.getRequestURI().getRawQuery();
        String token = first(parseForm(query), "__nlftoken", "");
        Preview preview = previews.get(token);
        if (preview != null && preview.request.cacheApiFailure()) {
            sendJson(exchange, 503, "{\"error\":\"nlFilter Lab mock failure\"}");
            return;
        }
        FilterRule.CacheState state = preview == null ? FilterRule.CacheState.NONE : preview.request.cacheState();
        String idPart = query == null ? "" : query.split("&", 2)[0];
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (String encodedId : idPart.split(",")) {
            String id = decode(encodedId).replaceAll("[^A-Za-z0-9_-]", "");
            if (id.isBlank()) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(Json.quote(id)).append(':').append(cacheEntry(id, state));
        }
        json.append('}');
        sendJson(exchange, 200, json.toString());
    }

    private static String cacheEntry(String id, FilterRule.CacheState state) {
        if (state == FilterRule.CacheState.NONE) {
            return "{\"caches\":{},\"cachings\":[],\"completes\":[],\"preferredHTML5\":null}";
        }
        boolean dmc = state == FilterRule.CacheState.DMC || state == FilterRule.CacheState.DMC_ECONOMY;
        boolean economy = state == FilterRule.CacheState.ECONOMY || state == FilterRule.CacheState.DMC_ECONOMY;
        String cacheId = id + "[lab].mp4";
        return "{\"caches\":{" + Json.quote(cacheId) + ":{\"complete\":true,\"dmc\":" + dmc +
                ",\"economy\":" + economy + ",\"size\":1048576,\"position\":1048576}}," +
                "\"cachings\":[],\"completes\":[" + Json.quote(cacheId) + "],\"preferredHTML5\":" +
                Json.quote(cacheId) + "}";
    }

    private void blockedCacheMutation(HttpExchange exchange) throws IOException {
        if (!guardOrigin(exchange)) {
            return;
        }
        sendJson(exchange, 501, "{\"error\":\"nlFilter Lab ではキャッシュ変更APIを実行しません\"}");
    }

    private void localAsset(HttpExchange exchange) throws IOException {
        if (!guardOrigin(exchange)) {
            return;
        }
        String relative = exchange.getRequestURI().getPath().substring("/local/".length());
        String lower = relative.toLowerCase();
        if (!(lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".gif") ||
                lower.endsWith(".png") || lower.endsWith(".svg") || lower.endsWith(".html") ||
                lower.endsWith(".json"))) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not Found");
            return;
        }
        serveFile(exchange, localRoot.resolve(relative).normalize(), localRoot, false);
    }

    private void thumbnail(HttpExchange exchange) throws IOException {
        if (!guardOrigin(exchange)) {
            return;
        }
        String id = exchange.getRequestURI().getPath().substring("/thumbnails/".length())
                .replaceAll("[^A-Za-z0-9_-]", "");
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"320\" height=\"180\" viewBox=\"0 0 320 180\">" +
                "<rect width=\"320\" height=\"180\" fill=\"#20242d\"/><path d=\"M132 50l80 40-80 40z\" fill=\"#39c5bb\"/>" +
                "<text x=\"16\" y=\"164\" fill=\"white\" font-family=\"sans-serif\" font-size=\"18\">" + id + "</text></svg>";
        send(exchange, 200, "image/svg+xml; charset=utf-8", svg);
    }

    private static void serveFile(HttpExchange exchange, Path file, Path allowedRoot, boolean preview) throws IOException {
        if (!file.startsWith(allowedRoot) || !Files.isRegularFile(file)) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not Found");
            return;
        }
        if (preview) {
            exchange.getResponseHeaders().set("Content-Security-Policy", PREVIEW_CSP);
        }
        byte[] bytes = Files.readAllBytes(file);
        send(exchange, 200, contentType(file), bytes);
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml; charset=utf-8";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase(expected)) {
            exchange.getResponseHeaders().set("Allow", expected);
            send(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return false;
        }
        return true;
    }

    private static Map<String, List<String>> parseForm(String value) {
        Map<String, List<String>> result = new HashMap<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String pair : value.split("&")) {
            int separator = pair.indexOf('=');
            String key = decode(separator < 0 ? pair : pair.substring(0, separator));
            String item = decode(separator < 0 ? "" : pair.substring(separator + 1));
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        return result;
    }

    private static String first(Map<String, List<String>> form, String key, String fallback) {
        List<String> values = form.get(key);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private boolean guardOrigin(HttpExchange exchange) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String loopback = "http://127.0.0.1:" + port();
        String localhost = "http://localhost:" + port();
        if (origin != null && !origin.equals("null") && !origin.equals(loopback) && !origin.equals(localhost)) {
            send(exchange, 403, "text/plain; charset=utf-8", "Forbidden Origin");
            return false;
        }
        addCorsHeaders(exchange, origin);
        return true;
    }

    private static void addCorsHeaders(HttpExchange exchange, String origin) {
        if (origin != null) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static byte[] readBody(HttpExchange exchange, int maximumBytes) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(maximumBytes + 1);
        if (body.length > maximumBytes) {
            send(exchange, 413, "text/plain; charset=utf-8", "Request body too large");
            return null;
        }
        return body;
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body);
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        send(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record Fixture(String id, String label, String file, String url, String contentType) {
    }

    private static final class Preview {
        final String token;
        final SimulationRequest request;
        final String html;
        final List<ClientLog> logs = new CopyOnWriteArrayList<>();

        Preview(String token, SimulationRequest request, String html) {
            this.token = token;
            this.request = request;
            this.html = html;
        }
    }

    private record ClientLog(String level, String message) {
    }
}
