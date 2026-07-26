package dareka;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

final class SetupMessages {
    private static final String BASE_NAME = "dareka.setup_messages";
    private final ResourceBundle resources;

    SetupMessages(Locale locale) {
        resources = ResourceBundle.getBundle(
                BASE_NAME,
                locale,
                ResourceBundle.Control.getNoFallbackControl(
                        ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    String text(String key) {
        try {
            return resources.getString(key);
        } catch (MissingResourceException error) {
            return "!" + key + "!";
        }
    }
}
