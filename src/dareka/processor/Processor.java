package dareka.processor;

import java.io.IOException;
import java.net.Socket;
import java.util.regex.Pattern;

public interface Processor {
    /**
     * サポートしているメソッドを問い合わせる時にシステムが呼び出します。
     * GETやPOSTなど、サポートしているメソッドを配列で返してください。
     * 
     * @return サポートしているメソッドを列挙した配列。
     */
    String[] getSupportedMethods();
    
    /**
     * サポートしているURLを問い合わせる時にシステムが呼び出します。
     * サポートしているURLにマッチする正規表現を返してください。
     * 
     * @return サポートしているURLにマッチする正規表現。
     * nullを返した場合は代わりにgetSupportedURLAsString()が呼び出される。
     */
    Pattern getSupportedURLAsPattern();
    
    /**
     * サポートしているURLを問い合わせる時にシステムが呼び出します。
     * サポートしているURLを戻り値で示してください。
     * 先頭が戻り値の文字列で始まっているURLにマッチします。
     * 正規表現が不要な簡易版。
     * 
     * @return サポートしているURL。
     * getSupportedURLAsPattern()でもここでもnullを返した場合は全てのURLにマッチ。
     */
    String getSupportedURLAsString();
    
    /**
     * サポートしているメソッドとURLに合致したリクエストが
     * インバウンド(ブラウザ)側から到着した時に
     * システムが呼び出します。
     * レスポンスをどのリソースから取得するかを決定し、
     * 戻り値で示してください。
     * 
     * @param requestHeader 到着したリクエストのヘッダ。
     * これを修正すると、アウトバウンド(サーバ)側に送信するヘッダに
     * 反映される。
     * @param browser
     * @return レスポンスとして返すリソース。
     * @throws IOException 
     */
    Resource onRequest(HttpRequestHeader requestHeader, Socket browser) throws IOException;
}
