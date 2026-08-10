package dareka;

import dareka.FirstRunWizard.FirstRunWizardPanel;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

public final class FirstRunSetupTest {
    private final Path repository;
    private final Path sandbox;
    private final Path previewDirectory;

    private FirstRunSetupTest(Path repository, Path sandbox,
            Path previewDirectory) {
        this.repository = repository;
        this.sandbox = sandbox;
        this.previewDirectory = previewDirectory;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: FirstRunSetupTest <repository> <sandbox> <preview>");
        }
        FirstRunSetupTest test = new FirstRunSetupTest(
                Path.of(args[0]).toAbsolutePath().normalize(),
                Path.of(args[1]).toAbsolutePath().normalize(),
                Path.of(args[2]).toAbsolutePath().normalize());
        test.run();
    }

    private void run() throws Exception {
        Files.createDirectories(sandbox);
        Files.createDirectories(previewDirectory);
        testPathResolution();
        testSystemAssetsRemainSeparated();
        testSeparatedSetupFiles();
        testRequirementDetection();
        testHeadlessLaunchOptions();
        testSuccessfulFileSetup();
        testSetupWithoutSystemIntegration();
        testExistingFilesArePreserved();
        testRollbackAfterCertificateFailure();
        testRollbackAfterIntegrationFailure();
        testCertificateTargets();
        testLocalizedKeysMatch();
        testWizardControlsAndRender();
        System.out.println("First-run setup tests passed: 13");
    }

    private void testPathResolution() throws Exception {
        Path app = freshSandbox("paths/application");
        Path data = freshSandbox("paths/data");
        Path absoluteCache = freshSandbox("paths/absolute-cache");
        String oldApplication = System.getProperty(
                NicoCachePaths.APPLICATION_ROOT_PROPERTY);
        String oldLauncher = System.getProperty("jpackage.app-path");
        String oldCache = System.getProperty("cacheFolder");
        String oldThumbnail = System.getProperty("thcacheFolder");
        String oldConverted = System.getProperty("convertedCacheFolder");
        try {
            System.setProperty(
                    NicoCachePaths.APPLICATION_ROOT_PROPERTY,
                    app.toString());
            System.setProperty("jpackage.app-path",
                    app.resolve("NicoCache_nl.exe").toString());
            Files.writeString(
                    app.resolve("config.ini"),
                    "userDataRoot="
                            + sandbox.resolve("legacy-config-data")
                            + System.lineSeparator(),
                    StandardCharsets.ISO_8859_1);
            Files.writeString(
                    app.resolve("config.properties"),
                    "userDataRoot="
                            + data.toString()
                            + System.lineSeparator(),
                    StandardCharsets.ISO_8859_1);

            System.setProperty("cacheFolder", absoluteCache.toString());
            assertEquals(
                    absoluteCache,
                    NicoCachePaths.cacheDirectory().toPath(),
                    "absolute cache path must be preserved");
            System.setProperty("thcacheFolder", "thumbnail/custom");
            assertEquals(
                    data.resolve("thumbnail/custom"),
                    NicoCachePaths.thumbnailCacheDirectory().toPath(),
                    "relative thumbnail path must use data root");
            System.clearProperty("convertedCacheFolder");
            assertEquals(
                    data.resolve("cvcache"),
                    NicoCachePaths.convertedCacheDirectory().toPath(),
                    "default converted cache path must use data root");
            assertEquals(
                    app.resolve("defaults"),
                    NicoCachePaths.applicationPath("defaults"),
                    "distribution path must use application root");

            Path systemCacerts = app.resolve("data/tlsclient/cacerts2");
            Files.createDirectories(systemCacerts.getParent());
            Files.writeString(systemCacerts, "system-cacerts");
            assertEquals(
                    systemCacerts,
                    NicoCachePaths.tlsClientCacertsFile().toPath(),
                    "TLS client store must fall back to system assets");
            Path userCacerts = data.resolve("data/tlsclient/cacerts2");
            Files.createDirectories(userCacerts.getParent());
            Files.writeString(userCacerts, "user-cacerts");
            assertEquals(
                    userCacerts,
                    NicoCachePaths.tlsClientCacertsFile().toPath(),
                    "user TLS client store must override system assets");

            boolean rejected = false;
            try {
                NicoCachePaths.userPath("../outside");
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assertTrue(rejected, "path traversal must be rejected");

            Files.writeString(
                    app.resolve("config.properties"),
                    "userDataRoot=\\u0000" + System.lineSeparator(),
                    StandardCharsets.ISO_8859_1);
            boolean invalidConfigRejected = false;
            try {
                NicoCachePaths.dataRoot();
            } catch (IllegalStateException expected) {
                invalidConfigRejected = true;
                assertContains(expected.getMessage(), "userDataRoot",
                        "invalid configured path error");
            }
            assertTrue(invalidConfigRejected,
                    "invalid configured data root must be rejected");

            Files.delete(app.resolve("config.properties"));
            Files.writeString(
                    app.resolve(NicoCachePaths.PORTABLE_FLAG),
                    "portable");
            assertEquals(
                    app,
                    NicoCachePaths.dataRoot(),
                    "portable mode must use application root");

            Path linuxLauncher = app.resolve("NicoCache_nl");
            assertEquals(
                    app,
                    PlatformSupport.applicationRootFromLauncher(
                            linuxLauncher, PlatformSupport.Kind.LINUX),
                    "Linux launcher must resolve the flat application root");
            assertEquals(
                    linuxLauncher,
                    PlatformSupport.launcherPath(app, PlatformSupport.Kind.LINUX),
                    "Linux flat launcher path");
            Path macLauncher = app.resolve("NicoCache_nl");
            assertEquals(
                    app,
                    PlatformSupport.applicationRootFromLauncher(
                            macLauncher, PlatformSupport.Kind.MACOS),
                    "macOS launcher must resolve the flat application root");
            assertEquals(
                    macLauncher,
                    PlatformSupport.launcherPath(app, PlatformSupport.Kind.MACOS),
                    "macOS flat launcher path");
        } finally {
            restoreProperty(
                    NicoCachePaths.APPLICATION_ROOT_PROPERTY,
                    oldApplication);
            restoreProperty("jpackage.app-path", oldLauncher);
            restoreProperty("cacheFolder", oldCache);
            restoreProperty("thcacheFolder", oldThumbnail);
            restoreProperty("convertedCacheFolder", oldConverted);
        }
        System.out.println("PASS application, user, configured, and portable paths");
    }

    private void testSystemAssetsRemainSeparated() throws Exception {
        Path app = freshSandbox("separation/application");
        Path data = freshSandbox("separation/data");
        copyTemplateFiles(app);
        Files.createDirectories(app.resolve("local"));
        Files.createDirectories(app.resolve("extensions"));
        Files.createDirectories(app.resolve("nlFilters"));
        Files.writeString(app.resolve("local/system.js"), "system-local");
        Files.writeString(
                app.resolve("extensions/sample.java"), "system-extension");
        Files.writeString(
                app.resolve("nlFilters/01_system.txt"), "system-filter");

        FirstRunSetupService service = FirstRunSetupService.production(
                app, data);
        service.apply(new SetupOptions(
                data, false, false, false, false));

        assertFalse(Files.exists(data.resolve("local/system.js")),
                "system local asset must not be copied to user data");
        assertFalse(Files.exists(data.resolve("extensions/sample.java")),
                "system extension sample must not be copied to user data");
        assertFalse(Files.exists(data.resolve("nlFilters/01_system.txt")),
                "system filter must not be copied to user data");
        assertTrue(Files.isDirectory(data.resolve("local")),
                "user local directory must be created");
        assertTrue(Files.isDirectory(data.resolve("extensions")),
                "user extensions directory must be created");
        assertTrue(Files.isDirectory(data.resolve("nlFilters")),
                "user filter directory must be created");
        System.out.println("PASS system assets remain separate from user data");
    }

    private void testSeparatedSetupFiles() throws Exception {
        Path app = freshSandbox("separated-setup/application");
        Path data = freshSandbox("separated-setup/data");
        copyTemplateFiles(app);
        FirstRunSetupService.SetupFiles files =
                new FirstRunSetupService.SetupFiles(app, data);
        FakeCertificateGenerator certificates =
                new FakeCertificateGenerator(data, false);
        FakeSystemIntegration integration = new FakeSystemIntegration(false);
        FirstRunSetupService service = new FirstRunSetupService(
                files, certificates, integration);

        service.apply(new SetupOptions(
                data, false, false, true, false));

        assertTrue(
                Files.isRegularFile(app.resolve("config.properties")),
                "config must be created in application root");
        assertTrue(
                Files.isRegularFile(data.resolve("proxy.pac")),
                "proxy PAC must be created in data root");
        assertTrue(
                Files.isRegularFile(
                        data.resolve("data/first-run-setup.properties")),
                "setup state must be created in data root");
        Properties config = loadProperties(
                app.resolve("config.properties"));
        assertEquals(data.toString(), config.getProperty("userDataRoot"),
                "config must persist user data root");
        System.out.println("PASS separated first-run setup files");
    }

    private void testHeadlessLaunchOptions() {
        LaunchOptions valid = LaunchOptions.parse(new String[] {
                "--setup",
                "--headless",
                "--user-data-root=" + sandbox.resolve("headless-data"),
                "--https=true",
                "--trust-certificate=false",
                "--proxy=false",
                "--autostart=true"
        });
        assertEquals(null, valid.getError(), "valid headless setup");
        assertTrue(valid.isSetup(), "setup mode");
        assertTrue(valid.isHeadless(), "headless mode");
        assertTrue(valid.getSetupOptions().isHttpsEnabled(),
                "headless HTTPS option");
        assertFalse(valid.getSetupOptions().isCertificateTrusted(),
                "headless certificate trust option");
        assertTrue(valid.getSetupOptions().isAutoStartEnabled(),
                "headless auto-start option");

        LaunchOptions missing = LaunchOptions.parse(new String[] {
                "--setup",
                "--headless",
                "--user-data-root=" + sandbox.resolve("headless-data"),
                "--https=false",
                "--trust-certificate=false",
                "--proxy=false"
        });
        assertContains(missing.getError(), "--autostart",
                "missing option must fail");

        LaunchOptions invalid = LaunchOptions.parse(new String[] {
                "--setup",
                "--headless",
                "--user-data-root=" + sandbox.resolve("headless-data"),
                "--https=yes",
                "--trust-certificate=false",
                "--proxy=false",
                "--autostart=false"
        });
        assertContains(invalid.getError(), "true または false",
                "invalid boolean must fail");

        LaunchOptions contradiction = LaunchOptions.parse(new String[] {
                "--setup",
                "--headless",
                "--user-data-root=" + sandbox.resolve("headless-data"),
                "--https=false",
                "--trust-certificate=true",
                "--proxy=false",
                "--autostart=false"
        });
        assertContains(contradiction.getError(), "--https=true",
                "certificate trust without HTTPS must fail");

        LaunchOptions guiSetup = LaunchOptions.parse(new String[] {
                "--setup"
        });
        assertContains(guiSetup.getError(), "--headless",
                "explicit setup must be headless");

        LaunchOptions ordinary = LaunchOptions.parse(new String[] {
                "--headless",
                "legacy-option"
        });
        assertEquals(null, ordinary.getError(), "ordinary headless launch");
        assertFalse(ordinary.isSetup(), "ordinary launch is not setup");
        assertEquals(1, ordinary.getForwardedArgs().length,
                "ordinary forwarded argument count");
        assertEquals("legacy-option", ordinary.getForwardedArgs()[0],
                "ordinary forwarded argument");
        System.out.println("PASS unified headless setup launch options");
    }

    private void testRequirementDetection() throws Exception {
        Path directory = freshSandbox("detection");
        copyTemplateFiles(directory);
        String oldForce = System.getProperty("dareka.setup.force");
        String oldDisable = System.getProperty("dareka.setup.disable");
        String oldVersion = System.getProperty("jpackage.app-version");
        try {
            System.clearProperty("dareka.setup.force");
            System.clearProperty("dareka.setup.disable");
            System.clearProperty("jpackage.app-version");
            assertFalse(FirstRunSetup.isRequired(directory),
                    "unpackaged launch must not show setup");

            System.setProperty("dareka.setup.force", "true");
            assertTrue(FirstRunSetup.isRequired(directory),
                    "forced setup must be required");
            System.clearProperty("dareka.setup.force");
            System.setProperty("jpackage.app-version", "0.1.1");
            assertTrue(FirstRunSetup.isRequired(directory),
                    "packaged first launch must require setup");

            Files.copy(
                    directory.resolve("config.properties.default"),
                    directory.resolve("config.properties"));
            assertFalse(FirstRunSetup.isRequired(directory),
                    "existing config must skip setup");
            Files.delete(directory.resolve("config.properties"));

            System.setProperty("dareka.setup.disable", "true");
            assertFalse(FirstRunSetup.isRequired(directory),
                    "disabled setup must be skipped");
        } finally {
            restoreProperty("dareka.setup.force", oldForce);
            restoreProperty("dareka.setup.disable", oldDisable);
            restoreProperty("jpackage.app-version", oldVersion);
        }
        System.out.println("PASS first-run requirement detection");
    }

    private void testSuccessfulFileSetup() throws Exception {
        Path directory = freshSandbox("success");
        copyTemplateFiles(directory);
        FakeCertificateGenerator certificates =
                new FakeCertificateGenerator(directory, false);
        FakeSystemIntegration integration = new FakeSystemIntegration(false);
        FirstRunSetupService service = new FirstRunSetupService(
                new FirstRunSetupService.SetupFiles(directory),
                certificates,
                integration);
        SetupOptions options = new SetupOptions(
                directory, true, true, true, true);
        service.apply(options);

        assertContains(
                Files.readString(directory.resolve("config.properties")),
                "enableMitM=true",
                "HTTPS option");
        assertTrue(Files.isRegularFile(directory.resolve("proxy.pac")),
                "proxy.pac must be created");
        assertContains(
                Files.readString(directory.resolve("proxy.pac")),
                "host.toLowerCase() === 'debug'",
                "proxy.pac debug virtual host route");
        assertContains(
                Files.readString(directory.resolve("NicoCacheGUI.property")),
                "HideWindow=true",
                "GUI property");
        assertTrue(Files.isRegularFile(
                directory.resolve("data/first-run-setup.properties")),
                "setup state must be created");
        assertEquals(1, certificates.generateCount,
                "certificate generator count");
        assertEquals(1, integration.applyCount,
                "integration apply count");
        assertTrue(integration.options.isAutoStartEnabled(),
                "auto-start option must be forwarded");
        assertTrue(integration.options.isCertificateTrusted(),
                "certificate trust option must be forwarded");
        System.out.println("PASS setup file creation and option forwarding");
    }

    private void testExistingFilesArePreserved() throws Exception {
        Path directory = freshSandbox("preserve");
        copyTemplateFiles(directory);
        Files.writeString(
                directory.resolve("proxy.pac"),
                "custom-proxy",
                StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("NicoCacheGUI.property"),
                "HideWindow=false",
                StandardCharsets.UTF_8);
        FirstRunSetupService service = new FirstRunSetupService(
                new FirstRunSetupService.SetupFiles(directory),
                new FakeCertificateGenerator(directory, false),
                new FakeSystemIntegration(false));
        service.apply(new SetupOptions(
                directory, false, false, true, false));

        assertEquals(
                "custom-proxy",
                Files.readString(directory.resolve("proxy.pac")),
                "existing proxy");
        assertEquals(
                "HideWindow=false",
                Files.readString(directory.resolve("NicoCacheGUI.property")),
                "existing GUI properties");
        assertContains(
                Files.readString(directory.resolve("config.properties")),
                "enableMitM=false",
                "disabled HTTPS option");
        System.out.println("PASS existing user files are preserved");
    }

    private void testSetupWithoutSystemIntegration() throws Exception {
        Path directory = freshSandbox("no-system-integration");
        copyTemplateFiles(directory);
        FakeCertificateGenerator certificates =
                new FakeCertificateGenerator(directory, false);
        FakeSystemIntegration integration = new FakeSystemIntegration(false);
        FirstRunSetupService service = new FirstRunSetupService(
                new FirstRunSetupService.SetupFiles(directory),
                certificates,
                integration);
        service.apply(new SetupOptions(
                directory, true, false, false, false));

        assertEquals(1, certificates.generateCount,
                "HTTPS certificate files must still be generated");
        assertEquals(0, integration.applyCount,
                "disabled OS options must skip system integration");
        assertTrue(Files.isRegularFile(
                directory.resolve("data/first-run-setup.properties")),
                "setup must complete without system integration");
        System.out.println("PASS setup without OS integration");
    }

    private void testRollbackAfterIntegrationFailure() throws Exception {
        Path directory = freshSandbox("rollback");
        copyTemplateFiles(directory);
        FakeCertificateGenerator certificates =
                new FakeCertificateGenerator(directory, false);
        FakeSystemIntegration integration = new FakeSystemIntegration(true);
        FirstRunSetupService service = new FirstRunSetupService(
                new FirstRunSetupService.SetupFiles(directory),
                certificates,
                integration);
        boolean failed = false;
        try {
            service.apply(new SetupOptions(
                    directory, true, true, true, true));
        } catch (IOException expected) {
            failed = true;
        }
        assertTrue(failed, "integration failure must propagate");
        assertFalse(Files.exists(directory.resolve("config.properties")),
                "config must be rolled back");
        assertFalse(Files.exists(directory.resolve("proxy.pac")),
                "proxy PAC must be rolled back");
        assertFalse(Files.exists(directory.resolve("NicoCacheGUI.property")),
                "GUI properties must be rolled back");
        assertFalse(Files.exists(
                directory.resolve("data/first-run-setup.properties")),
                "completion state must not exist");
        assertFalse(Files.exists(directory.resolve("certs/fake-ca.cer")),
                "generated certificate must be rolled back");
        assertEquals(1, integration.rollbackCount,
                "system rollback count");
        assertEquals(1, certificates.rollbackCount,
                "certificate rollback count");
        System.out.println("PASS rollback after integration failure");
    }

    private void testRollbackAfterCertificateFailure() throws Exception {
        Path directory = freshSandbox("certificate-failure");
        copyTemplateFiles(directory);
        FakeCertificateGenerator certificates =
                new FakeCertificateGenerator(directory, true);
        FakeSystemIntegration integration = new FakeSystemIntegration(false);
        FirstRunSetupService service = new FirstRunSetupService(
                new FirstRunSetupService.SetupFiles(directory),
                certificates,
                integration);
        boolean failed = false;
        try {
            service.apply(new SetupOptions(
                    directory, true, true, true, true));
        } catch (IOException expected) {
            failed = true;
        }
        assertTrue(failed, "certificate failure must propagate");
        assertFalse(Files.exists(directory.resolve("config.properties")),
                "config must roll back after certificate failure");
        assertFalse(Files.exists(directory.resolve("certs/fake-ca.cer")),
                "partial certificate must be removed");
        assertEquals(1, certificates.rollbackCount,
                "certificate rollback after generation failure");
        assertEquals(0, integration.applyCount,
                "system integration must not start after certificate failure");
        System.out.println("PASS rollback after certificate failure");
    }

    private void testLocalizedKeysMatch() throws Exception {
        Properties english = loadProperties(
                repository.resolve("src/dareka/setup_messages.properties"));
        Properties japanese = loadProperties(
                repository.resolve("src/dareka/setup_messages_ja.properties"));
        assertEquals(english.stringPropertyNames(),
                japanese.stringPropertyNames(),
                "localized message keys");
        SetupMessages fallback = new SetupMessages(Locale.FRENCH);
        assertEquals("Back", fallback.text("button.back"),
                "English fallback");
        SetupMessages ja = new SetupMessages(Locale.JAPANESE);
        assertEquals("戻る", ja.text("button.back"), "Japanese message");
        System.out.println("PASS localized message keys and fallback");
    }

    private void testCertificateTargets() throws Exception {
        List<String> targets = Files.readAllLines(
                repository.resolve("certificate-targets.txt"),
                StandardCharsets.US_ASCII);
        targets.removeIf(line -> line.isBlank()
                || line.trim().startsWith("#"));
        assertTrue(targets.size() >= 20,
                "certificate target list must cover current services");
        assertEquals(targets.size(), new HashSet<>(targets).size(),
                "certificate targets must be unique");
        for (String required : new String[] {
                "*.nicovideo.jp",
                "*.video.nimg.jp",
                "*.dmc.nico",
                "*.nvcomment.nicovideo.jp" }) {
            assertTrue(targets.contains(required),
                    "certificate target is missing: " + required);
        }
        String generator = Files.readString(
                repository.resolve("src/nicocacheca/NicoCacheCA.java"),
                StandardCharsets.UTF_8);
        assertContains(generator, "certificate-targets.txt",
                "certificate generator must use shared targets");
        assertContains(generator, "--targets-file",
                "certificate generator must accept a target file");
        assertFalse(generator.contains("*.nicovideo.jp"),
                "certificate generator must not hard-code target domains");
        System.out.println("PASS shared production certificate targets");
    }

    private void testWizardControlsAndRender() throws Exception {
        AtomicReference<SetupOptions> applied = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<FirstRunWizardPanel> panelReference =
                new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FirstRunWizardPanel panel = new FirstRunWizardPanel(
                    new SetupMessages(Locale.JAPANESE),
                    Locale.JAPANESE,
                    sandbox.resolve("wizard-user-data"),
                    new FirstRunWizardPanel.Listener() {
                        @Override
                        public void apply(SetupOptions options) {
                            applied.set(options);
                        }

                        @Override
                        public void cancel() {
                            cancelled.set(true);
                        }
                    });
            panelReference.set(panel);

            assertEquals(0, panel.getStep(), "initial step");
            assertFalse(panel.getBackButton().isEnabled(),
                    "Back must be disabled on welcome");
            assertTrue(panel.getNextButton().isVisible(),
                    "Next must be visible on welcome");
            assertFalse(panel.getApplyButton().isVisible(),
                    "Apply must be hidden on welcome");
            render(panel, 600, 430,
                    previewDirectory.resolve("wizard-step1-narrow.png"));

            panel.getNextButton().doClick();
            assertEquals(1, panel.getStep(), "data root step");
            render(panel, 720, 500,
                    previewDirectory.resolve("wizard-step2-standard.png"));
            panel.getDataRootField().setText("relative-data");
            panel.getNextButton().doClick();
            assertEquals(1, panel.getStep(),
                    "relative data root must block navigation");
            assertContains(panel.getDataRootError().getText(),
                    "絶対パス", "relative data root error");
            Path selectedDataRoot = sandbox.resolve("wizard-selected-data");
            panel.getDataRootField().setText(selectedDataRoot.toString());
            panel.getNextButton().doClick();
            assertEquals(2, panel.getStep(), "options step");
            render(panel, 720, 500,
                    previewDirectory.resolve("wizard-step3-standard.png"));
            assertTrue(panel.getHttpsCheckBox().isSelected(),
                    "HTTPS MitM must be selected initially");
            assertTrue(panel.getCertificateCheckBox().isSelected(),
                    "CA trust registration must be selected initially");
            assertTrue(panel.getProxyCheckBox().isSelected(),
                    "proxy.pac must be selected initially");
            assertTrue(panel.getAutoStartCheckBox().isSelected(),
                    "auto-start must be selected initially");
            panel.getHttpsCheckBox().doClick();
            assertFalse(panel.getCertificateCheckBox().isEnabled(),
                    "CA trust must be disabled without HTTPS MitM");
            assertFalse(panel.getCertificateCheckBox().isSelected(),
                    "CA trust must be cleared without HTTPS MitM");
            assertFalse(panel.getProxyCheckBox().isEnabled(),
                    "proxy must be disabled without HTTPS");
            assertFalse(panel.getProxyCheckBox().isSelected(),
                    "proxy must be cleared without HTTPS");
            panel.getHttpsCheckBox().doClick();
            assertTrue(panel.getCertificateCheckBox().isEnabled(),
                    "CA trust must be enabled with HTTPS MitM");
            assertTrue(panel.getProxyCheckBox().isEnabled(),
                    "proxy must be enabled with HTTPS");
            panel.getCertificateCheckBox().doClick();
            panel.getProxyCheckBox().doClick();
            panel.getAutoStartCheckBox().doClick();

            panel.getBackButton().doClick();
            assertEquals(1, panel.getStep(), "Back from options");
            panel.getNextButton().doClick();
            assertEquals(2, panel.getStep(), "return to options");
            assertTrue(panel.getHttpsCheckBox().isSelected(),
                    "HTTPS choice must survive navigation");
            assertTrue(panel.getProxyCheckBox().isSelected(),
                    "proxy choice must survive navigation");
            assertFalse(panel.getAutoStartCheckBox().isSelected(),
                    "auto-start choice must survive navigation");

            panel.getNextButton().doClick();
            assertEquals(3, panel.getStep(), "summary step");
            assertTrue(panel.getApplyButton().isVisible(),
                    "Apply must be visible on summary");
            assertContains(panel.getSummary().getText(),
                    selectedDataRoot.toString(),
                    "summary data root");
            assertContains(panel.getSummary().getText(),
                    "HTTPS MitMを有効にします",
                    "summary HTTPS MitM");
            assertContains(panel.getSummary().getText(),
                    "ローカルCAを生成し、OSの信頼ストアへ登録します",
                    "summary CA trust");
            assertContains(panel.getSummary().getText(),
                    "ログオン時起動へ追加しません",
                    "summary auto-start");
            render(panel, 960, 600,
                    previewDirectory.resolve("wizard-step4-standard.png"));
            assertComponentsWithinBounds(panel, panel);

            panel.setBusy(true);
            assertFalse(panel.getApplyButton().isEnabled(),
                    "Apply must be disabled while busy");
            assertFalse(panel.getCancelButton().isEnabled(),
                    "Cancel must be disabled while busy");
            panel.setBusy(false);
            assertTrue(panel.getApplyButton().isEnabled(),
                    "Apply must recover after failure");

            panel.getApplyButton().doClick();
            SetupOptions selectedOptions = applied.get();
            panel.showResult(selectedOptions, null);
            assertEquals(4, panel.getStep(), "success result step");
            assertFalse(panel.getBackButton().isVisible(),
                    "Back must be hidden after success");
            assertTrue(panel.getFinishButton().isVisible(),
                    "Finish must be visible on results");
            assertContains(panel.getResultBody().getText(),
                    "セットアップが完了しました",
                    "success result body");
            assertContains(panel.getResultSummary().getText(),
                    "HTTPS MitM: 成功",
                    "HTTPS MitM success result");
            assertContains(panel.getResultSummary().getText(),
                    "ローカルCAの信頼登録: 成功",
                    "CA trust success result");
            assertContains(panel.getResultSummary().getText(),
                    "自動プロキシー: 成功",
                    "proxy success result");
            assertContains(panel.getResultSummary().getText(),
                    "ログオン時自動起動: 未選択",
                    "auto-start skipped result");
            render(panel, 600, 430,
                    previewDirectory.resolve(
                            "wizard-step5-success-narrow.png"));
            assertComponentsWithinBounds(panel, panel);

            panel.showResult(
                    selectedOptions,
                    new IOException("テスト用の適用エラー"));
            assertTrue(panel.getBackButton().isVisible(),
                    "Back must be visible after failure");
            assertContains(panel.getResultBody().getText(),
                    "ロールバックしました",
                    "failure result body");
            assertContains(panel.getResultSummary().getText(),
                    "HTTPS MitM: 失敗（ロールバック済み）",
                    "HTTPS MitM failure result");
            assertContains(panel.getResultSummary().getText(),
                    "ログオン時自動起動: 未選択",
                    "unselected option must not be marked failed");
            assertContains(panel.getResultSummary().getText(),
                    "テスト用の適用エラー",
                    "failure detail");
            render(panel, 720, 500,
                    previewDirectory.resolve(
                            "wizard-step5-failure-standard.png"));
            assertComponentsWithinBounds(panel, panel);

            panel.getBackButton().doClick();
            assertEquals(3, panel.getStep(),
                    "Back from failure results");
            assertTrue(panel.getApplyButton().isVisible(),
                    "Apply must be available for retry");
            assertTrue(panel.getHttpsCheckBox().isEnabled(),
                    "HTTPS choice must recover for retry");
            assertTrue(panel.getProxyCheckBox().isEnabled(),
                    "proxy choice must recover for retry");
            assertTrue(panel.getAutoStartCheckBox().isEnabled(),
                    "auto-start choice must recover for retry");
            panel.showResult(selectedOptions, null);
            panel.getFinishButton().doClick();
        });

        SetupOptions options = applied.get();
        assertTrue(options != null, "Apply callback must run");
        assertEquals(
                sandbox.resolve("wizard-selected-data"),
                options.getUserDataRoot(),
                "applied user data root");
        assertTrue(options.isHttpsEnabled(), "applied HTTPS option");
        assertTrue(options.isCertificateTrusted(),
                "applied certificate trust option");
        assertTrue(options.isProxyConfigured(), "applied proxy option");
        assertFalse(options.isAutoStartEnabled(),
                "applied auto-start option");
        assertTrue(cancelled.get(), "Cancel callback must run");
        System.out.println("PASS wizard controls, state, busy mode, and render");
    }

    private Path freshSandbox(String name) throws IOException {
        Path directory = sandbox.resolve(name);
        Files.createDirectories(directory);
        return directory;
    }

    private void copyTemplateFiles(Path directory) throws IOException {
        Files.copy(
                repository.resolve("config.properties.default"),
                directory.resolve("config.properties.default"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
                repository.resolve("proxy_sample.pac"),
                directory.resolve("proxy_sample.pac"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(directory.resolve("certs"));
        Files.createDirectories(directory.resolve("data"));
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path);
                var reader = new java.io.InputStreamReader(
                        input, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @SuppressWarnings("auxiliaryclass")
    private static void render(FirstRunWizardPanel panel, int width,
            int height, Path output) {
        panel.setSize(width, height);
        layoutRecursively(panel);
        BufferedImage image = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            panel.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        try {
            ImageIO.write(image, "png", output.toFile());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutRecursively((Container) child);
            }
        }
    }

    private static void assertComponentsWithinBounds(
            Container container, Container root) {
        for (Component child : container.getComponents()) {
            if (child.isVisible() && child.getName() != null
                    && child instanceof JComponent) {
                Rectangle bounds = SwingUtilities.convertRectangle(
                        child.getParent(),
                        child.getBounds(),
                        root);
                assertTrue(bounds.x >= 0 && bounds.y >= 0,
                        "interactive component must not have negative position: "
                                + child.getName());
                assertTrue(bounds.x + bounds.width <= root.getWidth() + 1,
                        "interactive component must fit horizontally: "
                                + child.getName());
                assertTrue(bounds.y + bounds.height <= root.getHeight() + 1,
                        "interactive component must fit vertically: "
                                + child.getName());
            }
            if (child instanceof Container) {
                assertComponentsWithinBounds((Container) child, root);
            }
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static void assertTrue(boolean actual, String message) {
        if (!actual) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean actual, String message) {
        assertTrue(!actual, message);
    }

    private static void assertContains(String actual, String expected,
            String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + ": expected to contain="
                    + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual,
            String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static final class FakeCertificateGenerator
            implements FirstRunSetupService.CertificateGenerator {
        private final Path certificate;
        private final boolean fail;
        private int generateCount;
        private int rollbackCount;

        private FakeCertificateGenerator(Path directory, boolean fail) {
            this.certificate = directory.resolve("certs/fake-ca.cer");
            this.fail = fail;
        }

        @Override
        public void generate() throws Exception {
            generateCount++;
            Files.writeString(certificate, "fake-certificate");
            if (fail) {
                throw new IOException("simulated certificate failure");
            }
        }

        @Override
        public void rollback() throws Exception {
            rollbackCount++;
            Files.deleteIfExists(certificate);
        }
    }

    private static final class FakeSystemIntegration
            implements FirstRunSetupService.SystemIntegration {
        private final boolean fail;
        private int applyCount;
        private int rollbackCount;
        private SetupOptions options;

        private FakeSystemIntegration(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void apply(SetupOptions options) throws Exception {
            applyCount++;
            this.options = options;
            if (fail) {
                throw new IOException("simulated integration failure");
            }
        }

        @Override
        public void rollback() {
            rollbackCount++;
        }
    }
}
