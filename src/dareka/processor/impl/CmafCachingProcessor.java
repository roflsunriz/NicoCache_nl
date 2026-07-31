package dareka.processor.impl;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import dareka.common.CloseUtil;
import dareka.common.LRUMap;
import dareka.common.Logger;
import dareka.common.M3u8Util;
import dareka.common.Pair;
import dareka.common.TextUtil;
import dareka.extensions.SystemEventListener;
import dareka.processor.FetchUtil;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.TransferListener;
import dareka.processor.URLResource;


// - 2023年下旬に導入されたAWSから配信されるCommon Media Application Format形式.
//   2024年2月20日から本格稼動した. DMC-HLSの次の配信方式. .
//   開発部の名前でDMS、通信サブドメインでDomandとも呼ぶ.
//   (DMC(DWANGO Media Cluster), DMS(DWANGO Media Services))
// - この配信は配信毎に渡される復号鍵で復号しないとデータを取り出せないため、暗号化された
//   状態のデータを保存しても不整合が発生する.
//   完全なキャッシュがある場合にのみプレイリスト自体を乗っ取り、キャッシュを利用する.
// - note: 上記のように書いたが毎回同じ復号鍵が渡される例しか見たことがない(2025-02).
// - DMC-HLSと同じくwatchページに画質一覧情報がある. それらはWatchVars.javaが処理する.
// - v2024-10-04まではsmXXXとリソースURLとの対応を得るために、javascriptで通信関数を
//   adviceしてsmXXXをブラウザからnicocache側に伝える処理をしていた.
//   nvapiを使う方法に変更したためこのjsは廃止した.
// - URLにNicoCache_nl用のクエリーパラメーターである"nicocachenl_"から始まるものがある.
//   このProcessorはその仕様を使う. URLからこのパラメーターを除去したり、setParameterする
//   処理はRewriterProcessor.javaが行なっている.
// - キャッシュ保存時はニコ動サーバーの各種URLを使うが、キャッシュ利用時に
//   MasterPlaylist以外ではNicocacheローカルなURLに置き換えてクライアントへ渡す.
//   この設計の目的はDMC-HLSキャッシュをCmafCachingProcessorが利用出来るようにす
//   るため. (キャッシュディレクトリにmaster.m3u8を持ってさえいれば、兄弟ファイ
//   ルでも子孫ファイルでも任意の構造を扱えるようにするため)
// - 2025-02-27: anime.nicovideo.jpをembedで参照する時に"/hlsext/"で配信されるらしい.


//public class CmafCachingProcessor extends HlsCachingProcessor {
public class CmafCachingProcessor implements Processor {

    /**
     * "(?:arg1|arg2|arg3|...)" こういう文字列を作る.
     */
    private static String regexp_or(String... s) {
        return "(?:" + String.join("|", s) + ")";
    };
    // 子要素として動画とオーディオのm3u8を持つm3u8.
    // - 以下のgroup(1)とgroup(2)を合わせてキャッシュに必要な情報を通信毎に共有
    //   するためのキー(DomandCVI-Key, 連想配列のキーの意味)として使う.
    //   (searchに付いてくるsessionでもいいけど、path部の方が仕様変わりにくそうだから)
    // group(1): 24桁16進数. hlsbid. MongoDBのObjectIDと一致するらしい. たぶん毎回同じ.
    // group(2): 16桁16進数. m3u8の名前部分. 2024-06まではvideo mode(品質)ごとに一意の表現だった.
    private static final Pattern MASTER_PLAYLIST_URL_PATTERN = Pattern.compile(
        "^https?" + Pattern.quote("://delivery.domand.nicovideo.jp/")
        + regexp_or("hlsbid", "shlsbid", "hlsext")
        + "/([a-f0-9]+)/playlists/variants/([a-f0-9]+)" + Pattern.quote(".m3u8") + ".*");

    // masterの子であり、子要素としてvideo用key urlと無音動画チャンクurlリストを持つm3u8
    // - * dmc動画がdomandに移行し、"360p_low"(アンダースコアに続く"low")が現われるようになった場合
    //   * 、group(4)の正規表現を変更する必要がある. 今はその仕様は無いものとして扱う.
    //   * 2024-05追記. dmc動画はdmsへ移行した. "_low"は"-low"に移行したようだ. このコメントはもう
    //   * 消して良いかもしれない.
    // group(1): hlsbid.
    // group(2): video-src-id. 例: "video-h264-1080p", "video-h264-360p-lowest"
    // group(3): codec. 例: "h264",
    // group(4): 例: "1080p". "360p-lowest"
    // group(5): video height. "p"抜き. 例: "1080", "360"
    // group(6): いわゆるdmcLow. "" | "-lowest" | "-mid" | "-low"
    private static final Pattern VIDEO_PLAYLIST_URL_PATTERN = Pattern.compile(
        "https?" + Pattern.quote("://delivery.domand.nicovideo.jp/")
        + regexp_or("hlsbid", "shlsbid", "hlsext")
        + "/([a-f0-9]+)/playlists/media/(video-(\\w+)-((\\d+)p(-lowest|-mid|-low)?))" + Pattern.quote(".m3u8") + "[^\"\n]*"
        , Pattern.MULTILINE);

    // masterの子であり、子要素としてaudio用key urlと無音動画チャンクurlリストを持つm3u8
    // group(1): hlsbid.
    // group(2): audio-src-id. 例: "audio-aac-128kbps" | "audio-aac-576kbps-hr"
    // group(3): codec. 例: "aac",
    // group(4): audio kbps. "kbps"抜き. 例: "128"
    private static final Pattern AUDIO_PLAYLIST_URL_PATTERN = Pattern.compile(
        "https?" + Pattern.quote("://delivery.domand.nicovideo.jp/")
        + regexp_or("hlsbid", "shlsbid", "hlsext")
        + "/([a-f0-9]+)/playlists/media/(audio-(\\w+)-(\\d+)kbps(?:-hr)?)" + Pattern.quote(".m3u8") + "[^\"\n]*"
        , Pattern.MULTILINE);

    // group(1): "video" | "audio"
    // group(2): codec
    // group(3): video size or audio
    // group(4): "p" | "kbps"
    // group(5): "-lowest" | "-mid" | "-low" は画質
    //           "-hr" は音質につく
    private static final Pattern KEY_URL_PATTERN = Pattern.compile(
        "^https?" + Pattern.quote("://delivery.domand.nicovideo.jp/")
        + regexp_or("hlsbid", "shlsbid", "hlsext")
        + "/[a-f0-9]+/keys/(audio|video)-(\\w+)-(\\d+)(p|kbps)(-lowest|-mid|-low|-hr)?" + Pattern.quote(".key") + ".*");

    // group(1): "video" | "audio"
    // group(2): filename. 例: "init01.cmfv" | "02.cmfa"
    private static final Pattern CHUNK_URL_PATTERN = Pattern.compile(
        "^https?://(?:asset|delivery)" // sub-sub domain.
        + Pattern.quote(".domand.nicovideo.jp/") // domain.
        // 謎の定表現. 以下のように書くが"shlsbid"以外は現れないはず
        + regexp_or("hlsbid/", "shlsbid/", "hlsext/") + "?"
        + "[a-f0-9]+"
        + "(?:/segments/[a-f0-9]+)?" // "shlsbid"の場合
        + "/"
        + "(audio|video)/" // group(1)
        + "\\d+/" // "1", "12", "123"...というバリエーションのある謎の数字.
        + "(?:(?:audio|video)-[^/]*)/" // 品質を示すっぽい表現.
        + "([^?]*)[^\n]*" // group(2). ファイル名部分.
        );
    // 例: "https://delivery.domand.nicovideo.jp/shlsbid/1234567890abcdef12345678/segments/1234567890abcdef12345678/audio/1/audio-aac-192kbps/init001.cmfa?sh=azAZ09_-&session=af09&Policy=azAZ09__&Signature=azAZ09-~&Key-Pair-Id=AZ09"
    // 例: "https://asset.domand.nicovideo.jp/1234567890abcdef12345678/audio/1/audio-aac-64kbps/01.cmfa?..."

    // group(1): 動画ID. 例: "sm12345"
    private static final Pattern API_ACCESS_RIGHTS_HLS_URL_PATTERN = Pattern.compile(
        "^https?://" + Pattern.quote("nvapi.nicovideo.jp/v1/watch/")
        + "([^/?]*)" // group(1)
        + Pattern.quote("/access-rights/hls")
        + "(?:[?].*)?$"
        );
    // 例: https://nvapi.nicovideo.jp/v1/watch/sm1234/access-rights/hls?actionTrackId=1289abcxyz_1234567890123
    // - このURLの応答からマスタープレイリストが得られる.

    private final Executor executor;
    public final static ReentrantLock giantLock = new ReentrantLock();

    private static final String[] PROCESSOR_SUPPORTED_METHODS =
        new String[] { "GET", "POST" };
    private static final Pattern PROCESSOR_SUPPORTED_PATTERN = Pattern.compile(
        MASTER_PLAYLIST_URL_PATTERN.pattern()
        + "|" + VIDEO_PLAYLIST_URL_PATTERN.pattern()
        + "|" + AUDIO_PLAYLIST_URL_PATTERN.pattern()
        + "|" + KEY_URL_PATTERN.pattern()
        + "|" + CHUNK_URL_PATTERN.pattern()
        + "|" + API_ACCESS_RIGHTS_HLS_URL_PATTERN.pattern()
        );

    public CmafCachingProcessor(Executor executor) {
        this.executor = executor;
    }

    @Override
    public String[] getSupportedMethods() {
        return PROCESSOR_SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return PROCESSOR_SUPPORTED_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    static final Pattern URL_FOR_DEBUG = Pattern.compile("^(.*/)([^?/]*)([?].*)?$");
    public static String abbrurl(HttpRequestHeader requestHeader) {
        String rhash = String.format("%x", requestHeader.hashCode());
        String url = requestHeader.getURI();
        Matcher m = URL_FOR_DEBUG.matcher(url);
        if (m.matches()) {
            String o = m.group(1) + m.group(3);
            String name = m.group(2);
            // ファイル名部分/それ以外部分のハッシュ//:requestHeaderのハッシュ
            return String.format("%s/%x//:%s", name, o.hashCode(), rhash);
        };
        return rhash + "//" + url;
    };

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser)
        throws IOException {

        // - ブラウザのリクエスト1回に対して複数回これが呼び出される前提で書く.
        // - なぜ複数来るかは不明. 分かった人教えてください.
        // - return null;は他のProcessorに処理を讓ることを意味する.

        // Logger.info("-- method: " + requestHeader.getMethod());

        String uri = requestHeader.getURI();

        Matcher m;
        if ((m = MASTER_PLAYLIST_URL_PATTERN.matcher(uri)).matches()) {
            // String hlsbid = m.group(1); // 24桁16進数
            // String m3u8id = m.group(2); // 16桁16進数
            return processMasterPlaylist(requestHeader);
        };

        if ((m = API_ACCESS_RIGHTS_HLS_URL_PATTERN.matcher(uri)).matches()) {
            return processApiHls(requestHeader, browser, m.group(1));
        };

        // - DomandCVIEntryは動画キャッシュ中に必要な情報を管理するコンテナ機能
        //   と、鍵とIVが揃った時まで処理を保留させるためのもの.
        DomandCVIEntry movieInfo = getDomandCVIEntry(requestHeader);
        if (movieInfo == null) {
            return null; // 高い確率でコーディングミスが原因.
        };

        {
            AV av = AV.UNSPECIFIED;
            Matcher mv = VIDEO_PLAYLIST_URL_PATTERN.matcher(uri);
            Matcher ma = AUDIO_PLAYLIST_URL_PATTERN.matcher(uri);
            if (mv.matches()) {
                av = AV.VIDEO;
            }
            else if (ma.matches()) {
                av = AV.AUDIO;
            };
            if (av != AV.UNSPECIFIED) {
                return processSubPlaylist(
                    requestHeader, av, movieInfo);
            };
        };

        if ((m = KEY_URL_PATTERN.matcher(uri)).matches()) {
            return processKey(requestHeader, /*"video"or"audio"*/m.group(1), movieInfo);
        };

        if ((m = CHUNK_URL_PATTERN.matcher(uri)).matches()) {
            return processChunk(
                requestHeader, movieInfo, /*"audio"or"video"*/m.group(1),
                /*filename*/m.group(2));
        };

        return null;
    };

