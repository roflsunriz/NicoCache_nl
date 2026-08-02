package nicocache.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Read-only checks for the NicoCache_nl user-data-root contract. */
final class DataRootInspector {
    private static final String SITE_KEYSTORE_PASSWORD = "NicoCache";
    private static final List<String> SETUP_DIRECTORIES = List.of(
            "cache", "certs", "cvcache", "data", "extensions", "list",
            "local", "nlFilters", "thcache");

    private DataRootInspector() {
    }

    static DataRootInspection inspect(Path applicationRoot, Path dataRoot) {
        Path application = normalize(applicationRoot);
        Path data = normalize(dataRoot);
        List<DataRootInspection.Item> items = new ArrayList<>();

        addRootChecks(items, data);
        addApplicationConfigChecks(items, application);
        Properties effectiveConfig = loadEffectiveConfig(items, application);
        addConfiguredRootCheck(items, application, data, effectiveConfig);
        addSetupStateCheck(items, data);
        addSetupDirectories(items, data);
        addTlsClientStoreCheck(items, application, data);
        addMitmChecks(items, application, data, effectiveConfig);
        addProxyCheck(items, data);
        addGuiPropertiesCheck(items, data);
        addLegacyLayoutCheck(items, application, data);

        return DataRootInspection.create(application, data, items);
    }

    private static void addRootChecks(List<DataRootInspection.Item> items,
            Path data) {
        if (data == null) {
            add(items, "data-root", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.BLOCKED, null, null,
                    "invalid-path");
            add(items, "data-root-access", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ERROR, null, null,
                    "inspection-error");
            return;
        }
        try {
            if (!Files.exists(data)) {
                if (isCreatableFromParent(data)) {
                    add(items, "data-root", DataRootInspection.Severity.REQUIRED,
                            DataRootInspection.ItemState.MISSING, data, null,
                            "missing.create");
                    add(items, "data-root-access",
                            DataRootInspection.Severity.REQUIRED,
                            DataRootInspection.ItemState.MISSING, data, null,
                            "missing.create");
                } else {
                    add(items, "data-root",
                            DataRootInspection.Severity.REQUIRED,
                            DataRootInspection.ItemState.BLOCKED, data, null,
                            "missing");
                    add(items, "data-root-access",
                            DataRootInspection.Severity.REQUIRED,
                            DataRootInspection.ItemState.BLOCKED, data, null,
                            "permission");
                }
                return;
            }
            if (!Files.isDirectory(data)) {
                add(items, "data-root", DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED, data, null,
                        "invalid-type");
                add(items, "data-root-access",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED, data, null,
                        "invalid-type");
                return;
            }
            add(items, "data-root", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.OK, data, null, "present");
            if (Files.isReadable(data) && Files.isWritable(data)) {
                add(items, "data-root-access",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.OK, data, null,
                        "present");
            } else {
                add(items, "data-root-access",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED, data, null,
                        "permission");
            }
        } catch (SecurityException error) {
            add(items, "data-root", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ERROR, data, null,
                    "inspection-error");
            add(items, "data-root-access",
                    DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ERROR, data, null,
                    "inspection-error");
        }
    }

    private static void addApplicationConfigChecks(
            List<DataRootInspection.Item> items, Path application) {
        Path config = resolve(application, "config.properties");
        if (config == null) {
            add(items, "application-config", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.BLOCKED, null, null,
                    "invalid-path");
            return;
        }
        try {
            if (Files.isRegularFile(config)) {
                add(items, "application-config",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.OK, config, null,
                        "present");
            } else if (Files.exists(config)) {
                add(items, "application-config",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED, config, null,
                        "invalid-type");
            } else {
                add(items, "application-config",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED, config, null,
                        "missing.required");
            }
        } catch (SecurityException error) {
            add(items, "application-config",
                    DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ERROR, config, null,
                    "inspection-error");
        }
    }

