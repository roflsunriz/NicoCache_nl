package dareka.processor.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HashMap;

import dareka.common.CloseUtil;
import dareka.common.Logger;

/**
 * メソッド情報クラス
 */
class MethodInfo {
    /** インスタンス */
    public Object instance;
    /** メソッド */
    public Method method;
    /** 引数 */
    public Object[] args;
    /** 一度だけ実行するかどうか */
    public boolean once;

    /**
     * コンストラクタ
     *
     * @param instance インスタンス
     * @param method メソッド
     * @param args 引数
     * @param once 一度だけ実行するかどうか
     */
    public MethodInfo(Object instance, Method method, Object[] args, boolean once) {
        this.instance = instance;
        this.method   = method;
        this.args     = args;
        this.once     = once;
    }
    /**
     * コンストラクタ
     *
     * @param instance インスタンス
     * @param method メソッド
     * @param args 引数
     */
    public MethodInfo(Object instance, Method method, Object[] args) {
        this(instance, method, args, true);
    }
}

/**
 * 簡易LocalFlvテンプレートクラス
 * <p>
 * config.propertiesのtemplateFileで指定したファイルをテンプレートとして使用します。
 * デフォルトは/local/list.htmlです。
 * テンプレートが無い場合は、標準の list.js を使用するキャッシュ管理ページを表示します。
 * </p>
 *
 */
public class LocalFlvTemplate {
    /** 設定値 */
    private File templateFile;
    /** 文字コード */
    private String fileEncoding = "UTF-8";
    /** キーワード⇒オブジェクト 変換テーブル */
    private HashMap<String, Object> objectTable = new HashMap<>();
    /** キーワード⇒メソッド 変換テーブル */
    private HashMap<String, MethodInfo> methodTable = new HashMap<>();
    /** 読み込み済みテンプレート。ファイル更新時に破棄する。 */
    private String cachedTemplate;
    private long cachedLastModified = Long.MIN_VALUE;
    private long cachedLength = Long.MIN_VALUE;
    private String cachedEncoding;

    /**
     * コンストラクタ
     *
     * @param templateFile テンプレートファイル
     */
    public LocalFlvTemplate(File templateFile) {
        this.templateFile = templateFile;
    }

    /**
     * テンプレートファイルを読み込み、キーワード置き換え後の結果を返します。
     *
     * @return 結果
     * @throws IOException テンプレートファイルの読み込みに失敗した場合
     */
    public String execute() throws IOException {
        return replaceAll(loadTemplate());
    }

