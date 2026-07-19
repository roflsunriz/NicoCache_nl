package dareka.common.regex;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaMatcher {
    Matcher m = null;

    JavaMatcher(){ }

    JavaMatcher(Pattern p, CharSequence input) {
        m = p.matcher(input);
    }

    public boolean find() {
        return m.find();
    }

    public boolean matches() {
        return m.matches();
    }

    public CharSequence group() {
        return m.group();
    }

    public String group(int num) {
        return m.group(num);
    }

    public int groupCount() {
        return m.groupCount();
    }

    public JavaMatcher reset() {
        m.reset();
        return this;
    }

    public int start() {
        return m != null ? m.start() : -1;
    }

    public int end() {
        return m != null ? m.end() : -1;
    }

    public String replaceFirst(String rep) {
        return m.replaceFirst(rep);
    }

    public void appendReplacement(StringBuffer sb, String replacement) {
        m.appendReplacement(sb, replacement);
    }

    public void appendTail(StringBuffer sb) {
        m.appendTail(sb);
    }
}