    /**
     * - マスタープレイリストとsmidとの対応を保持する.
     * - 登録してすぐに使われるし、POSTメソッドno-cacheで要求されるため登録漏れする
     *   可能性も低い.
     * - そのため小さな数字でも問題が起きる可能性は低い.
     */
    static Map<String,String> masterPlaylistToSmid =
        Collections.synchronizedMap(new LRUMap<String,String>(50));

    private Resource processApiHls
    (HttpRequestHeader requestHeader, Socket browser, String smid) {
        // 例: https://nvapi.nicovideo.jp/v1/watch/sm1234/access-rights/hls?actionTrackId=1289abcxyz_1234567890123

        Pair<URLResource, byte[]> rr;
        try {
            rr = FetchUtil.fetchBinaryContent(requestHeader, browser.getInputStream());
        } catch(IOException e) {
            Logger.info("nvapi通信エラー: " + smid);
            return null;
        };
        URLResource resource = rr.first;
        String content = new String(rr.second, StandardCharsets.UTF_8);

        // 本当はdareka.common.jsonを使ってjson解釈した方がいい.
        String t1 = "\"contentUrl\":\"";
        int p1 = content.indexOf(t1);
        if (p1 < 0) {
            // Logger.info("--ApiHls: p1 < 0: " + content);
            return resource;
        };
        int p2 = p1 + t1.length();
        int p3 = content.indexOf("\"", p2);
        if (p3 < 0) {
            // Logger.info("--ApiHls: p3 < 0: " + content);
            return resource;
        };
        String t4 = content.substring(p2, p3);
        int pq = t4.indexOf("?");
        if (pq >= 0) {
            t4 = t4.substring(0, pq);
        };

        masterPlaylistToSmid.put(t4, smid);
        // Logger.info("--mptosmid.put: " + smid + " , " + t4);

        return resource;
    };

    private Resource processKey(HttpRequestHeader requestHeader
                                , String videoOrAudio
                                , DomandCVIEntry movieInfo) {
        AV av = AV.UNSPECIFIED;
        if ("audio".equals(videoOrAudio)) {
            av = AV.AUDIO;
            // Logger.info("-- audio key: start");
        } else if ("video".equals(videoOrAudio)) {
            av = AV.VIDEO;
            // Logger.info("-- video key: start");
        } else {
            Logger.info("未知のkeyファイルです: " + movieInfo.getSmid());
            return null;
        };

        Pair<URLResource, byte[]> rr;
        try {
            rr = FetchUtil.fetchBinaryContent(requestHeader);
        } catch(IOException e) {
            Logger.info("keyファイル通信エラー: " + movieInfo.getSmid());
            // movieInfo.setCacheSaveFlag(false)しない.
            return null;
        };
        URLResource resource = rr.first;
        byte[] binContent = rr.second;

        if (binContent.length != 16) {
            // 唯一対応しているAES-128の鍵長ではない
            movieInfo.setCacheSaveFlag(false);
            Logger.info("AES-128 keyが16bytesではありません");
            return resource;
        };

        // Logger.info("-- thread[" + Thread.currentThread().getId() + "]");

        // 既に鍵をセットしていても上書きする。プレイリスト内で鍵が
        // 切り替わる場合に備え、要求URLごとの値も保持する。
        if (av.isAudio()) {
            // Logger.info("-- audio key: ok");
            movieInfo.setAudioKeyForUrl(requestHeader.getURI(), binContent);
        } else {
            // Logger.info("-- video key: ok");
            movieInfo.setVideoKeyForUrl(requestHeader.getURI(), binContent);
        };

        return resource;
    };

    private static String getUrlBaseName(String url) {
        url = removeUrlSearch(url);
        int slash = url.lastIndexOf("/");
        if (0 <= slash) {
            url = url.substring(slash + 1);
        };
        return url;
    };

    private static String removeUrlSearch(String url) {
        int question = url.indexOf("?");
        if (0 <= question) {
            return url.substring(0, question);
        };
        return url;
    };

    // - nicocachenl_domandcvikeyからkeyを得て作成済みのDomandCVIEntryを得る.
    // - 作成済みのそれがない場合はnull.
    // - エラー表示処理.
    private static DomandCVIEntry getDomandCVIEntry(
        HttpRequestHeader requestHeader) {

        String key = requestHeader.getParameter("nicocachenl_domandcvikey");

        DomandCVIEntry data = null;
        if (key != null) {
            data = NLShared.INSTANCE.getDomandCVIManager().get(key);
        };
        if (key == null || data == null) {

            if ("true".equals(
                    requestHeader.getParameter("nicocachenl_noerror"))) {

                return null;
            };

            String k = key == null ? "ng" : "ok";
            String d = data == null ? "ng" : "ok";
            String videoType = requestHeader.getParameter("nicocachenl_video_type");
            String videoNumId = requestHeader.getParameter("nicocachenl_video_id");
            String noerror = requestHeader.getParameter("nicocachenl_noerror");
            String smid = "" + videoType + videoNumId;
            String m = "";
            if (videoType == null && videoNumId == null) {
                smid = "null"; // 若干見やすく.
            };
            if (key == null && data == null && !"null".equals(smid)) {
                // URL表示が鬱陶しいので、smidが得られた場合は下のelseifに比べて簡潔に表示する.
                Logger.info("CMAF付与情報取得失敗(1): (キー:" + k + ", 値:" + d
                            + ", smid:" + smid + ", noerror:" + noerror + ") "
                            + requestHeader.getPathBasename());
            } else if (key == null && data == null) {
                // - 2025-08-20よりサムネイルプレビュー再生が実装された.
                // - それによる再生はこの分岐にくる.
                // - とりあえずinfoからdebug扱いにしておく.
                m = "(ページ更新で改善しない場合、おそらくインジェクション"
                    + "javascriptかnlFilterの構成が失敗しています) ";
                Logger.debug("CMAF付与情報取得失敗(2): (キー:" + k + ", 値:" + d
                             + ", smid:" + smid + ", noerror:" + noerror + ") " + m
                             + requestHeader.getPathWithoutQuery());
            } else if (key != null && data == null) {
                Logger.info("ページを更新してください"
                            + "(キー:ok, 値:ng, smid:" + smid +")");
            };
            return null;
        };
        return data;
    };

    // - CMAFチャンクファイルの".cmfa"と"".cmfv"を入れておく場所.
    // - nltmp_smXXX*.hls ディレクトリがまだ存在しない場合はnull.
    public static File getTmpStreamCmafavDirectory(Cache cache, String audioOrVideo) {
        File tmpCacheDir = cache.getCacheTmpFile();
        if (tmpCacheDir == null || !tmpCacheDir.exists()) {
            return null;
        };
        return new File(tmpCacheDir, audioOrVideo);
    };

    /**
     * CMAF のプレイリスト更新で HlsTmpSegments に古いセグメント名が残ると、
     * 実ファイルが揃っていても件数不一致のまま完了判定に到達しない。
     * ページ再読み込み時と同じく、ディスク上の m3u8 とチャンクから状態を作り直す。
     */
    static void rescanCachedSegments(DomandCVIEntry movieInfo) {
        CacheManager.HlsTmpSegments.forget(movieInfo.getVideoDescriptor());
        movieInfo.setHlsTmpSegments(null);
    };

    /**
     * 選択された音声を一つの映像キャッシュへ統合した後、統合先を失った別画質の
     * nltmp を回収する。これらは同じページ内では完成不能で残り続ける。
     */
    static void removeOrphanedFamilyTmp(DomandCVIEntry completedEntry) {
        for (DomandCVIEntry entry : completedEntry.getFamilyEntries()) {
            if (entry == completedEntry) {
                continue;
            };
            try {
                File tmp = entry.getCache().getCacheTmpFile();
                if (tmp != null && tmp.exists()) {
                    entry.getCache().deleteTmp();
                    Logger.info("(cmaf)removed orphaned nltmp: " + tmp.getName());
                };
            } catch (IOException e) {
                Logger.info("(cmaf)failed to remove orphaned nltmp: "
                            + entry.getCache().getCacheFileName());
            };
        };
    };

    private static VideoDescriptor superiorIncompatibleCache(DomandCVIEntry data) {
        String smid = data.getSmid();
        // swf,flv,mp4のキャッシュを持っている場合HLSをキャッシュしない
        if (Boolean.getBoolean("workaroundNoDisableDoubleCacheImported")) {
            return null;
        };
        VideoDescriptor cachedSmile = CacheManager.getPreferredCachedVideo(smid, false, null);
        if (cachedSmile != null && !cachedSmile.isLow()) {
            return cachedSmile;
        };
        VideoDescriptor cachedDmc = CacheManager.getPreferredCachedVideo(smid, true, null);
        if (cachedDmc != null && !cachedDmc.isLow()
            && !".hls".equals(cachedDmc.getPostfix())) {

            return cachedDmc;
        };
        return null;
    };

    private static int notifyAndCheckResult(NLEventSource eventSource, int eventId) {
        if (eventSource != null) {
            int result = NLShared.INSTANCE.notifySystemEvent(eventId, eventSource, true);
            if (result != SystemEventListener.RESULT_OK) {
                return result;
            }
        }
        return SystemEventListener.RESULT_OK;
    }

