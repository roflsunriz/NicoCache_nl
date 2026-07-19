package dareka.processor;

import java.io.IOException;
import java.net.Socket;

import dareka.Main;
import dareka.common.CloseUtil;
import dareka.common.Config;
import dareka.common.Logger;

public class MitmResource extends Resource {
    @Override
    public boolean transferTo(Socket receiver, HttpRequestHeader requestHeader,
            Config config) throws IOException {

        HttpResponseHeader responseHeader =
                new HttpResponseHeader(
                        "HTTP/1.1 200 Connection established\r\n\r\n");

        execSendingHeaderSequence(receiver.getOutputStream(), responseHeader);

        Logger.debugWithThread("start mitm");

        Socket tlsSocket = Main.getTlsEndPoint().upgrade(receiver);
        try {
            Main.handleTlsLoopback(tlsSocket);
        } finally {
            CloseUtil.close(tlsSocket);
        }
        return false;
    }
}
