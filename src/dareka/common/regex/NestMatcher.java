package dareka.common.regex;

import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.Pair;
import dareka.common.Logger;

public class NestMatcher extends JavaMatcher {
	CharSequence content;
	Matcher t = null;
	Matcher c = null;
	Pattern conPattern = null;
	
	int last = 0, matchStart = 0;
	
	NestMatcher(NestPattern np, CharSequence input) {
		t = np.tagPattern.matcher(input);
		c = np.conPattern.matcher(input);
		conPattern = np.conPattern;
		content = input;
	}

	@Override
	public boolean find() {
		Stack<Pair<Integer, Integer>> tag = new Stack<Pair<Integer, Integer>>();
		while(t.find()) {
			if (t.group(1) != null) {
				// 開始タグ
				tag.push(new Pair<Integer, Integer>(t.start(), t.end()));
			} else {
				// 終了タグ
				if (!tag.empty()) {
					Pair<Integer, Integer> se = tag.pop();
					matchStart = se.first;
					c.region(se.second, t.start());
					if (c.find()) {
						t.region(t.end(), t.regionEnd());
						return true;
					}
				}
			}
		}
		
		return false;
	}

	@Override
	public CharSequence group() {
//返す範囲を$NESTで指定したタグまで含めるように
//		return content.subSequence(c.regionStart(), c.regionEnd());
		return content.subSequence(matchStart, t.regionStart());
	}

	@Override
	public void appendReplacement(StringBuffer sb, String replacement) {
		sb.append(content.subSequence(last, matchStart));
// $0は特別なので先に置換
// Replaceの特殊文字を2回エスケープする(今回ともう一度置換するため)
		replacement = replacement.replaceAll("(?<!\\\\)(?:(?:\\\\\\\\)*)\\$0(?!\\d)", Matcher.quoteReplacement(Matcher.quoteReplacement(content.subSequence(matchStart, t.regionStart()).toString())));

// そのままreplaceFirstすると、ページ全体が返ってくる
// →マッチした部分のみを置換して取得するメソッドが無いので、
// マッチした部分のみのStringにもう一度マッチさせる
// マッチングの先頭/最後に先読み/後読みがあると当然マッチしなくなるので、
// 前後に".*"を置くなどして対処が必要だが、
// Matches()ではなくfind()なので、うかつな正規表現を入れると激重になる
// "^$" などの境界条件を入れるといい
		String rep = c.group();
		Matcher m = conPattern.matcher(rep);
		if (!m.matches()) {
			Logger.info("$NEST matching Error.");
		}
		rep = m.replaceFirst(replacement);
		sb.append(rep);
		last = t.regionStart();
	}

	@Override
	public void appendTail(StringBuffer sb) {
		sb.append(content.subSequence(last, content.length()));
	}
}
