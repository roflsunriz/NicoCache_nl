package dareka;

final class SetupOptions {
    private final boolean enableHttps;
    private final boolean trustCertificate;
    private final boolean configureProxy;
    private final boolean enableAutoStart;

    SetupOptions(boolean enableHttps, boolean trustCertificate,
            boolean configureProxy, boolean enableAutoStart) {
        this.enableHttps = enableHttps;
        this.trustCertificate = trustCertificate;
        this.configureProxy = configureProxy;
        this.enableAutoStart = enableAutoStart;
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
