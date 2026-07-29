package nlfilterlab;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HeadlessRunner {
    private record OracleSummary(String status, int matched, int mismatched, int unavailable) {
    }

    private static final Pattern TOKEN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern PREVIEW_URL = Pattern.compile("\\\"previewUrl\\\":\\\"([^\\\"]+)\\\"");

    private HeadlessRunner() {
    }

    static int run(Path repositoryRoot, Path labRoot, List<String> arguments) {
        Options options;
        try {
            options = Options.parse(repositoryRoot, arguments);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return 2;
        }

        LabServer server = null;
        Path output = options.outputDir;
        List<String> failures = new ArrayList<>();
        String renderJson = "{}";
        String logsJson = "{\"logs\":[]}";
        String browserName = null;
        int exitCode = 3;
        try {
            Files.createDirectories(output);
            for (String artifact : List.of("result.json", "render.json", "final.html", "screenshot.png", "console.json")) {
                Files.deleteIfExists(output.resolve(artifact));
            }
            ParserCompatibility.Report compatibility = ParserCompatibility.inspect(repositoryRoot, labRoot);
            if (compatibility.statusName().equals("mismatch") || compatibility.statusName().equals("source-partial")) {
                failures.add("NicoCache_nl のパーサーソースが変わっています。互換性の再監査が必要です");
            }
            OracleSummary oracle = productionSummary(repositoryRoot, options);
            if (oracle.mismatched > 0) failures.add("本体パーサーとLabの内部表現が一致しません");
            Path browser = options.browser != null ? options.browser : findBrowser();
            if (browser == null || !Files.isRegularFile(browser)) {
                throw new IOException("Chrome または Edge が見つかりません。--browser で実行ファイルを指定してください");
            }
            browserName = browser.toString();
            server = new LabServer(repositoryRoot, labRoot, 0);
            server.start();
            String origin = "http://127.0.0.1:" + server.port();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String form = buildForm(repositoryRoot, options);
            HttpResponse<String> render = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/render"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            renderJson = render.body();
            Files.writeString(output.resolve("render.json"), renderJson, StandardCharsets.UTF_8);
            if (render.statusCode() != 200) {
                failures.add("疑似適用APIが HTTP " + render.statusCode() + " を返しました");
            } else {
                String token = extract(TOKEN, renderJson, "render token");
                String previewPath = extract(PREVIEW_URL, renderJson, "preview URL");
                String separator = previewPath.contains("?") ? "&" : "?";
                String previewUrl = origin + previewPath + separator + "spaAdd=" + options.spaAdd;

                Path domProfile = output.resolve(".profile-dom");
                Path screenshotProfile = output.resolve(".profile-screenshot");
                int domExit = runBrowser(browser, domProfile, options, previewUrl, true,
                        output.resolve("final.html"), output.resolve("browser-dom.log"));
                int screenshotExit = runBrowser(browser, screenshotProfile, options, previewUrl, false,
                        output.resolve("browser-screenshot.log"), output.resolve("browser-screenshot-error.log"));
                deleteTree(domProfile);
                deleteTree(screenshotProfile);
                if (domExit != 0) failures.add("DOM取得ブラウザーの終了コード=" + domExit);
                if (screenshotExit != 0) failures.add("スクリーンショット取得ブラウザーの終了コード=" + screenshotExit);

                Thread.sleep(250);
                HttpResponse<String> logs = client.send(HttpRequest.newBuilder(
                                URI.create(origin + "/api/logs?token=" + encode(token)))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                logsJson = logs.body();
                Files.writeString(output.resolve("console.json"), logsJson, StandardCharsets.UTF_8);
                if (count(logsJson, "\\\"level\\\":\\\"error\\\"") > 0) failures.add("コンソールエラーを検出しました");
                if (count(renderJson, "\\\"severity\\\":\\\"ERROR\\\"") > 0) failures.add("フィルター診断エラーを検出しました");
                if (!isPng(output.resolve("screenshot.png"))) failures.add("有効な screenshot.png を生成できませんでした");
                Path finalDom = output.resolve("final.html");
                if (!Files.isRegularFile(finalDom) || !Files.readString(finalDom).contains("data-fixture=\"" + options.fixture + "\"")) {
                    failures.add("final.html に指定したfixtureがありません");
                }
            }
            exitCode = failures.isEmpty() ? 0 : 1;
        } catch (Exception exception) {
            failures.add(exception.getClass().getSimpleName() + ": " + exception.getMessage());
            exitCode = 3;
        } finally {
            if (server != null) server.stop();
        }

        String result = resultJson(repositoryRoot, labRoot, options, output, browserName, renderJson, logsJson,
                failures, exitCode);
        try {
            Files.createDirectories(output);
            Files.writeString(output.resolve("result.json"), result, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            System.err.println("result.json を保存できません: " + exception.getMessage());
            if (exitCode == 0) exitCode = 3;
        }
        System.out.println(result);
        return exitCode;
    }

    private static String buildForm(Path repositoryRoot, Options options) throws Exception {
        List<String> fields = new ArrayList<>();
        fields.add(pair("fixture", options.fixture));
        fields.add(pair("cacheState", options.cacheState));
        fields.add(pair("reencoded", options.reencoded));
        fields.add(pair("reencodedBitrate", Integer.toString(options.reencodedBitrate)));
        if (options.url != null) fields.add(pair("url", options.url));
        if (options.contentType != null) fields.add(pair("contentType", options.contentType));
        List<Path> files = selectedFiles(repositoryRoot, options);
        for (Path file : files) fields.add(pair("file", file.getFileName().toString()));
        return String.join("&", fields);
    }

    private static List<Path> selectedFiles(Path repositoryRoot, Options options) throws Exception {
        return options.noFilters ? List.of() : options.files.isEmpty()
                ? RepositoryFilters.tracked(repositoryRoot)
                : RepositoryFilters.resolveExplicit(repositoryRoot, options.files);
    }

    private static OracleSummary productionSummary(Path repositoryRoot, Options options) {
        int matched = 0;
        int mismatched = 0;
        int unavailable = 0;
        try {
            FilterParser parser = new FilterParser();
            for (Path file : selectedFiles(repositoryRoot, options)) {
                ParserConformance.Report report = ParserConformance.compare(repositoryRoot, file, parser.parse(file));
                if (report.status().equals("matched")) matched++;
                else if (report.status().equals("mismatch")) mismatched++;
                else unavailable++;
            }
        } catch (Exception exception) {
            unavailable++;
        }
        return new OracleSummary(mismatched > 0 ? "mismatch" : unavailable > 0 ? "unavailable" : "matched",
                matched, mismatched, unavailable);
    }

    private static int runBrowser(Path browser, Path profile, Options options, String url, boolean dumpDom,
            Path standardOutput, Path standardError) throws Exception {
        Files.createDirectories(profile);
        List<String> command = new ArrayList<>(List.of(browser.toString(), "--headless=new", "--disable-gpu",
                "--no-first-run", "--no-default-browser-check", "--hide-scrollbars",
                "--run-all-compositor-stages-before-draw", "--virtual-time-budget=3000",
                "--user-data-dir=" + profile.toAbsolutePath(),
                "--window-size=" + options.width + "," + options.height));
        if (dumpDom) {
            command.add("--dump-dom");
        } else {
            command.add("--screenshot=" + options.outputDir.resolve("screenshot.png").toAbsolutePath());
        }
        command.add(url);
        Process process = new ProcessBuilder(command).redirectOutput(standardOutput.toFile())
                .redirectError(standardError.toFile()).start();
        if (!process.waitFor(options.timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return 124;
        }
        return process.exitValue();
    }

    private static String resultJson(Path repositoryRoot, Path labRoot, Options options, Path output, String browser,
            String renderJson, String logsJson, List<String> failures, int exitCode) {
        ParserCompatibility.Report compatibility = ParserCompatibility.inspect(repositoryRoot, labRoot);
        OracleSummary oracle = productionSummary(repositoryRoot, options);
        return "{\"schemaVersion\":1,\"status\":" + Json.quote(exitCode == 0 ? "passed" : "failed") +
                ",\"exitCode\":" + exitCode +
                ",\"fixture\":" + Json.quote(options.fixture) +
                ",\"cacheState\":" + Json.quote(options.cacheState) +
                ",\"reencoded\":" + Json.quote(options.reencoded) +
                ",\"reencodedBitrate\":" + options.reencodedBitrate +
                ",\"spaAdd\":" + options.spaAdd +
                ",\"viewport\":{\"width\":" + options.width + ",\"height\":" + options.height + "}" +
                ",\"browser\":" + Json.quote(browser) +
                ",\"parserCompatibility\":" + ParserCompatibility.toJson(compatibility) +
                ",\"productionParser\":{\"status\":" + Json.quote(oracle.status) +
                ",\"matchedFiles\":" + oracle.matched + ",\"mismatchedFiles\":" + oracle.mismatched +
                ",\"unavailableFiles\":" + oracle.unavailable + "}" +
                ",\"metrics\":{\"diagnosticErrors\":" + count(renderJson, "\\\"severity\\\":\\\"ERROR\\\"") +
                ",\"consoleErrors\":" + count(logsJson, "\\\"level\\\":\\\"error\\\"") + "}" +
                ",\"artifacts\":{\"result\":" + Json.quote(output.resolve("result.json").toString()) +
                ",\"render\":" + Json.quote(output.resolve("render.json").toString()) +
                ",\"finalDom\":" + Json.quote(output.resolve("final.html").toString()) +
                ",\"screenshot\":" + Json.quote(output.resolve("screenshot.png").toString()) +
                ",\"console\":" + Json.quote(output.resolve("console.json").toString()) + "}" +
                ",\"failures\":" + Json.stringArray(failures) + "}";
    }

    private static String extract(Pattern pattern, String input, String label) throws IOException {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) throw new IOException(label + " が応答にありません");
        return matcher.group(1).replace("\\/", "/");
    }

    private static int count(String value, String regex) {
        int total = 0;
        Matcher matcher = Pattern.compile(regex).matcher(value);
        while (matcher.find()) total++;
        return total;
    }

    private static boolean isPng(Path path) {
        try {
            byte[] signature = Files.readAllBytes(path);
            return signature.length > 8 && signature[0] == (byte) 0x89 && signature[1] == 'P' &&
                    signature[2] == 'N' && signature[3] == 'G';
        } catch (IOException exception) {
            return false;
        }
    }

    private static String pair(String key, String value) {
        return encode(key) + "=" + encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Path findBrowser() {
        String local = System.getenv("LOCALAPPDATA");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        List<Path> candidates = new ArrayList<>();
        if (programFiles != null) {
            candidates.add(Path.of(programFiles, "Google/Chrome/Application/chrome.exe"));
            candidates.add(Path.of(programFiles, "Microsoft/Edge/Application/msedge.exe"));
        }
        if (programFilesX86 != null) {
            candidates.add(Path.of(programFilesX86, "Google/Chrome/Application/chrome.exe"));
            candidates.add(Path.of(programFilesX86, "Microsoft/Edge/Application/msedge.exe"));
        }
        if (local != null) candidates.add(Path.of(local, "Google/Chrome/Application/chrome.exe"));
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static final class Options {
        String fixture = "watch";
        String cacheState = "NONE";
        String reencoded = "null";
        int reencodedBitrate;
        int spaAdd;
        int width = 1280;
        int height = 900;
        int timeoutSeconds = 30;
        Path outputDir;
        Path browser;
        String url;
        String contentType;
        boolean noFilters;
        final List<String> files = new ArrayList<>();

        static Options parse(Path repositoryRoot, List<String> arguments) {
            Options value = new Options();
            value.outputDir = repositoryRoot.resolve(".cache/nlfilter-lab/headless/watch-none").normalize();
            for (int i = 0; i < arguments.size(); i++) {
                String option = arguments.get(i);
                String next = i + 1 < arguments.size() ? arguments.get(i + 1) : null;
                switch (option) {
                    case "--fixture" -> { value.fixture = required(option, next); i++; }
                    case "--cache-state" -> { value.cacheState = required(option, next).toUpperCase(Locale.ROOT); i++; }
                    case "--reencoded" -> { value.reencoded = required(option, next).toLowerCase(Locale.ROOT); i++; }
                    case "--reencoded-bitrate" -> { value.reencodedBitrate = nonNegative(option, required(option, next)); i++; }
                    case "--spa-add" -> { value.spaAdd = nonNegative(option, required(option, next)); i++; }
                    case "--output-dir" -> { value.outputDir = resolve(repositoryRoot, required(option, next)); i++; }
                    case "--browser" -> { value.browser = resolve(repositoryRoot, required(option, next)); i++; }
                    case "--url" -> { value.url = required(option, next); i++; }
                    case "--content-type" -> { value.contentType = required(option, next); i++; }
                    case "--file" -> { value.files.add(required(option, next)); i++; }
                    case "--no-filters" -> value.noFilters = true;
                    case "--viewport" -> {
                        String[] size = required(option, next).toLowerCase(Locale.ROOT).split("x", 2);
                        if (size.length != 2) throw new IllegalArgumentException("--viewport は 1280x900 の形式です");
                        value.width = positive(option, size[0]);
                        value.height = positive(option, size[1]);
                        i++;
                    }
                    case "--timeout" -> { value.timeoutSeconds = positive(option, required(option, next)); i++; }
                    default -> throw new IllegalArgumentException("不明なオプションです: " + option);
                }
            }
            if (!List.of("watch", "search", "anime").contains(value.fixture))
                throw new IllegalArgumentException("--fixture は watch, search, anime のいずれかです");
            try { FilterRule.CacheState.valueOf(value.cacheState); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("--cache-state が不正です: " + value.cacheState); }
            if (!List.of("true", "false", "null").contains(value.reencoded))
                throw new IllegalArgumentException("--reencoded は true, false, null のいずれかです");
            if (value.noFilters && !value.files.isEmpty())
                throw new IllegalArgumentException("--no-filters と --file は同時に指定できません");
            if (!arguments.contains("--output-dir")) {
                value.outputDir = repositoryRoot.resolve(".cache/nlfilter-lab/headless/" +
                        value.fixture + "-" + value.cacheState.toLowerCase(Locale.ROOT)).normalize();
            }
            return value;
        }

        private static Path resolve(Path repositoryRoot, String value) {
            Path path = Path.of(value);
            return (path.isAbsolute() ? path : repositoryRoot.resolve(path)).toAbsolutePath().normalize();
        }

        private static String required(String option, String value) {
            if (value == null || value.startsWith("--")) throw new IllegalArgumentException(option + " に値が必要です");
            return value;
        }

        private static int positive(String option, String value) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " は正の整数で指定してください");
            }
        }

        private static int nonNegative(String option, String value) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 0) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " は0以上の整数で指定してください");
            }
        }
    }
}
