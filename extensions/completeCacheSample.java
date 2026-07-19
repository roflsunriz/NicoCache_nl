package extensions;

import java.io.IOException;

import dareka.Main;
import dareka.common.Logger;
import dareka.extensions.CompleteCache;
import dareka.extensions.Extension2;
import dareka.extensions.ExtensionManager;

import dareka.processor.impl.Cache;
import dareka.processor.util.GetThumbInfoUtil;

/*
 *
 */
public class completeCacheSample implements Extension2, CompleteCache {

// Extension2でのみ対応

	// Extension interface
	public void registerExtensions(ExtensionManager mgr) {
// インスタンスは1つだけ作って使いまわすので、newしても意味無し
		mgr.registerCompleteCache(this);
	}

	public String getVersionString() {
		return "completeCacheSample";
	}

// -----------------------------------------------------


	public completeCacheSample() {
	}

	/**
	 * このExtensionの優先順位(高 0〜 低)
	 * データ取得のみなど、キャッシュを移動せず、必ず次のExtensionに回す場合は
	 * 負の値を設定すること(他のExtensionとの協調のため)
	 */
	private int priority = -1;
	public int getPriority() {
		return priority;
	}

	/**
	 * onCompleteが呼ばれる直前、呼び出し順を決定する際に呼ばれる
	 * ここで設定ファイルの動的読み込みや、priorityの設定をする
	 * (onCompleteが呼ばれるときには、すでに順序が決定しているので、
	 * 動的読み込みでpriorityの設定が初回に反映されないため)
	 * スレッドセーフな実装が必要
	 * ･･･だが良く考えたらgetPriorityでやればよかったんだ。
	 * 分かりやすいからいいかw
	 */
	public void update() {
		// synchronized ファイル読み込みとか
	}

    /**
     * キャッシュ完了時に呼ばれる
	 * キャッシュを移動した時はtrue、しなかったときはfalseを返す
	 * trueを返した時は、以後のCompleteCacheExtensionは呼ばれない
	 * スレッドセーフな実装が必要
     * @throws IOException
     */
	public boolean onComplete(Cache cache) throws IOException {

		String smid = cache.getId();
		if (smid.endsWith("low")) {
			smid = smid.substring(0, smid.length()-3);
		}

// 本体内蔵のgetThumbInfo読み込みメソッド(排他つき)
// キャッシュ完了ごとに1度ならサーバ負荷的にも問題ないかな
// (複数のCompleteCacheExtensionを利用するときのためにキャッシュつきなので、
// なるべくこれを利用の事)
// 生のgetThumbInfoのStringが返ります。取得できない時はnull
// GetThumbInfoUtil.getWithRetry(String 動画ID, int リトライ回数, long リトライ待ち時間(ms))
		String info = GetThumbInfoUtil.getWithRetry(smid, 3, 3000);
		if (info == null) {
			Logger.info("completeCacheSample : getThumbInfoError.");
			return false;
		}
		Logger.info(info);


// 適当にキャッシュ移動とか
// あると嬉しいのは投稿者IDとかタグ(ロック有り無し)とか?
// cache.move とかは排他処理を持ってるので気にしなくてもおk

// キャッシュ移動してないからfalseで次のExtに回す
		return false;
	}

}

