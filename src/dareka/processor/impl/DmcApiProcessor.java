package dareka.processor.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;

import dareka.common.Logger;
import dareka.common.json.*;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.URLResource;

public class DmcApiProcessor implements Processor {

    private final String[] SUPPORTED_METHODS = new String[]{"POST"};
    private final Pattern SUPPORTED_PATTERN
            = Pattern.compile("^https?://api\\.dmc\\.nico(?::\\d+)?/api/sessions(/[^?]*)?"
                    + "\\?.*_format=([^&]+).*$");

    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return SUPPORTED_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser) throws IOException {
        String uri = requestHeader.getURI();
        InputStream in = browser.getInputStream();
        requestHeader.removeHopByHopHeaders();

        // 解凍できないEncodingは削除
        String acceptEncoding = requestHeader.getMessageHeader(HttpHeader.ACCEPT_ENCODING);
        if (acceptEncoding != null) {
            acceptEncoding = acceptEncoding.toLowerCase().replaceAll(
                "(?: *, *)?(?:bzip2|sdch|br|compress|zstd|dcb|dcz)(?:;[^,]*)?", "");
            acceptEncoding = acceptEncoding.replaceFirst("^ *, *", "");
            requestHeader.setMessageHeader(HttpHeader.ACCEPT_ENCODING, acceptEncoding);
        }

        // ヘッダを受信して、Bodyも受信するか判断
        URLResource r = new URLResource(uri);
        HttpResponseHeader responseHeader = r.getResponseHeader(in, requestHeader);
        if (responseHeader == null) {
            Logger.warning("failed to access to api: " + uri + " (no responseHeader)");
            return r;
        }

        // Bodyの取得
        byte[] bcontent = r.getResponseBody();
        if (bcontent == null) {
            Logger.warning("failed to access to api: " + uri + " (no responseBody)");
            return r;
        }
        if (responseHeader.getMessageHeader(HttpHeader.CONTENT_ENCODING) != null) {
            Logger.warning("failed to access to api: " + uri + " (cannot decode)");
            return r;
        }
        responseHeader.removeMessageHeader("Vary");
        responseHeader.removeMessageHeader("Accept-Ranges");
        responseHeader.removeHopByHopHeaders();

        // nlパラメータのコピー
        for (Map.Entry<String, String> e : requestHeader.getParameters().entrySet()) {
            responseHeader.setParameter(e.getKey(), e.getValue());
        }
        processResponse(uri, bcontent);
        return StringResource.getRawResource(responseHeader, bcontent);
    }

    private final Pattern METHOD_PATTERN
            = Pattern.compile("\\?.*_method=([^&]+)");

    private void processResponse(String uri, byte[] bcontent)
            throws IOException {
        Logger.debug("ApiProcessor: " + uri);
        Matcher m = SUPPORTED_PATTERN.matcher(uri);
        if (!m.matches()) {
            return;
        }
        String method = null;
        Matcher m2 = METHOD_PATTERN.matcher(uri);
        if (m2.find()) {
            method = m2.group(1);
        }
        if (method != null && method.equals("DELETE")) {
            return;
        }

        String format = m.group(2);
        if (format.equals("json")) {
            processJsonResponse(bcontent);
        }
    }

    private void processJsonResponse(byte[] bcontent) throws IOException {
        String content = new String(bcontent, "UTF-8");

        JsonObject obj = Json.parseObject(content);
        if (obj == null) {
            Logger.debug("Broken JSON: " + content);
            return;
        }

        //Logger.debug(obj.toString());
        try {
            String sessionId = obj.getString("data", "session", "id");
            String ht2Key = obj.getString("data", "session", "content_auth", "content_auth_info", "value");
            JsonArray content_src_ids = obj.getArray("data", "session", "content_src_id_sets", 0, "content_src_ids");
            boolean multiple_streams = content_src_ids.size() > 1;
            String video_src_id = content_src_ids.getString(0, "src_id_to_mux", "video_src_ids", 0);
            String audio_src_id = content_src_ids.getString(0, "src_id_to_mux", "audio_src_ids", 0);
            if (video_src_id == null || audio_src_id == null) {
                // StoryBoard
                return;
            }
            JsonObject hls_parameters = obj.getObject("data", "session", "protocol", "parameters",
                "http_parameters", "parameters", "hls_parameters");
            boolean encrypted;
            String separated_audio_stream;
            if (hls_parameters != null) {
                encrypted = hls_parameters.getObject("encryption", "empty") == null;
                separated_audio_stream = hls_parameters.getString("separate_audio_stream");
            } else {
                encrypted = false;
                separated_audio_stream = "no";
            }
            Logger.debug("session.id: " + sessionId);
            Logger.debug("ht2_nicovideo: " + ht2Key);
            Logger.debug("multiple_streams: " + multiple_streams);
            Logger.debug("video_src_id: " + video_src_id);
            Logger.debug("audio_src_id: " + audio_src_id);
            Logger.debug("encrypted: " + encrypted);
            Logger.debug("separated_audio_stream: " + separated_audio_stream);
            updateHt2Info(ht2Key, multiple_streams, video_src_id, audio_src_id, encrypted,
                !"no".equals(separated_audio_stream));
        } catch (Exception e) {
            Logger.warning("failed to extract information from API: " + e.getMessage());
        }
    }

    private void updateHt2Info(String ht2Key, boolean multiStream, String videoType, String audioType,
                               boolean encrypted, boolean separatedAudioStream) {
        Ht2Entry entry = new Ht2Entry(ht2Key, multiStream, videoType, audioType, encrypted, separatedAudioStream);
        NLShared.INSTANCE.getHt2Manager().update(entry);
    }
}
