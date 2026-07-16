package com.codeatlas.onboarding;

import com.codeatlas.onboarding.model.ArchitectureOrientation;
import com.codeatlas.onboarding.model.BoundarySummary;
import com.codeatlas.onboarding.model.CentralComponentSummary;
import com.codeatlas.onboarding.model.EntryPointSummary;
import com.codeatlas.onboarding.model.EvidenceRef;
import com.codeatlas.onboarding.model.ExpertQuestion;
import com.codeatlas.onboarding.model.FinalSummary;
import com.codeatlas.onboarding.model.OnboardingResult;
import com.codeatlas.onboarding.model.OnboardingRisk;
import com.codeatlas.onboarding.model.OnboardingStageResult;
import com.codeatlas.onboarding.model.ReadingRecommendation;
import com.codeatlas.onboarding.model.RepositoryIntake;
import com.codeatlas.onboarding.model.RepresentativeLineagePath;
import com.codeatlas.onboarding.model.ScanHealthSummary;
import com.codeatlas.onboarding.model.SystemInventory;
import com.codeatlas.reporting.Json;

import java.util.List;
import java.util.StringJoiner;
import java.util.function.Function;

/**
 * Renders an {@link OnboardingResult} to plain text, deterministic JSON and a
 * self-contained HTML page.
 *
 * <p><b>Determinism:</b> {@link #toJson} excludes the only volatile data -
 * per-stage durations and any wall-clock timestamp - so identical scans and
 * options yield byte-for-byte identical JSON. Text and HTML include durations (in
 * a performance section) for humans; those outputs are not the determinism target.
 *
 * <p><b>Self-contained HTML:</b> no external scripts, styles, fonts or images -
 * everything is inline, so it renders offline with no network access.
 */
public final class OnboardingReport {

    private OnboardingReport() {
    }

    // ============================ TEXT ============================

    public static String toText(OnboardingResult r) {
        StringBuilder sb = new StringBuilder();
        String bar = "=".repeat(72);
        sb.append(bar).append('\n');
        sb.append("  CODE ATLAS ONBOARDING REPORT - ").append(r.intake().displayName()).append('\n');
        sb.append("  scan ").append(r.scanId()).append("   status ").append(r.status()).append('\n');
        sb.append(bar).append('\n');

        sb.append("\nRepository:\n");
        RepositoryIntake in = r.intake();
        line(sb, "Name", in.displayName());
        line(sb, "Location", in.sanitizedLocation());
        line(sb, "Languages", String.join(", ", in.languages()));
        line(sb, "Build systems", in.buildSystems().isEmpty() ? "(none detected)"
                : String.join(", ", in.buildSystems()));
        line(sb, "Files", String.valueOf(in.totalFiles()));
        line(sb, "Tool / schema", in.toolVersion() + " / schema v" + in.schemaVersion());

        ScanHealthSummary h = r.scanHealth();
        sb.append("\nScan health: ").append(h.status()).append('\n');
        h.reasons().forEach(x -> sb.append("  - ").append(x).append('\n'));

        sb.append("\nPrimary entry points:\n");
        if (r.entryPoints().isEmpty()) {
            sb.append("  (none detected within analyzed evidence)\n");
        }
        r.entryPoints().forEach(e -> sb.append("  - ").append(e.language().toUpperCase())
                .append(": ").append(e.displayName()).append(" [").append(e.type()).append("]\n"));

        sb.append("\nJava/Ada boundaries:\n");
        if (r.boundaries().isEmpty()) {
            sb.append("  (none detected from crossing evidence)\n");
        }
        r.boundaries().forEach(b -> sb.append("  - ").append(b.type()).append(": ")
                .append(b.javaSideLabel()).append(" -> ").append(b.adaSideLabel())
                .append("  via ").append(b.sharedArtifact()).append('\n'));

        sb.append("\nRepresentative data paths:\n");
        if (r.lineagePaths().isEmpty()) {
            sb.append("  (none sampled)\n");
        }
        r.lineagePaths().forEach(p -> {
            sb.append("  - ").append(p.title()).append(p.partial() ? "  [PARTIAL]" : "").append('\n');
            p.orderedEdges().forEach(e -> sb.append("      ").append(e).append('\n'));
        });

        sb.append("\nTop components to read:\n");
        int[] n = {1};
        r.centralComponents().forEach(c -> sb.append("  ").append(n[0]++).append(". ")
                .append(c.displayName()).append("  - ").append(c.purpose())
                .append("  (score ").append(c.score()).append(")\n"));

        sb.append("\nSuggested reading order:\n");
        r.readingOrder().forEach(rr -> sb.append("  ").append(rr.order()).append(". ")
                .append(rr.displayName()).append("  - ").append(rr.reason()).append('\n'));

        sb.append("\nUnresolved / risks:\n");
        r.risks().stream().filter(x -> x.category() != com.codeatlas.onboarding.model.RiskCategory.ANALYSIS_LIMITATION)
                .forEach(x -> sb.append("  - [").append(x.category()).append("] ")
                        .append(x.title()).append('\n'));

        sb.append("\nQuestions for subject-matter experts:\n");
        r.expertQuestions().forEach(q -> sb.append("  - (").append(q.role()).append(") ")
                .append(q.question()).append('\n'));

        sb.append("\nFinal summary:\n");
        r.summary().answers().forEach(qa -> sb.append("  ").append(qa.question()).append('\n')
                .append("      ").append(qa.answer()).append('\n'));

        sb.append('\n').append(performanceText(r));
        sb.append("\nKnown limitations:\n");
        r.knownLimitations().forEach(x -> sb.append("  - ").append(x).append('\n'));
        return sb.toString();
    }

