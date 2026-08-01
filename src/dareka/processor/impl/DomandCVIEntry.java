package dareka.processor.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.UnsupportedOperationException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dareka.common.Logger;
import dareka.extensions.CompleteCache;
import dareka.extensions.SystemEventListener;
import dareka.common.M3u8Util;

// - Domand仕様の通信間で情報を共有するためのコンテナ.
// - nltmp_smXXX[xxxp,xxx]_title.hlsを作成する前の状態からそれをstore(complete)処理する
//   までの状態を扱う.
// - このクラスに実装寄りのコードを書くべきではないが現在含まれている(chunkLoadStart,
//   mightWrite, segmentsComplete, cacheStoreなど).
// - audio用とvideo用それぞれのプロパティが含まれるのは名残りであって、現在は一つの
//   サブプレイリストを扱うから整理するべき.
// - key値の仕様はロジック側(CmafCachingProcessor.java)を参照.
// - スペルアウトはDomand Cache Video Info Entry.
// - NLShared.INSTANCE.getDomandCVIManager().update(entry);
// - 一部コメントの unsynchronized は非同期性を考慮済みであることを強調するマーク.
public class DomandCVIEntry {
    // - synchronizedの付け方に一貫性がない. 利用例に依存して付けている.

    // DomandCVIManagerがこのEntryを残すか捨てるか判断するのに使う.
    private long updatedOn;

    // 連想配列のキー(暗号鍵ではない). 重複しなければ形式は自由.
    // キャッシュ中は[動画IDと品質モード]とkeyが対応付いていると都合が良い.
    private final String key;

    private final String videoType; // smとか.
    private final String videoNumId; // smなどを除いた動画番号.
    private final int videoHeight; // 例: 1080
    private       int audioKbps; // 例: 128
    private final String videoMode; // 例: "1080p"
    private final String videoSrcId; // null |例: "video-h264-1080p"
    private final String audioSrcId; // null |例: "audio-aac-128kbps"
    private       String videoCodec; // null |例: "avc1.4d4020"
    private       String audioCodec; // null |例: "mp4a.40.2"
    private final Boolean lowAccess; // 最高品質の映像と音声であるならfalse.

    private final String postfix; // 例: ".hls"
    private final NicoIdInfoCache.Entry idInfo; // videoNumId と紐付いている情報.
    private final VideoDescriptor videoDescriptor;
    // - videoDescriptorが示す品質よりも高品質なキャッシュを指す場合もある.
    // - リクエストを表現するためのcache. 整理のために変更すること.
    private Cache cache;
    // - キャッシュ利用(保存ではなく読み出し)するためのcache.
    private Cache cacheOfCacheForUsing = null;

    private Boolean cacheSaveFlag = true; // キャッシュしないならfalse.
    // 実装時点ではHLS AES-128（AES/CBC/PKCS7Padding）だけを扱うためmethodは記録しない.
    private byte[] audioIV = null; // 復号に使う初期化ベクトル
    private byte[] videoIV = null; // 復号に使う初期化ベクトル
    private byte[] audioKey = null; // 復号に使うキー
    private byte[] videoKey = null; // 復号に使うキー

    // HLSの#EXT-X-KEYは、次の#EXT-X-KEYまで後続セグメントへ適用される。
    // そのためプレイリスト内で鍵・IVが切り替わる配信では、品質全体で一つの
    // 値を共有すると後続セグメントを別の鍵で復号してしまう。
    private Map<String, String> audioKeyUrlsBySegmentUrl = new HashMap<>();
    private Map<String, byte[]> audioIVsBySegmentUrl = new HashMap<>();
    private Map<String, byte[]> audioKeysByUrl = new HashMap<>();
    private Map<String, String> videoKeyUrlsBySegmentUrl = new HashMap<>();
    private Map<String, byte[]> videoIVsBySegmentUrl = new HashMap<>();
    private Map<String, byte[]> videoKeysByUrl = new HashMap<>();

    // - 復号に使う初期化ベクトルとキーは復号対象よりも後にダウンロードされるかも知れない.
    //   それに備えて復号処理を後で行なえるようにする.
    // - いまのところは順序が重要な処理は担わせないからHashSet.
    private Set<Runnable> gotAudioDecryptInfoListeners = new HashSet<>();
    private Set<Runnable> gotVideoDecryptInfoListeners = new HashSet<>();
    private Map<String, Set<Runnable>> gotAudioDecryptInfoListenersBySegmentUrl =
        new HashMap<>();
    private Map<String, Set<Runnable>> gotVideoDecryptInfoListenersBySegmentUrl =
        new HashMap<>();

    // - 動画チャンクをダウンロードし始めてからキャッシュ一時ディレクトリを作るようにするための
    //   変数たち.
    // - m3u8だけが読み込まれチャンクが読み込まれない状況を想定している.
    //   例えばvideo-h264-360p.m3u8が通信に上がった段階で一時ディレクトリを作ってしまうと
    //   m3u8だけが入ったnltmp_*ディレクトリが増殖してしまう.
    // - あまり複雑になったら分離すること. 分離を前提に組むこと.
    private boolean chunkLoadStartedFlag = false;
    // - ファイル名stringとbyte[]コンテンツの2要素を持つ配列にしてしまった方がいい.
    private byte[] masterM3u8 = null; // master.m3u8 書き込み待ちのファイル内容
    private byte[] videoM3u8 = null; // video.m3u8 上同
    private byte[] audioM3u8 = null; // audio.m3u8 上同

    // - ニコ動のcmaf動画は一つのaudioに複数のvideoが紐付いていた.
    // - 2025-03-28: 状況が変わり複数のaudio品質がマスタープレイリストに提示されるように
    //   なった.
    // - このプロパティはその相互の紐付けを管理する.
    // - 従って潜るように参照していくと循環する(同じインスタンスに繰り返し出会う).
    // - audioを管理するthisの場合、このプロパティにはvideoを管理するDomandCVIEntryが入る.
    // - videoの場合はaudioのDomandCVIEntryが入る.
    // - このクラスは継承しないし、このプロパティの変更に伴う処理もないから
    //   setter/getterを用意しない.
    // - 5は1080p, 720p, 480p, 360p, 360p-lowestという想定.
    // - インデックスが小さい程高品質.
    public List<DomandCVIEntry> assocList = new ArrayList<>(5);

