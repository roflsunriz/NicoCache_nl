package nlfilterlab;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public final class NlFilterLabTests {
    private static final String HEADER = "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)\n";
    private static int passed;

    private NlFilterLabTests() {
    }

    public static void main(String[] args) throws Exception {
        Path repository = Path.of(System.getProperty("nlfilterlab.repository", ".")).toAbsolutePath().normalize();
        Path temporary = repository.resolve(".cache/nlfilter-lab/tests");
        Files.createDirectories(temporary);

        run("追跡中フィルターをエラーなく解析", () -> trackedFiltersParse(repository));
        run("未閉鎖ブロックを検出", () -> unclosedBlock(temporary));
        run("不正なJava正規表現を検出", () -> invalidPattern(temporary));
        run("構文エラーのルールを疑似適用対象に残さない", () -> invalidRuleDiscarded(temporary));
        run("未知セクションを検出", () -> unknownSection(temporary));
        run("EachLine行数不一致を検出", () -> eachLineMismatch(temporary));
        run("EachLineを行ごとに適用", () -> eachLine(repository, temporary));
        run("URLとContent-Typeの非対象を除外", () -> contextSelection(repository, temporary));
        run("MatchLocalを本体のlocal URL判定で適用", () -> matchLocal(repository, temporary));
        run("idGroupの5状態を切り替え", () -> cacheVariants(repository));
        run("idGroup第2値から動画IDを補完", () -> idGroupFallback(repository, temporary));
        run("単独キャッシュ分岐とnoCache null groupを本体互換で処理", () -> legacyCacheBranches(repository, temporary));
        run("LST・AddList・RequireHeader参照を疑似実行", () -> stateAndHeaderMacros(repository, temporary));
        run("SET・INC・AddVariable・RequestHeaderを疑似実行", () -> dynamicStateAndRequestHeader(repository, temporary));
        run("NEST・nlcase・watch静的変数を疑似実行", () -> advancedReplacementMacros(repository, temporary));
        run("再エンコード情報を明示モックで疑似実行", () -> environmentDependentMacros(repository, temporary));
        run("URL optional groupと空TSを本体互換で展開", () -> macroCompatibility(repository, temporary));
        run("StyleとScriptをhead/bodyへ挿入", () -> appendSections(repository, temporary));
        run("Appendの大文字小文字と不正セクションを厳格に処理", () -> appendCompatibility(repository, temporary));
        run("Replace内のCRLFを維持", () -> crlfReplacement(repository, temporary));
        run("末尾空白付きセクションのtrimモード", () -> trimMode(repository, temporary));
        run("freeSpaceをJavaScript数値へ置換", () -> freeSpaceVariable(repository));
        run("追跡ファイルを辞書順で列挙", () -> trackedOrder(repository));
        run("NicoCache_nlパーサーソースの基準一致", () -> parserSourceBaseline(repository));
        run("パーサーソースの差異と部分欠落を検出", () -> parserSourceChanges(repository, temporary));
        run("稼働中EasyRewriterを構文オラクルとして呼び出す", () -> productionParserOracle(repository));
        run("全セクション・全オプションを本体内部表現と照合", () -> parserOptionCorpus(repository, temporary));
        run("副作用のない置換結果を本体実行結果と照合", () -> productionExecutionCorpus(repository, temporary));
        run("ローカルサーバーのOrigin・本文・パス境界", () -> serverBoundaries(repository));

        System.out.println("PASS: " + passed + " tests");
    }

    private static void trackedFiltersParse(Path repository) throws Exception {
        FilterParser parser = new FilterParser();
        ParseResult all = new ParseResult();
        List<Path> files = RepositoryFilters.tracked(repository);
        for (Path file : files) all.merge(parser.parse(file));
        assertEquals(8, files.size(), "追跡フィルター数");
        assertTrue(!all.hasErrors(), "既存フィルターにエラーがない");
        assertEquals(40, all.rules.size(), "既存ルール数");
    }

    private static void unclosedBlock(Path temporary) throws Exception {
        Path file = write(temporary, "unclosed.txt", HEADER + "[Replace]\nName = x\nURL = example\\.com/\nMatch<\n(foo\n");
        ParseResult result = new FilterParser().parse(file);
        assertDiagnostic(result, "unclosed-block");
    }

    private static void invalidPattern(Path temporary) throws Exception {
        Path file = write(temporary, "invalid-pattern.txt", HEADER +
                "[Replace]\nName = x\nURL = example\\.com/\nMatch<\n([\n>\nReplace<\nx\n>\n");
        ParseResult result = new FilterParser().parse(file);
        assertDiagnostic(result, "pattern");
    }

    private static void invalidRuleDiscarded(Path temporary) throws Exception {
        Path file = write(temporary, "invalid-discarded.txt", HEADER +
                "[Replace]\nName = bad\nURL = example\\.com/\nMatch<\n([\n>\nReplace<\nchanged\n>\n");
        ParseResult result = new FilterParser().parse(file);
        assertEquals(0, result.rules.size(), "不正ルール数");
    }

    private static void eachLine(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "each-line.txt", HEADER +
                "[Replace]\nName = each\nURL = example\\.com/\nEachLine = TRUE\nMulti = TRUE\n" +
                "Match<\ncat\ndog\n>\nReplace<\n猫\n犬\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        assertEquals("matched", ParserConformance.compare(repository, file, parsed).status(), "EachLine構文互換");
        SimulationResult result = simulate(repository, parsed.rules, "cat dog cat", "https://example.com/", "text/html",
                FilterRule.CacheState.NONE);
        assertEquals("猫 犬 猫", result.rendered, "EachLine変換結果");
    }

    private static void unknownSection(Path temporary) throws Exception {
        Path file = write(temporary, "unknown-section.txt", HEADER + "[Replcae]\n");
        assertDiagnostic(new FilterParser().parse(file), "unknown-section");
    }

    private static void eachLineMismatch(Path temporary) throws Exception {
        Path file = write(temporary, "each-line-mismatch.txt", HEADER +
                "[Replace]\nName = mismatch\nURL = example\\.com/\nEachLine = TRUE\n" +
                "Match<\na\nb\n>\nReplace<\nx\n>\n");
        assertDiagnostic(new FilterParser().parse(file), "each-line-count");
    }

    private static void contextSelection(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "context.txt", HEADER +
                "[Replace]\nName = context\nURL = example\\.com/watch/\nContentType = text/html\n" +
                "Match<\nold\n>\nReplace<\nnew\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        assertEquals("matched", ParserConformance.compare(repository, file, parsed).status(), "条件構文互換");
        SimulationResult wrongUrl = simulate(repository, parsed.rules, "old", "https://example.com/search/", "text/html",
                FilterRule.CacheState.NONE);
        SimulationResult wrongType = simulate(repository, parsed.rules, "old", "https://example.com/watch/1", "application/json",
                FilterRule.CacheState.NONE);
        assertEquals("old", wrongUrl.rendered, "URL非対象");
        assertEquals("old", wrongType.rendered, "Content-Type非対象");
    }

    private static void matchLocal(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "match-local.txt", HEADER +
                "[Replace]\nName = broad-off\nURL = www\\.nicovideo\\.jp/\nMatch<\nx\n>\nReplace<\noff\n>\n" +
                "[Replace]\nName = explicit\nURL = www\\.nicovideo\\.jp/local/\nMatch<\nx\n>\nReplace<\nexplicit\n>\n" +
                "[Replace]\nName = broad-on\nURL = www\\.nicovideo\\.jp/\nMatchLocal = TRUE\nMatch<\nexplicit\n>\nReplace<\non\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        SimulationResult result = simulate(repository, parsed.rules, "x",
                "https://www.nicovideo.jp/local/test.js", "text/javascript", FilterRule.CacheState.NONE);
        assertEquals("on", result.rendered, "MatchLocal変換");
    }

    private static void cacheVariants(Path repository) {
        ParseResult parsed = new FilterParser().parse(repository.resolve("20_watchFilter.txt"));
        FilterRule colorRule = parsed.rules.stream().filter(rule -> "キャッシュ済動画のリンク色変更(watch)".equals(rule.name))
                .findFirst().orElseThrow();
        String html = "<a href=\"/watch/sm9\">title</a>";
        assertContains(simulate(repository, List.of(colorRule), html, "https://www.nicovideo.jp/watch/sm9", "text/html",
                FilterRule.CacheState.NORMAL).rendered, "#C00000", "通常色");
        assertContains(simulate(repository, List.of(colorRule), html, "https://www.nicovideo.jp/watch/sm9", "text/html",
                FilterRule.CacheState.ECONOMY).rendered, "#C08000", "エコノミー色");
        assertContains(simulate(repository, List.of(colorRule), html, "https://www.nicovideo.jp/watch/sm9", "text/html",
                FilterRule.CacheState.DMC).rendered, "#008000", "DMC色");
        assertContains(simulate(repository, List.of(colorRule), html, "https://www.nicovideo.jp/watch/sm9", "text/html",
                FilterRule.CacheState.DMC_ECONOMY).rendered, "#808000", "DMCエコノミー色");
        assertEquals(html, simulate(repository, List.of(colorRule), html, "https://www.nicovideo.jp/watch/sm9", "text/html",
                FilterRule.CacheState.NONE).rendered, "キャッシュなし");
    }

    private static void idGroupFallback(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "id-group-fallback.txt", HEADER +
                "[Replace]\nName = fallback\nURL = example\\.com/\nidGroup = 1,2\n" +
                "Match<\n(?:video/(sm\\d+)|thumb/(\\d+))\n>\nReplace<\n<eachSmid>-cached\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        assertEquals("matched", ParserConformance.compare(repository, file, parsed).status(), "idGroup構文互換");
        SimulationResult result = simulate(repository, parsed.rules, "thumb/9", "https://example.com/", "text/html",
                FilterRule.CacheState.NORMAL);
        assertEquals("sm9-cached", result.rendered, "idGroup第2値補完");
    }

    private static void legacyCacheBranches(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "legacy-cache.txt", HEADER +
                "[Replace]\nName = branch\nURL = example\\.com/\nidGroup = 1\nMatch<\n(?:id=(sm\\d+)|none)\n>\n" +
                "Replace<\nnormal<$>economy\n>\n" +
                "[Replace]\nName = no-cache-null\nURL = example\\.com/\nidGroup = !1\nMatch<\n(?:id=(sm\\d+)|empty)\n>\nReplace<\nmissing\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        SimulationResult normal = simulate(repository, List.of(parsed.rules.get(0)), "id=sm9", "https://example.com/",
                "text/html", FilterRule.CacheState.NORMAL);
        SimulationResult economy = simulate(repository, List.of(parsed.rules.get(0)), "id=sm9", "https://example.com/",
                "text/html", FilterRule.CacheState.ECONOMY);
        SimulationResult nullNoCache = simulate(repository, List.of(parsed.rules.get(1)), "empty", "https://example.com/",
                "text/html", FilterRule.CacheState.NORMAL);
        assertEquals("normal", normal.rendered, "通常単独分岐");
        assertEquals("economy", economy.rendered, "エコノミー単独分岐");
        assertEquals("missing", nullNoCache.rendered, "null noCache分岐");
    }

    private static void stateAndHeaderMacros(Path repository, Path temporary) throws Exception {
        Path list = temporary.resolve("macro-list.txt");
        Files.writeString(list, "# comment\ncat\nd.g\n", StandardCharsets.UTF_8);
        String relativeList = repository.getParent().relativize(list).toString().replace('\\', '/');
        Path file = write(temporary, "supported-state.txt", HEADER +
                "[Replace]\nName = list\nURL = example\\.com/\nAddList = x.lst\nMatch<\na\n>\nReplace<\nb\n>\n" +
                "[Replace]\nName = condition\nURL = example\\.com/\nRequire = $LST(\"" + relativeList + "\")\nMatch<\n(cat)\n>\nReplace<\nlist-$1\n>\n" +
                "[Replace]\nName = consume-added-list\nURL = example\\.com/\nMatch<\n$LST(\"x.lst\")\n>\nReplace<\nseen-$1\n>\n" +
                "[Replace]\nName = header-ref\nURL = example\\.com/\nRequireHeader = (GET)\nMatch<\na\n>\nReplace<\n$RequireHeader1\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        assertEquals("matched", ParserConformance.compare(repository, file, parsed).status(), "状態マクロ構文互換");
        assertEquals(4, parsed.rules.size(), "対応ルール数");
        assertTrue(parsed.rules.stream().allMatch(rule -> rule.simulationSupported), "状態ルールを疑似実行可能");
        SimulationResult result = simulate(repository, parsed.rules, "a b cat", "https://example.com/", "text/html",
                FilterRule.CacheState.NONE);
        assertEquals("GET seen-b list-cat", result.rendered, "RequireHeader参照とLST条件");
        assertEquals(List.of("b"), result.listAdditions.get("x.lst"), "AddList疑似結果");
    }

    private static void dynamicStateAndRequestHeader(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "dynamic-state.txt", HEADER +
                "[RequestHeader]\nName = redirect\nEachLine = TRUE\nMatch<\nhttps://example\\.com/old/(.*)\n>\nReplace<\nhttps://example.com/new/$1\n>\n" +
                "[Replace]\nName = state\nURL = example\\.com/new/\nMulti = TRUE\nMatch<\n$SET(value=ok)$INC(count)(item)\n>\nReplace<\n<nlVar:value>-$1-<nlVar:count>\n>\n" +
                "[Replace]\nName = collect\nURL = example\\.com/new/\nMulti = TRUE\nAddVariable = joined\nMatch<\n(x)\n>\nReplace<\n$1\n>\n" +
                "[Replace]\nName = use-var\nURL = example\\.com/new/\nMatch<\nEND\n>\nReplace<\n<nlVar:joined>\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        assertEquals("matched", ParserConformance.compare(repository, file, parsed).status(), "RequestHeader構文互換");
        SimulationResult result = simulate(repository, parsed.rules, "item item xx END",
                "https://example.com/old/page", "text/html", FilterRule.CacheState.NONE);
        assertEquals("https://example.com/new/page", result.effectiveUrl, "RequestHeader URL");
        assertEquals("ok-item-1 ok-item-2 xx xx", result.rendered, "SET/INC/AddVariable");
        assertEquals("xx", result.variables.get("joined"), "AddVariable値");
    }

    private static void advancedReplacementMacros(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "advanced-macros.txt", HEADER +
                "[Replace]\nName = nest\nURL = www\\.nicovideo\\.jp/watch/\nMatch<\n$NEST(<div[^>]*>,target,</div>)\n>\nReplace<\nX\n>\n" +
                "[Replace]\nName = case\nURL = www\\.nicovideo\\.jp/watch/\nMatch<\nCASE\n>\nReplace<\n<nlcase \"b\"><when \"a\">A<when \"b\">B<when else>Z</nlcase>-<smid>-<id>-<memoryId>\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        assertEquals("matched", ParserConformance.compare(repository, file, parsed).status(), "$NEST構文互換");
        SimulationResult result = simulate(repository, parsed.rules,
                "<div><div>target</div></div> CASE", "https://www.nicovideo.jp/watch/sm9", "text/html",
                FilterRule.CacheState.NONE);
        assertEquals("<div>X</div> B-sm9-9-sm9", result.rendered, "$NEST/nlcase/watch変数");
    }

    private static void environmentDependentMacros(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "environment-dependent.txt", HEADER +
                "[Replace]\nName = reencoded\nURL = example\\.com/\nMatch<\nx\n>\nReplace<\n$REENCODED(sm9)-$REENCODED_BITRATE(sm9)\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        assertEquals(1, parsed.rules.size(), "実環境依存ルール数");
        assertTrue(parsed.rules.get(0).simulationSupported, "実キャッシュ依存マクロを明示モック可能");
        SimulationResult result = new FilterEngine(repository).simulate(parsed.rules,
                new SimulationRequest("test", "https://example.com/", "text/html", 200,
                        FilterRule.CacheState.NONE, false, "true", 3200), "x");
        assertEquals("true-3200", result.rendered, "再エンコードモック");
    }

    private static void macroCompatibility(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "macro-compat.txt", HEADER +
                "[Replace]\nName = macros\nFullURL = https://example\\.com/(foo)?\nMatch<\nx\n>\nReplace<\n[$URL1][$TS()]\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        String rendered = simulate(repository, parsed.rules, "x", "https://example.com/", "text/html",
                FilterRule.CacheState.NONE).rendered;
        assertTrue(rendered.matches("\\[\\]\\[\\d{10}\\]"), "optional URL groupとTS: " + rendered);
    }

    private static void appendSections(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "append.txt", HEADER +
                "[Style]\nName = style\nURL = example\\.com/\nAppend<\n.test { color: red; }\n>\n" +
                "[Script]\nName = script\nURL = example\\.com/\nAppend<\nwindow.tested = true;\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        SimulationResult result = simulate(repository, parsed.rules, "<html><head></head><body></body></html>",
                "https://example.com/", "text/html", FilterRule.CacheState.NONE);
        assertContains(result.rendered, "<style type=\"text/css\">", "Styleタグ");
        assertTrue(result.rendered.indexOf(".test { color: red; }") < result.rendered.indexOf("</head>"), "Style挿入位置");
        assertTrue(result.rendered.indexOf("window.tested = true;") < result.rendered.indexOf("</body>"), "Script挿入位置");
    }

    private static void appendCompatibility(Path repository, Path temporary) throws Exception {
        Path upper = write(temporary, "append-upper.txt", HEADER +
                "[Style]\nName = style\nURL = example\\.com/\nEachLine = TRUE\nReplaceOnly = FALSE\nAppend<\n.x{content:\"<nlVar:VERSION>\"}\n>\n");
        ParseResult parsed = new FilterParser().parse(upper);
        assertTrue(parsed.rules.get(0).replaceOnly && !parsed.rules.get(0).eachLine, "Append強制フラグ");
        String lower = "<html><head></head><body></body></html>";
        assertContains(simulate(repository, parsed.rules, lower, "https://example.com/", "text/html",
                FilterRule.CacheState.NONE).rendered, "<nlVar:VERSION>", "Append本文はマクロ展開しない");
        String html = "<HTML><HEAD></HEAD><BODY></BODY></HTML>";
        assertEquals(html, simulate(repository, parsed.rules, html, "https://example.com/", "text/html",
                FilterRule.CacheState.NONE).rendered, "大文字HEADには挿入しない");

        Path invalid = write(temporary, "append-invalid.txt", HEADER +
                "[Replace]\nName = invalid\nURL = example\\.com/\nAppend<\nx\n>\n");
        ParseResult invalidResult = new FilterParser().parse(invalid);
        assertDiagnostic(invalidResult, "invalid-append");
        assertEquals(0, invalidResult.rules.size(), "不正Appendルール数");

        Path special = write(temporary, "append-special.txt", HEADER +
                "[Script]\nName = external\nURL = example\\.com/\nAppend<\nhttps://www.nicovideo.jp/local/test.js\n>\n");
        ParseResult specialResult = new FilterParser().parse(special);
        assertEquals(FilterRule.Section.REPLACE, specialResult.rules.get(0).section, "特殊Appendセクション");
        assertEquals("script: external", specialResult.rules.get(0).name, "特殊Append名");
        assertContains(specialResult.rules.get(0).replacements.get(0),
                "<script type=\"text/javascript\" src=\"https://www.nicovideo.jp/$TS(local/test.js)\"></script>",
                "特殊Append変換");
        assertEquals("matched", ParserConformance.compare(repository, special, specialResult).status(),
                "特殊Append本体互換");
    }

    private static void parserSourceBaseline(Path repository) {
        Path labRoot = Path.of(System.getProperty("nlfilterlab.root", "tools/nlfilter-lab")).toAbsolutePath().normalize();
        ParserCompatibility.Report report = ParserCompatibility.inspect(repository, labRoot);
        assertEquals("matched", report.statusName(), "parser source status");
    }

    private static void parserSourceChanges(Path repository, Path temporary) throws Exception {
        Path labRoot = Path.of(System.getProperty("nlfilterlab.root", "tools/nlfilter-lab")).toAbsolutePath().normalize();
        String baseline = Files.readString(labRoot.resolve("parser-baseline.properties"), StandardCharsets.UTF_8);

        Path mismatchLab = temporary.resolve("parser-mismatch-lab");
        Files.createDirectories(mismatchLab);
        Files.writeString(mismatchLab.resolve("parser-baseline.properties"),
                baseline.replaceFirst("EasyRewriter\\.java=[0-9a-f]+", "EasyRewriter.java=00"), StandardCharsets.UTF_8);
        assertEquals("mismatch", ParserCompatibility.inspect(repository, mismatchLab).statusName(), "hash mismatch");

        Path partialParent = temporary.resolve("parser-partial");
        Path partialRepository = partialParent.resolve("nlFilters");
        Path partialSource = partialParent.resolve("src/dareka/processor/impl/EasyRewriter.java");
        Path partialLab = temporary.resolve("parser-partial-lab");
        Files.createDirectories(partialRepository);
        Files.createDirectories(partialSource.getParent());
        Files.createDirectories(partialLab);
        Files.copy(repository.getParent().resolve("src/dareka/processor/impl/EasyRewriter.java"), partialSource,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(partialLab.resolve("parser-baseline.properties"), baseline, StandardCharsets.UTF_8);
        assertEquals("source-partial", ParserCompatibility.inspect(partialRepository, partialLab).statusName(),
                "partial source");
    }

    private static void productionParserOracle(Path repository) throws Exception {
        int productionRules = 0;
        for (Path file : RepositoryFilters.tracked(repository)) {
            ProductionParserOracle.Result result = ProductionParserOracle.parse(repository, file);
            assertTrue(result.available(), "production parser unavailable: " + result.reason());
            productionRules += result.rules().size();
            ParseResult lab = new FilterParser().parse(file);
            ParserConformance.Report conformance = ParserConformance.compare(repository, file, lab);
            assertEquals("matched", conformance.status(), file.getFileName() + ": " + conformance.differences());
        }
        assertEquals(40, productionRules, "production parser rule count");
    }

    private static void parserOptionCorpus(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "all-options.txt", HEADER +
                "[Config]\nName = config\nMatch<\nkey\n>\nReplace<\nvalue\n>\n" +
                "[Replace]\nName = options\nFullURL = https://example\\.com/(watch)/\nStatusCode = 200, 404\n" +
                "ContentType = !image/\nRequire = !blocked\nRequireHeader = (Agent)\nMatchLocal = TRUE\n" +
                "Multi = TRUE\nEachLine = FALSE\nReplaceOnly = TRUE\nReplaceDelay = TRUE\nDebug = TRUE\n" +
                "idGroup = !1,URL1\nAddList = list.txt\nAddVariable = variable\n" +
                "Match<\n(sm\\d+)\n>\nReplace<\n\\$1 literal\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        ParserConformance.Report report = ParserConformance.compare(repository, file, parsed);
        assertEquals("matched", report.status(), "all options: " + report.differences());
        assertTrue(parsed.rules.get(1).idGroup2 == -1, "非数字idGroup第2値の内部表現");
    }

    private static void productionExecutionCorpus(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "execution-corpus.txt", HEADER +
                "[Replace]\nName = state-case\nURL = example\\.com/\nMulti = TRUE\n" +
                "Match<\n$SET(mode=b)$INC(n)(item)\n>\n" +
                "Replace<\n<nlcase \"b\"><when \"a\">A<when \"b\">B<when else>Z</nlcase>-$1-<nlVar:n>\n>\n" +
                "[Replace]\nName = nest\nURL = example\\.com/\nMatch<\n$NEST(<div[^>]*>,target,</div>)\n>\nReplace<\nN\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        String input = "item item <div><div>target</div></div>";
        String lab = simulate(repository, parsed.rules, input, "https://example.com/", "text/html",
                FilterRule.CacheState.NONE).rendered;
        ProductionParserOracle.ExecutionResult production = ProductionParserOracle.executePure(repository, file,
                "https://example.com/", input);
        assertTrue(production.available(), "production execution unavailable: " + production.reason());
        assertEquals(production.content(), lab, "production execution result");
    }

    private static void crlfReplacement(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "crlf.txt", HEADER +
                "[Replace]\nName = crlf\nURL = example\\.com/\nMatch<\nmarker\n>\nReplace<\nfirst\nsecond<CRLF>third\n>\n");
        ParseResult parsed = new FilterParser().parse(file);
        SimulationResult result = simulate(repository, parsed.rules, "marker", "https://example.com/", "text/html",
                FilterRule.CacheState.NONE);
        assertEquals("first\r\nsecond\r\nthird", result.rendered, "CRLF変換");
    }

    private static void trackedOrder(Path repository) throws Exception {
        List<Path> files = RepositoryFilters.tracked(repository);
        List<String> actual = files.stream().map(path -> path.getFileName().toString()).toList();
        List<String> sorted = new ArrayList<>(actual);
        sorted.sort(String::compareTo);
        assertEquals(sorted, actual, "辞書順");
    }

    private static void serverBoundaries(Path repository) throws Exception {
        Path labRoot = Path.of(System.getProperty("nlfilterlab.root", "tools/nlfilter-lab")).toAbsolutePath().normalize();
        LabServer server = new LabServer(repository, labRoot, 0);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.port();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> config = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, config.statusCode(), "config status");
            assertContains(config.body(), "\"parserCompatibility\":{\"status\":\"matched\"",
                    "config parser compatibility");

            HttpResponse<String> sandboxOrigin = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config"))
                            .header("Origin", "null").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, sandboxOrigin.statusCode(), "sandbox Origin許可");
            assertEquals("null", sandboxOrigin.headers().firstValue("Access-Control-Allow-Origin").orElse(""),
                    "sandbox CORS応答");

            HttpResponse<String> foreign = client.send(HttpRequest.newBuilder(URI.create(base + "/api/render"))
                            .header("Origin", "https://example.com")
                            .POST(HttpRequest.BodyPublishers.ofString("fixture=watch"))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, foreign.statusCode(), "外部Origin拒否");
            assertTrue(foreign.headers().firstValue("Access-Control-Allow-Origin").isEmpty(), "外部OriginへCORSを許可しない");

            HttpResponse<String> oversized = client.send(HttpRequest.newBuilder(URI.create(base + "/api/render"))
                            .POST(HttpRequest.BodyPublishers.ofString("x".repeat(1024 * 1024 + 1)))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, oversized.statusCode(), "大きすぎる本文を拒否");

            HttpResponse<String> traversal = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/local/%2e%2e/config.properties")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, traversal.statusCode(), "localパストラバーサル拒否");
        } finally {
            server.stop();
        }
    }

    private static void trimMode(Path repository, Path temporary) throws Exception {
        Path file = write(temporary, "trim-mode.txt", HEADER +
                "[Replace] \n  Name = trim  \n  URL = example\\.com/  \n  Match<  \n  foo  \n  >  \n  Replace<  \n  bar  \n  >  \n");
        ParseResult parsed = new FilterParser().parse(file);
        assertTrue(!parsed.hasErrors(), "trimモード構文");
        SimulationResult result = simulate(repository, parsed.rules, "foo", "https://example.com/", "text/html",
                FilterRule.CacheState.NONE);
        assertEquals("bar", result.rendered, "trimモード置換");
    }

    private static void freeSpaceVariable(Path repository) {
        ParseResult parsed = new FilterParser().parse(repository.resolve("05_topBarFilter.txt"));
        SimulationResult result = simulate(repository, parsed.rules, "<html><head></head><body></body></html>",
                "https://www.nicovideo.jp/watch/sm9", "text/html", FilterRule.CacheState.NONE);
        assertContains(result.rendered, "if (128.0 < 3)", "freeSpace数値");
    }

    private static SimulationResult simulate(Path repository, List<FilterRule> rules, String content, String url,
            String contentType, FilterRule.CacheState state) {
        return new FilterEngine(repository).simulate(rules,
                new SimulationRequest("test", url, contentType, 200, state, false), content);
    }

    private static Path write(Path directory, String name, String content) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static void assertDiagnostic(ParseResult result, String code) {
        assertTrue(result.diagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals(code)), "診断 " + code);
    }

    private static void assertContains(String actual, String expected, String label) {
        assertTrue(actual.contains(expected), label + ": " + actual);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void run(String name, CheckedRunnable runnable) throws Exception {
        runnable.run();
        passed++;
        System.out.println("PASS " + name);
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
