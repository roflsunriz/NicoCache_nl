package dareka.processor.impl;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import dareka.processor.HttpRequestHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;

/**
 * 廃止済みDMC-HLS新規取得経路のバイナリ互換shim。
 * 保存済みHLSの配信はCacheDirProcessorとCmafUseCacheProcessorが担当する。
 */
public class HlsCachingProcessor implements Processor {
    public static final ReentrantLock giantLock = new ReentrantLock();
    private static final String[] NO_METHODS = new String[0];

    public HlsCachingProcessor(Executor executor) {
    }

    @Override
    public String[] getSupportedMethods() {
        return NO_METHODS;
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