    // - 初期化はロジック側が行う.
    private CacheManager.HlsTmpSegments hlsTmpSegments = null;

    // - trueならばthisが役割を終えた状態.
    //   - ハンドルしていたnltmpはすでに存在していない.
    private boolean completedFlag = false;

    public Set<DomandCVIEntry> getFamilyEntries() {
        Set<DomandCVIEntry> entries = new HashSet<>(assocList.size() +
                                                    assocList.get(0).assocList.size());
        for (DomandCVIEntry x : assocList) {
            entries.add(x);
        };
        for (DomandCVIEntry x : assocList.get(0).assocList) {
            entries.add(x);
        };
        return entries;
    };

    public DomandCVIEntry getDomandCVIEntryByVideoSrcId(String srcId) {
        for (DomandCVIEntry x : getFamilyEntries()) {
            if (srcId.equals(x.videoSrcId)) {
                return x;
            };
        };
        return null;
    };

    public DomandCVIEntry getDomandCVIEntryByAudioSrcId(String srcId) {
        for (DomandCVIEntry x : getFamilyEntries()) {
            if (srcId.equals(x.audioSrcId)) {
                return x;
            };
        };
        return null;
    };

    public DomandCVIEntry(
        String key
        , String videoType, String videoNumId, int videoHeight, int audioKbps
        , String videoMode, String videoSrcId, String audioSrcId
        , Boolean lowAccess, String postfix, NicoIdInfoCache.Entry idInfo
        , VideoDescriptor videoDescriptor, Cache cache) {

        this.updatedOn = System.currentTimeMillis();

        this.key = key;

        this.videoType = videoType;
        this.videoNumId = videoNumId;
        this.videoHeight = videoHeight;
        this.audioKbps = audioKbps;
        this.videoMode = videoMode;
        // - 2025-03: 両方が同時にnon-nullであることはない.
        // - 必ず一方がnullでもう一方がString.
        this.videoSrcId = videoSrcId;
        this.audioSrcId = audioSrcId;

        this.lowAccess = lowAccess;
        this.postfix = postfix;
        this.idInfo = idInfo;
        this.videoDescriptor = videoDescriptor;
        this.cache = cache;
    };

    public long getUpdatedOn() {
        return updatedOn;
    };

    public String getKey() {
        return key;
    };

    public String getVideoType() {
        return videoType;
    };

    public String getVideoNumId() {
        return videoNumId;
    };

    // "smXXX" を返す
    public String getSmid() {
        // 表記先例はgetSmidが優勢. getSMIDもある.
        return videoType + videoNumId;
    };

    public int getVideoHeight() {
        return videoHeight;
    };

    public int getAudioKbps() {
        return audioKbps;
    };

    public String getVideoMode() {
        return videoMode;
    };

    public String getVideoSrcId() {
        return videoSrcId;
    };

    public String getAudioSrcId() {
        return audioSrcId;
    };

    public String getVideoCodec() {
        return videoCodec;
    };

    public String getAudioCodec() {
        return audioCodec;
    };

    public void setVideoCodec(String v) {
        videoCodec = v;
    };

    public void setAudioCodec(String v) {
        audioCodec = v;
    };

    public Boolean getLowAccess() {
        return lowAccess;
    };

    public String getPostfix() {
        return postfix;
    };

    public NicoIdInfoCache.Entry getIdInfo() {
        return idInfo;
    };

    // - これはcacheから取り出すべき.
    public VideoDescriptor getVideoDescriptor() {
        return videoDescriptor;
    };

    public void setCache(Cache v) {
        cache = v;
    };

    public Cache getCache() {
        return cache;
    };

    public synchronized void setCacheOfCacheForUsing(Cache v) {
        cacheOfCacheForUsing = v;
    };
    public synchronized Cache getCacheOfCacheForUsing() {
        return cacheOfCacheForUsing;
    };

    // - マスタープレイリストを頂点とするハンドルファミリーのロック用代表オブジェクトを
    //   返す.
    // - ファミリー内のどことから呼んでも同じobjectが返る.
    public DomandCVIEntry getRepresentative() {
        return getAudioDomandCVIEntries().get(0);
    };

    public List<DomandCVIEntry> getAudioDomandCVIEntries() {
        if (isAudioHandling()) {
            DomandCVIEntry video = assocList.get(0);
            return video.assocList;
        };
        return assocList;
    };

    // - 関連すべてのcacheSaveFlagを変更する.
    // - thisだけのcacheSaveFlagを変更することを考慮しない.
    // - 制御的な関数名よりも意味的な複数の関数に分けた方がよい.
    public Boolean setCacheSaveFlag(Boolean v) {
        // - およその意味はsmXXXに対するロック.
        // - idInfoは取得失敗の可能性がある.
        synchronized (getRepresentative()) {
            if (cacheSaveFlag.equals(v)) {
                return v;
            };
            cacheSaveFlag = v;
            for (int i = assocList.size() - 1; i >= 0; --i) {
                DomandCVIEntry x = assocList.get(i);
                x.setCacheSaveFlag(v);
            };
        };
        return v;
    };

    // - thisが役割終了している場合もfalse.
    // - completedFlagを加味することは意味論上での一貫性が怪しいから要改善.
    public synchronized Boolean getCacheSaveFlag() {
        // - audioを代表オブジェクトとしてロック.
        return !completedFlag && cacheSaveFlag;
    };

    private synchronized void mightCallAudioListeners() {
        if (audioIV == null || audioKey == null) {
            return;
        };
        for (Runnable run : gotAudioDecryptInfoListeners) {
            run.run();
        };
        gotAudioDecryptInfoListeners.clear();
    };

    private synchronized void mightCallVideoListeners() {
        if (videoIV == null || videoKey == null) {
            return;
        };
        for (Runnable run : gotVideoDecryptInfoListeners) {
            run.run();
        };
        gotVideoDecryptInfoListeners.clear();
    };

    // 登録した処理は0回or1回実行される.
    // 鍵・IVの確認と登録を同じロック下で行い、確認直後に情報が揃う競合でも
    // 復号処理を取りこぼさない。
    public synchronized void addGotAudioDecryptInfoListeners(Runnable r) {
        if (audioIV != null && audioKey != null) {
            r.run();
        } else {
            gotAudioDecryptInfoListeners.add(r);
        };
    };

