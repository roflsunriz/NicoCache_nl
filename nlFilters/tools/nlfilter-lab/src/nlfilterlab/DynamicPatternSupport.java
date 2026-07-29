package nlfilterlab;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DynamicPatternSupport {
    private static final String VARIABLE_NAME = "[^\\s\"#\\$\\(\\)<>=]+";
    private static final Pattern LST = macro("LST", "\"?([^\"\\)]*)\"?");
    private static final Pattern INC = macro("INC", "(" + VARIABLE_NAME + ")");
    private static final Pattern SET = macro("SET", "(" + VARIABLE_NAME + ")\\s*=\\s*([^\\)]*)");
    static final Pattern NEST = Pattern.compile(
            "\\$NEST\\(((?:\\\\\\\\|\\\\,|[^,])+),((?:\\\\\\\\|\\\\,|[^,])+),(.+)\\)\\s*");

    private DynamicPatternSupport() {
    }

    static String prepareForCompile(String expression) {
        if (NEST.matcher(expression).matches()) return "(?!)";
        // $LSTは本体で選択肢全体を1つのキャプチャグループにする。
        String prepared = replaceAll(LST, expression, "((?!))");
        prepared = replaceAll(INC, prepared, "");
        return replaceAll(SET, prepared, "");
    }

    static Pattern compile(String expression, Path installationRoot) {
        return compile(expression, installationRoot, null);
    }

    static Pattern compile(String expression, Path installationRoot, SimulationContext context) {
        if (NEST.matcher(expression).matches()) return Pattern.compile("(?!)");
        Matcher lists = LST.matcher(expression);
        StringBuffer expanded = new StringBuffer();
        while (lists.find()) {
            lists.appendReplacement(expanded,
                    Matcher.quoteReplacement(readListPattern(installationRoot, lists.group(1), context)));
        }
        String prepared = lists.appendTail(expanded).toString();
        prepared = replaceAll(INC, prepared, "");
        prepared = replaceAll(SET, prepared, "");
        return Pattern.compile(prepared);
    }

    static void applyStateMacros(String expression, SimulationContext context) {
        Matcher increments = INC.matcher(expression);
        while (increments.find()) context.increment(increments.group(1));
        Matcher sets = SET.matcher(expression);
        while (sets.find()) context.set(sets.group(1), sets.group(2));
    }

    static boolean hasDynamicMacro(String expression) {
        return expression != null && (LST.matcher(expression).find() || INC.matcher(expression).find() ||
                SET.matcher(expression).find() || NEST.matcher(expression).matches());
    }

    private static String readListPattern(Path installationRoot, String rawName, SimulationContext context) {
        boolean quote = rawName.startsWith("!");
        String fileName = quote ? rawName.substring(1) : rawName;
        Path path = installationRoot.resolve(fileName).normalize();
        if (!path.startsWith(installationRoot)) return "(?!)";
        try {
            TreeSet<String> lines = new TreeSet<>();
            if (Files.isRegularFile(path)) {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) lines.add(line);
                }
            }
            if (context != null) lines.addAll(context.listAdditions.getOrDefault(fileName, List.of()));
            if (lines.isEmpty()) return "(?!)";
            List<String> alternatives = new ArrayList<>();
            for (String line : lines) alternatives.add(quote ? Pattern.quote(line) : line);
            return "(" + String.join("|", alternatives) + ")";
        } catch (Exception ignored) {
            return "(?!)";
        }
    }

    private static Pattern macro(String name, String body) {
        return Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*\\$" + name + "\\(" + body + "\\)");
    }

    private static String replaceAll(Pattern pattern, String input, String replacement) {
        return pattern.matcher(input).replaceAll(Matcher.quoteReplacement(replacement));
    }
}