    private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9]");
    private static Pair<String,String> getVideoTypeAndId
    (HttpRequestHeader requestHeader) {

        // - nicocachenl_video_typeとnicocachenl_video_idは
        //   url_injection_sys.jsというブラウザ側jsが付加していたが、これは廃止した
        //   (2024-10-04).

        // 例:"sm".
        String type = requestHeader.getParameter("nicocachenl_video_type");

        // 例:sm9なら"9".
        String id = requestHeader.getParameter("nicocachenl_video_id");

        if (type != null && id != null) {
            return new Pair<String,String>(type, id);
        };

        String url = requestHeader.getURI();
        int question = url.indexOf("?");
        if (question >= 0) {
            url = url.substring(0, question);
        };

        // Logger.info("--mptosmid.get: " + url);
        String smid = masterPlaylistToSmid.get(url);
        if (smid == null) {
            return new Pair<String,String>(null, null);
        };

        Matcher m = NUMBER_PATTERN.matcher(smid);
        if (m.find()) {
            return new Pair<String,String>(
                smid.substring(0, m.start()),
                smid.substring(m.start()));
        };
        return new Pair<String,String>(null, null);
    };

    private Resource processMasterPlaylist
    (HttpRequestHeader requestHeader)
        throws IOException {

        // Logger.info(abbrurl(requestHeader));
        // Logger.info(requestHeader.toString());

        String uri = requestHeader.getURI();

        Pair<String,String> videoTypeAndId = getVideoTypeAndId(requestHeader);
        String videoType = videoTypeAndId.first;
        String videoId = videoTypeAndId.second;
        videoTypeAndId = null;
        String smid = videoType + videoId;

        if (videoType == null || videoId == null) {
            // nicocache_nlのjavascriptによるインジェクションが上手くいっていない
            Logger.info("対象URL(cmaf)ですが動画情報が不明なためキャッシュしません: " + smid);
            Logger.debug("url: " + uri);
            return null;
        };

        // - nicocachenl_save: "false"の場合にキャッシュ保存をしない.
        //   未指定と不正値の場合は"true"で保存をする.
        //   "force_true"だと各種自動判定によって"false"にされない.
        // - "force_false"は未定義で不正値("true"扱い).
        // - setCacheSaveFlagはそのプレイリストに対するキャッシュ保存全てを抑制するが
        //   nicocachenl_saveはその通信だけでキャッシュ保存を抑制する.
        // - nicocachenl_* に関するドキュメントをどこかにまとめること.
        String nicocachenl_save = requestHeader.getParameter("nicocachenl_save");
        if (! "force_true".equals(nicocachenl_save)) {
            // - Origin: https://embed.nicovideo.jp
            // - Referer: https://embed.nicovideo.jp/
            String origin = requestHeader.getMessageHeader("Origin");
            String referer = requestHeader.getMessageHeader("Referer");
            origin = origin + "/"; // - "null/"考慮済み.
            referer = referer + "/";
            String domain = "//embed.nicovideo.jp/";
            if (origin.contains(domain) || referer.contains(domain)) {
                nicocachenl_save = "false";
                Logger.info(smid + ": 埋め込み動画のためキャッシュ保存抑制");
            };
        };

        // video_src_idとaudio_src_idを得るためにbodyを得る.
        Pair<URLResource, byte[]> rr = FetchUtil.fetchBinaryContent(requestHeader);
        URLResource resource = rr.first;
        byte[] binContent = rr.second;

        if (binContent == null) {
            // どういう場合か?
            return resource;
        };

        String masterM3u8;
        masterM3u8 = new String(binContent, StandardCharsets.UTF_8);

        // - ここからmaster.m3u8に書かれた内容をパースしている.
        // - 正規表現を使った無理矢理な方法.
        // - 正当にはm3u8パーサーを用意すること.

        Matcher videoPLM = VIDEO_PLAYLIST_URL_PATTERN.matcher(masterM3u8);
        Matcher audioPLM = AUDIO_PLAYLIST_URL_PATTERN.matcher(masterM3u8);

        // - 2025-03-19時点audio品質は2段階. 告知されている品質分類は5段階.
        // - capacityは重要な定数ではない.
        // - audio movie info list. 音声サブプレイリストハンドラー.
        List<DomandCVIEntry> audioMovis = new ArrayList<>(5);

        int videoPlaylistCount = 0;

        // - 2025-03-18前後からaudioも複数の品質がある.
        while (audioPLM.find()) {
            String audioSrcId = audioPLM.group(2); // 例: "audio-aac-128kbps"
            String audioKbps = audioPLM.group(4); // 例: 128
            String keyForAudioDomandCVI = smid + audioSrcId;

            // - nltmp_sm12345[0p,128]_title.hls というような一時dirを作る(0pに注目).
            // - そこにaudio chunksだけをキャッシュする.
            // - 完了時にvideoキャッシュ側に統合する.
            DomandCVIEntry audioMovInfo = prepareDomandCVIEntry(
                keyForAudioDomandCVI, videoType, videoId, /*videoHeight*/"0", audioKbps,
                /*videoMode*/"0p", /*videoSrcId*/null, audioSrcId);

            if (audioMovInfo == null) {
                // - prepareDomandCVIEntryがログを済ましている.
                // - nicocachenl_noerrorを伝播させる処理をするべき.
                return resource;
            };
            audioMovis.add(audioMovInfo);
        };

        // - 複数のvideoプレイリストがある.
        // - 1080p, 720p, 480p, 320p, 320p-lowestなどそれぞれにDomandCVIEntryを
        //   用意する.
        while (videoPLM.find()) {
            ++videoPlaylistCount;

            String videoSrcId = videoPLM.group(2); // 例: "video-h264-1080p"
            String videoHeight = videoPLM.group(5); // 例: 1080
            String videoMode = videoPLM.group(4); // 例: 1080p
            String keyForVideoDomandCVI = smid + videoSrcId;
            // - nltmp_sm12345[1080p,0]_title.hls というような一時dirを作る(
            //   audio kbpsが0であることに注目).
            // - そこにvideo chunksだけをキャッシュする.
            // - 完了時にaudio chunksを取り込む.
            DomandCVIEntry videoMovInfo = prepareDomandCVIEntry(
                keyForVideoDomandCVI,
                videoType, videoId, videoHeight, /*audioKbps*/"0",
                videoMode, videoSrcId, /*audioSrcId*/null);
            // nullはどういう状況か？
            if (videoMovInfo != null) {
                for (DomandCVIEntry audioMovInfo : audioMovis) {
                    videoMovInfo.assocList.add(audioMovInfo);
                    audioMovInfo.assocList.add(videoMovInfo);
                };
            };
        };

        if (0 == videoPlaylistCount || 0 == audioMovis.size()) {
            Logger.info("サブプレイリストを検出出来ないためキャッシュしません: " + smid);
            return resource;
        };

        // - 利用できるうちの最高画質最高音質.
        // - note: 1080pで投稿された動画であっても一般会員は上限720p
        //     (2024-08 - 2025-02時点の制限).
        DomandCVIEntry topMovInfo;
        {
            // - 選択可能なうちの最良audioと最良video.
            DomandCVIEntry a = audioMovis.get(0);
            DomandCVIEntry v = a.assocList.get(0);
            // key値はユニークであればそれで良い.
            String key =
                v.getVideoNumId() + "," + a.getAudioKbps() + "," + v.getVideoMode();
            topMovInfo = prepareDomandCVIEntry(
                key, v.getVideoType(), v.getVideoNumId(), "" + v.getVideoHeight(),
                "" + a.getAudioKbps(), v.getVideoMode(),
                v.getVideoSrcId(), a.getAudioSrcId());
        };

        // - 一番画質が良いものでイベント通知する.
        // - 全ての画質を通知するべきか？期待される動作が分からない.
        Cache cacheForEvent = topMovInfo.getCache();

        NLEventSource eventSource = null;
        if (NLShared.INSTANCE.countSystemEventListeners() > 0) {
            eventSource = new NLEventSource(null, requestHeader, cacheForEvent);
        };

        // [nl] Extensionにキャッシュ要求イベントを通知する.
        if   (notifyAndCheckResult(eventSource, SystemEventListener.CACHE_REQUEST)
              != SystemEventListener.RESULT_OK) {
            audioMovis.get(0).setCacheSaveFlag(false); // video側にも伝播する.
            Logger.debug(requestHeader.getURI() + " pass-through by extension");
            return resource;
        };

        // - 特殊キャッシュを持っていたらキャッシュから応答する.
        Resource cacheResource = processMasterPlaylistFromSpecialCacheIfExists(
            requestHeader, topMovInfo);
        if (cacheResource != null) {
            return cacheResource;
        };

        // [nl] Extensionがキャッシュを禁止する場合はキャッシュしない
        if   (notifyAndCheckResult(eventSource, SystemEventListener.CACHE_STARTING)
              != SystemEventListener.RESULT_OK) {
            audioMovis.get(0).setCacheSaveFlag(false); // video側にも伝播する.
            Logger.info("(cmaf|ext)disable cache: " +
                        topMovInfo.getCache().getCacheFileName());
            // fall through
        };

        // smidにtitleを結び付ける処理.
        scheduleTitleRetrieverIfNeeded(audioMovis.get(0));

        // キャッシュしない場合でも、そのフラグを子要素であるURLに伝達するために通常通り
        // のことをする.
        return processMasterPlaylistFromServer(
            requestHeader, audioMovis, resource, masterM3u8, nicocachenl_save);
    };

    // - メソッド名は「キャッシュから応答するわけではない」という意味.
    private Resource processMasterPlaylistFromServer
    (HttpRequestHeader requestHeader
     , List<DomandCVIEntry> audioMovis
     , URLResource resource, String masterM3u8
     , String nicocachenl_save) throws IOException {

        // Logger.info("----(cmaf)processMasterPlaylistFromServer: " + requestHeader.getPathBasename());

        if (! "false".equals(nicocachenl_save)) {
            mightWriteMasterM3u8s(masterM3u8, audioMovis.get(0).getFamilyEntries());
        };
        // - この通信でのmaster.m3u8に関するキャッシュ保存処理ここまで.

        // - 通信ハンドラーにキャッシュに必要な情報を渡すために、サーバーから来た
        //   マスタープレイリストを加工してからクライアントへ.

        final String nicocachenl_save_param =
            "false".equals(nicocachenl_save) || "force_true".equals(nicocachenl_save)
            ? "nicocachenl_save=" + nicocachenl_save
            : "";

        // - ファミリー内のどれでもいい操作の時の代表オブジェクト.
        DomandCVIEntry movie = audioMovis.get(0).getRepresentative();

        String smid = movie.getSmid();
        String contentToResponse = M3u8Util.replaceURL(masterM3u8, (url, line) -> {
                if (url.contains("/audio")) {
                    String audioSrcId = getAudioSrcIdFromLine(line);
                    if (audioSrcId == null) {
                        Logger.info("error: " + smid
                                    + ": audio url in unknown line: " + line);
                    };
                    DomandCVIEntry audioMovInfo =
                        movie.getDomandCVIEntryByAudioSrcId(audioSrcId);
                    if (audioMovInfo == null) {
                        Logger.info("error: " + smid + ": unknown audio src id: " + audioSrcId);
                        return addUrlSearch(url,
                                            "nicocachenl_noerror=true",
                                            nicocachenl_save_param);
                    };
                    return addUrlSearch(
                        url,
                        "nicocachenl_domandcvikey=" + audioMovInfo.getKey(),
                        nicocachenl_save_param);
                };

                if (!url.contains("/video")) {
                    Logger.info("error: " + smid
                                + ": unknown url expression: " + url);
                    return addUrlSearch(url,
                                        "nicocachenl_noerror=true",
                                        nicocachenl_save_param);
                };

                // - 以降 true == url.contains("/video")

                String videoSrcId = getVideoSrcId(url); // 例: "video-h264-1080p"
                if (videoSrcId == null) {
                    Logger.info("error: " + smid
                                + ": unknown video m3u8 url expression: " + url);
                    return addUrlSearch(url,
                                        "nicocachenl_noerror=true",
                                        nicocachenl_save_param);
                };
                DomandCVIEntry videoMovInfo =
                    movie.getDomandCVIEntryByVideoSrcId(videoSrcId);
                if (videoMovInfo == null) {
                    Logger.info("error: " + smid
                                + ": unknown video-src-id: " + videoSrcId);
                    return addUrlSearch(url,
                                        "nicocachenl_noerror=true",
                                        nicocachenl_save_param);
                };
                return addUrlSearch(
                    url,
                    "nicocachenl_domandcvikey=" + videoMovInfo.getKey(),
                    nicocachenl_save_param);
            });

        Resource response = new StringResource(contentToResponse);
        response.setResponseHeader(HttpHeader.CONTENT_TYPE, "application/vnd.apple.mpegurl");
        setCors(response, requestHeader);
        return response;
    };

    private static String addUrlSearch(String url, String... search) {
        String s = String.join("&", search)
            .replaceAll("&+", "&")
            .replaceAll("&*$", "");
        if (url.contains("?")) {
            return url + "&" + s;
        };
        return url + "?" + s;
    };

    private static String getVideoSrcId(String m3u8url) {
        Matcher videoPLM = VIDEO_PLAYLIST_URL_PATTERN.matcher(m3u8url);
        if (!videoPLM.find()) {
            return null;
        };
        return videoPLM.group(2); // 例: "video-h264-1080p"
    };

    private static final Pattern M3U8_GROUP_ID_PATTERN = Pattern.compile
        ("GROUP-ID=\"([^\"]*)\"");
    private static String getAudioSrcIdFromLine(String m3u8line) {
        Matcher m = M3U8_GROUP_ID_PATTERN.matcher(m3u8line);
        if (!m.find()) {
            return null;
        };
        return m.group(1);
    };

    private static boolean mightWriteMasterM3u8s
    (String serverSideMasterM3u8, Set<DomandCVIEntry> entries) {

        Map<String,String> srcIdToCodec = M3u8Util.buildSrcIdToCodecMap(
            serverSideMasterM3u8, "a:", "v:");

        for (DomandCVIEntry entry : entries) {
            entry.setVideoCodec(srcIdToCodec.get("v:" + entry.getVideoSrcId()));
            entry.setAudioCodec(srcIdToCodec.get("a:" + entry.getAudioSrcId()));

            if (entry.isAudioHandling()) {
                if (entry.getVideoCodec() == null) {
                    entry.setVideoCodec("avc1.4d4020"); // 普通のH.264.
                };
            } else if (entry.getAudioCodec() == null) {
                entry.setAudioCodec("mp4a.40.2"); // 普通のaac.
            };
            // - 上記はどちらもnltmp状態を再生するためのmaster.m3u8を作るための仮値.
            // - audioとvideoを統合する際にmaster.m3u8を作り直す.

            byte[] toWriteFile = M3u8Util.buildMasterM3u8ForSaving(
                entry.getAudioCodec(), entry.getVideoCodec());

            // 実際に書き込まれるのはmovieInfo.chunkLoadStart()した時.
            entry.mightWriteMasterM3u8(toWriteFile);
        };
        return true;
    };

    // - エラーをログするためのadvice関数.
    // - videoとaudioの両方を指定する必要があるのは、audio品質がひとつだった頃の名残り.
    // - つまり関数構成を変えるべき.
    private static DomandCVIEntry prepareDomandCVIEntry
    (String entryKeyName
     , String videoType, String videoNumId, String videoHeight
     , String audioKbps , String videoMode, String videoSrcId
     , String audioSrcId) {

        DomandCVIEntry movieInfo
            = NLShared.INSTANCE.getDomandCVIManager().get(entryKeyName);

        if (movieInfo != null) {
            return movieInfo;
        };
        try {
            // - ここがDomandCVIEntryの初期化ポイント.
            // - DomandCVIManagerにも入る. keyは連想配列のキー.
            movieInfo = DomandCVIUtil.initAndPutEntry(
                entryKeyName, videoType, videoNumId, videoHeight
                , audioKbps, videoMode, videoSrcId, audioSrcId);
        } catch (NoIdInfoException e) {
            // どういう場合か不明. 要検証.
            Logger.info("idInfo is not found: " + videoType + videoNumId);
            return null;
        } catch (NumberFormatException e) {
            Logger.info("video height or audio kbps is not integer expression: "
                        + videoType + videoNumId);
            return null;
        };
        return movieInfo;
    };

    public static int countString(String haystack, String needle) {
        int needleLength = needle.length();
        int count = 0;
        int index = 0;

        for (;;) {
            index = haystack.indexOf(needle, index);
            if (index < 0) {
                return count;
            };
            ++count;
            index += needleLength;
        }
    };

    private void scheduleTitleRetrieverIfNeeded(DomandCVIEntry data) {
        if (!Boolean.getBoolean("title")) {
            return;
        };
        FutureTask<String> retrieveTitlteTask = null;
        String sm = data.getVideoType();
        String id_without_sm = data.getVideoNumId();
        NicoIdInfoCache.Entry idInfo = data.getIdInfo();
        if (idInfo == null || !idInfo.isTitleValid()) {
            retrieveTitlteTask = new FutureTask<>(
                new NicoCachingTitleRetriever(sm, id_without_sm));
            executor.execute(retrieveTitlteTask);
        };
    };

    // - 特殊キャッシュ(後述)を持っていればローカルのmaster.m3u8を返す.
    // - master.m3u8を持つhlsではあるがaudioとvideoに分割されていないもの(dmc時代のhls)や
    //   それ以外の非分割hlsやそれ以外の未知のhlsを特殊キャッシュと呼ぶ.
    // - 普通hls cacheの応答はsub playlist側で行なう.
    // - 持っていなければnull.
    // - requestHeaderと要求側最高品質を表すDomandCVIEntryを指定する.
    // - 要求に合わせた品質キャッシュを応答する機能は実装していない.
    private Resource processMasterPlaylistFromSpecialCacheIfExists
    (HttpRequestHeader requestHeader, DomandCVIEntry data)
        throws IOException {

        String smid = data.getSmid();
        String postfix = data.getPostfix();
        VideoDescriptor requestedVideo = data.getVideoDescriptor();
        Cache cacheToResponse;

        {
            VideoDescriptor cachedHls =
                CacheManager.getPreferredCachedVideo(smid, true, Cache.HLS);

            Logger.debug("Preferred cache: " + cachedHls);
            if (cachedHls == null) {
                return null;
            };
            if (requestedVideo.isPreferredThan(cachedHls, true, postfix)) {
                return null;
            };

            cacheToResponse = new Cache(cachedHls);
        };

        // - なぜ念入りチェックをすることにしたか？
        if (!cacheToResponse.exists()) {
            return null;
        };

        File cacheDir = cacheToResponse.getCacheFile();
        if (new File(cacheDir, "video.m3u8").exists()
            && new File(cacheDir, "audio.m3u8").exists()) {

            // - dms仕様の普通のhlsキャッシュ.
            // - これらはsub playlistで応答する.
            return null;
        };

        // - dmc/hls仕様のキャッシュかどうか確認.
        // - 2025-07-05確認. 以前はsub playlist通信で1/ts/playlist.m3u8を応答する手法を
        //   とっていたが、この方法だと再生出来なくなった(黒画面のまま、なおかつ読み込み
        //   マークが出ない).
        // - master.m3u8通信で応答するようにする.
        if (new File(cacheDir, "1/ts/playlist.m3u8").exists()) {
            // do nothing.
        } else {
            // ここが最終確認だからreturn.
            return null;
        };

        // - 上記の除外条件ではprocessSubPlaylistFromCacheIfExistsでキャッシュ利用処理する.
        // - 上記以外のmaster.m3u8だけを持っているだろう未知のhlsキャッシュ.
        // - マスタープレイリストに書かれたcodecと実際のサブプレイリストのチャンクコーデックが
        //   不一致だと再生されない.
        // - ここではマスタープレイリストを乗っ取ることでその問題を回避する.
        // - 下記方法でマスターを乗っ取ると、画質選択の表示がおかしくなる(2024-08).
        //   - 最低画質を選択しているように表示されたり、自動を選択しているように表示される.
        //   - これらの画質選択は保存されるわけではないから実害はない.
        //   - しかし格好悪いので、上記条件により除外し最低限にする.
        //   - 本当は品質ごとのプレイリストが存在しているように偽装するべき.

        File file = new File(cacheDir, "master.m3u8");

        boolean loadFailed = false;
        String urlPrefix = requestHeader.getScheme() + "://" + requestHeader.getHost();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String params = "nicocachenl_domandcvikey=" + data.getKey();
            String line;
            while (null != (line = br.readLine())) {
                // - CmafUseCacheProcessorを呼び出すURLをセット.
                line = M3u8Util.replaceURL(line, (url,_line) -> {
                        return urlPrefix + "/cache/file/" + params + "//" + url;
                    });
                baos.write(line.getBytes(StandardCharsets.UTF_8));
                baos.write('\n');
            };
        } catch (IOException e) {
            loadFailed = true;
        };

        if (loadFailed) {
            Logger.warning("(cmaf|master)load failed: master.m3u8: " + file.getPath());
        } else {
            Logger.info("(cmaf)using cache: " + cacheToResponse.getCacheFileName());
            if (Boolean.getBoolean("touchCache")) {
                cacheToResponse.touch();
            };
            data.setCache(cacheToResponse);
        };

        Resource r = new StringResource(baos.toByteArray());
        r.setResponseHeader(HttpHeader.CONTENT_TYPE, "application/vnd.apple.mpegurl");
        setCors(r, requestHeader);

        // LimitFlvSpeedListener.addTo(r);
        return r;
    };


    private static Resource processSubPlaylistFromCacheIfExists
    (HttpRequestHeader requestHeader, AV av, DomandCVIEntry movieInfo) {

        // - キャッシュ利用せずキャッシュ保存する処理でもmovieInfo.cacheは利用される.

        String smid = movieInfo.getSmid();
        // - VideoDescriptorオブジェクトはCacheオブジェクトに管理されるもののはずだから
        //   movieInfo.getCache()から取り出した方がいいかも知れない.
        VideoDescriptor requestedVideo = movieInfo.getVideoDescriptor();

        // - 持っている中での等価品質or上位品質を探す.
        // - 音声品質が複数になった(2025-03)ことに対応するために、video品質とaudio品質は
        //   別々に比較探索する.
        // - 要求に合わせた品質キャッシュを応答する実装ではない.
        Cache cache = movieInfo.getCache();
        // Cache debugbefore = cache;
        if (cache == null || // - チェックしているがnullである状態はない.
            !cache.exists()  // - 同じ品質を持っているならそれを使う.
            ) {
            Cache preferredCache = null;
            String postfix = movieInfo.getPostfix(); // ".hls"
            for (VideoDescriptor x : CacheManager.getVideos(smid)) {
                if (requestedVideo.isPreferredThan(
                        x, /*dmcだけ*/true, /*要求postfix*/postfix,
                        /*audioCheckするか*/av.isAudio(),
                        /*videoCheckするか*/av.isVideo())) {
                    continue;
                };
                if (!CacheManager.completedCacheExsists(x)) {
                    continue;
                };
                preferredCache = new Cache(x);
                break;
            };
            if (preferredCache == null) {  // - 優先品質キャッシュは見つからなかった.
                return null;
            };
            cache = preferredCache;
        };
        // Logger.info("--(psp|fcie) " + debugbefore.getVideoDescriptor() + " , " + cache.getVideoDescriptor());

        File cacheDir = cache.getCacheFile();
        File file = null;
        if (av.isAudio()) {
            file = new File(cacheDir, "audio.m3u8");
        } else {
            file = new File(cacheDir, "video.m3u8");
        };
        if (!file.exists()) {
            // - hlsキャッシュだがaudio.m3u8かvideo.m3u8が存在しないパターン.
            // - dmc時代のhlsキャッシュもここを通る. (smXXX*.hls/下にmaster.m3u8,
            //   1/ts/playlist.m3u8, 1/ts/1.tsなどを持つキャッシュ)
            // - あるいはNicoCacheが作ったのではない独自のhlsキャッシュ.
            // - 2024-12-10時点でニコニコ動画のhlsプレイヤーはaudio.m3u8側とvideo.m3u8側に
            //   master.m3u8内容を返しても再生をするが次の制限がある.
            //   - 画質選択が機能しなくなる.
            //   - audio.m3u8要求に映像を含むm3u8を返すとサーバーから映像を読み込まなく
            //     なる、video.m3u8要求に対して音声を含む場合も同様.
            //   - そのため片方メディアが下位品質であるmaster.m3u8を応答に利用すると、
            //     上位品質であるもう片方メディアの読み込みを抑制してしまう.
            //   - したがって選択可能な最高品質よりも下位の品質のキャッシュしか持って
            //     いない場合には、どちらのメディアでもキャッシュを利用しない動作とする.
            if (isEqualOrPreferredThanAllAvailable(
                    cache.getVideoDescriptor(), movieInfo)) {
                file = new File(cacheDir, "master.m3u8");
            } else {
                Logger.info("(cmaf)lower cache found. disable %s cache usage: %s",
                            av.isAudio() ? "audio" : "video",
                            cache.getVideoDescriptor());
                return null;
            };
        };

        // - キャッシュ利用確定.

        movieInfo.setCache(cache);
        Logger.debug("Preferred cache: " + movieInfo.getCache().getVideoDescriptor());

        if (Boolean.getBoolean("touchCache")) {
            cache.touch();
        };

        boolean loadFailedm3u8 = false;
        String urlPrefix = requestHeader.getScheme() + "://" + requestHeader.getHost();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String params = "nicocachenl_domandcvikey=" + movieInfo.getKey();
            String line;
            // - なぜ一行ずつ処理することにしたか覚えていない.
            while (null != (line = br.readLine())) {
                // - CmafUseCacheProcessorを呼び出すNicoCache URLをセット.
                line = M3u8Util.replaceURL(line, (url,_line) -> {
                        return urlPrefix + "/cache/file/" + params + "//" + url;
                    });
                baos.write(line.getBytes(StandardCharsets.UTF_8));
                baos.write('\n');
            };
        } catch (IOException e) {
            loadFailedm3u8 = true;
        };

        String reqbasename = getCodecAndRate(getUrlBaseName(requestHeader.getURI()));
        if (loadFailedm3u8) {
            Logger.warning("(cmaf|sub)load failed: " + file);
        } else if (av.isAudio()) {
            Logger.info("(cmaf)using audio cache: " +
                        smid + "." + cache.getVideoDescriptor().getAudioBitrate()
                        + ": req=" + reqbasename
                        // + " " + "--- " + cache.getCachePath()
                );
        } else {
            Logger.info("(cmaf)using video cache: " +
                        smid + "." + cache.getVideoDescriptor().getVideoMode() +
                        ": req=" + reqbasename);
        };

        Resource r = new StringResource(baos.toByteArray());
        r.setResponseHeader(HttpHeader.CONTENT_TYPE, "application/vnd.apple.mpegurl");
        setCors(r, requestHeader);

        // LimitFlvSpeedListener.addTo(r);
        return r;
    };

    /**
     * - サーバー側が示す利用可能なすべての品質に対してargが同等品質であるか上位品質で
     *   あればtrue.
     * - ここで言う利用可能品質リストは、サーバーから配信されるマスターm3u8の内容のことで
     *   一般会員ならば720pが上限である範囲を言う.
     * - 複数の音声品質に対応.
     */
    private static boolean isEqualOrPreferredThanAllAvailable
    (VideoDescriptor arg, DomandCVIEntry movieInfo) {
        // - 処理の開始点をmovieInfoにしているのが分かりにくい.
        // - マスターm3u8を表現するクラスが必要.
        String postfix = movieInfo.getPostfix(); // ".hls"
        // - このentriesはサーバーが示す音声と映像ごとの品質のリスト.
        for (DomandCVIEntry x : movieInfo.getFamilyEntries()) {
            // - audioとvideoの両方を扱うentryのことは考えない.
            // - 扱っている側の品質だけを比較する.
            boolean audioCheck = x.isAudioHandling();
            boolean videoCheck = !audioCheck;
            if (x.getVideoDescriptor().isPreferredThan(
                    arg, true, postfix, audioCheck, videoCheck)) {
                // - x > arg

                // - 2025-06-26: 同等品質キャッシュを持っているにも関わらず低位と誤判定
                //   されている疑惑がある.
                // - おそらく原因はargに渡す変数が間違っていたことだった(2025-07-02).
                // - この関数にも一応、検証用の表示を追加しておく.
                String s = String.format("(cmaf;debug)%s: 通信候補[%s] > キャッシュ[%s]",
                                         audioCheck ? "audio check" : "video check",
                                         x.getVideoDescriptor(), arg);
                Logger.info(s);
                return false;
            };
        };
        return true;
    };

    // - 表示のためのメソッド.
    // - "video-h264-1080p.m3u8" -> "h264-1080p"
    // - "audio-aac-192kbps.m3u8" -> "aac-192kbps"
    private static String getCodecAndRate(String s) {
        int start = s.indexOf('-');
        int end = s.lastIndexOf('.');
        if (start < 0 || end < 0) {
            return s;
        };
        return s.substring(start + 1, end);
    };

    private static final Pattern EXT_X_KEY_LINE =
        Pattern.compile("(^|\n)(#EXT-X-KEY:[^\n]*?)(,[^\n]*)?(\n|$)");

    // cmaf-m3u8の#EXT-X-KEY:行をただのコメント行にする.
    private static String removeExtXKeyLine(String playlistContent) {
        Matcher m = EXT_X_KEY_LINE.matcher(playlistContent);
        return m.replaceAll("$1#$4"); // 行数減らさない.
    };

    // group(1): "audio" | "video"
    // group(2): 品質ごとに最高品質から低品質へ向かって"1","12","123"と増えていく謎の部分
    // group(3): チャンクファイル名
    private static final Pattern TO_SUBPLAYLIST_REMOTE_URL_TO_LOCAL_URL =
            Pattern.compile("^.*/(audio|video)/(\\d+)/[^/]*/([^?]*)(?:[?].*)$");

    // #EXT-X-KEYの属性は順不同なので、行全体を取得して個別に解釈する。
    private static final Pattern EXT_X_KEY_ATTRIBUTE_PATTERN =
        Pattern.compile("(?:^|,)\\s*([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    private static String getExtXKeyAttribute(String attributeList, String name) {
        Matcher m = EXT_X_KEY_ATTRIBUTE_PATTERN.matcher(attributeList);
        while (m.find()) {
            if (!name.equals(m.group(1))) {
                continue;
            };
            String value = m.group(2).trim();
            if (value.length() >= 2
                && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"') {
                return value.substring(1, value.length() - 1);
            };
            return value;
        };
        return null;
    };

    private static final class PlaylistDecryptInfo {
        final byte[] iv;

        PlaylistDecryptInfo(byte[] iv) {
            this.iv = iv;
        };
    };

    // #EXT-X-KEYは次の同タグまで後続のmedia segmentへ適用される。
    // プレイリスト全体の先頭1件だけを保存すると、鍵ローテーション後の
    // セグメントを誤った鍵・IVで復号してしまうため、ファイル名ごとに記録する。
    // 不明な暗号方式や属性が混在するプレイリストは、誤った復号結果を保存
    // しないようにnullを返す。
    private static PlaylistDecryptInfo registerPlaylistDecryptInfo(
        String content, AV av, DomandCVIEntry movieInfo) {

        PlaylistDecryptInfo first = null;
        String keyUrl = null;
        byte[] iv = null;

        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.endsWith("\r")
                ? rawLine.substring(0, rawLine.length() - 1)
                : rawLine;

            if (line.startsWith("#EXT-X-KEY:")) {
                String attributes = line.substring("#EXT-X-KEY:".length());
                String method = getExtXKeyAttribute(attributes, "METHOD");
                String nextKeyUrl = getExtXKeyAttribute(attributes, "URI");
                String ivHex = getExtXKeyAttribute(attributes, "IV");

                if (!"AES-128".equals(method)
                    || nextKeyUrl == null
                    || ivHex == null
                    || !KEY_URL_PATTERN.matcher(nextKeyUrl).matches()
                    || !ivHex.startsWith("0x")) {
                    return null;
                };

                byte[] nextIV;
                try {
                    nextIV = hexStringToByteArray(ivHex.substring(2));
                } catch (NumberFormatException e) {
                    return null;
                };
                if (nextIV.length != 16) {
                    return null;
                };

                keyUrl = nextKeyUrl;
                iv = nextIV;
                if (first == null) {
                    first = new PlaylistDecryptInfo(iv);
                };
                continue;
            };

            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            };

            Matcher m = TO_SUBPLAYLIST_REMOTE_URL_TO_LOCAL_URL.matcher(line);
            if (m.find() && keyUrl != null && iv != null) {
                String filename = m.group(3);
                if (av.isAudio()) {
                    movieInfo.setAudioDecryptInfo(filename, keyUrl, iv);
                } else {
                    movieInfo.setVideoDecryptInfo(filename, keyUrl, iv);
                };
            };
        };
        return first;
    };


    private static Resource processSubPlaylist(
        HttpRequestHeader requestHeader, AV av, DomandCVIEntry movieInfo)
        throws IOException {

        if (av.isUnspecified()) {
            Logger.info("processSubPlaylist: av==UNSPECIFIED: コーディングミス");
            return null;
        };
        if (movieInfo == null) {
            Logger.info("processSubPlaylist: movieInfo==null: コーディングミス");
            return null;
        };

        Resource preferredCache = processSubPlaylistFromCacheIfExists(
            requestHeader, av, movieInfo);
        if (preferredCache != null) {
            return preferredCache;
        };

        // swf,flv,mp4のキャッシュを持っている場合HLSをキャッシュしない
        {
            VideoDescriptor cached = superiorIncompatibleCache(movieInfo);
            if (cached != null) {
                movieInfo.setCacheSaveFlag(false); // キャッシュしないというフラグ.
                Logger.info("(cmaf)single file cache found. disable cache usage: "
                            + cached.toString());
                // no return.
            };
        };

        // - キャッシュしない決定をprocessMasterPlaylistでしていても、URLの加工は行う.

        Pair<URLResource, byte[]> rr = FetchUtil.fetchBinaryContent(requestHeader);
        URLResource serverResponse = rr.first;
        byte[] binContent = rr.second;

        if (binContent == null) {
            Logger.info("(CCP|binContent)error: 通信失敗.");
            return serverResponse;
        };

        String content = new String(binContent, StandardCharsets.UTF_8);

        // - 余計なsearch部を付けた場合や大量にリクエストを送った時に起きる.
        // - dms時代は403なし. dmc時代は403が付いていた. 一応403もチェック.
        if (content.startsWith("Forbidden") ||
            content.startsWith("403 Forbidden")) {

            movieInfo.setCacheSaveFlag(false); // キャッシュしない
            // - 2025-02 通信の失敗はnicocachenl_saveによるキャッシュ抑制の方がいい.
            // - nicocachenl_saveとの使い訳は徹底されていない.

            Logger.info("(cmaf subpl)Forbidden: requestHeader:" + requestHeader);
        };

        PlaylistDecryptInfo decryptInfo = registerPlaylistDecryptInfo(
            content, av, movieInfo);
        if (decryptInfo == null) {
            movieInfo.setCacheSaveFlag(false); // キャッシュしないというフラグ.
            Logger.info("復号情報をプレイリストから取り出せませんでした: "
                        + movieInfo.getSmid());
            return serverResponse;
        };
        if (av.isAudio()) {
            movieInfo.setAudioIV(decryptInfo.iv);
        } else {
            movieInfo.setVideoIV(decryptInfo.iv);
        };

        if (! "false".equals(requestHeader.getParameter("nicocachenl_save"))
            && movieInfo.getCacheSaveFlag()) {

            // video.m3u8とaudio.m3u8の内容構築.
            String contentToWriteFile = SubPlaylistRemoteUrlToLocalCacheUrl(
                content, movieInfo);

            if (contentToWriteFile != null) {
                byte[] binContentToWriteFile =
                    contentToWriteFile.getBytes(StandardCharsets.UTF_8);
                if (av.isAudio()) {
                    movieInfo.mightWriteAudioM3u8(binContentToWriteFile);
                } else {
                    movieInfo.mightWriteVideoM3u8(binContentToWriteFile);
                };
            };
        };

        // メソッドが縦に長すぎる. 整理必要.

        // - レスポンスを加工してクライアント(ブラウザ)へ.
        // - ここには未知形式のURLを含むm3u8は来ない. 未知形式は、
        //   SubPlaylistRemoteUrlToLocalCacheUrlで検証されエラー済み.
        String nicocachenl_save = requestHeader.getParameter("nicocachenl_save");
        String nicocachenl_save_param =
            "false".equals(nicocachenl_save) || "force_true".equals(nicocachenl_save)
            ? "&nicocachenl_save=" + nicocachenl_save
            : "";
        String contentToResponse = M3u8Util.injectURLSearch(
            content,
            "nicocachenl_domandcvikey=" + movieInfo.getKey() +
            nicocachenl_save_param);
        Resource resp = new StringResource(contentToResponse);
        resp.setResponseHeader(HttpHeader.CONTENT_TYPE, "application/vnd.apple.mpegurl");
        setCors(resp, requestHeader);
        return resp;
    };

    // - SubPlaylist(例:video-h264-1080p.m3u8,audio-aac-128kbps.m3u8)の
    //   ニコ動側のm3u8内容をローカルキャッシュ用に変換する.
    // - 不正な表現があった場合にキャッシュしないフラグをセット.
    // - エラーの表示.
    private static String SubPlaylistRemoteUrlToLocalCacheUrl
    (String remoteContent, DomandCVIEntry movieInfo) {

        String contentToWriteFile = removeExtXKeyLine(remoteContent);
        try {
            contentToWriteFile = M3u8Util.replaceURL(
                contentToWriteFile,
                (url, _line) -> {
                    if (!PROCESSOR_SUPPORTED_PATTERN.matcher(url).matches()) {
                        // - CmafCachingProcessorが処理しない表現が表われること
                        //   は想定外. 他でエラーするからここで弾く.
                        throw new IllegalStateException(url);
                    };
                    Matcher m = TO_SUBPLAYLIST_REMOTE_URL_TO_LOCAL_URL.matcher(url);
                    if (!m.find()) {
                        throw new IllegalStateException(url);
                    };
                    String audioVideo = m.group(1);
                    String filename = m.group(3);
                    return m.replaceFirst(audioVideo + "/" + filename);
                });
        } catch (IllegalStateException e) {
            movieInfo.setCacheSaveFlag(false);
            Logger.info("サブプレイリストに不明な表現があるためキャッシュしません"
                        + ": '" + e.getMessage() + "': " + movieInfo.getSmid());
            return null;
        };
        return contentToWriteFile;
    };


    private Resource processChunk(HttpRequestHeader requestHeader
                                  , DomandCVIEntry movieInfo
                                  , String avword
                                  , String filename)
        throws IOException {

        if (movieInfo == null) {
            Logger.info("processChunk: movieInfo==null: コーディングミス");
            return null;
        };

        if (!movieInfo.getCacheSaveFlag()) {
            return null;
        };

        if ("false".equals(requestHeader.getParameter("nicocachenl_save"))) {
            return null;
        };

        AV av = AV.UNSPECIFIED;
        if ("audio".equals(avword)) {
            av = AV.AUDIO;
        } else if ("video".equals(avword)) {
            av = AV.VIDEO;
        };

        if (av.isUnspecified()) {
            Logger.info("引数が\"audio\"でも\"video\"でもありません[" + avword + "]");
            return null;
        };

        NLEventSource eventSource = null;
        if (NLShared.INSTANCE.countSystemEventListeners() > 0) {
            eventSource = new NLEventSource(null, requestHeader,
                                            movieInfo.getCache());
        };

        // - チャンク読み込みの度に呼び出す.
        // - 関連付けられた処理は一度のみ走る.
        // - これをトリガーにnltmp_smXXX*.hlsが作られm3u8が保存される.
        movieInfo.chunkLoadStart();

        // 現行配信では EXT-X-MAP の初期化チャンク名が init で始まり、
        // media segment だけが暗号化される。この命名規約から外れる応答は
        // 復号処理の長さ・padding検証で保存を中止する。
        // - init以降には数値1. 左0パディングは動画チャンク数の桁数で決まる.
        //   例: init1.cmfa, init01.cmfv, init001.cmfv
        // - trueになる想定filenameは01.cmfv, 999.cmfv, 01.cmfa, 123.cmfaなど.
        boolean needDecrypt = !(filename.startsWith("init"));

        URLResource serverResource;
        try {
            Cache.incrementDL(movieInfo.getVideoDescriptor());

            String url = requestHeader.getURI();
            serverResource = new URLResource(url);
            serverResource.setFollowRedirects(true);

            ChunkListener x = new ChunkListener(
                movieInfo, av, filename, needDecrypt, eventSource, executor);
            if (url.contains("/shlsbid/")) {
                // - shlsbid動画は仕様が違う.
                // - 一部のログを非表示に.
                x.setShlsbid(true);
            };

            serverResource.addTransferListener(x);
        } catch (RuntimeException e) {
            Logger.error(e);
            throw e;
        };
        return serverResource;
    };


    private static void setCors(Resource r, HttpRequestHeader requestHeader) {
        if (requestHeader.getMessageHeader("Origin") != null) {
            r.setResponseHeader("Access-Control-Allow-Credentials", "true");
            r.setResponseHeader("Access-Control-Allow-Origin", requestHeader.getMessageHeader("Origin"));
        }
    }

    // "0x"で始まらない16進数文字列をバイト配列へ.
    // Extension からも参照可能な既存APIなので、このクラスに残してABIを維持する。
    // based on https://stackoverflow.com/questions/140131/
    //          author: https://stackoverflow.com/users/3093/dave-l
    public static byte[] hexStringToByteArray(String s)
        throws NumberFormatException {

        if (s == null || (s.length() & 1) != 0) {
            throw new NumberFormatException("hex string must contain pairs of digits");
        }
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int x1 = (byte)Character.digit(s.charAt(i), 16);
            int x2 = (byte)Character.digit(s.charAt(i+1), 16);
            if (x1 == -1 || x2 == -1) {
                throw new NumberFormatException();
            };
            data[i / 2] = (byte)((x1 << 4) + x2);
        };
        return data;
    };
};