    // 登録した処理は0回or1回実行される.
    public synchronized void addGotVideoDecryptInfoListeners(Runnable r) {
        if (videoIV != null && videoKey != null) {
            r.run();
        } else {
            gotVideoDecryptInfoListeners.add(r);
        };
    };

    // セグメント固有の鍵を待つ処理用。署名更新後の同名セグメントと混同しないよう、
    // ファイル名ではなくNicoCache内部パラメータを除いた要求URL単位で待機する。
    synchronized void addAudioDecryptInfoListener(String segmentUrl, Runnable r) {
        String segmentKey = urlWithoutNicoCacheParameters(segmentUrl);
        if (isAudioDecryptInfoReady(segmentKey)) {
            r.run();
            return;
        }
        gotAudioDecryptInfoListenersBySegmentUrl
            .computeIfAbsent(segmentKey, ignored -> new HashSet<>()).add(r);
    };

    synchronized void addVideoDecryptInfoListener(String segmentUrl, Runnable r) {
        String segmentKey = urlWithoutNicoCacheParameters(segmentUrl);
        if (isVideoDecryptInfoReady(segmentKey)) {
            r.run();
            return;
        }
        gotVideoDecryptInfoListenersBySegmentUrl
            .computeIfAbsent(segmentKey, ignored -> new HashSet<>()).add(r);
    };

    synchronized void setAudioDecryptInfo(
        String segmentUrl, String keyUrl, byte[] iv) {
        String segmentKey = urlWithoutNicoCacheParameters(segmentUrl);
        audioKeyUrlsBySegmentUrl.put(segmentKey, keyUrl);
        audioIVsBySegmentUrl.put(segmentKey, iv);
        mightCallAudioListeners();
        mightCallAudioSegmentListeners();
    };

    synchronized void setVideoDecryptInfo(
        String segmentUrl, String keyUrl, byte[] iv) {
        String segmentKey = urlWithoutNicoCacheParameters(segmentUrl);
        videoKeyUrlsBySegmentUrl.put(segmentKey, keyUrl);
        videoIVsBySegmentUrl.put(segmentKey, iv);
        mightCallVideoListeners();
        mightCallVideoSegmentListeners();
    };

    // プレイリストの鍵・セグメントURLには署名付きクエリを残したまま、
    // ブラウザーへ付与したNicoCache内部パラメータだけを除去して照合する。
    // パスだけに縮めると、同じ鍵パスで署名や鍵が切り替わったときに
    // 古い鍵を再利用してしまい、復号結果がBadPaddingになる。
    private static String urlWithoutNicoCacheParameters(String url) {
        if (url == null) {
            return null;
        };

        int question = url.indexOf("?");
        if (question < 0) {
            return url;
        };

        String query = url.substring(question + 1);
        StringBuilder keptQuery = new StringBuilder(query.length());
        for (String parameter : query.split("&", -1)) {
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            if (name.startsWith("nicocachenl_")) {
                continue;
            };
            if (keptQuery.length() > 0) {
                keptQuery.append('&');
            };
            keptQuery.append(parameter);
        };

        if (keptQuery.length() == 0) {
            return url.substring(0, question);
        };
        return url.substring(0, question + 1) + keptQuery;
    };

    private static void putKey(
        Map<String, byte[]> keysByUrl, String keyUrl, byte[] key) {
        keysByUrl.put(keyUrl, key);
        keysByUrl.put(urlWithoutNicoCacheParameters(keyUrl), key);
    };

    private static byte[] findKey(
        Map<String, byte[]> keysByUrl, String keyUrl) {
        byte[] key = keysByUrl.get(keyUrl);
        if (key != null) {
            return key;
        };
        return keysByUrl.get(urlWithoutNicoCacheParameters(keyUrl));
    };

    private boolean isAudioDecryptInfoReady(String segmentKey) {
        String keyUrl = audioKeyUrlsBySegmentUrl.get(segmentKey);
        return audioIVsBySegmentUrl.get(segmentKey) != null
            && keyUrl != null && findKey(audioKeysByUrl, keyUrl) != null;
    };

    private boolean isVideoDecryptInfoReady(String segmentKey) {
        String keyUrl = videoKeyUrlsBySegmentUrl.get(segmentKey);
        return videoIVsBySegmentUrl.get(segmentKey) != null
            && keyUrl != null && findKey(videoKeysByUrl, keyUrl) != null;
    };

    synchronized boolean hasAudioDecryptInfo(String segmentUrl) {
        String segmentKey = urlWithoutNicoCacheParameters(segmentUrl);
        return audioKeyUrlsBySegmentUrl.containsKey(segmentKey)
            && audioIVsBySegmentUrl.containsKey(segmentKey);
    };

    synchronized boolean hasVideoDecryptInfo(String segmentUrl) {
        String segmentKey = urlWithoutNicoCacheParameters(segmentUrl);
        return videoKeyUrlsBySegmentUrl.containsKey(segmentKey)
            && videoIVsBySegmentUrl.containsKey(segmentKey);
    };

    private void mightCallAudioSegmentListeners() {
        List<String> ready = new ArrayList<>();
        for (String segmentKey : gotAudioDecryptInfoListenersBySegmentUrl.keySet()) {
            if (isAudioDecryptInfoReady(segmentKey)) {
                ready.add(segmentKey);
            }
        }
        for (String segmentKey : ready) {
            Set<Runnable> listeners =
                gotAudioDecryptInfoListenersBySegmentUrl.remove(segmentKey);
            for (Runnable run : listeners) {
                run.run();
            }
        }
    };

    private void mightCallVideoSegmentListeners() {
        List<String> ready = new ArrayList<>();
        for (String segmentKey : gotVideoDecryptInfoListenersBySegmentUrl.keySet()) {
            if (isVideoDecryptInfoReady(segmentKey)) {
                ready.add(segmentKey);
            }
        }
        for (String segmentKey : ready) {
            Set<Runnable> listeners =
                gotVideoDecryptInfoListenersBySegmentUrl.remove(segmentKey);
            for (Runnable run : listeners) {
                run.run();
            }
        }
    };

    synchronized void setAudioKeyForUrl(String keyUrl, byte[] key) {
        putKey(audioKeysByUrl, keyUrl, key);
        audioKey = key;
        mightCallAudioListeners();
        mightCallAudioSegmentListeners();
    };