    private static Properties loadEffectiveConfig(
            List<DataRootInspection.Item> items, Path application) {
        Properties properties = new Properties();
        if (application == null) {
            return properties;
        }
        Path defaults = resolve(application, "defaults");
        try {
            if (defaults != null && Files.isDirectory(defaults)) {
                List<Path> defaultFiles;
                try (Stream<Path> stream = Files.list(defaults)) {
                    defaultFiles = stream
                            .filter(path -> path.getFileName().toString()
                                    .endsWith(".properties"))
                            .sorted(Comparator.comparing(
                                    path -> path.getFileName().toString()))
                            .collect(Collectors.toList());
                }
                for (Path file : defaultFiles) {
                    loadProperties(file, properties);
                }
            }
            Path config = resolve(application, "config.properties");
            if (config != null && Files.isRegularFile(config)) {
                loadProperties(config, properties);
            }
        } catch (IOException | SecurityException error) {
            add(items, "configuration-values",
                    DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ERROR,
                    resolve(application, "config.properties"), null,
                    "inspection-error");
        }
        return properties;
    }

    private static void addConfiguredRootCheck(
            List<DataRootInspection.Item> items, Path application, Path data,
            Properties config) {
        String configured = configuredProperty(config, "userDataRoot");
        if (configured == null) {
            add(items, "configured-data-root",
                    DataRootInspection.Severity.INFORMATIONAL,
                    DataRootInspection.ItemState.NOT_APPLICABLE, null, null,
                    "not-applicable");
            return;
        }
        Path configuredPath = parseConfiguredRoot(configured, application);
        if (configuredPath == null) {
            add(items, "configured-data-root",
                    DataRootInspection.Severity.INFORMATIONAL,
                    DataRootInspection.ItemState.ATTENTION, null, null,
                    "invalid-path");
        } else if (!samePath(configuredPath, data)) {
            add(items, "configured-data-root",
                    DataRootInspection.Severity.INFORMATIONAL,
                    DataRootInspection.ItemState.ATTENTION, configuredPath,
                    data, "configured-root-mismatch");
        } else {
            add(items, "configured-data-root",
                    DataRootInspection.Severity.INFORMATIONAL,
                    DataRootInspection.ItemState.OK, configuredPath, null,
                    "present");
        }
    }

    private static Properties addSetupStateCheck(
            List<DataRootInspection.Item> items, Path data) {
        Path statePath = resolve(data, "data/first-run-setup.properties");
        if (statePath == null) {
            add(items, "setup-record", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.BLOCKED, null, null,
                    "invalid-path");
            return null;
        }
        try {
            if (!Files.isRegularFile(statePath)) {
                add(items, "setup-record", DataRootInspection.Severity.REQUIRED,
                        Files.exists(statePath)
                                ? DataRootInspection.ItemState.BLOCKED
                                : DataRootInspection.ItemState.ATTENTION,
                        statePath, null,
                        Files.exists(statePath) ? "invalid-type"
                                : "setup-record");
                return null;
            }
            Properties state = new Properties();
            loadProperties(statePath, state);
            if (!"complete".equalsIgnoreCase(
                    state.getProperty("status", "").trim())) {
                add(items, "setup-record", DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.ATTENTION, statePath, null,
                        "setup-record.invalid");
                return state;
            }
            String recordedRoot = state.getProperty("userDataRoot", "").trim();
            Path recordedPath = parseRecordedRoot(recordedRoot, data);
            if (recordedPath == null || !samePath(recordedPath, data)) {
                add(items, "setup-record", DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.ATTENTION, statePath, null,
                        "setup-record.mismatch");
            } else {
                add(items, "setup-record", DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.OK, statePath, null,
                        "present");
            }
            return state;
        } catch (IOException | RuntimeException error) {
            add(items, "setup-record", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ERROR, statePath, null,
                    "inspection-error");
            return null;
        }
    }

    private static void addSetupDirectories(
            List<DataRootInspection.Item> items, Path data) {
        for (String directory : SETUP_DIRECTORIES) {
            Path path = resolve(data, directory);
            String missingReason = "list".equals(directory)
                    ? "missing.create.list" : "missing.create";
            addDirectoryCheck(items, directory.replace('/', '-'), path,
                    missingReason);
        }
    }

