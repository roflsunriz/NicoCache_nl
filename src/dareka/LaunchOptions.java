package dareka;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class LaunchOptions {
    private static final String USAGE =
            "Usage: NicoCache_nl.exe --setup --headless "
            + "--https=<true|false> "
            + "--trust-certificate=<true|false> "
            + "--proxy=<true|false> "
            + "--autostart=<true|false>";

    private final boolean headless;
    private final boolean setup;
    private final SetupOptions setupOptions;
    private final String[] forwardedArgs;
    private final String error;

    private LaunchOptions(boolean headless, boolean setup,
            SetupOptions setupOptions, String[] forwardedArgs, String error) {
        this.headless = headless;
        this.setup = setup;
        this.setupOptions = setupOptions;
        this.forwardedArgs = forwardedArgs;
        this.error = error;
    }

    static LaunchOptions parse(String[] args) {
        boolean headless = false;
        boolean setup = false;
        List<String> forwarded = new ArrayList<>();
        Map<String, Boolean> values = new HashMap<>();

        for (String arg : args) {
            if ("--headless".equals(arg)) {
                headless = true;
            } else if ("--setup".equals(arg)) {
                setup = true;
            } else if (arg.startsWith("--https=")
                    || arg.startsWith("--trust-certificate=")
                    || arg.startsWith("--proxy=")
                    || arg.startsWith("--autostart=")) {
                int separator = arg.indexOf('=');
                String name = arg.substring(2, separator);
                String value = arg.substring(separator + 1);
                if (!"true".equals(value) && !"false".equals(value)) {
                    return error(headless, setup,
                            "true または false を指定してください: --" + name);
                }
                if (values.put(name, Boolean.valueOf(value)) != null) {
                    return error(headless, setup,
                            "オプションが重複しています: --" + name);
                }
            } else {
                forwarded.add(arg);
            }
        }

        if (!setup) {
            if (!values.isEmpty()) {
                return error(headless, false,
                        "初回セットアップの選択には --setup が必要です");
            }
            return new LaunchOptions(
                    headless,
                    false,
                    null,
                    forwarded.toArray(new String[0]),
                    null);
        }
        if (!headless) {
            return error(false, true,
                    "--setup は --headless と同時に指定してください。"
                    + "GUIでは初回起動時に自動表示されます");
        }
        if (!forwarded.isEmpty()) {
            return error(true, true,
                    "不明なセットアップオプションです: " + forwarded.get(0));
        }
        for (String required : new String[] {
                "https", "trust-certificate", "proxy", "autostart" }) {
            if (!values.containsKey(required)) {
                return error(true, true,
                        "必須オプションがありません: --" + required);
            }
        }

        boolean https = values.get("https");
        boolean trustCertificate = values.get("trust-certificate");
        boolean proxy = values.get("proxy");
        if (!https && trustCertificate) {
            return error(true, true,
                    "--trust-certificate=true には --https=true が必要です");
        }
        if (!https && proxy) {
            return error(true, true,
                    "--proxy=true には --https=true が必要です");
        }
        SetupOptions options = new SetupOptions(
                https,
                trustCertificate,
                proxy,
                values.get("autostart"));
        return new LaunchOptions(true, true, options, new String[0], null);
    }

    private static LaunchOptions error(boolean headless, boolean setup,
            String message) {
        return new LaunchOptions(
                headless,
                setup,
                null,
                new String[0],
                message + System.lineSeparator() + USAGE);
    }

    boolean isHeadless() {
        return headless;
    }

    boolean isSetup() {
        return setup;
    }

    SetupOptions getSetupOptions() {
        return setupOptions;
    }

    String[] getForwardedArgs() {
        return forwardedArgs.clone();
    }

    String getError() {
        return error;
    }
}
