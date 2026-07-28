package dareka.processor.impl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.NLConfig;
import dareka.common.CloseUtil;
import dareka.common.Config;
import dareka.common.ConfigObserver;
import dareka.common.Logger;
import dareka.common.TextUtil;
import dareka.extensions.CompleteCache;
import dareka.extensions.NLFilterListener;
import dareka.extensions.SystemEventListener;
import dareka.processor.util.GetThumbInfoUtil;

/**
 * [nl] パッケージスコープで共有情報を保持するクラス<br>
 * パッケージスコープ外からは許可したクラスのみインスタンスを取得できる
 * @since NicoCache_nl+110424mod
 */
public class NLShared implements ConfigObserver {

    // パッケージスコープ外でインスタンス化を許容するクラス名のパターン
    private static final Pattern FRIENDS_CLASS_PATTERN = Pattern.compile(
            "dareka\\.(NLMain|processor.+)");

    private NLShared() {
        NLConfig.addObserver(this);
    }

    @Override
    public void update(Config config) {
        vid2cidFile = NLConfig.getFile("vid2cidFile");
        vid2cidLastModified = 0L; // 強制読み直し
    }

    private static final boolean DEBUG = Boolean.getBoolean("dareka.debug");

    /** デバッグモードの時は通常ログとしても出力する */
    public void debugWithThread2(String message) {
        if (DEBUG) {
            Logger.info(message);
        }
        Logger.debugWithThread(message);
    }

    /** パッケージスコープ内でインスタンスを利用する場合はこちらを使う */
    static final NLShared INSTANCE = new NLShared();

    /**
     * {@link #FRIENDS_CLASS_PATTERN} で許可されたクラスにのみインスタンスを返す。
     * 呼び出し元チェックにコストがかかるので、複数必要な場合は保持して使い回すこと。
     *
     * @return NLSharedのインスタンス
     * @throws RuntimeException 許可されていないクラスからの呼び出し
     */
    public static NLShared getInstance() {
        String callerName = "(no name)";
        StackTraceElement[] ste = new Throwable().getStackTrace();
        if (ste.length > 1) {
            callerName = ste[1].getClassName();
        }
        if (!FRIENDS_CLASS_PATTERN.matcher(callerName).matches()) {
            throw new RuntimeException("you are not friends: " + callerName);
        }
        return INSTANCE;
    }


    /**
     * ニコ生からのsmidとリクエスト時間を保持
     * @since NicoCache_nl (9).03
     */
    private static ConcurrentHashMap<String, Long> liveIdList =
        new ConcurrentHashMap<>();

    public void setLiveMovie(String smid) {
        liveIdList.put(smid, System.currentTimeMillis());
    }

    public boolean isLiveMovie(String smid) {
        // 古いのは削除
        long now = System.currentTimeMillis();
        for (String oldId : liveIdList.keySet()) {
            Long setTime = liveIdList.get(oldId);
            // とりあえず30秒無効にしてみる
            if (setTime != null && now > setTime + 30000L) {
                liveIdList.remove(oldId);
            }
        }
        return liveIdList.containsKey(smid);
    }


    /**
     * キャッシュ途中で削除する為のリスト
     * @since NicoCache_nl (9).10
     */
    private static Set<String> deleteSet =
        Collections.synchronizedSet(new HashSet<>());

    public boolean isInDeleteSet(String id) {
        return deleteSet.contains(id);
    }

    public void addDeleteSet(String id) {
        deleteSet.add(id);
    }

    public void removeDeleteSet(String id) {
        deleteSet.remove(id);
    }


    /**
     * キャッシュ完了時に呼び出されるExtension →CacheManagerあたりが適切？
     * @since NicoCache_nl (9).10
     */
    private static List<CompleteCachePair> completeCaches =
        Collections.synchronizedList(new ArrayList<>());

    class CompleteCachePair {
        public int priority;
        public CompleteCache completeCache;
        CompleteCachePair(int p, CompleteCache c) {
            this.priority = p;
            this.completeCache = c;
        }
    }

    public void addCompleteCache(CompleteCache c) {
        completeCaches.add(new CompleteCachePair(0, c));
    }

