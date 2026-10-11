package com.tem.griefprot;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/**
 * Which face of a block a player is looking at.
 *
 * <p>Bukkit hands out the aimed face in {@code PlayerInteractEvent}, but only for
 * the instant a click happens. The brush overlay has to keep drawing that one face
 * for as long as the player is aiming at it, which is longer than any click lasts,
 * and an overlay that drew the wrong face while waiting would be worse than none.
 *
 * <p>So this works the face out from the ray, the same ray {@code
 * getTargetBlockExact} just used to find the block: the eye and the look
 * direction. Pure arithmetic, no server state, so it can be pinned by a test rather
 * than by standing in front of a wall and squinting.
 */
final class AimedFace {

    /** Bukkit's own target range. Long enough that nothing disappears mid hold. */
    static final int TARGET_RANGE = 6;

    private static final BlockFace[] FACES = {
        BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST
    };

    private AimedFace() {}

    /**
     * The face the ray enters {@code (bx, by, bz)} through, or null when it does
     * not enter at all.
     *
     * <p>The entry face is the plane the ray crosses first while heading into the
     * block, so among the six planes it is the one that satisfies all three of:
     * the ray is travelling into the block rather than out of it, the crossing is
     * ahead of the origin rather than behind, and the crossing point is inside the
     * square rather than off its edge.
     *
     * <p>Ties, which happen on a ray through an exact corner, go to the earlier
     * face in {@link #FACES}. Both answers are on the block the player is looking
     * at and neither is wrong enough to matter for a drawing, but a fixed order
     * means the overlay does not flicker between two faces as they breathe.
     */
    static BlockFace aimedFace(
            double ex, double ey, double ez, double dx, double dy, double dz, int bx, int by, int bz) {
        double nearest = Double.POSITIVE_INFINITY;
        BlockFace face = null;

        for (BlockFace candidate : FACES) {
            // the coordinate of the plane this face lies in
            double plane = switch (candidate) {
                case DOWN -> by;
                case UP -> by + 1;
                case NORTH -> bz;
                case SOUTH -> bz + 1;
                case WEST -> bx;
                default -> bx + 1;
            };

            // +1 when the face looks along the positive axis, so the outward normal
            double normal = switch (candidate) {
                case DOWN, NORTH, WEST -> -1.0;
                default -> 1.0;
            };

            double origin = switch (candidate) {
                case DOWN, UP -> ey;
                case NORTH, SOUTH -> ez;
                default -> ex;
            };

            double along = switch (candidate) {
                case DOWN, UP -> dy;
                case NORTH, SOUTH -> dz;
                default -> dx;
            };

            // travelling into the block through this face, not away from it
            if (along * normal >= 0.0) {
                continue;
            }

            double t = (plane - origin) / along;
            if (t <= 0.0 || t >= nearest) {
                continue;
            }

            double hx = ex + dx * t;
            double hy = ey + dy * t;
            double hz = ez + dz * t;

            boolean inside = switch (candidate) {
                case DOWN, UP -> within(hx, bx) && within(hz, bz);
                case NORTH, SOUTH -> within(hx, bx) && within(hy, by);
                default -> within(hy, by) && within(hz, bz);
            };
            if (!inside) {
                continue;
            }

            nearest = t;
            face = candidate;
        }

        return face;
    }

    /** A block's faces span from the block coordinate to one past it. */
    private static boolean within(double value, int base) {
        return value >= base && value <= base + 1.0;
    }

    /**
     * The face {@code player} is aiming at on {@code block}, or null when the ray
     * does not say.
     *
     * <p>Deliberately not cross-checked against the face the last click reported.
     * The two agree in practice, and where they could ever disagree the click's
     * face is what the action uses and this is only a drawing, so taking the live
     * ray and being occasionally a pixel off beats caching a value that goes stale
     * the moment the player looks somewhere else.
     */
    static BlockFace of(Player player, Block block) {
        var eye = player.getEyeLocation();
        var look = eye.getDirection();
        return aimedFace(
                eye.getX(),
                eye.getY(),
                eye.getZ(),
                look.getX(),
                look.getY(),
                look.getZ(),
                block.getX(),
                block.getY(),
                block.getZ());
    }
}