    static String performanceText(OnboardingResult r) {
        StringBuilder sb = new StringBuilder("Performance (stage durations):\n");
        for (OnboardingStageResult s : r.stages()) {
            sb.append(String.format("  %-34s %5d ms  [%s]%n", s.name(), s.durationMillis(), s.completeness()));
        }
        sb.append(String.format("  %-34s %5d ms%n", "TOTAL", r.totalDurationMillis()));
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, String value) {
        sb.append("  ").append(label).append(": ").append(value).append('\n');
    }

    // ============================ JSON ============================
    // Deterministic: no durations, no timestamps. Lists are pre-sorted by the service.

    public static String toJson(OnboardingResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"scanId\": ").append(q(r.scanId())).append(",\n");
        sb.append("  \"status\": ").append(q(r.status())).append(",\n");
        sb.append("  \"toolVersion\": ").append(q(r.toolVersion())).append(",\n");
        sb.append("  \"ruleVersions\": ").append(strArr(r.ruleVersions())).append(",\n");
        sb.append("  \"repository\": ").append(intakeJson(r.intake())).append(",\n");
        sb.append("  \"scanHealth\": ").append(healthJson(r.scanHealth())).append(",\n");
        sb.append("  \"inventory\": ").append(inventoryJson(r.inventory())).append(",\n");
        sb.append("  \"entryPoints\": ").append(list(r.entryPoints(), OnboardingReport::entryJson)).append(",\n");
        sb.append("  \"orientation\": ").append(orientationJson(r.orientation())).append(",\n");
        sb.append("  \"boundaries\": ").append(list(r.boundaries(), OnboardingReport::boundaryJson)).append(",\n");
        sb.append("  \"lineagePaths\": ").append(list(r.lineagePaths(), OnboardingReport::pathJson)).append(",\n");
        sb.append("  \"centralComponents\": ").append(list(r.centralComponents(), OnboardingReport::componentJson)).append(",\n");
        sb.append("  \"risks\": ").append(list(r.risks(), OnboardingReport::riskJson)).append(",\n");
        sb.append("  \"readingOrder\": ").append(list(r.readingOrder(), OnboardingReport::readingJson)).append(",\n");
        sb.append("  \"expertQuestions\": ").append(list(r.expertQuestions(), OnboardingReport::questionJson)).append(",\n");
        sb.append("  \"finalSummary\": ").append(summaryJson(r.summary())).append(",\n");
        sb.append("  \"stages\": ").append(list(r.stages(), OnboardingReport::stageJson)).append(",\n");
        sb.append("  \"knownLimitations\": ").append(strArr(r.knownLimitations())).append('\n');
        sb.append("}\n");
        return sb.toString();
    }

    private static String intakeJson(RepositoryIntake i) {
        return "{"
                + "\"repositoryKey\": " + q(i.repositoryKey())
                + ", \"displayName\": " + q(i.displayName())
                + ", \"sanitizedLocation\": " + q(i.sanitizedLocation())
                + ", \"branch\": " + q(i.branch())
                + ", \"storageMode\": " + q(i.storageMode())
                + ", \"schemaVersion\": " + q(i.schemaVersion())
                + ", \"languages\": " + strArr(i.languages())
                + ", \"buildSystems\": " + strArr(i.buildSystems())
                + ", \"totalFiles\": " + i.totalFiles()
                + "}";
    }

    private static String healthJson(ScanHealthSummary h) {
        return "{"
                + "\"status\": " + q(h.status())
                + ", \"filesDiscovered\": " + h.filesDiscovered()
                + ", \"filesAnalyzed\": " + h.filesAnalyzed()
                + ", \"filesReused\": " + h.filesReused()
                + ", \"filesSkipped\": " + h.filesSkipped()
                + ", \"filesFailed\": " + h.filesFailed()
                + ", \"unsupportedFileTypes\": " + h.unsupportedFileTypes()
                + ", \"referencesResolved\": " + h.referencesResolved()
                + ", \"referencesUnresolved\": " + h.referencesUnresolved()
                + ", \"referencesAmbiguous\": " + h.referencesAmbiguous()
                + ", \"resolutionRatePercent\": " + h.resolutionRatePercent()
                + ", \"exactFileCounts\": " + h.exactFileCounts()
                + ", \"reasons\": " + strArr(h.reasons())
                + "}";
    }

    private static String inventoryJson(SystemInventory inv) {
        return list(inv.categories(), c -> "{\"name\": " + q(c.name()) + ", \"count\": " + c.count()
                + ", \"examples\": " + strArr(c.examples()) + "}");
    }

    private static String entryJson(EntryPointSummary e) {
        return "{\"stableId\": " + q(e.stableId()) + ", \"displayName\": " + q(e.displayName())
                + ", \"language\": " + q(e.language()) + ", \"type\": " + q(e.type())
                + ", \"location\": " + q(e.location()) + ", \"confidence\": " + q(e.confidence())
                + ", \"resolutionStatus\": " + q(e.resolutionStatus())
                + ", \"evidence\": " + refArr(e.evidence()) + "}";
    }

    private static String orientationJson(ArchitectureOrientation o) {
        return "{\"majorModules\": " + strArr(o.majorModules())
                + ", \"inferredLayers\": " + strArr(o.inferredLayers())
                + ", \"mostConnected\": " + strArr(o.mostConnected())
                + ", \"dataAccess\": " + strArr(o.dataAccess())
                + ", \"externalFacing\": " + strArr(o.externalFacing())
                + ", \"notes\": " + strArr(o.notes()) + "}";
    }

    private static String boundaryJson(BoundarySummary b) {
        return "{\"type\": " + q(b.type().name()) + ", \"javaSideId\": " + q(b.javaSideId())
                + ", \"javaSideLabel\": " + q(b.javaSideLabel()) + ", \"adaSideId\": " + q(b.adaSideId())
                + ", \"adaSideLabel\": " + q(b.adaSideLabel()) + ", \"sharedArtifact\": " + q(b.sharedArtifact())
                + ", \"confidence\": " + q(b.confidence()) + ", \"missingInformation\": " + q(b.missingInformation())
                + ", \"resolutionStatus\": " + q(b.resolutionStatus())
                + ", \"evidence\": " + refArr(b.evidence()) + "}";
    }

    private static String pathJson(RepresentativeLineagePath p) {
        return "{\"title\": " + q(p.title()) + ", \"startId\": " + q(p.startId())
                + ", \"endId\": " + q(p.endId()) + ", \"orderedNodes\": " + strArr(p.orderedNodes())
                + ", \"orderedEdges\": " + strArr(p.orderedEdges())
                + ", \"relationshipTypes\": " + strArr(p.relationshipTypes())
                + ", \"confidence\": " + q(p.confidence()) + ", \"partial\": " + p.partial()
                + ", \"unresolvedGaps\": " + strArr(p.unresolvedGaps())
                + ", \"blindSpots\": " + strArr(p.blindSpots())
                + ", \"evidence\": " + refArr(p.evidence()) + "}";
    }

    private static String componentJson(CentralComponentSummary c) {
        return "{\"stableId\": " + q(c.stableId()) + ", \"displayName\": " + q(c.displayName())
                + ", \"language\": " + q(c.language()) + ", \"purpose\": " + q(c.purpose())
                + ", \"score\": " + c.score() + ", \"scoreBasis\": " + q(c.scoreBasis())
                + ", \"complexity\": " + c.complexity()
                + ", \"inputs\": " + strArr(c.inputs()) + ", \"outputs\": " + strArr(c.outputs())
                + ", \"callers\": " + strArr(c.callers()) + ", \"callees\": " + strArr(c.callees())
                + ", \"dependencies\": " + strArr(c.dependencies()) + ", \"dependents\": " + strArr(c.dependents())
                + ", \"dataSources\": " + strArr(c.dataSources()) + ", \"dataSinks\": " + strArr(c.dataSinks())
                + ", \"sideEffects\": " + strArr(c.sideEffects()) + ", \"limitations\": " + strArr(c.limitations())
                + ", \"evidence\": " + refArr(c.evidence()) + "}";
    }

    private static String riskJson(OnboardingRisk k) {
        return "{\"category\": " + q(k.category().name()) + ", \"title\": " + q(k.title())
                + ", \"description\": " + q(k.description()) + ", \"evidence\": " + refArr(k.evidence()) + "}";
    }

    private static String readingJson(ReadingRecommendation rr) {
        return "{\"order\": " + rr.order() + ", \"targetId\": " + q(rr.targetId())
                + ", \"displayName\": " + q(rr.displayName()) + ", \"reason\": " + q(rr.reason())
                + ", \"questionAnswered\": " + q(rr.questionAnswered())
                + ", \"prerequisites\": " + strArr(rr.prerequisites())
                + ", \"confidence\": " + q(rr.confidence()) + ", \"evidence\": " + refArr(rr.evidence()) + "}";
    }

    private static String questionJson(ExpertQuestion q) {
        return "{\"role\": " + q(q.role()) + ", \"question\": " + q(q.question())
                + ", \"basis\": " + q(q.basis()) + ", \"componentIds\": " + strArr(q.componentIds())
                + ", \"evidence\": " + refArr(q.evidence()) + "}";
    }

    private static String summaryJson(FinalSummary s) {
        String answers = list(s.answers(), a -> "{\"question\": " + q(a.question())
                + ", \"answer\": " + q(a.answer()) + "}");
        return "{\"answers\": " + answers
                + ", \"confirmedFacts\": " + strArr(s.confirmedFacts())
                + ", \"resolvedRelationships\": " + strArr(s.resolvedRelationships())
                + ", \"inferredArchitecture\": " + strArr(s.inferredArchitecture())
                + ", \"unresolvedQuestions\": " + strArr(s.unresolvedQuestions())
                + ", \"knownLimitations\": " + strArr(s.knownLimitations()) + "}";
    }

    private static String stageJson(OnboardingStageResult s) {
        // Duration deliberately excluded to keep JSON deterministic.
        return "{\"name\": " + q(s.name()) + ", \"completeness\": " + q(s.completeness().name())
                + ", \"inputs\": " + strArr(s.inputs()) + ", \"output\": " + q(s.output())
                + ", \"warnings\": " + strArr(s.warnings()) + ", \"evidence\": " + refArr(s.evidence()) + "}";
    }

    // ---- JSON helpers ----

    private static String q(String s) {
        return Json.quote(s);
    }

    private static String strArr(List<String> list) {
        if (list.isEmpty()) {
            return "[]";
        }
        StringJoiner j = new StringJoiner(", ", "[", "]");
        list.forEach(s -> j.add(q(s)));
        return j.toString();
    }

    private static String refArr(List<EvidenceRef> refs) {
        if (refs.isEmpty()) {
            return "[]";
        }
        StringJoiner j = new StringJoiner(", ", "[", "]");
        refs.forEach(x -> j.add("{\"stableId\": " + q(x.stableId()) + ", \"location\": " + q(x.location()) + "}"));
        return j.toString();
    }

    private static <T> String list(List<T> items, Function<T, String> render) {
        if (items.isEmpty()) {
            return "[]";
        }
        StringJoiner j = new StringJoiner(",\n    ", "[\n    ", "\n  ]");
        items.forEach(i -> j.add(render.apply(i)));
        return j.toString();
    }

    // ============================ HTML ============================
    // Self-contained: inline CSS only, no scripts, no external assets.

    public static String toHtml(OnboardingResult r, String generatedAtLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Code Atlas Onboarding - ").append(esc(r.intake().displayName()))
                .append("</title>").append(css()).append("</head><body>");
        sb.append("<header><h1>Code Atlas Onboarding Report</h1>")
                .append("<p class=\"sub\">").append(esc(r.intake().displayName()))
                .append(" &middot; scan ").append(esc(r.scanId()))
                .append(" &middot; status <b class=\"").append(statusClass(r.status())).append("\">")
                .append(esc(r.status())).append("</b>")
                .append(generatedAtLabel == null ? "" : " &middot; generated " + esc(generatedAtLabel))
                .append("</p>")
                .append("<p class=\"note\">Evidence-backed software understanding - not operational "
                        + "authorization, certification, or accreditation.</p></header>");

        // Repository + scan health
        RepositoryIntake in = r.intake();
        sb.append(section("1 · Repository", "<table>"
                + row("Name", esc(in.displayName())) + row("Location", esc(in.sanitizedLocation()))
                + row("Languages", esc(String.join(", ", in.languages())))
                + row("Build systems", esc(in.buildSystems().isEmpty() ? "(none detected)"
                        : String.join(", ", in.buildSystems())))
                + row("Files", String.valueOf(in.totalFiles()))
                + row("Tool / schema", esc(in.toolVersion()) + " / schema v" + esc(in.schemaVersion()))
                + "</table>"));

        ScanHealthSummary h = r.scanHealth();
        sb.append(section("2 · Scan health",
                "<p class=\"badge " + statusClass(h.status()) + "\">" + esc(h.status()) + "</p>"
                        + "<p>" + h.resolutionRatePercent() + "% of references resolved; "
                        + h.referencesUnresolved() + " unresolved, " + h.referencesAmbiguous() + " ambiguous.</p>"
                        + ul(h.reasons())));

        // Inventory
        StringBuilder inv = new StringBuilder("<table><tr><th>Category</th><th>Count</th><th>Examples</th></tr>");
        r.inventory().categories().forEach(c -> inv.append("<tr><td>").append(esc(c.name()))
                .append("</td><td>").append(c.count()).append("</td><td>")
                .append(esc(String.join(", ", c.examples()))).append("</td></tr>"));
        inv.append("</table>");
        sb.append(section("3 · System inventory", inv.toString()));

        // Entry points
        StringBuilder ep = new StringBuilder();
        if (r.entryPoints().isEmpty()) {
            ep.append("<p>(none detected within analyzed evidence)</p>");
        } else {
            ep.append("<ul>");
            r.entryPoints().forEach(e -> ep.append("<li><b>").append(esc(e.language().toUpperCase()))
                    .append("</b> ").append(esc(e.displayName())).append(" - <i>").append(esc(e.type()))
                    .append("</i> <span class=\"loc\">").append(esc(e.location())).append("</span></li>"));
            ep.append("</ul>");
        }
        sb.append(section("4 · Entry points", ep.toString()));

        // Orientation
        ArchitectureOrientation o = r.orientation();
        sb.append(section("5 · Architecture orientation",
                labeled("Major modules", o.majorModules()) + labeled("Inferred layers", o.inferredLayers())
                        + labeled("Most connected", o.mostConnected()) + labeled("Data access", o.dataAccess())
                        + labeled("External-facing", o.externalFacing()) + labeled("Notes", o.notes())));

        // Boundaries
        StringBuilder bd = new StringBuilder();
        if (r.boundaries().isEmpty()) {
            bd.append("<p>(none detected from crossing evidence)</p>");
        } else {
            bd.append("<ul>");
            r.boundaries().forEach(b -> bd.append("<li><b>").append(esc(b.type().name())).append("</b>: ")
                    .append(esc(b.javaSideLabel())).append(" &rarr; ").append(esc(b.adaSideLabel()))
                    .append(" via ").append(esc(b.sharedArtifact()))
                    .append("<br><span class=\"muted\">").append(esc(b.confidence()))
                    .append("</span><br><span class=\"muted\">Missing: ").append(esc(b.missingInformation()))
                    .append("</span></li>"));
            bd.append("</ul>");
        }
        sb.append(section("6 · Java/Ada boundaries", bd.toString()));

        // Lineage paths
        StringBuilder lp = new StringBuilder();
        if (r.lineagePaths().isEmpty()) {
            lp.append("<p>(none sampled)</p>");
        }
        r.lineagePaths().forEach(p -> {
            lp.append("<div class=\"path\"><b>").append(esc(p.title())).append("</b> ")
                    .append(p.partial() ? "<span class=\"badge warn\">PARTIAL</span>" : "")
                    .append(" <span class=\"muted\">").append(esc(p.confidence())).append("</span><ol>");
            p.orderedEdges().forEach(e -> lp.append("<li>").append(esc(e)).append("</li>"));
            lp.append("</ol>").append(p.unresolvedGaps().isEmpty() ? ""
                    : "<div class=\"muted\">Gaps: " + esc(String.join("; ", p.unresolvedGaps())) + "</div>")
                    .append("</div>");
        });
        sb.append(section("7 · Representative data-lineage paths", lp.toString()));

        // Central components
        StringBuilder cc = new StringBuilder();
        int[] i = {1};
        r.centralComponents().forEach(c -> cc.append("<div class=\"card\"><b>").append(i[0]++).append(". ")
                .append(esc(c.displayName())).append("</b> <span class=\"muted\">score ").append(c.score())
                .append("</span><br><i>").append(esc(c.purpose())).append("</i>")
                .append("<div class=\"muted\">").append(esc(c.scoreBasis())).append("</div>")
                .append(c.dataSinks().isEmpty() ? "" : "<div>Writes: " + esc(String.join(", ", c.dataSinks())) + "</div>")
                .append("</div>"));
        sb.append(section("8 · Central components", cc.toString()));

        // Risks
        sb.append(section("9 · Risks and gaps", riskHtml(r)));

        // Reading order
        StringBuilder ro = new StringBuilder("<ol>");
        r.readingOrder().forEach(rr -> ro.append("<li><b>").append(esc(rr.displayName())).append("</b> - ")
                .append(esc(rr.reason())).append("<br><span class=\"muted\">Answers: ")
                .append(esc(rr.questionAnswered())).append("</span></li>"));
        ro.append("</ol>");
        sb.append(section("10 · Suggested reading order", ro.toString()));

        // Expert questions
        StringBuilder eq = new StringBuilder("<ul>");
        r.expertQuestions().forEach(q -> eq.append("<li><b>").append(esc(q.role())).append(":</b> ")
                .append(esc(q.question())).append("</li>"));
        eq.append("</ul>");
        sb.append(section("11 · Questions for subject-matter experts", eq.toString()));

        // Final summary
        StringBuilder fs = new StringBuilder("<dl>");
        r.summary().answers().forEach(qa -> fs.append("<dt>").append(esc(qa.question())).append("</dt><dd>")
                .append(esc(qa.answer())).append("</dd>"));
        fs.append("</dl>");
        fs.append(labeled("Confirmed facts", r.summary().confirmedFacts()));
        fs.append(labeled("Resolved relationships", r.summary().resolvedRelationships()));
        fs.append(labeled("Inferred architecture", r.summary().inferredArchitecture()));
        fs.append(labeled("Unresolved questions", r.summary().unresolvedQuestions()));
        sb.append(section("12 · Final onboarding summary", fs.toString()));

        sb.append(section("Known limitations", ul(r.knownLimitations())));
        sb.append("<footer>Code Atlas ").append(esc(r.toolVersion())).append(" &middot; ")
                .append(esc(String.join(", ", r.ruleVersions())))
                .append(" &middot; offline, read-only, deterministic.</footer>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String riskHtml(OnboardingResult r) {
        StringBuilder sb = new StringBuilder("<ul>");
        r.risks().forEach(k -> sb.append("<li><span class=\"tag\">").append(esc(k.category().name()))
                .append("</span> <b>").append(esc(k.title())).append("</b> - ")
                .append(esc(k.description())).append("</li>"));
        return sb.append("</ul>").toString();
    }

    private static String section(String title, String body) {
        return "<section><h2>" + esc(title) + "</h2>" + body + "</section>";
    }

    private static String labeled(String label, List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        return "<div class=\"labeled\"><h3>" + esc(label) + "</h3>" + ul(items) + "</div>";
    }

    private static String ul(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<ul>");
        items.forEach(x -> sb.append("<li>").append(esc(x)).append("</li>"));
        return sb.append("</ul>").toString();
    }

    private static String row(String k, String v) {
        return "<tr><th>" + esc(k) + "</th><td>" + v + "</td></tr>";
    }

    private static String statusClass(String status) {
        return switch (status) {
            case "HEALTHY" -> "ok";
            case "PARTIAL", "POOR" -> "warn";
            case "FAILED" -> "bad";
            default -> "muted";
        };
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String css() {
        return "<style>"
                + ":root{--bg:#fff;--fg:#1a1a1a;--muted:#666;--line:#e2e2e2;--accent:#2b5;--card:#f7f7f8;}"
                + "@media(prefers-color-scheme:dark){:root{--bg:#16181c;--fg:#e6e6e6;--muted:#9aa;--line:#333;--card:#1e2127;}}"
                + "*{box-sizing:border-box;}body{margin:0;padding:0 1rem 3rem;background:var(--bg);color:var(--fg);"
                + "font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;max-width:60rem;margin:0 auto;}"
                + "header{padding:1.5rem 0;border-bottom:2px solid var(--line);}"
                + "h1{margin:0 0 .3rem;font-size:1.6rem;}h2{font-size:1.15rem;border-bottom:1px solid var(--line);"
                + "padding-bottom:.3rem;margin-top:2rem;}h3{font-size:.95rem;margin:.8rem 0 .3rem;color:var(--muted);}"
                + ".sub{color:var(--fg);margin:.2rem 0;}.note{color:var(--muted);font-size:.85rem;}"
                + "table{border-collapse:collapse;width:100%;overflow-x:auto;display:block;}"
                + "th,td{text-align:left;padding:.35rem .6rem;border-bottom:1px solid var(--line);vertical-align:top;}"
                + "th{color:var(--muted);font-weight:600;white-space:nowrap;}"
                + "ul,ol{margin:.4rem 0;padding-left:1.3rem;}li{margin:.25rem 0;}"
                + ".muted{color:var(--muted);font-size:.85rem;}.loc{color:var(--muted);font-size:.8rem;}"
                + ".card,.path{background:var(--card);border:1px solid var(--line);border-radius:6px;"
                + "padding:.6rem .8rem;margin:.5rem 0;}"
                + ".badge{display:inline-block;padding:.05rem .5rem;border-radius:10px;font-size:.75rem;font-weight:700;}"
                + ".tag{display:inline-block;padding:.02rem .4rem;border:1px solid var(--line);border-radius:4px;"
                + "font-size:.7rem;color:var(--muted);}"
                + ".ok{color:var(--accent);}.warn{color:#b80;}.bad{color:#c33;}"
                + ".badge.ok{background:#2b551a;color:#fff;}.badge.warn{background:#b80;color:#fff;}"
                + ".badge.bad{background:#c33;color:#fff;}"
                + "dt{font-weight:600;margin-top:.4rem;}dd{margin:0 0 .3rem;color:var(--muted);}"
                + "footer{margin-top:2rem;padding-top:1rem;border-top:1px solid var(--line);color:var(--muted);"
                + "font-size:.8rem;}code{font-family:ui-monospace,monospace;}"
                + "</style>";
    }
}
