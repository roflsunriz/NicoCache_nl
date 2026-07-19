package dareka.processor.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import dareka.common.Logger;

// - Domand仕様の通信ごとに情報を共有するための連想配列.
// - 子要素(DomandCVIEntry)はいつまで保持しておけばいいか不明なもの.
// - 実用のために古いものから順に捨てる機能を提供する.
// - Domand-Cache-Video Info-EntryのManager.
public class DomandCVIManager {

    // コンテナの初期キャパシティ.
    private static final int initialCapacity =
        5 * 70;
    // コンテナ内要素数がこれを超過したら忘却処理を行なう.
    private static final int gcThresold =
        5 * 60;
    // 忘却処理後にこのサイズになるようにする.
    private static final int sizeAfterGC =
        5 * 50;

    // - 上記これらに強い根拠はない. 一般的利用の上限を予測して設定する.
    // - 1動画で5個ほど使う.
    // - 上から順に大→小であること.

    private final HashMap<String, DomandCVIEntry> container;

    public DomandCVIManager() {
        this.container = new HashMap<>(initialCapacity);
    };

    public void update(DomandCVIEntry entry) {
        boolean needGc = false;
        synchronized (container) {
            if (container.size() > gcThresold) {
                needGc = true;
            };
        };
        if (needGc) {
            gc();
        };
        synchronized (container) {
            container.put(entry.getKey(), entry);
        };
    };

    public DomandCVIEntry get(String key) {
        synchronized (container) {
            return container.get(key);
        }
    };

    // 動画のキャッシュを削除したとき、保持中のCMAF状態も破棄する.
    // 破棄しないと、再視聴時に完了済みのEntryを再利用してキャッシュ処理が始まらない.
    public void removeBySmid(String smid) {
        synchronized (container) {
            Iterator<Map.Entry<String, DomandCVIEntry>> iterator =
                container.entrySet().iterator();
            while (iterator.hasNext()) {
                DomandCVIEntry entry = iterator.next().getValue();
                if (smid.equals(entry.getSmid())) {
                    iterator.remove();
                };
            };
        };
    };

    // - この方法は不適切.
    // - 意味的にはupdateOnを基準にするべき.
    public void gc() {
        Logger.info("DomandCVIManager.gc");
        synchronized (container) {
            gcSub();
        };
        Logger.debug("DomandCVIManager.gc: end");
    };

    private void gcSub() {
        if (container.size() < gcThresold) {
            return;
        };

        int remove_count = container.size() - sizeAfterGC;

        List<DomandCVIEntry> list = new ArrayList<>(container.values());
        list.sort(new EntryComparator());

        for (int i=0; i < remove_count; ++i) {
            DomandCVIEntry entry = list.get(i);
            container.remove(entry.getKey());
            Logger.debug("DomandCVIManager.gc: removing " + entry.getKey());
        };
    };

    static class EntryComparator implements Comparator<DomandCVIEntry> {
        @Override
        public int compare(DomandCVIEntry a, DomandCVIEntry b) {
            return ((Long)a.getUpdatedOn()).compareTo(b.getUpdatedOn());
        };
    };
};
