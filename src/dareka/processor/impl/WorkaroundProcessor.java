package dareka.processor.impl;

import dareka.extensions.RequestFilter;
import dareka.processor.HttpRequestHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;

import java.io.IOException;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WorkaroundProcessor implements Processor, RequestFilter {
    // 任意のMethodにマッチ
    private static final String[] SUPPORTED_METHODS = new String[] { null };

    private static final Pattern INVALID_WATCH_PATERN = Pattern.compile(
            "^(https?://[^/]+\\.nicovideo\\.jp/(?!cache/)(?:.*?)\\w{2}\\d+?)(?:low)?" +
                    "\\[[\\w-]+(?:,\\d+)?,\\d+\\](?:\\.\\w+)?(.*?(?:\\?.*|))$");

    private static final Pattern LOGGER_PATERN = Pattern.compile(
            "^https?://flapi\\.nicovideo\\.jp/api/(?:cspreport|logger\\.php.*)$");

    private static final Pattern PROCESSOR_PATTERN = Pattern.compile(
            INVALID_WATCH_PATERN.pattern() + "|" + LOGGER_PATERN.pattern());

    // Processor
    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return PROCESSOR_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser) throws IOException {
        Matcher m = INVALID_WATCH_PATERN.matcher(requestHeader.getURI());
        if (m.matches()) {
            return StringResource.getRedirect(m.group(1) + m.group(2));
        }
        return StringResource.getNotFound();
    }

    // RequestFilter
    @Override
    public int onRequest(HttpRequestHeader requestHeader) throws IOException {
        String referer = requestHeader.getMessageHeader("Referer");

        if (referer != null) {
            Matcher m = INVALID_WATCH_PATERN.matcher(referer);
            if (m.matches()) {
                requestHeader.setMessageHeader("Referer", "https://www.nicovideo.jp/my");
                requestHeader.setParameter("nl-from", referer);
                requestHeader.setParameter("nl-region", "local");
            }
        }
        return RequestFilter.OK;
    }

}
