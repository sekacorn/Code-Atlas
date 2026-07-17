package com.codeatlas.ui;

import com.codeatlas.analysis.ComplexityHotspot;
import com.codeatlas.analysis.DeadCodeCandidate;
import com.codeatlas.analysis.lineage.LineageQuery;
import com.codeatlas.analysis.lineage.LineageResult;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.ToolResult;
import com.codeatlas.tools.Views;

import java.util.List;
import java.util.Map;

import static com.codeatlas.ui.Html.entityLink;
import static com.codeatlas.ui.Html.esc;
import static com.codeatlas.ui.Html.row;
import static com.codeatlas.ui.Html.urlEncode;

/**
 * The explorer's pages, rendered from the read-only tool API.
 *
 * <p>Every page is a view of facts the index already holds — the explorer computes
 * no analysis of its own and can write nothing. Absences are stated ("no callers
 * within analyzed evidence") rather than left as an empty space that could be read
 * as "none exist".
 */
final class Pages {

    private static final int LIMIT = 100;

    private final AtlasToolApi api;
    private final String repository;

    Pages(AtlasToolApi api, String repository) {
        this.api = api;
        this.repository = repository;
    }

    private String page(String title, String body) {
        return Html.page(title, repository, api.scanId(), body);
    }

    // ---- overview ----

    String overview() {
        Views.RepositorySummaryView s = api.getRepositorySummary().value();
        StringBuilder b = new StringBuilder("<h1>Overview</h1><div class=\"grid\">");
        b.append(stat(String.valueOf(s.totalFiles()), "files"));
        b.append(stat(String.format("%,d", s.codeLines()), "lines of code"));
        b.append(stat(String.valueOf(s.endpoints().size()), "endpoints"));
        b.append(stat(String.valueOf(s.dataStores().size()), "data stores"));
        b.append(stat(String.valueOf(s.deadCodeCandidates()), "dead-code candidates"));
        b.append(stat(String.valueOf(s.unresolvedReferences()), "unresolved refs"));
        b.append("</div>");

        b.append("<h2>Languages</h2><table>");
        s.filesByLanguage().forEach((lang, n) -> b.append(row(lang, String.valueOf(n))));
        b.append("</table>");

        b.append("<h2>Entities</h2><table>");
        s.entityCounts().forEach((kind, n) -> b.append("<tr><th>")
                .append("<a href=\"/search?kind=").append(urlEncode(kind)).append("\">")
                .append(esc(kind)).append("</a></th><td>").append(n).append("</td></tr>"));
        b.append("</table>");

        if (!s.endpoints().isEmpty()) {
            b.append("<h2>Endpoints</h2><ul>");
            s.endpoints().forEach(e -> b.append("<li>").append(esc(e)).append("</li>"));
            b.append("</ul>");
        }
        b.append("<p class=\"empty\">Type in the search box above to find any entity, then click "
                + "through its callers, dependencies and data lineage.</p>");
        return page("Overview", b.toString());
    }

    private String stat(String value, String label) {
        return "<div class=\"stat\"><b>" + esc(value) + "</b><span>" + esc(label) + "</span></div>";
    }

    // ---- search ----

    String search(String q, String kind, String language) {
        StringBuilder b = new StringBuilder("<h1>Search</h1>");
        b.append("<form class=\"card\" action=\"/search\" method=\"get\">")
                .append("<input type=\"search\" name=\"q\" value=\"").append(esc(q))
                .append("\" placeholder=\"name or qualified name\" aria-label=\"Text\"> ")
                .append("<input type=\"text\" name=\"kind\" value=\"").append(esc(kind))
                .append("\" placeholder=\"kind (e.g. CLASS)\" aria-label=\"Kind\"> ")
                .append("<input type=\"text\" name=\"language\" value=\"").append(esc(language))
                .append("\" placeholder=\"language (java/ada/sql)\" aria-label=\"Language\"> ")
                .append("<button type=\"submit\">Search</button></form>");

        if (q.isBlank() && kind.isBlank() && language.isBlank()) {
            b.append(Html.empty("Enter a name, or filter by kind or language."));
            return page("Search", b.toString());
        }
        ToolResult<List<Views.EntityView>> r = api.searchEntities(q,
                kind.isBlank() ? null : kind, language.isBlank() ? null : language, LIMIT);
        List<Views.EntityView> hits = r.value();
        if (hits.isEmpty()) {
            b.append(Html.empty("Nothing matched within this scan."));
            return page("Search", b.toString());
        }
        b.append("<p class=\"meta\">").append(r.totalMatches()).append(" match(es)")
                .append(r.truncated() ? ", showing the first " + LIMIT : "").append("</p>");
        b.append("<table><tr><th>Kind</th><th>Name</th><th>Where</th></tr>");
        for (Views.EntityView e : hits) {
            b.append("<tr><td><span class=\"chip\">").append(esc(e.kind())).append("</span></td>")
                    .append("<td>").append(entityLink(e.stableId(), e.qualifiedName())).append("</td>")
                    .append("<td class=\"loc\">").append(esc(e.location())).append("</td></tr>");
        }
        b.append("</table>");
        return page("Search", b.toString());
    }

