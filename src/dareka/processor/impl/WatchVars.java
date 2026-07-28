package dareka.processor.impl;

import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.Main;
import dareka.NLConfig;
import dareka.common.LRUMap;
import dareka.common.Logger;
import dareka.common.TextUtil;
import dareka.common.json.*;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;

/**
 * [nl] watch ページをパースしてプレイヤーに設定する変数や動画情報の値を取得するクラス
 * <ul>
 * <li>原宿の場合は so.addVariable/var Video から取得 [廃止済み]
 * <li>GINZAの場合は watchAPIDataContainer から取得 [廃止済み]
 * <li>HTML5の場合は data-api-data から取得 [2024-06までで廃止済み]
 * <li>「将来的にニコ動が HTML5 ベースになる場合もこちらで吸収」
 *     →できた．なるほど神じゃねーの
 * <li>同じくHTML5の場合は server-response から取得(2024-08からの仕様)
 * </ul>
 * @since NicoCache_nl+120609mod
 */
public class WatchVars {
    // HTML5
    private static final Pattern SERVER_RESPONSE_CONTAINER_PATTERN = Pattern.compile(
            "<meta name=\"server-response\" content=\"([^\"]+)\"");
    private static final Pattern DATA_API_DATA_CONTAINER_PATTERN = Pattern.compile(
            "data-api-data=\"([^\"]+)\"");

    private static final Pattern WATCH_ID_PATTERN = Pattern.compile(
            "([a-z]{2})?(\\d+)");
    private static final Pattern MAJOR_ERROR_PAGE_PATTERN = Pattern.compile(
            "<span>ログイン</span>|<h1>お探しの動画は(?:再生|視聴)できません</h1>|<h1>お探しの動画は視聴可能期間が終了しています</h1>|<h1>短時間での連続アクセスはご遠慮ください</h1>");

    // smid to WatchVars
    private static final Map<String, WatchVars> cache =
            Collections.synchronizedMap(new LRUMap<>());

    /**
     * WatchVars オブジェクトを取得する
     * <p>
     * 与えられた watch ページの内容から WatchVars オブジェクトを生成して返す。
     * 動画IDとタイトルが取得できた場合は {@linkplain NicoIdInfoCache} に記録する。
     * 更に生成したオブジェクトをキャッシュする。
     * <p>
     * 引数に https?:// から始まる文字列、もしくは12文字以下の文字列を与えた場合、
     * 与えられた文字列を元に動画IDを取得し、その動画IDに対応する WatchVars
     * オブジェクトがキャッシュに残っているならそちらを返す。
     * <p>
     * 常に最新の情報を取得したい場合は、watch ページの内容を渡して WatchVars
     * オブジェクトを生成する必要が有る。
     * なお、オブジェクトの生成にはある程度の時間が必要なので、Rewriter
     * 内部からの呼び出し等で事前に watch ページを通っている事が自明である場合、
     * キャッシュを利用した方がパフォーマンス的に有利である。
     *
     * @param content watch ページの内容、もしくは URL or 動画ID or スレッドID
     * @return WatchVars オブジェクト、キャッシュに残っていない場合のみ null
     */
    public static WatchVars get(String content) {
        if (content == null) {
            return null;
        }
        if (content.length() <= 12 || content.matches("https?://.*")) {
            int pos = content.lastIndexOf('/');
            if (pos > 0) {
                content = content.substring(pos + 1);
            }
            Matcher m = WATCH_ID_PATTERN.matcher(content);
            if (!m.find()) {
                return null;
            }
            String videoId = m.group();
            if (TextUtil.isThreadId(videoId)) {
                videoId = NLShared.INSTANCE.thread2smid(videoId);
                if (videoId == null) {
                    return null;
                }
            }
            return cache.get(videoId);
        }
        return new WatchVars(content);
    }

    public enum Type {
        None,
        @Deprecated Harajuku,
        @Deprecated Ginza,
        Html5,
        @Deprecated WatchApi,
    };

    private Type type = Type.None;

