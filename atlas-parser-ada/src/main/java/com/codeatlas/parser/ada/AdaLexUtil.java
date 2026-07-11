package com.codeatlas.parser.ada;

/** Small text helpers shared by the Ada scanner. */
final class AdaLexUtil {

    private AdaLexUtil() {
    }

    /**
     * Removes an Ada line comment ({@code -- ...}) and blanks out string literals,
     * so keyword/identifier matching never trips over text inside strings or
     * comments. Character length is preserved where practical to keep columns sane.
     */
    static String strip(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!inString && c == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') {
                break; // rest of line is a comment
            }
            if (c == '"') {
                inString = !inString;
                sb.append(' ');
                continue;
            }
            sb.append(inString ? ' ' : c);
        }
        return sb.toString();
    }
}
