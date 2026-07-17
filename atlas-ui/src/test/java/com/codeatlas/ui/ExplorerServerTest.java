package com.codeatlas.ui;

import com.codeatlas.core.CodeAtlasPipeline;
import com.codeatlas.core.PipelineConfig;
import com.codeatlas.tools.AtlasToolApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The explorer end to end: it answers over loopback, renders the model, and holds
 * its read-only and offline guarantees.
 */
class ExplorerServerTest {

    @TempDir
    static Path work;
    static AtlasToolApi api;
    static ExplorerServer server;
    static HttpClient client;

    @BeforeAll
    static void startOnce() throws IOException {
        Path repo = work.resolve("repo");
        Files.createDirectories(repo.resolve("db"));
        Files.createDirectories(repo.resolve("src/com/example"));
        Files.writeString(repo.resolve("db/schema.sql"),
                "CREATE TABLE customer (id BIGINT PRIMARY KEY, name VARCHAR(255));\n");
        Files.writeString(repo.resolve("src/com/example/CustomerDao.java"), """
                package com.example;

                public class CustomerDao {
                    public String findName(long id) {
                        String sql = "SELECT name FROM customer WHERE id = ?";
                        return run(sql);
                    }
                    private String run(String s) { return s; }
                }
                """);
        Path index = work.resolve("index").resolve("atlas");
        Files.createDirectories(index.getParent());
        CodeAtlasPipeline.withDiscoveredParsers()
                .run(repo, PipelineConfig.builder().indexPath(index).build());

        api = AtlasToolApi.open(index);
        server = new ExplorerServer(api, "repo", 0, ExplorerServer.noGraphs("no graphs in this test"));
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
        if (api != null) {
            api.close();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void overviewRenders() throws Exception {
        HttpResponse<String> r = get("/");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("Overview"));
        assertTrue(r.body().contains("Search entities"), "the search box is always available");
    }

    @Test
    void searchFindsAnEntityAndLinksToIt() throws Exception {
        HttpResponse<String> r = get("/search?q=CustomerDao");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("com.example.CustomerDao"), r.body());
        assertTrue(r.body().contains("/entity?id="), "results link through to the entity page");
    }

    @Test
    void entityPageShowsFactsAndNavigableRelationships() throws Exception {
        HttpResponse<String> r = get("/entity?id="
                + Html.urlEncode("java:method:com.example.CustomerDao#findName(long)"));
        assertEquals(200, r.statusCode());
        String body = r.body();
        assertTrue(body.contains("findName"));
        assertTrue(body.contains("Uses"), "its outgoing edges are listed");
        // The SQL lineage edge is reachable by clicking, which is the whole point.
        assertTrue(body.contains("/entity?id=sql%3Atable%3Acustomer")
                || body.contains("sql:table:customer"), "the table it reads is navigable");
    }

    @Test
    void lineagePageTracesFromAnEntity() throws Exception {
        HttpResponse<String> r = get("/lineage?id="
                + Html.urlEncode("sql:table:customer") + "&dir=upstream");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("Lineage upstream"));
    }

    @Test
    void analysisPagesRender() throws Exception {
        assertEquals(200, get("/dead-code").statusCode());
        assertEquals(200, get("/complexity").statusCode());
        assertEquals(200, get("/unresolved").statusCode());
    }

    @Test
    void unknownEntityAndUnknownPageAreHandledNotCrashed() throws Exception {
        HttpResponse<String> missing = get("/entity?id=java:type:does.not.Exist");
        assertEquals(200, missing.statusCode());
        assertTrue(missing.body().contains("Not found"));
        assertEquals(404, get("/nope").statusCode());
    }

    // ---- guarantees ----

    @Test
    void onlyGetIsAnswered() throws Exception {
        HttpResponse<String> post = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/"))
                        .POST(HttpRequest.BodyPublishers.ofString("x=1")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode(), "a read-only view accepts no mutation");
    }

    @Test
    void boundToLoopbackOnly() {
        assertTrue(server.url().startsWith("http://127.0.0.1:"),
                "the explorer must never be reachable off the machine");
    }

    @Test
    void pagesAreSelfContainedAndScriptFree() throws Exception {
        String body = get("/").body();
        assertFalse(body.contains("<script"), "no scripts: it works in a locked-down browser");
        assertFalse(body.contains("http://") && body.replace("http://127.0.0.1", "").contains("http://"),
                "no external assets");
        assertFalse(body.contains("https://"), "no external assets");
        assertTrue(body.contains("<style"), "styles are inlined");
    }

    @Test
    void untrustedTextFromTheModelIsEscaped() throws Exception {
        // Search text is echoed back into the form; it must never become markup.
        HttpResponse<String> r = get("/search?q=" + Html.urlEncode("<script>alert(1)</script>"));
        assertEquals(200, r.statusCode());
        assertFalse(r.body().contains("<script>alert(1)</script>"), "the payload must be escaped");
        assertTrue(r.body().contains("&lt;script&gt;"), "and shown as text");
    }

    @Test
    void responsesCarryADefensiveContentSecurityPolicy() throws Exception {
        HttpResponse<String> r = get("/");
        assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
    }
}
