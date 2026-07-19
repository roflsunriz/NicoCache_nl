package dareka.common;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * テキスト文字列を扱うためのユーティリティクラス
 * @since NicoCache_nl+111111mod
 */
public class TextUtil {

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "[a-z]{2}\\d{1,9}");
    private static final Pattern THREAD_ID_PATTERN = Pattern.compile(
            "\\d{10,}");
    private static final Pattern BLANK_PATTERN = Pattern.compile(
            "[\\s　]*");
    private static final Pattern STRIP_TAGS_PATTERN = Pattern.compile(
            "<[^>]*>");

    /**
     * 動画ID(=英字2文字＋9桁以下の数字)か？(末尾に"low"が付くものは含まない)
     *
     * @param id 判定する文字列 (nullも可)
     * @return 文字列が動画IDの場合のみ true
     **/
    public static boolean isVideoId(String id) {
        return id != null && VIDEO_ID_PATTERN.matcher(id).matches();
    }

    /**
     * スレッドID(=10桁以上の数字のみ)か？
     *
     * @param id 判定する文字列 (nullも可)
     * @return 文字列がスレッドIDの場合のみ true
     */
    public static boolean isThreadId(String id) {
        return id != null && THREAD_ID_PATTERN.matcher(id).matches();
    }

    /**
     * 空白のみの文字列か？
     *
     * @param content 判定する文字列 (nullも可)
     * @return 空白のみの文字列 or nullなら true
     */
    public static boolean isBlank(String content) {
        return content == null || BLANK_PATTERN.matcher(content).matches();
    }

    /**
     * タグを除去する(Prototype.js の同名メソッドと同等)
     *
     * @param content 対象文字列 (nullも可)
     * @return タグを除去した文字列
     */
    public static String stripTags(String content) {
        if (content == null) {
            return null;
        }
        return STRIP_TAGS_PATTERN.matcher(content).replaceAll("");
    }


    private static final Pattern ZERO_WIDTH_CHAR_PATTERN = Pattern.compile(
            "[\\u0323\\u2000-\\u200f\\u2028-\\u202e]");

    /**
     * ゼロ幅文字(ZERO WITH SPACE等)を除去する
     *
     * @param content 対象文字列 (nullも可)
     * @return ゼロ幅文字を削除した文字列
     */
    public static String stripZeroWithChars(String content) {
        if (content == null) {
            return null;
        }
        return ZERO_WIDTH_CHAR_PATTERN.matcher(content).replaceAll("");
    }

    /**
     * HTML の特殊文字をエスケープする(Prototype.js の同名メソッドと同等)<br>
     * 対象となる文字は <b>& < > " '</b> の5種類
     *
     * @param content 対象文字列 (nullも可)
     * @return エスケープした文字列
     * @since NicoCache_nl+120609mod
     */
    public static String escapeHTML(String content) {
        if (content == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(content.length() * 2);
        char c;
        for (int i = 0; i < content.length(); i++) {
            switch (c = content.charAt(i)) {
            case '&':  sb.append("&amp;" ); break;
            case '<':  sb.append("&lt;"  ); break;
            case '>':  sb.append("&gt;"  ); break;
            case '"':  sb.append("&quot;"); break;
            case '\'': sb.append("&#039;"); break;
            default:   sb.append(c);
            }
        }
        return sb.toString();
    }


    private static final Pattern UNESCAPE_PATTERN = Pattern.compile(
            "&([A-Za-z]+|#(\\d+));");
    private static final HashMap<String, Integer> unescapeMap =
            new HashMap<String, Integer>();
    static {
        unescapeMap.put("amp" , 38); // &
        unescapeMap.put("lt"  , 60); // <
        unescapeMap.put("gt"  , 62); // >
        unescapeMap.put("quot", 34); // "
        unescapeMap.put("apos", 39); // '
    }
    private static String i2s(Integer i) {
        return String.valueOf((char)i.intValue());
    }

    /**
     * HTML のエスケープを元に戻す(Prototype.js の同名メソッドと同等)
     *
     * @param content 対象文字列 (nullも可)
     * @return 元に戻した文字列
     * @since NicoCache_nl+120609mod
     */
    public static String unescapeHTML(String content) {
        if (content == null) {
            return null;
        }
        StringBuffer sb = new StringBuffer(content.length());
        Matcher m = UNESCAPE_PATTERN.matcher(content);
        String str, rep;
        while (m.find()) {
            if (unescapeMap.containsKey(str = m.group(1))) {
                rep = i2s(unescapeMap.get(str));
            } else if (str.startsWith("#")) {
                rep = i2s(Integer.valueOf(m.group(2)));
            } else {
                rep = "$0";
            }
            m.appendReplacement(sb, rep);
        }
        return m.appendTail(sb).toString();
    }

    /**
     * 基本的な XML 実体参照(amp,lt,gt,quot,apos)を元の文字に置換する<br>
     * {@linkplain #unescapeHTML(String)} とは異なり、こちらは連続する
     * <b>'&amp;amp;lt;'</b> を <b>'<'</b> と置換する。
     *
     * @param content 対象文字列 (nullも可)
     * @return 置換した文字列
     */
    public static String replaceER(String content) {
        if (content == null) {
            return null;
        }
        return content.replace("&amp;" , "&" )
                      .replace("&lt;"  , "<" )
                      .replace("&gt;"  , ">" )
                      .replace("&quot;", "\"")
                      .replace("&apos;", "'" );
    }

    /**
     * 文字列に対して JSON 準拠のエスケープ処理を行う
     * @param content 対象文字列 (nullも可)
     * @return エスケープした文字列
     * @since NicoCache_nl+120609mod
     */
    public static String escapeJSON(String content) {
        if (content == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(content.length() * 6);
        char c;
        for (int i = 0; i < content.length(); i++) {
            c = content.charAt(i);
            if (c > 127) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                switch (c) {
                case '/' : sb.append("\\/" ); break;
                case '"' : sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b" ); break;
                case '\f': sb.append("\\f" ); break;
                case '\n': sb.append("\\n" ); break;
                case '\r': sb.append("\\r" ); break;
                case '\t': sb.append("\\t" ); break;
                default  : sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * JSON のエスケープ文字を元に戻す
     * @param content 対象文字列 (nullも可)
     * @return エスケープ解除した文字列
     * @since NicoCache_nl+120609mod
     */
    public static String unescapeJSON(String content) {
        if (content == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(content);
        char c; String s;
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\\' && i + 1 < sb.length()) {
                switch (c = sb.charAt(i + 1)) {
                case 'b': s = "\b"; break;
                case 'f': s = "\f"; break;
                case 'n': s = "\n"; break;
                case 'r': s = "\r"; break;
                case 't': s = "\t"; break;
                case 'u':
                    if (i + 1 + 4 < sb.length()) {
                        try {
                            sb.replace(i, i + 2 + 4, new String(Character.toChars(Integer.parseInt(sb.substring(i + 2, i + 2 + 4), 16))));
                        } catch (NumberFormatException nfe) {
                            continue;
                        }
                    }
                    continue;
                default : s = String.valueOf(c);
                }
                sb.replace(i, i + 2, s);
            }
        }
        return sb.toString();
    }


    private static final Pattern ASCII_UTF16_PATTERN = Pattern.compile(
            "(?<!\\\\)(?:\\\\\\\\)*\\\\u([0-9A-Fa-f]{4})");
    private static final Pattern ASCII_ESCAPE_PATTERN = Pattern.compile(
            "(?<!\\\\)(?:\\\\\\\\)*\\\\([/<>'\"])");

    /**
     * \\uXXXX にエスケープされた文字列を UTF-16 に戻す(native2asciiの逆)<br>
     * 更に <b>/ < > ' "</b> のエスケープも元に戻す(JSON仕様に準拠していません)
     *
     * @param content 対象文字列 (nullも可)
     * @return デコードした文字列
     * @see #unescapeJSON(String)
     */
    public static String ascii2native(String content) {
        if (content == null) {
            return null;
        }
        StringBuffer sb = new StringBuffer(content.length());
        Matcher m = ASCII_UTF16_PATTERN.matcher(content);
        while (m.find()) {
            m.appendReplacement(sb, new String(
                    Character.toChars(Integer.parseInt(m.group(1), 16))));
        }
        m.appendTail(sb);
        return ASCII_ESCAPE_PATTERN.matcher(sb.toString()).replaceAll("$1");
    }

    /**
     * 全角の英数字を半角に変換する
     *
     * @param content 変換対象の文字列 (nullも可)
     * @return 変換された文字列
     */
    public static String zen2han(String content) {
        StringBuilder sb = new StringBuilder(content);
        char c;
        for (int i = 0; i < sb.length(); i++) {
            c = sb.charAt(i);
            if ('０' <= c && c <= '９') {
                sb.setCharAt(i, (char) (c - '０' + '0'));
            } else if ('Ａ' <= c && c <= 'Ｚ') {
                sb.setCharAt(i, (char) (c - 'Ａ' + 'A'));
            } else if ('ａ' <= c && c <= 'ｚ') {
                sb.setCharAt(i, (char) (c - 'ａ' + 'a'));
            }
        }
        return sb.toString();
    }

    public static String join(String[] split) {
        return join(split, null, 0);
    }

    public static String join(String[] split, String separator) {
        return join(split, separator, 0);
    }

    public static String join(String[] split, String separator, int limit) {
        return join(Arrays.asList(split), separator, limit);
    }

    public static String join(List<String> split) {
        return join(split, null, 0);
    }

    public static String join(List<String> split, String separator) {
        return join(split, separator, 0);
    }

    /**
     * 分割された文字列を連結する(JavaScript の同名メソッドと同等)
     *
     * @param split 分割された文字列を含むリスト
     * @param separator 区切り文字列、nullを指定した場合はカンマ(",")
     * @param limit 連結する文字列の上限数、デフォルトは全て連結
     * @return 連結された文字列、リストが空の場合は空文字列を返す
     */
    public static String join(List<String> split, String separator, int limit) {
        if (separator == null) {
            separator = ",";
        }
        if (limit == 0 || split.size() < limit) {
            limit = split.size();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (split.get(i) != null) {
                if (sb.length() > 0) {
                    sb.append(separator);
                }
                sb.append(split.get(i));
            }
        }
        return sb.toString();
    }

    /**
     * バイト単位の数値を最も適切な単位を付加した文字列に変換する
     *
     * @param bytes バイト単位の数値
     * @return 最も適切な単位を付加した文字列
     */
    public static String bytesToString(long bytes) {
        if (bytes > 1099511627776L) {
            return String.format("%,.3f TB", bytes / 1099511627776.0);
        } else if (bytes > 1073741824L) {
            return String.format("%,.2f GB", bytes / 1073741824.0);
        } else if (bytes > 1048576L) {
            return String.format("%,.1f MB", bytes / 1048576.0);
        } else if (bytes > 1024L) {
            return String.format("%,.1f KB", bytes / 1024.0);
        } else if (bytes > 1L) {
            return bytes + " bytes";
        }
        return bytes + " byte";
    }

}
