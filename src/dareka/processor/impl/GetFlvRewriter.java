package dareka.processor.impl;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.extensions.Rewriter;
import dareka.processor.HttpResponseHeader;

/** 廃止済みgetflv応答書換え用Rewriterのバイナリ互換shim。 */
public class GetFlvRewriter implements Rewriter {
    private static final Pattern NEVER_MATCH = Pattern.compile("(?!)");

    @Override
    public Pattern getRewriterSupportedURLAsPattern() {
        return NEVER_MATCH;
    }

    @Override
    public String onMatch(Matcher match, HttpResponseHeader responseHeader, String content)
            throws IOException {
        return content;
    }
}
