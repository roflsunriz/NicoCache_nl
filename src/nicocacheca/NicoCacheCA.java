package nicocacheca;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Calendar;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

/**
 * NicoCache_nl+mod+mod用の認証局と証明書を作るよ
 *
 * 外部ライブラリに依存しているのでNicoCache_nl本体へ
 * 組み込むのを避けて別アプリケーションにしてあります．
 *
 * Required libraries:
 *  - Bouncy Castle
 *    - bcprov.jar (bcprov-jdk15on-xxx.jar)
 *    - bcpkix.jar (bcpkix-jdk15on-xxx.jar)
 *    - bcutil.jar (bcutil-jdk15on-xxx.jar)
 */
public class NicoCacheCA {
    // 「生成する・しない」、「上書きする・しない」の動作が一言で表わせない.

    final static String VER_STRING = "NicoCacheCA 210723+240314";

    final static String CA_DN = "CN=NicoCache_nl CA";
    final static File CERTS_DIR = new File("certs/");
    final static File CA_KEYSTORE_FILE = new File("certs/ca.jks");
    final static File CA_CERT_FILE = new File("certs/ca.cer");
    final static File CA_PEM_FILE = new File("certs/ca.pem");
    final static File SITE_KEYSTORE_FILE = new File("certs/site.jks");
    final static File SITE_CERT_FILE = new File("certs/site.cer");
    final static File SITE_TARGETS_FILE = new File("certs/site.targets");
    final static String KEYSTORE_PASSPHRASE = "NicoCache";

    public static boolean generateCA() {
        try {
            KeyPair kp = genKey();

            X509v3CertificateBuilder certBuilder = makeDefaultCertificateBuilder(
                    new X500Name(CA_DN),
                    new X500Name(CA_DN),
                    kp.getPublic()
            ).addExtension(
                    Extension.keyUsage,
                    true,
                    new KeyUsage(KeyUsage.keyCertSign)
            ).addExtension(
                    Extension.extendedKeyUsage,
                    true,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
            );

            X509Certificate cert = caSign(certBuilder, kp.getPrivate());

            try (FileOutputStream fos = new FileOutputStream(CA_CERT_FILE)) {
                fos.write(cert.getEncoded());
            }

            char[] passphrase = KEYSTORE_PASSPHRASE.toCharArray();
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(null, null);
            ks.setKeyEntry("mykey", kp.getPrivate(), passphrase, new Certificate[] { cert });
            ks.store(new FileOutputStream(CA_KEYSTORE_FILE), passphrase);

            return true;
        } catch (GeneralSecurityException | OperatorCreationException | IOException ex) {
            ex.printStackTrace();
        }
        return false;
    }

