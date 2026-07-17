package com.codeatlas.ui;

/**
 * Per-request rendering context.
 *
 * <p>Held per request rather than on the renderer because the HTTP server handles
 * requests concurrently — a shared field would leak one visitor's theme or nonce
 * into another's page.
 *
 * @param theme the resolved colour theme (never raw cookie text)
 * @param url   the current path and query, so the theme switcher can return here
 * @param nonce a fresh random value authorising this response's inline script under
 *              the Content-Security-Policy; it must never be reused across responses
 */
record View(Theme theme, String url, String nonce) {
}
