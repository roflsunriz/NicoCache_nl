package dareka.common.regex;

import java.util.regex.Pattern;

import dareka.common.Logger;

final public class NestPattern extends JavaPattern {
	Pattern tagPattern = null;
	Pattern conPattern = null;

	NestPattern(String start, String content, String end) {
		tagPattern = Pattern.compile("(" + start + ")|(" + end + ")");
// 前方参照を使用可能にあわせ、中のマッチングをDOTALLから通常に
//		conPattern = Pattern.compile(content, Pattern.DOTALL);
		conPattern = Pattern.compile(content);
	}
	
	@Override
	public JavaMatcher matcher(CharSequence content) {
		return new NestMatcher(this, content);
	}


	// テスト用
    public static void main(String[] args) {
		String content = "aa<a>b</a><div align='left'><div clear>aba<div>nasi</div></div></div>"
						+ "<div align='left'>aaaaaaaa</div>";
    	JavaPattern n = new NestPattern("<div", ".+left.*", "</div>");
    	JavaMatcher m = n.matcher(content);
    	
    	StringBuffer b = new StringBuffer();
    	while(m.find()) {
    		Logger.info(m.group().toString());
    		m.appendReplacement(b, "ok");
    	}
    	m.appendTail(b);
    	Logger.info("result: " + b.toString());
    }
}
