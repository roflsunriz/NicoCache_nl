package dareka.processor.impl;

import java.net.URLConnection;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import dareka.processor.HttpRequestHeader;
import dareka.processor.URLResource;

public class SecureCookieStripper implements BiConsumer<HttpRequestHeader, URLConnection> {
    private final String SECURE_COOKIE_KEY = "user_session_secure";
    private final Pattern COOKIE_SPLITTER = Pattern.compile(";\\s*+");
    private final Pattern KEY_VALUE_SPLITTER = Pattern.compile("=");

    public static void register() {
        URLResource.registerRequestHook(new SecureCookieStripper());
    }

    @Override
    public void accept(HttpRequestHeader header, URLConnection con) {
        if (!"http".equals(header.getScheme())) return;
        String host = header.getHost();
        if (!host.endsWith(".nicovideo.jp") &&
                // これらはそもそもcookieがからのはずだけど
                !host.endsWith(".smilevideo.jp") &&
                !host.endsWith(".nimg.jp") &&
                !host.endsWith(".dmc.nico")) return;

        String cookie = con.getRequestProperty("Cookie");
        if (cookie == null) return;
        if (!cookie.contains(SECURE_COOKIE_KEY)) return;

        StringBuilder newcookie = new StringBuilder();
        String[] entries = COOKIE_SPLITTER.split(cookie);
        for (String entry : entries) {
            String[] parts = KEY_VALUE_SPLITTER.split(entry, 2);
            String key = parts[0];
            String value = parts[1];
            if (!key.equals(SECURE_COOKIE_KEY)) {
                if (newcookie.length() != 0)
                    newcookie.append("; ");
                newcookie.append(key).append('=').append(value);
            }
        }
        con.setRequestProperty("Cookie", newcookie.toString());
    }
}