// - Runnableを指定のexecutorでexecuteするようにするためのラッパークラス.
// - 復号情報取得時に別スレで復号を開始させるために定義.
// - 同期的に復号すると鍵要求の応答が遅れるから.
// - 動詞的なクラス名ってどうなの.
class AsyncExecute implements Runnable {
    Runnable runnable;
    Executor executor;
    public AsyncExecute(Runnable runnable, Executor executor) {
        this.runnable = runnable;
        this.executor = executor;
    };

    @Override
    public void run() {
        executor.execute(runnable);
    };
};


class ChunkListener implements TransferListener, Runnable {
    Cache cache;
    DomandCVIEntry movieInfo;
    NLEventSource eventSource;
    Executor executor;

    // - ローカル側セグメントファイル名.
    // - 個別キャッシュ(smXXX...hls/)ディレクトリからの相対パス.
    // - 例: video/01.cmfv, audio/02.cmfa
    String filenameRel;
    File tmpCacheDir; // <- tmpCacheDir
    File chunkavDir; // <- div
    File file;

    // - 通信をここに溜める.
    // - 書き込み量がcontent-lengthに満ちたら復号を始める.
    // - 復号が不要な場合はそのまま本番ファイルに名前変更する.
    // - 復号に必要な情報が揃っていなければ、movieInfo.onGotAudioKey
    //   or onGotVideoKeyへ登録.
    // - そして鍵取得時にもう一度実行.
    File partFile;
    FileChannel partChannel = null;
    RandomAccessFile partRAF = null;
    File decryptedFile;

