package com.tem.griefprot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the geometry behind the brush overlay's single face.
 *
 * <p>Bukkit only tells a plugin which face was clicked at the instant of the click,
 * and the overlay has to keep drawing it afterwards. This is the arithmetic that
 * works it out from the eye ray instead, so it is pinned here rather than checked
 * by standing in front of a wall.
 */
class AimedFaceTest {

    /** A block at the origin, and an eye far enough away to see all six faces. */
    private static final int X = 100;
    private static final int Y = 64;
    private static final int Z = -20;

    /** Aims at the middle of the block's near face from straight outside it. */
    private static BlockFace aimFrom(double ex, double ey, double ez, double dx, double dy, double dz) {
        return AimedFace.aimedFace(ex, ey, ez, dx, dy, dz, X, Y, Z);
    }

    @Test
    @DisplayName("a ray from above enters through the top")
    void fromAbove() {
        assertEquals(BlockFace.UP, aimFrom(X + 0.5, Y + 5, Z + 0.5, 0, -1, 0));
    }

    @Test
    @DisplayName("a ray from below enters through the bottom")
    void fromBelow() {
        assertEquals(BlockFace.DOWN, aimFrom(X + 0.5, Y - 5, Z + 0.5, 0, 1, 0));
    }

    @Test
    @DisplayName("a ray from the north enters through the north face")
    void fromNorth() {
        assertEquals(BlockFace.NORTH, aimFrom(X + 0.5, Y + 0.5, Z - 5, 0, 0, 1));
        assertEquals(BlockFace.SOUTH, aimFrom(X + 0.5, Y + 0.5, Z + 5, 0, 0, -1));
    }

    @Test
    @DisplayName("a ray from the side enters through that side")
    void fromTheSides() {
        assertEquals(BlockFace.WEST, aimFrom(X - 5, Y + 0.5, Z + 0.5, 1, 0, 0));
        assertEquals(BlockFace.EAST, aimFrom(X + 5, Y + 0.5, Z + 0.5, -1, 0, 0));
    }

    @Test
    @DisplayName("a slanted ray still finds the face it comes in through")
    void slantedRays() {
        // Steep and from the north, so the ray crosses the top plane first. Taking
        // the first crossing it is offered is what makes this correct.
        assertEquals(BlockFace.UP, aimFrom(X + 0.5, Y + 4, Z - 1, 0, -3, 1));

        // Shallow and from below the top, so it reaches the north plane while still
        // inside the block's own height. The two cross at the same distance, and
        // the top one is behind the eye here, so the north face is the answer.
        assertEquals(BlockFace.NORTH, aimFrom(X + 0.5, Y, Z - 2, 0, 0.3, 1));
    }

    @Test
    @DisplayName("every compass direction resolves to a face")
    void allDirectionsResolve() {
        for (double dx = -1; dx <= 1; dx += 0.5) {
            for (double dy = -1; dy <= 1; dy += 0.5) {
                for (double dz = -1; dz <= 1; dz += 0.5) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockFace face = aimFrom(
                            X + 0.5 - dx * 8,
                            Y + 0.5 - dy * 8,
                            Z + 0.5 - dz * 8,
                            dx,
                            dy,
                            dz);
                    org.junit.jupiter.api.Assertions.assertNotNull(face, dx + "," + dy + "," + dz);
                }
            }
        }
    }

    @Test
    @DisplayName("a ray heading away from the block finds nothing")
    void aRayPointingAwayFindsNothing() {
        assertNull(aimFrom(X + 5, Y + 0.5, Z + 0.5, 1, 0, 0));
    }

    @Test
    @DisplayName("a ray that misses the block entirely finds nothing")
    void aRayThatMissesFindsNothing() {
        assertNull(aimFrom(X + 0.5, Y + 5, Z + 0.5, 0, -1, 5), "past the side of it rather than onto it");
    }

    @Test
    @DisplayName("a ray already inside the block finds nothing")
    void aRayStartingInsideFindsNothing() {
        // The crosshair can never be inside the block it is aimed at, so this is a
        // guard rather than a case that arises in play.
        assertNull(aimFrom(X + 0.5, Y + 0.5, Z + 0.5, 0, -1, 0));
    }

    @Test
    @DisplayName("an exact corner picks one face and sticks to it")
    void cornersAreStable() {
        // A ray through a corner crosses two planes at the same distance. Either
        // answer is on the block, but it has to be the same one every time or the
        // overlay flickers as the player breathes.
        BlockFace first = aimFrom(X - 4, Y + 4, Z + 0.5, 1, -1, 0);
        assertEquals(first, aimFrom(X - 4, Y + 4, Z + 0.5, 1, -1, 0));
        org.junit.jupiter.api.Assertions.assertNotNull(first);
    }

    @Test
    @DisplayName("negative coordinates are not folded")
    void negativeCoordinatesAreNotFolded() {
        // Block at (-1, -60, -17). Shifting rather than offsetting would report a
        // face on the wrong block entirely.
        assertEquals(BlockFace.UP, AimedFace.aimedFace(-0.5, -56, -16.5, 0, -1, 0, -1, -60, -17));
        assertEquals(BlockFace.DOWN, AimedFace.aimedFace(-0.5, -64, -16.5, 0, 1, 0, -1, -60, -17));
    }
}