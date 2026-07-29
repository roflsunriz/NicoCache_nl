package nlfilterlab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Main {
    private record FileConformance(Path file, ParserConformance.Report report) {
    }

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path repositoryRoot = Path.of(System.getProperty("nlfilterlab.repository", ".")).toAbsolutePath().normalize();
        Path labRoot = Path.of(System.getProperty("nlfilterlab.root", "tools/nlfilter-lab")).toAbsolutePath().normalize();
        String command = args.length == 0 ? "check" : args[0];
        List<String> arguments = new ArrayList<>(Arrays.asList(args).subList(Math.min(1, args.length), args.length));
        try {
            switch (command) {
                case "check" -> System.exit(check(repositoryRoot, labRoot, arguments));
                case "source-check" -> System.exit(sourceCheck(repositoryRoot, labRoot, arguments));
                case "compatibility" -> System.exit(compatibility(repositoryRoot, labRoot, arguments));
                case "headless" -> System.exit(HeadlessRunner.run(repositoryRoot, labRoot, arguments));
                case "serve" -> serve(repositoryRoot, labRoot, arguments);
                default -> {
                    System.err.println("使用方法: nlfilter-lab.ps1 <check|source-check|compatibility|serve|headless> [options]");
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.exit(2);
        }
    }

    private static int check(Path repositoryRoot, Path labRoot, List<String> arguments) throws Exception {
        boolean json = arguments.remove("--json");
        for (String argument : arguments) {
            if (argument.startsWith("--")) throw new IllegalArgumentException("不明なオプションです: " + argument);
        }
        List<Path> files = arguments.isEmpty()
                ? RepositoryFilters.tracked(repositoryRoot)
                : RepositoryFilters.resolveExplicit(repositoryRoot, arguments);
        ParseResult all = new ParseResult();
        List<FileConformance> conformances = new ArrayList<>();
        FilterParser parser = new FilterParser();
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                all.diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, file, 0, "missing-file",
                        "ファイルが存在しません"));
                continue;
            }
            ParseResult parsed = parser.parse(file);
            ParserConformance.Report conformance = ParserConformance.compare(repositoryRoot, file, parsed);
            conformances.add(new FileConformance(file, conformance));
            if (conformance.status().equals("mismatch")) {
                all.diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, file, 0,
                        "production-parser-mismatch", String.join("; ", conformance.differences())));
            } else if (conformance.status().equals("unavailable")) {
                all.diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, file, 0,
                        "production-parser-unavailable", conformance.reason()));
            }
            all.merge(parsed);
        }
        List<Diagnostic> diagnostics = all.diagnostics.stream()
                .sorted((left, right) -> left.display(repositoryRoot).compareTo(right.display(repositoryRoot))).toList();
        long errors = all.diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count();
        long warnings = all.diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).count();
        ParserCompatibility.Report compatibility = ParserCompatibility.inspect(repositoryRoot, labRoot);
        boolean sourceFailure = compatibility.statusName().equals("mismatch") ||
                compatibility.statusName().equals("source-partial");
        if (json) {
            StringBuilder output = new StringBuilder("{\"status\":")
                    .append(Json.quote(errors == 0 && !sourceFailure ? "passed" : "failed"))
                    .append(",\"files\":").append(files.size())
                    .append(",\"rules\":").append(all.rules.size())
                    .append(",\"errors\":").append(errors)
                    .append(",\"warnings\":").append(warnings)
                    .append(",\"parserCompatibility\":").append(ParserCompatibility.toJson(compatibility))
                    .append(",\"productionParser\":").append(conformanceJson(repositoryRoot, conformances))
                    .append(",\"diagnostics\":[");
            boolean first = true;
            for (Diagnostic diagnostic : diagnostics) {
                if (!first) output.append(',');
                first = false;
                output.append("{\"severity\":").append(Json.quote(diagnostic.severity().toString()))
                        .append(",\"code\":").append(Json.quote(diagnostic.code()))
                        .append(",\"message\":").append(Json.quote(diagnostic.display(repositoryRoot))).append('}');
            }
            System.out.println(output.append("]}").toString());
        } else {
            diagnostics.forEach(diagnostic -> System.out.println(diagnostic.display(repositoryRoot)));
            printCompatibility(compatibility);
            printConformance(conformances);
            System.out.printf("%d files, %d rules, %d errors, %d warnings%n",
                    files.size(), all.rules.size(), errors, warnings);
        }
        return errors == 0 && !sourceFailure ? 0 : 1;
    }

    private static String conformanceJson(Path repositoryRoot, List<FileConformance> conformances) {
        long matched = conformances.stream().filter(item -> item.report.status().equals("matched")).count();
        long mismatched = conformances.stream().filter(item -> item.report.status().equals("mismatch")).count();
        long unavailable = conformances.stream().filter(item -> item.report.status().equals("unavailable")).count();
        String status = mismatched > 0 ? "mismatch" : unavailable > 0 ? "unavailable" : "matched";
        StringBuilder json = new StringBuilder("{\"status\":").append(Json.quote(status))
                .append(",\"matchedFiles\":").append(matched)
                .append(",\"mismatchedFiles\":").append(mismatched)
                .append(",\"unavailableFiles\":").append(unavailable).append(",\"files\":[");
        boolean first = true;
        for (FileConformance item : conformances) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"file\":").append(Json.quote(repositoryRoot.relativize(item.file.toAbsolutePath()).toString()))
                    .append(",\"status\":").append(Json.quote(item.report.status()))
                    .append(",\"productionRules\":").append(item.report.productionRules())
                    .append(",\"labRules\":").append(item.report.labRules())
                    .append(",\"differences\":").append(Json.stringArray(item.report.differences()))
                    .append(",\"reason\":").append(Json.quote(item.report.reason())).append('}');
        }
        return json.append("]}").toString();
    }

    private static void printConformance(List<FileConformance> conformances) {
        long matched = conformances.stream().filter(item -> item.report.status().equals("matched")).count();
        long mismatched = conformances.stream().filter(item -> item.report.status().equals("mismatch")).count();
        long unavailable = conformances.stream().filter(item -> item.report.status().equals("unavailable")).count();
        System.out.printf("production parser: %d matched, %d mismatched, %d unavailable%n",
                matched, mismatched, unavailable);
    }

    private static int sourceCheck(Path repositoryRoot, Path labRoot, List<String> arguments) {
        boolean json = arguments.remove("--json");
        if (!arguments.isEmpty()) throw new IllegalArgumentException("不明なオプションです: " + arguments.get(0));
        ParserCompatibility.Report report = ParserCompatibility.inspect(repositoryRoot, labRoot);
        if (json) System.out.println(ParserCompatibility.toJson(report));
        else printCompatibility(report);
        return report.statusName().equals("mismatch") || report.statusName().equals("source-partial") ? 1 : 0;
    }

    private static int compatibility(Path repositoryRoot, Path labRoot, List<String> arguments) throws Exception {
        boolean json = arguments.remove("--json");
        if (!arguments.isEmpty()) throw new IllegalArgumentException("不明なオプションです: " + arguments.get(0));
        ParserCompatibility.Report source = ParserCompatibility.inspect(repositoryRoot, labRoot);
        List<FileConformance> conformances = new ArrayList<>();
        FilterParser parser = new FilterParser();
        for (Path file : RepositoryFilters.tracked(repositoryRoot)) {
            conformances.add(new FileConformance(file,
                    ParserConformance.compare(repositoryRoot, file, parser.parse(file))));
        }
        boolean failed = source.statusName().equals("mismatch") || source.statusName().equals("source-partial") ||
                conformances.stream().anyMatch(item -> item.report.status().equals("mismatch"));
        List<String> deterministic = List.of("Replace/Script/Style", "RequestHeader URL書換", "URL/FullURL/ContentType/Require/RequireHeader",
                "Multi/EachLine/ReplaceOnly/ReplaceDelay/StatusCode", "$URL/$RequireHeader/$TS", "$LST/$INC/$SET",
                "AddList/AddVariable（メモリ内）", "$NEST", "nlcase/when", "nlVarとwatch静的変数", "特殊Append");
        List<String> mocked = List.of("idGroupのキャッシュ5状態", "$REENCODED/$REENCODED_BITRATE", "freeSpace",
                "リクエストヘッダー", "/cache API", "NicoCache_nl VERSION");
        List<String> boundaries = List.of("NLFilterListener拡張固有のidGroup/変数", "実キャッシュ索引とthread→smid解決",
                "Configを参照する本体・拡張機能", "実Cookie・認証ヘッダー", "AddListの実ファイル書込み",
                "NicoCache_nl本体を介したネットワーク副作用");
        if (json) {
            System.out.println("{\"status\":" + Json.quote(failed ? "failed" : "passed") +
                    ",\"level\":\"max-safe\",\"syntax\":{\"source\":" + ParserCompatibility.toJson(source) +
                    ",\"productionOracle\":" + conformanceJson(repositoryRoot, conformances) + "}" +
                    ",\"simulation\":{\"deterministic\":" + Json.stringArray(deterministic) +
                    ",\"mocked\":" + Json.stringArray(mocked) +
                    ",\"externalBoundaries\":" + Json.stringArray(boundaries) + "}}" );
        } else {
            System.out.println("compatibility level: max-safe");
            printCompatibility(source);
            printConformance(conformances);
            System.out.println("simulation: " + deterministic.size() + " deterministic groups, " + mocked.size() +
                    " explicit mocks, " + boundaries.size() + " external boundaries");
        }
        return failed ? 1 : 0;
    }

    private static void printCompatibility(ParserCompatibility.Report report) {
        System.out.println("parser source: " + report.statusName() + " (baseline " + report.baselineDate() + ")");
        for (ParserCompatibility.Entry entry : report.entries()) {
            if (entry.status() != ParserCompatibility.Status.MATCHED) {
                System.out.println("  " + entry.status().name().toLowerCase() + " " + entry.source());
            }
        }
    }

    private static void serve(Path repositoryRoot, Path labRoot, List<String> arguments) throws Exception {
        int port = 8765;
        for (int i = 0; i < arguments.size(); i++) {
            if (arguments.get(i).equals("--port") && i + 1 < arguments.size()) {
                port = Integer.parseInt(arguments.get(++i));
            } else {
                throw new IllegalArgumentException("不明なオプションです: " + arguments.get(i));
            }
        }
        LabServer server = new LabServer(repositoryRoot, labRoot, port);
        server.start();
        System.out.println("nlFilter Lab: http://127.0.0.1:" + server.port() + "/");
        System.out.println("終了するには Ctrl+C を押してください。");
    }
}
