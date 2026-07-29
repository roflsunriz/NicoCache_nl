package nlfilterlab;

import java.util.ArrayList;
import java.util.List;

final class ParseResult {
    final List<FilterRule> rules = new ArrayList<>();
    final List<Diagnostic> diagnostics = new ArrayList<>();

    boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }

    void merge(ParseResult other) {
        rules.addAll(other.rules);
        diagnostics.addAll(other.diagnostics);
    }
}