    private String content, data;
    private Matcher dataMatcher;
    private boolean rawJson = false;
    private JsonObject jsonRoot;
    private JsonObject json;
    private JsonObject dmcInfo;
    private JsonObject domandInfo; // dms(domand). CMAF. 2023-11からの動画仕様.
    private enum DeliveryType {
        CLASSIC,
        DMC,
        DOMAND
    }
    private DeliveryType deliveryType = DeliveryType.CLASSIC;
    private String videoId, videoTitle;
    private int videoIdNumber;
    private boolean deleted;
    private boolean isPeakTime, isPremium;
    private long duration;
    private String recipe_id = null;
    // private List<String> videos = null;
    // private List<String> audios = null;
    private Map<String, Boolean> qualityVideos = null;
    private Map<String, Boolean> qualityAudios = null;
    private HttpRequestHeader requestHeader;

    private WatchVars(String content) {
        this.content = content;
        dataMatcher = SERVER_RESPONSE_CONTAINER_PATTERN.matcher(content);
        if (dataMatcher.find()) {
            type = Type.Html5;
        } else {
            // 2024-08-06: 廃止済の機能だがまだ残しておく. 2024-08用実装が済んだら削除をすること.
            dataMatcher = DATA_API_DATA_CONTAINER_PATTERN.matcher(content);
            if (dataMatcher.find()) {
                type = Type.Html5;
            } else {
                dataMatcher = null;
            };
        }
        if (dataMatcher != null) {
            data = TextUtil.unescapeHTML(dataMatcher.group(1));
            jsonRoot = Json.parseObject(data);
            // Logger.info(jsonRoot.toJson());
        } else if (content.startsWith("{")) {
            data = content;
            jsonRoot = Json.parseObject(data);
            rawJson = true;
        } else {
            data = content;
        }

        json = null;
        if (jsonRoot != null && jsonRoot.getObject("data", "response") != null) {
            // 2024-08仕様.
            json = jsonRoot.getObject("data", "response");
        } else if (jsonRoot != null && jsonRoot.getObject("data") != null && jsonRoot.get("meta", "status") != null) {
            json = jsonRoot.getObject("data");
        } else {
            json = jsonRoot;
        }

        if (json != null) {
            if (rawJson) {
                if (json.getString("watchAuthKey") != null) {
                    return;
                } else if (json.getObject("video") != null && json.getObject("media") != null) {
                    type = Type.Html5;
                }
            }
            if (type == Type.Html5) {
                dmcInfo = json.getObject("media", "delivery"); // 廃止済み. 消すこと.
                // 2024-08仕様.
                domandInfo = json.getObject("media", "domand");
                deliveryType = domandInfo != null ? DeliveryType.DOMAND
                        : dmcInfo != null ? DeliveryType.DMC
                        : DeliveryType.CLASSIC;
                videoId = json.getString("video", "id");
                if (videoId != null) {
                    // titleとoriginalTitleの違いは何だろう
                    // originalDescriptionにはリンク処理後のHTML入っていたので
                    // titleも同様だとすればoriginalTitleを使うべき
                    videoTitle = json.getString("video", "title");
                    // Logger.info("videoTitle: " + videoTitle);
                    NicoCachingTitleRetriever.putTitleCache(videoId, videoTitle);

                    deleted = json.getBoolean("video", "isDeleted");

                    isPeakTime = json.getBoolean("system", "isPeakTime");
                    isPremium = json.getBoolean("viewer", "isPremium");

                    if (isDmcDelivery()) {
                        recipe_id = dmcInfo.getString("movie", "session", "recipeId");

                        // domandなら"video-h264-480p"みたいな要素の、
                        // DMCなら"archive_h264_1080p" みたいな要素のリスト.
                        // domandなら"audio-aac-192kbps"みたいな要素の、
                        // DMCなら"archive_aac_192kbps" みたいな要素のリスト.
                        qualityVideos = getQualityAvailability(dmcInfo.getArray("movie", "videos"));
                        qualityAudios = getQualityAvailability(dmcInfo.getArray("movie", "audios"));
                    }
                    if (isDomandDelivery()) {
                        qualityVideos = getQualityAvailability(domandInfo.getArray("videos"));
                        qualityAudios = getQualityAvailability(domandInfo.getArray("audios"));
                    }

                    // if (info != null) {
                    //     Logger.info("(info != null) "
                    //             + " " + (info.getArray("movie") != null ? "movie" : "Nmovie")
                    //             + " " + (info.getArray("movie", "videos") != null ? "videos" : "Nvideos")
                    //             + " " + (info.getArray("movie", "audios") != null ? "audios" : "Naudios"));
                    // }

                    JsonValue durationValue = json.get("video", "duration");
                    if (durationValue instanceof JsonNumber) {
                        duration = ((JsonNumber) durationValue).getLong();
                    }
                }
            }
            if (videoId != null) {
                videoIdNumber = Integer.parseInt(videoId.substring(2));
                analyzeSmile();
                analyzeDmc();
                analyzeDomand();
                cache.put(videoId, this);
            }
        } else {
            Matcher m = MAJOR_ERROR_PAGE_PATTERN.matcher(content);
            if (m.find()) {
                return;
            }

            Logger.warning("WatchVars: JSON not found.");
            if (Boolean.getBoolean("debugWatchVars")) {
                Logger.info(content);
            }
        }
        if (Boolean.getBoolean("debugWatchVars")) {
            dumpVars();
        }
    }