    synchronized void setVideoKeyForUrl(String keyUrl, byte[] key) {
        putKey(videoKeysByUrl, keyUrl, key);
        videoKey = key;
        mightCallVideoListeners();
        mightCallVideoSegmentListeners();
    };

    public synchronized void setAudioIV(byte[] v) {
        audioIV = v;
        mightCallAudioListeners();
    };

    public synchronized void setVideoIV(byte[] v) {
        videoIV = v;
        mightCallVideoListeners();
    };

    public synchronized void setAudioKey(byte[] v) {
        audioKey = v;
        mightCallAudioListeners();
    };

    public synchronized void setVideoKey(byte[] v) {
        videoKey = v;
        mightCallVideoListeners();
    };

    synchronized byte[] getAudioIV(String segmentUrl) {
        return audioIVsBySegmentUrl.get(urlWithoutNicoCacheParameters(segmentUrl));
    };

    synchronized byte[] getVideoIV(String segmentUrl) {
        return videoIVsBySegmentUrl.get(urlWithoutNicoCacheParameters(segmentUrl));
    };

    synchronized byte[] getAudioKey(String segmentUrl) {
        String keyUrl = audioKeyUrlsBySegmentUrl.get(
            urlWithoutNicoCacheParameters(segmentUrl));
        return keyUrl == null ? null : findKey(audioKeysByUrl, keyUrl);
    };

    synchronized byte[] getVideoKey(String segmentUrl) {
        String keyUrl = videoKeyUrlsBySegmentUrl.get(
            urlWithoutNicoCacheParameters(segmentUrl));
        return keyUrl == null ? null : findKey(videoKeysByUrl, keyUrl);
    };

    public synchronized byte[] getAudioIV() {
        return audioIV;
    };

    public synchronized byte[] getVideoIV() {
        return videoIV;
    };

    public synchronized byte[] getAudioKey() {
        return audioKey;
    };

    public synchronized byte[] getVideoKey() {
        return videoKey;
    };

    public synchronized boolean getCompletedFlag() {
        return completedFlag;
    };

    public synchronized CacheManager.HlsTmpSegments getHlsTmpSegments() {
        return hlsTmpSegments;
    };

    public synchronized void setHlsTmpSegments(CacheManager.HlsTmpSegments v) {
        hlsTmpSegments = v;
    };

    // - 判定方法がオブジェクト利用側を信頼しすぎている. 暗黙的すぎる.
    public boolean isAudioHandling() {
        return videoSrcId == null;
    };

    // - あるキャッシュセグメントの保存が終ったことをthisに伝える.
    // - 必要ならキャッシュコンプリート処理する.
    public void addCachedSegment(String filenameRel) {
        synchronized (this) {
            CacheManager.HlsTmpSegments x = getHlsTmpSegments();
            if (x == null) {
                // ここでmaster.m3u8からファイルを辿り必要なファイル一覧を算出する.
                x = CacheManager.HlsTmpSegments.get(getVideoDescriptor());
                if (x == null) {
                    Logger.warning("(dcvie)failed to initialize hlsTmpSegments: "
                                   + getVideoDescriptor().toString());
                    setCacheSaveFlag(false);
                    return;
                };
                setHlsTmpSegments(x);
            };
            x.addCachedSegment(filenameRel);
        };

        if (isSegmentsComplete()) {
            onSegmentsComplete();
        };
    };

    public synchronized boolean isSegmentsComplete() {
        if (hlsTmpSegments == null) {
            return false;
        };
        return hlsTmpSegments.isKnownSegmentsComplete();
    };

    // - thisが扱っているsub playlist下のsegment(chunk)が揃った時に呼び出される.
    // - thisはvideo or audioを扱っている.
    // unsynchronized.
    private void onSegmentsComplete() {
        // 異なる品質間でもキャッシュ移動・完成通知が同じ共有インデックスを
        // 更新するため、正しさを優先して共通のファイル移動ロックを使う。
        synchronized (Cache.getFileMoveLock()) {
            // true,falseの意味はコメント参照のこと.
            for (DomandCVIEntry audio_or_video : assocList) {
                audio_or_video.mightVideoSegmentsComplete(false);
            };
            mightVideoSegmentsComplete(true);
            mightAudioSegmentsComplete();
        };
    };

    String beforeSegsState = "";
    // - video-segmentsはcompleteしたかもという宣言的な名前.
    // - thisがaudioを扱っているなら何もしない.
    // - 必要に応じて前処理とcache complete処理をする.
    // - 引数がtrueならば既にthisのsegmentsは揃った状態. falseなら揃っているかチェックする.
    // - スレッドセーフ性は呼出側が確保する.
    private void mightVideoSegmentsComplete(boolean segmentsComplete) {

        if (isAudioHandling()) {
            return;
        };

        if (!getCacheSaveFlag()) {
            return;
        };

        if (!segmentsComplete) {
            if (!isSegmentsComplete()) {
                return;
            };
        };

        // - --debug.
        if (segmentsComplete) {
            beforeSegsState = hlsTmpSegments.debugFormat();
        };

        // - この時点で
        // - thisはvideoを扱っている.
        // - thisのsegmentsは揃っている.

        if (moveAnyAudioToMeFromAudioNltmp()) {
            cacheStore("move audio way");
            return;
        };
        if (copyAnyAudioToMeFromAnotherCache()) {
            cacheStore("copy audio way");
            return;
        };
    };

    private boolean moveAnyAudioToMeFromAudioNltmp() {
        for (DomandCVIEntry audio : getAudioDomandCVIEntries()) {
            if (moveAudioToMeFromAudioNltmp(audio)) {
                return true;
            };
        };
        return false;
    };