    public ArrayList<CompleteCache> getCompleteEntries() {
        // リストを返す前にupdateを呼び出してソートする
        for (CompleteCachePair p : completeCaches) {
            p.completeCache.update();
            p.priority = p.completeCache.getPriority();
            if (p.priority > 0) p.priority = 0; // 仕様上0が最高値なので
        }

        // コピーしたのをソートしてるから、マルチスレッド対策は特に要らないはず
        Object[] objArray = completeCaches.toArray();
        Arrays.sort(objArray, (o1, o2) ->
                ((CompleteCachePair) o1).priority - ((CompleteCachePair) o2).priority);

        ArrayList<CompleteCache> list = new ArrayList<>();
        for (Object o : objArray) {
            list.add(((CompleteCachePair) o).completeCache);
        }
        return list;
    }


    /**
     *  スレッドIDからsmidへの対応を保持
     *  @since NicoCache_nl+110424mod
     */
    private static Map<String, String> thread2smidCache =
//      Collections.synchronizedMap(new LRUMap<String, String>());
            new java.util.concurrent.ConcurrentHashMap<>();

    /** スレッドに関連付けられたsmidを取得、関連が無ければnullを返す */
    public String thread2smid(String thread) {
        if (!TextUtil.isThreadId(thread)) {
            return null;
        }
        if (!thread2smidCache.containsKey(thread)) {
            // キャッシュに無ければthumbinfoを取得してみる
            try {
                GetThumbInfoUtil.get(thread);
            } catch (IOException e) {
                Logger.debugWithThread(e);
            }
        }
        return thread2smidCache.get(thread);
    }

    /** スレッドとsmidを関連付ける */
    public void putThread2Smid(String thread, String smid) {
        if (TextUtil.isThreadId(thread) && TextUtil.isVideoId(smid)) {
            thread2smidCache.put(thread, smid);
            Logger.debugWithThread("thread2smid: " + thread + " => " + smid +
                    ", size=" + thread2smidCache.size());
        }
    }


    /**
     * 動画鯖のID(数値=vid)から実際にキャッシュするID(数値=cid)への対応を保持
     * @since NicoCache_nl+110424mod
     */
    private static Map<String, String> vid2cid = newTreeMap();
    private static File vid2cidFile;
    private static long vid2cidLastModified;

    private static final Pattern VID2CID_LINE_PATTERN = Pattern.compile(
            "\\s*\"(\\d+)\"\\s*:\\s*\"(\\d+)\",?");

    private static TreeMap<String, String> newTreeMap() {
        return new TreeMap<>((s1, s2) ->
                // Integerの範囲に収まらない(10桁以上の)入力があるので
                (int)(Long.parseLong(s1) - Long.parseLong(s2)));
    }

    /**
     * vid に関連付けられた cid を取得、無ければ vid をそのまま返す
     * @param vid 動画鯖のID(数値)
     * @return 実際にキャッシュするID(数値)
     */
    public String vid2cid(String vid) {
        loadJson();
        String cid = vid2cid.get(vid);
        if (cid != null) {
            Logger.debugWithThread(
                    "vid2cid(get): " + vid + " => " + cid);
            return cid;
        }
        return vid;
    }

    /**
     * vid と cid を関連付ける(結果は vid2cidFile に保存する)
     */
    public void put_vid2cid(String vid, String cid) {
        if (!validateVID(vid)) {
            Logger.warning("invalid vid: " + vid);
            return;
        }
        debugWithThread2("vid2cid(put): " + vid + " => " + cid);

        synchronized (vid2cid) {
            String prevId = vid2cid.put(vid, cid);
            if (prevId == null || !prevId.equals(cid)) {
                saveJson();
            }
        }
    }

    /** videoIdとして有効な文字列か？ */
    public boolean validateVID(String vid) {
        try {
            // 動画IDは9桁まで有効
            if (Integer.parseInt(vid) < 1000000000) {
                return true;
            }
        } catch (NumberFormatException e) {
            Logger.debugWithThread(e);
        }
        return false;
    }

