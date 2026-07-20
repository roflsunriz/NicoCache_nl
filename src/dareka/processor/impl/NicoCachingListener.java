package dareka.processor.impl;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.FutureTask;

import dareka.processor.HttpResponseHeader;
import dareka.processor.TransferListener;

/** 廃止済み単一ファイル取得処理用TransferListenerのバイナリ互換shim。 */
public class NicoCachingListener implements TransferListener {
    public NicoCachingListener(Cache cache, FutureTask<String> titleTask,
            InputStream input, NLEventSource eventSource, long contentLength) {
    }

    @Override
    public void onResponseHeader(HttpResponseHeader responseHeader) {
        onResponseHeaderCore(responseHeader);
    }

    public void onResponseHeaderCore(HttpResponseHeader responseHeader) {
    }

    @Override
    public void onTransferBegin(OutputStream output) {
    }

    @Override
    public void onTransferring(byte[] buffer, int length) {
    }

    @Override
    public void onTransferEnd(boolean completed) {
        onTransferEndCore(completed);
    }

    public void onTransferEndCore(boolean completed) {
    }
}
