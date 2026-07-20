package dareka.processor;

import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLConnection;

import dareka.common.Logger;

/**
 * Place to put workaround codes. They might violate standard coding regulation.
 * All dirty codes should be placed here to avoid scattering everywhere.
 * Additionally, this class does not have public access.
 *
 */
class Workarounds {
    /**
     * dirty hack!!
     *
     * change internal variable to avoid broken char problem.
     * see {@link HttpHeader}
     */
    static void dirtyChangeHttpURLConnectionImplEncoding() {
        try {
            Field encoding =
                    dirtyGetAccessibleField("sun.net.NetworkClient", "encoding");
            String originalEncoding = (String) encoding.get(null);
            encoding.set(null, "ISO8859_1");
            Logger.debugWithThread("changed NetworkClient.encoding from "
                    + originalEncoding + " to " + encoding.get(null));
        } catch (Exception e) {
            Logger.warning("failed to set workaround for multi bytes chars: "
                    + e.getMessage());
            Logger.debugWithThread(e);
        }
    }

    /**
     * dirty hack!!
     *
     * Slow server response prevent quick stop because HttpURLConnection
     * wait the response and we can not abort it in the normal way.
     * This method closes socket forcedly to avoid waiting inside
     * HttpURLConnection.
     *
     * HttpURLConnection may still wait for name resolving in
     * {@link InetAddress}
     */
    static void dirtyCloseHttpURLConnectionImplSocket(URLConnection con) {
        try {
            if (con == null) {
                return;
            }
            if (!(con instanceof HttpURLConnection)) {
                return;
            }

            Field http =
                    dirtyGetAccessibleField(
                            "sun.net.www.protocol.http.HttpURLConnection",
                            "http");
            Object httpClient = http.get(con);
            if (httpClient == null) {
                return;
            }

            Field serverSocket =
                    dirtyGetAccessibleField("sun.net.NetworkClient",
                            "serverSocket");
            Socket socket = (Socket) serverSocket.get(httpClient);
            if (socket == null) {
                return;
            }

            socket.close();
            Logger.debugWithThread("forcely close socket for " + con.getURL());
        } catch (Exception e) {
            Logger.warning("failed to set workaround for waiting server response: "
                    + e.getMessage());
            Logger.debugWithThread(e);
        }
    }

    private static Field dirtyGetAccessibleField(String className,
            String fieldName) throws ClassNotFoundException,
            NoSuchFieldException {
        Class<?> type = Class.forName(className);
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }
}