    private void dumpVars() {
        if (!Boolean.getBoolean("debugWatchVarsWithoutJson")) {
            Logger.info("WatchVars: JSON " + this.getJSON());
        }
        Logger.info("WatchVars: INFO " + videoId + " "
                + (type != Type.None ? type.toString() + " " : "")
                + (rawJson ? "RawJson " : "")
                + deliveryType.name().toLowerCase(Locale.ROOT) + " " + videoTitle);
        Logger.info("WatchVars: INFO2"
                + (isPremium ? " Premium" : " !Premium")
                + (isPeakTime ? " PeakTime" : " !PeakTime"));
        dumpDmcVars();
        StringBuilder sb = new StringBuilder("WatchVars: TAGS");
        for (String tag : getTags())
            sb.append(" ").append(tag);
        Logger.info(sb.toString());
    }

    private void dumpDmcVars() {
        if (deliveryType != DeliveryType.CLASSIC) {
            String dmcType="undefined", dmcId="undefined";
            if (isDmcDelivery()) {
                Logger.info("WatchVars: dmcInfo.recipe_id=" + recipe_id);
                Matcher m = null;
                if (recipe_id != null) {
                    m = Pattern.compile("nicovideo-(\\w{2})(\\d+)").matcher(recipe_id);
                }
                if (m != null && m.matches()) {
                    dmcType = m.group(1);
                    dmcId = m.group(2);
                } else {
                    Logger.info("WatchVars: invalid recipe_id");
                    dmcType = videoId.substring(0, 2);
                    dmcId = videoId.substring(2);
                }
            }
            else if (isDomandDelivery()) {
                dmcType = videoId.substring(0, 2);
                dmcId = videoId.substring(2);
            }

            NicoIdInfoCache.Entry info = NicoIdInfoCache.getInstance().get(dmcId);
            if (qualityVideos != null) {
                Logger.info("WatchVars: VIDEOS (all)");
                for (Map.Entry<String, Boolean> e : qualityVideos.entrySet()) {
                    Boolean low = info == null ? null : info.dmcVideoEconomy.get(e.getKey());
                    Logger.info("WatchVars: - " + (e.getValue() ? "" : "!") + e.getKey()
                            + (low == null ? " null" : low ? " low" : ""));
                }
            }
            // else if (videos != null) {
            //     Logger.info("WatchVars: VIDEOS (availables)");
            //     for (String video : videos) {
            //         Boolean low = info == null ? null : info.dmcVideoEconomy.get(video);
            //         Logger.info("WatchVars: - " + video
            //                 + (low == null ? " null" : low ? " low" : ""));
            //     }
            // }
            if (qualityAudios != null) {
                Logger.info("WatchVars: AUDIOS (all)");
                for (Map.Entry<String, Boolean> e : qualityAudios.entrySet()) {
                    Boolean low = info == null ? null : info.dmcAudioEconomy.get(e.getKey());
                    Logger.info("WatchVars: - " + (e.getValue() ? "" : "!") + e.getKey()
                            + (low == null ? " null" : low ? " low" : ""));
                }
            }
            // else if (audios != null) {
            //     Logger.info("WatchVars: AUDIOS (availables)");
            //     for (String audio : audios) {
            //         Boolean low = info == null ? null : info.dmcAudioEconomy.get(audio);
            //         Logger.info("WatchVars: - " + audio
            //                     + (low == null ? " null" : low ? " low" : ""));
            //     }
            // }
        }
    }

