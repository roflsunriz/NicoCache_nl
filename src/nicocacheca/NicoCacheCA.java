package nicocacheca;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
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
 * 外部ライブラリに依存しているのでNicoCache_nl本体とは
 * 別の内部JARにしてあります．
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
    final static String CERTS_DIRECTORY_PROPERTY =
            "nicocacheca.certsDirectory";
    private static final String USER_DATA_ROOT_PROPERTY =
            "nicocache.userDataRoot";
    private static final String APPLICATION_ROOT_PROPERTY =
            "nicocache.applicationRoot";
    final static File CERTS_DIR = getCertsDirectory();
    final static File CA_KEYSTORE_FILE =
            new File(CERTS_DIR, "ca.jks");
    final static File CA_CERT_FILE =
            new File(CERTS_DIR, "ca.cer");
    final static File CA_PEM_FILE =
            new File(CERTS_DIR, "ca.pem");
    final static File SITE_KEYSTORE_FILE =
            new File(CERTS_DIR, "site.jks");
    final static File SITE_CERT_FILE =
            new File(CERTS_DIR, "site.cer");
    final static File SITE_TARGETS_FILE =
            new File(CERTS_DIR, "site.targets");
    final static String KEYSTORE_PASSPHRASE = "NicoCache";

    private static File getCertsDirectory() {
        String configured = System.getProperty(CERTS_DIRECTORY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return new File(configured).getAbsoluteFile();
        }
        String dataRoot = System.getProperty(USER_DATA_ROOT_PROPERTY);
        if (dataRoot == null || dataRoot.isBlank()) {
            dataRoot = readConfiguredDataRoot();
        }
        if (dataRoot != null && !dataRoot.isBlank()) {
            Path root = Path.of(dataRoot);
            if (!root.isAbsolute()) {
                root = applicationRoot().resolve(root);
            }
            return root.toAbsolutePath().normalize().resolve("certs").toFile();
        }
        return new File("certs").getAbsoluteFile();
    }

    private static Path applicationRoot() {
        String configured = System.getProperty(APPLICATION_ROOT_PROPERTY);
        return configured == null || configured.isBlank()
                ? Path.of("").toAbsolutePath().normalize()
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private static String readConfiguredDataRoot() {
        Path config = applicationRoot().resolve("config.properties");
        if (!Files.isRegularFile(config)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(config,
                    StandardCharsets.ISO_8859_1)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")
                        || trimmed.startsWith("!")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator < 0) {
                    separator = trimmed.indexOf(':');
                }
                if (separator <= 0 || !"userDataRoot".equals(
                        trimmed.substring(0, separator).trim())) {
                    continue;
                }
                String raw = trimmed.substring(separator + 1).trim();
                if (raw.indexOf('\\') >= 0
                        && !raw.matches(".*\\\\u[0-9a-fA-F]{4}.*")) {
                    return raw.replace("\\\\", "\\");
                }
                break;
            }
            java.util.Properties properties = new java.util.Properties();
            try (java.io.InputStream input = Files.newInputStream(config)) {
                properties.load(input);
            }
            return properties.getProperty("userDataRoot");
        } catch (IOException | java.nio.file.InvalidPathException error) {
            throw new IllegalStateException(
                    "設定ファイルのユーザーデータ先を読み取れません: "
                            + config,
                    error);
        }
    }

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
        if (hasHelpOption(args)) {
            printUsage();
            return;
        }
        String[] domains = readTargets(args);
        if (domains.length == 0) {
            throw new IllegalArgumentException(
                    "証明書対象一覧が空です。--targets-file を確認してください");
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
                throw new IllegalStateException("認証局の生成に失敗しました");
            }
            showMessage = true;
            System.out.println("CA is generated. Please import " + CA_CERT_FILE.getPath() + " to your browser!");
        }

        // CA_KEYSTORE_FILEだけあって、CA_CERT_FILEがない場合に対処.
        if (!CA_CERT_FILE.exists()) {
            if (!createCerFromJks(CA_CERT_FILE, CA_KEYSTORE_FILE)) {
                throw new IllegalStateException("CA証明書の復元に失敗しました");
            };
        };

        // CA_PEM_FILE
        if (!CA_PEM_FILE.exists()) {
            if (!createPemFromCer(CA_PEM_FILE, CA_CERT_FILE)) {
                System.out.println("[ERROR] Failed to convert to PEM.");
                throw new IllegalStateException("CA PEMの生成に失敗しました");
            }
        }

        // SITE_CERT_FILE, SITE_KEYSTORE_FILE
        if (!generateCertificate(domains)) {
            System.out.println("[ERROR] Failed to generate site certificate.");
            throw new IllegalStateException("サイト証明書の生成に失敗しました");
        }

        // SITE_TARGETS_FILE
        if (!writeSiteTargetsFile(domains)) {
            System.out.println("[ERROR] Failed to write target list file: " + SITE_TARGETS_FILE.getName());
            throw new IllegalStateException("証明書対象一覧の保存に失敗しました");
        }

        if (showMessage) {
            System.out.println("Certificate is generated for " + String.join(", ", domains));
            System.out.println("");
            System.out.println("*** Please import " + CA_CERT_FILE.getPath() + " to your browser! ***");
        }
    }

    private static boolean hasHelpOption(String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)
                    || "--headless".equals(arg)) {
                if (!"--headless".equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void printUsage() {
        System.out.println(VER_STRING);
        System.out.println("Usage: java -jar NicoCacheCA.jar"
                + " [--headless] [--targets-file=<path>]");
        System.out.println("対象ホストは指定したUTF-8の一覧ファイルから"
                + "空行と#コメントを除いて読み込みます。");
    }

    private static String[] readTargets(String[] args) {
        Path targetsFile = Path.of(System.getProperty(
                "nicocacheca.targetsFile", "certificate-targets.txt"));
        java.util.List<String> positional = new java.util.ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--headless".equals(arg)) {
                continue;
            }
            if (arg.startsWith("--targets-file=")) {
                String value = arg.substring("--targets-file=".length()).trim();
                if (value.isEmpty()) {
                    throw new IllegalArgumentException(
                            "--targets-file は空にできません");
                }
                targetsFile = Path.of(value);
                continue;
            }
            if ("--targets-file".equals(arg)) {
                if (++index >= args.length || args[index].isBlank()) {
                    throw new IllegalArgumentException(
                            "--targets-file の値がありません");
                }
                targetsFile = Path.of(args[index]);
                continue;
            }
            if (arg.startsWith("--")) {
                throw new IllegalArgumentException("不明なオプションです: " + arg);
            }
            positional.add(arg);
        }
        if (!positional.isEmpty()) {
            return validateTargets(positional);
        }
        try {
            java.util.List<String> lines = Files.readAllLines(
                    targetsFile, StandardCharsets.UTF_8);
            java.util.List<String> targets = new java.util.ArrayList<>();
            for (String line : lines) {
                String target = line.trim();
                if (!target.isEmpty() && !target.startsWith("#")) {
                    targets.add(target);
                }
            }
            return validateTargets(targets);
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "証明書対象一覧を読み取れません: " + targetsFile, error);
        }
    }

    private static String[] validateTargets(java.util.List<String> targets) {
        for (String target : targets) {
            if (target.isEmpty() || target.indexOf('\0') >= 0
                    || target.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(
                        "証明書対象ホストが不正です: " + target);
            }
        }
        return targets.toArray(new String[0]);
    }

}
