package nlfilterlab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class FilterParser {
    private static final String REQUIRED_HEADER = "# nlフィルタ定義(文字コード判定用なのでこの行は削除しないこと)";
    private static final Pattern REPLACEMENT_GROUP = Pattern.compile("(?<![\\\\$])\\$(\\d+)");
    private static final Pattern URL_GROUP = Pattern.compile("(?<!\\\\)\\$URL(\\d+)(?!\\d)");
    private static final Pattern REQUIRE_HEADER_GROUP = Pattern.compile("(?<!\\\\)\\$RequireHeader(\\d+)(?!\\d)");
    private static final Pattern UNSUPPORTED_MACRO = Pattern.compile("(?!)");

    private enum State {
        OUTSIDE,
        OPTIONS,
        MATCH,
        REPLACE
    }

    ParseResult parse(Path file) {
        ParseResult result = new ParseResult();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            result.diagnostics.add(error(file, 0, "io", "読み込みに失敗しました: " + exception.getMessage()));
            return result;
        }

        if (lines.isEmpty() || !REQUIRED_HEADER.equals(lines.get(0))) {
            String detail = !lines.isEmpty() && lines.get(0).startsWith("\uFEFF")
                    ? "UTF-8 BOM が付いています。先頭行はBOMなしで保存してください"
                    : "文字コード判定用の先頭行がありません";
            result.diagnostics.add(error(file, 1, "required-header", detail));
        }

        State state = State.OUTSIDE;
        FilterRule current = null;
        StringBuilder block = new StringBuilder();
        int blockLine = 0;
        int ruleDiagnosticStart = 0;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int lineNumber = index + 1;

            if (state != State.OUTSIDE && current != null && current.trimNeeded) {
                line = line.trim();
            }

            if (state == State.MATCH || state == State.REPLACE) {
                if (line.equals(">")) {
                    if (state == State.MATCH) {
                        finishMatch(current, block.toString(), blockLine, result);
                        state = State.OPTIONS;
                    } else {
                        finishReplace(current, block.toString(), blockLine, result);
                        validateRule(current, result);
                        if (current != null && !hasErrorSince(result, ruleDiagnosticStart)) {
                            result.rules.add(current);
                        }
                        current = null;
                        state = State.OUTSIDE;
                    }
                    block.setLength(0);
                    continue;
                }
                appendBlockLine(block, line, current != null && current.eachLine, state == State.REPLACE);
                continue;
            }

            String sectionCandidate = line.startsWith("[") ? line.trim() : line;
            FilterRule.Section section = FilterRule.Section.parse(sectionCandidate);
            if (state == State.OUTSIDE) {
                if (section != null) {
                    current = new FilterRule(file, lineNumber, section);
                    ruleDiagnosticStart = result.diagnostics.size();
                    current.trimNeeded = !line.equals(sectionCandidate);
                    state = State.OPTIONS;
                } else if (sectionCandidate.equalsIgnoreCase("[Debug]")) {
                    // 本体同様、グローバルDebug指定として受理する。
                } else if (line.startsWith("[") && sectionCandidate.matches("\\[[^]]+]")) {
                    result.diagnostics.add(error(file, lineNumber, "unknown-section",
                            "未知のセクションです: " + sectionCandidate));
                }
                continue;
            }

            if (line.startsWith("#")) {
                continue;
            }
            if (section != null) {
                result.diagnostics.add(error(file, lineNumber, "incomplete-rule",
                        "前の " + current.section.label() + " が Replace< ... > で完了していません"));
                current = new FilterRule(file, lineNumber, section);
                ruleDiagnosticStart = result.diagnostics.size();
                current.trimNeeded = !line.equals(sectionCandidate);
                continue;
            }
            if (line.equalsIgnoreCase("Match<")) {
                block.setLength(0);
                blockLine = lineNumber;
                state = State.MATCH;
                continue;
            }
            if (line.equalsIgnoreCase("Replace<")) {
                block.setLength(0);
                blockLine = lineNumber;
                state = State.REPLACE;
                continue;
            }
            if (line.equalsIgnoreCase("Append<")) {
                if (current.section != FilterRule.Section.SCRIPT && current.section != FilterRule.Section.STYLE) {
                    result.diagnostics.add(error(file, lineNumber, "invalid-append",
                            "Append< は [Script] または [Style] で使用してください"));
                } else {
                    current.appendBlock = true;
                    current.eachLine = false;
                    current.replaceOnly = true;
                    current.rawMatches.add(current.section == FilterRule.Section.STYLE ? "(?=</head>)" : "(?=</body>)");
                    current.matches.add(Pattern.compile(current.rawMatches.get(0)));
                }
                block.setLength(0);
                blockLine = lineNumber;
                state = State.REPLACE;
                continue;
            }
            if (!parseOption(current, line, lineNumber, result)) {
                result.diagnostics.add(error(file, lineNumber, "syntax", "解釈できない行です: " + line));
                current = null;
                state = State.OUTSIDE;
            }
        }

        if (state == State.MATCH || state == State.REPLACE) {
            result.diagnostics.add(error(file, blockLine, "unclosed-block", "ブロックを閉じる単独行の > がありません"));
        } else if (state == State.OPTIONS && current != null) {
            result.diagnostics.add(error(file, current.sectionLine, "incomplete-rule",
                    current.section.label() + " が Replace< または Append< で完了していません"));
        }
        return result;
    }

    private static void appendBlockLine(StringBuilder block, String line, boolean eachLine, boolean replacement) {
        if (eachLine && block.length() > 0) {
            block.append('\u0000');
        }
        block.append(line);
        if (!eachLine && replacement) {
            block.append("\r\n");
        }
    }

    private static void finishMatch(FilterRule rule, String raw, int line, ParseResult result) {
        if (rule == null) {
            return;
        }
        for (String expression : splitEachLine(raw, rule.eachLine)) {
            rule.rawMatches.add(expression);
            try {
                Matcher nest = DynamicPatternSupport.NEST.matcher(expression);
                if (nest.matches()) {
                    Pattern.compile("(" + nest.group(1) + ")|(" + nest.group(3) + ")");
                    Pattern.compile(nest.group(2));
                    rule.matches.add(Pattern.compile("(?!)"));
                } else {
                    rule.matches.add(Pattern.compile(DynamicPatternSupport.prepareForCompile(expression)));
                }
            } catch (PatternSyntaxException exception) {
                result.diagnostics.add(error(rule.file, line, "pattern",
                        "Match の Java 正規表現が不正です: " + exception.getDescription()));
            }
        }
    }

    private static void finishReplace(FilterRule rule, String raw, int line, ParseResult result) {
        if (rule == null) {
            return;
        }
        if (!rule.eachLine && raw.endsWith("\r\n")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        if (rule.appendBlock && raw.matches("(?s)^https?://.*")) {
            StringBuilder transformed = new StringBuilder();
            for (String appendLine : raw.split("\\r\\n", -1)) {
                String value = appendLine;
                if (appendLine.matches("https?://.*")) {
                    Matcher local = Pattern.compile("(https?://[^/]+\\.nicovideo\\.jp/)(local/[^?]+)")
                            .matcher(appendLine);
                    if (local.matches()) value = local.group(1) + "$TS(" + local.group(2) + ")";
                    value = rule.section == FilterRule.Section.STYLE
                            ? "<link rel=\"stylesheet\" type=\"text/css\" href=\"" + value + "\" />"
                            : "<script type=\"text/javascript\" src=\"" + value + "\"></script>";
                }
                if (transformed.length() > 0) transformed.append("\r\n");
                transformed.append(value);
            }
            rule.section = FilterRule.Section.REPLACE;
            rule.name = "script: " + rule.name;
            rule.replaceOnly = false;
            raw = transformed + "<CRLF>";
        }
        raw = raw.replace("<CRLF>", "\r\n").replace("<TAB>", "\t");
        rule.replacements.addAll(splitEachLine(raw, rule.eachLine));
        if (rule.eachLine && rule.rawMatches.size() != rule.replacements.size()) {
            result.diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, rule.file, line,
                    "each-line-count", "EachLine の Match は " + rule.rawMatches.size() + " 行、Replace は " +
                    rule.replacements.size() + " 行です。行数を一致させてください"));
        }
    }

    private static List<String> splitEachLine(String raw, boolean eachLine) {
        if (!eachLine) {
            return List.of(raw);
        }
        String[] values = raw.split("\\x00");
        List<String> result = new ArrayList<>(List.of(values));
        if (result.isEmpty()) {
            result.add("");
        }
        return result;
    }

    private static boolean parseOption(FilterRule rule, String line, int lineNumber, ParseResult result) {
        String[] parts = line.trim().split("\\s*=\\s*", 2);
        if (parts.length != 2) {
            return false;
        }
        String key = parts[0].toLowerCase(Locale.ROOT);
        String value = parts[1];
        try {
            switch (key) {
                case "multi" -> rule.multi = parseBoolean(rule, lineNumber, key, value, result);
                case "eachline" -> rule.eachLine = parseBoolean(rule, lineNumber, key, value, result);
                case "url" -> {
                    rule.rawUrl = "https?://(?:" + value + ")";
                    rule.urlPattern = Pattern.compile(rule.rawUrl);
                }
                case "fullurl" -> {
                    rule.rawUrl = value;
                    rule.urlPattern = Pattern.compile(value);
                }
                case "statuscode" -> {
                    String[] values = value.split("\\s*,\\s*");
                    rule.statusCodes = new int[values.length];
                    for (int i = 0; i < values.length; i++) {
                        rule.statusCodes[i] = Integer.parseInt(values[i]);
                    }
                }
                case "name" -> rule.name = value;
                case "require" -> {
                    rule.rawRequire = value;
                    rule.require = compileCondition(value);
                }
                case "idgroup" -> parseIdGroup(rule, value, lineNumber, result);
                case "requireheader" -> {
                    rule.rawRequireHeader = value;
                    rule.requireHeader = compileCondition(value);
                }
                case "contenttype" -> {
                    rule.rawContentType = value;
                    rule.contentType = compileCondition(value);
                }
                case "matchlocal" -> rule.matchLocal = parseBoolean(rule, lineNumber, key, value, result);
                case "addlist" -> rule.addList = value;
                case "addvariable" -> rule.addVariable = value;
                case "replaceonly" -> rule.replaceOnly = parseBoolean(rule, lineNumber, key, value, result);
                case "replacedelay" -> rule.replaceDelay = parseBoolean(rule, lineNumber, key, value, result);
                case "debug" -> rule.debug = parseBoolean(rule, lineNumber, key, value, result);
                default -> {
                    return false;
                }
            }
        } catch (PatternSyntaxException exception) {
            result.diagnostics.add(error(rule.file, lineNumber, "pattern",
                    parts[0] + " の Java 正規表現が不正です: " + exception.getDescription()));
        } catch (NumberFormatException exception) {
            result.diagnostics.add(error(rule.file, lineNumber, "number", parts[0] + " は整数で指定してください"));
        }
        return true;
    }

    private static FilterRule.Condition compileCondition(String value) {
        boolean negated = value.startsWith("!");
        String expression = negated ? value.substring(1) : value;
        return new FilterRule.Condition(Pattern.compile(DynamicPatternSupport.prepareForCompile(expression)), negated);
    }

    private static boolean parseBoolean(FilterRule rule, int line, String key, String value, ParseResult result) {
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            result.diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, rule.file, line,
                    "boolean", key + " は TRUE または FALSE を推奨します。現在の本体では FALSE と解釈されます"));
        }
        return Boolean.parseBoolean(value);
    }

    private static void parseIdGroup(FilterRule rule, String value, int line, ParseResult result) {
        String[] values = value.split("\\s*,\\s*");
        String first = values[0];
        if (first.startsWith("!")) {
            rule.noCache = true;
            first = first.substring(1);
        }
        if (!first.matches("\\d+")) {
            result.diagnostics.add(error(rule.file, line, "id-group", "idGroup の第1引数は整数で指定してください"));
            return;
        }
        rule.idGroup = Integer.parseInt(first);
        if (values.length == 1) {
            rule.idGroup2 = 0;
        } else {
            rule.idGroupSecondRaw = values[1];
            if (values[1].matches("\\d+")) {
                rule.idGroup2 = Integer.parseInt(values[1]);
            }
        }
        if (values.length > 2) {
            result.diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, rule.file, line,
                    "id-group", "idGroup の第3引数以降は本体で使用されません"));
        }
    }

    private static void validateRule(FilterRule rule, ParseResult result) {
        if (rule == null) {
            return;
        }
        if (rule.name == null || rule.name.isBlank()) {
            result.diagnostics.add(error(rule.file, rule.sectionLine, "required-name", "Name がありません"));
        }
        if (rule.matches.isEmpty()) {
            result.diagnostics.add(error(rule.file, rule.sectionLine, "required-match", "Match< ... > がありません"));
        }
        if (rule.replacements.isEmpty()) {
            result.diagnostics.add(error(rule.file, rule.sectionLine, "required-replace", "Replace< または Append< がありません"));
        }
        if (rule.urlPattern == null && rule.section != FilterRule.Section.REQUEST_HEADER &&
                rule.section != FilterRule.Section.CONFIG) {
            result.diagnostics.add(error(rule.file, rule.sectionLine, "required-url", "URL または FullURL がありません"));
        }
        if (rule.requireHeader != null) {
            result.diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, rule.file, rule.sectionLine,
                    "simulation-mock", "RequireHeader は疑似リクエストヘッダーで評価します。実ブラウザーのCookie等は含みません"));
        }
        if (rule.section == FilterRule.Section.CONFIG) {
            result.diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, rule.file, rule.sectionLine,
                    "simulation-limit", rule.section.label() + " の状態変更はローカルテスターでは実行しません"));
        }
        if (rule.idGroupSecondRaw != null && !rule.idGroupSecondRaw.matches("\\d+")) {
            rule.simulationSupported = false;
            result.diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, rule.file, rule.sectionLine,
                    "simulation-limit", "idGroup の非数字第2引数は拡張依存のため疑似適用をスキップします"));
        }

        int urlGroups = rule.urlPattern == null ? -1 : rule.urlPattern.matcher("").groupCount();
        int requireHeaderGroups = rule.requireHeader == null ? -1 : rule.requireHeader.pattern().matcher("").groupCount();
        for (int index = 0; index < rule.rawMatches.size(); index++) {
            int groups = index < rule.matches.size() ? rule.matches.get(index).matcher("").groupCount() : -1;
            if (rule.idGroup > groups && groups >= 0) {
                result.diagnostics.add(error(rule.file, rule.sectionLine, "id-group-range",
                        "idGroup=" + rule.idGroup + " は Match のキャプチャ数 " + groups + " を超えています"));
            }
            if (rule.idGroup2 > groups && groups >= 0) {
                result.diagnostics.add(error(rule.file, rule.sectionLine, "id-group-range",
                        "idGroup 第2引数=" + rule.idGroup2 + " は Match のキャプチャ数 " + groups + " を超えています"));
            }
            String replacement = index < rule.replacements.size() ? rule.replacements.get(index) : "";
            Matcher groupMatcher = REPLACEMENT_GROUP.matcher(replacement);
            while (groupMatcher.find()) {
                int reference = Integer.parseInt(groupMatcher.group(1));
                int firstDigit = groupMatcher.group(1).charAt(0) - '0';
                if (groups >= 0 && reference > groups && firstDigit > groups) {
                    result.diagnostics.add(error(rule.file, rule.sectionLine, "replacement-group-range",
                            "$" + reference + " は Match のキャプチャ数 " + groups + " を超えています"));
                }
            }
            Matcher urlMatcher = URL_GROUP.matcher(replacement);
            while (urlMatcher.find()) {
                int reference = Integer.parseInt(urlMatcher.group(1));
                if (urlGroups >= 0 && reference > urlGroups) {
                    result.diagnostics.add(error(rule.file, rule.sectionLine, "url-group-range",
                            "$URL" + reference + " は URL のキャプチャ数 " + urlGroups + " を超えています"));
                }
            }
            Matcher headerMatcher = REQUIRE_HEADER_GROUP.matcher(replacement);
            while (headerMatcher.find()) {
                int reference = Integer.parseInt(headerMatcher.group(1));
                if (requireHeaderGroups < 0) {
                    result.diagnostics.add(error(rule.file, rule.sectionLine, "require-header-missing",
                            "$RequireHeader" + reference + " を使うには RequireHeader が必要です"));
                } else if (reference > requireHeaderGroups) {
                    result.diagnostics.add(error(rule.file, rule.sectionLine, "require-header-group-range",
                            "$RequireHeader" + reference + " は RequireHeader のキャプチャ数 " +
                                    requireHeaderGroups + " を超えています"));
                }
            }
            if (UNSUPPORTED_MACRO.matcher(replacement).find() ||
                    UNSUPPORTED_MACRO.matcher(rule.rawMatches.get(index)).find()) {
                rule.simulationSupported = false;
                result.diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, rule.file, rule.sectionLine,
                        "unsupported-macro", "未対応または状態を持つマクロがあるため、ローカルテスターではこのルールをスキップします"));
            }
        }
    }

    private static boolean hasErrorSince(ParseResult result, int start) {
        return result.diagnostics.subList(Math.min(start, result.diagnostics.size()), result.diagnostics.size())
                .stream().anyMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR);
    }

    private static Diagnostic error(Path file, int line, String code, String message) {
        return new Diagnostic(Diagnostic.Severity.ERROR, file, line, code, message);
    }
}
