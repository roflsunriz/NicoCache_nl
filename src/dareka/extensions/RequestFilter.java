package dareka.extensions;

import java.io.IOException;

import dareka.processor.HttpRequestHeader;

public interface RequestFilter {
	/**
	 * 処理が正常に完了したとき
	 */
	public static final int OK = 0;
	/**
	 * このリクエストを破棄するとき
	 */
	public static final int DROP = 1;
	
    /**
     * リクエストをProcessorで処理する前に呼ばれる
     * @param requestHeader 送られてきたリクエストヘッダ
     * @return 何となくintを返して利用できるようにする。
     * 			正常終了なら RequestFilter.OK　を返す。
     * @throws IOException
     */
    int onRequest(HttpRequestHeader requestHeader) 
    			throws IOException;
}