    private void analyzeSmile() {
        String videoSource = json.getString("video", "smileInfo", "url");
        if (videoSource == null) {
            videoSource = json.getString("legacy", "url");
        }
        if (videoSource == null) {
            return;
        }

        Matcher m_url = SM_FLV_PATTERN.matcher(videoSource);
        if (m_url.matches()) {
            // smidと動画IDが異なる場合にsmidでキャッシュするために記憶しておく
            String vid = m_url.group(2);
            String cid = videoId.substring(2);
            if (!vid.equals(cid) && !json.getBoolean("video", "isDeleted")) {
                if (NLConfig.matches("deletedVideoId", vid)) {
                    deleted = true;
                } else {
                    NLShared.INSTANCE.put_vid2cid(vid, cid);
                }
            }
        }
    }

    private static final Pattern SM_FLV_PATTERN = Pattern.compile(
            "^https?://(?!tn(?:-skr|\\.))[^/]+(?:smilevideo|nicovideo)\\.jp/"
                    + "smile\\?(\\w)=([^.]+)\\.\\d+(as3)?(low)?$");

    private void analyzeDomand() {
        if (!(isDomandDelivery()
              // && videos != null
              // && audios != null
              && videoId != null)) {
            return;
        }
        // - analyzeDmcがrecipe_idからtype(smとか)とid(動画番号)を得るのは、
        //   それがcache prefix id(cid)として最も信頼出来るからであるか？
        // - domand時点に於いてはvideoIdを信頼しても良いという前提で処理を書く.
        //   (cidとの紐づけを行なわないという意味)
        String type = videoId.substring(0, 2);
        String idnum = videoId.substring(2);
        NicoIdInfoCache.Entry info = NicoIdInfoCache.getInstance().get(idnum);
        if (info == null) {
            NicoIdInfoCache.getInstance().putOnlyTypeAndId(type, idnum);
            info = NicoIdInfoCache.getInstance().get(idnum);
        }
        NicoIdInfoCache.getInstance().sticky(idnum);

        if (qualityVideos != null && qualityAudios != null) {
            {
                boolean low = false;
                for (Map.Entry<String, Boolean> e : qualityVideos.entrySet()) {
                    info.dmcVideoEconomy.put(e.getKey(), low);
                    low = true;
                }
            }
            {
                boolean low = false;
                for (Map.Entry<String, Boolean> e : qualityAudios.entrySet()) {
                    info.dmcAudioEconomy.put(e.getKey(), low);
                    low = true;
                }
            }
        }
    }

    // - チャンネル動画等ではsmidと配信サーバーへリクエストするIDが異なる場合あり.
    // - 既にjsonから取得したrecipe_idからdmcType(smなど)とdcmId(動画番号)を得る.
    // - 登録がなければNicoIdInfoCacheにそれらを登録.
    // - 既にjsonから取得した品質リストから最高品質のものを除外してeconomy(low)として登録.
    // - 動画番号とキャッシュ用動画番号の紐づけをキャッシュする.
    // - deletedVideoIdってサイト側APIのgetflv(廃止済み)の問題だからもう関係ないよね？
    private void analyzeDmc() {
        if (isDmcDelivery()
            // && videos != null
            // && audios != null
            && recipe_id != null) {
            String dmcType, dmcId;
            Matcher m = Pattern.compile("nicovideo-(\\w{2})(\\d+)").matcher(recipe_id);
            if (m.matches()) {
                dmcType = m.group(1);
                dmcId = m.group(2);
            } else {
                Logger.warning("WatchVars: invalid recipe_id");
                dmcType = videoId.substring(0, 2);
                dmcId = videoId.substring(2);
            }

            NicoIdInfoCache.Entry info =
                    NicoIdInfoCache.getInstance().get(dmcId);
            if (info == null) {
                NicoIdInfoCache.getInstance().putOnlyTypeAndId(dmcType, dmcId);
                info = NicoIdInfoCache.getInstance().get(dmcId);
            }
            NicoIdInfoCache.getInstance().sticky(dmcId);
            if (qualityVideos != null && qualityAudios != null) {
                boolean low;
                low = false;
                for (Map.Entry<String, Boolean> e : qualityVideos.entrySet()) {
                    info.dmcVideoEconomy.put(e.getKey(), low);
                    low = true;
                }
                low = false;
                for (Map.Entry<String, Boolean> e : qualityAudios.entrySet()) {
                    info.dmcAudioEconomy.put(e.getKey(), low);
                    low = true;
                }
            }

            String vid = dmcId;
            String cid = videoId.substring(2);
            if (!vid.equals(cid) && !deleted) {
                if (NLConfig.matches("deletedVideoId", vid)) {
                    deleted = true;
                } else {
                    NLShared.INSTANCE.put_vid2cid(vid, cid);
                }
            }
        }
    }

