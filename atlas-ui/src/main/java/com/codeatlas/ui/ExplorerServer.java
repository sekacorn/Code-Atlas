package com.codeatlas.ui;

import com.codeatlas.tools.AtlasToolApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A local, read-only explorer for a scanned repository: search the model, open an
 * entity, and click through its callers, dependencies and data lineage.
 *
 * <p>Security posture, by construction:
 * <ul>
 *   <li><b>Loopback only.</b> The socket is bound to the loopback address, so the
 *       explorer is never reachable from the network.</li>
 *   <li><b>Read-only.</b> It serves views from {@link AtlasToolApi}, which is opened
 *       with database-level read-only access and exposes no mutating operation. Only
 *       {@code GET} is answered; anything else is rejected.</li>
 *   <li><b>No file serving.</b> Every response is rendered from the index, so there
 *       is no path to traverse and no source file is ever served.</li>
 *   <li><b>Offline and self-contained.</b> The CSS and the script are inline; nothing
 *       is fetched from a CDN or any other host, so it works with no network at all.
 *       The script is authorised by a fresh per-response nonce rather than a blanket
 *       {@code 'unsafe-inline'}, so the policy stays strict.</li>
 *   <li><b>Single-user by design.</b> There is deliberately no authentication: the
 *       socket is loopback-only and the view is read-only, so there is no boundary to
 *       authenticate across. A login screen would imply protection this does not
 *       provide.</li>
 * </ul>
 *
 * <p>The JavaScript is progressive enhancement only — search, navigation and the
 * theme switcher all work with scripting disabled, so a locked-down browser degrades
 * rather than breaks.
 */
public final class ExplorerServer implements AutoCloseable {

    /** Renders a graph of the given type as SVG, or explains why it cannot. */
    @FunctionalInterface
    public interface GraphRenderer {
        String svg(String type);
    }

    private static final String THEME_COOKIE = "atlas_theme";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpServer server;
    private final Pages pages;
    private final GraphRenderer graphs;

    public ExplorerServer(AtlasToolApi api, String repository, int port, GraphRenderer graphs) {
        this.pages = new Pages(api, repository);
        this.graphs = graphs;
        try {
            // Loopback only: never bound to a routable interface.
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start the explorer on port " + port, e);
        }
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    /** The port actually bound (useful when 0 was requested). */
    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port() + "/";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        View view = new View(themeOf(exchange), currentUrl(exchange), newNonce());
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                // The explorer is a read-only view; nothing here accepts a mutation.
                send(exchange, 405, "text/plain; charset=utf-8",
                        "Only GET is supported (read-only view).", view);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Map<String, String> q = query(exchange.getRequestURI().getRawQuery());
            if ("/theme".equals(path)) {
                setTheme(exchange, q);
                return;
            }
            String html = route(path, q, view);
            send(exchange, html == null ? 404 : 200, "text/html; charset=utf-8",
                    html == null ? pages.notFound(path, view) : html, view);
        } catch (RuntimeException e) {
            // A rendering failure must not take the explorer down.
            send(exchange, 500, "text/html; charset=utf-8",
                    "<!DOCTYPE html><html><body><h1>Error</h1><p>"
                            + Html.esc(String.valueOf(e.getMessage())) + "</p></body></html>", view);
        }
    }

    private String route(String path, Map<String, String> q, View view) {
        return switch (path) {
            case "/" -> pages.overview(view);
            case "/search" -> pages.search(q.getOrDefault("q", ""), q.getOrDefault("kind", ""),
                    q.getOrDefault("language", ""), view);
            case "/entity" -> pages.entity(q.getOrDefault("id", ""), view);
            case "/lineage" -> pages.lineage(q.getOrDefault("id", ""),
                    q.getOrDefault("dir", "downstream"), view);
            case "/dead-code" -> pages.deadCode(view);
            case "/complexity" -> pages.complexity(view);
            case "/unresolved" -> pages.unresolved(view);
            case "/graph" -> {
                String type = q.getOrDefault("type", "architecture");
                yield pages.graph(type, graphs.svg(type), view);
            }
            default -> null;
        };
    }

    // ---- theme (server-side, so the switcher works without JavaScript) ----

    /** Remembers the theme in a cookie and returns to the page the visitor was on. */
    private void setTheme(HttpExchange exchange, Map<String, String> q) throws IOException {
        Theme theme = Theme.from(q.get("set"));
        // Only ever one of three fixed literals; and the cookie is re-validated
        // through Theme.from on the way back in, never trusted as text.
        exchange.getResponseHeaders().add("Set-Cookie",
                THEME_COOKIE + "=" + theme.id() + "; Path=/; Max-Age=31536000; SameSite=Strict; HttpOnly");
        exchange.getResponseHeaders().set("Location", safeNext(q.get("next")));
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    /**
     * Where a theme switch may return to: a path on this server, and nothing else.
     * An absolute or protocol-relative target would turn the explorer into an open
     * redirect, so anything unexpected falls back to the root.
     */
    static String safeNext(String raw) {
        if (raw == null || raw.isEmpty() || !raw.startsWith("/")
                || raw.startsWith("//") || raw.contains("\\") || raw.contains("\n")
                || raw.contains("\r")) {
            return "/";
        }
        return raw;
    }

    private Theme themeOf(HttpExchange exchange) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String pair : header.split(";")) {
                String[] kv = pair.trim().split("=", 2);
                if (kv.length == 2 && THEME_COOKIE.equals(kv[0].trim())) {
                    return Theme.from(kv[1]);
                }
            }
        }
        return Theme.AUTO;
    }

    private static String currentUrl(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String raw = exchange.getRequestURI().getRawQuery();
        return raw == null || raw.isBlank() ? path : path + "?" + raw;
    }

    /** A fresh nonce per response — reusing one would defeat the policy. */
    private static String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s; // a malformed escape is treated as literal text, never fatal
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body, View view)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // Layered protection: nothing may load from another host, and only this
        // response's own inline script — named by its nonce — may run.
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; script-src 'nonce-" + view.nonce() + "'; "
                        + "style-src 'unsafe-inline'; img-src data:; form-action 'self'; "
                        + "base-uri 'none'; frame-ancestors 'none'");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** A renderer that reports graphs are unavailable, for callers without a model. */
    public static GraphRenderer noGraphs(String reason) {
        return type -> "<p class=\"empty\">" + Html.esc(reason) + "</p>";
    }

    /** Wraps a renderer so a graph failure shows as a message rather than a 500. */
    public static GraphRenderer safely(Function<String, String> renderer) {
        return type -> {
            try {
                return renderer.apply(type);
            } catch (RuntimeException e) {
                return "<p class=\"empty\">Graph unavailable: " + Html.esc(String.valueOf(e.getMessage()))
                        + "</p>";
            }
        };
    }
}
