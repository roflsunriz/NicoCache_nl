package dareka.processor.impl;

import java.io.IOException;
import java.net.Socket;
import java.util.regex.Pattern;

import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;

public class GetPostProcessor implements Processor {
    private static final String[] SUPPORTED_METHODS =
            new String[] { HttpHeader.GET, HttpHeader.POST, HttpHeader.HEAD,
                HttpHeader.PUT, HttpHeader.DELETE, HttpHeader.PATCH, HttpHeader.OPTIONS };

    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return null;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {
        return Resource.get(Resource.Type.URL, requestHeader.getURI());
    }
}
