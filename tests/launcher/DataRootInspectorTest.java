package nicocache.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.security.KeyStore;
import java.util.Comparator;
import java.util.List;
import java.util.EnumSet;
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
            testThumbnailCacheSettings(work);
            testThumbnailCachePermissions(work);
            testMissingTlsStoreIsBlocked(work);
            testMitmCertificateRequirements(work);
            testMitmTargetListIsAccepted(work);
            testMitmTargetMismatch(work);
            testTextEncodingMigrationReport(work);
            testLegacyLayoutIsReported(work);
            System.out.println("Data-root inspection tests passed: 10");
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
        assertEquals(DataRootInspection.ItemState.NOT_APPLICABLE,
                item(inspection, "text-encoding-migration").getState(),
                "text migration waits for first core start");
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

    private static void testThumbnailCacheSettings(Path work) throws Exception {
        Path application = createApplication(work.resolve("thumbnail-app"),
                true, false);
        Files.writeString(application.resolve("certificate-targets.txt"),
                "expected.example\n", StandardCharsets.US_ASCII);
        Path data = work.resolve("thumbnail-data");
        createCompleteRoot(data);
        Path config = application.resolve("config.properties");
        String original = Files.readString(config);
        Files.createDirectories(application.resolve("defaults"));
        Files.writeString(application.resolve("defaults/thumbnail-cache.properties"),
                "cacheThumbnail=true\nthcacheFolder=thcache\n");
        Path thumbnail = data.resolve("thcache");
        Files.delete(thumbnail);
        assertEquals(DataRootInspection.ItemState.OK,
                item(DataRootInspector.inspect(application, data),
                        "directory-thcache").getState(),
                "default-enabled cache is created at startup");
        Files.writeString(config, original + "cacheThumbnail=false\n");
        DataRootInspection disabled = DataRootInspector.inspect(application, data);
        assertEquals(DataRootInspection.ItemState.NOT_APPLICABLE,
                item(disabled, "directory-thcache").getState(),
                "disabled missing thumbnails are not required");
        assertEquals(DataRootInspection.OverallState.COMPLETE,
                disabled.getState(), "disabled cache does not add attention");
        assertTrue(!Files.exists(thumbnail), "inspection must remain read-only");
        Files.writeString(thumbnail, "keep-disabled-file");
        assertEquals(DataRootInspection.ItemState.NOT_APPLICABLE,
                item(DataRootInspector.inspect(application, data),
                        "directory-thcache").getState(),
                "disabled cache does not inspect file collisions");
        Files.writeString(config, original + "cacheThumbnail=true\n");
        assertEquals(DataRootInspection.ItemState.BLOCKED,
                item(DataRootInspector.inspect(application, data),
                        "directory-thcache").getState(),
                "enabled cache file collision is blocked");
        Files.delete(thumbnail);
        Path custom = data.resolve("nested/custom-thumbnails");
        Files.writeString(config, original
                + "cacheThumbnail=true\nthcacheFolder=nested/custom-thumbnails\n");
        DataRootInspection creatable = DataRootInspector.inspect(application, data);
        assertEquals(custom, item(creatable, "directory-thcache").getPath(),
                "configured relative thumbnail path uses data root");
        assertEquals(DataRootInspection.ItemState.OK,
                item(creatable, "directory-thcache").getState(),
                "missing writable thumbnail path is created by core on start");
        assertEquals(DataRootInspection.OverallState.COMPLETE,
                creatable.getState(), "automatic thumbnail creation is complete");
        assertTrue(!Files.exists(custom), "diagnosis does not create thumbnail cache");
        for (Locale locale : List.of(Locale.ENGLISH, Locale.JAPANESE)) {
            String details = DataRootInspectionFormatter.details(creatable, messages(locale));
            assertTrue(details.contains(locale.equals(Locale.JAPANESE)
                            ? "本体起動時" : "on startup"),
                    "automatic creation guidance is localized: " + locale);
        }
        Files.createDirectories(custom);
        Files.writeString(custom.resolve("ref"), "keep-ref-collision");
        assertEquals(DataRootInspection.ItemState.BLOCKED,
                item(DataRootInspector.inspect(application, data),
                        "directory-thcache").getState(),
                "reference index collision is blocked");
        Files.delete(custom.resolve("ref"));
        Files.writeString(config, original + "cacheThumbnail=true\nthcacheFolder="
                + custom.toString().replace("\\", "/") + "\n");
        assertEquals(custom, item(DataRootInspector.inspect(application, data),
                "directory-thcache").getPath(), "absolute thumbnail cache path");
        Files.delete(custom);
        Files.delete(custom.getParent());
        Files.writeString(custom.getParent(), "keep-parent-collision");
        assertEquals(DataRootInspection.ItemState.BLOCKED,
                item(DataRootInspector.inspect(application, data),
                        "directory-thcache").getState(),
                "parent file collision is blocked");
        assertEquals("keep-parent-collision", Files.readString(custom.getParent()),
                "diagnosis preserves colliding parent file");
    }

    private static void testThumbnailCachePermissions(Path work) throws Exception {
        Path application = createApplication(work.resolve("permissions-app"), true, false);
        Path data = work.resolve("permissions-data");
        createCompleteRoot(data);
        Path locked = data.resolve("thcache");
        AclFileAttributeView acl = Files.getFileAttributeView(locked,
                AclFileAttributeView.class);
        PosixFileAttributeView posix = Files.getFileAttributeView(locked,
                PosixFileAttributeView.class);
        List<AclEntry> oldAcl = acl == null ? null : acl.getAcl();
        var oldPermissions = posix == null ? null : posix.readAttributes().permissions();
        try {
            if (acl != null) {
                List<AclEntry> denied = new ArrayList<>(oldAcl);
                denied.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY)
                        .setPrincipal(locked.getFileSystem().getUserPrincipalLookupService()
                                .lookupPrincipalByName(System.getProperty("user.name")))
                        .setPermissions(EnumSet.of(AclEntryPermission.WRITE_DATA,
                                AclEntryPermission.APPEND_DATA,
                                AclEntryPermission.WRITE_NAMED_ATTRS,
                                AclEntryPermission.WRITE_ATTRIBUTES)).build());
                acl.setAcl(denied);
            } else if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString("r-x------"));
            } else {
                throw new AssertionError("no fixture permission mechanism available");
            }
            assertTrue(!Files.isWritable(locked), "fixture must actually deny writes");
            Path config = application.resolve("config.properties");
            String original = Files.readString(config);
            Files.writeString(config, original + "cacheThumbnail=false\n");
            assertEquals(DataRootInspection.ItemState.NOT_APPLICABLE,
                    item(DataRootInspector.inspect(application, data),
                            "directory-thcache").getState(), "disabled unwritable cache");
            for (String folder : List.of("thcache", "thcache/nested/cache")) {
                Files.writeString(config, original
                        + "cacheThumbnail=true\nthcacheFolder=" + folder + "\n");
                DataRootInspection.Item result = item(
                        DataRootInspector.inspect(application, data), "directory-thcache");
                assertEquals(DataRootInspection.ItemState.BLOCKED, result.getState(),
                        "enabled unwritable path is blocked: " + folder);
                assertEquals("permission", result.getReasonKey(), "permission reason");
            }
        } finally {
            if (acl != null) {
                acl.setAcl(oldAcl);
            } else if (posix != null) {
                posix.setPermissions(oldPermissions);
            }
        }
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
        String details = DataRootInspectionFormatter.details(inspection,
                messages(Locale.JAPANESE));
        assertTrue(details.contains("サイト証明書を再生成"),
                "mismatch guidance must name the site certificate");
        assertTrue(details.contains("ca.cerの再登録は不要"),
                "mismatch guidance must preserve the trusted CA");
    }

    private static void testTextEncodingMigrationReport(Path work)
            throws Exception {
        Path application = createApplication(work.resolve("encoding-app"),
                false, true);
        Path data = work.resolve("encoding-data");
        createBaseDirectories(data);
        Files.writeString(data.resolve("proxy.pac"), "DIRECT",
                StandardCharsets.US_ASCII);
        writeSetupState(data, "complete");
        Path report = data.resolve(
                "data/text-encoding-migration-v1.properties");

        Files.writeString(report, "version=1\nissues=0\n",
                StandardCharsets.UTF_8);
        DataRootInspection complete = DataRootInspector.inspect(application,
                data);
        assertEquals(DataRootInspection.ItemState.OK,
                item(complete, "text-encoding-migration").getState(),
                "successful text migration report");

        Files.writeString(report,
                "version=1\nissues=1\nissue.1.path=proxy.pac\n",
                StandardCharsets.UTF_8);
        DataRootInspection issues = DataRootInspector.inspect(application,
                data);
        assertEquals(DataRootInspection.ItemState.ATTENTION,
                item(issues, "text-encoding-migration").getState(),
                "text migration issue report");
        String details = DataRootInspectionFormatter.details(issues,
                messages(Locale.JAPANESE));
        assertTrue(details.contains("元ファイルは変更されていません"),
                "issue guidance must state that the original is preserved");

        Files.writeString(report, "version=1\nissues=invalid\n",
                StandardCharsets.UTF_8);
        DataRootInspection malformed = DataRootInspector.inspect(application,
                data);
        assertEquals(DataRootInspection.ItemState.ERROR,
                item(malformed, "text-encoding-migration").getState(),
                "malformed text migration report");
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
