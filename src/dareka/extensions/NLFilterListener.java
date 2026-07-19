package dareka.extensions;

import java.util.EventListener;

import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;

/**
 * nlFilter を拡張する Extension のインターフェース<br>
 * <b>まだ仕様確定じゃないので使う時は互換性が無くなっても文句を言わないこと</b>
 * @since NicoCache_nl+110411mod
 * TODO EventListenerに合わせた形に仕様を変更
 */
public interface NLFilterListener extends EventListener {

    int NOT_SUPPORTED = 0;
    int NOT_EXISTS    = 1;
    int EXISTS        = 2;
    int EXISTS_LOW    = 3;
    int EXISTS_DMC    = 4;
    int EXISTS_DMCLOW = 5;

    /**
     * nlFilter の書き換え対象コンテンツから情報を取得するインターフェース
     */
    interface Content {
        /**
         * 書き換え対象コンテンツの URL を取得する。
         * @return コンテンツのURL
         */
        String getURL();

        /**
         * 書き換え対象コンテンツの内容を取得する。
         * @return コンテンツ文字列
         */
        String getContent();

        /**
         * 書き換え対象コンテンツのリクエストヘッダを取得する。
         * @return コンテンツ文字列
         */
        HttpRequestHeader getRequestHeader();

        /**
         * 書き換え対象コンテンツのレスポンスヘッダを取得する。
         * @return コンテンツ文字列
         */
        HttpResponseHeader getResponseHeader();

        /**
         * コンテンツに関連付けられた変数(nlVar)の値を取得する。
         * <code>getVariable(name, null)</code>と同じ。
         * @param name 取得する変数名
         * @return 変数の値、存在しない場合は null
         */
        String getVariable(String name);

        /**
         * コンテンツに関連付けられた変数(nlVar)の値を取得する。
         * @param name 取得する変数名
         * @param def 変数が存在しない場合のデフォルト値
         * @return 変数の値
         */
        String getVariable(String name, String def);

        /**
         * コンテンツに関連付けられた変数(nlVar)の値を設定する。
         * @param name 設定する変数名
         * @param value 設定値、変数を削除する場合は null を指定
         * @return 以前の設定値、存在しなければ null
         */
        String setVariable(String name, String value);

    }

    /**
     * nlFilter(not nlFilter_sys)がコンテンツを処理する前にシステムから呼び出される。
     * nlFilter 処理前のコンテンツの内容を参照したり、Extension
     * 独自の変数(nlVar)を事前に設定する、といったことができる。
     * @param content 書き換え対象コンテンツ
     */
    void nlFilterBegin(Content content);

    /**
     * Replace 内に &lt;nlVar:name&gt; が出現する度にシステムから呼び出される。
     * 任意の nlVar に対して Extension 独自の値を返すことができる。
     * {@link #nlFilterBegin(Content)} 内で事前に値を設定する場合と異なり、
     * 実際に nlVar が参照された場合に呼び出される。
     *
     * @param name 対象の変数名
     * @param content 書き換え対象コンテンツ
     * @return 変数の値、変数名が対象外の場合は null
     */
    String nlVarReplace(String name, Content content);

    /**
     * idGroup が指定されていて、更に1番目にマッチした文字列がニコ動の
     * <b>smid</b>(^[a-z]{2}\d{1,9}$) 以外の場合にシステムから呼び出される。
     *
     * <p>ニコ動の場合は2番目の引数を省略すると1番目と同じ扱いになるが、
     * Extensionの場合は2番目の引数を省略すると存在しないものとして扱う。
     * また、2番目の引数に数値以外を渡すことで、文字列そのものを渡すことができる。
     *
     * <p>主に本体側がサポートしていない URL をキャッシュする Extension が、
     * nlFilter を使ってキャッシュの有無に応じたフィルタを適用することができる。
     *
     * @param s1 idGroupの1番目にマッチした文字列
     * @param s2 idGroupの2番目にマッチした文字列、存在しなければ null
     * @return 以下のいずれかの値を返すこと
     * <ul>
     * <li>{@link #NOT_SUPPORTED} パラメータ引数がサポート外 or idGroup に対応していない
     * <li>{@link #NOT_EXISTS} キャッシュが存在しない
     * <li>{@link #EXISTS}     キャッシュが存在する(<$>指定の場合は通常キャッシュ側を使用)
     * <li>{@link #EXISTS_LOW} キャッシュが存在する(<$>指定の場合はエコノミーキャッシュ側を使用)
     * </ul>
     */
    int idGroup(String s1, String s2);

    /**
     * nlFilter によって LST が初めて読み込まれた時、および
     * {@linkplain dareka.processor.impl.EasyRewriter.LST LSTアクセスAPI}
     * を通じて LST が更新された時にシステムから呼び出される。
     * 仕様上、ひとつの LST が更新されると "list/foo.txt" と "!list/foo.txt"
     * という二度の呼び出しが発生するので注意すること。
     *
     * @param list 更新されたリストのファイル名
     */
    void updateLST(String list);

}
