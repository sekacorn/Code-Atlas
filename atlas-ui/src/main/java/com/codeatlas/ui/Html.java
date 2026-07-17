package com.codeatlas.ui;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * HTML rendering for the explorer: escaping, links, styling and the page shell.
 *
 * <p><b>Everything rendered here is untrusted.</b> Entity names, file paths,
 * attribute values and SQL identifiers all come from scanned repositories, so every
 * value is escaped on the way out — for text and for attribute contexts — and URL
 * parameters are encoded. The explorer never echoes a raw value into markup.
 *
 * <p>The page is self-contained: the CSS and the script are inline, and nothing is
 * ever fetched from a CDN or any other host, so the explorer works with no network
 * at all. The script is authorised by a per-response nonce rather than a blanket
 * {@code 'unsafe-inline'}, and it is strictly progressive enhancement: search,
 * navigation and the theme switcher all work with JavaScript disabled.
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

    static String entityLink(String stableId, String label) {
        return "<a href=\"/entity?id=" + urlEncode(stableId) + "\">" + esc(label) + "</a>";
    }

    static String link(String href, String label) {
        return "<a href=\"" + esc(href) + "\">" + esc(label) + "</a>";
    }

    /** The full page shell: header, search, nav, theme switcher and content. */
    static String page(String title, String repository, String scanId, String body, View view) {
        return "<!DOCTYPE html>\n<html lang=\"en\"" + view.theme().htmlAttribute() + "><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(title) + " · Code Atlas</title>" + css() + "</head><body>"
                + "<header><div class=\"bar\">"
                + "<a class=\"brand\" href=\"/\"><span class=\"dot\"></span>Code Atlas</a>"
                + "<form class=\"search\" action=\"/search\" method=\"get\" role=\"search\">"
                + "<input id=\"q\" type=\"search\" name=\"q\" placeholder=\"Search entities…  (press / )\" "
                + "aria-label=\"Search entities\" autocomplete=\"off\">"
                + "<button type=\"submit\">Search</button>"
                + "</form>"
                + themeSwitcher(view)
                + "</div>"
                + "<nav>"
                + link("/", "Overview") + link("/search", "Search")
                + link("/dead-code", "Dead code") + link("/complexity", "Complexity")
                + link("/unresolved", "Unresolved") + link("/graph?type=architecture", "Graphs")
                + "</nav>"
                + "<p class=\"meta\">" + esc(repository) + " · scan " + esc(scanId)
                + " · read-only · offline · loopback only</p>"
                + "</header><main>" + body + "</main>"
                + "<footer>Read-only view over the persisted index. Every finding is evidence-backed; "
                + "an absent path means unknown, not absent.</footer>"
                + script(view) + "</body></html>";
    }

    /**
     * Theme switcher. These are ordinary links, so the theme changes server-side with
     * JavaScript disabled; the script upgrades them to switch instantly.
     */
    private static String themeSwitcher(View view) {
        StringBuilder b = new StringBuilder("<div class=\"themes\" role=\"group\" aria-label=\"Colour theme\">");
        for (Theme t : Theme.values()) {
            boolean on = t == view.theme();
            b.append("<a class=\"theme").append(on ? " on" : "").append("\"")
                    .append(on ? " aria-current=\"true\"" : "")
                    .append(" data-theme-set=\"").append(t.id()).append("\"")
                    .append(" href=\"/theme?set=").append(t.id())
                    .append("&next=").append(urlEncode(view.url())).append("\">")
                    .append(esc(label(t))).append("</a>");
        }
        return b.append("</div>").toString();
    }

    private static String label(Theme t) {
        return switch (t) {
            case AUTO -> "Auto";
            case LIGHT -> "Light";
            case DARK -> "Dark";
        };
    }

    static String row(String key, String valueHtml) {
        return "<tr><th>" + esc(key) + "</th><td>" + valueHtml + "</td></tr>";
    }

    static String empty(String message) {
        return "<p class=\"empty\">" + esc(message) + "</p>";
    }

    /**
     * A client-side filter box for a long list. Without JavaScript it is simply
     * hidden, and the full list below it is still fully usable.
     */
    static String filterBox(String target, String placeholder) {
        return "<input class=\"filter\" type=\"search\" data-filter=\"" + esc(target) + "\" "
                + "placeholder=\"" + esc(placeholder) + "\" aria-label=\"" + esc(placeholder)
                + "\" autocomplete=\"off\" hidden>";
    }

    // ---- inline assets ----

    private static String css() {
        return "<style>"
                // Light is the default; the media query handles Auto on a dark OS.
                + ":root{--bg:#ffffff;--surface:#f6f7f9;--raised:#ffffff;--fg:#16191d;--muted:#5c6672;"
                + "--line:#e2e6ea;--accent:#0b5cad;--accent-fg:#ffffff;--chip:#eef2f7;--chip-fg:#41505f;"
                + "--ok:#1a7f4b;--warn:#9a6700;--bad:#b3261e;--ring:#0b5cad55;--shadow:0 1px 2px #0000000f;}"
                + "@media(prefers-color-scheme:dark){:root{--bg:#0f1216;--surface:#161a20;--raised:#1b2029;"
                + "--fg:#e7eaee;--muted:#98a3b3;--line:#28303a;--accent:#79b8ff;--accent-fg:#0b1017;"
                + "--chip:#222a35;--chip-fg:#a9b6c6;--ok:#4ec98a;--warn:#e3b341;--bad:#ff7b72;"
                + "--ring:#79b8ff66;--shadow:0 1px 2px #00000040;}}"
                // Explicit choices override the OS in BOTH directions, so Light on a
                // dark OS works as surely as Dark on a light one.
                + ":root[data-theme=\"light\"]{--bg:#ffffff;--surface:#f6f7f9;--raised:#ffffff;--fg:#16191d;"
                + "--muted:#5c6672;--line:#e2e6ea;--accent:#0b5cad;--accent-fg:#ffffff;--chip:#eef2f7;"
                + "--chip-fg:#41505f;--ok:#1a7f4b;--warn:#9a6700;--bad:#b3261e;--ring:#0b5cad55;"
                + "--shadow:0 1px 2px #0000000f;}"
                + ":root[data-theme=\"dark\"]{--bg:#0f1216;--surface:#161a20;--raised:#1b2029;--fg:#e7eaee;"
                + "--muted:#98a3b3;--line:#28303a;--accent:#79b8ff;--accent-fg:#0b1017;--chip:#222a35;"
                + "--chip-fg:#a9b6c6;--ok:#4ec98a;--warn:#e3b341;--bad:#ff7b72;--ring:#79b8ff66;"
                + "--shadow:0 1px 2px #00000040;}"

                + "*{box-sizing:border-box;}"
                + "html{color-scheme:light dark;}"
                + ":root[data-theme=\"light\"]{color-scheme:light;}"
                + ":root[data-theme=\"dark\"]{color-scheme:dark;}"
                + "body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 system-ui,-apple-system,"
                + "'Segoe UI',Roboto,sans-serif;-webkit-font-smoothing:antialiased;}"

                + "header{position:sticky;top:0;z-index:5;background:var(--bg);border-bottom:1px solid var(--line);"
                + "padding:.55rem 1rem;}"
                + ".bar{display:flex;gap:.75rem;align-items:center;flex-wrap:wrap;}"
                + ".brand{display:flex;align-items:center;gap:.45rem;font-weight:700;text-decoration:none;"
                + "color:var(--fg);letter-spacing:-.01em;}"
                + ".dot{width:.6rem;height:.6rem;border-radius:50%;background:var(--accent);flex:none;}"
                + ".search{display:flex;gap:.4rem;flex:1;min-width:13rem;}"
                + ".search input{flex:1;padding:.45rem .65rem;border:1px solid var(--line);border-radius:7px;"
                + "background:var(--surface);color:var(--fg);font-size:.95rem;}"
                + ".search button{padding:.45rem .95rem;border:1px solid transparent;border-radius:7px;"
                + "background:var(--accent);color:var(--accent-fg);cursor:pointer;font-weight:600;font-size:.9rem;}"
                + ".search button:hover{filter:brightness(1.08);}"
                + ".themes{display:flex;border:1px solid var(--line);border-radius:7px;overflow:hidden;flex:none;}"
                + ".theme{padding:.35rem .6rem;font-size:.78rem;text-decoration:none;color:var(--muted);"
                + "background:var(--surface);border-right:1px solid var(--line);}"
                + ".theme:last-child{border-right:0;}"
                + ".theme:hover{color:var(--fg);}"
                + ".theme.on{background:var(--accent);color:var(--accent-fg);font-weight:600;}"
                + "nav{display:flex;gap:.9rem;flex-wrap:wrap;margin-top:.5rem;}"
                + "nav a{color:var(--muted);text-decoration:none;font-size:.85rem;padding:.1rem 0;"
                + "border-bottom:2px solid transparent;}"
                + "nav a:hover{color:var(--accent);border-bottom-color:var(--accent);}"
                + ".meta{color:var(--muted);font-size:.74rem;margin:.45rem 0 0;}"

                + "main{padding:1.1rem 1rem 2rem;max-width:72rem;margin:0 auto;}"
                + "h1{font-size:1.35rem;margin:.1rem 0 .9rem;letter-spacing:-.01em;}"
                + "h2{font-size:.95rem;margin:1.5rem 0 .5rem;color:var(--muted);text-transform:uppercase;"
                + "letter-spacing:.06em;font-weight:700;}"
                + "a{color:var(--accent);}"
                + "a:focus-visible,input:focus-visible,button:focus-visible{outline:2px solid var(--accent);"
                + "outline-offset:2px;border-radius:4px;}"
                + "input:focus{outline:none;border-color:var(--accent);box-shadow:0 0 0 3px var(--ring);}"

                + ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(9.5rem,1fr));gap:.6rem;}"
                + ".stat{background:var(--surface);border:1px solid var(--line);border-radius:10px;"
                + "padding:.7rem .8rem;box-shadow:var(--shadow);}"
                + ".stat b{display:block;font-size:1.5rem;letter-spacing:-.02em;}"
                + ".stat span{color:var(--muted);font-size:.75rem;}"
                + ".card{background:var(--surface);border:1px solid var(--line);border-radius:10px;"
                + "padding:.75rem .9rem;margin:.55rem 0;box-shadow:var(--shadow);}"

                + "table{border-collapse:collapse;width:100%;margin:.3rem 0;display:block;overflow-x:auto;}"
                + "th,td{text-align:left;padding:.4rem .65rem;border-bottom:1px solid var(--line);"
                + "vertical-align:top;font-size:.88rem;}"
                + "thead th{position:sticky;top:0;background:var(--surface);}"
                + "th{color:var(--muted);font-weight:600;white-space:nowrap;}"
                + "tbody tr:hover{background:var(--surface);}"
                + "ul{margin:.3rem 0;padding-left:1.15rem;}li{margin:.2rem 0;font-size:.88rem;}"

                + ".chip{display:inline-block;background:var(--chip);color:var(--chip-fg);border-radius:999px;"
                + "padding:.05rem .5rem;font-size:.7rem;font-weight:600;margin-right:.35rem;"
                + "letter-spacing:.02em;vertical-align:middle;}"
                + ".chip.ok{color:var(--ok);}.chip.warn{color:var(--warn);}.chip.bad{color:var(--bad);}"
                + ".loc{color:var(--muted);font-size:.78rem;font-family:ui-monospace,SFMono-Regular,Menlo,"
                + "monospace;word-break:break-all;}"
                + ".empty{color:var(--muted);font-style:italic;font-size:.9rem;}"
                + ".filter{width:100%;max-width:22rem;padding:.4rem .6rem;margin:.4rem 0;border-radius:7px;"
                + "border:1px solid var(--line);background:var(--surface);color:var(--fg);font-size:.88rem;}"
                + ".is-hidden{display:none !important;}"

                + "svg{max-width:100%;height:auto;}"
                + ".zoomable{cursor:grab;}"
                + "footer{border-top:1px solid var(--line);padding:.9rem 1rem;color:var(--muted);"
                + "font-size:.76rem;}"
                + "@media(max-width:34rem){.themes{order:3;}.search{order:2;}}"
                + "</style>";
    }

    /**
     * Progressive enhancement only. Everything below has a working no-script
     * fallback: the theme links navigate, the filter box stays hidden, and search is
     * an ordinary form submit.
     */
    private static String script(View view) {
        return "<script nonce=\"" + esc(view.nonce()) + "\">(function(){"
                // "/" focuses search, Escape leaves it. Ignored while typing elsewhere.
                + "document.addEventListener('keydown',function(e){"
                + "var t=e.target||{},tag=(t.tagName||'').toLowerCase();"
                + "if(e.key==='/'&&tag!=='input'&&tag!=='textarea'){var q=document.getElementById('q');"
                + "if(q){e.preventDefault();q.focus();q.select();}}"
                + "else if(e.key==='Escape'&&tag==='input'){t.blur();}});"
                // Instant theme switch: set the attribute now, and let the link's own
                // request persist the choice in the cookie for the next page.
                + "document.querySelectorAll('[data-theme-set]').forEach(function(a){"
                + "a.addEventListener('click',function(){var v=a.getAttribute('data-theme-set');"
                + "if(v==='auto'){document.documentElement.removeAttribute('data-theme');}"
                + "else{document.documentElement.setAttribute('data-theme',v);}});});"
                // Reveal the filter boxes and narrow rows/items as you type.
                + "document.querySelectorAll('[data-filter]').forEach(function(box){"
                + "box.hidden=false;"
                + "box.addEventListener('input',function(){"
                + "var needle=box.value.toLowerCase();"
                + "var scope=document.getElementById(box.getAttribute('data-filter'));"
                + "if(!scope){return;}"
                + "var rows=scope.querySelectorAll('tbody tr, li');"
                + "var shown=0;"
                + "rows.forEach(function(r){"
                + "var hit=r.textContent.toLowerCase().indexOf(needle)!==-1;"
                + "r.classList.toggle('is-hidden',!hit);if(hit){shown++;}});"
                + "var note=document.getElementById(box.getAttribute('data-filter')+'-count');"
                + "if(note){note.textContent=shown+' shown';}"
                + "});});"
                + "})();</script>";
    }
}