    private synchronized String loadTemplate() throws IOException {
        File file = templateFile;
        if (!file.isFile()) {
            throw new IOException("template file is not readable: " + file);
        }
        long lastModified = file.lastModified();
        long length = file.length();
        if (cachedTemplate != null
                && lastModified == cachedLastModified
                && length == cachedLength
                && fileEncoding.equals(cachedEncoding)) {
            return cachedTemplate;
        }

        StringBuilder contents = new StringBuilder(
                length > Integer.MAX_VALUE ? 8192 : (int)length);
        BufferedReader br = null;

        try {
            String line;
            String sep = System.getProperty("line.separator");
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), fileEncoding));
            while ((line = br.readLine()) != null) {
                contents.append(line).append(sep);
            }
        } finally {
            CloseUtil.close(br);
        }

        cachedTemplate = contents.toString();
        cachedLastModified = file.lastModified();
        cachedLength = file.length();
        cachedEncoding = fileEncoding;
        return cachedTemplate;
    }

    /**
     * キーワードを割り当てられたオブジェクトの文字列またはメソッドの実行結果に置き換えます。
     *
     * @param contents テンプレートコンテンツ
     * @return 結果
     */
    @SuppressWarnings("fallthrough")
    protected String replaceAll(String contents) {
        StringBuilder buf = new StringBuilder(contents.length());

        int pos = 0;
        while (pos < contents.length()) {
            char c = contents.charAt(pos);
            switch(c) {
            case '$':
                String key = getKeyword(contents, pos);
                if (key != null && containsKey(key)) {
                    Logger.debug("match keyword: " + key);
                    String value = getValue(key);
                    buf.append(value);
                    pos += (3 + key.length());
                    break;
                }
            default:
                buf.append(c);
                pos += 1;
            }
        }

        return buf.toString();
    }

    /**
     * キーワードを抽出します。
     *
     * @param contents コンテンツ
     * @param pos 現在位置
     * @return キーワード。見つからない場合はnull。
     */
    protected String getKeyword(String contents, int pos) {
        if (pos + 1 >= contents.length()) return null;
        if (contents.charAt(pos+1) != '{') return null;

        int start = pos + 2;
        int end   = contents.indexOf('}', start);
        if (end < 0) return null;

        String key = contents.substring(start, end);

        return key;
    }

    /**
     * 割り当てられたオブジェクトから結果を取得します。
     *
     * @param key キーワード
     * @return 結果
     */
    protected String getValue(String key) {
        if (objectTable.containsKey(key)) {
            return objectTable.get(key).toString();
        }
        Object value = null;
        MethodInfo info = methodTable.get(key);
        try {
            // メソッドを実行
            value = info.method.invoke(info.instance, info.args);
        } catch (Exception e) {
            Logger.error(e);
        }
        String result = (value == null) ? "" : value.toString();
        if (info.once) {
            objectTable.put(key, result);
            methodTable.remove(key);
        }

        return result;
    }

    /**
     * キーワードにオブジェクトを割り当てます。
     *
     * @param key キーワード
     * @param value オブジェクト
     * @return 既に割り当てられている場合はfalse
     */
    public boolean assign(String key, Object value) {
        if (containsKey(key)) return false;
        objectTable.put(key, value);
        return true;
    }

    /**
     * キーワードにメソッドを割り当てます。
     *
     * @param key キーワード
     * @param instance 割り当てるメソッドを持つインスタンス
     * @param method メソッド
     * @param args 引数
     * @param once 1回だけ実行
     * @return 既に割り当てられている場合はfalse
     */
    public boolean assign(String key, Object instance, Method method, Object[] args, boolean once) {
        if (containsKey(key)) return false;
        methodTable.put(key, new MethodInfo(instance, method, args, once));
        return true;
    }

    /**
     * キーワードにstaticメソッドを割り当てます。
     *
     * @param key キーワード
     * @param method staticメソッド
     * @param args 引数
     * @return 既に割り当てられている場合はfalse
     */
    public boolean assign(String key, Method method, Object[] args) {
        return assign(key, null, method, args, true);
    }

    /**
     * キーワードにstaticメソッドを割り当てます。
     *
     * @param key キーワード
     * @param method staticメソッド
     * @return 既に割り当てられている場合はfalse
     */
    public boolean assign(String key, Method method) {
        return assign(key, null, method, null, true);
    }

    /**
     * キーワードにメソッドを割り当てます。
     *
     * @param key キーワード
     * @param instance 割り当てるメソッドを持つインスタンス
     * @param method メソッド
     * @return 既に割り当てられている場合はfalse
     */
    public boolean assign(String key, Object instance, Method method) {
        return assign(key, instance, method, null, true);
    }

    /**
     * 割り当てを全て解除します。
     *
     */
    public void clear() {
        objectTable.clear();
        methodTable.clear();
    }

    /**
     * キーワードが既に割り当てられているか調べます。
     *
     * @param key キーワード
     * @return 割り当てられていたらtrue
     */
    public boolean containsKey(String key) {
        if (objectTable.containsKey(key) || methodTable.containsKey(key)) {
            return true;
        }
        return false;
    }

    @Deprecated
    public boolean containsKey(Object key) {
        if (objectTable.containsKey(key) || methodTable.containsKey(key)) {
            return true;
        }
        return false;
    }

    /**
     * テンプレートファイルの文字コードを指定します。<br>
     * デフォルトはUTF-8です。
     *
     * @param fileEncoding 文字コード
     */
    public void setFileEncoding(String fileEncoding) {
        synchronized (this) {
            this.fileEncoding = fileEncoding;
            cachedTemplate = null;
            cachedEncoding = null;
        }
    }
}
