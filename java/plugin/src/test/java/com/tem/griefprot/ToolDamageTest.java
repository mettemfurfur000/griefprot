package com.tem.griefprot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolDamageTest {

    @Test
    @DisplayName("a charge is the tool's own per-block rate scaled by the multiplier")
    void multiplierScalesThePerBlockRate() {
        assertEquals(3, ToolDamage.scaled(3, 1.0));
        assertEquals(6, ToolDamage.scaled(3, 2.0));
        assertEquals(12, ToolDamage.scaled(3, 4.0));
    }

    @Test
    @DisplayName("a fractional multiplier rounds up so it never silently becomes free")
    void fractionalMultipliersRoundUp() {
        // Truncating here would make 0.4x charge nothing for a tool declaring 1
        // per block, so turning the setting down would remove the raid cost
        // entirely rather than making it cheaper.
        assertEquals(1, ToolDamage.scaled(1, 0.4));
        assertEquals(1, ToolDamage.scaled(1, 0.01));
        assertEquals(2, ToolDamage.scaled(3, 0.4));
    }

    @Test
    @DisplayName("a zero or negative multiplier charges nothing")
    void nonPositiveMultipliersChargeNothing() {
        assertEquals(0, ToolDamage.scaled(3, 0.0));
        assertEquals(0, ToolDamage.scaled(3, -2.0));
    }

    @Test
    @DisplayName("an item that declares no per-block cost is never charged")
    void nothingToChargeIsNotCharged() {
        assertEquals(0, ToolDamage.scaled(0, 1.0));
        assertEquals(0, ToolDamage.scaled(-1, 1.0));
    }

    @Test
    @DisplayName("a multiplier of NaN charges nothing, but an unspeakable one saturates")
    void nonFiniteMultipliers() {
        // YAML reads .nan as a real value and it compares false against every
        // bound, so it can reach the arithmetic. There is no such number to
        // multiply, so it charges nothing.
        assertEquals(0, ToolDamage.scaled(3, Double.NaN));

        // An infinity is a multiplier too large to compute, which is a tool about
        // to be destroyed rather than a free raid. Saturating matters: clamping
        // it to 0 here would hand the attacker back the whole cost.
        assertEquals(Integer.MAX_VALUE, ToolDamage.scaled(3, Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("an absurd multiplier saturates instead of overflowing to a negative charge")
    void absurdMultipliersSaturate() {
        // 3 * MAX_VALUE overflows to infinity, so this is the same saturation
        // reached without going through an infinite multiplier.
        assertEquals(Integer.MAX_VALUE, ToolDamage.scaled(3, Double.MAX_VALUE));
    }
}