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
        return "NicoCache_nl config file"; // 多バイト文字は指定できない
    }

    @Override
    protected void doSetDefaults(Properties properties) {
        super.doSetDefaults(properties);

        // デフォルト値が存在しないと困るものだけ記述する
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
        try { // ensure closing in
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

    /**
     * プロパティ値を正規表現とみなしてコンパイル済みのパターンを返す。
     *
     * @param key プロパティ名
     * @return コンパイル済みのパターン、次のいずれかの場合は null
     * <ul>
     * <li>プロパティ値が存在しない
     * <li>プロパティ値が空文字列
     * <li>プロパティ値に正規表現エラーがある
     * </ul>
     * @since NicoCache_nl+111124mod
     */
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

    /**
     * プロパティ値を正規表現とみなして入力文字列をマッチングする正規表現エンジンを返す。
     *
     * @param key プロパティ名
     * @param input 入力文字列
     * @return 入力文字列をマッチングする正規表現エンジン<br>
     * 次のいずれかの場合は null
     * <ul>
     * <li>入力文字列が null or 空文字列
     * <li>プロパティ値が存在しない or 空文字列
     * <li>プロパティ値に正規表現エラーがある
     * </ul>
     * @since NicoCache_nl+110706mod
     */
    public static Matcher getMatcher(String key, String input) {
        if (input != null && input.length() > 0) {
            Pattern pattern = getPattern(key);
            if (pattern != null) {
                return pattern.matcher(input);
            }
        }
        return null;
    }

    /**
     * プロパティ値を正規表現とみなして入力文字列と部分一致のマッチングを行う。
     *
     * @param key プロパティ名
     * @param input 入力文字列
     * @return 入力文字列にマッチすればtrue。入力文字列がnull、空文字列、
     * マッチしない、プロパティ値が存在しない、正規表現エラーがあるならfalse
     */
    public static boolean find(String key, String input) {
        Matcher m = getMatcher(key, input);
        return m != null && m.find();
    }

    /**
     * プロパティ値を正規表現とみなして入力文字列と領域の先頭からマッチングを行う。
     *
     * @see #find(String, String)
     * @see java.util.regex.Matcher#lookingAt()
     * @since NicoCache_nl+110411mod
     */
    public static boolean lookingAt(String key, String input) {
        Matcher m = getMatcher(key, input);
        return m != null && m.lookingAt();
    }

    /**
     * プロパティ値を正規表現とみなして入力文字列と領域全体のマッチングを行う。
     *
     * @see #find(String, String)
     * @see java.util.regex.Matcher#matches()
     * @since NicoCache_nl+110411mod
     */
    public static boolean matches(String key, String input) {
        Matcher m = getMatcher(key, input);
        return m != null && m.matches();
    }

    /**
     * 日本語文字を1バイト文字('\\uXXXX')に変換した入力ストリームを返す。
     * startlineが指定されている場合、内部で文字セットを判別して変換して処理する。
     * 全てメモリに読み込んで処理するので、サイズの大きなファイルを指定しないこと。
     *
     * @param file 読み込む対象のファイル
     * @param startline 開始文字列(日本語文字を含んでいる必要がある)
     * @return 1バイト文字に変換された入力ストリーム
     * @throws IOException 処理途中に何らかの問題が発生した
     * @see FileUtil#getInputStreamReader(File, String)
     */
    public static InputStream getAsciiInputStream(File file,
            String startline) throws IOException {
        if (file.length() > Integer.MAX_VALUE) {
            throw new IOException(file.getPath() + " too large");
        }
        InputStreamReader in = FileUtil.getInputStreamReader(file, startline);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int)file.length());
        try {
            int ch;
            while ((ch = in.read()) != -1) {
                if (ch > 0xff) {
                    String enc = "0000" + Integer.toHexString(ch);
                    enc = "\\u" + enc.substring(enc.length() - 4);
                    out.write(enc.getBytes("ISO-8859-1"), 0, 6);
                } else {
                    out.write(ch);
                }
            }
        } finally {
            CloseUtil.close(in);
            CloseUtil.close(out);
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

}