    // 下記参照のidとその{動画,音声}をユーザーが取得出来るかどうかのMapを作る.
    // DMCなら引数は json.media.delivery.movie.{videos,audios} .
    // domand(DMS)なら引数は json.media.domand.movie.{videos,audios} .
    private Map<String, Boolean> getQualityAvailability(JsonArray jsonQuality) {
        if (jsonQuality == null) {
            return null;
        }
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (JsonValue o : jsonQuality.getList()) {
            if (!(o instanceof JsonObject)) {
                continue;
            }
            JsonObject obj = (JsonObject)o;
            // dmcなら"archive_aac_192kbps"とか"archive_h264_1080p"とか.
            // domandなら"video-h264-480p"とか"audio-aac-192kbps"とか.
            String id = obj.getString("id");
            if (id == null) {
                continue;
            }
            boolean available = obj.getBoolean("isAvailable");
            result.put(id, available);
        }
        return result;
    }

    private static String decodeURL(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (IllegalArgumentException e) {
            Logger.debug(e);
            return value;
        } catch (UnsupportedEncodingException e) {
            Logger.error(e);
        }
        return value;
    }

    private static String encodeURL(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (IllegalArgumentException e) {
            Logger.debug(e);
            return value;
        } catch (UnsupportedEncodingException e) {
            Logger.error(e);
        }
        return value;
    }

    /**
     * watchデータの種類を返す
     * @return データの種類
     */
    public Type getType() {
        return type;
    }

    /**
     * パースした JSON を再構築して返す
     * @return 再構築した JSON、パースできなかった場合は null
     */
    public String getJSON() {
        if (json != null) {
            return json.toJson();
        }
        return null;
    }

    /**
     * パースした JSON をオブジェクトで返す
     * @return 再構築した JSON、パースできなかった場合は null
     */
    public JsonObject getJsonObject() {
        return json;
    }

    /**
     * 動画IDを取得する
     * @return 動画ID、取得できない場合は null
     */
    public String getVideoId() {
        return videoId;
    }

    /**
     * 動画タイトルを取得する
     * @return 動画タイトル、取得できない場合は null
     */
    public String getVideoTitle() {
        return videoTitle;
    }

    /**
     * プレミアムでアクセスしているかを取得する
     * @return プレミアムなら true
     */
    public boolean isPremium() {
        return isPremium;
    }

    /**
     * 低画質になる時間帯にアクセスしているかを取得する
     * @return 低画質時間帯なら true
     */
    public boolean isPeakTime() {
        return isPeakTime;
    }

    /**
     * 動画が新形式かどうかを取得する
     * @return 新形式なら true
     */
    public boolean isDmc() {
        return isDmcDelivery();
    }

    private boolean isDmcDelivery() {
        return deliveryType == DeliveryType.DMC;
    }

    private boolean isDomandDelivery() {
        return deliveryType == DeliveryType.DOMAND;
    }

    /**
     * パースした dmcInfo をオブジェクトで返す
     * @return dmcInfo、パースできなかった場合は null
     */
    public JsonObject getDmcInfo() {
        return dmcInfo;
    }

    /**
     * 動画タグを取得する
     * @return 動画タグのリスト、取得できない場合は空リスト
     */
    public List<String> getTags() {
        ArrayList<String> tags = new ArrayList<>();
        if (json != null) {
            JsonValue v = null;
            if (type == Type.Html5) {
                v = json.get("tag", "items");
            }
            if (v instanceof JsonArray) {
                for (JsonValue vv : ((JsonArray) v).getList()) {
                    String tag = null;
                    if (type == Type.Html5) {
                        tag = vv.getString("name");
                    }
                    if (tag != null)
                        tags.add(tag);
                }
            }
        }
        return tags;
    }

