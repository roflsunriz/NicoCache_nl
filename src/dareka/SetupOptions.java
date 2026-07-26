package dareka;

final class SetupOptions {
    private final boolean enableHttps;
    private final boolean configureProxy;
    private final boolean enableAutoStart;

    SetupOptions(boolean enableHttps, boolean configureProxy,
            boolean enableAutoStart) {
        this.enableHttps = enableHttps;
        this.configureProxy = configureProxy;
        this.enableAutoStart = enableAutoStart;
    }

    boolean isHttpsEnabled() {
        return enableHttps;
    }

    boolean isProxyConfigured() {
        return configureProxy;
    }

    boolean isAutoStartEnabled() {
        return enableAutoStart;
    }
}