    boolean needDecrypt;
    Cipher decrypter = null;

    boolean errorOccurred;

    // - サーバーから予告されるコンテンツ長.
    // - 復号が必要な場合においては復号前のコンテンツ長.
    // - 復号が不必要な場合はコンテンツ長.
    long contentLength = 0;

    // - 通常のhttp 200受信の場合は0から受信した量を数える.
    // - partial content受信の場合はヘッダが示す開始位置から数える.
    // - この値がcontentLengthと一致したら全て受信した状態.
    int contentPos = 0;

    AV av;

    // - trueならshlsbid動画(珍しい). falseならhlsbid動画(普通).
    // - "/hlsbid/"となっているところが"/shlsbid/"となっているURL群で
    //   配信される動画がある(主にsoXXX).
    // - これらはレスポンスの仕様からして違う.
    // - このフラグはログ表示にのみ関わる.
    boolean shlsbid = false;

    public ChunkListener
    (DomandCVIEntry movieInfo, AV av, String filename, boolean needDecrypt
     , NLEventSource eventSource, Executor executor) {

        // 必須フィールドを初期化した上で無効なlistenerをno-opにするため、
        // 段階ごとにerrorOccurredを確認する。

        this.errorOccurred = av.isUnspecified();
        this.av = av;

        this.movieInfo = movieInfo;
        this.eventSource = eventSource;
        this.executor = executor;

        this.cache = movieInfo.getCache();

        this.tmpCacheDir = cache.getCacheTmpFile();

        // - 存在しない場合はnullが返る.
        // - ここで上記tmpCacheDirのテストもしている.
        this.chunkavDir = CmafCachingProcessor.getTmpStreamCmafavDirectory(
            cache, av.isAudio() ? "audio" : "video");

        if (chunkavDir == null) {
            // 途中でキャッシュコンプリートされているだけなら、ログしない.
            if (!cache.exists()) {
                Logger.info("error: chunkavDir==null: " + movieInfo.getSmid() + ": "
                            + filename + ": " + cache.getCacheFileName());
            };
            this.errorOccurred = true;
        };

        if (!errorOccurred && !chunkavDir.exists()) {
            if (cache.exists()) {
                // - 途中でキャッシュコンプリートされた.
                // - あるいはキャッシュ済なのにキャッシュ保存をしている.
                // - エラー出さない.
                // do nothing.
                this.errorOccurred = true;
                Logger.debug("(cmaf)already cached: " + movieInfo.getVideoDescriptor());
            }
            else if (!chunkavDir.mkdir()) {
                Logger.info("error: mkdir failed: " + chunkavDir);
                this.errorOccurred = true;
            };
        };

        if (!errorOccurred) {
            this.file = new File(this.chunkavDir, filename);

            // 個別キャッシュディレクトリからの相対パス.
            this.filenameRel = this.tmpCacheDir.toURI()
                .relativize(this.file.toURI()).toString();

            if (file.exists()) {
                // - このファイルはもうキャッシュする必要はないというフラグ.
                // - 本当はエラーじゃないからメッセージも出さない.
                this.errorOccurred = true;
                mightEndCache();
            };
        };

        // 既エラーではない場合のみログを出す.
        if (!errorOccurred && filenameRel.startsWith("file:")) {
            this.errorOccurred = true;
            Logger.info("-- 'file:'表現出現:tmpCacheDir:<"
                        + this.tmpCacheDir.toURI() + ">"
                        + ",file:<" + this.file.toURI() + ">"
                        + ",chunkavDir:<" + this.chunkavDir + ">");
        };

        // この一時ファイルが有効であるのは現在のjava仮想マシンが動いている
        // 間だけ. smXXX[]_titleフォルダ下よりも共通の一時ファイルディレクト
        // リ(を作ってそこ)に置いた方がゴミが残りにくいだろう.
        this.decryptedFile = new File(this.tmpCacheDir, "tmpcmfD_" + filename);
        this.decryptedFile.deleteOnExit(); // 期待しない.

        this.partFile = new File(this.tmpCacheDir, "tmpcmfP_" + filename);
        this.partFile.deleteOnExit(); // 期待しない.

        this.needDecrypt = needDecrypt;

        // Logger.info(String.format("--- cachedir: %s: %s", this.tmpCacheDir.exists() ? "exists" : "not found", this.tmpCacheDir));
    };

