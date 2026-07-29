package nlfilterlab;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class ParserConformance {
    record Report(String status, int productionRules, int labRules, List<String> differences, String reason) {
    }

    private ParserConformance() {
    }

    static Report compare(Path repositoryRoot, Path file, ParseResult lab) {
        ProductionParserOracle.Result production = ProductionParserOracle.parse(repositoryRoot, file);
        if (!production.available()) {
            return new Report("unavailable", 0, lab.rules.size(), List.of(), production.reason());
        }
        List<String> differences = new ArrayList<>();
        if (production.rules().size() != lab.rules.size()) {
            differences.add("ルール数: 本体=" + production.rules().size() + ", Lab=" + lab.rules.size());
        }
        int count = Math.min(production.rules().size(), lab.rules.size());
        for (int index = 0; index < count; index++) {
            compareRule(index, production.rules().get(index), lab.rules.get(index), differences);
        }
        return new Report(differences.isEmpty() ? "matched" : "mismatch", production.rules().size(),
                lab.rules.size(), List.copyOf(differences), null);
    }

    private static void compareRule(int index, ProductionParserOracle.Rule production, FilterRule lab,
            List<String> differences) {
        String label = "rule " + (index + 1) + " (" + Objects.toString(production.name(), "no name") + ") ";
        difference(label, "section", production.section(), lab.section.label(), differences);
        difference(label, "name", production.name(), lab.name, differences);
        difference(label, "URL", production.url(), lab.rawUrl, differences);
        difference(label, "Match", production.matches(), lab.rawMatches, differences);
        List<String> encodedReplacements = lab.replaceOnly
                ? lab.replacements.stream().map(ParserConformance::encodeReplaceOnly).toList()
                : lab.replacements;
        difference(label, "Replace", production.replacements(), encodedReplacements, differences);
        difference(label, "Require", production.require(), lab.rawRequire, differences);
        difference(label, "RequireHeader", production.requireHeader(), lab.rawRequireHeader, differences);
        difference(label, "ContentType", production.contentType(), lab.rawContentType, differences);
        List<Integer> labStatuses = lab.statusCodes == null ? List.of() : Arrays.stream(lab.statusCodes).boxed().toList();
        difference(label, "StatusCode", production.statusCodes(), labStatuses, differences);
        difference(label, "Multi", production.multi(), lab.multi, differences);
        difference(label, "EachLine", production.eachLine(), lab.eachLine, differences);
        difference(label, "MatchLocal", production.matchLocal(), lab.matchLocal, differences);
        difference(label, "ReplaceOnly", production.replaceOnly(), lab.replaceOnly, differences);
        difference(label, "ReplaceDelay", production.replaceDelay(), lab.replaceDelay, differences);
        difference(label, "noCache", production.noCache(), lab.noCache, differences);
        difference(label, "Debug", production.debug(), lab.debug, differences);
        difference(label, "idGroup", production.idGroup(), lab.idGroup, differences);
        difference(label, "idGroup2", production.idGroup2(), lab.idGroup2, differences);
        difference(label, "idGroup第2引数", production.idGroupSecondRaw(), lab.idGroupSecondRaw, differences);
        difference(label, "AddList", production.addList(), lab.addList, differences);
        difference(label, "AddVariable", production.addVariable(), lab.addVariable, differences);
    }

    private static String encodeReplaceOnly(String replacement) {
        return replacement.replaceAll("(?<!\\\\)(?=\\\\[^$])", "\\\\")
                .replaceAll("(?<!\\\\)(?=(?:\\\\\\\\)*\\$)", "\\\\");
    }

    private static void difference(String label, String field, Object production, Object lab,
            List<String> differences) {
        if (!Objects.equals(production, lab) && differences.size() < 20) {
            differences.add(label + field + " が不一致: 本体=" + production + ", Lab=" + lab);
        }
    }
}
