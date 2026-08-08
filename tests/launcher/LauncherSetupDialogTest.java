package nicocache.launcher;

import java.util.ListResourceBundle;
import java.util.ResourceBundle;

import javax.swing.JCheckBox;

public final class LauncherSetupDialogTest {
    private LauncherSetupDialogTest() {
    }

    public static void main(String[] args) {
        ResourceBundle messages = new ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
                return new Object[][] {
                    { "setup.https", "HTTPS MitM" },
                    { "setup.trust", "CA trust" },
                    { "setup.proxy", "proxy.pac" },
                    { "setup.autostart", "auto-start" }
                };
            }
        };

        LauncherSetupDialog.OptionCheckBoxes options =
                LauncherSetupDialog.recommendedOptionCheckBoxes(messages);
        assertSelected(options.https, "setup.https");
        assertSelected(options.trust, "setup.trust");
        assertSelected(options.proxy, "setup.proxy");
        assertSelected(options.autostart, "setup.autostart");
        System.out.println(
                "PASS launcher setup starts with all four options selected");
    }

    private static void assertSelected(JCheckBox checkBox, String name) {
        if (!name.equals(checkBox.getName())) {
            throw new AssertionError(
                    "unexpected setup option name: " + checkBox.getName());
        }
        if (!checkBox.isSelected()) {
            throw new AssertionError(
                    "setup option must be selected initially: " + name);
        }
    }
}
