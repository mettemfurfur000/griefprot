package com.tem.griefprot;

import com.tem.griefprot.world.GriefWorld;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;

/**
 * Wipes stored protection once the block it describes is gone.
 *
 * <p>Protection is keyed by coordinates, so a record outlives the block it was
 * placed on and is inherited by whatever takes those coordinates next. Every
 * listener that lets a block disappear routes through here, so the two layers
 * always go together: {@code gp_chunk_reinf_clear} drops the faces, and the
 * shield points have to be drained by hand because nothing on the C side ever
 * clears them on a block's behalf.
 *
 * <p>The generators go through here for the same reason, and for one more: a
 * shield is anchored at a block position, so a generator whose block was blown up
 * leaves a shield covering the hole. {@link ShieldGenerators#onBlockGone} is
 * passed in rather than reached for, so that a fourth caller added later cannot
 * forget it.
 *
 * <p>Draining the points is safe on its own. {@link com.tem.griefprot.world.WorldPolicy}
 * refuses air, so a shield whose target is now empty skips it without spending
 * budget; the points would have been unreachable rather than lost.
 */
final class Protection {

    private Protection() {}

    /** Drops both protection layers at one position, if it holds either. */
    static void clearAt(GriefWorld world, int x, int y, int z) {
        if (!world.hasProtection(x, y, z)) {
            return;
        }
        world.abi().reinfClear(world.handle(), x, y, z);
        int points = world.abi().pointsGet(world.handle(), x, y, z);
        if (points > 0) {
            world.abi().pointsTake(world.handle(), x, y, z, points);
        }
    }

    /**
     * Drops both protection layers at a block and at the slot it shares with
     * another block, plus the shield a generator was holding there.
     *
     * <p>Breaking either half of a bisected block or a bed destroys both, so the
     * half that was not the one aimed at loses its record too. A generator is
     * never multipart, so only the aimed-at half is worth asking about, but the
     * other half's cell is still cleared exactly as it was before.
     */
    static void clearBlockAndPartner(ShieldGenerators generators, GriefWorld world, Block block) {
        generators.onBlockGone(world, block);
        clearAt(world, block.getX(), block.getY(), block.getZ());

        Block otherHalf = multipartPartner(block);
        if (otherHalf != null) {
            clearAt(world, otherHalf.getX(), otherHalf.getY(), otherHalf.getZ());
        }
    }

    /**
     * The block sharing {@code block}'s slot, or null when it has none.
     *
     * <p>Called while the block is still standing, either from a break event
     * before the server applies it or from the placement that replaced it.
     */
    static Block multipartPartner(Block block) {
        BlockFace direction = partnerDirection(block.getBlockData());
        if (direction == null) {
            return null;
        }
        Block other = block.getRelative(direction);
        if (other.getType() != block.getType()) {
            return null;
        }
        return isOtherHalf(block.getBlockData(), other.getBlockData()) ? other : null;
    }

    /** Where a multipart block's other half sits, or null for a single block. */
    static BlockFace partnerDirection(BlockData data) {
        if (data instanceof Bisected bisected) {
            return bisected.getHalf() == Bisected.Half.TOP ? BlockFace.DOWN : BlockFace.UP;
        }
        if (data instanceof Bed bed) {
            return bed.getPart() == Bed.Part.HEAD ? bed.getFacing().getOppositeFace() : bed.getFacing();
        }
        return null;
    }

    private static boolean isOtherHalf(BlockData data, BlockData other) {
        if (data instanceof Bisected bisected && other instanceof Bisected otherBisected) {
            return bisected.getHalf() != otherBisected.getHalf();
        }
        if (data instanceof Bed bed && other instanceof Bed otherBed) {
            return bed.getPart() != otherBed.getPart() && bed.getFacing() == otherBed.getFacing();
        }
        return false;
    }
}