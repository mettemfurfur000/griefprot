package com.tem.griefprot.config;

import java.util.List;
import org.bukkit.Material;

/**
 * Tunables for the plugin, read once at startup.
 *
 * <p>The thirteen numeric fields map one for one onto {@code gp_abi_world_config}.
 * They live here rather than being read ad hoc so that a value the library later
 * clamps is caught at startup instead of showing up as mysterious behaviour.
 *
 * @param maxFacesPerBlock how many faces may carry reinforcement at once
 * @param shieldPool how many shields one dimension may hold
 * @param shieldRegenPointsPerWindow total points the shield may grant each window
 * @param shieldWindow game seconds a shield protects before it must recharge
 * @param shieldRange radius in blocks a shield reaches
 * @param shieldMaxTargets how many blocks one shield may cover
 * @param shieldFuelMax fuel a full shield holds
 * @param shieldFuelPerSec fuel units burned per game second while running
 * @param decayInterval game seconds before an uncovered record decays
 * @param decayAmount how much one decay sweep removes
 * @param copperBreaks break events the copper tier absorbs
 * @param ironBreaks break events the iron tier absorbs
 * @param shieldPointsPerWindow how many points a covering shield hands out
 * @param protectableMaterials materials players may reinforce, empty for all solid
 * @param copperTierPoints reinforcement value granted by one copper plate
 * @param ironTierPoints reinforcement value granted by one iron plate
 * @param debugOverlayRadius block radius rendered by the reinforcement debug overlay
 * @param toolDamageMultiplier scales the tool durability an absorbed break costs
 * @param oppositeSide where a sneaking plate lands, see {@link OppositeSide}
 * @param brushHoldTicks how long a face tool must be held before it takes effect
 * @param shieldCoreMaterial the block a player turns into a shield generator
 * @param shieldCoreActivationItem the item that turns it
 */
public record PluginConfig(
        int maxFacesPerBlock,
        int shieldPool,
        int shieldRegenPointsPerWindow,
        int shieldWindow,
        int shieldRange,
        int shieldMaxTargets,
        int shieldFuelMax,
        int shieldFuelPerSec,
        int decayInterval,
        int decayAmount,
        int copperBreaks,
        int ironBreaks,
        int shieldPointsPerWindow,
        List<String> protectableMaterials,
        int copperTierPoints,
        int ironTierPoints,
        int debugOverlayRadius,
        double toolDamageMultiplier,
        OppositeSide oppositeSide,
        int brushHoldTicks,
        Material shieldCoreMaterial,
        Material shieldCoreActivationItem) {}