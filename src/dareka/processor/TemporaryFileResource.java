package dareka.processor;

import java.io.File;
import java.io.IOException;
import java.net.Socket;

import dareka.common.Config;

// 送信が終わったらファイルを削除するResource
public class TemporaryFileResource extends LocalFileResource {
    private volatile File fileToDelete = null;
    public TemporaryFileResource(File deleteFile) throws IOException {
        super(deleteFile);
        fileToDelete = deleteFile;
    }

    @Override
    public void stopTransfer() {
        super.stopTransfer();
        if (fileToDelete != null) {
            fileToDelete.delete();
        }
    }

    @Override
    public boolean endEnsuredTransferTo(Socket receiver,
            HttpRequestHeader requestHeader, Config config) throws IOException {
        boolean result = super.endEnsuredTransferTo(receiver, requestHeader, config);
        if (fileToDelete.exists()) {
            fileToDelete.delete();
        }
        return result;
    }
}
