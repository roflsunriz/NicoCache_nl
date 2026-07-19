package dareka.extensions;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.processor.HttpResponseHeader;

public interface Rewriter {
	/*
	 * サポートしているURLを正規表現で返す
	 */
    Pattern getRewriterSupportedURLAsPattern();

    
    /**
     * URLが正規表現にマッチした場合に呼ばれる
     * @param match マッチに使用したMatcher
     * @param responseHeader レスポンスヘッダ
     * @param content 受信したコンテンツ（既に書き換えられている可能性あり）
     * @return 書き換えたコンテンツ
     * @throws IOException
     */
    String onMatch(Matcher match, HttpResponseHeader responseHeader, String content) 
    			throws IOException;
}
