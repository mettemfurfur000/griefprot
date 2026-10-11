package com.tem.griefprot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The parts of a shield generator that are arithmetic rather than server state.
 *
 * <p>Neither of these needs a running server: {@link ShieldGenerators#burnSeconds}
 * only reaches for a tag once the item is not named in its table, and
 * {@link ShieldGeneratorListener#inserts} is told whether the cursor is empty
 * rather than being handed the stack. That split is the same one
 * {@code BukkitWorldPolicy.hasProtectionSurface} makes for the same reason.
 */
class ShieldGeneratorTest {

    @Nested
    @DisplayName("fuel is worth the furnace seconds it would have burned for")
    class FuelValue {

        @Test
        void theTiersPlayersCarryAreVanillaAccurate() {
            // These are the numbers the library drains at shield-fuel-per-second
            // per second, so a coal is 80 seconds of running time and nothing
            // about the fuel slot is a rescaled second rate.
            assertEquals(80, ShieldGenerators.burnSeconds(Material.COAL));
            assertEquals(80, ShieldGenerators.burnSeconds(Material.CHARCOAL));
            assertEquals(120, ShieldGenerators.burnSeconds(Material.BLAZE_ROD));
            assertEquals(800, ShieldGenerators.burnSeconds(Material.COAL_BLOCK));
            assertEquals(1000, ShieldGenerators.burnSeconds(Material.LAVA_BUCKET));
            assertEquals(200, ShieldGenerators.burnSeconds(Material.DRIED_KELP_BLOCK));
        }

        @Test
        void theCheapFuelsAreWorthLessThanACoal() {
            assertEquals(5, ShieldGenerators.burnSeconds(Material.STICK));
            assertEquals(2, ShieldGenerators.burnSeconds(Material.BAMBOO));
            assertEquals(15, ShieldGenerators.burnSeconds(Material.BAMBOO_BLOCK));
        }

        @Test
        void unsupportedBurnablesAreRefused() {
            assertFalse(ShieldGenerators.BURN_SECONDS.containsKey(Material.CRAFTING_TABLE));
            assertFalse(ShieldGenerators.BURN_SECONDS.containsKey(Material.IRON_INGOT));
        }

        @Test
        @DisplayName("the wood family is reached through a tag, not a table")
        void theWoodFamilyIsCoveredByTagsRatherThanEnumerated() {
            /*
             * Vanilla values several dozen wooden blocks one at a time and none of
             * them is worth more than a plank, so they are reached through the
             * item tags instead. Those tags are read from the server's registry,
             * which is exactly what a unit test has no way to stand up, so this
             * pins the intent rather than the lookup: planks and logs are 15
             * seconds and a wooden slab is half a plank.
             */
            assertEquals(ShieldGenerators.WOOD_SECONDS, 15);
            assertEquals(ShieldGenerators.WOOD_SLAB_SECONDS, 7);
        }
    }

    @Nested
    @DisplayName("the input slot refuses insertions but still gives things back")
    class InputSlot {

        @Test
        void anEmptyCursorIsAPickupAndMustNotBeRefused() {
            // Refusing this would make the slot a place items go to be lost: a
            // hopper can put something there without a click, and the only way
            // to get it back would be the click this refuses.
            assertFalse(ShieldGeneratorListener.inserts(ClickType.LEFT, true));
            assertFalse(ShieldGeneratorListener.inserts(ClickType.RIGHT, true));
        }

        @Test
        void aShiftClickOutOfTheSlotIsAlsoAPickup() {
            // Shifting from a furnace slot empties the cursor into the world, not
            // into the slot, so this is the player taking something away.
            assertFalse(ShieldGeneratorListener.inserts(ClickType.SHIFT_LEFT, true));
            assertFalse(ShieldGeneratorListener.inserts(ClickType.SHIFT_RIGHT, true));
        }

        @Test
        void holdingSomethingAndClickingPutsItIn() {
            assertTrue(ShieldGeneratorListener.inserts(ClickType.LEFT, false));
            assertTrue(ShieldGeneratorListener.inserts(ClickType.RIGHT, false));
        }

        @Test
        void clicksThatCarryAnItemInWithAnEmptyCursorAreStillInsertions() {
            // None of these move the cursor, so an empty one says nothing about
            // whether the slot ends up with something in it.
            assertTrue(ShieldGeneratorListener.inserts(ClickType.NUMBER_KEY, true));
            assertTrue(ShieldGeneratorListener.inserts(ClickType.SWAP_OFFHAND, true));
            assertTrue(ShieldGeneratorListener.inserts(ClickType.DROP, true));
            assertTrue(ShieldGeneratorListener.inserts(ClickType.CONTROL_DROP, true));
            assertTrue(ShieldGeneratorListener.inserts(ClickType.UNKNOWN, true));
        }

        @Test
        @DisplayName("creative cannot fabricate a stack into the slot")
        void creativeIsRefused() {
            // A generator's input slot is not somewhere to conjure items, even for
            // someone who may conjure anything else.
            assertTrue(ShieldGeneratorListener.inserts(ClickType.CREATIVE, true));
        }

        @Test
        void aMiddleClickTakesHalfAndIsLeftAlone() {
            assertFalse(ShieldGeneratorListener.inserts(ClickType.MIDDLE, true));
        }
    }
}