    // ---- entity ----

    String entity(String id) {
        Views.EntityView e = api.getEntity(id).value().orElse(null);
        if (e == null) {
            return page("Not found", "<h1>Not found</h1>"
                    + Html.empty("No entity with stable id '" + id + "' in this scan."));
        }
        StringBuilder b = new StringBuilder("<h1><span class=\"chip\">" + esc(e.kind()) + "</span> "
                + esc(e.name()) + "</h1>");
        b.append("<table>");
        b.append(row("Qualified name", esc(e.qualifiedName())));
        b.append(row("Stable id", "<span class=\"loc\">" + esc(e.stableId()) + "</span>"));
        b.append(row("Language", esc(e.language())));
        b.append(row("Location", "<span class=\"loc\">"
                + esc(e.location().isBlank() ? "(no source location)" : e.location()) + "</span>"));
        b.append("</table>");

        if (!e.attributes().isEmpty()) {
            b.append("<h2>Attributes</h2><table>");
            for (Map.Entry<String, String> a : e.attributes().entrySet()) {
                b.append(row(a.getKey(), esc(a.getValue())));
            }
            b.append("</table>");
        }

        b.append(neighbours("Callers", api.getCallers(id, LIMIT)));
        b.append(neighbours("Callees", api.getCallees(id, LIMIT)));
        b.append(neighbours("Used by", api.getDependents(id, LIMIT)));
        b.append(neighbours("Uses", api.getDependencies(id, LIMIT)));
        b.append(neighbours("Members", api.getMembers(id, LIMIT)));

        ToolResult<List<Views.NeighborView>> build = api.getBuildMembership(id);
        b.append("<h2>Build module</h2>");
        if (build.value().isEmpty()) {
            b.append(Html.empty(build.note().isBlank() ? "Not inside any build module." : build.note()));
        } else {
            b.append(neighbourList(build.value()));
        }

        ToolResult<List<Views.NeighborView>> config = api.getConfigurationReferences(id, LIMIT);
        if (!config.value().isEmpty()) {
            b.append("<h2>Configuration</h2>").append(neighbourList(config.value()));
        }

        b.append("<h2>Data lineage</h2><p>")
                .append(Html.link("/lineage?id=" + urlEncode(id) + "&dir=upstream", "Trace upstream"))
                .append(" · ")
                .append(Html.link("/lineage?id=" + urlEncode(id) + "&dir=downstream", "Trace downstream"))
                .append("</p>");
        return page(e.name(), b.toString());
    }

    private String neighbours(String title, ToolResult<List<Views.NeighborView>> r) {
        StringBuilder b = new StringBuilder("<h2>" + esc(title) + "</h2>");
        if (r.value().isEmpty()) {
            return b.append(Html.empty("None within analyzed evidence.")).toString();
        }
        return b.append(neighbourList(r.value())).toString();
    }

    private String neighbourList(List<Views.NeighborView> ns) {
        StringBuilder b = new StringBuilder("<ul>");
        for (Views.NeighborView n : ns) {
            b.append("<li><span class=\"chip\">").append(esc(n.edge().kind())).append("</span>")
                    .append(entityLink(n.entity().stableId(), n.entity().qualifiedName()));
            if (!n.edge().evidence().isBlank()) {
                b.append(" <span class=\"loc\">").append(esc(n.edge().evidence())).append("</span>");
            }
            if (n.edge().inferred()) {
                b.append(" <span class=\"chip\">inferred</span>");
            }
            if (n.edge().ambiguous()) {
                b.append(" <span class=\"chip\">ambiguous</span>");
            }
            b.append("</li>");
        }
        return b.append("</ul>").toString();
    }

    // ---- lineage ----

