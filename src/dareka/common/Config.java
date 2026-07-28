package dareka.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Provides general configuration management. This class supports:
 * <ul>
 * <li>load and store config file.
 * <li>load defaults/*.properties.
 * <li>store properties in the system properties with validation.
 * <li>reload properties in runtime.
 * </ul>
 *
 * This class does not deal with any application specific properties.
 * Subclass can manage its own properties by overriding
 * template methods named doXXX(). Each application should set its own subclass
 * via {@link Config#setConfig(Config)}.
 */
// GUIへ依存せず、ConfigObserverとreloadを通じて実行時設定を提供する。
public abstract class Config {
    private static volatile Config config;
    private static final CopyOnWriteArrayList<ConfigObserver> observers =
        new CopyOnWriteArrayList<ConfigObserver>(); // [nl]

    private Properties properties = new Properties(); // null object pattern
    private Properties defaultProperties = new Properties(); // [nl]
    private File configFile;
    private long lastModified;

    public static Config getConfig() {
        return config;
    }

    public static void setConfig(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        Config.config = config;
    }

    @SuppressWarnings("this-escape")
    public Config(File configFile) {
        this.configFile = configFile;

        // doSetDefaults は公開済みの拡張用テンプレートメソッドであり、
        // 既存サブクラスとの初期化契約を維持するためここで呼び出す。
        doSetDefaults(defaultProperties); // [nl]
        update();
    }

    /**
     * [nl] オブザーバーを追加する。Configが存在する場合は直後に
     * {@link ConfigObserver#update(Config) update} メソッドを呼び出すので、
     * 初期化処理はその中で行っても良い。
     *
     * @param o 追加するオブザーバー
     * @since NicoCache_nl+110219mod
     */
    public static void addObserver(ConfigObserver o) {
        observers.add(o);

        if (config != null) {
            o.update(config);
        }
    }

    /**
     * [nl] オブザーバーを削除する。
     * @param o 削除するオブザーバー
     * @since NicoCache_nl+110219mod
     */
    public static void removeObserver(ConfigObserver o) {
        observers.remove(o);
    }

    public File getConfigFile() {
        return configFile;
    }

    /**
     * [nl] 設定ファイルが修正されていれば読み込む。
     * @return 設定ファイルを読み込んだらtrue
     */
    public synchronized boolean reload() {
        if (configFile.lastModified() <= lastModified) {
            return false;
        }
        Logger.info("Reloading '" + configFile.getName() + "'");

        update();
        return true;
    }

    /**
     * Template method to specify comments of config file.
     *
     * @return comments. null means no comment.
     */
    protected String doGetConfigFileComments() {
        return null;
    }

    /**
     * Template method to set mandatory properties in case of
     * the default property files do not exist.
     *
     * @param properties place to put mandatory properties.
     */
    protected abstract void doSetDefaults(Properties properties);

    /**
     * Template method to validate property to be set.
     * No validation in default implementation.
     *
     * @param key
     * @param value
     * @return validate value. if returns null, the property is ignored.
     */
    protected String doValidateValue(String key, String value) {
        return value;
    }

    protected synchronized void update() { // [nl]
        Properties newProperties = new Properties(defaultProperties);

        //doSetDefaults(newProperties);
        // デフォルト値を別に持っておかないと、validate結果がnullだった場合に
        // システムプロパティにデフォルト値が設定されなくなる

        try {
            setDefaultsFromFiles(newProperties);
        } catch (IOException ioe) {
            Logger.debugWithThread(ioe);
        }

        try {
            if (configFile.exists()) {
                loadFrom(configFile, newProperties);
            } else {
                storeConfigFile(newProperties);
            }
        } catch (IOException ioe) {
            Logger.debugWithThread(ioe);
        }

        updateSystemProperties(newProperties);
        properties = newProperties;
        lastModified = configFile.lastModified();

        for (ConfigObserver o : observers) { // [nl]
            o.update(config);
        }
    }

    private void storeConfigFile(Properties properties) throws IOException {
        FileOutputStream out = new FileOutputStream(configFile);
        try { // ensure closing out
            properties.store(out, doGetConfigFileComments());
        } finally {
            CloseUtil.close(out);
        }
    }

    private void updateSystemProperties(Properties newProperties) {
        // It is unlikely to cause problem, but this update is not atomic.

        for (Object objKey : properties.keySet()) {
            String key = (String) objKey;
            if (!newProperties.containsKey(key)) { // [nl]
                System.clearProperty(key);
            }
        }

        for (Map.Entry<Object, Object> entry : newProperties.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            setProperty(key, value.trim());
        }
    }

    public void setProperty(String key, String value) {
        String validatedValue = doValidateValue(key, value);
        if (validatedValue == null)
            validatedValue = defaultProperties.getProperty(key); // [nl]
        if (validatedValue == null) {
            return;
        }

        System.setProperty(key, validatedValue);
        Logger.debugWithThread(key + "=" + validatedValue);
    }

    private void setDefaultsFromFiles(Properties p) throws IOException {
        String applicationRoot =
                System.getProperty("nicocache.applicationRoot");
        File defaultsDir = applicationRoot == null
                || applicationRoot.isBlank()
                ? new File("defaults")
                : new File(applicationRoot, "defaults");

        File[] files = defaultsDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                if (name.endsWith(".properties")) {
                    return true;
                } else {
                    return false;
                }
            }
        });

        if (files == null) {
            throw new IOException("failed to read: " + defaultsDir);
        }

        Arrays.sort(files);
        for (File f : files) {
            loadFrom(f, p);
        }
    }

    protected void loadFrom(File propertyFile, Properties p) // [nl]
            throws FileNotFoundException, IOException {
        FileInputStream in = new FileInputStream(propertyFile);
        try { // ensure closing in
            p.load(in);
        } finally {
            CloseUtil.close(in);
        }
    }

    // [nl] staticな型別デフォルト値付きgetter
    /**
     * Should use {@link Boolean#getBoolean(String)}
     */
    @Deprecated
    public static boolean getBoolean(String key, boolean def) {
        String value = System.getProperty(key);
        if (value == null) {
            return def;
        } else {
            return Boolean.parseBoolean(value);
        }
    }

    /**
     * Should use {@link System#getProperty(String)}
     */
    @Deprecated
    public static String getString(String key, String def) {
        return System.getProperty(key, def);
    }

    /**
     * Should use {@link Integer#getInteger(String)}
     */
    @Deprecated
    public static int getInteger(String key, int def) {
        String value = System.getProperty(key);
        if (value == null) {
            return def;
        } else {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return def;
            }
        }
    }

    /**
     * [nl] Extensionでは使われていないと思うけど…
     */
    @Deprecated
    public static boolean getFlag(String key, String feature, boolean def) {
        String value = System.getProperty(key);
        if (value == null) {
            return def;
        } else {
            Pattern pt = Pattern.compile("(?:^|,)\\s*" + feature + "\\s*(?:,|$)");
            return pt.matcher(value).find();
        }
    }
}
