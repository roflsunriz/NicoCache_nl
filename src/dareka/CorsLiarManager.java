package dareka;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.FileUtil;
import dareka.common.Logger;
import dareka.common.json.Json;
import dareka.common.json.JsonArray;
import dareka.common.json.JsonObject;
import dareka.common.json.JsonString;
import dareka.common.json.JsonValue;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;

class CorsLiarManager {
    private static class Configuration {
        final ArrayList<CorsLiar.Model> entries;
        final Pattern mergedUrl;

        Configuration(ArrayList<CorsLiar.Model> entries, Pattern mergedUrl) {
            this.entries = entries;
            this.mergedUrl = mergedUrl;
        }
    }

    private static final CorsLiarManager INSTANCE = new CorsLiarManager();
    public static CorsLiarManager getInstance() { return INSTANCE; }

    private AtomicReference<Configuration> configRef = new AtomicReference<>();
    private static final String CHARTEST_LINE = "// CORS設定ファイル";
    private static final Path CONF_DIR = Paths.get("data/cors");
    private static final Pattern BACKREFERENCE_PATTERN = Pattern.compile("\\\\[k1-9]");

    public void load() {
        ArrayList<CorsLiar.Model> entries = new ArrayList<>();

        File confDirFile = CONF_DIR.toFile();
        if (!confDirFile.exists()) {
            confDirFile.mkdirs();
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CONF_DIR, "*.conf")) {
            stream.forEach(path -> load(entries, path.toFile()));
        } catch (IOException e) {
            Logger.error(e);
            return;
        }

        Pattern mergedUrl = null;
        if (entries.size() >= 5) {
            boolean disabled = false;
            StringBuilder sbMergedUrl = new StringBuilder();
            for (CorsLiar.Model entry : entries) {
                String pat = entry.getUrl().pattern();
                if (BACKREFERENCE_PATTERN.matcher(pat).find()) {
                    disabled = true;
                    break;
                }
                if (sbMergedUrl.length() != 0)
                    sbMergedUrl.append('|');
                sbMergedUrl.append(pat);
            }
            if (!disabled) {
                mergedUrl = Pattern.compile(sbMergedUrl.toString());
            }
        }

        Configuration conf = new Configuration(entries, mergedUrl);
        configRef.set(conf);

        Logger.info("CORS configurations are loaded.");
    }

    private void load(ArrayList<CorsLiar.Model> entries, File file) {
        char[] buf = new char[1024];
        StringBuilder sb = new StringBuilder();

        try (InputStreamReader in = FileUtil.getInputStreamReader(file, CHARTEST_LINE)) {
            while (true) {
                int read = in.read(buf);
                if (read < 0) break;
                sb.append(buf, 0, read);
            }
        } catch (IOException ex) {
            Logger.warning("Failed to load " + file.getPath());
            return;
        }

        JsonValue root = Json.parse(sb.toString(), true);
        if (root == null) {
            Logger.warning("Failed to parse json: " + file.getPath());
            return;
        }
        if (!(root instanceof JsonArray)) {
            Logger.warning("Structure Error (root): " + file.getPath());
            return;
        }

        for (JsonValue v : ((JsonArray)root).getList()) {
            CorsLiar.Model entry;
            if (v instanceof JsonObject &&
                    (entry = parseCorsLiar((JsonObject)v, file)) != null) {
                entries.add(entry);
            } else {
                Logger.warning("Structure Error (entry): " + file.getPath());
            }
        }
    }

    private static final HashSet<String> ROOT_KEYS = new HashSet<>(
            Arrays.asList("origin", "url", "fake-origin", "allow-credentials", "allow-methods", "allow-headers"));
    private CorsLiar.Model parseCorsLiar(JsonObject json, File file) {
        String originString = json.getString("origin");
        String urlString = json.getString("url");
        String fakeOrigin = json.getString("fake-origin");
        boolean allowCredentials = json.getBoolean("allow-credentials");

        for (String key : json.getMap().keySet()) {
            if (!ROOT_KEYS.contains(key)) {
                Logger.warning("CORS Unknown key: " + key + " in " + file.getPath());
            }
        }

        HashSet<String> allowMethods = null;
        JsonArray jsonAllowMethods = json.getArray("allow-methods");
        if (jsonAllowMethods != null) {
            allowMethods = new HashSet<>();
            for (JsonValue v : jsonAllowMethods.getList()) {
                if (!(v instanceof JsonString)) {
                    return null;
                }
                allowMethods.add(v.toString());
            }
            if (!allowMethods.contains(HttpHeader.OPTIONS)) {
                allowMethods.add(HttpHeader.OPTIONS);
            }
        }

        HashSet<String> allowHeaders = null;
        JsonArray jsonAllowHeaders = json.getArray("allow-headers");
        if (jsonAllowHeaders != null) {
            allowHeaders = new HashSet<>();
            for (JsonValue v : jsonAllowHeaders.getList()) {
                if (!(v instanceof JsonString)) {
                    return null;
                }
                allowHeaders.add(v.toString());
            }
        }

        Pattern origin = Pattern.compile(originString);
        Pattern url = Pattern.compile(urlString);

        CorsLiar.Model entry = new CorsLiar.Model(origin, url, fakeOrigin,
                allowCredentials, allowMethods, allowHeaders);

        return entry;
    }


    public CorsLiar match(HttpRequestHeader requestHeader) {
        String origin = requestHeader.getMessageHeader(CorsLiar.HEADER_ORIGIN);
        if (origin == null) return null;

        Configuration config = configRef.get();
        String uri = requestHeader.getURI();

        if (config.mergedUrl != null) {
            Matcher m = config.mergedUrl.matcher(uri);
            if (!m.matches()) return null;
        }

        for (CorsLiar.Model entry : config.entries) {
            Matcher m = entry.getUrl().matcher(uri);
            if (!m.matches()) continue;
            m = entry.getOrigin().matcher(origin);
            if (!m.matches()) continue;

            return new CorsLiar(entry);
        }
        return null;
    }
}