    // vid2cid専用形式として検証と差し替えを一体で行う。
    private void loadJson() {
        if (vid2cidFile == null || !vid2cidFile.isFile() ||
                vid2cidFile.lastModified() <= vid2cidLastModified) {
            return;
        }
        debugWithThread2("loading " + vid2cidFile.getPath());
        BufferedReader br = null;
        try {
            TreeMap<String, String> map = newTreeMap();
            br = new BufferedReader(new FileReader(vid2cidFile));
            String line; Matcher m;
            while ((line = br.readLine()) != null) {
                if ((m = VID2CID_LINE_PATTERN.matcher(line)).lookingAt() &&
                        !NLConfig.matches("deletedVideoId", m.group(1))) {
                    map.put(m.group(1), m.group(2));
                }
            }
            vid2cidLastModified = vid2cidFile.lastModified();
            vid2cid = map;
        } catch (IOException e) {
            Logger.error(e);
        } finally {
            CloseUtil.close(br);
        }
    }

    // vid2cidのバックアップ作成と保存を一体で行う。
    private void saveJson() {
        if (vid2cidFile == null) {
            return;
        }
        debugWithThread2("saving " + vid2cidFile.getPath());
        if (vid2cidFile.exists()) {
            File bakFile = new File(vid2cidFile.getPath() + ".bak");
            bakFile.delete();
            vid2cidFile.renameTo(bakFile);
        }
        BufferedWriter bw = null;
        boolean written = false;
        try {
            bw = new BufferedWriter(new FileWriter(vid2cidFile));
            for (Map.Entry<String, String> e : vid2cid.entrySet()) {
                if (written) {
                    bw.append(",\r\n");
                } else {
                    bw.append("{\r\n");
                    written = true;
                }
                bw.write("\"");
                bw.write(e.getKey());
                bw.write("\": \"");
                bw.write(e.getValue());
                bw.write("\"");
            }
            bw.write("\r\n}");
        } catch (IOException e) {
            Logger.error(e);
        } finally {
            CloseUtil.close(bw);
            if (written) {
                vid2cidLastModified = vid2cidFile.lastModified();
            }
        }
    }


    /**
     * イベント通知インターフェース
     * @since NicoCache_nl+111130mod
     */
    private static List<SystemEventListener> systemEventListeners =
        Collections.synchronizedList(new ArrayList<>());

    public void addEventListener(EventListener listener) {
        boolean added = false;
        if (listener instanceof SystemEventListener) {
            Logger.debug("adding SystemEventListener: " + listener);
            added |= systemEventListeners.add((SystemEventListener) listener);
        }
        if (listener instanceof NLFilterListener) {
            Logger.debug("adding NLFilterListener: " + listener);
            added |= EasyRewriter.addListener((NLFilterListener) listener);
        }
        if (!added) {
            Logger.debug("unknown EventListener: " + listener);
        }
    }

    public int countSystemEventListeners() {
        return systemEventListeners.size();
    }

    /**
     * システムイベントを全体に通知する
     * @param id イベントID
     * @param source イベント発生元オブジェクト
     * @param checkResultOK 戻り値をチェックして途中で処理を中断するか？
     * @return イベント処理結果
     */
    public int notifySystemEvent(int id, NLEventSource source, boolean checkResultOK) {
        for (SystemEventListener listener : systemEventListeners) {
            try {
                int result = listener.onSystemEvent(id, source);
                if (checkResultOK && result != SystemEventListener.RESULT_OK) {
                    return result;
                }
            } catch (Throwable e) {
                Logger.error(e);
            }
        }
        return SystemEventListener.RESULT_OK;
    }

    /**
     *  ht2認証情報に付随する動画情報を保持
     *  @since NicoCache_nl+150304mod+170104mod
     *  2024-08: dmc動画廃止に伴い既に不要なはず.
     */
    private final Ht2Manager ht2manager = new Ht2Manager();
    public Ht2Manager getHt2Manager() {
        return ht2manager;
    }

    /**
     * キャッシュ保存中に必要なDomand(DMS), CMAF動画の情報を通信間で共有するため
     * のもの
     * 2024-03-05
     */
    private final DomandCVIManager domandCVIManager = new DomandCVIManager();
    public DomandCVIManager getDomandCVIManager() {
        return domandCVIManager;
    };
}
