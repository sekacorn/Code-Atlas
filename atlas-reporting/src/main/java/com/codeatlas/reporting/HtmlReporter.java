package com.codeatlas.reporting;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.analysis.AnalysisResult;
import com.codeatlas.analysis.ComplexityHotspot;
import com.codeatlas.analysis.ComponentDependency;
import com.codeatlas.analysis.DeadCodeCandidate;
import com.codeatlas.analysis.RepositoryMetrics;
import com.codeatlas.analysis.lineage.LineageSummary;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Renders a single self-contained HTML page: dashboard, metrics, complexity,
 * dead code and the component-level data-flow view. No external assets, no CDN,
 * no scripts &mdash; it opens offline in any browser, which suits restricted /
 * air-gapped environments.
 */
public final class HtmlReporter {

    public String render(ReportData data) {
        AnalysisResult a = data.analysis();
        RepositoryMetrics m = a.metrics();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
          .append("<title>Code Atlas — ").append(esc(data.repositoryName())).append("</title>")
          .append(css())
          .append("</head><body>");

        sb.append("<header><h1>Code Atlas</h1><div class=\"sub\">")
          .append(esc(data.repositoryName())).append(" · generated ")
          .append(esc(data.generatedAt().atZone(java.time.ZoneId.systemDefault())
                  .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))))
          .append(" · scanned in ").append(data.scanDurationMillis()).append(" ms · ")
          .append(esc(data.scanId())).append("</div></header>");

        sb.append("<main>");
        coverageBanner(sb, data.coverage());
        dashboard(sb, m, a);
        coverageSection(sb, data.coverage());
        languageDistribution(sb, m);
        lineageSection(sb, a.lineage());
        complexitySection(sb, a);
        deadCodeSection(sb, a);
        dependencySection(sb, a);
        dataFlowSection(sb, a);
        sb.append("</main>");

        sb.append("<footer>Deterministic static analysis · no AI required · findings are evidence-based and should be reviewed before action.</footer>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private void coverageBanner(StringBuilder sb, AnalysisCoverage c) {
        if (c.isPartial()) {
            sb.append("<div class=\"banner\">⚠ <strong>Partial analysis.</strong> ")
              .append("This scan did not fully cover the repository — ")
              .append(c.resolutionRatePercent()).append("% of detected references resolved, ")
              .append(c.filesSkipped()).append(" file(s) skipped, ")
              .append(c.filesFailed()).append(" failed. See coverage below; findings should be read accordingly.</div>");
        } else {
            sb.append("<div class=\"banner ok\">✓ Full coverage: every discovered file was analysed and all detected references resolved.</div>");
        }
    }

    private void coverageSection(StringBuilder sb, AnalysisCoverage c) {
        sb.append("<section><h2>Analysis coverage</h2>")
          .append("<p class=\"note\">What Code Atlas actually understood. Incomplete coverage is reported, never hidden.</p>");
        sb.append("<table class=\"cov\"><tbody>");
        covRow(sb, "Files discovered", format(c.filesDiscovered()));
        covRow(sb, "Files analyzed", format(c.filesAnalyzed()));
        covRow(sb, "Files skipped (no parser)", format(c.filesSkipped()));
        covRow(sb, "Files failed", format(c.filesFailed()));
        covRow(sb, "Files unreadable", format(c.filesUnreadable()));
        covRow(sb, "Files reused from cache", format(c.filesReused()));
        covRow(sb, "Unsupported file types", format(c.unsupportedFileTypes()));
        covRow(sb, "References resolved", format(c.referencesResolved()));
        covRow(sb, "References unresolved", format(c.referencesUnresolved()));
        covRow(sb, "References ambiguous", format(c.referencesAmbiguous()));
        covRow(sb, "Resolution rate", c.resolutionRatePercent() + "%");
        covRow(sb, "Scan status", c.isPartial() ? "PARTIAL" : "COMPLETE");
        sb.append("</tbody></table></section>");
    }

    private void covRow(StringBuilder sb, String label, String value) {
        sb.append("<tr><td>").append(esc(label)).append("</td><td class=\"num\">")
          .append(esc(value)).append("</td></tr>");
    }

    private void dashboard(StringBuilder sb, RepositoryMetrics m, AnalysisResult a) {
        sb.append("<section><h2>Repository dashboard</h2><div class=\"cards\">");
        card(sb, "Files", format(m.totalFiles()), null);
        card(sb, "Lines of code", format(m.codeLines()), format(m.totalLines()) + " total");
        card(sb, "Languages", String.valueOf(m.filesByLanguage().size()), null);
        card(sb, "Classes / Types", format(m.countOf(com.codeatlas.model.EntityKind.CLASS)
                + m.countOf(com.codeatlas.model.EntityKind.RECORD)
                + m.countOf(com.codeatlas.model.EntityKind.ENUM)
                + m.countOf(com.codeatlas.model.EntityKind.TYPE)), null);
        card(sb, "Methods / Subprograms", format(m.countOf(com.codeatlas.model.EntityKind.METHOD)
                + m.countOf(com.codeatlas.model.EntityKind.FUNCTION)
                + m.countOf(com.codeatlas.model.EntityKind.PROCEDURE)), null);
        card(sb, "Packages", format(m.countOf(com.codeatlas.model.EntityKind.PACKAGE)), null);
        card(sb, "Complexity hotspots", format(a.complexityHotspots().size()), null);
        card(sb, "Dead-code candidates", format(a.deadCode().size()), a.deadCodePercent() + "% of units");
        sb.append("</div></section>");
    }

    private void languageDistribution(StringBuilder sb, RepositoryMetrics m) {
        if (m.filesByLanguage().isEmpty()) {
            return;
        }
        int max = m.filesByLanguage().values().stream().mapToInt(Integer::intValue).max().orElse(1);
        sb.append("<section><h2>Language distribution</h2><table class=\"bars\">");
        for (Map.Entry<String, Integer> e : m.filesByLanguage().entrySet()) {
            int pct = (int) Math.round(100.0 * e.getValue() / max);
            sb.append("<tr><td class=\"lbl\">").append(esc(e.getKey())).append("</td>")
              .append("<td class=\"bar\"><span style=\"width:").append(pct).append("%\"></span></td>")
              .append("<td class=\"num\">").append(e.getValue()).append(" files</td></tr>");
        }
        sb.append("</table></section>");
    }

    private void lineageSection(StringBuilder sb, LineageSummary s) {
        if (s.endpoints().isEmpty() && s.stores().isEmpty() && s.sources().isEmpty()) {
            return; // no lineage evidence in this repository — omit the section
        }
        sb.append("<section><h2>Data lineage</h2>")
          .append("<p class=\"note\">Deterministic, evidence-backed paths from inputs (HTTP endpoints, console sources) ")
          .append("toward data stores, package state and outputs. ")
          .append("\"Complete\" means complete within analyzed evidence — unresolved segments are listed, never hidden.</p>");

        if (!s.endpoints().isEmpty()) {
            sb.append("<h3>Endpoints</h3>");
            sb.append("<table><thead><tr><th>Endpoint</th><th>Handler</th><th>Validated</th><th>Downstream</th></tr></thead><tbody>");
            for (LineageSummary.EndpointView e : s.endpoints()) {
                LineageSummary.EndpointTrace trace = s.traces().stream()
                        .filter(t -> t.endpointId().equals(e.stableId())).findFirst().orElse(null);
                String downstream = trace == null ? "—"
                        : trace.reachesStore() ? "reaches data store (confidence " + confFmt(trace.minConfidence()) + ")"
                        : "partial — no store reached";
                sb.append("<tr><td>").append(esc(e.httpMethod() + " " + e.path()))
                  .append(e.pathUnresolved() ? " <span class=\"badge r-med\">PATH UNRESOLVED</span>" : "")
                  .append("</td><td class=\"loc\">").append(esc(e.handler())).append("</td>")
                  .append("<td>").append(e.validated() ? "yes" : "—").append("</td>")
                  .append("<td>").append(esc(downstream)).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }

        if (!s.sources().isEmpty()) {
            sb.append("<h3>Input sources &amp; output sinks</h3>");
            sb.append("<table><thead><tr><th>Name</th><th>Direction</th><th>Description</th></tr></thead><tbody>");
            for (LineageSummary.IoView v : s.sources()) {
                sb.append("<tr><td>").append(esc(v.name())).append("</td>")
                  .append("<td>").append(esc(v.direction())).append("</td>")
                  .append("<td class=\"loc\">").append(esc(v.description())).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }

        if (!s.stores().isEmpty()) {
            sb.append("<h3>Data stores</h3>");
            sb.append("<table><thead><tr><th>Table</th><th>Mapped from</th><th>Naming</th></tr></thead><tbody>");
            for (LineageSummary.StoreView v : s.stores()) {
                sb.append("<tr><td>").append(esc(v.name())).append("</td>")
                  .append("<td class=\"loc\">").append(esc(v.mappedFromEntity())).append("</td>")
                  .append("<td>").append(v.nameInferred()
                          ? riskBadge("MODERATE") + " inferred default name" : "explicit @Table").append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }

        sb.append("<h3>Representative paths</h3>");
        renderTraces(sb, s.traces());
        renderTraces(sb, s.sourceTraces());

        var c = s.coverage();
        sb.append("<h3>Lineage coverage</h3><table class=\"cov\"><tbody>");
        covRow(sb, "Endpoints detected", format(c.endpointsDetected()));
        covRow(sb, "Endpoints with a store path", format(c.endpointsWithStorePath()));
        covRow(sb, "Repositories detected", format(c.repositoriesDetected()));
        covRow(sb, "Repositories mapped to entities", format(c.repositoriesMappedToEntities()));
        covRow(sb, "Entities mapped to tables", format(c.entitiesMappedToTables()));
        covRow(sb, "Resolved lineage edges", format(c.resolvedEdges()));
        covRow(sb, "Inferred lineage edges", format(c.inferredEdges()));
        covRow(sb, "Unresolved lineage edges", format(c.unresolvedEdges()));
        covRow(sb, "Complete paths (within evidence)", format(c.completePaths()));
        covRow(sb, "Partial paths", format(c.partialPaths()));
        sb.append("</tbody></table></section>");
    }

    private void renderTraces(StringBuilder sb, java.util.List<LineageSummary.EndpointTrace> traces) {
        for (LineageSummary.EndpointTrace t : traces) {
            if (t.steps().isEmpty()) {
                continue;
            }
            sb.append("<div class=\"lineage-path\">");
            for (String step : t.steps()) {
                sb.append("<div>").append(esc(step)).append("</div>");
            }
            if (t.gapCount() > 0) {
                sb.append("<div class=\"gap\">").append(t.gapCount())
                  .append(" unresolved segment(s) — see report.json lineage gaps</div>");
            }
            sb.append("</div>");
        }
    }

    private static String confFmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private void complexitySection(StringBuilder sb, AnalysisResult a) {
        sb.append("<section><h2>Complexity hotspots</h2>");
        List<ComplexityHotspot> hs = a.complexityHotspots();
        if (hs.isEmpty()) {
            sb.append("<p class=\"empty\">No methods exceed the complexity threshold.</p></section>");
            return;
        }
        sb.append("<table><thead><tr><th>Unit</th><th>Complexity</th><th>Risk</th><th>Location</th></tr></thead><tbody>");
        for (ComplexityHotspot h : hs.stream().limit(100).toList()) {
            sb.append("<tr><td>").append(esc(h.qualifiedName())).append("</td>")
              .append("<td class=\"num\">").append(h.complexity()).append("</td>")
              .append("<td>").append(riskBadge(h.risk().name())).append("</td>")
              .append("<td class=\"loc\">").append(esc(h.location().toString())).append("</td></tr>");
        }
        sb.append("</tbody></table></section>");
    }

    private void deadCodeSection(StringBuilder sb, AnalysisResult a) {
        sb.append("<section><h2>Dead-code candidates</h2>")
          .append("<p class=\"note\">Probable-only. Each finding lists its evidence and a confidence score; review before removal.</p>");
        List<DeadCodeCandidate> dc = a.deadCode();
        if (dc.isEmpty()) {
            sb.append("<p class=\"empty\">No probable dead code found.</p></section>");
            return;
        }
        sb.append("<table><thead><tr><th>Entity</th><th>Kind</th><th>Confidence</th><th>Evidence</th><th>Location</th></tr></thead><tbody>");
        for (DeadCodeCandidate c : dc.stream().limit(200).toList()) {
            sb.append("<tr><td>").append(esc(c.qualifiedName())).append("</td>")
              .append("<td>").append(esc(c.kind().name())).append("</td>")
              .append("<td>").append(confidenceBar(c.confidence())).append("</td><td><ul class=\"ev\">");
            for (String e : c.evidence()) {
                sb.append("<li>").append(esc(e)).append("</li>");
            }
            sb.append("</ul></td><td class=\"loc\">").append(esc(c.location().toString())).append("</td></tr>");
        }
        sb.append("</tbody></table></section>");
    }

    private void dependencySection(StringBuilder sb, AnalysisResult a) {
        sb.append("<section><h2>Component coupling</h2>");
        var comps = a.dependencies().components();
        if (comps.isEmpty()) {
            sb.append("<p class=\"empty\">No package-level dependencies detected.</p>");
        } else {
            sb.append("<table><thead><tr><th>Component</th><th>Depends on</th><th>Dependents</th><th>Risk</th></tr></thead><tbody>");
            for (ComponentDependency c : comps.stream().limit(100).toList()) {
                sb.append("<tr><td>").append(esc(c.name())).append("</td>")
                  .append("<td class=\"num\">").append(c.dependencies()).append("</td>")
                  .append("<td class=\"num\">").append(c.dependents()).append("</td>")
                  .append("<td>").append(riskBadge(c.risk().name())).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        var cycles = a.dependencies().circular();
        if (!cycles.isEmpty()) {
            sb.append("<h3>Circular dependencies</h3><ul class=\"cycles\">");
            for (List<String> cycle : cycles) {
                sb.append("<li>").append(esc(String.join(" → ", cycle))).append("</li>");
            }
            sb.append("</ul>");
        }
        sb.append("</section>");
    }

    private void dataFlowSection(StringBuilder sb, AnalysisResult a) {
        sb.append("<section><h2>Data flow &amp; consumers</h2>")
          .append("<p class=\"note\">Derived from resolved dependency edges: which components are pulled from (sources) and which pull the most (consumers).</p>");
        var comps = a.dependencies().components();
        if (comps.isEmpty()) {
            sb.append("<p class=\"empty\">Not enough resolved cross-component edges yet.</p></section>");
            return;
        }
        var mostConsumed = comps.stream()
                .sorted((x, y) -> Integer.compare(y.dependents(), x.dependents()))
                .limit(5).toList();
        var biggestConsumers = comps.stream()
                .sorted((x, y) -> Integer.compare(y.dependencies(), x.dependencies()))
                .limit(5).toList();
        sb.append("<div class=\"twocol\"><div><h3>Most depended-on (data sources / hubs)</h3><ol>");
        for (ComponentDependency c : mostConsumed) {
            sb.append("<li>").append(esc(c.name())).append(" <span class=\"num\">")
              .append(c.dependents()).append(" consumers</span></li>");
        }
        sb.append("</ol></div><div><h3>Biggest consumers (pull from many)</h3><ol>");
        for (ComponentDependency c : biggestConsumers) {
            sb.append("<li>").append(esc(c.name())).append(" <span class=\"num\">")
              .append(c.dependencies()).append(" sources</span></li>");
        }
        sb.append("</ol></div></div></section>");
    }

    // ---- small render helpers ----

    private void card(StringBuilder sb, String label, String value, String sub) {
        sb.append("<div class=\"card\"><div class=\"val\">").append(esc(value)).append("</div>")
          .append("<div class=\"lab\">").append(esc(label)).append("</div>");
        if (sub != null) {
            sb.append("<div class=\"csub\">").append(esc(sub)).append("</div>");
        }
        sb.append("</div>");
    }

    private String confidenceBar(int pct) {
        String cls = pct >= 90 ? "hi" : pct >= 75 ? "mid" : "lo";
        return "<div class=\"conf\"><span class=\"" + cls + "\" style=\"width:" + pct + "%\"></span></div>"
                + "<span class=\"cnum\">" + pct + "%</span>";
    }

    private String riskBadge(String risk) {
        String cls = switch (risk) {
            case "VERY_HIGH", "HIGH" -> "r-high";
            case "MEDIUM", "MODERATE" -> "r-med";
            default -> "r-low";
        };
        return "<span class=\"badge " + cls + "\">" + esc(risk.replace('_', ' ')) + "</span>";
    }

    private static String format(long n) {
        return String.format("%,d", n);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String css() {
        return "<style>"
                + ":root{--bg:#0f1420;--panel:#171d2b;--line:#26304a;--txt:#e6ebf5;--mut:#93a0bd;--acc:#5b8def;--hi:#3fb970;--mid:#e0a23a;--lo:#d9534f;}"
                + "*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--txt);line-height:1.5}"
                + "header{padding:28px 32px;border-bottom:1px solid var(--line);background:linear-gradient(180deg,#131a29,#0f1420)}"
                + "header h1{margin:0;font-size:22px;letter-spacing:.5px}header .sub{color:var(--mut);font-size:13px;margin-top:4px}"
                + "main{max-width:1100px;margin:0 auto;padding:24px 32px}"
                + "section{margin:28px 0}h2{font-size:16px;border-left:3px solid var(--acc);padding-left:10px;margin-bottom:14px}"
                + "h3{font-size:14px;color:var(--mut);margin:18px 0 8px}"
                + ".cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px}"
                + ".card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px}"
                + ".card .val{font-size:24px;font-weight:600}.card .lab{color:var(--mut);font-size:12px;margin-top:2px}.card .csub{color:var(--mut);font-size:11px;margin-top:4px}"
                + "table{width:100%;border-collapse:collapse;background:var(--panel);border:1px solid var(--line);border-radius:10px;overflow:hidden;font-size:13px}"
                + "th,td{text-align:left;padding:9px 12px;border-bottom:1px solid var(--line);vertical-align:top}"
                + "th{color:var(--mut);font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.4px}"
                + "tr:last-child td{border-bottom:none}.num{text-align:right;font-variant-numeric:tabular-nums}.loc{color:var(--mut);font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px}"
                + "table.bars td.lbl{width:120px}table.bars td.bar{padding:9px 12px}table.bars td.bar span{display:block;height:10px;background:var(--acc);border-radius:5px}table.bars td.num{width:90px;color:var(--mut)}"
                + ".conf{display:inline-block;width:80px;height:8px;background:#22293c;border-radius:4px;overflow:hidden;vertical-align:middle}"
                + ".conf span{display:block;height:100%}.conf .hi{background:var(--hi)}.conf .mid{background:var(--mid)}.conf .lo{background:var(--lo)}.cnum{margin-left:8px;color:var(--mut);font-size:12px}"
                + ".badge{display:inline-block;padding:2px 8px;border-radius:20px;font-size:11px;font-weight:600}.r-high{background:rgba(217,83,79,.18);color:#f2938f}.r-med{background:rgba(224,162,58,.18);color:#e7c07f}.r-low{background:rgba(63,185,112,.16);color:#8fd9ae}"
                + "ul.ev{margin:0;padding-left:16px}ul.ev li{font-size:12px;color:var(--mut)}"
                + ".twocol{display:grid;grid-template-columns:1fr 1fr;gap:20px}ol{margin:0;padding-left:20px}ol li{margin:4px 0}"
                + ".cycles li{color:#f2938f}.note{color:var(--mut);font-size:13px;margin:-4px 0 12px}.empty{color:var(--mut);font-style:italic}"
                + ".banner{margin:20px 0 4px;padding:12px 16px;border-radius:10px;font-size:13px;background:rgba(224,162,58,.14);border:1px solid rgba(224,162,58,.4);color:#e7c07f}"
                + ".banner.ok{background:rgba(63,185,112,.12);border-color:rgba(63,185,112,.35);color:#8fd9ae}"
                + "table.cov{max-width:460px}table.cov td:first-child{color:var(--mut)}"
                + ".lineage-path{background:var(--panel);border:1px solid var(--line);border-radius:10px;"
                + "padding:12px 16px;margin:10px 0;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px}"
                + ".lineage-path div{padding:1px 0}.lineage-path .gap{color:#e7c07f;margin-top:6px;font-family:inherit}"
                + "footer{max-width:1100px;margin:0 auto;padding:20px 32px 40px;color:var(--mut);font-size:12px;border-top:1px solid var(--line)}"
                + "@media(max-width:640px){.twocol{grid-template-columns:1fr}}"
                + "</style>";
    }
}
