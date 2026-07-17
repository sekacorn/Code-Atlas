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
import java.util.HashMap;
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
 *   <li><b>Offline and self-contained.</b> Inline CSS only — no scripts, no external
 *       fonts, styles or images, so it works with no network at all.</li>
 * </ul>
 *
 * <p>It also needs no JavaScript: search is a plain HTML form, so it works in a
 * locked-down browser.
 */
public final class ExplorerServer implements AutoCloseable {

    /** Renders a graph of the given type as SVG, or explains why it cannot. */
    @FunctionalInterface
    public interface GraphRenderer {
        String svg(String type);
    }

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
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                // The explorer is a read-only view; nothing here accepts a mutation.
                send(exchange, 405, "text/plain; charset=utf-8", "Only GET is supported (read-only view).");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Map<String, String> q = query(exchange.getRequestURI().getRawQuery());
            String html = route(path, q);
            send(exchange, html == null ? 404 : 200, "text/html; charset=utf-8",
                    html == null ? pages.notFound(path) : html);
        } catch (RuntimeException e) {
            // A rendering failure must not take the explorer down.
            send(exchange, 500, "text/html; charset=utf-8",
                    "<!DOCTYPE html><html><body><h1>Error</h1><p>"
                            + Html.esc(String.valueOf(e.getMessage())) + "</p></body></html>");
        }
    }

    private String route(String path, Map<String, String> q) {
        return switch (path) {
            case "/" -> pages.overview();
            case "/search" -> pages.search(q.getOrDefault("q", ""), q.getOrDefault("kind", ""),
                    q.getOrDefault("language", ""));
            case "/entity" -> pages.entity(q.getOrDefault("id", ""));
            case "/lineage" -> pages.lineage(q.getOrDefault("id", ""), q.getOrDefault("dir", "downstream"));
            case "/dead-code" -> pages.deadCode();
            case "/complexity" -> pages.complexity();
            case "/unresolved" -> pages.unresolved();
            case "/graph" -> {
                String type = q.getOrDefault("type", "architecture");
                yield pages.graph(type, graphs.svg(type));
            }
            default -> null;
        };
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

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // Defence in depth: the pages embed no external assets and run no scripts.
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'self'");
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
