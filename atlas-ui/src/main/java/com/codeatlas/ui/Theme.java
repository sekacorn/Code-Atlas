package com.codeatlas.ui;

import java.util.Locale;

/**
 * The explorer's colour theme.
 *
 * <p>{@link #AUTO} follows the operating system; {@link #LIGHT} and {@link #DARK}
 * override it. The choice is remembered in a cookie and applied server-side, so the
 * switcher needs no JavaScript.
 *
 * <p>The cookie is user-controlled input and ends up inside an HTML attribute, so a
 * value is only ever accepted through {@link #from}, which whitelists the three
 * known names and falls back to {@code AUTO}. Nothing from the cookie is echoed
 * into the page.
 */
enum Theme {
    AUTO,
    LIGHT,
    DARK;

    /** The theme named by untrusted text, or {@link #AUTO} for anything unrecognised. */
    static Theme from(String raw) {
        if (raw == null) {
            return AUTO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "light" -> LIGHT;
            case "dark" -> DARK;
            default -> AUTO;
        };
    }

    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The value for {@code <html data-theme>}: empty for {@link #AUTO}, so the
     * media query decides. Always one of three fixed literals — never user text.
     */
    String htmlAttribute() {
        return this == AUTO ? "" : " data-theme=\"" + id() + "\"";
    }
}
