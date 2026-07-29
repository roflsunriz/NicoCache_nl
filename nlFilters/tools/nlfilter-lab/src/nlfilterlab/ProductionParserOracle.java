package nlfilterlab;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

final class ProductionParserOracle {
    record Rule(String section, String name, String url, List<String> matches, List<String> replacements,
            String require, String requireHeader, String contentType, List<Integer> statusCodes,
            boolean multi, boolean eachLine, boolean matchLocal, boolean replaceOnly, boolean replaceDelay,
            boolean noCache, boolean debug, int idGroup, int idGroup2, String idGroupSecondRaw,
            String addList, String addVariable) {
    }

    record Result(boolean available, String reason, List<Rule> rules) {
    }

    record ExecutionResult(boolean available, String reason, String content) {
    }

    private ProductionParserOracle() {
    }

    static Result parse(Path repositoryRoot, Path file) {
        Path installationRoot = repositoryRoot.getParent();
        Path jar = installationRoot.resolve("NicoCache_nl.jar");
        if (!Files.isRegularFile(jar)) {
            return new Result(false, "NicoCache_nl.jar が見つかりません", List.of());
        }
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(jar.toUri().toURL());
            Path lib = installationRoot.resolve("lib");
            if (Files.isDirectory(lib)) {
                try (var entries = Files.list(lib)) {
                    for (Path dependency : entries.filter(path -> path.getFileName().toString().endsWith(".jar")).toList()) {
                        urls.add(dependency.toUri().toURL());
                    }
                }
            }
            try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new),
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> rewriterClass = Class.forName("dareka.processor.impl.EasyRewriter", true, loader);
                Constructor<?> rewriterConstructor = rewriterClass.getDeclaredConstructor();
                rewriterConstructor.setAccessible(true);
                Object rewriter = rewriterConstructor.newInstance();

                Class<?> filterFileClass = Class.forName(
                        "dareka.processor.impl.EasyRewriter$FilterFile", true, loader);
                Constructor<?> filterFileConstructor = filterFileClass.getDeclaredConstructor(String.class);
                filterFileConstructor.setAccessible(true);
                Object filterFile = filterFileConstructor.newInstance(file.toAbsolutePath().toString());

                Method parse = rewriterClass.getDeclaredMethod("parseFilterFile", filterFileClass);
                parse.setAccessible(true);
                parse.invoke(rewriter, filterFile);

                Field parsed = filterFileClass.getDeclaredField("parsed");
                parsed.setAccessible(true);
                List<?> productionRules = (List<?>) parsed.get(filterFile);
                List<Rule> rules = new ArrayList<>();
                for (Object productionRule : productionRules) rules.add(readRule(productionRule));
                return new Result(true, null, List.copyOf(rules));
            }
        } catch (Throwable exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return new Result(false, cause.getClass().getSimpleName() + ": " + cause.getMessage(), List.of());
        }
    }

    static ExecutionResult executePure(Path repositoryRoot, Path file, String url, String content) {
        Path installationRoot = repositoryRoot.getParent();
        Path jar = installationRoot.resolve("NicoCache_nl.jar");
        if (!Files.isRegularFile(jar)) return new ExecutionResult(false, "NicoCache_nl.jar が見つかりません", null);
        try {
            List<URL> urls = runtimeUrls(installationRoot, jar);
            try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new),
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> rewriterClass = Class.forName("dareka.processor.impl.EasyRewriter", true, loader);
                Constructor<?> constructor = rewriterClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object rewriter = constructor.newInstance();
                Class<?> filterFileClass = Class.forName("dareka.processor.impl.EasyRewriter$FilterFile", true, loader);
                Constructor<?> fileConstructor = filterFileClass.getDeclaredConstructor(String.class);
                fileConstructor.setAccessible(true);
                Object productionFile = fileConstructor.newInstance(file.toAbsolutePath().toString());
                Method parse = rewriterClass.getDeclaredMethod("parseFilterFile", filterFileClass);
                parse.setAccessible(true);
                parse.invoke(rewriter, productionFile);
                Field parsed = filterFileClass.getDeclaredField("parsed");
                parsed.setAccessible(true);
                ArrayList<?> rules = (ArrayList<?>) parsed.get(productionFile);
                Class<?> requestHeader = Class.forName("dareka.processor.HttpRequestHeader", false, loader);
                Class<?> responseHeader = Class.forName("dareka.processor.HttpResponseHeader", false, loader);
                Method apply = rewriterClass.getDeclaredMethod("applyUserFilter", String.class, String.class,
                        requestHeader, responseHeader, ArrayList.class);
                apply.setAccessible(true);
                String rendered = (String) apply.invoke(rewriter, url, content, null, null, rules);
                return new ExecutionResult(true, null, rendered);
            }
        } catch (Throwable exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return new ExecutionResult(false, cause.getClass().getSimpleName() + ": " + cause.getMessage(), null);
        }
    }

    private static List<URL> runtimeUrls(Path installationRoot, Path jar) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        Path lib = installationRoot.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (var entries = Files.list(lib)) {
                for (Path dependency : entries.filter(path -> path.getFileName().toString().endsWith(".jar")).toList()) {
                    urls.add(dependency.toUri().toURL());
                }
            }
        }
        return urls;
    }

    private static Rule readRule(Object rule) throws Exception {
        Class<?> type = rule.getClass();
        Object match = field(type, rule, "match");
        List<String> matches = match == null ? List.of() : Arrays.asList((String[]) field(match.getClass(), match, "contents"));
        String[] replacementValues = (String[]) field(type, rule, "replace");
        int[] idGroups = (int[]) field(type, rule, "idGroup");
        String[] idGroupStrings = (String[]) field(type, rule, "idGroupString");
        int[] statusCodeValues = (int[]) field(type, rule, "statusCodes");
        Pattern url = (Pattern) field(type, rule, "url");
        return new Rule(
                (String) field(type, rule, "section"),
                (String) field(type, rule, "name"),
                url == null ? null : url.pattern(),
                List.copyOf(matches),
                replacementValues == null ? List.of() : List.of(replacementValues),
                readCondition(type, rule, "require"),
                readCondition(type, rule, "requireHeader"),
                readCondition(type, rule, "contentType"),
                statusCodeValues == null ? List.of() : Arrays.stream(statusCodeValues).boxed().toList(),
                (boolean) field(type, rule, "multi"),
                (boolean) field(type, rule, "each"),
                (boolean) field(type, rule, "matchLocal"),
                (boolean) field(type, rule, "replaceOnly"),
                (boolean) field(type, rule, "replaceDelay"),
                (boolean) field(type, rule, "noCache"),
                (boolean) field(type, rule, "debugMode"),
                idGroups[0], idGroups[1],
                idGroupStrings != null && idGroupStrings.length > 1 ? idGroupStrings[1] : null,
                (String) field(type, rule, "addList"),
                (String) field(type, rule, "addVariable"));
    }

    private static String readCondition(Class<?> type, Object rule, String name) throws Exception {
        Object condition = field(type, rule, name);
        if (condition == null) return null;
        Class<?> conditionType = condition.getClass();
        String[] contents = (String[]) field(conditionType, condition, "contents");
        boolean negated = (boolean) field(conditionType, condition, "not");
        return (negated ? "!" : "") + contents[0];
    }

    private static Object field(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
