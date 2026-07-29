package dareka;

import java.nio.file.Path;

final class SetupOptions {
    private final Path userDataRoot;
    private final boolean enableHttps;
    private final boolean trustCertificate;
    private final boolean configureProxy;
    private final boolean enableAutoStart;

    SetupOptions(Path userDataRoot, boolean enableHttps,
            boolean trustCertificate,
            boolean configureProxy, boolean enableAutoStart) {
        if (userDataRoot == null) {
            throw new IllegalArgumentException("userDataRoot must not be null");
        }
        this.userDataRoot = userDataRoot.toAbsolutePath().normalize();
        this.enableHttps = enableHttps;
        this.trustCertificate = trustCertificate;
        this.configureProxy = configureProxy;
        this.enableAutoStart = enableAutoStart;
    }

    Path getUserDataRoot() {
        return userDataRoot;
    }

    boolean isHttpsEnabled() {
        return enableHttps;
    }

    boolean isCertificateTrusted() {
        return trustCertificate;
    }

    boolean isProxyConfigured() {
        return configureProxy;
    }

    boolean isAutoStartEnabled() {
        return enableAutoStart;
    }

    boolean needsSystemIntegration() {
        return trustCertificate || configureProxy || enableAutoStart;
    }
}
