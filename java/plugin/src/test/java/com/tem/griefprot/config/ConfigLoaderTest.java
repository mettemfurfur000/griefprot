package com.tem.griefprot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

    @Test
    void shieldRegenPointsPerWindowHasAnExplicitKeyAndLegacyAlias() {
        YamlConfiguration config = new YamlConfiguration();
        assertEquals(20, ConfigLoader.load(config).shieldRegenPointsPerWindow());

        config.set("shield-regen-points-per-window", 7);
        config.set("shield-regen-seconds", 3);
        assertEquals(7, ConfigLoader.load(config).shieldRegenPointsPerWindow());

        config.set("shield-regen-points-per-window", null);
        assertEquals(3, ConfigLoader.load(config).shieldRegenPointsPerWindow());
    }

    @Test
    void oppositeSideDefaultsToSameBlockAndParsesEveryMode() {
        YamlConfiguration config = new YamlConfiguration();
        // Anything else leaves reinforcement only on faces the builder can see,
        // which is the problem the setting exists to solve.
        assertEquals(OppositeSide.SAME_BLOCK, ConfigLoader.load(config).oppositeSide());

        config.set("opposite-side-placement", "through");
        assertEquals(OppositeSide.THROUGH, ConfigLoader.load(config).oppositeSide());

        config.set("opposite-side-placement", "off");
        assertEquals(OppositeSide.OFF, ConfigLoader.load(config).oppositeSide());

        // Case and padding should not decide whether a setting works.
        config.set("opposite-side-placement", "  THROUGH ");
        assertEquals(OppositeSide.THROUGH, ConfigLoader.load(config).oppositeSide());
    }

    @Test
    void oppositeSideFallsBackOnAnythingUnrecognised() {
        YamlConfiguration config = new YamlConfiguration();

        config.set("opposite-side-placement", "sideways");
        assertEquals(OppositeSide.SAME_BLOCK, ConfigLoader.load(config).oppositeSide());

        config.set("opposite-side-placement", true);
        assertEquals(OppositeSide.SAME_BLOCK, ConfigLoader.load(config).oppositeSide());
    }

    @Test
    void debugOverlayRadiusDefaultsAndAcceptsConfiguredRange() {
        YamlConfiguration config = new YamlConfiguration();
        assertEquals(2, ConfigLoader.load(config).debugOverlayRadius());

        config.set("debug-overlay-radius", 32);
        assertEquals(32, ConfigLoader.load(config).debugOverlayRadius());
    }

    @Test
    void debugOverlayRadiusFallsBackOutsideSupportedRange() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("debug-overlay-radius", 33);
        assertEquals(2, ConfigLoader.load(config).debugOverlayRadius());
    }

    @Test
    void toolDamageMultiplierDefaultsHighAndAcceptsAFraction() {
        YamlConfiguration config = new YamlConfiguration();
        // Not the item's own rate of 1: that is invisible on any real tool.
        assertEquals(20.0, ConfigLoader.load(config).toolDamageMultiplier());

        config.set("tool-damage-multiplier", 4.5);
        assertEquals(4.5, ConfigLoader.load(config).toolDamageMultiplier());

        // 0 is the documented way to turn tool wear off, so it must survive
        config.set("tool-damage-multiplier", 0.0);
        assertEquals(0.0, ConfigLoader.load(config).toolDamageMultiplier());
    }

    @Test
    void toolDamageMultiplierFallsBackOutsideTheRangeOrNotANumber() {
        YamlConfiguration config = new YamlConfiguration();

        config.set("tool-damage-multiplier", 101.0);
        assertEquals(20.0, ConfigLoader.load(config).toolDamageMultiplier());

        config.set("tool-damage-multiplier", -1.0);
        assertEquals(20.0, ConfigLoader.load(config).toolDamageMultiplier());

        // YAML parses these into real values, and both compare false against
        // every bound, so a range test on its own would wave them through.
        config.set("tool-damage-multiplier", Double.NaN);
        assertEquals(20.0, ConfigLoader.load(config).toolDamageMultiplier());

        config.set("tool-damage-multiplier", Double.POSITIVE_INFINITY);
        assertEquals(20.0, ConfigLoader.load(config).toolDamageMultiplier());
    }

    @Test
    void brushHoldDefaultsToASecondAndAcceptsItsRange() {
        YamlConfiguration config = new YamlConfiguration();
        assertEquals(20, ConfigLoader.load(config).brushHoldTicks());

        config.set("brush-hold-ticks", 40);
        assertEquals(40, ConfigLoader.load(config).brushHoldTicks());

        config.set("brush-hold-ticks", 1);
        assertEquals(1, ConfigLoader.load(config).brushHoldTicks(), "instant should still be a hold");
    }

    @Test
    void brushHoldFallsBackOutsideItsRange() {
        YamlConfiguration config = new YamlConfiguration();

        // Zero would put the hold back to being a plain click, which is the thing
        // the delay exists to stop.
        config.set("brush-hold-ticks", 0);
        assertEquals(20, ConfigLoader.load(config).brushHoldTicks());

        config.set("brush-hold-ticks", -5);
        assertEquals(20, ConfigLoader.load(config).brushHoldTicks());

        config.set("brush-hold-ticks", 5000);
        assertEquals(20, ConfigLoader.load(config).brushHoldTicks());
    }
}