    public static boolean generateCertificate(String[] domains) {
        try {
            KeyStore caKeyStore = KeyStore.getInstance("JKS");
            char[] passphrase = KEYSTORE_PASSPHRASE.toCharArray();
            caKeyStore.load(new FileInputStream(CA_KEYSTORE_FILE), passphrase);
            PrivateKey caPrivateKey = (PrivateKey)caKeyStore.getKey("mykey", passphrase);
            X509Certificate caCert = (X509Certificate)caKeyStore.getCertificate("mykey");
            X500Name issuer = new JcaX509CertificateHolder(caCert).getSubject();

            KeyPair kp = genKey();

            ASN1EncodableVector subjectAltNames = new ASN1EncodableVector();
            for (String domain : domains) {
                subjectAltNames.add(new GeneralName(GeneralName.dNSName, domain));
            }

            X500NameBuilder subjectBuilder = new X500NameBuilder(BCStyle.INSTANCE);
            subjectBuilder.addRDN(BCStyle.CN, domains[0]);

            X509v3CertificateBuilder certBuilder = makeDefaultCertificateBuilder(
                    issuer,
                    subjectBuilder.build(),
                    kp.getPublic()
            ).addExtension(
                    Extension.keyUsage,
                    true,
                    new KeyUsage(KeyUsage.digitalSignature)
            ).addExtension(
                    Extension.extendedKeyUsage,
                    true,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
            ).addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    new DERSequence(subjectAltNames)
            );

            X509Certificate cert = caSign(certBuilder, caPrivateKey);

            try (FileOutputStream fos = new FileOutputStream(SITE_CERT_FILE)) {
                fos.write(cert.getEncoded());
            }

            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(null, null);
            ks.setKeyEntry("mykey", kp.getPrivate(), passphrase, new Certificate[] { cert });
            ks.store(new FileOutputStream(SITE_KEYSTORE_FILE), passphrase);

            return true;
        } catch (GeneralSecurityException | OperatorCreationException | IOException ex) {
            ex.printStackTrace();
        }
        return false;
    }

    // src(ca.jks)からdest(ca.cer)を作ります.
    public static boolean createCerFromJks(File dest, File src) {
        byte[] certbin = null;
        try (FileInputStream fis = new FileInputStream(src)) {
            KeyStore keystore = KeyStore.getInstance("JKS");
            char[] passphrase = KEYSTORE_PASSPHRASE.toCharArray();
            keystore.load(fis, passphrase);
            certbin = keystore.getCertificate("mykey").getEncoded();
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to read keystore file '" + src + "'");
            return false;
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
            e.printStackTrace();
            return false;
        }

        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write(certbin);
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to write '" + src + "'");
            return false;
        }
        System.out.println("Generated: '" + dest + "'");
        return true;
    }

    // fromCert(ca.cer)からtoPem(ca.pem)を作ります.
    public static boolean createPemFromCer(File toPem, File fromCert) {
        byte[] certbin = null;
        try {
            certbin = Files.readAllBytes(fromCert.toPath());
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to read certificate file '" + fromCert + "'");
            return false;
        }

        try (FileOutputStream fos = new FileOutputStream(toPem);
             OutputStreamWriter osw = new OutputStreamWriter(fos);
             PemWriter pw = new PemWriter(osw)) {
            PemObject po = new PemObject("CERTIFICATE", certbin);
            pw.writeObject(po);
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to write file '" + toPem + "'");
            return false;
        }
        System.out.println("Generated: '" + toPem + "'");
        return true;
    }

    static KeyPair genKey() throws GeneralSecurityException {
        KeyPairGenerator ecdsa = KeyPairGenerator.getInstance("ECDSA", BouncyCastleProvider.PROVIDER_NAME);
        ecdsa.initialize(new ECGenParameterSpec("prime256v1"));
        KeyPair kp = ecdsa.generateKeyPair();
        return kp;
    }

    static X509v3CertificateBuilder makeDefaultCertificateBuilder(X500Name issuer, X500Name subject, PublicKey pk)
            throws CertIOException {
        boolean isCA = issuer.equals(subject);

        Calendar notBefore = Calendar.getInstance();
        Calendar notAfter = Calendar.getInstance();
        notAfter.add(Calendar.YEAR, 10);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer,
                isCA ? BigInteger.ONE : BigInteger.valueOf(System.currentTimeMillis()),
                notBefore.getTime(),
                notAfter.getTime(),
                subject,
                pk
        );
        certBuilder.addExtension(
                Extension.basicConstraints,
                true,
                new BasicConstraints(isCA)
        );
        return certBuilder;
    }

    static X509Certificate caSign(X509v3CertificateBuilder certBuilder, PrivateKey caPrivateKey)
            throws OperatorCreationException, CertificateException {
        ContentSigner signer =
                new JcaContentSignerBuilder("SHA256withECDSA").build(caPrivateKey);
        X509CertificateHolder certHolder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certHolder);
        return cert;
    }

    static boolean writeSiteTargetsFile(String[] domains) {
        try (FileWriter fw = new FileWriter(SITE_TARGETS_FILE);
                BufferedWriter bw = new BufferedWriter(fw)) {
            for (String domain : domains) {
                bw.write(domain);
                bw.newLine();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println(VER_STRING);
            System.out.println("Usage: java -jar dist/NicoCacheCA.jar domain ...");
            System.out.println("e.g. \"*.nicovideo.jp\" \"*.ce.nicovideo.jp\" \"*.smilevideo.jp\" ...");
            return;
        }
        if (!CERTS_DIR.exists()) {
            CERTS_DIR.mkdirs();
        }
        Security.addProvider(new BouncyCastleProvider());

        boolean showMessage = false;
        // CA_CERT_FILE, CA_KEYSTORE_FILE
        if (!CA_KEYSTORE_FILE.exists()) {
            if (!generateCA()) {
                System.out.println("[ERROR] Failed to generate CA.");
                return;
            }
            showMessage = true;
            System.out.println("CA is generated. Please import " + CA_CERT_FILE.getPath() + " to your browser!");
        }

        // CA_KEYSTORE_FILEだけあって、CA_CERT_FILEがない場合に対処.
        if (!CA_CERT_FILE.exists()) {
            if (!createCerFromJks(CA_CERT_FILE, CA_KEYSTORE_FILE)) {
                return;
            };
        };

        // CA_PEM_FILE
        if (!CA_PEM_FILE.exists()) {
            if (!createPemFromCer(CA_PEM_FILE, CA_CERT_FILE)) {
                System.out.println("[ERROR] Failed to convert to PEM.");
                return;
            }
        }

        // SITE_CERT_FILE, SITE_KEYSTORE_FILE
        if (!generateCertificate(args)) {
            System.out.println("[ERROR] Failed to generate site certificate.");
            return;
        }

        // SITE_TARGETS_FILE
        if (!writeSiteTargetsFile(args)) {
            System.out.println("[ERROR] Failed to write target list file: " + SITE_TARGETS_FILE.getName());
            return;
        }

        if (showMessage) {
            System.out.println("Certificate is generated for " + String.join(", ", args));
            System.out.println("");
            System.out.println("*** Please import " + CA_CERT_FILE.getPath() + " to your browser! ***");
        }
    }

}
