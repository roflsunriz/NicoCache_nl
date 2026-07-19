package dareka.processor;

import java.io.IOException;
import java.io.InputStream;

import dareka.common.Logger;
import dareka.common.Pair;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.URLResource;

public class FetchUtil {
    private FetchUtil(){}

    public static Pair<URLResource, byte[]> fetchBinaryContent
    (HttpRequestHeader requestHeader) throws IOException {

        return fetchBinaryContent(requestHeader, null);
    };

    public static Pair<URLResource, byte[]> fetchBinaryContent
    (HttpRequestHeader requestHeader, InputStream browserToServer)
        throws IOException {

        String uri = requestHeader.getURI();
        requestHeader.removeHopByHopHeaders();

        // 解凍できないEncodingを削除.
        String acceptEncoding = requestHeader.getMessageHeader(HttpHeader.ACCEPT_ENCODING);
        if (acceptEncoding != null) {
            // Logger.info("--acceptEncoding ori: " + acceptEncoding);
            acceptEncoding = acceptEncoding.toLowerCase().replaceAll(
                "(?: *, *)?(?:bzip2|sdch|br|compress|zstd|dcb|dcz)(?:;[^,]*)?", "");
            acceptEncoding = acceptEncoding.replaceFirst("^ *, *", "");
            // Logger.info("--acceptEncoding rep: " + acceptEncoding);
            requestHeader.setMessageHeader(HttpHeader.ACCEPT_ENCODING, acceptEncoding);
        };

        // ヘッダを受信してからBodyを受信するか判断.
        URLResource r = new URLResource(uri);
        HttpResponseHeader responseHeader
            = r.getResponseHeader(browserToServer, requestHeader);

        if (responseHeader == null) {
            Logger.warning("failed to access to: " + uri + " (no responseHeader)");
            return new Pair<>(r, null);
        };
        responseHeader.removeHopByHopHeaders();

        responseHeader.removeMessageHeader("Vary");
        responseHeader.removeMessageHeader("Accept-Ranges");

        // Bodyを取得.
        byte[] bcontent = r.getResponseBody();
        if (bcontent == null) {
            Logger.warning("failed to access to: " + uri + " (no responseBody)");
            return new Pair<>(r, null);
        };

        return new Pair<>(r, bcontent);
    };
};
