package com.tem.griefprot;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The items that act on a block face, and what each of them does to it.
 *
 * <p>Every one of these has to be held against a block for a moment before it
 * takes effect. That is not a lag guard, it is the misclick guard: a nugget used
 * to plate the block under the crosshair the instant the button went down, which
 * meant a stray right click while reaching for something else quietly spent a
 * plate and made a face harder to break than its owner meant. Holding the tool
 * still is a decision the player can see they are making.
 *
 * <p>The items are deliberately not interchangeable, and none of them can be
 * undone in game. All three are one way, so an admin command is the only way
 * back; see {@code /griefprot admin protection unlock}.
 */
enum FaceTool {

    /**
     * Reinforces the aimed face, upgrading whatever is already there.
     *
     * <p>Needs a nugget in the other hand to say which tier, because there is only
     * one brush in the game and it says nothing about how much protection a face
     * should end up with. Both hands are available for the brush, so a player who
     * wants the nugget in the offhand can have it there.
     */
    BRUSH(Material.BRUSH, "Reinforcing", true),

    /**
     * Stops the block taking reinforcement on any face it does not already carry.
     *
     * <p>For a build that has to stay movable: a redstone contraption whose
     * pistons need to shift blocks, where one accidental plate, or one planted by
     * someone who wanted the build to stop working, would quietly take that away.
     * Existing faces keep upgrading, so the lock costs the owner nothing they
     * already had.
     */
    LOCK_FACES(Material.FLINT_AND_STEEL, "Locking", false),

    /**
     * Waives the library's push and pull rules for the block, so a piston moves it
     * like it moves anything else. The protection travels with it.
     *
     * <p>The other half of {@link #LOCK_FACES}: a reinforced block that still has
     * to be part of a moving contraption.
     */
    PISTON_FREE(Material.SLIME_BALL, "Slicking", false);

    private static final FaceTool[] VALUES = values();

    private final Material material;
    private final String verb;
    private final boolean needsTierItem;

    FaceTool(Material material, String verb, boolean needsTierItem) {
        this.material = material;
        this.verb = verb;
        this.needsTierItem = needsTierItem;
    }

    Material material() {
        return material;
    }

    /** What the progress bar calls this while it is filling. */
    String verb() {
        return verb;
    }

    /** Whether the other hand has to hold the nugget that picks the tier. */
    boolean needsTierItem() {
        return needsTierItem;
    }

    /** The tool this item starts, or null when it starts nothing. */
    static FaceTool forMaterial(Material material) {
        for (FaceTool tool : VALUES) {
            if (tool.material == material) {
                return tool;
            }
        }
        return null;
    }

    /** The hand opposite the one holding {@code tool}. */
    static EquipmentSlot otherHand(EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
    }
}