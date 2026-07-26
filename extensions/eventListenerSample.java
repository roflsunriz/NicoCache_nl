package extensions;

import java.io.IOException;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.Logger;
import dareka.extensions.Extension2;
import dareka.extensions.ExtensionManager;
import dareka.extensions.NLFilterListener;
import dareka.extensions.SystemEventListener;
import dareka.processor.util.GetThumbInfoUtil;

/**
 * イベント処理と nlFilter 拡張のサンプル
 * <p>
 * オプション指定(eventListenerSample.XXXX)で以下の機能を実装:
 * <ul>
 * <li>定期呼び出し時刻の表示(ShowPeriodicCall)
 * <li>ユーザーニコ割をキャッシュしない(DisableUserCM)
 * <li>nlFilterでUNIXTIMEを置換(ReplaceUNIXTIME)
 * </ul>
 */
public class eventListenerSample implements Extension2,
        SystemEventListener, NLFilterListener {

    private static final String KEY = "eventListenerSample.";
    private static final String KEY_SHOWPERIODIC    = KEY + "ShowPeriodicCall";
    private static final String KEY_DISABLEUSERCM   = KEY + "DisableUserCM";
    private static final String KEY_REPLACEUNIXTIME     = KEY + "ReplaceUNIXTIME";

    public String getVersionString() {
        return "eventListenerSample rev.3";
    }

    public void registerExtensions(ExtensionManager mgr) {
        mgr.registerEventListener(this);
    }

    public int onSystemEvent(int id, EventSource source) {
        switch (id) {
        case SYSTEM_EXIT:
            Logger.info("システム終了");
            break;
        case PERIODIC_CALL:
            if (Boolean.getBoolean(KEY_SHOWPERIODIC)) {
                Logger.info("定期呼び出し: " +
                        new Date(System.currentTimeMillis()).toString());
            }
            break;
        case URL_MEMCACHED:
            Logger.info("メモリキャッシュ完了: " + source.getURL());
            break;
        case CACHE_REQUEST:
            Logger.info("キャッシュ要求: " + source.getURL());
            break;
        case CACHE_STARTING:
            if (Boolean.getBoolean(KEY_DISABLEUSERCM)) {
                // 200KB以内のnm動画(=ユーザーニコ割)はキャッシュしない
                String videoId = source.getCache().getVideoId();
                if (videoId != null && videoId.startsWith("nm")) {
                    int videoSize = getVideoSize(videoId);
                    if (0 < videoSize && videoSize <= 200 * 1024) {
                        return RESULT_NG;
                    }
                }
            }
            Logger.info("キャッシュ開始: " + source.getCache().getId());
            break;
        case CACHE_COMPLETED:
            Logger.info("キャッシュ完了: " + source.getCache().getId());
            break;
        case CACHE_SUSPENDED:
            Logger.info("キャッシュ中断: " + source.getCache().getId());
            break;
        case CACHE_REMOVING:
            Logger.info("キャッシュ削除開始: " + source.getCache().getId());
            break;
        case CACHE_REMOVED:
            Logger.info("キャッシュ削除完了: " + source.getCache().getId());
            break;
        case CONFIG_RELOADED:
            Logger.info("コンフィグ再読込");
            break;
        }
        return RESULT_OK;
    }

    private static final Pattern SIZE_HIGH_PATTERN = Pattern.compile(
            "<size_high>(\\d+)</size_high>");

    private int getVideoSize(String id) {
        try {
            String thumbinfo = GetThumbInfoUtil.get(id);
            if (thumbinfo != null) {
                Matcher m = SIZE_HIGH_PATTERN.matcher(thumbinfo);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        } catch (IOException e) {
            Logger.error(e);
        }
        return -1;
    }

    // NLFilterListener は暫定仕様なので変更する可能性があります

    public void nlFilterBegin(Content content) {
        // <nlVar:eventListenerSample.VERSION> でバージョン文字列を取得
        content.setVariable(KEY + "VERSION", getVersionString());
    }

    public String nlVarReplace(String name, Content content) {
        // <nlVar:UNIXTIME> を現在時刻に置き換える
        if (name.equals("UNIXTIME") && Boolean.getBoolean(KEY_REPLACEUNIXTIME)) {
            return String.format("%d", new Date().getTime() / 1000L);
        }
        return null;
    }

    public int idGroup(String s1, String s2) {
        return NLFilterListener.NOT_SUPPORTED;
    }

    public void updateLST(String list) {
        Logger.info("LSTパターン更新: " + list);
    }

}
