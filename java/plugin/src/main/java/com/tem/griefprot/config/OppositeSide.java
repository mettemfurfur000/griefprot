package com.tem.griefprot.config;

/**
 * Where a sneaking reinforcement plate lands, from {@code opposite-side-placement}.
 *
 * <p>The two reachable modes are genuinely different mechanics, not two flavours
 * of one, so they are named rather than blended. Anything a shift-click cannot
 * resolve is reported rather than silently falling back to the other mode: a
 * plate appearing somewhere the player did not ask for is worse than a refusal.
 */
public enum OppositeSide {

    /**
     * The far side of the block that was clicked: the face diametrically
     * opposite the one the player aimed at.
     *
     * <p>The block is always the one under the crosshair, so this never fails on
     * a missing neighbour. It is what makes the underside of a wall reachable at
     * all: stand on top of a one-block-tall wall, shift-click its top face, and
     * the plate lands on the face nothing can aim at.
     */
    SAME_BLOCK,

    /**
     * Through the clicked face and onto the block behind it, on the face that
     * shares the plane.
     *
     * <p>This is the only way to plate the interior of a wall more than one block
     * thick, since the faces between two buried blocks cannot be aimed at by
     * anyone. It refuses whenever the block behind is air or otherwise
     * unprotectable, which is most of the time in the open, so it is not a
     * general replacement for {@link #SAME_BLOCK}.
     */
    THROUGH,

    /** A plate always lands on the face that was clicked. Shift means nothing. */
    OFF;

    /** Parses a config value, falling back rather than throwing on a typo. */
    public static OppositeSide parse(String raw, OppositeSide fallback) {
        if (raw == null) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "same-block" -> SAME_BLOCK;
            case "through" -> THROUGH;
            case "off" -> OFF;
            default -> fallback;
        };
    }
}