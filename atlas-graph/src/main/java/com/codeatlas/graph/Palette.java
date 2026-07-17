package com.codeatlas.graph;

/**
 * The colour scheme for exported graphs, shared by {@link DotWriter} and
 * {@link SvgWriter} so both renderers say the same thing in the same way.
 *
 * <p>Hues come from the Okabe-Ito qualitative palette, whose members stay separable
 * under dichromacy. They replace a red/amber/green ramp — the one pairing that
 * roughly 8% of men and 0.5% of women cannot reliably tell apart, and the pairing
 * these graphs most needed: a reader who cannot separate red from green could not
 * tell a dead node from a live one.
 *
 * <p>Fills are not eyeballed. Each is its hue mixed toward white at a ratio chosen by
 * search, maximising the worst-case CIE76 separation after simulating each dichromacy
 * while keeping the label above WCAG AAA contrast. {@code PaletteTest} re-derives that
 * guarantee on every build.
 *
 * <h2>Two standards, because there are two situations</h2>
 * Where colour <em>is</em> the meaning — dead vs live, the risk ramp — nothing else on
 * the node carries it, so those sets hold a strict bar: separable under protanopia,
 * deuteranopia <em>and</em> tritanopia, plus a redundant {@link Emphasis} on every
 * opposed pair, so they survive greyscale printing and monochromacy too.
 *
 * <p>The architecture graph is different: eight categories cannot be made mutually
 * separable under all three dichromacies at once — each one flattens colour space to
 * roughly two dimensions, and eight points will not stay apart in all of them (SERVICE
 * and TABLE collapse to ΔE 0.5 under tritanopia however they are placed). That set is
 * therefore held to the common dichromacies, which is honest rather than lax: every
 * architecture node is labelled with its role, so hue groups nodes while the label
 * states what they are. No information lives in the colour alone.
 *
 * <h2>Why this is the only palette</h2>
 * Not an opt-in accessibility mode. Exported SVG and DOT files outlive the session that
 * made them and reach reviewers nobody surveyed first, so the artifact must be readable
 * without knowing who is reading it — and none of this costs a full-colour-vision reader
 * anything.
 */
public final class Palette {

    private Palette() {
    }

    // Okabe-Ito, used for borders and as the hue identity of each category.
    private static final String BLUE = "#0072b2";
    private static final String SKY = "#56b4e9";
    private static final String GREEN = "#009e73";
    private static final String ORANGE = "#e69f00";
    private static final String VERMILLION = "#d55e00";
    private static final String PURPLE = "#cc79a7";
    private static final String GREY = "#666666";
    /**
     * Okabe-Ito's eighth member. Its yellow is too light to read as a border on white,
     * and a darkened yellow is no substitute: drained of red, vermillion lands on dark
     * gold, and the two borders merged under protanopia. Black is separable under every
     * vision; the yellow identity survives in the fill.
     */
    private static final String BLACK = "#000000";

    // ---- architecture graph: layer roles, held to the common dichromacies ----
    private static final String BLUE_FILL = "#71b0d4";
    private static final String SKY_FILL = "#c4e5f7";
    private static final String PURPLE_FILL = "#e2b3cd";
    private static final String YELLOW_FILL = "#f2e85f";
    private static final String GREEN_FILL = "#53bda0";
    private static final String VERMILLION_FILL = "#edb991";
    private static final String ORANGE_FILL = "#e7a40e";

    // ---- risk graph: strict, and ordinal ----
    // Risk has an order, so it gets an ordered channel. High is decisively the darkest,
    // so "worse" still reads with every cone gone; low and medium separate on blue vs
    // orange, the one pairing every dichromacy preserves.
    private static final String RISK_LOW_FILL = "#b7d7e9";
    private static final String RISK_MEDIUM_FILL = "#f2ce7c";
    private static final String RISK_HIGH_FILL = "#e39455";

    // ---- dead-code graph: strict ----
    // Dead is the thing being looked for, so it is the one that stands out.
    private static final String DEAD_FILL = "#e39455";
    private static final String ACTIVE_FILL = "#d8f0ea";

    /**
     * Deliberately the lightest fill in any set. Dichromacy drains chroma from several
     * hues toward neutral, so a mid grey drifts into them; holding the uncategorised
     * node at the top of the lightness axis keeps it clear on a channel no colour
     * vision loses.
     */
    private static final String GREY_FILL = "#f4f4f4";

    /** The label colour every fill above is placed to stay legible behind. */
    public static final String LABEL = "#1a1a1a";

    /**
     * The non-colour channel: border treatment carries the same meaning the hue does,
     * so the meaning survives when the hue does not.
     */
    public enum Emphasis {
        /** Solid hairline border. */
        NORMAL,
        /** Broken border — the node is not live. Drawn "not solid" because it is not. */
        DASHED,
        /** Heavy border — the node demands attention. */
        HEAVY
    }

    static String fill(GraphModel.Category c) {
        return switch (c) {
            case RISK_HIGH -> RISK_HIGH_FILL;
            case RISK_MEDIUM -> RISK_MEDIUM_FILL;
            case RISK_LOW -> RISK_LOW_FILL;
            case DEAD -> DEAD_FILL;
            case ACTIVE -> ACTIVE_FILL;
            case ENDPOINT, SOURCE -> BLUE_FILL;
            case CONTROLLER -> SKY_FILL;
            case SERVICE -> PURPLE_FILL;
            case MAPPER -> YELLOW_FILL;
            case REPOSITORY -> GREEN_FILL;
            case TABLE, SINK -> VERMILLION_FILL;
            case STATE -> ORANGE_FILL;
            case DEFAULT -> GREY_FILL;
        };
    }

    static String border(GraphModel.Category c) {
        return switch (c) {
            case RISK_HIGH, DEAD, TABLE, SINK -> VERMILLION;
            case RISK_MEDIUM, STATE -> ORANGE;
            case RISK_LOW, ENDPOINT, SOURCE -> BLUE;
            case ACTIVE, REPOSITORY -> GREEN;
            case CONTROLLER -> SKY;
            case SERVICE -> PURPLE;
            case MAPPER -> BLACK;
            case DEFAULT -> GREY;
        };
    }

    static Emphasis emphasis(GraphModel.Category c) {
        return switch (c) {
            case DEAD -> Emphasis.DASHED;
            case RISK_HIGH -> Emphasis.HEAVY;
            default -> Emphasis.NORMAL;
        };
    }
}
