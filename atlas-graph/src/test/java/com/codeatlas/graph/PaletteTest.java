package com.codeatlas.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the graph palette to its two promises: that categories sharing a graph stay
 * telling apart under colour-vision deficiency, and that opposed meanings never rest
 * on hue alone.
 *
 * <p>Rather than trust that the chosen hues are safe, this test simulates dichromatic
 * vision (Viénot et al.'s widely used linear approximation) and measures the remaining
 * separation in CIE L*a*b*. A palette that regresses fails here with the offending pair
 * and its ΔE, not in a reviewer's inbox months later.
 */
class PaletteTest {

    /**
     * Sets where colour <em>is</em> the meaning: nothing else on a node says "dead" or
     * "high risk". Held to every dichromacy, tritanopia included.
     */
    private static final List<Set<GraphModel.Category>> LOAD_BEARING = List.of(
            Set.of(GraphModel.Category.DEAD, GraphModel.Category.ACTIVE),
            Set.of(GraphModel.Category.RISK_HIGH, GraphModel.Category.RISK_MEDIUM,
                    GraphModel.Category.RISK_LOW, GraphModel.Category.DEFAULT));

    /**
     * The architecture graph, where every node is labelled with its role and hue only
     * groups them. Eight categories cannot stay mutually separable under all three
     * dichromacies — each flattens colour space to roughly 2D and eight points will not
     * hold apart in all of them — so this set is held to the common ones. See
     * {@link #tritanopiaLimitIsWhatWeThinkItIs()}, which pins that claim down rather
     * than leaving it as an excuse.
     */
    private static final Set<GraphModel.Category> ARCHITECTURE = Set.of(
            GraphModel.Category.ENDPOINT, GraphModel.Category.CONTROLLER,
            GraphModel.Category.SERVICE, GraphModel.Category.MAPPER,
            GraphModel.Category.REPOSITORY, GraphModel.Category.TABLE,
            GraphModel.Category.SOURCE, GraphModel.Category.SINK,
            GraphModel.Category.STATE, GraphModel.Category.DEFAULT);

    /** Reuse of a hue <em>across</em> sets is deliberate — a reader never sees two at once. */
    private static final List<Vision> COMMON = List.of(Vision.NORMAL, Vision.PROTANOPIA,
            Vision.DEUTERANOPIA);

    /**
     * Pairs that intentionally share a colour because they denote the same role to a
     * reader: an endpoint and a data source are both where data enters, a table and a
     * sink both where it lands. Predates this palette; preserved deliberately.
     */
    private static final Set<Set<GraphModel.Category>> SYNONYMS = Set.of(
            Set.of(GraphModel.Category.ENDPOINT, GraphModel.Category.SOURCE),
            Set.of(GraphModel.Category.TABLE, GraphModel.Category.SINK));

    /**
     * Pairs whose meanings are opposed, where a misread is not a nuisance but a wrong
     * conclusion — "this code is dead", "this dependency is safe".
     */
    private static final List<Set<GraphModel.Category>> OPPOSED = List.of(
            Set.of(GraphModel.Category.DEAD, GraphModel.Category.ACTIVE),
            Set.of(GraphModel.Category.RISK_HIGH, GraphModel.Category.RISK_LOW),
            Set.of(GraphModel.Category.RISK_HIGH, GraphModel.Category.RISK_MEDIUM));

    /** ΔE76 below ~10 is where two colours stop reading as different at a glance. */
    private static final double MIN_DELTA_E = 10.0;

    @Test
    @DisplayName("where colour carries the meaning, it survives every dichromacy")
    void loadBearingCategoriesSurviveEveryDichromacy() {
        for (Vision vision : Vision.values()) {
            for (Set<GraphModel.Category> group : LOAD_BEARING) {
                eachPair(group, (a, b) -> {
                    assertSeparated(vision, a, b, Palette.fill(a), Palette.fill(b), "fill");
                    assertSeparated(vision, a, b, Palette.border(a), Palette.border(b), "border");
                });
            }
        }
    }

    @Test
    @DisplayName("architecture layers stay distinguishable under the common dichromacies")
    void architectureLayersSurviveCommonDichromacies() {
        for (Vision vision : COMMON) {
            eachPair(ARCHITECTURE, (a, b) -> {
                assertSeparated(vision, a, b, Palette.fill(a), Palette.fill(b), "fill");
                assertSeparated(vision, a, b, Palette.border(a), Palette.border(b), "border");
            });
        }
    }

    @Test
    @DisplayName("the risk ramp reads as ordered with no colour vision at all")
    void riskRampIsOrderedByLightness() {
        double low = luminance(Palette.fill(GraphModel.Category.RISK_LOW));
        double medium = luminance(Palette.fill(GraphModel.Category.RISK_MEDIUM));
        double high = luminance(Palette.fill(GraphModel.Category.RISK_HIGH));
        assertTrue(high < low && high < medium,
                "high risk must be the darkest fill so the ramp still says \"worse\" to a reader "
                        + "with no colour vision at all; got low=" + fmt(low) + " medium="
                        + fmt(medium) + " high=" + fmt(high));
    }

    @Test
    @DisplayName("opposed meanings never rest on colour alone")
    void opposedCategoriesDifferWithoutColour() {
        for (Set<GraphModel.Category> pair : OPPOSED) {
            List<GraphModel.Category> members = List.copyOf(pair);
            GraphModel.Category a = members.get(0);
            GraphModel.Category b = members.get(1);
            assertNotEquals(Palette.emphasis(a), Palette.emphasis(b),
                    a + " and " + b + " mean opposite things but share a border treatment, so a "
                            + "reader who cannot separate their hues — or a greyscale printout — "
                            + "loses the distinction entirely");
        }
    }

    @Test
    @DisplayName("node labels stay legible on every fill (WCAG AAA)")
    void labelContrastHoldsOnEveryFill() {
        for (GraphModel.Category c : GraphModel.Category.values()) {
            double ratio = contrast(Palette.LABEL, Palette.fill(c));
            assertTrue(ratio >= 7.0, "label contrast on " + c + " fill " + Palette.fill(c)
                    + " is " + fmt(ratio) + ":1, below the WCAG AAA 7:1 floor");
        }
    }

    /**
     * Pins the one limitation the architecture palette concedes, so it stays a measured
     * fact rather than a convenient story. If a future palette clears this bar, delete
     * the concession in {@link Palette}'s javadoc and fold the set into the strict test.
     */
    @Test
    @DisplayName("the conceded tritanopia collision is real, and confined to the labelled set")
    void tritanopiaLimitIsWhatWeThinkItIs() {
        double worst = Double.MAX_VALUE;
        for (GraphModel.Category a : ARCHITECTURE) {
            for (GraphModel.Category b : ARCHITECTURE) {
                if (a.compareTo(b) < 0 && !SYNONYMS.contains(Set.of(a, b))) {
                    worst = Math.min(worst, delta(Vision.TRITANOPIA, Palette.fill(a), Palette.fill(b)));
                }
            }
        }
        assertTrue(worst < MIN_DELTA_E, "the architecture palette now clears " + MIN_DELTA_E
                + " under tritanopia (worst pair ΔE " + fmt(worst) + "), so the documented "
                + "concession is stale — hold this set to the strict standard instead");

        // ... and the sets that actually carry meaning in colour do not concede it.
        for (Set<GraphModel.Category> group : LOAD_BEARING) {
            eachPair(group, (a, b) -> assertSeparated(Vision.TRITANOPIA, a, b,
                    Palette.fill(a), Palette.fill(b), "fill"));
        }
    }

    private static void eachPair(Set<GraphModel.Category> group,
                                 java.util.function.BiConsumer<GraphModel.Category,
                                         GraphModel.Category> check) {
        List<GraphModel.Category> members = List.copyOf(group);
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                if (!SYNONYMS.contains(Set.of(members.get(i), members.get(j)))) {
                    check.accept(members.get(i), members.get(j));
                }
            }
        }
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    private static double delta(Vision vision, String hexA, String hexB) {
        return deltaE(vision.simulate(rgb(hexA)), vision.simulate(rgb(hexB)));
    }

    private static void assertSeparated(Vision vision, GraphModel.Category a, GraphModel.Category b,
                                        String hexA, String hexB, String channel) {
        double delta = deltaE(vision.simulate(rgb(hexA)), vision.simulate(rgb(hexB)));
        assertTrue(delta >= MIN_DELTA_E, () -> String.format(
                "%s %s (%s) and %s (%s) collapse to ΔE %.1f under %s — below the %.0f needed to "
                        + "read as different colours", channel, a, hexA, b, hexB, delta, vision,
                MIN_DELTA_E));
    }

    // ---- colour-vision simulation ----

    /**
     * Viénot, Brettel & Mollon's linear dichromacy approximation: project the colour onto
     * the plane of hues the missing cone type cannot separate.
     */
    private enum Vision {
        NORMAL {
            @Override double[] apply(double[] lms) {
                return lms;
            }
        },
        /** No long-wave (red) cone. */
        PROTANOPIA {
            @Override double[] apply(double[] lms) {
                return new double[]{2.02344 * lms[1] - 2.52581 * lms[2], lms[1], lms[2]};
            }
        },
        /** No medium-wave (green) cone. The most common form. */
        DEUTERANOPIA {
            @Override double[] apply(double[] lms) {
                return new double[]{lms[0], 0.494207 * lms[0] + 1.24827 * lms[2], lms[2]};
            }
        },
        /** No short-wave (blue) cone. Rare, but the one a blue/orange palette must not trip on. */
        TRITANOPIA {
            @Override double[] apply(double[] lms) {
                return new double[]{lms[0], lms[1], -0.395913 * lms[0] + 0.801109 * lms[1]};
            }
        };

        abstract double[] apply(double[] lms);

        double[] simulate(double[] linearRgb) {
            double[] lms = {
                    17.8824 * linearRgb[0] + 43.5161 * linearRgb[1] + 4.11935 * linearRgb[2],
                    3.45565 * linearRgb[0] + 27.1554 * linearRgb[1] + 3.86714 * linearRgb[2],
                    0.0299566 * linearRgb[0] + 0.184309 * linearRgb[1] + 1.46709 * linearRgb[2]};
            double[] p = apply(lms);
            return new double[]{
                    0.080944479 * p[0] - 0.130504409 * p[1] + 0.116721066 * p[2],
                    -0.0102485335 * p[0] + 0.0540193266 * p[1] - 0.113614708 * p[2],
                    -0.000365296938 * p[0] - 0.00412161469 * p[1] + 0.693511405 * p[2]};
        }
    }

    // ---- colour maths ----

    /** Parses #rrggbb into linear-light RGB. */
    private static double[] rgb(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        return new double[]{
                toLinear(((v >> 16) & 0xff) / 255.0),
                toLinear(((v >> 8) & 0xff) / 255.0),
                toLinear((v & 0xff) / 255.0)};
    }

    private static double toLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double luminance(String hex) {
        double[] c = rgb(hex);
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** CIE76 colour difference: Euclidean distance in L*a*b*. */
    private static double deltaE(double[] linearA, double[] linearB) {
        double[] a = lab(linearA);
        double[] b = lab(linearB);
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    private static double[] lab(double[] linear) {
        double r = clamp(linear[0]);
        double g = clamp(linear[1]);
        double b = clamp(linear[2]);
        double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
        double fx = f(x);
        double fy = f(y);
        double fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : (7.787 * t) + (16.0 / 116.0);
    }

    /** Dichromacy projection can leave the sRGB gamut; clamp before measuring. */
    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
