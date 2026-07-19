package dareka.processor.impl;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.Logger;
import dareka.extensions.RequestFilter;
import dareka.processor.HttpRequestHeader;

public class DefaultRequestFilter implements RequestFilter {
    private static final String allowDomains =
        "^(?:LOCAL" +
        "|local\\.ptron" +
        "|(.+\\.)?nicovideo\\.jp" +
        "|(.+\\.)?smilevideo\\.jp" +
        "|(.+\\.)?[ns]img\\.jp" +
        "|(.+\\.)?dmc\\.nico" +
        "|(|g-)ecx\\.images-amazon\\.com" +
        "|(.+\\.)?nicovideo\\.photozou\\.jp" +
        ")$";
    private static final Pattern allowDomainPattern
        = Pattern.compile(allowDomains);

    private boolean isDenyDomain(String host) {
        // [nl] niconicoMode
        if (Boolean.getBoolean("niconicoMode")) {
            boolean allow = allowDomainPattern.matcher(host).matches();
            if (allow == false) {
                Logger.warning("Forbidden host access: " + host);
                return true;
            }
        }
        return false;
    }

    @Override
    public int onRequest(HttpRequestHeader requestHeader) throws IOException {
        // niconicoMode
        if (isDenyDomain(requestHeader.getHost()))
            return RequestFilter.DROP;

        // Refererを直す
        String referer = requestHeader.getMessageHeader("Referer");
        if (referer != null) {
            if (referer.matches("https?://www\\.nicovideo\\.jp/cache.*")) {
                requestHeader.setMessageHeader("Referer", "https://www.nicovideo.jp/my");
                requestHeader.setParameter("nl-from", referer);
                requestHeader.setParameter("nl-region", "cache");
            } else if (referer.matches("https?://[^/]+\\.nicovideo\\.jp/local(|/.*)")) {
                requestHeader.setMessageHeader("Referer", "https://www.nicovideo.jp/my");
                requestHeader.setParameter("nl-from", referer);
                requestHeader.setParameter("nl-region", "local");
            } else if (referer.startsWith("file://")) {
                requestHeader.setMessageHeader("Referer", "https://www.nicovideo.jp/my");
                requestHeader.setParameter("nl-from", referer);
                requestHeader.setParameter("nl-region", "local");
            }
        }

        // nicocacheのjavascriptがURLのsearchに付与した情報をnicocache内部用パラメーターに移動する.
        URLSearchInfoToParameter(requestHeader);

        return RequestFilter.OK;
    }

    // - nicocacheのjavascriptがURLのsearchに付与した情報をnicocache内部用
    //   パラメーターに移動する.
    // - 基本的にパラメーター名は"nicocachenl_"から始まる.
    private static void URLSearchInfoToParameter(HttpRequestHeader requestHeader) {
        String url = requestHeader.getURI();
        int questionPos = url.indexOf("?");

        if (questionPos < 0) {
            return;
        }

        String search = url.substring(questionPos);
        String newSearch = removeInjectedInfo(search);

        requestHeader.setURI(url.substring(0, questionPos) + newSearch);

        Matcher searchMatcher  = INJECTED_SEARCH_GET_PATTERN.matcher(search);

        while (searchMatcher.find()) {
            String s = searchMatcher.group();
            try {
                s = URLDecoder.decode(s, "UTF-8");
            }
            catch (UnsupportedEncodingException e) {
                // do nothing
            }

            Matcher m = LEFT_EQUAL_RIGHT.matcher(s);
            if (!m.matches()) {
                // =を含まず左辺の名前だけは空パラメーターとして扱う.
                requestHeader.setParameter(s, "");
                continue;
            }
            String lhs = m.group(1);
            String rhs = m.group(2);
            requestHeader.setParameter(lhs, rhs);
        }
    }

    // a=b=c のような形式は a と b=c . そのために左辺は最短一致.
    private static final Pattern LEFT_EQUAL_RIGHT = Pattern.compile("^(.*?)=(.*)$");

    // nicocache_nlのjavascriptがURLのsearchに付与した動画情報を取得するためのパターン.
    private static final Pattern INJECTED_SEARCH_GET_PATTERN =
        Pattern.compile("(?<=[?&])nicocachenl_[^&]*");

    // nicocache_nlのjavascriptがURLのsearchに付与した動画情報を消すためのパターン.
    // group(1):before, group(2):delimiter, group(3):after
    private static final Pattern INJECTED_SEARCH_REMOVE_PATTERN =
        Pattern.compile(
            "&nicocachenl_[^&]*"
            + "|" + "[?]nicocachenl_[^&]*$" // search が空になるから ? も消す
            + "|" + "(?<=[?])nicocachenl_[^&]*&");

    /**
     * nicocache_nlのjavascriptがURLのsearchに付与した動画情報を消す.
     * この関数はsearch部末尾に"&"がある場合にそれを消さない.
     * (それがプロキシとしての振舞いとして正当であるため).
     */
    public static String removeInjectedInfo(String url) {
        return INJECTED_SEARCH_REMOVE_PATTERN.matcher(url).replaceAll("");
    }
}
