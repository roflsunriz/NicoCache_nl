package nicocache.diagnostics;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic per-report credential and personal-environment redaction. */
final class Redactor {
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?im)^((?:proxy-)?authorization\\s*:\\s*)[^\\r\\n]+$");
    private static final Pattern COOKIE_HEADER = Pattern.compile(
            "(?im)^((?:set-)?cookie\\s*:\\s*)[^\\r\\n]+$");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b[A-Z0-9_.-]*(?:password|passwd|token|secret|cookie|"
            + "authorization|api[-_]?key)[A-Z0-9_.-]*\\b\\s*[=:]\\s*)"
            + "([^\\s,;\\r\\n]+)");
    private static final Pattern SECRET_QUERY = Pattern.compile(
            "(?i)([?&](?:access_token|token|secret|password|signature|"
            + "session|auth|key)=)([^&#\\s]+)");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern URL_USER_INFO = Pattern.compile(
            "(?i)(\\b[A-Z][A-Z0-9+.-]*://)([^\\s/@:]+):([^\\s/@]+)@");
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])");
    private static final Pattern GENERIC_WINDOWS_HOME = Pattern.compile(
            "(?i)[A-Z]:\\\\Users\\\\[^\\\\/\\r\\n]+");

    private final Map<String, String> replacements = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    Redactor(DiagnosticsPaths paths) {
        addLiteral(paths.applicationRoot(), "<APP_ROOT>");
        addLiteral(paths.dataRoot(), "<DATA_ROOT>");
        addLiteral(Path.of(System.getProperty("user.home", ".")),
                "<USER_HOME>");
        addLiteral(Path.of(System.getProperty("java.io.tmpdir", ".")),
                "<TEMP>");
    }

    String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        String value = input;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String replaced = value.replace(entry.getKey(), entry.getValue());
            if (!replaced.equals(value)) {
                increment(entry.getValue());
                value = replaced;
            }
        }
        value = replaceGroup(value, AUTH_HEADER, "$1<OMITTED>", "authorization");
        value = replaceGroup(value, COOKIE_HEADER, "$1<OMITTED>", "cookie");
        value = replaceGroup(value, SECRET_ASSIGNMENT,
                "$1<OMITTED>", "secret-value");
        value = replaceGroup(value, SECRET_QUERY,
                "$1<OMITTED>", "secret-query");
        value = replaceGroup(value, URL_USER_INFO,
                "$1<OMITTED>:<OMITTED>@", "url-user-info");
        value = replaceStable(value, EMAIL, "EMAIL");
        value = replaceIpAddresses(value);
        value = replaceGroup(value, GENERIC_WINDOWS_HOME,
                "<USER_HOME>", "user-home");
        return value;
    }

    Map<String, Integer> counts() {
        return new LinkedHashMap<>(counts);
    }

    private void addLiteral(Path path, String replacement) {
        if (path == null) {
            return;
        }
        String normalized = path.toAbsolutePath().normalize().toString();
        if (!normalized.isBlank()) {
            replacements.put(normalized, replacement);
            replacements.put(normalized.replace('\\', '/'), replacement);
        }
    }

    private String replaceIpAddresses(String input) {
        Matcher matcher = IPV4.matcher(input);
        StringBuffer result = new StringBuffer(input.length());
        while (matcher.find()) {
            String address = matcher.group();
            if (address.startsWith("127.") || "0.0.0.0".equals(address)) {
                matcher.appendReplacement(result,
                        Matcher.quoteReplacement(address));
            } else {
                String replacement = stable("IP", address);
                matcher.appendReplacement(result,
                        Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String replaceStable(String input, Pattern pattern,
            String category) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer(input.length());
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    stable(category, matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String stable(String category, String original) {
        String key = category + '\u0000' + original.toLowerCase(Locale.ROOT);
        String existing = replacements.get(key);
        if (existing != null) {
            increment(category.toLowerCase(Locale.ROOT));
            return existing;
        }
        long ordinal = replacements.keySet().stream()
                .filter(item -> item.startsWith(category + "\u0000"))
                .count() + 1L;
        String replacement = "<" + category + "_" + ordinal + ">";
        replacements.put(key, replacement);
        increment(category.toLowerCase(Locale.ROOT));
        return replacement;
    }

    private String replaceGroup(String input, Pattern pattern,
            String replacement, String category) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        int matches = 1;
        while (matcher.find()) {
            matches++;
        }
        counts.merge(category, matches, Integer::sum);
        return pattern.matcher(input).replaceAll(replacement);
    }

    private void increment(String category) {
        counts.merge(category, 1, Integer::sum);
    }
}
