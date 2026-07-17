package com.codeatlas.ui;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * HTML rendering for the explorer: escaping, links and the page shell.
 *
 * <p><b>Everything rendered here is untrusted.</b> Entity names, file paths,
 * attribute values and SQL identifiers all come from scanned repositories, so every
 * value is escaped on the way out — for text and for attribute contexts — and URL
 * parameters are encoded. The explorer never echoes a raw value into markup.
 *
 * <p>The page is self-contained: inline CSS only, no scripts, no external fonts,
 * styles or images. It renders on an offline workstation with no network at all.
 */
final class Html {

    private Html() {
    }

    /** Escapes text for element content and quoted attribute values alike. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** A link to an entity's page, labelled with escaped text. */
    static String entityLink(String stableId, String label) {
        return "<a href=\"/entity?id=" + urlEncode(stableId) + "\">" + esc(label) + "</a>";
    }

    static String link(String href, String label) {
        return "<a href=\"" + esc(href) + "\">" + esc(label) + "</a>";
    }

    /** The full page shell: header, always-present search box, nav and content. */
    static String page(String title, String repository, String scanId, String body) {
        return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(title) + " · Code Atlas</title>" + css() + "</head><body>"
                + "<header>"
                + "<div class=\"bar\">"
                + "<a class=\"brand\" href=\"/\">Code Atlas</a>"
                + "<form class=\"search\" action=\"/search\" method=\"get\" role=\"search\">"
                + "<input type=\"search\" name=\"q\" placeholder=\"Search entities…\" "
                + "aria-label=\"Search entities\" autofocus>"
                + "<button type=\"submit\">Search</button>"
                + "</form>"
                + "</div>"
                + "<nav>"
                + navLink("/", "Overview") + navLink("/search", "Search")
                + navLink("/dead-code", "Dead code") + navLink("/complexity", "Complexity")
                + navLink("/unresolved", "Unresolved") + navLink("/graph?type=architecture", "Graphs")
                + "</nav>"
                + "<p class=\"meta\">" + esc(repository) + " · scan " + esc(scanId)
                + " · read-only, offline</p>"
                + "</header><main>" + body + "</main>"
                + "<footer>Read-only view over the persisted index. Every finding is evidence-backed; "
                + "absent paths mean unknown, not absent.</footer>"
                + "</body></html>";
    }

    private static String navLink(String href, String label) {
        return "<a href=\"" + esc(href) + "\">" + esc(label) + "</a>";
    }

    /** A definition row for the key/value tables used on entity pages. */
    static String row(String key, String valueHtml) {
        return "<tr><th>" + esc(key) + "</th><td>" + valueHtml + "</td></tr>";
    }

    static String empty(String message) {
        return "<p class=\"empty\">" + esc(message) + "</p>";
    }

    private static String css() {
        return "<style>"
                + ":root{--bg:#fff;--fg:#1a1a1a;--muted:#666;--line:#e3e3e6;--card:#f7f7f8;"
                + "--accent:#0b5cad;--chip:#eef2f7;}"
                + "@media(prefers-color-scheme:dark){:root{--bg:#15171c;--fg:#e7e7ea;--muted:#98a0ab;"
                + "--line:#2c3038;--card:#1c1f26;--accent:#6fb3ff;--chip:#232833;}}"
                + "*{box-sizing:border-box;}"
                + "body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 system-ui,"
                + "-apple-system,Segoe UI,Roboto,sans-serif;}"
                + "header{border-bottom:1px solid var(--line);padding:.6rem 1rem;position:sticky;top:0;"
                + "background:var(--bg);}"
                + ".bar{display:flex;gap:1rem;align-items:center;flex-wrap:wrap;}"
                + ".brand{font-weight:700;text-decoration:none;color:var(--fg);font-size:1.05rem;}"
                + ".search{display:flex;gap:.4rem;flex:1;min-width:14rem;}"
                + ".search input{flex:1;padding:.45rem .6rem;border:1px solid var(--line);border-radius:6px;"
                + "background:var(--card);color:var(--fg);font-size:.95rem;}"
                + ".search button{padding:.45rem .9rem;border:1px solid var(--line);border-radius:6px;"
                + "background:var(--accent);color:#fff;cursor:pointer;font-size:.9rem;}"
                + "nav{display:flex;gap:.9rem;flex-wrap:wrap;margin-top:.5rem;}"
                + "nav a{color:var(--accent);text-decoration:none;font-size:.9rem;}"
                + "nav a:hover{text-decoration:underline;}"
                + ".meta{color:var(--muted);font-size:.78rem;margin:.4rem 0 0;}"
                + "main{padding:1rem;max-width:70rem;margin:0 auto;}"
                + "h1{font-size:1.3rem;margin:.2rem 0 .8rem;}"
                + "h2{font-size:1rem;margin:1.4rem 0 .4rem;border-bottom:1px solid var(--line);"
                + "padding-bottom:.25rem;}"
                + "a{color:var(--accent);}"
                + "table{border-collapse:collapse;width:100%;margin:.3rem 0;display:block;overflow-x:auto;}"
                + "th,td{text-align:left;padding:.35rem .6rem;border-bottom:1px solid var(--line);"
                + "vertical-align:top;font-size:.9rem;}"
                + "th{color:var(--muted);font-weight:600;white-space:nowrap;}"
                + "ul{margin:.3rem 0;padding-left:1.2rem;}li{margin:.15rem 0;font-size:.9rem;}"
                + ".chip{display:inline-block;background:var(--chip);border:1px solid var(--line);"
                + "border-radius:10px;padding:.02rem .5rem;font-size:.72rem;color:var(--muted);"
                + "margin-right:.3rem;}"
                + ".loc{color:var(--muted);font-size:.8rem;font-family:ui-monospace,monospace;}"
                + ".empty{color:var(--muted);font-style:italic;}"
                + ".card{background:var(--card);border:1px solid var(--line);border-radius:8px;"
                + "padding:.7rem .9rem;margin:.5rem 0;}"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(11rem,1fr));gap:.6rem;}"
                + ".stat{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:.6rem;}"
                + ".stat b{display:block;font-size:1.35rem;}"
                + ".stat span{color:var(--muted);font-size:.78rem;}"
                + "footer{border-top:1px solid var(--line);padding:.8rem 1rem;color:var(--muted);"
                + "font-size:.78rem;margin-top:2rem;}"
                + "svg{max-width:100%;height:auto;}"
                + "</style>";
    }
}