    // - 失敗でfalse.
    // - キャッシュ中であるnltmp_smX[0p,XXX]_title.hlsからaudioデータを自キャッシュへ
    //   移動する.
    // unsynchronized.
    private boolean moveAudioToMeFromAudioNltmp(DomandCVIEntry audioMovInfo) {
        boolean success = true;
        // synchronized (audioMovInfo.getVideoDescriptor()) {
        // 前提: すでにFileMoveLock取得済み
        if (audioMovInfo.completedFlag) {
            // - audioMovInfoは使い終えた後の状態.
            // - もうフォルダ内にファイルは残っていない.
            return false;
        };
        if (!audioMovInfo.isSegmentsComplete()) {
            // - オーディオセグメントは揃っていないから待たなければならない.
            return false;
        };

        Path src = audioMovInfo.getCache().getCacheTmpPath();
        Path dest = getCache().getCacheTmpPath();

        if (src == null || dest == null) {
            // - 2024-09-08: 起きる.
            // - 2025-04-03: src != null && dest==null
            //               起きるが別口でcache store正常成功する.
            Logger.info("(dcvie|moveaudio)programming error(1): "
                        + getVideoDescriptor() + " <- "
                        + audioMovInfo.getVideoDescriptor()
                        + ":"
                        + " dest[" + (dest==null?"null":"obj") + "]"
                        + " src[" + (src==null?"null":"obj") + "]");
            return false;
        };

        if (!overwriteMoveFiles_withErrorHandling(
                "(dcvie|moveaudio)",
                "failed to move audio dir: ",
                "programming error2: ",
                src.resolve("audio.m3u8"), dest.resolve("audio.m3u8"))) {
            success = false;
        };
        if (!overwriteMoveFiles_withErrorHandling(
                "(dcvie|moveaudio)",
                "failed to move audio dir: ",
                "programming error3: ",
                src.resolve("audio"), dest.resolve("audio"))) {
            success = false;
        };

        try {
            audioMovInfo.getCache().deleteTmp();
        }
        catch (IOException e) {
            Logger.info("failed to delete: "
                        + audioMovInfo.videoDescriptor.toString());
        };
        audioMovInfo.completedFlag = true;

        if (success) {
            this.audioKbps = audioMovInfo.audioKbps;
            this.audioCodec = audioMovInfo.audioCodec;
        };
        return success;
    };

    // - 前提: すでにFileMoveLock取得済み.
    private boolean copyAnyAudioToMeFromAnotherCache() {
        Set<VideoDescriptor> allvds = CacheManager.getVideos(getSmid());
        Set<Integer> audioBitrates = new HashSet<>(allvds.size());

        for (VideoDescriptor vd : allvds) {
            audioBitrates.add(vd.getAudioBitrate());
        };
        // - audio bitrateが高い方から順にコピーを試みる.
        List<Integer> sorted = new ArrayList<>(audioBitrates);
        Collections.sort(sorted, Collections.reverseOrder());
        for (Integer selAudioKbpsInt : sorted) {
            int selAudioKbps = (int)selAudioKbpsInt;
            for (VideoDescriptor vd : allvds) {
                if (selAudioKbps == vd.getAudioBitrate()) {
                    if (copyAudioToMeFromAnotherCache(vd)) {
                        return true;
                    };
                };
            };
        };
        return false;
    };

    // - スレッドセーフ性は呼出側が確保する.
    // unsynchronized.
    private boolean copyAudioToMeFromAnotherCache(VideoDescriptor audioVD) {

        if (!Cache.HLS.equals(audioVD.getPostfix())) {
            return false;
        };
        Cache audioCache = new Cache(audioVD);
        Path src = audioCache.getCachePath();
        Path dest = getCache().getCacheTmpPath();

        if (src == null) {
            return false;
        };

        if (dest == null) {
            // 2024-09-08起きる.
            Logger.info("(dcvie|copyaudio)programming error(1): "
                        + audioVD + " <- " + getVideoDescriptor()
                        + ": src=" + src + ": dest=" + dest);
            return false;
        };

        // - 今はcodecチェックしない.

        // - 2025-04-03: この過剰なチェックは本来必要ないがバグ対応のためにしばらくこう.
        if (!Files.isRegularFile(src.resolve("audio.m3u8"))) {
            return false;
        };
        if (Files.isRegularFile(src.resolve("audio").resolve("init1.cmfa")) ||
            Files.isRegularFile(src.resolve("audio").resolve("init01.cmfa")) ||
            Files.isRegularFile(src.resolve("audio").resolve("init001.cmfa")) ||
            Files.isRegularFile(src.resolve("audio").resolve("init0001.cmfa")) ||
            Files.isRegularFile(src.resolve("audio").resolve("init00001.cmfa"))) {
            // do nothing.
        } else {
            return false;
        };

        if (!copyFolder_withErrorHandling(
                "(dcvie|copyaudio)",
                "failed to copy audio data(1): ",
                "programming error(2):",
                src.resolve("audio.m3u8"),
                dest.resolve("audio.m3u8"),
                StandardCopyOption.REPLACE_EXISTING)) {
            return false;
        };
        if (!copyFolder_withErrorHandling(
                "(dcvie|copyaudio)",
                "failed to copy audio data(2): ",
                "programming error(3):",
                src.resolve("audio"),
                dest.resolve("audio"),
                StandardCopyOption.REPLACE_EXISTING)) {
            return false;
        };

        this.audioKbps = audioVD.getAudioBitrate();
        this.audioCodec = M3u8Util.getCodecsFromFile(src.resolve("master.m3u8"))[1];

        if (this.audioCodec == null) {
            Logger.info(audioVD.getType() + audioVD.getId()
                        + "既存音声のコーデック取得失敗. 代替を使用.");
            this.audioCodec = "mp4a.40.2"; // 2025年時点一般的.
        };
        return true;
    };

    /**
     * 成功時true.
     */
    private boolean overwriteMoveFiles_withErrorHandling
    (String errorMessagePrefix,
     String commonErrorMessagePart,
     String rareErrorMessagePart,
     Path source, Path target, CopyOption... options) {
        try {
            overwriteMoveFiles(source, target, options);
        }
        catch (AtomicMoveNotSupportedException
               | DirectoryNotEmptyException
               | FileAlreadyExistsException
               | UnsupportedOperationException e) {
            Logger.info(errorMessagePrefix +
                        rareErrorMessagePart +
                        e.getClass() + ": " +
                        target + " <- " + source);
            return false;
        }
        catch (IOException e) {
            Logger.info(errorMessagePrefix +
                         commonErrorMessagePart +
                         target + " <- " + source);
            return false;
        };
        return true;
    };

