package dareka.common;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * - LRU(Least Recently Used)式連想配列.
 * - put処理で要素数がmaxSizeを越えた場合に先頭要素を削除する.
 * - get処理でその要素を末尾に移動する.
 * - containsKey処理では末尾に移動しない.
 */
public class LRUMap<K, V> extends LinkedHashMap<K, V> {

    private static final long serialVersionUID = 1L;
    private int maxEntries;

    /**
     * 最小容量でLRUMapを作成する
     */
    public LRUMap() {
        this(12);
    };

    /**
     * 指定された最大エントリ数でLRUMapを作成する。
     * マップ容量は最大エントリ数が収まるように調整する。
     *
     * @param maxEntries 最大エントリ数
     */
    public LRUMap(int maxEntries) {
        this(maxEntries, 0.75f);
    };

    public LRUMap(int maxEntries, float loadFactor) {
        super((int)(Math.max(maxEntries, 12) / loadFactor * 1.05f),
              loadFactor,
              true);
        setMaxEntries(maxEntries);
    };

    /**
     * マップの最大エントリ数を取得する。
     *
     * @return 最大エントリ数
     */
    public int getMaxEntries() {
        return maxEntries;
    };

    /**
     * マップの最大エントリ数を設定する。
     * 最大エントリ数を縮小した場合は古いエントリから削除する。
     *
     * @param maxEntries 新たに設定する最大エントリ数
     */
    public void setMaxEntries(int maxEntries) {
        this.maxEntries = Math.max(maxEntries, 12);
        if (size() > maxEntries) {
            Iterator<K> i = keySet().iterator();
            while (size() > maxEntries && i.hasNext()) {
                i.next();
                i.remove();
            };
        };
    };

    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxEntries;
    };
};