    // - 複数回呼び出される.
    // - decrypterが利用可能状態ならtrue.
    private boolean initDecrypter() {
        if (this.decrypter != null) {
            return true;
        };

        try {
            if (needDecrypt) {
                byte[] iv = getIV(movieInfo, av, filenameRel.substring(
                    filenameRel.lastIndexOf('/') + 1));
                byte[] key = getEncKey(movieInfo, av, filenameRel.substring(
                    filenameRel.lastIndexOf('/') + 1));
                this.decrypter = createDecrypter(iv, key, av);
            };
        } catch (NoSuchAlgorithmException e) {
            error(e); // 高確率でコーディングミス.
        } catch (NoSuchPaddingException e) {
            error(e); // 高確率でコーディングミス.
        } catch (InvalidAlgorithmParameterException e) {
            error(e); // 高確率でコーディングミス.
        } catch (InvalidKeyException e) {
            error(e); // 通信の化けか？
        };
        return this.decrypter != null;
    };

    private void error() {
        errorOccurred = true;
        close(false);
    };

    private void error(Exception e) {
        errorOccurred = true;
        close(false);
        Logger.error(e);
    };

    private void error(String msg) {
        errorOccurred = true;
        close(false);
        Logger.info(msg);
    };

    private void errorwarning(String msg) {
        errorOccurred = true;
        close(false);
        Logger.warning(msg);
    };

    private void close(boolean deletePart) {
        CloseUtil.close(partChannel);
        CloseUtil.close(partRAF);
        partChannel = null;
        partRAF = null;

        if (deletePart) {
            if (partFile.exists()) {
                partFile.delete();
            };
        };
    };

    private static byte[] getIV(
        DomandCVIEntry movieInfo, AV av, String filename) {
        if (av.isAudio()) {
            return movieInfo.getAudioIV(filename);
        } else if (av.isVideo()) {
            return movieInfo.getVideoIV(filename);
        };
        return null;
    };

    private static byte[] getEncKey(
        DomandCVIEntry movieInfo, AV av, String filename) {
        if (av.isAudio()) {
            return movieInfo.getAudioKey(filename);
        } else if (av.isVideo()) {
            return movieInfo.getVideoKey(filename);
        };
        return null;
    };

    private static Cipher createDecrypter
    (byte[] ivbytes, byte[] keybytes, AV av)
        throws NoSuchAlgorithmException, NoSuchPaddingException
        , InvalidKeyException, InvalidAlgorithmParameterException
    {
        if (av == AV.UNSPECIFIED) {
            throw new IllegalArgumentException(
                    "audio/video type is required for decryption");
        };
        if (ivbytes == null || keybytes == null) {
            return null;
        };

        // - [RFC 8216 HTTP Live Streaming(HLS)]ではPKCS7を求めている.
        // - このPKCS5Paddingは実質PKCS7だから誤りではない.
        Cipher decrypter = Cipher.getInstance("AES/CBC/PKCS5Padding");

        IvParameterSpec iv = new IvParameterSpec(ivbytes);
        SecretKeySpec key = new SecretKeySpec(keybytes, "AES");

        decrypter.init(Cipher.DECRYPT_MODE, key, iv);
        // InvalidKeyException, InvalidAlgorithmParameterException

        return decrypter;
    };

