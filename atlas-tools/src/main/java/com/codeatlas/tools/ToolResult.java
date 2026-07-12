package com.codeatlas.tools;

/**
 * Uniform envelope for every agent-tool response.
 *
 * <p>Agents must never mistake an empty answer for a fact or an unsupported
 * capability for an empty answer, so the envelope makes all three states
 * explicit: {@code supported=false} means the platform cannot answer this kind
 * of question yet; an empty {@code value} with {@code supported=true} means the
 * question was answered and the answer is "nothing"; {@code truncated} means the
 * answer was cut by a limit and {@code totalMatches} tells how much exists.
 *
 * @param scanId       content-derived id of the completed scan that was queried
 * @param supported    whether the operation is implemented for this repository
 * @param value        the operation's result (empty/null when unsupported)
 * @param truncated    whether limits cut the result short
 * @param totalMatches total matches before limiting (-1 when not applicable)
 * @param note         honest caveat or explanation, empty when none
 */
public record ToolResult<T>(String scanId,
                            boolean supported,
                            T value,
                            boolean truncated,
                            int totalMatches,
                            String note) {

    static <T> ToolResult<T> of(String scanId, T value) {
        return new ToolResult<>(scanId, true, value, false, -1, "");
    }

    static <T> ToolResult<T> of(String scanId, T value, boolean truncated, int totalMatches) {
        return new ToolResult<>(scanId, true, value, truncated, totalMatches, "");
    }

    static <T> ToolResult<T> of(String scanId, T value, boolean truncated, int totalMatches, String note) {
        return new ToolResult<>(scanId, true, value, truncated, totalMatches, note);
    }

    static <T> ToolResult<T> unsupported(String scanId, T emptyValue, String reason) {
        return new ToolResult<>(scanId, false, emptyValue, false, -1, reason);
    }
}
