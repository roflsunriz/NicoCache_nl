package dareka;

import java.util.Set;
import java.util.regex.Pattern;

import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.Resource;
import dareka.processor.StringResource;

class CorsLiar {
    static final String HEADER_ORIGIN = "Origin";
    static final String HEADER_REFERER = "Referer";
    static final String HEADER_ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    static final String HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
    static final String HEADER_ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
    static final String HEADER_ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    static final String HEADER_VARY = "Vary";


    public static class Model {
        private final Pattern origin;
        private final Pattern url;
        private final String fakeOrigin;
        private final boolean allowCredentials;
        private final Set<String> allowMethods;
        private final Set<String> allowHeaders;

        Model(Pattern origin, Pattern url, String fakeOrigin,
                boolean allowCredentials, Set<String> allowMethods,
                Set<String> allowHeaders) {
            this.origin = origin;
            this.url = url;
            this.fakeOrigin = fakeOrigin;
            this.allowCredentials = allowCredentials;
            this.allowMethods = allowMethods;
            this.allowHeaders = allowHeaders;
        }

        public Pattern getOrigin() { return origin; }
        public Pattern getUrl() { return url; }
        public String getFakeOrigin() { return fakeOrigin; }
        public boolean getAllowCredentials() { return allowCredentials; }
    }


    private final Model model;
    private String origOrigin;
    private boolean preserved;

    CorsLiar(Model model) {
        this.model = model;
    }

    public void applyToRequest(HttpRequestHeader requestHeader) {
        preserveOrigin(requestHeader);
        if (model.fakeOrigin != null) {
            if (model.fakeOrigin.equals("none")) {
                requestHeader.removeMessageHeader(HEADER_ORIGIN);
            } else {
                requestHeader.setMessageHeader(HEADER_ORIGIN, model.fakeOrigin);
            }
        }
        requestHeader.removeMessageHeader(HEADER_REFERER);
    }

    private void preserveOrigin(HttpRequestHeader requestHeader) {
        if (!preserved) {
            origOrigin = requestHeader.getMessageHeader(HEADER_ORIGIN);
        }
    }

    public Resource processPreflight(HttpRequestHeader requestHeader) {
        String method = requestHeader.getMethod();
        if (!method.equals(HttpHeader.OPTIONS)) {
            return null;
        }

        preserveOrigin(requestHeader);
        if (origOrigin == null) {
            return null;
        }

        Resource r = new StringResource("");
        applyToResource(requestHeader, r);
        if (model.allowMethods != null) {
            r.setResponseHeader(HEADER_ACCESS_CONTROL_ALLOW_METHODS,
                    String.join(", ", model.allowMethods));
        }
        if (model.allowHeaders != null) {
            r.setResponseHeader(HEADER_ACCESS_CONTROL_ALLOW_HEADERS,
                    String.join(", ", model.allowHeaders));
        }
        return r;
    }

    public void applyToResource(HttpRequestHeader requestHeader, Resource resource) {
        if (origOrigin != null) {
            resource.setResponseHeader(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, origOrigin);
        }
        if (model.allowCredentials) {
            resource.setResponseHeader(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        } else {
            resource.removeResponseHeader(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS);
        }
        resource.addCommaSeparatedResponseHeaderValue(HEADER_VARY, "Origin");
    }

}