    static HttpRequestHeader REQUEST_HEADER_RIAPI;
    static HttpResponseHeader RESPONSE_HEADER_HTML;
    static HttpResponseHeader RESPONSE_HEADER_JSON;
    static HttpResponseHeader RESPONSE_HEADER_PLAIN;
    static {
        try {
            REQUEST_HEADER_RIAPI = newRequestHeader(
                    "http://riapi.nicovideo.jp/api/");
            RESPONSE_HEADER_HTML = new HttpResponseHeader(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n");
            RESPONSE_HEADER_JSON = new HttpResponseHeader(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n");
            RESPONSE_HEADER_PLAIN = new HttpResponseHeader(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n");
        } catch (IOException e) {
            Logger.error(e);
        }
    }

    private static HttpRequestHeader newRequestHeader(String url) {
        HttpRequestHeader requestHeader = null;
        try {
            requestHeader = new HttpRequestHeader(
                    HttpHeader.GET + " " + url + " HTTP/1.1\r\n\r\n");
            requestHeader.setMessageHeader("User-Agent",
                    TextUtil.escapeHTML(Main.VER_STRING));
        } catch (IOException e) {
            Logger.error(e);
        }
        return requestHeader;
    }

    /**
     * コンストラクタに渡した watch ページの内容を取得する
     * @return watch ページの内容
     */
    public String getContent() {
        return content;
    }

    /**
     * watch ページ内の JSON に対して nlFilter で置換した内容を取得する
     * <p>
     * まず JSON 全体に対して nlFilter を適用した上で、更に JSON
     * データ部分の特定の値に対してエスケープを解除してから nlFilter
     * を適用し、再エスケープ処理を行う。
     * <p>
     * 対象となる JSON は以下の通り
     * <ul>
     * <li>watch API で取得した JSON
     * <li>watch ページの data-api-data 内の JSON
     * </ul>
     * <p>
     * 置換対象のデータ部分は以下の通り
     * <ul>
     * <li> description の値
     * </ul>
     *
     * @return JSON 内部を置換した watch ページの内容、
     * JSON を含まない場合は {@linkplain #getContent()} と同じ
     */
    public String getReplacedContent() {
        if (videoId != null) {
            if (requestHeader == null) {
                requestHeader = newRequestHeader(
                        "http://www.nicovideo.jp/watch/" + videoId);
            }
            if (type == Type.Html5) {
                replaceJSON("video", "description");
            }

            String replaced = addCacheIcon(jsonRoot.toJson());
            if (content != data && !rawJson) {
                StringBuilder sb = new StringBuilder(content);
                sb.replace(dataMatcher.start(1), dataMatcher.end(1),
                        TextUtil.escapeHTML(replaced));
                replaced = sb.toString();
            }
            return replaced;
        }
        return content;
    }

    // nlFilter を使って JSON 内の値を置換
    private void replaceJSON(String... args) {
        JsonValue v = this.getValue(args);
        if (v instanceof JsonString) {
            String s = v.toString();
            s = EasyRewriter.replace(null, s,
                    requestHeader, RESPONSE_HEADER_HTML);
            ((JsonString) v).setValue(s);
        }
    }

    // nlFilter を使ってキャッシュ情報を付加
    private String addCacheIcon(String data) {
        return EasyRewriter.replace(null, data,
                REQUEST_HEADER_RIAPI, RESPONSE_HEADER_JSON);
    }

    /**
     * watch ページ内の JSON から値を取得して文字列で返す
     * <p>
     * Zeroの場合は watchAPIDataContainer 内の JSON が対象となる。
     * 原宿の場合は so.addVariable の値を元に構築し、更に var Video の値を
     * videoDetail オブジェクトにマップした JSON が対象となる（プレイリスト形式）。
     * 原宿の場合は内部で擬似的に JSON にマップしているため以下の違いがある。
     * <ul>
     * <li>Zero では flashvars オブジェクト内の値が JSON オブジェクト直下にある
     * <li>Zero ではタグの扱いが tagList だが、原宿では tags/lockedTags となる
     * <li>Zero に存在する uploaderInfo と viewerInfo は無い
     * <li>数値や真偽値の扱いがリテラルの場合と文字列の場合がある
     * </ul>
     * <p>
     * JSON は階層構造なので、引数には取得対象の値を特定するための
     * メンバー文字列を階層構造順に複数指定する（そのため可変引数となる）。
     * 例えば、VideoDetail 内の description の値を取得したい場合、
     * {@code getString("VideoDetail", "description")} と呼び出す。
     * <p>
     * 引数を一つだけ指定した場合、その値が存在しない場合は flashvars から再取得する。
     * そのため、原宿にも対応するには flashvars を引数で直接指定しない方が良い。
     *
     * @param args 取得対象の値を特定するメンバー文字列
     * @return 取得対象の文字列、存在しない or 値自体がヌルの場合は null
     */
    @Deprecated
    public String getString(String... args) {
        if (json != null) {
            JsonValue v = getValue(args);
            if (v instanceof JsonNull) {
                return null;
            } else if (v != null) {
                return v.toString();
            }
        }
        return null;
    }

    private JsonValue getValue(String... args) {
        JsonValue v = json.get((Object[]) args);
        return v;
    }

    /**
     * watch ページ内の JSON から値を取得して真偽値で返す
     * @param args 取得対象の値を特定するメンバー文字列
     * @return 取得対象の値が存在し真と評価されるときに true
     * @see #getString(String...)
     */
    @Deprecated
    public boolean getBoolean(String... args) {
        if (json != null) {
            JsonValue v = getValue(args);
            if (v != null) {
                return v.toBoolean();
            }
        }
        return false;
    }

    /**
     * watch ページ内の JSON から値を取得して整数値(int)で返す
     * @param args 取得対象の値を特定するメンバー文字列
     * @return 取得対象の整数値、存在しない or 値が不正 or 値自体がゼロの場合は 0
     * @see #getString(String...)
     */
    @Deprecated
    public int getInteger(String... args) {
        long l = getLong(args);
        return (int) l;
    }

    /**
     * watch ページ内の JSON から値を取得して整数値(long)で返す
     * @param args 取得対象の値を特定するメンバー文字列
     * @return 取得対象の整数値、存在しない or 値が不正 or 値自体がゼロの場合は 0
     * @see #getString(String...)
     */
    @Deprecated
    public long getLong(String... args) {
        if (json != null) {
            JsonValue v = getValue(args);
            if (v instanceof JsonNumber) {
                return ((JsonNumber) v).getLong();
            } else if (v instanceof JsonString) {
                try {
                    return Long.parseLong(((JsonString) v).value());
                } catch (NumberFormatException e) {
                    Logger.debug(e);
                }
            }
        }
        return 0;
    }

    /**
     * watch ページ内の JSON から値を取得して実数値(double)で返す
     * @param args 取得対象の値を特定するメンバー文字列
     * @return 取得対象の実数値、存在しない or 値が不正 or 値自体がゼロの場合は 0
     * @see #getString(String...)
     */
    @Deprecated
    public double getDouble(String... args) {
        if (json != null) {
            JsonValue v = getValue(args);
            if (v instanceof JsonNumber) {
                return ((JsonNumber) v).getDouble();
            } else if (v instanceof JsonString) {
                try {
                    return Double.parseDouble(((JsonString) v).value());
                } catch (NumberFormatException e) {
                    Logger.debug(e);
                }
            }
        }
        return 0;
    }

    private static List<String> getArrayOfString(JsonArray a) {
        if (a != null) {
            List<JsonValue> al = a.getList();
            ArrayList<String> result = new ArrayList<>(al.size());
            for (JsonValue v2 : al) {
                if (v2 instanceof JsonString) {
                    result.add(v2.toString());
                } else {
                    return null;
                }
            }
            return result;
        }
        return null;
    }

    // テスト用
    public static void main(String args[]) {
        for (int i = 0; i < args.length; i++) {
            FileReader fr = null;
            StringBuilder sb = new StringBuilder();
            try {
                fr = new FileReader(args[i]);
                int c;
                while ((c = fr.read()) != -1) {
                    sb.append((char) c);
                }
                fr.close();
                String input = TextUtil.unescapeHTML(sb.toString());
                System.out.println("INPUT = " + input);
                JsonValue json = Json.parse(input);
                if (json != null) {
                    System.out.println("PARSE = " + json.toJson());
                }
            } catch (IOException e) {
                System.out.println(e.toString());
            }
        }
    }

}
