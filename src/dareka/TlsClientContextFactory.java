package dareka;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import dareka.common.Logger;

public class TlsClientContextFactory {
    private final static File CACERTS_KEYSTORE_FILE =
            NicoCachePaths.userFile("data/tlsclient/cacerts2");
    private final static String KEYSTORE_PASSPHRASE = "NicoCache";

    private static SSLContext context;

    public static SSLContext getContext() {
        return context;
    }

    public static boolean init() {
        try {
            char[] passphrase = KEYSTORE_PASSPHRASE.toCharArray();
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream(CACERTS_KEYSTORE_FILE), passphrase);

            context = SSLContext.getInstance("TLS");
            X509TrustManager myTrustManager = getTrustManager("SunX509", ks);
            X509TrustManager jreTrustManager = getTrustManager(TrustManagerFactory.getDefaultAlgorithm(), null);
            X509TrustManager[] trustManagers = { myTrustManager, jreTrustManager };
            CompositeX509TrustManager ctm = new CompositeX509TrustManager(trustManagers);
            context.init(null, new X509TrustManager[] { ctm }, null);
        } catch (Exception e) {
            Logger.error(e);
            return false;
        }
        return true;
    }

    private static X509TrustManager getTrustManager(String algorithm, KeyStore keyStore)
            throws NoSuchAlgorithmException, KeyStoreException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(algorithm);
        tmf.init(keyStore);
        for (TrustManager trustManager : tmf.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager) {
                return (X509TrustManager)trustManager;
            }
        }
        return null;
    }

    // 複数の証明書ストアを用いて検証する
    // http://codyaray.com/2013/04/java-ssl-with-multiple-keystores
    public static class CompositeX509TrustManager implements X509TrustManager {
        private X509TrustManager[] trustManagers;
        private X509Certificate[] acceptedIssuers;

        public CompositeX509TrustManager(X509TrustManager[] trustManagers) {
            this.trustManagers = trustManagers;
            joinAcceptedIssuers();
        }

        private void joinAcceptedIssuers() {
            int l = 0;
            for (X509TrustManager tm : trustManagers) {
                l += tm.getAcceptedIssuers().length;
            }
            acceptedIssuers = new X509Certificate[l];
            l = 0;
            for (X509TrustManager tm : trustManagers) {
                X509Certificate[] issuers = tm.getAcceptedIssuers();
                for (int i = 0; i < issuers.length; i++) {
                    acceptedIssuers[l+i] = issuers[i];
                }
                l += issuers.length;
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new UnsupportedOperationException("Unused");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException lastException = null;
            for (X509TrustManager tm : trustManagers) {
                try {
                    tm.checkServerTrusted(chain, authType);
                    lastException = null;
                    break;
                } catch (CertificateException e) {
                    lastException = e;
                }
            }
            if (lastException != null) {
                MessageDigest sha1 = null;
                try {
                    sha1 = MessageDigest.getInstance("SHA-1");
                } catch (NoSuchAlgorithmException e) {
                    Logger.error(e);
                    throw lastException;
                }
                StringBuilder msg = new StringBuilder();
                msg.append("サーバの証明書の検証に失敗しました:");
                for (X509Certificate cert : chain) {
                    msg.append(String.format("\n  subject=%s\n    issuer=%s\n    fingerprint=%s",
                            cert.getSubjectDN().getName(),
                            cert.getIssuerDN().getName(),
                            fingerprint(cert, sha1)));
                }
                Logger.info(msg.toString());
                throw lastException;
            }
        }

        static final String HEX_CHARS = "0123456789abcdef";
        private String fingerprint(X509Certificate cert, MessageDigest md) throws CertificateException {
            md.reset();
            byte[] bytes = md.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                if (sb.length() != 0)
                    sb.append(':');
                sb.append(HEX_CHARS.charAt((b >> 4) & 0xf));
                sb.append(HEX_CHARS.charAt(b & 0xf));
            }
            return sb.toString();
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return acceptedIssuers;
        }

    }
}
