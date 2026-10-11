package com.tem.griefprot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Color;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DebugOverlayTest {

    private static final List<BlockFace> FACES =
            List.of(BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST);

    @Test
    @DisplayName("reinforcement color uses red, copper and iron durability anchors")
    void durabilityColorsUseTierAnchors() {
        assertEquals(Color.fromRGB(255, 48, 48), DebugOverlay.durabilityColor(0, 32, 256));
        assertEquals(Color.fromRGB(255, 196, 48), DebugOverlay.durabilityColor(32, 32, 256));
        assertEquals(Color.fromRGB(64, 224, 96), DebugOverlay.durabilityColor(256, 32, 256));
    }

    @Test
    @DisplayName("reinforcement color moves smoothly toward green as durability rises")
    void durabilityColorInterpolatesBetweenAnchors() {
        Color almostSpent = DebugOverlay.durabilityColor(1, 32, 256);
        Color betweenTiers = DebugOverlay.durabilityColor(100, 32, 256);

        assertTrue(almostSpent.getGreen() < DebugOverlay.durabilityColor(32, 32, 256).getGreen());
        assertTrue(betweenTiers.getGreen() > 196);
        assertTrue(betweenTiers.getRed() < 255);
    }

    @Test
    @DisplayName("details overlay reports all face durability values and shield points")
    void detailsReportProtectionAmounts() {
        assertEquals(
                "Block 1,64,-2 | D:0 U:7 N:0 S:3 W:0 E:12 | shield:5",
                DebugOverlay.detailsText(1, 64, -2, new int[] {0, 7, 0, 3, 0, 12}, 5));
    }

    @Test
    @DisplayName("overlay radius is spherical and includes its boundary")
    void overlayRadiusUsesSphere() {
        assertTrue(DebugOverlay.withinRadius(3, 4, 0, 25));
        assertTrue(DebugOverlay.withinRadius(-5, 0, 0, 25));
        assertFalse(DebugOverlay.withinRadius(4, 4, 0, 25));
    }

    @Test
    @DisplayName("a reinforced face is sampled as a plane covering the whole quad, not an outline")
    void faceGridFillsTheQuad() {
        double[][] corners = DebugOverlay.faceCorners(10, 64, -3, BlockFace.UP);
        List<double[]> grid = DebugOverlay.faceGrid(corners, 4);

        assertEquals(25, grid.size(), "four divisions is a 5x5 plane");

        // every sample sits on the face's own plane and inside its extent
        for (double[] point : grid) {
            assertEquals(65.0, point[1], 1.0e-9);
            assertTrue(point[0] >= 10.0 && point[0] <= 11.0);
            assertTrue(point[2] >= -3.0 && point[2] <= -2.0);
        }
        assertArrayEquals(corners[0], grid.getFirst(), 1.0e-9);
        assertArrayEquals(corners[2], grid.getLast(), 1.0e-9);

        // the interior is sampled too, which is the whole point: an outline
        // leaves the middle empty and two faces sharing an edge look identical
        assertTrue(grid.stream().anyMatch(point -> Math.abs(point[0] - 10.5) < 1.0e-9
                && Math.abs(point[2] + 2.5) < 1.0e-9));
    }

    @Test
    @DisplayName("every face is sampled on its own plane so neighbours cannot be confused")
    void eachFaceSamplesItsOwnPlane() {
        for (BlockFace face : FACES) {
            // The axis a face points along is the one it never varies on. A face
            // that strays off its own plane is the failure this guards: the west
            // face once ran corner to opposite corner, slicing diagonally through
            // the block instead of drawing a square on its side.
            int normal = switch (face) {
                case DOWN, UP -> 1;
                case NORTH, SOUTH -> 2;
                default -> 0;
            };
            double plane = switch (face) {
                case DOWN -> 8.0;
                case UP -> 9.0;
                case NORTH -> -9.0;
                case SOUTH -> -8.0;
                case WEST -> 4.0;
                default -> 5.0;
            };

            double[][] grid = DebugOverlay.faceGrid(DebugOverlay.faceCorners(4, 8, -9, face), 4)
                    .toArray(new double[25][]);

            for (int axis = 0; axis < 3; axis++) {
                if (axis == normal) {
                    for (double[] point : grid) {
                        assertEquals(plane, point[axis], 1.0e-9, face + " stays on its own plane");
                    }
                } else {
                    assertEquals(1.0, spread(grid, axis), 1.0e-9, face + " spans a unit of axis " + axis);
                }
            }
        }
    }

    private static double spread(double[][] points, int axis) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double[] point : points) {
            min = Math.min(min, point[axis]);
            max = Math.max(max, point[axis]);
        }
        return max - min;
    }

    @Test
    @DisplayName("a face grid needs a quadrilateral and at least one division")
    void faceGridRejectsBadInput() {
        double[][] corners = DebugOverlay.faceCorners(0, 0, 0, BlockFace.UP);
        assertThrows(IllegalArgumentException.class, () -> DebugOverlay.faceGrid(corners, 0));
        assertThrows(IllegalArgumentException.class, () -> DebugOverlay.faceGrid(new double[][] {corners[0], corners[1]}, 4));
        assertThrows(IllegalArgumentException.class, () -> DebugOverlay.faceCorners(0, 0, 0, BlockFace.SELF));
    }
}
