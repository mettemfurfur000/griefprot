package com.tem.griefprot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the hold a face tool has to finish before it does anything.
 *
 * <p>The interesting part is not the countdown, it is that a click means "start or
 * continue". Holding the button re-fires the interact event several times a second,
 * so a hold that restarted on every one of them could never fill, and the whole
 * mechanic would appear to do nothing at all.
 */
class FaceHoldTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private final FaceHold holds = new FaceHold();

    private FaceHold.Pending hold(long startMillis) {
        return hold(PLAYER, 4, 64, 5, BlockFace.NORTH, FaceTool.BRUSH, EquipmentSlot.HAND, false, startMillis);
    }

    private FaceHold.Pending hold(
            UUID player,
            int x,
            int y,
            int z,
            BlockFace face,
            FaceTool tool,
            EquipmentSlot hand,
            boolean sneaking,
            long startMillis) {
        return new FaceHold.Pending(player, WORLD, x, y, z, face, tool, hand, sneaking, startMillis);
    }

    @Test
    @DisplayName("the first click starts a hold and a repeat click keeps it")
    void aRepeatClickDoesNotRestartTheHold() {
        assertTrue(holds.arm(hold(1_000L)), "the first click starts it");
        assertFalse(holds.arm(hold(9_000L)), "holding the button must not reset the clock");
        assertEquals(1_000L, holds.get(PLAYER).startMillis(), "the start time is what the first click set");
    }

    @Test
    @DisplayName("a click on a different block replaces the hold")
    void aDifferentBlockRestartsTheHold() {
        holds.arm(hold(1_000L));

        assertTrue(holds.arm(hold(PLAYER, 9, 64, 5, BlockFace.NORTH, FaceTool.BRUSH, EquipmentSlot.HAND, false, 9_000L)));
        assertEquals(9_000L, holds.get(PLAYER).startMillis());
    }

    @Test
    @DisplayName("a different face on the same block replaces the hold too")
    void aDifferentFaceRestartsTheHold() {
        holds.arm(hold(1_000L));

        assertTrue(holds.arm(
                hold(PLAYER, 4, 64, 5, BlockFace.UP, FaceTool.BRUSH, EquipmentSlot.HAND, false, 9_000L)));
    }

    @Test
    @DisplayName("shifting mid hold replaces it, because the plate would land elsewhere")
    void shiftingMidHoldRestartsTheHold() {
        holds.arm(hold(1_000L));

        assertTrue(holds.arm(
                hold(PLAYER, 4, 64, 5, BlockFace.NORTH, FaceTool.BRUSH, EquipmentSlot.HAND, true, 9_000L)));
    }

    @Test
    @DisplayName("a different tool replaces the hold")
    void aDifferentToolRestartsTheHold() {
        holds.arm(hold(1_000L));

        assertTrue(holds.arm(
                hold(PLAYER, 4, 64, 5, BlockFace.NORTH, FaceTool.LOCK_FACES, EquipmentSlot.HAND, false, 9_000L)));
    }

    @Test
    @DisplayName("a repeat click in the other hand is still the same hold")
    void bothHandsShareTheHold() {
        holds.arm(hold(1_000L));

        // A click produces one event per hand. If the hand were part of the hold's
        // identity, a player with a brush in both hands would restart the clock on
        // every repeat and the hold would never finish at all.
        assertFalse(holds.arm(
                hold(PLAYER, 4, 64, 5, BlockFace.NORTH, FaceTool.BRUSH, EquipmentSlot.OFF_HAND, false, 9_000L)));
        assertEquals(1_000L, holds.get(PLAYER).startMillis(), "the clock must not move");
        assertEquals(
                EquipmentSlot.OFF_HAND,
                holds.get(PLAYER).hand(),
                "the hold should follow the tool to the hand it is now in");
    }

    @Test
    @DisplayName("each player holds at most one thing at a time")
    void holdsArePerPlayer() {
        holds.arm(hold(1_000L));
        FaceHold.Pending other = hold(OTHER, 4, 64, 5, BlockFace.NORTH, FaceTool.BRUSH, EquipmentSlot.HAND, false, 2_000L);

        assertTrue(holds.arm(other));
        assertEquals(2, holds.pending().size());
        assertEquals(1_000L, holds.get(PLAYER).startMillis());
        assertEquals(2_000L, holds.get(OTHER).startMillis());
    }

    @Test
    @DisplayName("a hold is not due before the configured time and is due after it")
    void dueFollowsTheWallClock() {
        FaceHold.Pending hold = hold(10_000L);

        assertFalse(FaceHold.due(hold, 10_999L, 1_000L));
        assertTrue(FaceHold.due(hold, 11_000L, 1_000L), "the boundary belongs to the hold");
        assertTrue(FaceHold.due(hold, 12_000L, 1_000L));
    }

    @Test
    @DisplayName("the progress bar fills from empty to full and never overshoots")
    void progressBarFills() {
        FaceHold.Pending hold = hold(0L);

        assertEquals(0, FaceHold.filled(hold, 0L, 1_000L));
        assertEquals(5, FaceHold.filled(hold, 500L, 1_000L));
        assertEquals(10, FaceHold.filled(hold, 1_000L, 1_000L));
        assertEquals(10, FaceHold.filled(hold, 9_000L, 1_000L), "an overrunning tick must not overflow the bar");

        assertEquals("Reinforcing |----------| 0%", FaceHold.progress(hold, 0L, 1_000L));
        assertEquals("Reinforcing |#####-----| 50%", FaceHold.progress(hold, 500L, 1_000L));
        assertEquals("Reinforcing |##########| 100%", FaceHold.progress(hold, 1_000L, 1_000L));
    }

    @Test
    @DisplayName("the progress bar says what is being done")
    void progressNamesTheTool() {
        FaceHold.Pending lock =
                hold(PLAYER, 0, 64, 0, BlockFace.UP, FaceTool.LOCK_FACES, EquipmentSlot.HAND, false, 0L);

        assertTrue(FaceHold.progress(lock, 1_000L, 1_000L).startsWith("Locking "));
    }

    @Test
    @DisplayName("cancelling and forgetting take the hold away")
    void holdsCanBeDropped() {
        FaceHold.Pending hold = hold(1_000L);
        holds.arm(hold);
        assertSame(hold, holds.get(PLAYER));
        assertTrue(holds.isPending(PLAYER));

        holds.cancel(PLAYER);
        assertNull(holds.get(PLAYER));
        assertFalse(holds.isPending(PLAYER));
        assertTrue(holds.isEmpty());

        holds.arm(hold);
        holds.clear();
        assertTrue(holds.isEmpty());
    }

    @Test
    @DisplayName("pending is a copy, so a caller can walk it while cancelling")
    void pendingIsACopy() {
        holds.arm(hold(1_000L));

        // The listener cancels holds while walking this, so handing out the live
        // map would be a ConcurrentModificationException on the first one.
        var snapshot = holds.pending();
        assertEquals(1, snapshot.size());

        holds.cancel(PLAYER);
        assertEquals(1, snapshot.size(), "the snapshot the caller is holding is unaffected");
        assertTrue(holds.isEmpty());
    }

    @Test
    @DisplayName("the other hand is always the one that is not holding the tool")
    void otherHandFlips() {
        assertEquals(EquipmentSlot.OFF_HAND, FaceTool.otherHand(EquipmentSlot.HAND));
        assertEquals(EquipmentSlot.HAND, FaceTool.otherHand(EquipmentSlot.OFF_HAND));
    }

    @Test
    @DisplayName("only the three tools start a hold")
    void onlyTheToolsStartAHold() {
        assertEquals(FaceTool.BRUSH, FaceTool.forMaterial(org.bukkit.Material.BRUSH));
        assertEquals(FaceTool.LOCK_FACES, FaceTool.forMaterial(org.bukkit.Material.FLINT_AND_STEEL));
        assertEquals(FaceTool.PISTON_FREE, FaceTool.forMaterial(org.bukkit.Material.SLIME_BALL));

        // A nugget on its own does nothing. The brush applies it.
        assertNull(FaceTool.forMaterial(org.bukkit.Material.COPPER_NUGGET));
        assertNull(FaceTool.forMaterial(org.bukkit.Material.IRON_NUGGET));
        assertNull(FaceTool.forMaterial(org.bukkit.Material.AIR));
        assertNull(FaceTool.forMaterial(org.bukkit.Material.STONE));
    }

    @Test
    @DisplayName("only the brush needs a tier item in the other hand")
    void onlyTheBrushNeedsANugget() {
        assertTrue(FaceTool.BRUSH.needsTierItem());
        assertFalse(FaceTool.LOCK_FACES.needsTierItem());
        assertFalse(FaceTool.PISTON_FREE.needsTierItem());
    }
}