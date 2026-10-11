package com.tem.griefprot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExplosionListenerTest {

    @Test
    @DisplayName("blast-facing side is selected for all six directions")
    void selectsFaceTowardOrigin() {
        assertEquals(BlockFace.EAST, ExplosionListener.faceToward(2.5, 64.5, 0.5, 0, 64, 0));
        assertEquals(BlockFace.WEST, ExplosionListener.faceToward(-1.5, 64.5, 0.5, 0, 64, 0));
        assertEquals(BlockFace.UP, ExplosionListener.faceToward(0.5, 66.5, 0.5, 0, 64, 0));
        assertEquals(BlockFace.DOWN, ExplosionListener.faceToward(0.5, 61.5, 0.5, 0, 64, 0));
        assertEquals(BlockFace.SOUTH, ExplosionListener.faceToward(0.5, 64.5, 2.5, 0, 64, 0));
        assertEquals(BlockFace.NORTH, ExplosionListener.faceToward(0.5, 64.5, -1.5, 0, 64, 0));
    }

    @Test
    @DisplayName("the dominant axis is used for diagonal blasts")
    void dominantAxisWinsForDiagonal() {
        assertEquals(BlockFace.NORTH, ExplosionListener.faceToward(1.5, 65.5, -2.5, 0, 64, 0));
    }

    @Test
    @DisplayName("an origin at the block center has no directional face")
    void centeredOriginHasNoFace() {
        assertEquals(BlockFace.SELF, ExplosionListener.faceToward(0.5, 64.5, 0.5, 0, 64, 0));
    }

}