package com.codeatlas.graph;

import com.codeatlas.core.CodeAtlasPipeline;
import com.codeatlas.core.LineageFixtures;
import com.codeatlas.core.PipelineConfig;
import com.codeatlas.core.PipelineResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graph exports over the customer fixture: structural correctness, determinism,
 * self-containment, and honest handling of empty graphs.
 */
class GraphExporterTest {

    private final GraphExporter exporter = new GraphExporter();

    private PipelineResult scan(Path repo) throws IOException {
        LineageFixtures.writeCustomerApp(repo);
        return CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults());
    }

    @Test
    void architectureGraphLayersRolesInOrder(@TempDir Path repo) throws IOException {
        PipelineResult r = scan(repo);
        GraphModel g = new GraphBuilder(r.model(), r.analysis()).build(GraphType.ARCHITECTURE);

        var controller = node(g, "java:type:com.example.customer.CustomerController");
        var service = node(g, "java:type:com.example.customer.CustomerService");
        var repoNode = node(g, "java:type:com.example.customer.CustomerRepository");
        var table = node(g, "sql:table:customer");
        var endpoint = node(g, "java:endpoint:POST:/customers");

        assertTrue(endpoint.layer() < controller.layer(), "endpoint before controller");
        assertTrue(controller.layer() < service.layer(), "controller before service");
        assertTrue(service.layer() < repoNode.layer(), "service before repository");
        assertTrue(repoNode.layer() < table.layer(), "repository before table");
        assertEquals(GraphModel.Category.CONTROLLER, controller.category());
        assertEquals(GraphModel.Category.TABLE, table.category());
    }

    @Test
    void deadCodeGraphMarksProbableDeadNodes(@TempDir Path repo) throws IOException {
        PipelineResult r = scan(repo);
        GraphModel g = new GraphBuilder(r.model(), r.analysis()).build(GraphType.DEAD_CODE);
        assertTrue(g.nodes().stream().anyMatch(n -> n.category() == GraphModel.Category.ACTIVE),
                "active nodes are present");
        // Every node is classified strictly active or dead.
        assertTrue(g.nodes().stream().allMatch(n ->
                n.category() == GraphModel.Category.ACTIVE || n.category() == GraphModel.Category.DEAD));
    }

    @Test
    void callGraphContainsResolvedCalls(@TempDir Path repo) throws IOException {
        PipelineResult r = scan(repo);
        GraphModel g = new GraphBuilder(r.model(), r.analysis()).build(GraphType.CALL);
        assertTrue(g.edges().stream().anyMatch(e ->
                        e.from().contains("CustomerController#createCustomer")
                                && e.to().contains("CustomerService#createCustomer")),
                "the controller→service call is an edge");
    }

    @Test
    void dotAndSvgAreDeterministicAndSelfContained(@TempDir Path repo) throws IOException {
        PipelineResult r = scan(repo);
        for (GraphType type : GraphType.values()) {
            String dot1 = exporter.export(r.model(), r.analysis(), type, GraphExporter.Format.DOT);
            String dot2 = exporter.export(r.model(), r.analysis(), type, GraphExporter.Format.DOT);
            assertEquals(dot1, dot2, "DOT must be deterministic for " + type);
            assertTrue(dot1.startsWith("digraph CodeAtlas"), type + " DOT header");

            String svg1 = exporter.export(r.model(), r.analysis(), type, GraphExporter.Format.SVG);
            String svg2 = exporter.export(r.model(), r.analysis(), type, GraphExporter.Format.SVG);
            assertEquals(svg1, svg2, "SVG must be deterministic for " + type);
            assertTrue(svg1.contains("<svg"), type + " is SVG");
            // Self-contained: no external references.
            assertFalse(svg1.contains("http://") && svg1.replace("http://www.w3.org/2000/svg", "").contains("http://"),
                    "no external asset URLs beyond the SVG namespace");
            assertFalse(svg1.contains("<script"), "no scripts in the SVG");
        }
    }

    @Test
    void emptyGraphIsHandledHonestly(@TempDir Path repo) throws IOException {
        // A repo with a single trivial class: the dependency (package-coupling) graph
        // has no cross-package edges, and the architecture graph has no roles.
        java.nio.file.Files.createDirectories(repo.resolve("src"));
        java.nio.file.Files.writeString(repo.resolve("src/Solo.java"), "public class Solo { void a(){} }");
        PipelineResult r = CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults());
        GraphModel arch = new GraphBuilder(r.model(), r.analysis()).build(GraphType.ARCHITECTURE);
        assertTrue(arch.nodes().isEmpty());
        String svg = new SvgWriter().render(arch);
        assertTrue(svg.contains("No nodes for this graph"), "empty graph says so");
    }

    @Test
    void dotQuotingContainsUntrustedIdentifiersAndControlCharacters() {
        assertEquals("\"node\\\\\\\"; injected\\nnext\\tvalue\"",
                DotWriter.quote("node\\\"; injected\nnext\tvalue"));
    }

    private static GraphModel.Node node(GraphModel g, String id) {
        return g.nodes().stream().filter(n -> n.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("missing node " + id + " in " + g.title()));
    }
}
