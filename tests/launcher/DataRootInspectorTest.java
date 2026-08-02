package nicocache.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

/** Regression tests for user-data-root migration diagnostics. */
public final class DataRootInspectorTest {
    private DataRootInspectorTest() {
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("nicocache-data-root-test-");
        try {
            testIncompleteRoot(work);
            testCompleteRoot(work);
            testMissingTlsStoreIsBlocked(work);
            testMitmCertificateRequirements(work);
            testMitmTargetListIsAccepted(work);
            testMitmTargetMismatch(work);
            testLegacyLayoutIsReported(work);
            System.out.println("Data-root inspection tests passed: 7");
        } finally {
            deleteTree(work);
        }
    }

    private static void testIncompleteRoot(Path work) throws Exception {
        Path application = createApplication(work.resolve("incomplete-app"),
                false, true);
        Path data = work.resolve("incomplete-data");
        Files.createDirectories(data);

        DataRootInspection inspection = DataRootInspector.inspect(application,
                data);
        assertEquals(DataRootInspection.OverallState.ATTENTION,
                inspection.getState(), "incomplete root state");
        assertEquals(DataRootInspection.ItemState.MISSING,
                item(inspection, "directory-cache").getState(),
                "missing cache directory");
        assertEquals(DataRootInspection.ItemState.FALLBACK,
                item(inspection, "tls-client-store").getState(),
                "application TLS fallback");
        assertEquals(DataRootInspection.ItemState.ATTENTION,
                item(inspection, "setup-record").getState(),
                "missing setup record");
        assertEquals("missing.create.list",
                item(inspection, "directory-list").getReasonKey(),
                "missing list directory reason");
        assertEquals(DataRootInspection.ItemState.ATTENTION,
                item(inspection, "mitm-certificates").getState(),
                "disabled MitM is incomplete");
        assertEquals("disabled.required",
                item(inspection, "mitm-certificates").getReasonKey(),
                "disabled MitM reason");
        assertEquals(DataRootInspection.ItemState.ATTENTION,
                item(inspection, "proxy-pac").getState(),
                "missing proxy PAC is incomplete");
        ResourceBundle messages = messages(Locale.JAPANESE);
        String details = DataRootInspectionFormatter.details(inspection,
                messages);
        assertTrue(details.contains("LST用 list フォルダー"),
                "list purpose must be visible in diagnosis");
        assertTrue(details.contains("本体起動に影響しません"),
                "optional list directory guidance must be visible");
        assertTrue(details.contains("現行ニコニコ動画ではHTTPS MitMが必須です"),
                "disabled MitM guidance must be visible");
        assertTrue(details.contains("proxy.pacを配置してください"),
                "proxy PAC guidance must be visible");
        assertEquals(1, inspection.getExitCode(),
                "incomplete root exit code");
    }

    private static void testCompleteRoot(Path work) throws Exception {
        Path application = createApplication(work.resolve("complete-app"),
                true, false);
        Files.writeString(application.resolve("certificate-targets.txt"),
                "expected.example\n", StandardCharsets.US_ASCII);
        Path data = work.resolve("complete-data");
        createCompleteRoot(data);

        DataRootInspection inspection = DataRootInspector.inspect(application,
                data);
        assertEquals(DataRootInspection.OverallState.COMPLETE,
                inspection.getState(), "complete root state");
        assertEquals(DataRootInspection.ItemState.OK,
                item(inspection, "setup-record").getState(),
                "complete setup record");
        assertEquals(DataRootInspection.ItemState.OK,
                item(inspection, "tls-client-store").getState(),
                "user TLS store");
        assertEquals(DataRootInspection.ItemState.OK,
                item(inspection, "proxy-pac").getState(),
                "proxy PAC");
        assertEquals(0, inspection.getExitCode(),
                "complete root exit code");
        for (Locale locale : List.of(Locale.ENGLISH, Locale.JAPANESE)) {
            ResourceBundle messages = messages(locale);
            String details = DataRootInspectionFormatter.details(inspection,
                    messages);
            assertTrue(details.contains("cache"),
                    "localized diagnosis must contain item details: " + locale);
            assertTrue(details.contains(data.toString()),
                    "localized diagnosis must contain the selected root: "
                            + locale);
            String actionMarker = locale.equals(Locale.JAPANESE)
                    ? "対応: " : "Action: ";
            assertEquals(inspection.getItems().size(),
                    countOccurrences(details, actionMarker),
                    "localized diagnosis must provide an action for every item: "
                            + locale);
        }
    }

