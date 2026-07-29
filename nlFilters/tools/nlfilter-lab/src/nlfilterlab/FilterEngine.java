package nlfilterlab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FilterEngine {
    private record NestApplyResult(String content, int replacements) {
    }

    private static final Pattern CACHE_SPLIT = Pattern.compile(
            "^([\\s\\S]*?)\\n*<\\$>\\n*([\\s\\S]*)<\\$>\\n*([\\s\\S]*)<\\$>\\n*([\\s\\S]*)$");
    private static final Pattern INLINE_CACHE_SPLIT = Pattern.compile(
            "^([\\s\\S]*?)\\n*<([^<>]*?)\\$\\$([^<>]*?)\\$\\$([^<>]*?)\\$\\$([^<>]*?)>\\n*([\\s\\S]*)$");
    private static final Pattern LEGACY_CACHE_SPLIT = Pattern.compile(
            "^([\\s\\S]*?)\\n*<(\\w*)\\$(\\w*)>\\n*([\\s\\S]*)$");
    private static final Pattern URL_MACRO = Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*\\$URL(\\d+)(?!\\d)");
    private static final Pattern TS_MACRO = Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*\\$TS\\(([^?)]*)(\\?[^)]+)?\\)");
    private static final Pattern NL_VARIABLE = Pattern.compile("<nlVar:([^>]+)>");
    private static final Pattern REENCODED_MACRO = Pattern.compile(
            "(?<!\\\\)(?:\\\\\\\\)*\\$REENCODED\\(([^)]*)\\)");
    private static final Pattern REENCODED_BITRATE_MACRO = Pattern.compile(
            "(?<!\\\\)(?:\\\\\\\\)*\\$REENCODED_BITRATE\\(([^)]*)\\)");
    private static final Pattern REQUIRE_HEADER_MACRO = Pattern.compile(
            "(?<!\\\\)(?:\\\\\\\\)*\\$RequireHeader(\\d+)(?!\\d)");
    private static final Pattern CASE_PATTERN = Pattern.compile(
            "<nlcase\\s+\"((?:(?!\">).)*?)\">((?:(?!<nlcase\\b).)*?)</nlcase>", Pattern.DOTALL);
    private static final Pattern CASE_WHEN_PATTERN = Pattern.compile(
            "<when\\s+(?:\"(.*?)\"|else)>(.*?)(?:$|(?=<when))", Pattern.DOTALL);
    private static final Pattern LOCAL_URL = Pattern.compile(
            "^https?://[^/]+\\.nicovideo\\.jp/((?:flv|nico)player\\.swf|flvplayer_wrapper\\.swf|" +
                    "flv_booster\\.swf|local(/?|/[^?]*|\\w+\\.\\w+))(\\?.*)?$");

    private final Path repositoryRoot;
    private final Path localRoot;

    FilterEngine(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
        this.localRoot = repositoryRoot.getParent().resolve("local").normalize();
    }

    SimulationResult simulate(List<FilterRule> rules, SimulationRequest request, String content) {
        SimulationResult result = new SimulationResult(content);
        SimulationContext context = new SimulationContext(request, content);
        applyRequestHeaders(rules, request, context, result);
        SimulationRequest effectiveRequest = new SimulationRequest(request.fixture(), context.effectiveUrl,
                request.contentType(), request.statusCode(), request.cacheState(), request.cacheApiFailure(),
                request.reencoded(), request.reencodedBitrate());
        List<FilterRule> styles = new ArrayList<>();
        List<FilterRule> scripts = new ArrayList<>();
        List<FilterRule> delayed = new ArrayList<>();

        for (FilterRule rule : rules) {
            if (!rule.isResponseRule() || !matchesContext(rule, effectiveRequest, context, result.rendered)) {
                continue;
            }
            if (!rule.simulationSupported) {
                result.traces.add(new SimulationResult.Trace(rule.identifier(), rule.section.label(), 0,
                        "未対応マクロを含むため疑似適用をスキップ"));
                continue;
            }
            if (rule.section == FilterRule.Section.REPLACE) {
                if (rule.replaceDelay) {
                    delayed.add(rule);
                } else {
                    applyReplace(rule, effectiveRequest, context, result);
                }
            } else if (rule.section == FilterRule.Section.STYLE) {
                styles.add(rule);
            } else if (rule.section == FilterRule.Section.SCRIPT) {
                scripts.add(rule);
            }
        }

        appendCollected(styles, "style", "</head>", result);
        appendCollected(scripts, "script", "</body>", result);
        for (FilterRule rule : delayed) {
            applyReplace(rule, effectiveRequest, context, result);
        }
        result.effectiveUrl = context.effectiveUrl;
        result.variables.putAll(context.variables);
        context.listAdditions.forEach((name, values) -> result.listAdditions.put(name, List.copyOf(values)));
        return result;
    }

    private boolean matchesContext(FilterRule rule, SimulationRequest request, SimulationContext context, String content) {
        if (rule.urlPattern == null || !rule.urlPattern.matcher(request.url()).lookingAt()) {
            return false;
        }
        if (LOCAL_URL.matcher(request.url()).matches()) {
            int local = request.url().indexOf("local/");
            if (local > 0 && rule.urlPattern.matcher(request.url().substring(0, local)).lookingAt() &&
                    !rule.matchLocal) return false;
        }
        if (rule.contentType != null && !conditionFind(rule.rawContentType, rule.contentType, request.contentType(), context)) {
            return false;
        }
        if (rule.requireHeader != null && !conditionFind(rule.rawRequireHeader, rule.requireHeader, context.requestHeader, context)) {
            return false;
        }
        if (rule.statusCodes != null) {
            boolean accepted = false;
            for (int statusCode : rule.statusCodes) {
                accepted |= statusCode == request.statusCode();
            }
            if (!accepted) {
                return false;
            }
        } else if (request.statusCode() != 200 && request.statusCode() != 403 &&
                request.statusCode() != 404 && request.statusCode() != 503) {
            return false;
        }
        return rule.require == null || conditionFind(rule.rawRequire, rule.require, content, context);
    }

    private boolean conditionFind(String raw, FilterRule.Condition fallback, String value, SimulationContext context) {
        if (raw == null) return true;
        boolean negated = raw.startsWith("!");
        String expression = negated ? raw.substring(1) : raw;
        boolean found = value != null && DynamicPatternSupport.compile(expression, repositoryRoot.getParent(), context)
                .matcher(value).find();
        return found ^ negated;
    }

    private void applyRequestHeaders(List<FilterRule> rules, SimulationRequest request, SimulationContext context,
            SimulationResult result) {
        for (FilterRule rule : rules) {
            if (rule.section != FilterRule.Section.REQUEST_HEADER) continue;
            if (!rule.simulationSupported) {
                result.traces.add(new SimulationResult.Trace(rule.identifier(), rule.section.label(), 0,
                        "未対応条件のためリクエストURL疑似変換をスキップ"));
                continue;
            }
            int replacements = 0;
            for (int index = 0; index < rule.rawMatches.size(); index++) {
                Pattern pattern = DynamicPatternSupport.compile(rule.rawMatches.get(index), repositoryRoot.getParent(), context);
                Matcher matcher = pattern.matcher(context.effectiveUrl);
                if (!matcher.matches() || !cacheAllows(rule, matcher, request.cacheState())) continue;
                String replacement = index < rule.replacements.size() ? rule.replacements.get(index) : "";
                try {
                    context.effectiveUrl = matcher.replaceFirst(replacement);
                    context.updateRequestHeader(request.contentType());
                    replacements++;
                } catch (RuntimeException exception) {
                    result.diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, rule.file, rule.sectionLine,
                            "request-header-replacement", rule.identifier() + " のURL置換に失敗しました: " +
                            exception.getMessage()));
                }
            }
            result.traces.add(new SimulationResult.Trace(rule.identifier(), rule.section.label(), replacements,
                    replacements == 0 ? "リクエストURLは非該当" : "リクエストURLを疑似変換"));
        }
    }

    private static boolean cacheAllows(FilterRule rule, Matcher matcher, FilterRule.CacheState state) {
        if (rule.idGroup <= 0) return true;
        try {
            String id = matcher.group(rule.idGroup);
            if (id == null && rule.noCache) return true;
            return rule.noCache ? state == FilterRule.CacheState.NONE : state != FilterRule.CacheState.NONE;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void applyReplace(FilterRule rule, SimulationRequest request, SimulationContext context,
            SimulationResult result) {
        int replacedCount = 0;
        String working = result.rendered;
        for (int index = 0; index < rule.rawMatches.size(); index++) {
            String rawMatch = rule.rawMatches.get(index);
            if (DynamicPatternSupport.NEST.matcher(rawMatch).matches()) {
                NestApplyResult nested = applyNest(rule, index, request, context, working, result);
                working = nested.content;
                replacedCount += nested.replacements;
                continue;
            }
            Pattern pattern = DynamicPatternSupport.compile(rawMatch, repositoryRoot.getParent(), context);
            Matcher matcher = pattern.matcher(working);
            if (!matcher.find()) {
                continue;
            }
            StringBuffer output = new StringBuffer(working.length() + 256);
            int currentCount = 0;
            do {
                DynamicPatternSupport.applyStateMacros(rawMatch, context);
                String raw = index < rule.replacements.size() ? rule.replacements.get(index) : "";
                String replacement = selectCacheVariant(rule, matcher, raw, request.cacheState());
                if (replacement != null) {
                    replacement = expandStaticMacros(rule, request, context, replacement);
                    if (replacement == null) {
                        matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
                        if (!rule.multi) break;
                        continue;
                    }
                    try {
                        if (rule.addList != null || rule.addVariable != null) {
                            String value = expandBackReferences(matcher, replacement, null);
                            if (!value.isEmpty()) {
                                if (rule.addList != null) context.addList(rule.addList, value);
                                if (rule.addVariable != null) context.appendVariable(rule.addVariable, value);
                            }
                            matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
                        } else {
                            matcher.appendReplacement(output,
                                    rule.replaceOnly ? Matcher.quoteReplacement(replacement) : replacement);
                        }
                        currentCount++;
                    } catch (RuntimeException exception) {
                        result.diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, rule.file,
                                rule.sectionLine, "replacement", rule.identifier() + " の置換に失敗しました: " +
                                exception.getMessage()));
                        matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
                    }
                } else {
                    matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
                }
                if (!rule.multi) {
                    break;
                }
            } while (matcher.find());
            matcher.appendTail(output);
            if (currentCount > 0) {
                working = output.toString();
                replacedCount += currentCount;
            }
        }
        result.rendered = working;
        result.traces.add(new SimulationResult.Trace(rule.identifier(), rule.section.label(), replacedCount,
                replacedCount == 0 ? "Match または疑似キャッシュ条件が非該当" :
                        (rule.addList != null || rule.addVariable != null ? "状態を疑似更新（本文は維持）" : "本文を置換")));
    }

    private NestApplyResult applyNest(FilterRule rule, int index, SimulationRequest request,
            SimulationContext context, String content, SimulationResult result) {
        Matcher specification = DynamicPatternSupport.NEST.matcher(rule.rawMatches.get(index));
        if (!specification.matches()) return new NestApplyResult(content, 0);
        if (rule.idGroup > 0) {
            result.diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, rule.file, rule.sectionLine,
                    "nest-id-group", "$NEST と idGroup の併用は本体でもグループ参照できません"));
            return new NestApplyResult(content, 0);
        }
        Pattern tags = Pattern.compile("(" + specification.group(1) + ")|(" + specification.group(3) + ")");
        Pattern innerPattern = Pattern.compile(specification.group(2));
        Matcher tagMatcher = tags.matcher(content);
        Deque<int[]> stack = new ArrayDeque<>();
        StringBuilder output = new StringBuilder(content.length() + 128);
        int last = 0;
        int count = 0;
        while (tagMatcher.find()) {
            if (tagMatcher.group(1) != null) {
                stack.push(new int[]{tagMatcher.start(), tagMatcher.end()});
                continue;
            }
            if (stack.isEmpty()) continue;
            int[] start = stack.pop();
            Matcher inner = innerPattern.matcher(content);
            inner.region(start[1], tagMatcher.start());
            if (!inner.find()) continue;
            String raw = index < rule.replacements.size() ? rule.replacements.get(index) : "";
            String replacement = expandStaticMacros(rule, request, context, raw);
            if (replacement == null) continue;
            try {
                String outer = content.substring(start[0], tagMatcher.end());
                String expanded = expandBackReferences(inner, replacement, outer);
                output.append(content, last, start[0]).append(expanded);
                last = tagMatcher.end();
                count++;
                tagMatcher.region(last, content.length());
                stack.clear();
                if (!rule.multi) break;
            } catch (RuntimeException exception) {
                result.diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, rule.file, rule.sectionLine,
                        "nest-replacement", rule.identifier() + " の$NEST置換に失敗しました: " +
                        exception.getMessage()));
                break;
            }
        }
        if (count == 0) return new NestApplyResult(content, 0);
        output.append(content, last, content.length());
        return new NestApplyResult(output.toString(), count);
    }

    private static String selectCacheVariant(FilterRule rule, Matcher contentMatcher, String raw,
            FilterRule.CacheState cacheState) {
        if (rule.idGroup <= 0) {
            return raw;
        }
        String videoId;
        String alternateId = null;
        try {
            videoId = contentMatcher.group(rule.idGroup);
            if (rule.idGroup2 > 0) {
                alternateId = contentMatcher.group(rule.idGroup2);
            }
        } catch (RuntimeException exception) {
            return null;
        }
        if (videoId == null && rule.noCache) {
            return raw;
        }
        if ((videoId == null || videoId.matches("\\d{10,}")) && alternateId != null &&
                alternateId.matches("\\d+")) {
            // 実環境はキャッシュ索引から sm/so/nm 等を解決する。Labはキャッシュ状態が
            // URL全体で固定なので、分岐確認用の決定的なsmidへ補完する。
            videoId = "sm" + alternateId;
        }
        if (videoId == null || videoId.isBlank()) {
            return rule.noCache && cacheState == FilterRule.CacheState.NONE ? raw : null;
        }
        if (rule.noCache) {
            return cacheState == FilterRule.CacheState.NONE ? raw : null;
        }
        if (cacheState == FilterRule.CacheState.NONE) {
            return null;
        }

        String[] variants = splitCacheVariants(raw);
        int variant = switch (cacheState) {
            case NORMAL -> 0;
            case ECONOMY -> 1;
            case DMC -> 2;
            case DMC_ECONOMY -> 3;
            case NONE -> 0;
        };
        return variants[variant].replace("<eachSmid>", videoId);
    }

    private static String[] splitCacheVariants(String raw) {
        String[] variants = {raw, raw, raw, raw};
        Matcher matcher = CACHE_SPLIT.matcher(raw);
        if (matcher.matches()) {
            for (int i = 0; i < 4; i++) {
                variants[i] = matcher.group(i + 1);
            }
            return variants;
        }
        matcher = INLINE_CACHE_SPLIT.matcher(raw);
        if (matcher.matches()) {
            for (int i = 0; i < 4; i++) {
                variants[i] = matcher.group(1) + matcher.group(i + 2) + matcher.group(6);
            }
            return variants;
        }
        matcher = LEGACY_CACHE_SPLIT.matcher(raw);
        if (matcher.matches()) {
            if (matcher.group(2).isEmpty() && matcher.group(3).isEmpty()) {
                variants[0] = matcher.group(1);
                variants[1] = matcher.group(4);
                variants[2] = variants[0];
                variants[3] = variants[1];
                return variants;
            }
            variants[0] = matcher.group(1) + matcher.group(2) + matcher.group(4);
            variants[1] = matcher.group(1) + matcher.group(3) + matcher.group(4);
            variants[2] = variants[0];
            variants[3] = variants[1];
        }
        return variants;
    }

    private String expandStaticMacros(FilterRule rule, SimulationRequest request, SimulationContext context,
            String replacement) {
        if (rule.urlPattern != null && replacement.contains("$URL")) {
            Matcher url = rule.urlPattern.matcher(request.url());
            if (url.lookingAt()) {
                Matcher macro = URL_MACRO.matcher(replacement);
                StringBuffer output = new StringBuffer();
                while (macro.find()) {
                    int group = Integer.parseInt(macro.group(1));
                    String value = group <= url.groupCount() ? (url.group(group) == null ? "" : url.group(group)) : macro.group();
                    macro.appendReplacement(output, Matcher.quoteReplacement(value));
                }
                replacement = macro.appendTail(output).toString();
            }
        }

        Matcher timestamp = TS_MACRO.matcher(replacement);
        StringBuffer timestampOutput = new StringBuffer();
        while (timestamp.find()) {
            String path = timestamp.group(1);
            String queryPrefix = timestamp.group(2) == null ? "?" : timestamp.group(2);
            Path target = repositoryRoot.getParent().resolve(path).normalize();
            String value = path.isEmpty() ? Long.toString(context.startedAtSeconds) : path;
            if (!path.isEmpty() && target.startsWith(repositoryRoot.getParent()) && Files.isRegularFile(target)) {
                try {
                    value = path + queryPrefix + (Files.getLastModifiedTime(target).toMillis() / 1000L);
                } catch (Exception ignored) {
                    value = path;
                }
            }
            timestamp.appendReplacement(timestampOutput, Matcher.quoteReplacement(value));
        }
        replacement = timestamp.appendTail(timestampOutput).toString();

        replacement = REENCODED_BITRATE_MACRO.matcher(replacement)
                .replaceAll(Matcher.quoteReplacement(Integer.toString(request.reencodedBitrate())));
        replacement = REENCODED_MACRO.matcher(replacement)
                .replaceAll(Matcher.quoteReplacement(request.reencoded()));

        if (rule.requireHeader != null && replacement.contains("$RequireHeader")) {
            String raw = rule.rawRequireHeader == null ? "" : rule.rawRequireHeader;
            String expression = raw.startsWith("!") ? raw.substring(1) : raw;
            Matcher header = DynamicPatternSupport.compile(expression, repositoryRoot.getParent(), context)
                    .matcher(context.requestHeader);
            if (header.find()) {
                Matcher macro = REQUIRE_HEADER_MACRO.matcher(replacement);
                StringBuffer output = new StringBuffer();
                while (macro.find()) {
                    int group = Integer.parseInt(macro.group(1));
                    String value = group <= header.groupCount() && header.group(group) != null ? header.group(group) : "";
                    macro.appendReplacement(output, Matcher.quoteReplacement(value));
                }
                replacement = macro.appendTail(output).toString();
            }
        }

        Matcher variable = NL_VARIABLE.matcher(replacement);
        StringBuffer variableOutput = new StringBuffer();
        while (variable.find()) {
            String name = variable.group(1);
            String value = name.startsWith("config!")
                    ? System.getProperty(name.substring("config!".length()))
                    : context.variables.get(name);
            if (value == null) return null;
            variable.appendReplacement(variableOutput, Matcher.quoteReplacement(value));
        }
        replacement = variable.appendTail(variableOutput).toString();
        replacement = replacement.replace("<id>", context.id)
                .replace("<smid>", context.smid)
                .replace("<memoryId>", context.memoryId)
                .replace("<freeSpace>", "128.0");
        replacement = replaceCaseWhen(replacement);

        // 仮想URLを判定に使いつつ、ローカル資産はLabサーバーから読み込ませる。
        replacement = replacement.replaceAll("(?i)https?://www\\.nicovideo\\.jp/local/", "/local/");
        return replacement;
    }

    private static String replaceCaseWhen(String replacement) {
        while (true) {
            Matcher cases = CASE_PATTERN.matcher(replacement);
            if (!cases.find()) return replacement;
            StringBuffer output = new StringBuffer();
            do {
                String selected = "";
                Matcher when = CASE_WHEN_PATTERN.matcher(cases.group(2));
                while (when.find()) {
                    if (when.group(1) == null || cases.group(1).equals(when.group(1))) {
                        selected = when.group(2);
                        break;
                    }
                }
                cases.appendReplacement(output, Matcher.quoteReplacement(selected));
            } while (cases.find());
            replacement = cases.appendTail(output).toString();
        }
    }

    private static String expandBackReferences(Matcher matcher, String replacement, String groupZeroOverride) {
        StringBuilder output = new StringBuilder(replacement.length() + 32);
        for (int index = 0; index < replacement.length(); index++) {
            char character = replacement.charAt(index);
            if (character == '\\') {
                if (++index >= replacement.length()) throw new IllegalArgumentException("末尾の\\は不正です");
                output.append(replacement.charAt(index));
                continue;
            }
            if (character != '$') {
                output.append(character);
                continue;
            }
            if (++index >= replacement.length()) throw new IllegalArgumentException("末尾の$は不正です");
            if (replacement.charAt(index) == '{') {
                int end = replacement.indexOf('}', index + 1);
                if (end < 0) throw new IllegalArgumentException("名前付きグループ参照が閉じていません");
                String value = matcher.group(replacement.substring(index + 1, end));
                output.append(value == null ? "" : value);
                index = end;
                continue;
            }
            if (!Character.isDigit(replacement.charAt(index))) {
                throw new IllegalArgumentException("$の後にはグループ番号または{name}が必要です");
            }
            int group = replacement.charAt(index) - '0';
            if (group > matcher.groupCount()) throw new IndexOutOfBoundsException("group " + group);
            while (index + 1 < replacement.length() && Character.isDigit(replacement.charAt(index + 1))) {
                int candidate = group * 10 + (replacement.charAt(index + 1) - '0');
                if (candidate > matcher.groupCount()) break;
                group = candidate;
                index++;
            }
            String value = group == 0 && groupZeroOverride != null ? groupZeroOverride : matcher.group(group);
            output.append(value == null ? "" : value);
        }
        return output.toString();
    }

    private void appendCollected(List<FilterRule> rules, String elementName, String marker, SimulationResult result) {
        if (rules.isEmpty()) {
            return;
        }
        StringBuilder payload = new StringBuilder();
        for (FilterRule rule : rules) {
            // 本体のwrapAndConcatはAppend本文へ置換マクロ展開を行わない。
            String body = rule.replacements.isEmpty() ? "" : rule.replacements.get(0);
            payload.append('<').append(elementName).append(" type=\"")
                    .append(elementName.equals("style") ? "text/css" : "text/javascript")
                    .append("\">\r\n/* ").append(rule.identifier()).append(" */\r\n")
                    .append(body).append("\r\n</").append(elementName).append(">\r\n");
        }
        int position = result.rendered.indexOf(marker);
        if (position >= 0) {
            result.rendered = result.rendered.substring(0, position) + payload + result.rendered.substring(position);
            for (FilterRule rule : rules) {
                result.traces.add(new SimulationResult.Trace(rule.identifier(), rule.section.label(), 1,
                        "</" + (elementName.equals("style") ? "head" : "body") + "> の直前へ一括挿入"));
            }
        } else {
            for (FilterRule rule : rules) {
                result.traces.add(new SimulationResult.Trace(rule.identifier(), rule.section.label(), 0,
                        marker + " がないため挿入なし"));
            }
            result.diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, null, 0, "missing-html-marker",
                    marker + " がないため " + elementName + " を挿入できませんでした"));
        }
    }
}
