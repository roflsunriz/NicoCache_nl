package nicocache.cmaftomp4;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** CLIオプションを解釈した不変値。 */
final class CliOptions {
    private final boolean headless;
    private final boolean help;
    private final boolean version;
    private final boolean force;
    private final boolean verbose;
    private final boolean openOutput;
    private final Path input;
    private final Path output;
    private final String ffmpeg;
    private final String title;
    private final Locale locale;

    private CliOptions(Builder builder) {
        headless = builder.headless;
        help = builder.help;
        version = builder.version;
        force = builder.force;
        verbose = builder.verbose;
        openOutput = builder.openOutput;
        input = builder.input;
        output = builder.output;
        ffmpeg = builder.ffmpeg;
        title = builder.title;
        locale = builder.locale;
    }

    static CliOptions parse(String[] args) throws CliException {
        Builder builder = new Builder();
        boolean positionalOnly = false;
        for (int index = 0; index < args.length; index++) {
            String raw = args[index];
            if (positionalOnly) {
                builder.setInput(Paths.get(raw));
                continue;
            }
            if ("--".equals(raw)) {
                positionalOnly = true;
                continue;
            }
            if ("--help".equals(raw) || "-h".equals(raw)) {
                builder.help = true;
                continue;
            }
            if ("--version".equals(raw) || "-v".equals(raw)) {
                builder.version = true;
                continue;
            }
            if ("--headless".equals(raw) || "-H".equals(raw)) {
                builder.headless = true;
                continue;
            }
            if ("--force".equals(raw) || "-f".equals(raw)) {
                builder.force = true;
                continue;
            }
            if ("--verbose".equals(raw)) {
                builder.verbose = true;
                continue;
            }
            if ("--open-output".equals(raw)) {
                builder.openOutput = true;
                continue;
            }

            String option = raw;
            String inlineValue = null;
            int equals = raw.indexOf('=');
            if (equals > 0 && raw.startsWith("--")) {
                option = raw.substring(0, equals);
                inlineValue = raw.substring(equals + 1);
            }

            if ("--input".equals(option) || "-i".equals(option)) {
                builder.setInput(Paths.get(requireValue(option, inlineValue, args, index + 1)));
                if (inlineValue == null) {
                    index++;
                }
                continue;
            }
            if ("--output".equals(option) || "-o".equals(option)) {
                builder.output = Paths.get(requireValue(option, inlineValue, args, index + 1));
                if (inlineValue == null) {
                    index++;
                }
                continue;
            }
            if ("--ffmpeg".equals(option)) {
                builder.ffmpeg = requireValue(option, inlineValue, args, index + 1);
                if (inlineValue == null) {
                    index++;
                }
                continue;
            }
            if ("--title".equals(option)) {
                builder.title = requireValue(option, inlineValue, args, index + 1);
                if (inlineValue == null) {
                    index++;
                }
                continue;
            }
            if ("--lang".equals(option)) {
                builder.locale = parseLocale(requireValue(option, inlineValue, args, index + 1));
                if (inlineValue == null) {
                    index++;
                }
                Messages.setLocale(builder.locale);
                continue;
            }
            if (raw.startsWith("-")) {
                throw new CliException(Messages.format("error.unknown-option", raw));
            }
            builder.setInput(Paths.get(raw));
        }
        return new CliOptions(builder);
    }

    boolean isHeadless() {
        return headless;
    }

    boolean isHelp() {
        return help;
    }

    boolean isVersion() {
        return version;
    }

    boolean isForce() {
        return force;
    }

    boolean isVerbose() {
        return verbose;
    }

    boolean isOpenOutput() {
        return openOutput;
    }

    Path getInput() {
        return input;
    }

    Path getOutput() {
        return output;
    }

    String getFfmpeg() {
        return ffmpeg;
    }

    String getTitle() {
        return title;
    }

    Locale getLocale() {
        return locale;
    }

    private static String requireValue(
            String option, String inlineValue, String[] args, int nextIndex) throws CliException {
        if (inlineValue != null) {
            if (!inlineValue.isEmpty()) {
                return inlineValue;
            }
            throw new CliException(Messages.format("error.empty-value", option));
        }
        if (nextIndex >= args.length || args[nextIndex].startsWith("-")) {
            throw new CliException(Messages.format("error.missing-value", option));
        }
        return args[nextIndex];
    }

    private static Locale parseLocale(String value) throws CliException {
        if ("ja".equalsIgnoreCase(value) || "ja-jp".equalsIgnoreCase(value)) {
            return Locale.JAPANESE;
        }
        if ("en".equalsIgnoreCase(value) || "en-us".equalsIgnoreCase(value)) {
            return Locale.ENGLISH;
        }
        throw new CliException(Messages.format("error.unsupported-language", value));
    }

    private static final class Builder {
        boolean headless;
        boolean help;
        boolean version;
        boolean force;
        boolean verbose;
        boolean openOutput;
        Path input;
        Path output;
        String ffmpeg;
        String title;
        Locale locale;

        void setInput(Path value) throws CliException {
            if (input != null) {
                throw new CliException(Messages.get("error.multiple-input"));
            }
            input = value;
        }
    }
}
