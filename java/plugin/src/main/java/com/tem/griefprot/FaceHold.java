package com.tem.griefprot;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The holds players have in progress on a block face.
 *
 * <p>Holding the button down re-fires {@code PlayerInteractEvent} several times a
 * second, which is the whole reason a click has to mean "start or continue" rather
 * than "start". A hold that restarted its clock on every one of those would never
 * fill, and a hold that ignored them would have no way to notice the player
 * walking away. So a repeat click for the same block, face, tool and sneaking
 * state keeps the clock where it was, and one for anything else replaces the hold.
 *
 * <p>The hand is not part of that identity, because a click produces one event per
 * hand and comparing it would make two brushes in two hands restart the clock on
 * every repeat. The stored hold takes the newer hand instead, so the tool is always
 * read from wherever the player is actually holding it.
 *
 * <p>Progress is measured on the wall clock from the first click rather than
 * counted in ticks, because the repeat cadence is the server's business and not
 * something the mechanic should depend on. Nothing here watches the button: the
 * caller decides what still counts as aiming at the target, and this only remembers
 * what it last agreed to.
 *
 * <p>Main thread only, like every Bukkit listener. One pending hold per player,
 * which is all a person with one crosshair can be doing at once.
 */
final class FaceHold {

    /** Bar width in characters, so the action bar stays about the same length. */
    private static final int BAR_CELLS = 10;

    private final Map<UUID, Pending> pending = new HashMap<>();

    /**
     * One player's hold in progress: what they are aiming at, with what, and since
     * when.
     *
     * <p>{@code world}, the coordinates and {@code face} describe the block that was
     * <em>clicked</em>, not where the action will land. Sneaking sends a plate to
     * the far side of that block or the one behind it, and resolving that per tick
     * would mean re-running the targeting rules against a world that may have moved
     * under the player. The click's own answer is used instead, and sneaking out
     * from under a hold is treated as walking away.
     */
    record Pending(
            UUID player,
            UUID world,
            int x,
            int y,
            int z,
            BlockFace face,
            FaceTool tool,
            EquipmentSlot hand,
            boolean sneaking,
            long startMillis) {

        /**
         * Whether another click means this same hold rather than a new one.
         *
         * <p>The hand is deliberately not part of this. A click produces one event
         * per hand, and comparing the hand would make two brushes in two hands
         * restart the clock on every repeat and never finish. Moving the tool
         * between hands is not walking away from a block either, so the stored hold
         * takes the newer hand instead.
         */
        boolean continues(Pending other) {
            return world.equals(other.world)
                    && x == other.x
                    && y == other.y
                    && z == other.z
                    && face == other.face
                    && tool == other.tool
                    && sneaking == other.sneaking;
        }

        /** The same hold, with its clock set to `millis`. */
        Pending startedAt(long millis) {
            return new Pending(player, world, x, y, z, face, tool, hand, sneaking, millis);
        }
    }

    /**
     * Starts a hold, or keeps the one already in progress.
     *
     * @return true when this is a new hold and the clock just started
     */
    boolean arm(Pending hold) {
        Pending current = pending.get(hold.player());
        if (current != null && current.continues(hold)) {
            // Same hold. The clock stays where the first click put it, but the rest
            // of the record is the newer one so the tool is read from whichever hand
            // it is in now.
            pending.put(hold.player(), hold.startedAt(current.startMillis()));
            return false;
        }
        pending.put(hold.player(), hold);
        return true;
    }

    /** The hold this player has going, or null. */
    Pending get(UUID player) {
        return pending.get(player);
    }

    /** Whether this player is mid hold, which the overlay has to stay quiet over. */
    boolean isPending(UUID player) {
        return pending.containsKey(player);
    }

    /** Every hold in progress. A copy, because the caller mutates the tracker. */
    Collection<Pending> pending() {
        return List.copyOf(pending.values());
    }

    boolean isEmpty() {
        return pending.isEmpty();
    }

    void cancel(UUID player) {
        pending.remove(player);
    }

    void clear() {
        pending.clear();
    }

    /** Whether a hold started at {@code startMillis} has run long enough by now. */
    static boolean due(Pending hold, long nowMillis, long holdMillis) {
        return nowMillis - hold.startMillis() >= holdMillis;
    }

    /** How much of the hold is left, 0..BAR_CELLS. */
    static int filled(Pending hold, long nowMillis, long holdMillis) {
        if (holdMillis <= 0) {
            return BAR_CELLS;
        }
        double done = (double) (nowMillis - hold.startMillis()) / holdMillis;
        return (int) Math.round(Math.max(0.0, Math.min(1.0, done)) * BAR_CELLS);
    }

    /**
     * The action bar line for a hold in progress.
     *
     * <p>The bar is the point: without it the player is staring at a block for a
     * second with no idea whether anything is happening, which is the same dead
     * feeling the hold was meant to avoid.
     */
    static String progress(Pending hold, long nowMillis, long holdMillis) {
        int cells = filled(hold, nowMillis, holdMillis);
        int percent = holdMillis <= 0 ? 100 : Math.min(100, cells * 100 / BAR_CELLS);
        StringBuilder bar = new StringBuilder(BAR_CELLS + 2);
        bar.append('|');
        bar.append("#".repeat(cells));
        bar.append("-".repeat(BAR_CELLS - cells));
        bar.append('|');
        return hold.tool().verb() + " " + bar + " " + percent + "%";
    }
}