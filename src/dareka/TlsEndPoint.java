package dareka;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.Socket;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import dareka.common.Logger;

public class TlsEndPoint {
    private final static File SITE_KEYSTORE_FILE =
            NicoCachePaths.userFile("certs/site.jks");
    private final static File SITE_TARGETS_FILE =
            NicoCachePaths.userFile("certs/site.targets");
    private final static String KEYSTORE_PASSPHRASE = "NicoCache";
    private SSLSocketFactory tlsSocketFactory = null;
    private boolean ready = false;
    private Pattern mitmHostPortPattern;
    private HashSet<String> targetHosts = new HashSet<>();

    boolean init() {
        if (!SITE_KEYSTORE_FILE.exists()) {
            Logger.warning("Key store " + SITE_KEYSTORE_FILE.getPath() + " does not exist.");
            Logger.warning("TLS MitMの有効化手順は documents/tls.md を参照してください．");
            return false;
        }
        try {
            char[] passphrase = KEYSTORE_PASSPHRASE.toCharArray();
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream(SITE_KEYSTORE_FILE), passphrase);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, passphrase);

            SSLContext tlsContext = SSLContext.getInstance("TLS");
            tlsContext.init(kmf.getKeyManagers(), null, null);

            tlsSocketFactory = tlsContext.getSocketFactory();
            ready = true;
        } catch (Exception ex) {
            Logger.error(ex);
            return false;
        }

        try {
            String mitmHostPort = System.getProperty("mitmHostPort");
            if (mitmHostPort == null) {
                Logger.warning("mitmHostPort property does not exist.");
                return false;
            }
            processHostPortPatterns(mitmHostPort);
            if (!validateTargetHosts()) {
            Logger.warning("証明書の対象ドメインとMitM機能の対象ドメインが一致していません．\n"
                    + "NicoCacheCA.jarをcertificate-targets.txtで再実行してください．");
                return false;
            }
        } catch (Exception ex) {
            Logger.error(ex);
            return false;
        }
        return true;
    }

    public boolean isReady() {
        return ready;
    }

    public SSLSocket upgrade(Socket socket) throws IOException {
        SSLSocket tlsSocket = (SSLSocket)tlsSocketFactory.createSocket(
                socket,
                socket.getInetAddress().getHostAddress(),
                socket.getPort(),
                true);
        tlsSocket.setUseClientMode(false);
        tlsSocket.startHandshake();
        return tlsSocket;
    }

    public Pattern getMitmHostPortPattern() {
        return mitmHostPortPattern;
    }


    private void processHostPortPatterns(String hostports) {
        StringBuilder regex = new StringBuilder();
        for (String hostport : hostports.split("\\s+")) {
            if (hostport.isEmpty())
                continue;
            if (regex.length() != 0)
                regex.append('|');
            String[] parts = hostport.split(":", 2);
            String host = parts[0];
            String port = parts.length == 1 ? null : parts[1];
            regex.append(hostPortPatternToRegex(host, port));
            targetHosts.add(host);
        }
        if (regex.length() != 0) {
            mitmHostPortPattern = Pattern.compile(regex.toString());
        }
    }

    private String hostPortPatternToRegex(String host, String port) {
        StringBuilder regex = new StringBuilder();
        int length = host.length();
        for (int i = 0; i < length; i++) {
            char c = host.charAt(i);
            if (c == '*') {
                regex.append("[^.]++");
            } else if (c == '.') {
                regex.append("\\.");
            } else if ('a' <= c && c <= 'z' || 'A' <= c && c <= 'Z' || '0' <= c && c <= '9' || c == '-') {
                regex.append(c);
            } else {
                throw new IllegalArgumentException("Invalid host pattern: " + host);
            }
        }
        if (port != null) {
            if ("*".equals(port)) {
                regex.append(":\\d++");
            } else if (port.matches("\\d++")) {
                regex.append(':').append(port);
            } else {
                throw new IllegalArgumentException("Invalid port pattern: " + port);
            }
        } else {
            regex.append(":443");
        }

        return regex.toString();
    }

    private boolean validateTargetHosts() {
        HashSet<String> certTargets = new HashSet<>();
        if (!SITE_TARGETS_FILE.exists()) {
            return false;
        }
        try (FileReader fr = new FileReader(SITE_TARGETS_FILE);
                BufferedReader br = new BufferedReader(fr)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() == 0) continue;
                certTargets.add(line);
            }
            return certTargets.containsAll(targetHosts);
        } catch (IOException e) {
            return false;
        }
    }
}
