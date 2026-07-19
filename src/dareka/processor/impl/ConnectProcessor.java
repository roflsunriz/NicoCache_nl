package dareka.processor.impl;

import java.io.IOException;
import java.net.Socket;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import dareka.Main;
import dareka.common.Config;
import dareka.common.ConfigObserver;
import dareka.processor.HttpRequestHeader;
import dareka.processor.MitmResource;
import dareka.processor.Processor;
import dareka.processor.Resource;

public class ConnectProcessor implements Processor, ConfigObserver {
    private static final String[] SUPPORTED_METHODS = new String[] { "CONNECT" };

    private static volatile boolean propertyLoaded = false;
    private static volatile boolean mitmMode = false;
    private static volatile Pattern mitmHostPortPattern;

    static {
        Config.addObserver(new ConnectProcessor());
    }

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
        String hostport = requestHeader.getURI();

        if (!propertyLoaded) {
            if (Boolean.getBoolean("enableMitm") &&
                    Main.getTlsEndPoint().isReady()) {
                mitmHostPortPattern = Main.getTlsEndPoint().getMitmHostPortPattern();
                if (mitmHostPortPattern == null) {
                    mitmMode = false;
                } else {
                    mitmMode = true;
                }
            }
            propertyLoaded = true;
        }

        if (mitmMode) {
            Matcher m = mitmHostPortPattern.matcher(hostport);
            if (m.matches()) {
                return new MitmResource();
            }
        }

        return Resource.get(Resource.Type.HOSTPORT, requestHeader.getURI());
    }

    @Override
    public void update(Config config) {
        propertyLoaded = false;
    }
}