    private static ResourceBundle messages(Locale locale) {
        return ResourceBundle.getBundle("nicocache.launcher.messages", locale,
                ResourceBundle.Control.getNoFallbackControl(
                        ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    private static void testMissingTlsStoreIsBlocked(Path work)
            throws Exception {
        Path application = createApplication(work.resolve("no-tls-app"),
                false, false);
        Path data = work.resolve("no-tls-data");
        createBaseDirectories(data);
        writeSetupState(data, "complete");

        DataRootInspection inspection = DataRootInspector.inspect(application,
                data);
        assertEquals(DataRootInspection.OverallState.BLOCKED,
                inspection.getState(), "missing TLS store state");
        assertEquals(DataRootInspection.ItemState.BLOCKED,
                item(inspection, "tls-client-store").getState(),
                "missing TLS store");
        assertEquals(DataRootInspection.ItemState.ATTENTION,
                item(inspection, "mitm-certificates").getState(),
                "disabled MitM is incomplete");
    }

    private static void testMitmCertificateRequirements(Path work)
            throws Exception {
        Path application = createApplication(work.resolve("mitm-app"), true,
                false);
        Path data = work.resolve("mitm-data");
        createBaseDirectories(data);
        Files.createDirectories(data.resolve("data/tlsclient"));
        writeKeyStore(data.resolve("data/tlsclient/cacerts2"));
        writeSetupState(data, "complete");

        DataRootInspection inspection = DataRootInspector.inspect(application,
                data);
        assertEquals(DataRootInspection.OverallState.BLOCKED,
                inspection.getState(), "missing MitM certificate state");
        assertEquals(DataRootInspection.ItemState.BLOCKED,
                item(inspection, "site-keystore").getState(),
                "missing site keystore");
        assertEquals(DataRootInspection.ItemState.BLOCKED,
                item(inspection, "site-targets").getState(),
                "missing site targets");
        assertEquals(DataRootInspection.ItemState.ATTENTION,
                item(inspection, "certificate-targets").getState(),
                "missing certificate source list is not a runtime block");
    }

    private static void testLegacyLayoutIsReported(Path work) throws Exception {
        Path application = createApplication(work.resolve("legacy-app"),
                false, false);
        Path data = work.resolve("legacy-data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("config.ini"), "legacy",
                StandardCharsets.US_ASCII);

        DataRootInspection inspection = DataRootInspector.inspect(application,
                data);
        assertEquals(DataRootInspection.ItemState.ATTENTION,
                item(inspection, "legacy-layout").getState(),
                "legacy layout marker");
    }

    private static void testMitmTargetListIsAccepted(Path work)
            throws Exception {
        Path application = createApplication(work.resolve("accepted-app"), true,
                false);
        Files.writeString(application.resolve("config.properties"),
                "enableMitm=true" + System.lineSeparator()
                        + "mitmHostPort=expected.example" + System.lineSeparator(),
                StandardCharsets.ISO_8859_1);
        Path data = work.resolve("accepted-data");
        createBaseDirectories(data);
        Files.createDirectories(data.resolve("data/tlsclient"));
        writeKeyStore(data.resolve("data/tlsclient/cacerts2"));
        writeKeyStore(data.resolve("certs/site.jks"));
        Files.writeString(data.resolve("certs/site.targets"),
                "expected.example\n", StandardCharsets.US_ASCII);
        writeSetupState(data, "complete");

        DataRootInspection inspection = DataRootInspector.inspect(application,
                data);
        assertEquals(DataRootInspection.ItemState.OK,
                item(inspection, "site-targets").getState(),
                "matching MitM target list");
        assertEquals("targets.present",
                item(inspection, "site-targets").getReasonKey(),
                "non-empty MitM target list reason");
    }

    private static void testMitmTargetMismatch(Path work) throws Exception {
        Path application = createApplication(work.resolve("mismatch-app"), true,
                false);
        Files.writeString(application.resolve("config.properties"),
                "enableMitm=true" + System.lineSeparator()
                        + "mitmHostPort=expected.example" + System.lineSeparator(),
                StandardCharsets.ISO_8859_1);
        Path data = work.resolve("mismatch-data");
        createBaseDirectories(data);
        Files.createDirectories(data.resolve("data/tlsclient"));
        writeKeyStore(data.resolve("data/tlsclient/cacerts2"));
        writeKeyStore(data.resolve("certs/site.jks"));
        Files.writeString(data.resolve("certs/site.targets"), "other.example\n",
                StandardCharsets.US_ASCII);
        writeSetupState(data, "complete");

        DataRootInspection inspection = DataRootInspector.inspect(application,
                data);
        assertEquals(DataRootInspection.ItemState.BLOCKED,
                item(inspection, "site-targets").getState(),
                "MitM target mismatch");
    }

    private static Path createApplication(Path application,
            boolean enableMitm, boolean includeFallbackStore) throws Exception {
        Files.createDirectories(application);
        String config = "enableMitm=" + enableMitm + System.lineSeparator();
        if (enableMitm) {
            config += "mitmHostPort=expected.example"
                    + System.lineSeparator();
        }
        Files.writeString(application.resolve("config.properties"), config,
                StandardCharsets.ISO_8859_1);
        if (includeFallbackStore) {
            Files.createDirectories(application.resolve("data/tlsclient"));
            writeKeyStore(application.resolve("data/tlsclient/cacerts2"));
        }
        return application;
    }

    private static void createCompleteRoot(Path data) throws Exception {
        createBaseDirectories(data);
        Files.createDirectories(data.resolve("data/tlsclient"));
        writeKeyStore(data.resolve("data/tlsclient/cacerts2"));
        writeKeyStore(data.resolve("certs/site.jks"));
        Files.writeString(data.resolve("certs/site.targets"),
                "expected.example\n", StandardCharsets.US_ASCII);
        Files.writeString(data.resolve("certs/ca.cer"), "test-ca",
                StandardCharsets.US_ASCII);
        Files.writeString(data.resolve("proxy.pac"), "DIRECT",
                StandardCharsets.US_ASCII);
        writeSetupState(data, "complete");
    }

    private static void createBaseDirectories(Path data) throws IOException {
        for (String directory : List.of(
                "cache", "certs", "cvcache", "data", "extensions",
                "list", "local", "nlFilters", "thcache")) {
            Files.createDirectories(data.resolve(directory));
        }
    }

    private static void writeSetupState(Path data, String status)
            throws IOException {
        Path statePath = data.resolve("data/first-run-setup.properties");
        Properties state = new Properties();
        state.setProperty("status", status);
        state.setProperty("userDataRoot", data.toAbsolutePath().toString());
        try (var output = Files.newOutputStream(statePath)) {
            state.store(output, "test");
        }
    }

    private static void writeKeyStore(Path path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, "NicoCache".toCharArray());
        try (var output = Files.newOutputStream(path)) {
            keyStore.store(output, "NicoCache".toCharArray());
        }
    }

    private static DataRootInspection.Item item(DataRootInspection inspection,
            String id) {
        for (DataRootInspection.Item item : inspection.getItems()) {
            if (id.equals(item.getId())) {
                return item;
            }
        }
        throw new AssertionError("診断項目がありません: " + id);
    }

    private static void assertEquals(Object expected, Object actual,
            String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
