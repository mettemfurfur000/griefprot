package com.tem.griefprot.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BukkitWorldPolicyTest {

    @Test
    void onlySolidMaterialsWithPositiveHardnessCanCarryProtection() {
        assertTrue(BukkitWorldPolicy.hasProtectionSurface(true, 1.5f));
        assertFalse(BukkitWorldPolicy.hasProtectionSurface(false, 1.5f));
        assertFalse(BukkitWorldPolicy.hasProtectionSurface(true, 0.0f));
        assertFalse(BukkitWorldPolicy.hasProtectionSurface(true, -1.0f));
    }
}