    // "bytes 10-20/30"ならreturn [10,20,30].
    private int[] parseSingleContentRange
    (List<String> contentRangeList) {

        if (contentRangeList.size() == 0) {
            errorwarning("不明なpartial contentです(0): " + filenameRel);
            return null;
        };

        if (contentRangeList.size() != 1) {
            errorwarning(
                "<206 multipart partial content> was responded: "
                + filenameRel + ": '" + contentRangeList + "'");
            return null;
        };

        String contentRange = contentRangeList.get(0);
        if (!contentRange.startsWith("bytes ")) {
            errorwarning("不明なpartial contentです(1): " + filenameRel
                         + "'" + contentRange + "'");
            return null;
        };

        // "bytes 10-20/30" → "10-20/30"
        String ablstr = contentRange.substring("bytes ".length());
        // "10-20/30" → "10-20", "30"
        String[] ab_l = ablstr.split("/");
        if (ab_l.length != 2) {
            errorwarning("不明なpartial contentです(2): " + filenameRel
                         + "'" + contentRange + "'");
            return null;
        };

        // "10-20" → "10", "20"
        String[] a_b = ab_l[0].split("-");
        if (a_b.length != 2) {
            errorwarning("不明なpartial contentです(3): " + filenameRel
                         + "'" + contentRange + "'");
            return null;
        };

        try {
            int a = Integer.parseInt(a_b[0]);
            int b = Integer.parseInt(a_b[1]);
            int l = Integer.parseInt(ab_l[1]);
            return new int[]{a, b, l};
        } catch (NumberFormatException e) {
            Logger.error(e);
        };
        errorwarning("不明なpartial contentです(4): " + filenameRel
                     + ": '" + contentRange + "'");
        return null;
    };

    private void prepareForPartialContent(HttpResponseHeader responseHeader) {

        List<String> contentRanges =
            responseHeader.getMessageHeadersOfName("Content-Range");
        int[] abl = parseSingleContentRange(contentRanges);

        if (abl == null) {
            // エラーはもう出している.
            return;
        };

        contentLength = abl[2];
        int start = abl[0];
        int end = abl[1] + 1; // +1で開区間を閉区間へ.

        openPartFileForWrite();

        try {
            // start == 現在長は、前回受信分の直後から続ける正常な Range 応答。
            // これを失敗扱いにすると最後のチャンクだけ残り、54/55 (98%) の
            // ような状態で HLS キャッシュが完了しない。
            if (start > partRAF.length()) {
                errorwarning(
                    "未取得位置から開始するpartial contentです: "
                    + filenameRel + ": 取得済み[" + partRAF.length()
                    + "] Content-Range[" + contentRanges.get(0) + "]");
                return;
            };
        } catch (IOException e) {
            errorwarning("ファイルサイズ取得失敗: " + partFile);
            return;
        };

        try {
            partRAF.seek(start);
            contentPos = start;
        } catch (IOException e) {
            errorwarning("seek失敗: " + partFile);
            return;
        };

        Logger.info(filenameRel + ": 再開 " + start + "-" + end);
    };

    private void openPartFileForWrite() {
        if (partRAF != null || partChannel != null) {
            errorwarning("error: partRAF!=null||partChannel!=null: "
                         + "コーディングミス");
            return;
        };

        try {
            partRAF = new RandomAccessFile(partFile, "rw");
        } catch (FileNotFoundException e) {
            errorwarning("書き込みopen失敗: " + partFile);
            return;
        };
        partChannel = partRAF.getChannel();
    };

    private void decryptAsync() {
        // ダウンロードした動画チャンクの復号処理を非同期で行なう.
        if (initDecrypter()) {
            // 復号情報の用意完了. run()呼び出し.
            executor.execute(this);
        } else {
            // まだ復号情報の用意が出来ていない. run()呼び出しを予約.
            Runnable async = new AsyncExecute(this, executor);
            if (av.isAudio()) {
                movieInfo.addAudioDecryptInfoListener(async);
            } else {
                movieInfo.addVideoDecryptInfoListener(async);
            };
        };
    };

    // - decryptしてその結果をfileへ.
    // - DL中フラグを下げる.
    // - 必要ならキャッシュコンプリート処理.
    @Override
    public void run() {
        // - この処理はRunnableとしてmovieInfo.gotAudioDecryptInfoListenersあるいは
        //   gotVideoDecryptInfoListeners経由で呼ばれる. 呼び出し機序はDomandCVIEntry.javaを参照.
        // - あるいはexecutor.execute経由で呼ばれる.

        if (errorOccurred) {
            return;
        };

        if (decrypter == null) {
            if (!initDecrypter()) {
                Cache.decrementDL(movieInfo.getVideoDescriptor());
                close(false);
                Logger.info(filenameRel + ": 復号準備失敗");
                return;
            };
        };

        decrypt();

        if (errorOccurred) {
            Cache.decrementDL(movieInfo.getVideoDescriptor());
            close(false);
            return;
        };

        partFile.renameTo(file);
        if (!file.exists()) {
            // - cache store済ならエラーではない.
            if (!movieInfo.getCompletedFlag()) {
                errorwarning("移動失敗(pf): '" + partFile + "' → '"+ file + "'");
            };
            Cache.decrementDL(movieInfo.getVideoDescriptor());
            close(false);
            return;
        };
        partFile.delete();

        mightEndCache();
    };

    // - partFileの中身を復号してtmpFileへ.
    // - 復号失敗時はpartFileもtmpFileも削除.
    // - partRAF,partChannelはcloseされていること.
    // - partFileのサイズチェックはされていること.
    private void decrypt() {

        FileInputStream partIStream;
        FileChannel partIChannel;

        FileChannel decryptedChannel = null; // <- cacheChannel
        FileOutputStream decryptedOStream = null;

        // - IllegalBlockSizeExceptionが起きた時のためにそれを表示するため
        //   だけにdecrypterに入力した暗号文サイズを数える。
        int decrypterInputCounter = 0;

        byte[] rawbuf = new byte[1024 * 4]; // 効率良さげなサイズ.
        ByteBuffer bytebuf = ByteBuffer.wrap(rawbuf);

        try {
            partIStream = new FileInputStream(partFile);
            partIChannel = partIStream.getChannel();
        }
        catch (FileNotFoundException e) {
            // 成果ファイルが既にあるならログしない.
            if (file.exists() || !movieInfo.getCacheSaveFlag()) {
                // do nothing.
            } else {
                error(e);
            };
            return;
        };

        try {
            long partLength = partIChannel.size();
            if (partLength != contentLength) {
                String s = String.format(
                    "%s: データ長不一致;コーディングミス. "
                    + "content-length[%d] ファイル長[%d]"
                    , filenameRel, contentLength, partLength);
                errorwarning(s);
                CloseUtil.close(partIChannel);
                CloseUtil.close(partIStream);
                return;
            };
        } catch (IOException e) {
            errorwarning("入力ファイルチェック失敗");
            CloseUtil.close(partIChannel);
            CloseUtil.close(partIStream);
            return;
        };

        try {
            decryptedOStream = new FileOutputStream(decryptedFile);
            decryptedChannel = decryptedOStream.getChannel();
        } catch (FileNotFoundException e) {
            error(e);
            close(false); // partファイルの削除はしない.
            CloseUtil.close(partIChannel);
            CloseUtil.close(partIStream);
            return;
        };

        // partから読み取って復号してdecryptedへ書き込むループ.
        try {
            long reads = 0;
            for (;;) {
                reads = partIChannel.read(bytebuf);
                if (reads <= 0) {
                    break;
                };
                byte[] deced = decrypter.update(rawbuf, 0, (int)reads);
                decryptedChannel.write(ByteBuffer.wrap(deced));

                decrypterInputCounter += (int)reads;
                bytebuf.clear();
            };
            CloseUtil.close(partIChannel);
            CloseUtil.close(partIStream);
            partFile.delete();
        } catch (IOException e) {
            error(e);
            close(true); // trueでpartファイルの削除もする.
            CloseUtil.close(partIChannel);
            CloseUtil.close(partIStream);
            CloseUtil.close(decryptedChannel);
            CloseUtil.close(decryptedOStream);
            decryptedFile.delete();
            return;
        };

        // 復号終了処理.
        byte[] deced;
        try {
            deced = decrypter.doFinal();
        } catch (IllegalBlockSizeException e) {
            // - AES-128では入力総バイト数が16の倍数ではないという例外.
            String s = String.format(
                "%s: %s. content-length[%d%%16=%d] input-count[%d%%16=%d]"
                , filenameRel, "IllegalBlockSizeException"
                , contentLength, contentLength % 16
                , decrypterInputCounter, decrypterInputCounter % 16);
            errorwarning(s);
            CloseUtil.close(partIChannel);
            CloseUtil.close(partIStream);
            CloseUtil.close(decryptedChannel);
            CloseUtil.close(decryptedOStream);
            decryptedFile.delete();
            return;
        } catch (BadPaddingException e) {
            errorwarning(file + ": " + e.toString());
            CloseUtil.close(partIChannel);
            CloseUtil.close(partIStream);
            CloseUtil.close(decryptedChannel);
            CloseUtil.close(decryptedOStream);
            decryptedFile.delete();
            return;
        };

        try {
            decryptedChannel.write(ByteBuffer.wrap(deced));
        } catch (IOException e) {
            errorwarning(file + ": " + e.toString());
            CloseUtil.close(partIChannel);
            CloseUtil.close(partIStream);
            CloseUtil.close(decryptedChannel);
            CloseUtil.close(decryptedOStream);
            decryptedFile.delete();
            return;
        };

        if (CloseUtil.close(decryptedChannel) &&
            CloseUtil.close(decryptedOStream)) {
            // do nothing
        } else {
            errorwarning(decryptedFile + ": close失敗");
            decryptedFile.delete();
            return;
        };

        long decryptedLength = decryptedFile.length();
        if (decryptedLength > contentLength) {
            errorwarning(String.format(
                    "%s: 復号後データが暗号文より長いため保存を中止: %d > %d",
                    filenameRel, decryptedLength, contentLength));
            decryptedFile.delete();
            return;
        }

        decryptedFile.renameTo(file);
        if (!file.exists()) {
            errorwarning("移動失敗(df): '" + decryptedFile + "' → '"+ file
                         + "'");
            return;
        };
        decryptedFile.delete();
    };

    // - close成功でtrue.
    // - 両者null時もtrue.
    private boolean closePartFile() {
        boolean result =
            CloseUtil.close(partChannel) &&
            CloseUtil.close(partRAF);
        partChannel = null;
        partRAF = null;
        return result;
    };

    // - debugとメッセージ用.
    private boolean isFirstCmfv() {
        return filenameRel.contains("/1.cmfv")
            || filenameRel.contains("/01.cmfv")
            || filenameRel.contains("/001.cmfv")
            || filenameRel.contains("/0001.cmfv")
            || filenameRel.contains("/00001.cmfv");
    };

    public void setShlsbid(boolean x) {
        shlsbid = x;
    };

    @Override
    public void onResponseHeader(HttpResponseHeader responseHeader) {

        if (errorOccurred) {
            return;
        };

        int statusCode = responseHeader.getStatusCode();

        // - Content-Lengthは通信のボディサイズを表すが、contentLength
        //   プロパティは完成形のファイルサイズを表す.
        // - そのため206ではこの値は変更される.
        contentLength = responseHeader.getContentLength();

        if (statusCode == 304) {
            // ファイルが既にあるなら通知しない.
            if (! file.exists()) {
                errorwarning("<304 not modified>応答: "
                             + filenameRel);
            };
            // Logger.info(responseHeader.toString());
            return;
        };

        if (contentLength == -1) {
            // - contentLength==-1をshlsbid動画のフラグとして使う.
            // - shlsbid動画の場合に、Content-Lengthがないヘッダが送られて
            //   来る.
            // - shlsbid動画は206が来ない.
            // - ログだけ出して非エラーしておく.
            if (!shlsbid) {
                Logger.info("no content-length header: " + filenameRel);
            };
        } else if (contentLength < 0) {
            Logger.info("Invalid Content-Length[" + contentLength + "]: "
                        + filenameRel);
        };

        if (statusCode == 206) {
            // - partial content
            // - この経路ではpartFileを事前に削除しない.
            prepareForPartialContent(responseHeader);
            return;
        };

        if (statusCode != 200) {
            errorwarning("Invalid status code[" + statusCode + "]: " + filenameRel);
            return;
        };

        openPartFileForWrite();
    };