    // ユーティリティー関数. 適切な場所へ移すこと.
    // - フォルダ階層をコピーする.
    // - 指定パスはファイルでもいい.
    // https://stackoverflow.com/a/50418060
    public static void copyFolder(Path source, Path target, CopyOption... options)
        throws IOException {

        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString())
                           , options);
                return FileVisitResult.CONTINUE;
            }
        });
    };

    /**
     * 成功時true.
     */
    private static boolean copyFolder_withErrorHandling
    (String errorMessagePrefix,
     String commonErrorMessagePart,
     String rareErrorMessagePart,
     Path source, Path target, CopyOption... options) {
        try {
            copyFolder(source, target, options);
        }
        catch (AtomicMoveNotSupportedException
               | DirectoryNotEmptyException
               | FileAlreadyExistsException
               | UnsupportedOperationException e) {
            Logger.info(errorMessagePrefix +
                        rareErrorMessagePart +
                        e.getClass() + ": " +
                        target + " <- " + source);
            return false;
        }
        catch (IOException e) {
            Logger.info(errorMessagePrefix +
                         commonErrorMessagePart +
                         target + " <- " + source);
            return false;
        };
        return true;
    };

    // - ユーティリティー関数.
    // - ディレクトリ構造も上書き移動する.
    // - 双方に同名ディレクトリがある場合はsource側の属性値が維持される.
    // - CopyOptionには自動的にREPLACE_EXISTINGが追加されます.
    static public void overwriteMoveFiles
    (Path source, Path target, CopyOption... argOptions)
        throws IOException {
        CopyOption[] workOptions = null;
        for (CopyOption x : argOptions) {
            if (x == StandardCopyOption.REPLACE_EXISTING) {
                workOptions = argOptions;
                break;
            };
        };
        if (workOptions == null) {
            // optionsの末尾にREPLACE_EXISTINGを追加する.
            int aolen = argOptions.length;
            workOptions = new CopyOption[aolen + 1];
            workOptions[aolen] = StandardCopyOption.REPLACE_EXISTING;
            for (int i=argOptions.length - 1; 0 <= i; --i) {
                workOptions[i] = argOptions[i];
            };
        };
        final CopyOption[] options = workOptions;

        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e)
                throws IOException {
                Files.deleteIfExists(source.resolve(target.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.move(file, target.resolve(source.relativize(file).toString())
                           , options);
                return FileVisitResult.CONTINUE;
            }
        });
    };

    private boolean workaroundNeedToRestart(String way) {
        // - ソースコードが正常ならば本来このチェックは不要だが2024-11-20時点通る
        //   ことがあるためしばらく残す.
        // - 2024-12-02 ここを通った時点で無限ループが確定していないか？
        // - 2025-04-03 起きる.
        //   videoを扱っているはずがaudioチャンクを入れていることがある.
        CacheManager.HlsTmpSegments.forget(getVideoDescriptor());
        CacheManager.HlsTmpSegments x =
            CacheManager.HlsTmpSegments.get(getVideoDescriptor());
        if (!x.allFilesExist(this.cache.getCacheTmpFile())) {
            Logger.info("(dcvie|cache store)segment missing["
                        + way + "] " + this.cache.getCacheFileName()
                        + " key[" + getKey() + "] v[" + videoSrcId + "]"
                        + " a[" + audioSrcId + "]");
            Logger.info("---bef---\n" + beforeSegsState + "\n\n"
                        + "---aft---\n" + x.debugFormat());
            try {
                Runtime.getRuntime().exec(new String[]{
                        "bash", "-c", "echo [-----] >> /utmp/logn"}).waitFor();
                Runtime.getRuntime().exec(new String[]{
                        "bash", "-c", "find '" + this.cache.getCacheTmpFile() + "'"
                        + " -type f  >> /utmp/logn"}).waitFor();
            } catch (Exception e) {};

            hlsTmpSegments = x;
            return true;
        };
        return false;
    };

    // - video segmentsを扱っているthisのcache complete処理.
    // - スレッドセーフ性は呼出側が確保する.
    // unsynchronized.
    private boolean cacheStore(String way) {
        if (workaroundNeedToRestart(way)) {
            // - メッセージは上記関数内で行う.
            // - 元々ここではrestartをしていたがそれは解決にならないためやめる.
            return false;
        };

        // - audioとvideoの両方を配置する先を作る.
        Cache finalCache = createCacheFileHandler(
            getSmid(), getVideoNumId(), getVideoMode(), getAudioKbps());

        Path src = getCache().getCacheTmpFile().toPath();
        Path dest = finalCache.getCacheTmpFileWithSet().toPath();
        if (!overwriteMoveFiles_withErrorHandling(
                "(dcvie|vcachestore)",
                "failed to move nltmp dir: ",
                "programming error1: ",
                src,
                dest)) {
            return false;
        };
        if (!deleteTmp_withErrorHandling(
                "(dcvie|vcachestore)failed to remove nltmp: ",
                getCache())) {
            return false;
        };

        byte[] masterm3u8 = M3u8Util.buildMasterM3u8ForSaving(audioCodec, videoCodec);
        writeM3u8("master.m3u8", masterm3u8, dest.toFile());

        try {
            // Logger.info("--[v]cache store " + getSmid());
            finalCache.store();
        } catch (IOException e) {
            Logger.debugWithThread(e);
        };

        // [nl] 存在チェックと完了時のイベント通知.
        if (finalCache.exists()) {
            this.completedFlag = true;
        }
        else {
            Logger.info("completion failed(v): " + finalCache.getCacheFileName());
            return false;
        };

        for (CompleteCache entry : NLShared.INSTANCE.getCompleteEntries()) {
            try {
                if (entry.onComplete(finalCache)) {
                    break; // trueが返ってきた時点キャッシュは移動した.
                };
            } catch (Throwable t) {
                Logger.error(t); // エラーは無視して続行
            };
        };
        if (NLShared.INSTANCE.countSystemEventListeners() > 0) {
            NLShared.INSTANCE.notifySystemEvent(
                SystemEventListener.CACHE_COMPLETED
                , new NLEventSource(null, /*requestHeader*/null, finalCache)
                , false);
        };

        Logger.info("cache completed: " + finalCache.getCacheFileName());
        return true;
    };

    public Cache createCacheFileHandler() {
        return createCacheFileHandler(
            getSmid(), getVideoNumId(), getVideoMode(), getAudioKbps());
    };

    // - 指定のCacheインスタンスを作る.
    public static Cache createCacheFileHandler
    (String smid, String videoNumId, String videoMode, int audioKbps) {
        NicoIdInfoCache.Entry idInfo = NicoIdInfoCache.getInstance().get(videoNumId);
        boolean lowAudio = false;
        boolean lowVideo = false;
        if (idInfo != null) {
            {
                Boolean a = idInfo.estimateDmcAudioEconomyFromKbps(audioKbps);
                if (a == null) {
                    Logger.info(smid + ": unknown audio kbps " + audioKbps
                                + ", treated as low.");
                    a = false;
                };
                lowAudio = a;
            };
            {
                Boolean v = idInfo.estimateDmcVideoEconomyFromVideoMode(videoMode);
                if (v == null) {
                    Logger.info(smid + ": unknown video mode " + videoMode
                                + ", treated as low.");
                    v = false;
                };
                lowVideo = v;
            };
        };
        boolean lowAccess = lowAudio || lowVideo;
        VideoDescriptor finalvd = VideoDescriptor.newDmc(
            smid, Cache.HLS, lowAccess, videoMode, 0, audioKbps, "");
        VideoDescriptor regvd = Cache.getRegisteredVideoDescriptor(finalvd);
        if (regvd != null) {
            finalvd = regvd;
        };

        if (idInfo == null) {
            return new Cache(finalvd);
        };
        return new Cache(finalvd, idInfo.getTitle());
    };

    // - audio-segmentsはcompleteしたかもという宣言的な名前.
    // - thisがvideoを扱っているなら何もしない.
    // - thisのsegmentsは揃っている前提.
    // - 必要に応じて前処理とcache complete処理をする.
    // - 前提: FileMoveLock取得済み.
    private void mightAudioSegmentsComplete() {
        if (!isAudioHandling()) {
            return;
        };
        if (!getCacheSaveFlag()) {
            return;
        };

        for (VideoDescriptor x : CacheManager.getVideos(getSmid())) {
            if (!getPostfix().equals(x.getPostfix())) {
                continue;
            };
            if ("0p".equals(x.getVideoMode())) {
                // 一応テストするが無映像がcomplete状態であることはない.
                continue;
            };
            if (0 == x.getAudioBitrate()) {
                // ほぼ同上同様.
                continue;
            };
            if (!CacheManager.completedCacheExsists(x)) {
                continue;
            };
            // - thisのaudioとxのvideoを入れるための新たなCache.
            Cache dest = createCacheFileHandler(
                getSmid(), getVideoNumId(), x.getVideoMode(), getAudioKbps());
            Cache videosrc = new Cache(x);

            Path destpath = dest.getCacheTmpFileWithSet().toPath();

            if (!destpath.toFile().mkdir() && !Files.isDirectory(destpath)) {
                Logger.info("failed to mkdir: " + destpath);
                continue;
            };

            if (!copyVideoData_withErrorHandling(
                    "(dcvie|audiostore)",
                    "failed to copy video data: ",
                    "programming error(1): ",
                    videosrc.getCachePath(),
                    destpath)) {
                deleteTmp_withErrorHandling(
                    "(dcvie|audiostore)failed to remove nltmp: ",
                    dest);
                continue;
            };
            if (!copyAudioData_withErrorHandling(
                    "(dcvie|audiostore)",
                    "failed to copy audio data: ",
                    "programming error(1): ",
                    getCache().getCacheTmpPath(),
                    destpath)) {
                deleteTmp_withErrorHandling(
                    "(dcvie|audiostore)failed to remove nltmp(1): ",
                    dest);
                continue;
            };
            String videoCodec = M3u8Util.getCodecsFromFile(
                videosrc.getCachePath().resolve("master.m3u8"))[0];
            String audioCodec = getAudioCodec();
            if (this.videoCodec == null) {
                Logger.info(getSmid()
                            + ": 既存映像のコーデック取得失敗. 代替を使用.");
                this.audioCodec = "avc1.4d4020"; // 2025年時点一般的.
            };

            byte[] masterm3u8 = M3u8Util.buildMasterM3u8ForSaving(audioCodec, videoCodec);
            writeM3u8("master.m3u8", masterm3u8, /*dir*/destpath.toFile());

            try {
                // Logger.info("--[a]cache store " + getSmid());
                dest.store();
            } catch (IOException e) {
                Logger.debugWithThread(e);
            };

            if (dest.exists()) {
                this.completedFlag = true;
                // store成功したからsource(videoDescriptorがdestとは異なる)を消す.
                deleteTmp_withErrorHandling(
                    "(dcvie|audiostore)failed to remove nltmp(2): ",
                    getCache());
            } else {
                Logger.info("completion failed(a): " + dest.getCacheFileName());
                deleteTmp_withErrorHandling(
                    "(dcvie|audiostore)failed to remove nltmp(3): ",
                    dest);
                continue;
            };

            for (CompleteCache entry : NLShared.INSTANCE.getCompleteEntries()) {
                try {
                    if (entry.onComplete(dest)) {
                        break; // trueが返ってきた時点でキャッシュは移動している.
                    };
                } catch (Throwable t) {
                    Logger.error(t); // エラーは無視して続行
                };
            };
            if (NLShared.INSTANCE.countSystemEventListeners() > 0) {
                NLShared.INSTANCE.notifySystemEvent(
                    SystemEventListener.CACHE_COMPLETED
                    , new NLEventSource(null, /*requestHeader*/null, dest)
                    , false);
            };

            Logger.info("cache completed: " + dest.getCacheFileName());
            return;
        };
        return;
    };

    private static boolean copyVideoData_withErrorHandling
    (String errorMessagePrefix,
     String commonErrorMessagePart,
     String rareErrorMessagePart,
     Path source, Path target) {
        String[] subpathlist = {"video", "video.m3u8", "master.m3u8"};
        for (String x : subpathlist) {
            if (!copyFolder_withErrorHandling(
                    errorMessagePrefix,
                    commonErrorMessagePart,
                    rareErrorMessagePart,
                    source.resolve(x),
                    target.resolve(x),
                    StandardCopyOption.REPLACE_EXISTING)) {
                return false;
            };
        };
        return true;
    };
    private static boolean copyAudioData_withErrorHandling
    (String errorMessagePrefix,
     String commonErrorMessagePart,
     String rareErrorMessagePart,
     Path source, Path target) {
        String[] subpathlist = {"audio", "audio.m3u8", "master.m3u8"};
        for (String x : subpathlist) {
            if (!copyFolder_withErrorHandling(
                    errorMessagePrefix,
                    commonErrorMessagePart,
                    rareErrorMessagePart,
                    source.resolve(x),
                    target.resolve(x),
                    StandardCopyOption.REPLACE_EXISTING)) {
                return false;
            };
        };
        return true;
    };

    private static boolean deleteTmp_withErrorHandling
    (String errorMessagePrefix, Cache cache) {
        try {
            cache.deleteTmp();
        }
        catch (IOException x) {
            Logger.info(errorMessagePrefix + x + ": " + cache.getVideoDescriptor());
            return false;
        };
        return true;
    };

    // - mightはcacheSaveFlagがtrueかつnltmpCreatedがtrueであればキャッシュファイルとして
    //   書き込むという意味.
    // - 条件が揃っていない場合は溜め込まれ、chunkLoadStart()で書き込まれる.
    public synchronized void mightWriteMasterM3u8(byte[] content) {
        masterM3u8 = content;
        mightWrite();
    };
    public synchronized void mightWriteVideoM3u8(byte[] content) {
        videoM3u8 = content;
        mightWrite();
    };
    public synchronized void mightWriteAudioM3u8(byte[] content) {
        audioM3u8 = content;
        mightWrite();
    };
    // どのチャンクであれ動画チャンク・音声チャンクへの要求をキャッチした時に呼び出す.
    public synchronized void chunkLoadStart() {
        updatedOn = System.currentTimeMillis();

        if (!chunkLoadStartedFlag) {
            chunkLoadStartedFlag = true;

            // - 元々はplaylist urlをハンドルする部分で出していたメッセージ.
            // - その元の方法では検出した全てのplaylistで表示されたため鬱陶しかった.
            //   (audio, 1080p, 720p, 480p, 360p, 144p, 低画質それぞれに対して表示が
            //    出てしまう).
            // - チャンクを読み込み初めてから出すようにしたのがこれ.
            String audioVideo = isAudioHandling() ? "audio" : "video";
            Logger.info("(dcvie)no cache found(" + audioVideo + "): "
                        + getCache().getCacheFileName());

            // - 複数回呼び出しても無害だけど無駄だからフラグが変わる時1回だけ呼び出す.
            // - この時点でmasterM3u8==nullである場合は存在しない.
            mightWrite();
        };
    };

    private void mightWrite() {
        if (!getCacheSaveFlag()) {
            return;
        };
        if (!chunkLoadStartedFlag) {
            return;
        };
        if (masterM3u8 != null) {
            writeM3u8("master.m3u8", masterM3u8);
            masterM3u8 = null;
        };
        if (videoM3u8 != null) {
            writeM3u8("video.m3u8", videoM3u8);
            videoM3u8 = null;
        };
        if (audioM3u8 != null) {
            writeM3u8("audio.m3u8", audioM3u8);
            audioM3u8 = null;
        };
    };

    private void writeM3u8(String filename, byte[] content) {
        writeM3u8(filename, content, null);
    };
    private void writeM3u8(String filename, byte[] content, File dir) {
        try {
            // - cacheが設定済みでなければ、ここに来ないことを前提にしている.
            // - nltmp_smXXX[yyy,zzz]_title.hls作成.
            // - cache.getCacheTmpFile()はこれ以降から非null.
            if (dir == null) {
                dir = cache.prepareTmpHlsDirectory();
            };
            File playlist = new File(dir, filename);

            if (playlist.exists()) {
                // 既に存在するなら内容比較.
                byte[] prevContent = new byte[(int) playlist.length()];
                try (FileInputStream stream = new FileInputStream(playlist)) {
                    stream.read(prevContent);
                };
                if (!Arrays.equals(content, prevContent)) {
                    // - 可能性は:
                    //   - NicoCacheが知らない理由でファイルが書き換えられた.
                    //   - 「この動画は投稿者によって修正されました」.
                    //   - NicoCacheのプレイリスト加工アルゴリズムが変わった.
                    Logger.info("Playlist mismatch: " + playlist.getPath());
                    deleteContainedFilesWithoutM3u8(dir); // 動画・音声チャンク削除.
                };
            };

            // 改行コードをLFにするためバイナリIO
            try (FileOutputStream fos = new FileOutputStream(playlist, false)) {
                fos.write(content);
            }
            catch (IOException e) {
                Logger.info("Failed to write list: " + playlist.getPath());
            };
        }
        catch (IOException e) {
            // - prepareTmpHlsDirectoryが失敗した.
            // - 異常状態だからキャッシュ抑制.
            Logger.info("Failed to create nltmp dir: " + getSmid());
            setCacheSaveFlag(false);
        };
    };

    private static void deleteContainedFilesWithoutM3u8(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        };
        for (File file : files) {
            if (!file.getName().endsWith(".m3u8")) {
                file.delete();
            };
        };
    };

    /**
     * - キャッシュファイルの映像品質部の表現にする. (smXXX[YYY,ZZZ]のYYY).
     * - 不正形式の時はnull.
     * - "video-h264-1080p" -> "1080p"
     * - "video-h264-360p-lowest" -> "360p-lowest"
     */
    public static String videoSrcIdToCacheQualityExpression(String videoSrcId) {
        if (videoSrcId == null) {
            return null;
        };
        String s = videoSrcId;
        int p1 = s.indexOf('-');
        if (p1 < 0) {
            return null;
        };
        int p2 = s.indexOf('-', p1+1);
        if (p2 < 0) {
            return null;
        };
        return s.substring(p2+1);
    };
    /**
     * - キャッシュファイルの音声品質部の表現にする. (smXXX[YYY,ZZZ]のZZZ).
     * - 不正形式の時は-1.
     * - "audio-aac-128kbps" -> "128"
     * - "audio-aac-576kbps-hr" -> "576"
     */
    public static int audioSrcIdToCacheQualityExpression(String audioSrcId) {
        if (audioSrcId == null) {
            return -1;
        };
        String s = audioSrcId;
        int p1 = s.indexOf('-');
        if (p1 < 0) {
            return -1;
        };
        int p2 = s.indexOf('-', p1+1);
        if (p2 < 0) {
            return -1;
        };
        int p3 = s.indexOf('k', p2 + 1);
        if (p3 < 0) {
            return -1;
        };
        try {
            return Integer.parseInt(s.substring(p2+1, p3));
        } catch (NumberFormatException e) {
            // do nothing.
        };
        return -1;
    };
};
