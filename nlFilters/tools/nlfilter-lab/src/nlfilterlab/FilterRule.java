package nlfilterlab;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class FilterRule {
    enum Section {
        REPLACE("[Replace]"),
        CONFIG("[Config]"),
        REQUEST_HEADER("[RequestHeader]"),
        STYLE("[Style]"),
        SCRIPT("[Script]");

        private final String label;

        Section(String label) {
            this.label = label;
        }

        static Section parse(String value) {
            for (Section section : values()) {
                if (section.label.equalsIgnoreCase(value)) {
                    return section;
                }
            }
            return null;
        }

        String label() {
            return label;
        }
    }

    enum CacheState {
        NONE,
        NORMAL,
        ECONOMY,
        DMC,
        DMC_ECONOMY;

        static CacheState parse(String value) {
            if (value == null) {
                return NONE;
            }
            try {
                return valueOf(value.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return NONE;
            }
        }
    }

    record Condition(Pattern pattern, boolean negated) {
        boolean find(String value) {
            boolean found = value != null && pattern.matcher(value).find();
            return found ^ negated;
        }
    }

    final Path file;
    final String sourceName;
    final int sectionLine;
    Section section;
    String name;
    String rawUrl;
    Pattern urlPattern;
    Condition contentType;
    Condition require;
    Condition requireHeader;
    String rawContentType;
    String rawRequire;
    String rawRequireHeader;
    int[] statusCodes;
    boolean multi;
    boolean eachLine;
    boolean matchLocal;
    boolean replaceOnly;
    boolean replaceDelay;
    boolean debug;
    boolean noCache;
    boolean trimNeeded;
    int idGroup = -1;
    int idGroup2 = -1;
    String idGroupSecondRaw;
    String addList;
    String addVariable;
    boolean simulationSupported = true;
    boolean appendBlock;
    final List<String> rawMatches = new ArrayList<>();
    final List<Pattern> matches = new ArrayList<>();
    final List<String> replacements = new ArrayList<>();

    FilterRule(Path file, int sectionLine, Section section) {
        this.file = file;
        this.sourceName = file.getFileName().toString();
        this.sectionLine = sectionLine;
        this.section = section;
    }

    String identifier() {
        return sourceName.replaceFirst("(?i)\\.txt$", "") + "/" +
                (name == null ? "(no name)" : name.replace('*', '_').replace('/', '_'));
    }

    boolean isResponseRule() {
        return section == Section.REPLACE || section == Section.STYLE || section == Section.SCRIPT;
    }
}
