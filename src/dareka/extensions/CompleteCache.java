package dareka.extensions;

import java.io.IOException;
import dareka.processor.impl.Cache;

public interface CompleteCache {
	/**
	 * このExtensionの優先順位(高 0〜 低)
	 * キャッシュを移動せず、必ず次のExtensionに回す場合は
	 * 負の値を設定すること(他のExtensionとの協調のため)
	 */
	int getPriority();

	/**
	 * onCompleteが呼ばれる直前、呼び出し順を決定する際に呼ばれる
	 * ここで設定ファイルの動的読み込みや、priorityの設定をする
	 * (onCompleteが呼ばれるときには、すでに順序が決定しているので、
	 * 動的読み込みでpriorityの設定が初回に反映されないため)
	 * スレッドセーフな実装が必要
	 * ･･･だが良く考えたらgetPriorityでやればよかったんだ。
	 * 分かりやすいからいいかw
	 */
	void update();

    /**
     * キャッシュ完了時に呼ばれる
	 * キャッシュを移動した時はtrue、しなかったときはfalseを返す
	 * trueを返した時は、以後のCompleteCacheExtensionは呼ばれない
	 * スレッドセーフな実装が必要
     * @throws IOException
     */
    boolean onComplete(Cache cache) 
    			throws IOException;
}
