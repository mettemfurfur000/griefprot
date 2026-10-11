package com.tem.griefprot.world;

import com.tem.griefprot.GriefProtPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The Bukkit implementation of {@link WorldPolicy}.
 *
 * <p>These rules keep the native store honest:
 *
 * <ul>
 *   <li>Only solid blocks with positive hardness can be protected. Letting a
 *       shield sit on air or a zero-hardness plant would spend fuel protecting
 *       a position that cannot meaningfully retain protection.
 *   <li>Protection is refused for blocks the world can replace on its own, such
 *       as fluids, so a record cannot outlive the block it describes.
 * </ul>
 */
public final class BukkitWorldPolicy implements WorldPolicy {

    private static final Set<Material> ALWAYS_REFUSED = EnumSet.of(
            Material.AIR,
            Material.CAVE_AIR,
            Material.VOID_AIR,
            Material.WATER,
            Material.LAVA);

    private final GriefProtPlugin plugin;

    /** Materials the player is allowed to reinforce. Empty means "all solid blocks". */
    private final Set<Material> allowed = new HashSet<>();

    /** How many points a covering shield hands out per window. */
    private final int grantAmount;

    public BukkitWorldPolicy(GriefProtPlugin plugin) {
        this.plugin = plugin;
        this.grantAmount = Math.max(1, plugin.config().shieldPointsPerWindow());
        loadAllowedMaterials();
    }

    private void loadAllowedMaterials() {
        allowed.clear();
        for (String raw : plugin.config().protectableMaterials()) {
            Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                plugin.getLogger().warning("unknown material in config, ignoring: " + raw);
                continue;
            }
            if (ALWAYS_REFUSED.contains(material)) {
                plugin.getLogger().warning(material + " can never be protected, ignoring");
                continue;
            }
            if (!hasProtectionSurface(material)) {
                plugin.getLogger().warning(material + " is not solid with positive hardness, ignoring");
                continue;
            }
            allowed.add(material);
        }
    }

    @Override
    public boolean protectable(GriefWorld world, int x, int y, int z) {
        if (world.bukkit().getMinHeight() > y || y >= world.bukkit().getMaxHeight()) {
            return false;
        }
        Block block = world.bukkit().getBlockAt(x, y, z);
        return isProtectable(block);
    }

    /** Whether a block may carry protection, ignoring position. */
    public boolean isProtectable(Block block) {
        Material material = block.getType();
        if (ALWAYS_REFUSED.contains(material)) {
            return false;
        }
        if (!hasProtectionSurface(material)) {
            return false;
        }
        return allowed.isEmpty() || allowed.contains(material);
    }

    static boolean hasProtectionSurface(Material material) {
        return hasProtectionSurface(material.isSolid(), material.getHardness());
    }

    static boolean hasProtectionSurface(boolean solid, float hardness) {
        return solid && hardness > 0;
    }

    @Override
    public int grant(GriefWorld world, int x, int y, int z, int amount, int cap) {
        int offered = Math.min(Math.min(amount, cap), grantAmount);
        if (offered <= 0) {
            return 0;
        }
        // The native shield accounts only for what the host actually stored.
        return world.abi().pointsAdd(world.handle(), x, y, z, offered, cap);
    }

    @Override
    public void cover(GriefWorld world, int x, int y, int z, long now) {
        // Nothing observable by default. The library has already recorded the
        // cover, so a host that wants particles or a message hooks here.
    }

    /** Location of a native position, for callers that need to talk to Bukkit. */
    public static Location locate(World world, int x, int y, int z) {
        return new Location(world, x, y, z);
    }
}