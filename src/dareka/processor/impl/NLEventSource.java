package dareka.processor.impl;

import dareka.common.Logger;
import dareka.extensions.SystemEventListener;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.URLResource;

/**
 * [nl] イベントの発生元を保持するクラス
 * @since NicoCache_nl+111111mod
 */
public class NLEventSource implements SystemEventListener.EventSource {

    protected String url, content;
    protected HttpRequestHeader requestHeader;
    protected HttpResponseHeader responseHeader;
    protected URLResource resource;
    protected Cache cache;

    public NLEventSource(String url, HttpRequestHeader requestHeader,
            String content) {
        this.url = url;
        this.requestHeader = requestHeader;
        this.content = content;
    }

    public NLEventSource(String url, HttpRequestHeader requestHeader,
            URLResource resource) {
        this.url = url;
        this.requestHeader = requestHeader;
        this.resource = resource;
    }

    public NLEventSource(String url, HttpRequestHeader requestHeader,
            Cache cache) {
        this.url = url;
        this.requestHeader = requestHeader;
        this.cache = cache;
    }

    public String getURL() {
        if (url == null && requestHeader != null) {
            url = requestHeader.getURI();
        }
        return url;
    }

    public String getContent() {
        if (content == null && resource != null) {
            try {
                content = new String(resource.getResponseBody(), "UTF-8");
            } catch (Exception e) {
                Logger.error(e);
            }
        }
        return content;
    }

    public HttpRequestHeader getRequestHeader() {
        return requestHeader;
    }

    public HttpResponseHeader getResponseHeader() {
        if (responseHeader == null && resource != null) {
            try {
                responseHeader = resource.getResponseHeader(null, requestHeader);
            } catch (Exception e) {
                Logger.error(e);
            }
        }
        return responseHeader;
    }

    public URLResource getURLResoruce() {
        return resource;
    }

    public Cache getCache() {
        return cache;
    }

    void setResponseHeader(HttpResponseHeader responseHeader) {
        this.responseHeader = responseHeader;
    }

}
