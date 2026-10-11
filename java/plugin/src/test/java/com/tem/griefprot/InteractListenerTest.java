package com.tem.griefprot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.tem.griefprot.InteractListener.OppositeSite;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the targeting arithmetic behind opposite-side placement.
 *
 * <p>The listener itself needs a server, so what is pinned here is the part that
 * can silently be wrong: which block a sneaking click reaches and which face on
 * it takes the plate.
 */
class InteractListenerTest {

    private static final BlockFace[] SIDES = {
        BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST
    };

    @Test
    @DisplayName("the same-block face is the far side of the one clicked")
    void sameBlockFaceIsTheOppositeFace() {
        for (BlockFace clicked : SIDES) {
            BlockFace far = InteractListener.sameBlockFace(clicked);

            assertEquals(clicked.getOppositeFace(), far, clicked + " has no far side");
            assertNotEquals(clicked, far, clicked + " plated the face that was clicked");
        }
    }

    @Test
    @DisplayName("clicking the top of a one-block-tall wall reaches its underside")
    void sameBlockTopReachesTheUnderside() {
        assertEquals(BlockFace.DOWN, InteractListener.sameBlockFace(BlockFace.UP));
        assertEquals(BlockFace.UP, InteractListener.sameBlockFace(BlockFace.DOWN));
    }

    @Test
    @DisplayName("the far block's face points back through the face that was clicked")
    void farFacePointsBackAtTheClickedBlock() {
        for (BlockFace clicked : SIDES) {
            OppositeSite site = InteractListener.oppositeSite(clicked, 7, 64, -9);

            // Stepping from the far block along the chosen face must land back on
            // the block that was clicked. Getting the sign backwards satisfies
            // nothing here: it lands on the block beyond that instead.
            assertEquals(7, site.x() + site.face().getModX(), clicked + " x");
            assertEquals(64, site.y() + site.face().getModY(), clicked + " y");
            assertEquals(-9, site.z() + site.face().getModZ(), clicked + " z");
        }
    }

    @Test
    @DisplayName("the far block is adjacent, and is not the one that was clicked")
    void farBlockIsAdjacentAndNotTheClickedOne() {
        for (BlockFace clicked : SIDES) {
            OppositeSite site = InteractListener.oppositeSite(clicked, 7, 64, -9);

            int away = Math.max(Math.max(Math.abs(site.x() - 7), Math.abs(site.y() - 64)), Math.abs(site.z() + 9));
            assertEquals(1, away, clicked + " is not a neighbour");
            assertNotEquals(clicked, site.face(), clicked + " plated the face that was clicked");
        }
    }

    @Test
    @DisplayName("clicking the underside of a brick reaches the block beneath it")
    void clickingDownReachesTheBlockUnderneath() {
        OppositeSite site = InteractListener.oppositeSite(BlockFace.DOWN, 0, 64, 0);

        assertEquals(63, site.y(), "the block the brick stands on");
        assertEquals(BlockFace.UP, site.face(), "its top face, the other side of the same plane");
    }

    @Test
    @DisplayName("clicking the outer face of a wall plate the brick behind it")
    void clickingSideReachesTheNextBlockIn() {
        OppositeSite site = InteractListener.oppositeSite(BlockFace.WEST, 0, 64, 0);

        assertEquals(-1, site.x(), "the brick behind the one clicked");
        assertEquals(BlockFace.EAST, site.face(), "its inner face, facing back out");
    }

    @Test
    @DisplayName("negative coordinates are offset, not truncated")
    void negativeCoordinatesAreOffsetNotTruncated() {
        OppositeSite site = InteractListener.oppositeSite(BlockFace.WEST, -1, -60, -17);

        assertEquals(-2, site.x());
        assertEquals(-60, site.y());
        assertEquals(-17, site.z());
        assertEquals(BlockFace.EAST, site.face());
    }
}