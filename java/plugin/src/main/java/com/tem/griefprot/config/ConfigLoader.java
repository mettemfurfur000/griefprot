package com.tem.griefprot.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Reads {@link PluginConfig} out of a Bukkit config, clamping anything the
 * native library would refuse.
 *
 * <p>Clamping here rather than letting the library do it keeps the failure at
 * startup with a message naming the key, instead of surfacing later as protection
 * that mysteriously stops working.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /**
     * Largest reinforcement value a face can hold.
     *
     * <p>The library stores durability in a fixed width field and refuses anything
     * wider, so a tier configured above this would be accepted here and then
     * silently rejected when the plate is placed. Fall back instead, which is the
     * same treatment every other out of range value here gets.
     */
    private static final int DURABILITY_MAX = 4095;
    private static final int DEBUG_OVERLAY_RADIUS_MAX = 32;

    public static PluginConfig load(FileConfiguration config) {
        return load(config, message -> {});
    }

    /**
     * @param warn told about a key that named nothing usable, so the admin sees
     *     the fallback instead of discovering it later as a missing mechanic
     */
    public static PluginConfig load(FileConfiguration config, Consumer<String> warn) {
        return new PluginConfig(
                atLeast(config, "max-faces-per-block", 4, 1),
                atLeast(config, "shield-pool", 4096, 1),
                shieldRegenPointsPerWindow(config, warn),
                atLeast(config, "shield-window-seconds", 300, 1),
                atLeast(config, "shield-range", 4, 0),
                atLeast(config, "shield-max-targets", 64, 1),
                atLeast(config, "shield-fuel-max", 64, 1),
                atLeast(config, "shield-fuel-per-second", 1, 0),
                atLeast(config, "decay-interval-seconds", 300, 1),
                atLeast(config, "decay-amount", 1, 1),
                atLeast(config, "copper-breaks", 32, 1),
                atLeast(config, "iron-breaks", 256, 1),
                atLeast(config, "shield-points-per-window", 1, 1),
                strings(config, "protectable-materials"),
                atMost(config, "copper-tier-points", 32, DURABILITY_MAX),
                atMost(config, "iron-tier-points", 256, DURABILITY_MAX),
                within(config, "debug-overlay-radius", 2, 0, DEBUG_OVERLAY_RADIUS_MAX),
                decimal(config, "tool-damage-multiplier", 20.0, 0.0, 100.0),
                OppositeSide.parse(config.getString("opposite-side-placement"), OppositeSide.SAME_BLOCK),
                // 20 ticks is one second. Long enough that a misclick resolves
                // before anything is spent, short enough that reinforcing a wall
                // does not feel like a chore.
                within(config, "brush-hold-ticks", 20, 1, 200),
                material(config, "shield-core-material", Material.BLAST_FURNACE, true, warn),
                material(config, "shield-core-activation-item", Material.NETHER_STAR, false, warn));
    }

    private static int shieldRegenPointsPerWindow(FileConfiguration config, Consumer<String> warn) {
        String key = "shield-regen-points-per-window";
        if (config.contains(key, true)) {
            return atLeast(config, key, 20, 1);
        }
        if (config.contains("shield-regen-seconds", true)) {
            warn.accept("'shield-regen-seconds' is a legacy point-budget key, not a duration; "
                    + "use 'shield-regen-points-per-window'. The duration is 'shield-window-seconds'.");
            return atLeast(config, "shield-regen-seconds", 20, 1);
        }
        return 20;
    }

    private static int atLeast(FileConfiguration config, String key, int fallback, int minimum) {
        int value = config.getInt(key, fallback);
        if (value < minimum) {
            return fallback;
        }
        return value;
    }

    private static int atMost(FileConfiguration config, String key, int fallback, int maximum) {
        int value = config.getInt(key, fallback);
        if (value < 1 || value > maximum) {
            return fallback;
        }
        return value;
    }

    private static int within(FileConfiguration config, String key, int fallback, int minimum, int maximum) {
        int value = config.getInt(key, fallback);
        return value < minimum || value > maximum ? fallback : value;
    }

    /**
     * Reads a fractional value, rejecting anything the range does not cover.
     *
     * <p>The non-finite check is not decoration: YAML reads {@code .nan} and
     * {@code .inf} as real values, and both compare false against every bound,
     * so a range test alone would wave them through to reach the damage
     * arithmetic.
     *
     * <p>The default is 20 rather than the item's own rate of 1 because that
     * rate is a flat 1 for every vanilla tool, which on a diamond pickaxe is one
     * durability in 1561 and never moves the bar. See the key in config.yml.
     */
    private static double decimal(FileConfiguration config, String key, double fallback, double minimum, double maximum) {
        double value = config.getDouble(key, fallback);
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            return fallback;
        }
        return value;
    }

    private static List<String> strings(FileConfiguration config, String key) {
        if (!config.isList(key)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object raw : config.getList(key)) {
            if (raw instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Reads a material name, falling back when it names nothing usable.
     *
     * <p>{@code asBlock} is the difference between the two keys: the core has to
     * be something a player can place and a shield can anchor to, and the
     * activation item has to be something a player can hold. A mistyped key that
     * quietly resolved to nothing would leave the shield generator with no way to
     * exist at all, and no symptom other than its absence.
     */
    private static Material material(
            FileConfiguration config, String key, Material fallback, boolean asBlock, Consumer<String> warn) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            warn.accept(key + " must name a material; using " + fallback);
            return fallback;
        }
        Material material = Material.matchMaterial(text.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            warn.accept(key + " names no material (" + text + "); using " + fallback);
            return fallback;
        }
        if (asBlock && !material.isBlock()) {
            warn.accept(key + " must be a block, but " + material + " is an item; using " + fallback);
            return fallback;
        }
        if (!asBlock && !material.isItem()) {
            warn.accept(key + " must be an item, but " + material + " is a block; using " + fallback);
            return fallback;
        }
        return material;
    }
}