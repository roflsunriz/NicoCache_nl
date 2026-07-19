package dareka.processor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import dareka.common.Config;

public class StringResource extends Resource {
    private String statusline = "HTTP/1.1 200 OK";
    private byte[] contentAsBytes = null;

    private boolean clientCanKeepAlive;

    public static StringResource getRedirect(String redirectUri) {
        return new StringResource("HTTP/1.1 302 Found\r\nLocation:"
                + redirectUri, "");
    }

    public static StringResource getNotFound() {
        return new StringResource("HTTP/1.1 404 Not Found", "404 Not Found");
    }

    public static StringResource getNotModified() {
        return new StringResource("HTTP/1.1 304 Not Modified", "");
    }

    public static StringResource getBadRequest() { // [nl]
        return new StringResource("HTTP/1.1 400 Bad Request", "400 Bad Request");
    }

    public static StringResource getForbidden() {
        return new StringResource("HTTP/1.1 403 Forbidden", "403 Forbidden");
    }

    public static StringResource getMethodNotAllowed() {
        return new StringResource("HTTP/1.1 405 Method Not Allowed", "405 Method Not Allowed");
    }

    public static StringResource getPayloadTooLarge() {
        return new StringResource("HTTP/1.1 413 Payload Too Large", "413 Payload Too Large");
    }

    // 例外からエラーページを作る(夏.05)
    public static StringResource getInternalError(Exception e) {
        StringWriter sw = new StringWriter();
        sw.append("NicoCacheでエラーが発生しました。\r\n");
        e.printStackTrace(new PrintWriter(sw));
        return new StringResource("HTTP/1.1 500 Internal Server Error",
                sw.toString());
    }

    public static StringResource getInternalError(String resource) {
        return new StringResource("HTTP/1.1 500 Internal Server Error", resource);
    }

    public static StringResource getBadGateway() {
        return getBadGateway("502 Bad Gateway");
    }

    public static StringResource getBadGateway(String resource) {
        return new StringResource("HTTP/1.1 502 Bad Gateway", resource);
    }

    public static StringResource getRawResource(
            HttpResponseHeader responseHeader, byte[] content) {
        StringResource r = new StringResource(content);
        r.statusline =
                responseHeader.getVersion() + " "
                        + String.valueOf(responseHeader.getStatusCode()) + " "
                        + responseHeader.getReason();
        r.copyResponseHeaders(responseHeader);

        return r;
    }

    public StringResource(String resource) {
        contentAsBytes = getBytesAsUtf8(resource);
    }

    public StringResource(byte[] resource) {
        contentAsBytes = resource;
    }

    protected StringResource(String startline, String content) {
        statusline = startline;
        contentAsBytes = getBytesAsUtf8(content);
    }

    @Deprecated
    public StringResource(int status, String resource) {
        switch (status) {
        case 302:
            statusline = "HTTP/1.1 302 Found\r\nLocation:" + resource;
            contentAsBytes = new byte[0]; // represents it's not forgotten.
            return;
        case 304:
            statusline = "HTTP/1.1 304 Not Modified";
            break;
        case 404:
            statusline = "HTTP/1.1 404 Not Found";
            break;
        default:
            throw new IllegalArgumentException("unexpected status code: "
                    + status);
        }
        contentAsBytes = getBytesAsUtf8(resource);
    }

    private byte[] getBytesAsUtf8(String str) {
        try {
            return str.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            // never happen
            throw new IllegalStateException("cannot use UTF-8");
        }
    }

    public void addNoCacheResponseHeaders() {
        setResponseHeader("Expires", "Thu, 01 Dec 1994 16:00:00 GMT");
        setResponseHeader("Cache-Control", "no-store");
        setResponseHeader("Pragma", "no-cache");
    }

    public void copyResponseHeaders(HttpResponseHeader responseHeader) {
        for (Map.Entry<String, List<String>> entry : responseHeader.getMessageHeaders().entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            for (String value : values) {
                addResponseHeader(key, value);
            }
        }
    }

    /* (非 Javadoc)
     * @see dareka.processor.Resource#doSetMandatoryHeader(dareka.processor.HttpResponseHeader)
     */
    @Override
    protected void doSetMandatoryResponseHeader(
            HttpResponseHeader responseHeader) {
        responseHeader.setContentLength(contentAsBytes.length);

        if (clientCanKeepAlive) {
            responseHeader.setMessageHeader(HttpHeader.CONNECTION,
                    HttpHeader.CONNECTION_KEEP_ALIVE);
        } else {
            responseHeader.setMessageHeader(HttpHeader.CONNECTION,
                    HttpHeader.CONNECTION_CLOSE);
        }
    }

    @Override
    public boolean endEnsuredTransferTo(Socket receiver,
            HttpRequestHeader requestHeader, Config config) throws IOException {
        clientCanKeepAlive = isClientCanKeepAlive(requestHeader);
        HttpResponseHeader responseHeader =
                new HttpResponseHeader(statusline + "\r\n\r\n");
        responseHeader.addMessageHeader("Content-type", "text/plain; charset=UTF-8");

        execSendingHeaderSequence(receiver.getOutputStream(), responseHeader);

        execSendingBodySequence(receiver.getOutputStream(),
                new ByteArrayInputStream(contentAsBytes),
                responseHeader.getContentLength());

        return clientCanKeepAlive;
    }

}