    @Override
    public void onTransferBegin(OutputStream receiverOut) {
        // do nothing
    };

    @Override
    public void onTransferring(byte[] input, int length) {

        if (errorOccurred) {
            return;
        };

        ByteBuffer bb = ByteBuffer.wrap(input, 0, length);
        try {
            partChannel.write(bb);
            contentPos += length;
        } catch (IOException e) {
            errorwarning(filenameRel + ": 書き込み失敗");
        };
        return;
    };

    @Override
    public void onTransferEnd(boolean completed) {

        if (errorOccurred) {
            // - hls版からコメント転記.
            // - [nl] DL中フラグを消す.
            // - Cache#store()後じゃないとExtension等に不具合が出るので注意.
            // - Cache#store()でVideoDescriptorが差し替わることに注意.
            // - 差し替わり前のvideoDescriptorを対象にdecrementする.
            Cache.decrementDL(movieInfo.getVideoDescriptor());
            close(false);
            return;
        };

        if (!closePartFile()) {
            errorwarning(file + ": partFile close失敗");
            Cache.decrementDL(movieInfo.getVideoDescriptor());
            close(false);
            return;
        };

        if (completed && contentLength == -1) {
            // - contentLength==-1をshlsbid動画のフラグとして使う.
            // - content-lengthなしで送られて来ていて、なおかつ今completed
            //   ならば、それを信頼する.
            contentLength = contentPos;
        }
        else if (contentPos > contentLength) {
            String s = String.format(
                "%s: 過剰なコンテンツボディ: 予告[%d], 応答[%d]"
                , filenameRel, contentLength, contentPos);
            errorwarning(s);
            Cache.decrementDL(movieInfo.getVideoDescriptor());
            close(false);
            return;
        }
        else if (contentPos != contentLength) {
            String s = String.format(
                "%s: 未完 %d/%d", filenameRel, contentPos, contentLength);
            errorwarning(s);
            Cache.decrementDL(movieInfo.getVideoDescriptor());
            close(false);
            return;
        };

        if (needDecrypt) {
            decryptAsync();
            return;
        };

        // 別スレッドが上書きした可能性も想定して、existsによる確認をする.
        partFile.renameTo(file);
        if (!file.exists()) {
            errorwarning("移動失敗(ppf): '" + partFile + "' → '"+ file + "'");
            Cache.decrementDL(movieInfo.getVideoDescriptor());
            close(false);
            return;
        };
        partFile.delete();

        mightEndCache();
    };

    private void mightEndCache() {

        // プレイリスト更新時に残る古いセグメント記録を捨て、実ファイルから
        // 再構築してから完了通知する。これにより再読み込み待ちをなくす。
        CmafCachingProcessor.rescanCachedSegments(movieInfo);

        // 必要に応じてこの先でcache storeされる.
        movieInfo.addCachedSegment(filenameRel);

        // 音声・映像を統合済みなら、統合先を失った別画質用の nltmp を残さない。
        if (movieInfo.getCompletedFlag()) {
            CmafCachingProcessor.removeOrphanedFamilyTmp(movieInfo);
        };

        if (Boolean.getBoolean("showCaching")) {
            reportCachingProgress();
        };
    };

    private void reportCachingProgress() {
        Cache cache = movieInfo.getCache();
        long cachedSegments = cache.tmpCachedSize();
        long totalSegments = cache.tmpFinalSize();
        if (cachedSegments < 0 || totalSegments <= 0) {
            Logger.info("caching " + movieInfo.getSmid());
            return;
        };

        File tmpDir = cache.getCacheTmpFile();
        long cachedBytes = getDirectorySize(tmpDir);
        double percentage = (double)cachedSegments / totalSegments * 100;
        Logger.info(String.format(
            "caching %s: %s (%d/%d segments, %.0f%%)",
            movieInfo.getSmid(), TextUtil.bytesToString(cachedBytes),
            cachedSegments, totalSegments, percentage));
    };

    private static long getDirectorySize(File file) {
        if (file == null || !file.exists()) {
            return 0;
        };
        if (!file.isDirectory()) {
            return file.length();
        };

        long size = 0;
        File[] files = file.listFiles();
        if (files == null) {
            return 0;
        };
        for (File child : files) {
            size += getDirectorySize(child);
        };
        return size;
    };

};
// End Class ChunkListener.


enum AV {
    AUDIO, VIDEO, UNSPECIFIED;
    public boolean isAudio() {
        return this == AUDIO;
    };
    public boolean isVideo() {
        return this == VIDEO;
    };
    public boolean isUnspecified() {
        return this == UNSPECIFIED;
    };
    public String toString() {
        if (isAudio()) {
            return "audio";
        };
        if (isVideo()) {
            return "video";
        };
        return "unspecified";
    };
};


@SuppressWarnings("serial")
class NoIdInfoException extends Exception {
};


class DomandCVIUtil {

    // - Hlsで言うMovieData(情報コンテナ)と同じ役割を持つ共有エントリーを作る.
    // - 実質的にはCMAFのサブプレイリストハンドラー.
    // - 2024-03-18: 音声と映像を分ける必要が出た.
    public static DomandCVIEntry initAndPutEntry(
        String entryKeyName
        , String videoType, String videoNumId, String videoHeightText
        , String audioKbpsText , String videoMode, String videoSrcId
        , String audioSrcId)
        throws NoIdInfoException, NumberFormatException {

        int videoHeight = Integer.parseInt(videoHeightText);
        int audioKbps = Integer.parseInt(audioKbpsText);

        String postfix = Cache.HLS;

        NicoIdInfoCache.Entry idInfo = NicoIdInfoCache.getInstance().get(videoNumId);
        if (idInfo == null) {
            throw new NoIdInfoException();
        };

        // - srcIdがnullである方は非lowとして扱う.
        boolean lowAccess = getLowAccess(idInfo, videoSrcId, audioSrcId);

        // - dmcLow(360p-lowestのようなもの. これは360pとは違う.)かどうか
        //   はVideoDescriptorのコンストラクタ内でvideoModeから判定される.
        // - dmc時代のdmcLowは1種類だった(らしい)が現在(2025年)は違う.
        //   正常に対応出来ているか怪しい. 要検証.
        VideoDescriptor videoDescriptor = getVideoDescriptor(
            videoType + videoNumId, postfix, lowAccess, videoMode, audioKbps
            , /*srcid*/"");

        Cache cache = getCache(idInfo, videoDescriptor, lowAccess);

        DomandCVIEntry entry = new DomandCVIEntry(
            entryKeyName, videoType, videoNumId, videoHeight, audioKbps
            , videoMode, videoSrcId, audioSrcId, lowAccess, postfix
            , idInfo, videoDescriptor, cache);

        NLShared.INSTANCE.getDomandCVIManager().update(entry);

        return entry;
    };

    // - lowとは最高画質ではないか最高音質ではないということ.
    // - videoSrcIdがnullである場合、画質がlowであるかどうかを判定しない.
    // - audioSrcIdがnullである場合、音質がlowであるかどうかを判定しない.
    private static boolean getLowAccess(
        NicoIdInfoCache.Entry idInfo, String videoSrcId, String audioSrcId) {
        // DMCのメソッドを使っているのは誤りではない.
        if (videoSrcId != null) {
            Boolean low = idInfo.getDmcVideoEconomy(videoSrcId);
            if (null != low && low) {
                return true;
            };
        };
        if (audioSrcId != null) {
            Boolean low = idInfo.getDmcAudioEconomy(audioSrcId);
            if (null != low && low) {
                return true;
            };
        };
        return false;
    };

    private static VideoDescriptor getVideoDescriptor(
        String smid, String postfix, boolean lowAccess, String videoMode,
        int audioKbps, String srcId) {
        // - DMCのメソッドを使っているのは誤りではない.
        // - videoBitrate使用はDMCよりも前に廃止された.
        VideoDescriptor vd = VideoDescriptor.newDmc(
            smid, postfix, lowAccess, videoMode, /*videoBitrate*/0
            , audioKbps, srcId);
        VideoDescriptor regvd = Cache.getRegisteredVideoDescriptor(vd);
        if (regvd != null) {
            return regvd;
        };
        return vd;
    };

    private static Cache getCache(
        NicoIdInfoCache.Entry idInfo, VideoDescriptor vd
        , boolean lowAccess) {

        Cache cache;
        if (idInfo == null) {
            cache = new Cache(vd);
        }
        else {
            cache = new Cache(vd, idInfo.getTitle());
        };
        if (!lowAccess) {
            cache.unmarkLow();
        };
        return cache;
    };
};

/**
 * キャッシュ用データ管理クラス
 */
class DomandMovieData {
    private String smid; // sm,so,nm付きの動画番号ID.
    // HLS版のbitrateの単位はキロ. こちらではそれを明記する.
    private int audioKbps;
    private int videoHeight;
    private String postfix; // 拡張子
    private NicoIdInfoCache.Entry idInfo; // videoNumId と紐付いている情報.
    private VideoDescriptor videoDescriptor;
    private Cache cache;
    private String videoType; // sm,so,nmなど.
    private String videoNumId; // smなどを除いた動画番号.
    private boolean lowAccess; // 最上のvideoと最上のaudioならばfalse.
    private String videoMode; // 例: "1080p"
    // domand仕様に"360p_low"はない(2024-03).

    public DomandMovieData(
        String videoType, String videoNumId, String videoHeight, String audioKbps
        , String videoMode, String videoSrcId, String audioSrcId)
        throws NoIdInfoException, NumberFormatException {

        this.videoType = videoType;
        this.videoNumId = videoNumId;
        this.smid = videoType + videoNumId;
        initIdInfo(videoNumId);
        this.postfix = Cache.HLS;
        this.audioKbps = Integer.parseInt(audioKbps);
        this.videoHeight = Integer.parseInt(videoHeight);
        initLowAccess(videoSrcId, audioSrcId);
        this.videoMode = videoMode;
        initCache(this.idInfo, this.videoMode);
    };

    private void initIdInfo(String videoNumId) throws NoIdInfoException {
        this.idInfo = NicoIdInfoCache.getInstance().get(videoNumId);
        if (this.idInfo == null) {
            throw new NoIdInfoException();
        };
    };

    private void initLowAccess(String videoSrcId, String audioSrcId) {
        // 引数例: "video-h264-1080p", "audio-aac-128kbps"

        // DMCのメソッドを使っているのは誤りではない.
        Boolean videoLow = idInfo.getDmcVideoEconomy(videoSrcId);
        Boolean audioLow = idInfo.getDmcAudioEconomy(audioSrcId);
        if (videoLow == null && audioSrcId == null) {
            this.lowAccess = false;
            return;
        };
        this.lowAccess = videoLow || audioLow;
    };

    private void initCache(NicoIdInfoCache.Entry idInfo, String videoMode) {
        this.videoDescriptor = VideoDescriptor.newDmc(
            smid, postfix, lowAccess, videoMode,
            /*videoBitrate*/0, audioKbps, /*srcid*/"");
        VideoDescriptor regVideoDesc =
            Cache.getRegisteredVideoDescriptor(this.videoDescriptor);
        if (regVideoDesc != null) {
            this.videoDescriptor = regVideoDesc;
        };
        if (this.idInfo == null) {
            // HLSにならってこう書く. ここを通ることはあるか？
            cache = new Cache(videoDescriptor);
        }
        else {
            cache = new Cache(videoDescriptor, idInfo.getTitle());
        };
        if (!this.lowAccess) {
            cache.unmarkLow();
        };
    };

    public Cache getCache() { return cache;};
    public String getSmid() { return smid;};
    public VideoDescriptor getVideoDescriptor() { return videoDescriptor;};
    public String getPostfix() { return postfix;};
    public NicoIdInfoCache.Entry getIdInfo() { return idInfo;};
    public String getVideoType() { return videoType;};
    public String getVideoId() { return videoNumId;};
    public int getVideoHeight() {return videoHeight;};
};
