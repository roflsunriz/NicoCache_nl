package dareka.common.regex;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaPattern {
	Pattern pt = null;
	
	JavaPattern() { }
	
	JavaPattern(String regex) {
		pt = Pattern.compile(regex);
	}
	
	static final Pattern nestPattern = Pattern.compile(
//			"\\$NEST\\(([^,]+),([^,]+),(.+)\\)\\s*");
			"\\$NEST\\(((?:\\\\\\\\|\\\\,|[^,])+),((?:\\\\\\\\|\\\\,|[^,])+),(.+)\\)\\s*");
	static public JavaPattern compile(String regex) {
		Matcher m = nestPattern.matcher(regex);
		if (m.matches()) {
			return new NestPattern(m.group(1), m.group(2), m.group(3));
		}
		return new JavaPattern(regex);
	}
	
	public JavaMatcher matcher(CharSequence content) {
		return new JavaMatcher(pt, content);
	}
	
	public String pattern() {
		return pt != null ? pt.pattern() : toString();
	}
}