    private static void addDirectoryCheck(
            List<DataRootInspection.Item> items, String id, Path path,
            String missingReason) {
        if (path == null) {
            add(items, "directory-" + id,
                    DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.BLOCKED, null, null,
                    "invalid-path");
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                add(items, "directory-" + id,
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.OK, path, null,
                        "present");
            } else if (Files.exists(path)) {
                add(items, "directory-" + id,
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED, path, null,
                        "invalid-type");
            } else {
                add(items, "directory-" + id,
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.MISSING, path, null,
                        missingReason);
            }
        } catch (SecurityException error) {
            add(items, "directory-" + id,
                    DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ERROR, path, null,
                    "inspection-error");
        }
    }

    private static void addTlsClientStoreCheck(
            List<DataRootInspection.Item> items, Path application, Path data) {
        Path userStore = resolve(data, "data/tlsclient/cacerts2");
        Path applicationStore = resolve(application, "data/tlsclient/cacerts2");
        try {
            if (userStore != null && Files.isRegularFile(userStore)) {
                boolean valid = isValidKeyStore(userStore);
                add(items, "tls-client-store",
                        DataRootInspection.Severity.REQUIRED,
                        valid ? DataRootInspection.ItemState.OK
                                : DataRootInspection.ItemState.BLOCKED,
                        userStore, null, valid ? "present" : "keystore");
            } else if (userStore != null && Files.exists(userStore)) {
                add(items, "tls-client-store",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED, userStore, null,
                        "invalid-type");
            } else if (applicationStore != null
                    && Files.isRegularFile(applicationStore)) {
                boolean valid = isValidKeyStore(applicationStore);
                add(items, "tls-client-store",
                        DataRootInspection.Severity.REQUIRED,
                        valid ? DataRootInspection.ItemState.FALLBACK
                                : DataRootInspection.ItemState.BLOCKED,
                        userStore, applicationStore,
                        valid ? "fallback" : "keystore");
            } else {
                add(items, "tls-client-store",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED, userStore, null,
                        "missing.required");
            }
        } catch (SecurityException error) {
            add(items, "tls-client-store",
                    DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ERROR, userStore, null,
                    "inspection-error");
        }
    }

    private static void addMitmChecks(List<DataRootInspection.Item> items,
            Path application, Path data, Properties config) {
        boolean enabled = Boolean.parseBoolean(configuredProperty(config,
                "enableMitm", "enableMitM", "httpsMitm"));
        if (!enabled) {
            add(items, "mitm-certificates",
                    DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ATTENTION,
                    resolve(application, "config.properties"), null,
                    "disabled.required");
            return;
        }

        Path certificateDirectory = resolve(data, "certs");
        Path siteKeyStore = resolve(certificateDirectory, "site.jks");
        Path siteTargets = resolve(certificateDirectory, "site.targets");
        Path caCertificate = resolve(certificateDirectory, "ca.cer");
        Path targetsFile = resolve(application, "certificate-targets.txt");

        addMitmTargetPatternCheck(items, application, config);
        addKeyStoreCheck(items, siteKeyStore);
        addSiteTargetsCheck(items, siteTargets, config);
        addNonEmptyFileCheck(items, "certificate-targets",
                DataRootInspection.Severity.RECOMMENDED, targetsFile,
                "targets-source", "certificate-targets");
        addRegularFileCheck(items, "ca-certificate",
                DataRootInspection.Severity.RECOMMENDED, caCertificate,
                "certificate-trust");
    }

    private static void addMitmTargetPatternCheck(
            List<DataRootInspection.Item> items, Path application,
            Properties config) {
        String patterns = configuredProperty(config, "mitmHostPort");
        Path configPath = resolve(application, "config.properties");
        if (patterns == null || patterns.isBlank()) {
            add(items, "mitm-target-patterns",
                    DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.BLOCKED, configPath, null,
                    "missing.required");
        } else {
            add(items, "mitm-target-patterns",
                    DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.OK, configPath, null,
                    "present");
        }
    }

    private static void addSiteTargetsCheck(
            List<DataRootInspection.Item> items, Path siteTargets,
            Properties config) {
        if (siteTargets == null) {
            add(items, "site-targets", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.BLOCKED, null, null,
                    "invalid-path");
            return;
        }
        try {
            if (!Files.isRegularFile(siteTargets)) {
                add(items, "site-targets",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED, siteTargets, null,
                        Files.exists(siteTargets) ? "invalid-type"
                                : "missing.required");
                return;
            }
            List<String> targetLines = Files.readAllLines(siteTargets,
                    StandardCharsets.UTF_8).stream().map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .collect(Collectors.toList());
            if (targetLines.isEmpty()) {
                add(items, "site-targets",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED, siteTargets, null,
                        "empty");
                return;
            }
            String patterns = configuredProperty(config, "mitmHostPort");
            if (patterns != null && !patterns.isBlank()
                    && !targetLines.containsAll(requiredCertificateTargets(
                            patterns))) {
                add(items, "site-targets",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED, siteTargets, null,
                        "targets.mismatch");
                return;
            }
            add(items, "site-targets", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.OK, siteTargets, null,
                    "targets.present");
        } catch (IOException | RuntimeException error) {
            add(items, "site-targets", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ERROR, siteTargets, null,
                    "inspection-error");
        }
    }

    private static List<String> requiredCertificateTargets(String patterns) {
        List<String> targets = new ArrayList<>();
        for (String pattern : patterns.trim().split("\\s+")) {
            if (pattern.isEmpty()) {
                continue;
            }
            int separator = pattern.indexOf(':');
            targets.add(separator < 0 ? pattern : pattern.substring(0,
                    separator));
        }
        return targets;
    }

    private static void addKeyStoreCheck(List<DataRootInspection.Item> items,
            Path path) {
        if (path == null) {
            add(items, "site-keystore", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.BLOCKED, null, null,
                    "invalid-path");
            return;
        }
        try {
            if (!Files.isRegularFile(path)) {
                add(items, "site-keystore",
                        DataRootInspection.Severity.REQUIRED,
                        DataRootInspection.ItemState.BLOCKED,
                        path, null,
                        Files.exists(path) ? "invalid-type" : "missing.required");
                return;
            }
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (InputStream input = Files.newInputStream(path,
                    StandardOpenOption.READ)) {
                keyStore.load(input, SITE_KEYSTORE_PASSWORD.toCharArray());
            }
            add(items, "site-keystore", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.OK, path, null, "present");
        } catch (Exception error) {
            add(items, "site-keystore", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.BLOCKED, path, null,
                    "keystore");
        }
    }

    private static void addRegularFileCheck(
            List<DataRootInspection.Item> items, String id,
            DataRootInspection.Severity severity, Path path, String missingReason) {
        if (path == null) {
            add(items, id, severity, DataRootInspection.ItemState.ATTENTION,
                    null, null, "invalid-path");
            return;
        }
        try {
            if (Files.isRegularFile(path)) {
                add(items, id, severity, DataRootInspection.ItemState.OK,
                        path, null, "present");
            } else if (Files.exists(path)) {
                add(items, id, severity, DataRootInspection.ItemState.ATTENTION,
                        path, null, "invalid-type");
            } else {
                add(items, id, severity, DataRootInspection.ItemState.ATTENTION,
                        path, null, missingReason);
            }
        } catch (SecurityException error) {
            add(items, id, severity, DataRootInspection.ItemState.ERROR, path,
                    null, "inspection-error");
        }
    }

    private static void addNonEmptyFileCheck(
            List<DataRootInspection.Item> items, String id,
            DataRootInspection.Severity severity, Path path,
            String presentReason, String missingReason) {
        if (path == null) {
            add(items, id, severity, DataRootInspection.ItemState.BLOCKED,
                    null, null, "invalid-path");
            return;
        }
        try {
            if (!Files.isRegularFile(path)) {
                boolean required = severity == DataRootInspection.Severity.REQUIRED;
                add(items, id, severity,
                        required ? DataRootInspection.ItemState.BLOCKED
                                : DataRootInspection.ItemState.ATTENTION,
                        path, null,
                        Files.exists(path)
                                ? "invalid-type" : missingReason);
                return;
            }
            boolean nonEmpty = Files.readAllLines(path, StandardCharsets.UTF_8)
                    .stream().map(String::trim).anyMatch(value -> !value.isEmpty());
            add(items, id, severity,
                    nonEmpty ? DataRootInspection.ItemState.OK
                            : severity == DataRootInspection.Severity.REQUIRED
                            ? DataRootInspection.ItemState.BLOCKED
                            : DataRootInspection.ItemState.ATTENTION,
                    path, null, nonEmpty ? presentReason : "empty");
        } catch (IOException | SecurityException error) {
            add(items, id, severity, DataRootInspection.ItemState.ERROR, path,
                    null, "inspection-error");
        }
    }

    private static void addProxyCheck(List<DataRootInspection.Item> items,
            Path data) {
        Path proxy = resolve(data, "proxy.pac");
        addRegularFileCheck(items, "proxy-pac",
                DataRootInspection.Severity.REQUIRED, proxy, "proxy");
    }

    private static void addGuiPropertiesCheck(
            List<DataRootInspection.Item> items, Path data) {
        Path guiProperties = resolve(data, "NicoCacheGUI.property");
        if (guiProperties != null && existsRegularFile(guiProperties)) {
            add(items, "gui-properties",
                    DataRootInspection.Severity.INFORMATIONAL,
                    DataRootInspection.ItemState.OK, guiProperties, null,
                    "present");
        } else {
            add(items, "gui-properties",
                    DataRootInspection.Severity.INFORMATIONAL,
                    DataRootInspection.ItemState.MISSING, guiProperties, null,
                    "optional");
        }
    }

    private static void addLegacyLayoutCheck(
            List<DataRootInspection.Item> items, Path application, Path data) {
        if (application == null || data == null || samePath(application, data)) {
            add(items, "legacy-layout",
                    DataRootInspection.Severity.INFORMATIONAL,
                    DataRootInspection.ItemState.NOT_APPLICABLE, data, null,
                    "not-applicable");
            return;
        }
        Path legacyConfig = resolve(data, "config.ini");
        Path misplacedConfig = resolve(data, "config.properties");
        Path legacyJar = resolve(data, "NicoCache_nl.jar");
        if (existsRegularFile(legacyConfig)) {
            add(items, "legacy-layout", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ATTENTION, legacyConfig, null,
                    "legacy");
        } else if (existsRegularFile(misplacedConfig)) {
            add(items, "legacy-layout", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ATTENTION, misplacedConfig, null,
                    "legacy");
        } else if (existsRegularFile(legacyJar)) {
            add(items, "legacy-layout", DataRootInspection.Severity.REQUIRED,
                    DataRootInspection.ItemState.ATTENTION, legacyJar, null,
                    "legacy");
        } else {
            add(items, "legacy-layout",
                    DataRootInspection.Severity.INFORMATIONAL,
                    DataRootInspection.ItemState.NOT_APPLICABLE, data, null,
                    "not-applicable");
        }
    }

    private static void add(List<DataRootInspection.Item> items, String id,
            DataRootInspection.Severity severity,
            DataRootInspection.ItemState state, Path path, Path fallbackPath,
            String reasonKey) {
        items.add(new DataRootInspection.Item(id, severity, state, path,
                fallbackPath, reasonKey));
    }

    private static boolean isCreatableFromParent(Path path) {
        Path parent = path.getParent();
        return parent != null && Files.isDirectory(parent)
                && Files.isWritable(parent);
    }

    private static boolean existsRegularFile(Path path) {
        try {
            return path != null && Files.isRegularFile(path);
        } catch (SecurityException error) {
            return false;
        }
    }

    private static void loadProperties(Path path, Properties target)
            throws IOException {
        try (InputStream input = Files.newInputStream(path,
                StandardOpenOption.READ)) {
            target.load(input);
        }
    }

    private static String configuredProperty(Properties properties,
            String... keys) {
        for (String key : keys) {
            String value = properties.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isValidKeyStore(Path path) {
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (InputStream input = Files.newInputStream(path,
                    StandardOpenOption.READ)) {
                keyStore.load(input, SITE_KEYSTORE_PASSWORD.toCharArray());
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static Path parseConfiguredRoot(String value, Path application) {
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? path.toAbsolutePath().normalize()
                    : resolve(application, value);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Path parseRecordedRoot(String value, Path data) {
        if (value == null || value.isBlank() || data == null) {
            return null;
        }
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? path.toAbsolutePath().normalize()
                    : data.resolve(path).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Path normalize(Path path) {
        try {
            return path == null ? null : path.toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Path resolve(Path root, String relative) {
        if (root == null) {
            return null;
        }
        try {
            return root.resolve(relative).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static boolean samePath(Path left, Path right) {
        Path normalizedLeft = normalize(left);
        Path normalizedRight = normalize(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }
}
