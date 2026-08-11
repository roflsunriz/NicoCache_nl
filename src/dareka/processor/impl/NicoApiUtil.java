package dareka.processor.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import dareka.common.Logger;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.URLResource;

/**
 * Utility class to treat nicovideo API.
 *
 * For maintenance, all of knowledge for nicovideo API should be placed here.
 *
 */
public class NicoApiUtil {
    private NicoApiUtil() {
        // prevent instantiation
    }

    public static String getThumbURL(String type, String id) {
//        return "http://ext.nicovideo.jp/api/getthumbinfo/" + type + id;
        return EXTAPI_URL + "getthumbinfo/" + type + id;
    }

    public static String getThumbTitle(InputStream thumbResponse) {
        SAXParserFactory f = SAXParserFactory.newInstance();
        try {
            SAXParser p = f.newSAXParser();

            class ThumbTitleHandler extends DefaultHandler {
                public String status = "";

                private boolean inTitle;
                public String title = "";

                /* (非 Javadoc)
                 * @see org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String, java.lang.String, java.lang.String, org.xml.sax.Attributes)
                 */
                @Override
                public void startElement(String uri, String localName,
                        String name, Attributes attributes) {
                    if (name.equals("nicovideo_thumb_response")) {
                        status = attributes.getValue("status");
                    } else if (name.equals("title")) {
                        inTitle = true;
                    }
                }

                /* (非 Javadoc)
                 * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String, java.lang.String, java.lang.String)
                 */
                @Override
                public void endElement(String uri, String localName, String name) {
                    if (name.equals("title")) {
                        inTitle = false;
                    }
                }

                /* (非 Javadoc)
                 * @see org.xml.sax.helpers.DefaultHandler#characters(char[], int, int)
                 */
                @Override
                public void characters(char[] ch, int start, int length) {
                    if (inTitle) {
                        StringBuilder newTitle = new StringBuilder(title);
                        newTitle.append(ch, start, length);
                        title = newTitle.toString();
                    }
                }
            }

            ThumbTitleHandler h = new ThumbTitleHandler();
            InputSource i = new InputSource(thumbResponse);
            p.parse(i, h);

            if (!"ok".equals(h.status)) {
                return null;
            }

            return h.title;
        } catch (ParserConfigurationException e) {
            Logger.error(e);
        } catch (SAXException e) {
            Logger.error(e);
        } catch (IOException e) {
            Logger.error(e);
        }
        return null;
    }

    /*
     * 削除時の応答
    <?xml version="1.0" encoding="UTF-8"?>
    <nicovideo_thumb_response status="fail">
    <error>
    <code>DELETED</code>
    <description>deleted</description>
    </error>
    </nicovideo_thumb_response>
     *
     * 成功時の応答
    <?xml version="1.0" encoding="UTF-8"?>
    <nicovideo_thumb_response status="ok">
    <thumb>
    <video_id>sm9</video_id>
    <title>新・豪血寺一族 -煩悩解放 - レッツゴー！陰陽師</title>
    <description>レッツゴー！陰陽師（フルコーラスバージョン）</description>
    <thumbnail_url>http://tn-skr.smilevideo.jp/smile?i=9</thumbnail_url>
    <first_retrieve>2007-03-06T00:33:00+09:00</first_retrieve>
    <length>5:20</length>
    <view_counter>3835975</view_counter>
    <comment_num>2615213</comment_num>
    <mylist_counter>49903</mylist_counter>
    <last_res_body>ギターソロ：エリーザ ギターソロ：ミランダ ギターソロ：リディア ギターソロ：マラリヤ ... </last_res_body>
    <watch_url>http://www.nicovideo.jp/watch/sm9</watch_url>
    <thumb_type>video</thumb_type>
    <tags>
    <tag>陰陽師</tag>
    <tag>レッツゴー！陰陽師</tag>
    <tag>公式</tag>
    <tag>音楽</tag>
    <tag>懼ｧｲ時代の英雄</tag>
    <tag>弾幕推進キャンペーン中</tag>
    <tag>sm最古の動画</tag>
    <tag>sm9</tag>
    </tags>
    </thumb>
    </nicovideo_thumb_response>
     */

    // [nl] APIアクセス用
    public static final String EXTAPI_URL = "https://ext.nicovideo.jp/api/";
    public static final String FLAPI_URL  = "https://flapi.nicovideo.jp/api/";
    private static final Pattern URL_HOST_PATTERN = Pattern.compile("https?://([^/]+)");

