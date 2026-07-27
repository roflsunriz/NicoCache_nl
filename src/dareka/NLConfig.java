package dareka;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import dareka.common.CloseUtil;
import dareka.common.FileUtil;
import dareka.common.Logger;
import dareka.extensions.SystemEventListener;

/**
 * Implementation for basic NicoCache_nl configuration.
 * @since NicoCache_nl+101219mod
 */
public class NLConfig extends BasicConfig {
    private static final String CHARTEST_LINE = "# NicoCache_nl 設定ファイル";

    private static final ConcurrentHashMap<String, Pattern> patternCache =
        new ConcurrentHashMap<String, Pattern>();

    public NLConfig(File configFile) {
        super(configFile);
    }

    @Override
    protected String doGetConfigFileComments() {
        return "NicoCache_nl config file";
    }

    @Override
    protected void doSetDefaults(Properties properties) {
        super.doSetDefaults(properties);

        properties.setProperty("allowFrom", "local");
        properties.setProperty("speedLimit", "0");
        properties.setProperty("cacheFolder", "cache");
        properties.setProperty("needFreeSpace", "100");
        properties.setProperty("rewriterContentType", "^text/");
        properties.setProperty("thcacheFolder", "thcache");
        properties.setProperty("thcacheTimeout", "10000");
        properties.setProperty("convertedCacheFolder", "cvcache");
    }

    @Override
    protected String doValidateValue(String key, String value) {
        if (key == null) {
            return null;
        }
        switch (key) {
        case "listenPort":
        case "proxyPort": {
            int port = parseInt(value, 0);
            if (port < 1 || 65535 < port) {
                warnInvalidProperty(key, value);
                return null;
            }
            break;
        }
        case "allowFrom":
            if ("lan".equals(value)) {
                value = "lanC";
            }
            break;
        case "speedLimit": {
            int limit = parseInt(value, 0);
            if (limit < 0 || (0 < limit && limit < 8) || 1000 < limit) {
                warnInvalidProperty(key, value);
                return null;
            }
            break;
        }
        case "cacheFolder":
            if ("".equals(value)) {
                value = "cache";
            }
            break;
        case "thcacheFolder":
            if ("".equals(value)) {
                value = "thcache";
            }
            break;
        case "thcacheTimeout": {
            int timeout = parseInt(value, 0);
            if (timeout < 5000) {
                warnInvalidProperty(key, value);
                return null;
            }
            break;
        }
        case "convertedCacheFolder":
            if ("".equals(value)) {
                value = "cvcache";
            }
            break;
        default:
            value = super.doValidateValue(key, value);
            break;
        }
        return value;
    }

    private static int parseInt(String value, int def) {
        if (value == null) {
            return def;
        } else {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return def;
            }
        }
    }

    private static void warnInvalidProperty(String key, String value) {
        Logger.warning("invalid property: " + key + "=" + value);
    }

    @Override
    public synchronized boolean reload() {
        boolean reloaded = super.reload();
        if (reloaded) {
            NLMain.SHARED.notifySystemEvent(
                    SystemEventListener.CONFIG_RELOADED, null, false);
        }
        return reloaded;
    }

    @Override
    protected synchronized void update() {
        super.update();
        patternCache.clear();
    }

    @Override
    protected void loadFrom(File propertyFile, Properties p)
            throws FileNotFoundException, IOException {
        InputStream in = getAsciiInputStream(propertyFile, CHARTEST_LINE);
        try {
            p.load(in);
        } finally {
            CloseUtil.close(in);
        }
    }

    /**
     * プロパティ値をパスとみなして File オブジェクトを取得する。
     * 相対パスは利用者データルート基準で解決する。
     *
     * @param key プロパティ名
     * @return File オブジェクト、プロパティ名が無効な場合は null
     * @since NicoCache_nl+111111mod
     */
    public static File getFile(String key) {
        if (key == null) {
            return null;
        }
        switch (key) {
        case "cacheFolder":
            return NicoCachePaths.cacheDirectory();
        case "thcacheFolder":
            return NicoCachePaths.thumbnailCacheDirectory();
        case "convertedCacheFolder":
            return NicoCachePaths.convertedCacheDirectory();
        default:
            String value = System.getProperty(key);
            if (value == null || value.length() == 0) {
                return null;
            }
            return NicoCachePaths.configuredFile(value, null);
        }
    }

    public static Pattern getPattern(String key) {
        Pattern pattern = patternCache.get(key);
        if (pattern == null) {
            String value = System.getProperty(key);
            try {
                if (value != null && value.length() > 0) {
                    pattern = Pattern.compile(value);
                    patternCache.put(key, pattern);
                }
            } catch (PatternSyntaxException e) {
                warnInvalidProperty(key, value);
            }
        }
        return pattern;
    }

    public static Matcher getMatcher(String key, String input) {
        if (input != null && input.length() > 0) {
            Pattern pattern = getPattern(key);
            if (pattern != null) {
                return pattern.matcher(input);
            }
        }
        return null;
    }

    public static boolean find(String key, String input) {
        Matcher m = getMatcher(key, input);
        return m != null && m.find();
    }

    public static boolean lookingAt(String key, String input) {
        Matcher m = getMatcher(key, input);
        return m != null && m.lookingAt();
    }

    public static boolean matches(String key, String input) {
        Matcher m = getMatcher(key, input);
        return m != null && m.matches();
    }

    private static InputStream getAsciiInputStream(File f, String line)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileUtil.copy(f, out);
        byte[] data = out.toByteArray();
        String charset = FileUtil.detectCharset(data, line);
        if (charset == null) {
            charset = System.getProperty("file.encoding");
        }
        return new ByteArrayInputStream(new String(data, charset).getBytes("ISO-8859-1"));
    }
}
