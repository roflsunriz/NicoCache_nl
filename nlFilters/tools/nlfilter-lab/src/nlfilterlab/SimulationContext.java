package nlfilterlab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SimulationContext {
    private static final Pattern WATCH_PAGE = Pattern.compile(
            "^https?://www\\.nicovideo\\.jp/watch/(\\w{2}\\d+)(?:\\?.*)?$");
    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:(?<!\\w)videoId|&quot;video&quot;:\\{[^}]+?(?<!\\w)id)(?:\\\", \\\"|&quot;:&quot;)([a-z]{2}\\d+)(?!\\w)");

    final Map<String, String> variables = new LinkedHashMap<>();
    final Map<String, List<String>> listAdditions = new LinkedHashMap<>();
    final long startedAtSeconds = System.currentTimeMillis() / 1000L;
    String effectiveUrl;
    String requestHeader;
    String id = "";
    String smid = "";
    String memoryId = "";

    SimulationContext(SimulationRequest request, String content) {
        effectiveUrl = request.url();
        variables.put("VERSION", "nlFilter-Lab");
        updateRequestHeader(request.contentType());
        Matcher watch = WATCH_PAGE.matcher(effectiveUrl);
        if (watch.matches()) {
            smid = watch.group(1);
            memoryId = smid;
            if (smid.matches("\\d+")) {
                Matcher video = VIDEO_ID.matcher(content);
                if (video.find()) smid = video.group(1);
            }
            if (smid.length() > 2) id = smid.substring(2);
        }
    }

    void updateRequestHeader(String contentType) {
        requestHeader = "GET " + effectiveUrl + " HTTP/1.1\r\n" +
                "User-Agent: nlFilter-Lab/1.0\r\n" +
                "Accept: " + contentType + "\r\n";
    }

    void increment(String name) {
        try {
            int current = Integer.parseInt(variables.getOrDefault(name, "0"));
            variables.put(name, Integer.toString(current + 1));
        } catch (NumberFormatException ignored) {
            // 本体同様、非数値なら値を変更しない。
        }
    }

    void set(String name, String value) {
        variables.put(name, value);
    }

    void appendVariable(String name, String value) {
        variables.merge(name, value, String::concat);
    }

    void addList(String name, String value) {
        List<String> values = listAdditions.computeIfAbsent(name, ignored -> new ArrayList<>());
        if (!values.contains(value)) values.add(value);
    }
}
