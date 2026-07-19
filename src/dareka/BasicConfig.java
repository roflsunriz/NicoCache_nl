package dareka;

import java.io.File;
import java.util.Properties;

import dareka.common.Config;

/**
 * Implementation for basic NicoCache configuration.
 *
 */
public class BasicConfig extends Config {
    public BasicConfig(File configFile) {
        super(configFile);
    }

    @Override
    protected String doGetConfigFileComments() {
        return "NicoCache config file";
    }

    @Override
    protected void doSetDefaults(Properties properties) {
        properties.setProperty("listenPort", "8080");
        properties.setProperty("proxyHost", "");
        properties.setProperty("proxyPort", "8081");
        properties.setProperty("title", "true");
        properties.setProperty("touchCache", "true");
        properties.setProperty("readTimeout", "600000");
        properties.setProperty("fileNameCharset", "");
    }

    @Override
    protected String doValidateValue(String key, String value) {
        if ("readTimeout".equals(key)) {
            if (Integer.valueOf(value).intValue() < 0) {
                return "0";
            }
        }

        return value;
    }
}