    String lineage(String id, String direction) {
        Views.EntityView e = api.getEntity(id).value().orElse(null);
        if (e == null) {
            return page("Not found", "<h1>Not found</h1>" + Html.empty("No entity with id '" + id + "'."));
        }
        LineageQuery.Direction dir = "upstream".equalsIgnoreCase(direction)
                ? LineageQuery.Direction.UPSTREAM : LineageQuery.Direction.DOWNSTREAM;
        LineageResult r = api.traceDataLineage(new LineageQuery(id, dir, 8, true, 0.40)).value();

        StringBuilder b = new StringBuilder("<h1>Lineage " + esc(dir.name().toLowerCase())
                + " of " + esc(e.name()) + "</h1>");
        b.append("<p>").append(entityLink(id, e.qualifiedName())).append(" · ")
                .append(Html.link("/lineage?id=" + urlEncode(id) + "&dir="
                        + (dir == LineageQuery.Direction.UPSTREAM ? "downstream" : "upstream"),
                        "switch direction")).append("</p>");

        if (r.paths().isEmpty()) {
            b.append(Html.empty("No connected path within analyzed evidence."));
        }
        int n = 1;
        for (LineageResult.Path p : r.paths()) {
            b.append("<div class=\"card\"><b>Path ").append(n++).append("</b> <span class=\"chip\">")
                    .append(String.format("confidence %.2f", p.minConfidence())).append("</span><ol>");
            for (LineageResult.Edge edge : p.edges()) {
                b.append("<li>").append(entityLink(edge.fromId(), shortId(edge.fromId())))
                        .append(" <span class=\"chip\">").append(esc(edge.kind())).append("</span> ")
                        .append(entityLink(edge.toId(), shortId(edge.toId())))
                        .append(" <span class=\"loc\">").append(esc(edge.location())).append("</span>");
                if (edge.inferred()) {
                    b.append(" <span class=\"chip\">inferred</span>");
                }
                b.append("</li>");
            }
            b.append("</ol></div>");
        }
        b.append("<h2>Unresolved segments</h2>");
        if (r.gaps().isEmpty()) {
            b.append(Html.empty("None found within the selected scope."));
        } else {
            b.append("<ul>");
            r.gaps().forEach(g -> b.append("<li><span class=\"chip\">").append(esc(g.kind()))
                    .append("</span>").append(esc(g.description())).append("</li>"));
            b.append("</ul>");
        }
        return page("Lineage", b.toString());
    }

    // ---- analysis lists ----

    String deadCode() {
        List<DeadCodeCandidate> all = api.findDeadCodeCandidates(LIMIT).value();
        StringBuilder b = new StringBuilder("<h1>Dead-code candidates</h1>");
        b.append("<p class=\"meta\">Probable, never certain — each is a candidate for review, "
                + "not proof. Reflection, DI and dynamic invocation are blind spots.</p>");
        if (all.isEmpty()) {
            return page("Dead code", b.append(Html.empty("None reported at the default threshold.")).toString());
        }
        b.append("<table><tr><th>Confidence</th><th>Entity</th><th>Where</th></tr>");
        for (DeadCodeCandidate c : all) {
            b.append("<tr><td>").append(c.confidence()).append("%</td><td>")
                    .append(entityLink(c.stableId(), c.qualifiedName())).append("</td>")
                    .append("<td class=\"loc\">").append(esc(String.valueOf(c.location())))
                    .append("</td></tr>");
        }
        return page("Dead code", b.append("</table>").toString());
    }

    String complexity() {
        List<ComplexityHotspot> all = api.getComplexity(LIMIT).value();
        StringBuilder b = new StringBuilder("<h1>Complexity hotspots</h1>");
        if (all.isEmpty()) {
            return page("Complexity", b.append(Html.empty("None above the default threshold.")).toString());
        }
        b.append("<table><tr><th>Complexity</th><th>Entity</th><th>Where</th></tr>");
        for (ComplexityHotspot h : all) {
            b.append("<tr><td>").append(h.complexity()).append("</td><td>").append(esc(h.qualifiedName()))
                    .append("</td><td class=\"loc\">").append(esc(String.valueOf(h.location())))
                    .append("</td></tr>");
        }
        return page("Complexity", b.append("</table>").toString());
    }

    String unresolved() {
        List<Views.UnresolvedReference> all = api.getUnresolvedReferences(LIMIT).value();
        StringBuilder b = new StringBuilder("<h1>Unresolved references</h1>");
        b.append("<p class=\"meta\">References the linker could not connect to a target. These are "
                + "analysis gaps — treat an absent path as unknown, not absent.</p>");
        if (all.isEmpty()) {
            return page("Unresolved", b.append(Html.empty("Everything resolved in this scan.")).toString());
        }
        b.append("<table><tr><th>From</th><th>Target</th><th>Kind</th><th>Where</th></tr>");
        for (Views.UnresolvedReference u : all) {
            b.append("<tr><td>").append(entityLink(u.fromId(), shortId(u.fromId()))).append("</td>")
                    .append("<td>").append(esc(u.targetName())).append("</td>")
                    .append("<td><span class=\"chip\">").append(esc(u.kind())).append("</span></td>")
                    .append("<td class=\"loc\">").append(esc(u.location())).append("</td></tr>");
        }
        return page("Unresolved", b.append("</table>").toString());
    }

    String graph(String type, String svg) {
        String body = "<h1>Graph: " + esc(type) + "</h1><p>"
                + Html.link("/graph?type=dependency", "dependency") + " · "
                + Html.link("/graph?type=call", "call") + " · "
                + Html.link("/graph?type=dead-code", "dead-code") + " · "
                + Html.link("/graph?type=architecture", "architecture") + "</p>"
                + "<div class=\"card\">" + svg + "</div>";
        return page("Graph", body);
    }

    String notFound(String path) {
        return page("Not found", "<h1>Not found</h1>" + Html.empty("No page at '" + path + "'."));
    }

    private static String shortId(String id) {
        int c = id.lastIndexOf(':');
        return c >= 0 ? id.substring(c + 1) : id;
    }
}
