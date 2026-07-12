package com.codeatlas.parser.config;

import java.util.Locale;
import java.util.Set;

/**
 * Recognises configuration keys whose values are likely secrets (passwords,
 * tokens, credentials, connection strings) so they are never stored or reported.
 *
 * <p>This enforces the platform's security requirement that connection strings
 * and credentials must not appear in reports: the parser never persists raw
 * configuration values, and this class provides the classification and masking
 * used when a value would otherwise be surfaced.
 */
public final class ConfigSecrets {

    private static final Set<String> SECRET_MARKERS = Set.of(
            "password", "passwd", "pwd", "secret", "token", "apikey", "api-key", "api_key",
            "credential", "credentials", "privatekey", "private-key", "private_key",
            "accesskey", "access-key", "access_key", "authorization", "auth-token");

    private ConfigSecrets() {
    }

    /** Whether a configuration key's value should be treated as a secret. */
    public static boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        for (String marker : SECRET_MARKERS) {
            if (k.contains(marker)) {
                return true;
            }
        }
        // A value under a "…url"/"…uri"/"…dsn" key may be a connection string.
        return k.endsWith("dsn") || k.endsWith("connectionstring") || k.endsWith("connection-string");
    }

    /** A fixed mask; the real value is never retained. */
    public static String mask(String value) {
        return "****";
    }
}
