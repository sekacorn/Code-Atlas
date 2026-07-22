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

    /** A client that shows the redirect itself rather than following it. */
    private HttpClient noRedirect() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
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
    void pagesAreSelfContainedWithNothingLoadedFromAnotherHost() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("<style"), "styles are inlined");
        assertTrue(body.contains("<script"), "the enhancement script is inlined");
        // The point is not "no script" but "nothing from anywhere else": no src=, no
        // href= to a stylesheet, no CDN. It must run with no network at all.
        assertFalse(body.contains("<script src"), "the script is inline, never fetched");
        assertFalse(body.contains("rel=\"stylesheet\""), "no external stylesheet");
        assertFalse(body.contains("https://"), "no external assets");
        assertFalse(body.replace("http://127.0.0.1", "").contains("http://"), "no external assets");
    }

    @Test
    void theScriptIsAuthorisedByAPerResponseNonceNotUnsafeInline() throws Exception {
        HttpResponse<String> a = get("/");
        String csp = a.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("script-src 'nonce-"), csp);
        assertFalse(csp.contains("unsafe-inline'; script"), "scripts are not blanket-allowed");
        assertTrue(csp.contains("default-src 'none'"), csp);

        // The nonce in the header must match the one on the tag, and must not repeat.
        String nonceA = csp.replaceAll(".*script-src 'nonce-([^']+)'.*", "$1");
        assertTrue(a.body().contains("<script nonce=\"" + nonceA + "\">"), "tag carries the header's nonce");
        String nonceB = get("/").headers().firstValue("Content-Security-Policy").orElse("")
                .replaceAll(".*script-src 'nonce-([^']+)'.*", "$1");
        assertFalse(nonceA.equals(nonceB), "a nonce is never reused across responses");
    }

    // ---- theme ----

    @Test
    void themeDefaultsToAutoAndFollowsTheOperatingSystem() throws Exception {
        String body = get("/").body();
        // Auto puts no attribute on <html>, so the media query decides. (The switcher
        // links and the CSS legitimately mention data-theme, so assert on the tag.)
        assertTrue(body.contains("<html lang=\"en\">"), "Auto sets no theme attribute");
        assertTrue(body.contains("prefers-color-scheme:dark"), "and dark styling is available for it");
    }

    @Test
    void choosingAThemeSetsACookieAndReturnsToTheSamePage() throws Exception {
        HttpResponse<String> r = noRedirect().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                        + "/theme?set=dark&next=" + Html.urlEncode("/search?q=Dao"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, r.statusCode());
        assertEquals("/search?q=Dao", r.headers().firstValue("Location").orElse(""));
        String cookie = r.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(cookie.startsWith("atlas_theme=dark"), cookie);
        assertTrue(cookie.contains("SameSite=Strict"), cookie);
    }

    @Test
    void aChosenThemeIsAppliedServerSideSoItWorksWithoutJavaScript() throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/"))
                        .header("Cookie", "atlas_theme=dark").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(r.body().contains("<html lang=\"en\" data-theme=\"dark\">"),
                "the server renders the choice; no script needed");
        assertTrue(r.body().contains(":root[data-theme=\"dark\"]"), "with styling that overrides the OS");
    }

    @Test
    void anUnknownOrHostileThemeCookieFallsBackToAutoAndIsNeverEchoed() throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/"))
                        .header("Cookie", "atlas_theme=\"><script>alert(1)</script>").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertFalse(r.body().contains("<script>alert(1)"), "cookie text never reaches the page");
        assertTrue(r.body().contains("<html lang=\"en\">"), "an unrecognised theme falls back to Auto");
    }

    @Test
    void themeReturnTargetCannotBeUsedAsAnOpenRedirect() {
        assertEquals("/", ExplorerServer.safeNext("https://evil.example/x"), "absolute URL refused");
        assertEquals("/", ExplorerServer.safeNext("//evil.example/x"), "protocol-relative refused");
        assertEquals("/", ExplorerServer.safeNext("/x\r\nSet-Cookie: a=b"), "header injection refused");
        assertEquals("/", ExplorerServer.safeNext(null));
        assertEquals("/search?q=a", ExplorerServer.safeNext("/search?q=a"), "a local path is allowed");
    }

    @Test
    void hostHeaderAcceptsOnlyLoopbackNames() {
        assertTrue(ExplorerServer.allowedHost("127.0.0.1:8138"));
        assertTrue(ExplorerServer.allowedHost("localhost"));
        assertTrue(ExplorerServer.allowedHost("[::1]:8138"));
        assertFalse(ExplorerServer.allowedHost("example.test:8138"));
        assertFalse(ExplorerServer.allowedHost("127.0.0.1.example.test"));
        assertFalse(ExplorerServer.allowedHost("localhost:bad"));
        assertFalse(ExplorerServer.allowedHost(null));
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
