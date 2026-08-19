package dareka.processor.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import dareka.common.json.JsonArray;
import dareka.common.json.JsonNumber;
import dareka.common.json.JsonObject;
import dareka.common.json.JsonString;
import dareka.processor.HttpResponseHeader;
import dareka.processor.URLResource;
import dareka.processor.util.GetThumbInfoUtil;

/** Fetches and normalizes public video metadata for the management site. */
final class NicoVideoMetadata {
    private NicoVideoMetadata() {
    }

    static JsonObject fetch(String videoId) throws IOException {
        String xml = fetchXml(videoId);
        if (xml == null || xml.isBlank()) {
            throw new IOException("empty getthumbinfo response");
        }
        return parse(videoId, xml);
    }

    private static String fetchXml(String videoId) throws IOException {
        String endpoint = System.getProperty("videoMetadataEndpoint", "").trim();
        if (endpoint.isEmpty()) {
            return GetThumbInfoUtil.get(videoId);
        }
        if (!endpoint.matches("https?://[^\\s]+/")) {
            throw new IOException("invalid video metadata endpoint");
        }

        URLResource resource = new URLResource(endpoint + videoId);
        // 明示されたメタデータfixture／代替APIはそのURLへ直接接続する。
        // グローバル上流プロキシーが同じfixtureを指すテスト環境で、
        // fixture自身をプロキシーとして再経由するとUnix系JDKの接続が滞留する。
        resource.setProxy("", 0);
        resource.setFollowRedirects(true);
        HttpResponseHeader response = resource.getResponseHeader(null, null);
        if (response == null || response.getStatusCode() != 200) {
            throw new IOException("video metadata endpoint returned an error");
        }
        byte[] body = resource.getResponseBody();
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    static JsonObject parse(String videoId, String xml) throws IOException {
        ThumbHandler handler = new ThumbHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setNamespaceAware(false);
            factory.newSAXParser().parse(new ByteArrayInputStream(
                    xml.getBytes(StandardCharsets.UTF_8)), handler);
        } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("invalid getthumbinfo response", error);
        }
        return handler.toJson(videoId);
    }

    private static final class ThumbHandler extends DefaultHandler {
        private final StringBuilder text = new StringBuilder();
        private final List<String> tags = new ArrayList<>();
        private String status;
        private String currentElement;
        private String responseVideoId;
        private String title;
        private String description;
        private String thumbnailUrl;
        private String uploadedAt;
        private String duration;
        private String viewCount;
        private String commentCount;
        private String mylistCount;
        private String ownerName;
        private String channelName;
        private String errorCode;
        private String errorDescription;
        private boolean inError;

        @Override
        public void startElement(String uri, String localName, String qName,
                Attributes attributes) {
            currentElement = qName;
            text.setLength(0);
            if ("nicovideo_thumb_response".equals(qName)) {
                status = attributes.getValue("status");
            } else if ("error".equals(qName)) {
                inError = true;
            }
        }

        @Override
        public void characters(char[] characters, int start, int length) {
            if (currentElement != null) {
                text.append(characters, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String value = text.toString().trim();
            if ("video_id".equals(qName)) {
                responseVideoId = value;
            } else if ("title".equals(qName)) {
                title = value;
            } else if ("description".equals(qName) && !inError) {
                description = value;
            } else if ("thumbnail_url".equals(qName)) {
                thumbnailUrl = value;
            } else if ("first_retrieve".equals(qName)) {
                uploadedAt = value;
            } else if ("length".equals(qName)) {
                duration = value;
            } else if ("view_counter".equals(qName)) {
                viewCount = value;
            } else if ("comment_num".equals(qName)) {
                commentCount = value;
            } else if ("mylist_counter".equals(qName)) {
                mylistCount = value;
            } else if ("user_nickname".equals(qName)) {
                ownerName = value;
            } else if ("ch_name".equals(qName)) {
                channelName = value;
            } else if ("tag".equals(qName) && !value.isEmpty()) {
                tags.add(value);
            } else if ("code".equals(qName) && inError) {
                errorCode = value;
            } else if ("description".equals(qName) && inError) {
                errorDescription = value;
            }
            if ("error".equals(qName)) {
                inError = false;
            }
            currentElement = null;
            text.setLength(0);
        }

        private JsonObject toJson(String requestedVideoId) throws IOException {
            if ("fail".equalsIgnoreCase(status)) {
                String code = emptyToDefault(errorCode, "UNKNOWN");
                return new JsonObject()
                        .put("videoId", new JsonString(requestedVideoId))
                        .put("availabilityStatus", new JsonString(
                                classifyUnavailable(code, errorDescription)))
                        .put("errorCode", new JsonString(code))
                        .put("message", new JsonString(emptyToDefault(
                                errorDescription, "動画情報を取得できませんでした")));
            }
            if (!"ok".equalsIgnoreCase(status)) {
                throw new IOException("getthumbinfo status is missing");
            }

            JsonArray tagValues = new JsonArray();
            for (String tag : tags) {
                tagValues.add(new JsonString(tag));
            }
            return new JsonObject()
                    .put("videoId", new JsonString(emptyToDefault(
                            responseVideoId, requestedVideoId)))
                    .put("availabilityStatus", new JsonString("available"))
                    .put("title", new JsonString(emptyToDefault(title, "")))
                    .put("description", new JsonString(emptyToDefault(
                            description, "")))
                    .put("thumbnailUrl", new JsonString(emptyToDefault(
                            thumbnailUrl, "")))
                    .put("uploadedAt", new JsonString(emptyToDefault(
                            uploadedAt, "")))
                    .put("duration", new JsonString(emptyToDefault(duration, "")))
                    .put("viewCount", new JsonNumber(parseLong(viewCount)))
                    .put("commentCount", new JsonNumber(parseLong(commentCount)))
                    .put("mylistCount", new JsonNumber(parseLong(mylistCount)))
                    .put("author", new JsonString(emptyToDefault(
                            ownerName, emptyToDefault(channelName, ""))))
                    .put("tags", tagValues);
        }
    }

    private static String classifyUnavailable(String code, String message) {
        String normalized = (code + " " + emptyToDefault(message, ""))
                .toLowerCase();
        if (normalized.contains("private") || normalized.contains("非公開")) {
            return "private";
        }
        if (normalized.contains("deleted") || normalized.contains("not_found")
                || normalized.contains("not found")
                || normalized.contains("削除")
                || normalized.contains("存在しません")) {
            return "deleted";
        }
        return "unavailable";
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.replace(",", "").trim());
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private static String emptyToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
