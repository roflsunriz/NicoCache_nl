package nicocache.cmaftomp4;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/** ユーザー向けメッセージをロケール辞書から取得する。 */
final class Messages {
    private static final ResourceBundle.Control NO_DEFAULT_LOCALE_FALLBACK =
            new ResourceBundle.Control() {
                @Override
                public Locale getFallbackLocale(String baseName, Locale requestedLocale) {
                    return null;
                }
            };
    private static volatile Locale locale = defaultLocale();
    private static volatile ResourceBundle bundle = loadBundle(locale);

    private Messages() {
    }

    static void setLocale(Locale requested) {
        if (requested == null) {
            return;
        }
        locale = requested;
        bundle = loadBundle(requested);
    }

    static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (RuntimeException e) {
            return key;
        }
    }

    static String format(String key, Object... arguments) {
        return MessageFormat.format(get(key), arguments);
    }

    private static ResourceBundle loadBundle(Locale requested) {
        return ResourceBundle.getBundle("messages", requested, NO_DEFAULT_LOCALE_FALLBACK);
    }

    private static Locale defaultLocale() {
        return "ja".equalsIgnoreCase(Locale.getDefault().getLanguage())
                ? Locale.JAPANESE
                : Locale.ENGLISH;
    }
}
