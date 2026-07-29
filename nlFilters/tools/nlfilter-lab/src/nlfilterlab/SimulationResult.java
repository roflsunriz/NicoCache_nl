package nlfilterlab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SimulationResult {
    final String original;
    String rendered;
    final List<Trace> traces = new ArrayList<>();
    final List<Diagnostic> diagnostics = new ArrayList<>();
    String effectiveUrl;
    final Map<String, String> variables = new LinkedHashMap<>();
    final Map<String, List<String>> listAdditions = new LinkedHashMap<>();

    SimulationResult(String original) {
        this.original = original;
        this.rendered = original;
    }

    record Trace(String identifier, String section, int replacements, String note) {
    }
}