    /**
     * [nl] クエリ文字列をマップにして返す
     *
     * @param query 対象のクエリ文字列
     * @return クエリ文字列から生成したマップ
     */
    public static Map<String, String> query2map(String query) {
        HashMap<String, String> map = new HashMap<String, String>();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length < 2) {
                    map.put(kv[0], null);
                } else {
                    try {
                        map.put(kv[0], URLDecoder.decode(kv[1], "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        Logger.debug(e);
                    }
                }
            }
        }
        return map;
    }

    /**
     * [nl] マップをクエリ文字列にして返す
     *
     * @param map 対象のマップ
     * @return マップから生成したクエリ文字列
     */
    public static String map2query(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        if (map != null) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                try {
                    String key = URLEncoder.encode(entry.getKey(), "UTF-8");
                    String value = entry.getValue();
                    if(value != null) value = URLEncoder.encode(value, "UTF-8");
                    if(sb.length() > 0) sb.append('&');
                    sb.append(key);
                    if(value != null) sb.append('=').append(value);
                } catch (UnsupportedEncodingException e) {
                    Logger.debug(e);
                }
            }
        }
        return sb.toString();
    }

    /**
     * [nl] API 名とパラメータマップから path 文字列を生成する
     *
     * @param name API 名
     * @param params パラメータマップ、不要な場合は null
     * @return path 文字列
     */
    public static String makePath(String name, Map<String, String> params) {
        String path = name, query = map2query(params);
        if (query.length() > 0) {
            path += "?" + query;
        }
        return path;
    }

    /**
     * [nl] URL にアクセスするための URLResource を生成して返す
     *
     * @param url アクセス対象の URL
     * @param requestHeader リクエストヘッダを指定した場合はレスポンスヘッダも受信する
     * @param postString POST 文字列、不要なら null
     * @return 生成した URLResource
     * @throws IOException レスポンスヘッダ受信中に問題が発生した
     */
    public static URLResource getURLResource(String url,
            HttpRequestHeader requestHeader, String postString) throws IOException {
        URLResource r = new URLResource(url);
        r.setFollowRedirects(true);
        if (requestHeader != null) {
            requestHeader.setURI(url);
            Matcher m = URL_HOST_PATTERN.matcher(url);
            if (m.find()) {
                requestHeader.setMessageHeader("Host", m.group(1));
            }
            InputStream in = null;
            if (postString != null) {
                byte[] postBody = postString.getBytes(StandardCharsets.UTF_8);
                in = new ByteArrayInputStream(postBody);
                requestHeader.setMethod("POST");
                requestHeader.setMessageHeader(HttpHeader.CONTENT_LENGTH,
                        Integer.toString(postBody.length));
            } else {
                requestHeader.setMethod("GET");
            }
            r.getResponseHeader(in, requestHeader);
        }
        return r;
    }

    /**
     * [nl] URL にアクセスして内容を文字列にして返す
     *
     * @param resource アクセス対象の URLResource、レスポンスヘッダを受信しておく必要がある
     * @return 成功すれば受信した内容文字列、失敗すれば null
     * @see #getURLResource(String, HttpRequestHeader, String)
     */
    public static String getResourceContent(URLResource resource) {
        try {
            byte[] body = resource.getResponseBody();
            if (body != null) return new String(body);
        } catch (IOException e) {
            Logger.debug(e);
        }
        return null;
    }

    /**
     * [nl] flapi にアクセスして内容文字列を返す
     *
     * @param path "/api/〜" 以降の path 文字列
     * @param requestHeader リクエストヘッダ
     * @param postString POST 文字列、不要なら null
     * @return 成功すれば受信した内容文字列、失敗すれば null
     */
    public static String getFlapiContent(String path,
            HttpRequestHeader requestHeader, String postString) {
        try {
            return getResourceContent(getURLResource(
                    FLAPI_URL.concat(path), requestHeader, postString));
        } catch (IOException e) {
            Logger.debug(e);
        }
        return null;
    }

    /**
     * [nl] flapi にアクセスして内容文字列を返す
     *
     * @param name API 名
     * @param params クエリ文字列のマップ
     * @param requestHeader リクエストヘッダ
     * @param postString POST 文字列、不要なら null
     * @return 成功すれば受信した内容文字列、失敗すれば null
     * @see #query2map(String)
     */
    public static String getFlapiContent(String name, Map<String, String> params,
            HttpRequestHeader requestHeader, String postString) {
        return getFlapiContent(makePath(name, params), requestHeader, postString);
    }

}
