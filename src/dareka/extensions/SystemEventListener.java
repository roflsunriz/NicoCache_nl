package dareka.extensions;

import java.util.EventListener;

import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.URLResource;
import dareka.processor.impl.Cache;

/**
 * システムのイベント通知を受け取るインターフェース
 * @since NicoCache_nl+111130mod
 */
public interface SystemEventListener extends EventListener {
	
	// Constant Interface Antipattern に該当するけど、
	// Extension からしか使わないので利便性を優先
	
	/**
	 * システム終了時に一度だけ呼び出される。
	 * <p>
	 * 取得できる情報: 無し(null が渡される)
	 */
	int SYSTEM_EXIT = 1;
	
	/**
	 * システムから定期的に呼び出される。
	 * 呼び出し元のスレッドは優先度が最低に設定されているので、
	 * システム負荷が高いと呼び出し間隔が前後することもあるので注意。
	 * なお、一度目の呼び出しは起動直後に行われるので、
	 * 時間のかかる初期化処理等をこのタイミングで行っても良い。
	 * 
	 * <p>
	 * 取得できる情報: 無し(null が渡される)
	 */
	int PERIODIC_CALL = 2;
	
	/**
	 * URL をメモリにキャッシュ完了した時に呼び出される。
	 * メモリキャッシュ対象の URL 以外は呼び出されないので注意。
	 * <p>
	 * 取得できる情報:
	 * URL, Content, RequestHeader, ResponseHeader, URLResource<br>
	 * @see dareka.processor.URLResourceCache
	 */
	int URL_MEMCACHED = 3;
	
	/**
	 * 動画へのキャッシュ要求時にキャッシュの有無に関わらず呼び出される。
	 * 既にキャッシュがある場合にここで削除すると再度キャッシュ処理を行う。
	 * 戻り値に RESULT_NG を返すことでリクエスト自体をパススルーできる。
	 * <p>
	 * 取得できる情報: URL, RequestHeader, Cache
	 */
	int CACHE_REQUEST = 4;
	
	/**
	 * 動画のキャッシュ開始時に呼び出される。
	 * 戻り値に RESULT_NG を返すことで動画のキャッシュを禁止できる。
	 * <p>
	 * 取得できる情報: URL, RequestHeader, Cache
	 */
	int CACHE_STARTING = 5;
	
	/**
	 * 動画のキャッシュ完了時に呼び出される。
	 * <p>
	 * 取得できる情報: URL, RequestHeader, ResponseHeader, Cache
	 */
	int CACHE_COMPLETED = 6;
	
	/**
	 * 動画のキャッシュ中断時に呼び出される。
	 * <p>
	 * 取得できる情報: URL, RequestHeader, ResponseHeader, Cache
	 */
	int CACHE_SUSPENDED = 7;
	
	/**
	 * 動画のキャッシュ削除時に呼び出される。
	 * 戻り値に RESULT_NG を返すことでキャッシュの削除を禁止できる。
	 * なお、この呼び出し内で<b>キャッシュ削除を行わないこと</b>。
	 * <p>
	 * 取得できる情報: Cache
	 */
	int CACHE_REMOVING = 8;
	
	/**
	 * 動画のキャッシュ削除完了時に呼び出される。
	 * <p>
	 * 取得できる情報: Cache
	 */
	int CACHE_REMOVED = 9;
	
	/**
	 * config.properties を再読み込みした時に呼び出される。
	 * <p>
	 * 取得できる情報: 無し(null が渡される)
	 */
	int CONFIG_RELOADED = 10;
	
	// イベントIDを追加する場合は必ず末尾に追加して既存のID値を変更しないこと
	
	/**
	 * イベント発生元の情報を取得するインターフェース
	 */
	interface EventSource {
		String getURL();
		String getContent();
		HttpRequestHeader getRequestHeader();
		HttpResponseHeader getResponseHeader();
		URLResource getURLResoruce();
		Cache getCache();
	}
	
	int RESULT_OK = 0;
	int RESULT_NG = 1;
	
	/**
	 * イベントが発生する度にシステムから呼び出される。
	 * 呼び出し順はロードした順番(Extension ファイル名の辞書昇順)となる。
	 * 
	 * @param id 発生したイベントID
	 * <ul>
	 * <li>{@link #SYSTEM_EXIT}     システム終了
	 * <li>{@link #PERIODIC_CALL}   定期呼び出し(60秒間隔)
	 * <li>{@link #URL_MEMCACHED}   URL メモリキャッシュ完了
	 * <li>{@link #CACHE_REQUEST}   キャッシュ要求
	 * <li>{@link #CACHE_STARTING}  キャッシュ開始(中断可)
	 * <li>{@link #CACHE_COMPLETED} キャッシュ完了
	 * <li>{@link #CACHE_SUSPENDED} キャッシュ中断
	 * <li>{@link #CACHE_REMOVING}  キャッシュ削除開始(中断可)
	 * <li>{@link #CACHE_REMOVED}   キャッシュ削除完了
	 * <li>{@link #CONFIG_RELOADED} コンフィグ再読込
	 * </ul>
	 * @param source イベント発生元オブジェクト(getXXX メソッドで情報を取得できる)。
	 * イベントIDによっては null もあり得るので、各イベントIDの取得出来る情報を参照のこと。
	 * @return 通常は {@link #RESULT_OK} を返すこと。特定のイベントに対して、
	 * そのイベントを許容しない場合に {@link #RESULT_NG} を返すと処理を中断できる。
	 */
	int onSystemEvent(int id, EventSource source);
	
}
