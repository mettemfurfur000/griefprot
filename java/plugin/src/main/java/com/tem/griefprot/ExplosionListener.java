package com.tem.griefprot;

import com.tem.griefprot.nativebridge.Abi;
import com.tem.griefprot.nativebridge.BreakResult;
import com.tem.griefprot.world.GriefWorld;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Resolves explosion damage against the same protection store as player breaks.
 *
 * <p>Which blocks a blast reaches is the server's decision, not ours. The list handed
 * to {@link org.bukkit.event.entity.EntityExplodeEvent#blockList()} is the output of
 * {@code ServerExplosion.calculateExplodedPositions()}, which already ran the real
 * exposure test: a fixed lattice of unit directions cast from each candidate toward
 * the explosion origin, occluded through collision shapes rather than full cubes. A
 * second tracer here would only be a second, coarser opinion about the same ray, and
 * it could not see blocks the server excluded -- which is exactly the set whose
 * intervening walls we would want to charge. So this listener only decides what
 * happens to blocks the server already selected.
 *
 * <p>Which reinforced face pays for the blast is a separate question. The server
 * does not say, so the blast is taken to arrive on the face nearest the origin;
 * if that face carries no reinforcement, the block's own weakest face pays
 * instead. Without that fallback a block whose exposed side is bare would fall
 * straight through to shield points and break, even though the reinforcement a
 * player put on its other sides would have held it -- which is the normal shape
 * of a diagonal blast past a reinforced neighbour.
 */
public final class ExplosionListener implements Listener {

    private static final AtomicLong EXPLOSION_IDS = new AtomicLong();
    private static final List<BlockFace> FACES =
            List.of(BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST);

    private final GriefProtPlugin plugin;

    public ExplosionListener(GriefProtPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protectAffectedBlocks(
                event.getLocation(), event.blockList(), "entity:" + event.getEntity().getType());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Location origin = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        protectAffectedBlocks(origin, event.blockList(), "block:" + event.getBlock().getType());
    }

    private void protectAffectedBlocks(Location origin, List<Block> affectedBlocks, String source) {
        GriefWorld world = plugin.grief(origin.getWorld());
        if (world == null) {
            return;
        }

        long explosionId = EXPLOSION_IDS.incrementAndGet();
        Set<BlockPosition> cancelledBlocks = new HashSet<>();
        List<BlockAudit> audits = new ArrayList<>();
        List<Block> candidates = new ArrayList<>(affectedBlocks);
        candidates.sort(Comparator.comparingDouble(block -> distanceSquared(origin, block)));
        for (Block block : candidates) {
            BlockPosition position = BlockPosition.of(block);
            BlockAudit audit = new BlockAudit(
                    block,
                    block.getType(),
                    faceDurability(world, block),
                    world.abi().pointsGet(world.handle(), block.getX(), block.getY(), block.getZ()));
            audits.add(audit);
            String decision;
            if (!world.hasProtection(block.getX(), block.getY(), block.getZ())) {
                decision = "unprotected_target_allowed";
                audit.setDecision(decision);
                plugin.getLogger().info(auditPrefix(explosionId) + " source=" + source + " origin="
                        + location(origin) + " target=" + position(block) + " decision=" + decision);
                continue;
            }

            BlockFace blastFace = faceToward(
                    origin.getX(), origin.getY(), origin.getZ(),
                    block.getX(), block.getY(), block.getZ());
            int face = plugin.faces().toAbi(blastFace);
            if (face != Abi.NO_FACE && faceDurability(world, block, face) == 0) {
                face = Abi.NO_FACE;
            }
            if (face == Abi.NO_FACE) {
                face = world.abi().weakestFace(
                        world.handle(), block.getX(), block.getY(), block.getZ());
                if (face == Abi.NO_FACE) {
                    face = plugin.abi().faceNone();
                }
            }
            BlockFace spentFace = plugin.faces().toBukkit(face);
            audit.addNote("blastFace=" + blastFace + " spentFace=" + spentFace);

            int struckFaceDurability = faceDurability(world, block, face);
            BreakResult result = world.resolveBreak(block, face, true, true);
            if (!result.ok()) {
                decision = "resolver_failed:" + world.abi().lastError();
                plugin.getLogger().warning(auditPrefix(explosionId) + " source=" + source + " target="
                        + position(block) + " blastFace=" + blastFace + " decision=" + decision);
                audit.setDecision(decision);
                continue;
            }
            if (struckFaceDurability > 0) {
                int after = faceDurability(world, block, face);
                audit.addNote("struck_face_durability before=" + struckFaceDurability + " after=" + after
                        + " blockBroke=" + result.destroyed());
            }
            if (result.protectedFromBreak()) {
                cancelledBlocks.add(position);
                decision = "target_protected reinforcementHeld=" + result.reinforcementHeld()
                        + " shieldPaid=" + result.shieldPaid() + " absorbed=" + result.absorbed()
                        + " shieldLeft=" + result.left();
            } else {
                decision = "target_allowed blockBroke=" + result.destroyed()
                        + " reinforcementHeld=" + result.reinforcementHeld()
                        + " shieldPaid=" + result.shieldPaid() + " absorbed=" + result.absorbed()
                        + " shieldLeft=" + result.left();
            }
            audit.setDecision(decision);
            plugin.getLogger().info(auditPrefix(explosionId) + " source=" + source + " origin="
                    + location(origin) + " target=" + position(block) + " blastFace=" + blastFace
                    + " spentFace=" + spentFace + " decision=" + decision);
        }

        affectedBlocks.removeIf(block -> cancelledBlocks.contains(BlockPosition.of(block)));

        plugin.getServer().getScheduler().runTask(plugin, () -> logExplosionOutcomes(
                world, explosionId, source, audits));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitiesDestroyed(EntityExplodeEvent event) {
        clearDestroyed(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlocksDestroyed(BlockExplodeEvent event) {
        clearDestroyed(event.blockList());
    }

    /**
     * Drops whatever the blocks this explosion is about to destroy were holding.
     *
     * <p>At {@link EventPriority#MONITOR} the blocks saved by
     * {@link #protectAffectedBlocks} have already left the list, so what remains
     * is exactly what the server is about to break. A target the resolver let
     * through keeps every face the blast did not strike, and those faces would
     * otherwise sit on air until something was placed there.
     */
    private void clearDestroyed(List<Block> destroyed) {
        if (destroyed.isEmpty()) {
            return;
        }
        GriefWorld world = plugin.grief(destroyed.getFirst().getWorld());
        if (world == null) {
            return;
        }
        for (Block block : destroyed) {
            Protection.clearBlockAndPartner(plugin.generators(), world, block);
        }
    }

    private void logExplosionOutcomes(GriefWorld world, long explosionId, String source, List<BlockAudit> audits) {
        if (plugin.grief(world.bukkit()) != world) {
            plugin.getLogger().warning(auditPrefix(explosionId) + " post-check skipped; world is no longer tracked");
            return;
        }
        for (BlockAudit audit : audits) {
            Block block = audit.block();
            boolean broken = block.getType().isAir();
            int[] afterFaces = faceDurability(world, block);
            int afterShield = world.abi().pointsGet(
                    world.handle(), block.getX(), block.getY(), block.getZ());
            String outcome = broken
                    ? "BROKEN"
                    : block.getType() == audit.typeBefore()
                            ? "survived:" + block.getType()
                            : "changed:" + audit.typeBefore() + "->" + block.getType();
            String cause = audit.notes().isEmpty() ? "no note recorded" : String.join(" || ", audit.notes());
            plugin.getLogger().info(auditPrefix(explosionId) + " post source=" + source + " target="
                    + position(block) + " typeBefore=" + audit.typeBefore() + " outcome=" + outcome
                    + " decision=" + audit.decision()
                    + " facesBefore=" + faceSummary(audit.facesBefore())
                    + " facesAfter=" + faceSummary(afterFaces)
                    + " shieldBefore=" + audit.shieldBefore() + " shieldAfter=" + afterShield
                    + " notes=" + cause);
            if (!broken) {
                for (int i = 0; i < FACES.size(); i++) {
                    int before = audit.facesBefore()[i];
                    int after = afterFaces[i];
                    if (after < before && after < 31) {
                        plugin.getLogger().warning(auditPrefix(explosionId) + " durability_below_31 source="
                                + source + " target=" + position(block) + " face=" + FACES.get(i)
                                + " before=" + before + " after=" + after
                                + " cause=" + cause + " decision=" + audit.decision());
                    }
                }
            }
        }
    }

    private int[] faceDurability(GriefWorld world, Block block) {
        int[] durability = new int[FACES.size()];
        for (int i = 0; i < FACES.size(); i++) {
            int face = plugin.faces().toAbi(FACES.get(i));
            durability[i] = faceDurability(world, block, face);
        }
        return durability;
    }

    private int faceDurability(GriefWorld world, Block block, int face) {
        if (face == Abi.NO_FACE) {
            return 0;
        }
        return world.abi().reinfFaceDurability(
                world.handle(), block.getX(), block.getY(), block.getZ(), face);
    }

    private static String faceSummary(int[] durability) {
        List<String> faces = new ArrayList<>(FACES.size());
        for (int i = 0; i < FACES.size(); i++) {
            faces.add(FACES.get(i) + ":" + durability[i]);
        }
        return String.join(",", faces);
    }

    private static String position(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static String location(Location location) {
        return location.getX() + "," + location.getY() + "," + location.getZ();
    }


    private static String auditPrefix(long id) {
        return "[explosion-audit:" + id + "]";
    }

    private static double distanceSquared(Location origin, Block block) {
        double dx = block.getX() + 0.5 - origin.getX();
        double dy = block.getY() + 0.5 - origin.getY();
        double dz = block.getZ() + 0.5 - origin.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Returns the block face nearest the explosion origin, or SELF when the
     * origin is exactly at the block center.
     */
    static BlockFace faceToward(double originX, double originY, double originZ, int blockX, int blockY, int blockZ) {
        double dx = originX - (blockX + 0.5);
        double dy = originY - (blockY + 0.5);
        double dz = originZ - (blockZ + 0.5);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);

        if (ax == 0.0 && ay == 0.0 && az == 0.0) {
            return BlockFace.SELF;
        }
        if (ax >= ay && ax >= az) {
            return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        if (ay >= az) {
            return dy > 0 ? BlockFace.UP : BlockFace.DOWN;
        }
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    record BlockPosition(UUID world, int x, int y, int z) {
        static BlockPosition of(Block block) {
            return new BlockPosition(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private static final class BlockAudit {
        private final Block block;
        private final org.bukkit.Material typeBefore;
        private final int[] facesBefore;
        private final int shieldBefore;
        private final List<String> notes = new ArrayList<>();
        private String decision = "not_evaluated";

        private BlockAudit(Block block, org.bukkit.Material typeBefore, int[] facesBefore, int shieldBefore) {
            this.block = block;
            this.typeBefore = typeBefore;
            this.facesBefore = facesBefore;
            this.shieldBefore = shieldBefore;
        }

        private Block block() {
            return block;
        }

        private org.bukkit.Material typeBefore() {
            return typeBefore;
        }

        private int[] facesBefore() {
            return facesBefore;
        }

        private int shieldBefore() {
            return shieldBefore;
        }

        private List<String> notes() {
            return notes;
        }

        private String decision() {
            return decision;
        }

        private void addNote(String note) {
            notes.add(note);
        }

        private void setDecision(String decision) {
            this.decision = decision;
        }
    }
}
