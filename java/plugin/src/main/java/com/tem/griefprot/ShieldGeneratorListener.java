package com.tem.griefprot;

import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;

/**
 * The events a shield generator is built out of.
 *
 * <p>All of it is event plumbing for {@link ShieldGenerators}, which owns the
 * state and the rules. What is here is the part that has to be an event: the
 * click that spends the activation item, the slot the input does not have, and
 * the two furnace events that would otherwise be a generator's undoing.
 *
 * <p>Break and placement are deliberately absent. Both already route through
 * {@link Protection}, and dropping a generator's shield in there is what makes it
 * disappear on a break, an explosion, and a piston shove alike without any of
 * those listeners knowing shields exist.
 *
 * <p>Chunk load and unload are here too, for the same reason
 * {@link ChunkListener} owns them for the store: the mirror of what the server did
 * to its chunks has to be kept by exactly one listener, and a generator the
 * plugin has not noticed is a generator that is never fed.
 */
public final class ShieldGeneratorListener implements Listener {

    private final GriefProtPlugin plugin;
    private final ShieldGenerators generators;

    public ShieldGeneratorListener(GriefProtPlugin plugin) {
        this.plugin = plugin;
        generators = plugin.generators();
    }

    // --- becoming one ----------------------------------------------------------

    /**
     * Spends the activation item on a placed core block.
     *
     * <p>Both hands are accepted, because the item may be in either and there is
     * nothing here that decides which hand should hold it. The event is not
     * cancelled: the player is right clicking a furnace, so the furnace's own GUI
     * opens, and the fuel slot is the first thing they need to see.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onActivate(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != plugin.config().shieldCoreActivationItem()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !generators.isCore(block)) {
            return;
        }
        generators.activate(event.getPlayer(), block, event.getHand());
    }

    // --- not a furnace ---------------------------------------------------------

    /**
     * Refuses the input slot, which is what makes this a generator rather than a
     * furnace.
     *
     * <p>Only insertions are refused. A click that picks the slot up is how a
     * player recovers something a hopper left there, and refusing that too would
     * make the slot a place items go to be lost.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getRawSlot() != ShieldGenerators.INPUT_SLOT) {
            return;
        }
        if (generator(event.getView().getTopInventory()) == null) {
            return;
        }
        if (!inserts(event.getClick(), isEmpty(event.getCursor()))) {
            return;
        }
        event.setCancelled(true);
        refuse(event.getWhoClicked() instanceof Player player ? player : null);
    }

    /** The same refusal for a drag, which is how several stacks arrive at once. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!event.getRawSlots().contains(ShieldGenerators.INPUT_SLOT)) {
            return;
        }
        if (generator(event.getView().getTopInventory()) == null) {
            return;
        }
        event.setCancelled(true);
        refuse(event.getWhoClicked() instanceof Player player ? player : null);
    }

    /**
     * Whether a click puts something into the slot rather than taking it out.
     *
     * <p>An empty cursor means the player is picking the slot up, which is the
     * one thing that must still work. A number key, a swap or a drop carries an
     * item in even though the cursor looks empty, so those are insertions too.
     * Creative is refused as well: in a creative inventory a single click
     * fabricates a stack, and a generator's input slot is not a place to
     * fabricate things.
     *
     * @param cursorEmpty whether the player is holding anything in the cursor
     */
    static boolean inserts(ClickType click, boolean cursorEmpty) {
        return switch (click) {
            case NUMBER_KEY, SWAP_OFFHAND, DROP, CONTROL_DROP, UNKNOWN, CREATIVE -> true;
            case LEFT, RIGHT -> !cursorEmpty;
            // Shift clicking a furnace slot empties the cursor into it, so a shift
            // click here is a pickup and is left alone. Middle click takes half.
            default -> false;
        };
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    /**
     * Cancels the smelt, for anything that reached the input slot without a click.
     *
     * <p>{@link #onInventoryClick} closes the door to players; a hopper or a
     * dispenser does not go through the door. Nothing ever comes out of the
     * result slot, so a generator fed by a pipe costs its owner the input and
     * nothing else.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSmelt(FurnaceSmeltEvent event) {
        Furnace furnace = generatorFurnace(event.getBlock());
        if (furnace != null) {
            event.setCancelled(true);
            log(Level.INFO, "smelt_cancelled", event.getBlock(),
                    "source=" + event.getSource().getType() + " result=" + event.getResult().getType());
        }
    }

    /**
     * Stops the furnace lighting fuel for itself.
     *
     * <p>The shield burns its own fuel at its own rate, and it learns what was
     * put in by looking. A furnace that lit a coal would take it out of the slot
     * for a burn nothing counts, and the poll would find an empty slot and credit
     * nothing, so the fuel would simply disappear. Refusing at {@link
     * EventPriority#LOWEST} means nothing downstream gets a veto on the refusal.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBurn(FurnaceBurnEvent event) {
        if (generatorFurnace(event.getBlock()) == null) {
            return;
        }
        event.setCancelled(true);
        event.setBurning(false);
        event.setConsumeFuel(false);
        log(Level.INFO, "furnace_burn_cancelled", event.getBlock(),
                "fuel=" + event.getFuel().getType() + " burn_time=" + event.getBurnTime()
                        + " cancelled=" + event.isCancelled() + " burning=" + event.isBurning()
                        + " consume_fuel=" + event.willConsumeFuel());
    }

    // --- staying in step with the chunks ---------------------------------------

    /** Picks up generators the chunk was holding, once it is resident again. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        generators.scan(event.getChunk());
    }

    /**
     * Drops the chunk's generators from the poll's walk.
     *
     * <p>The shield itself is suspended rather than destroyed by
     * {@link com.tem.griefprot.world.GriefWorld}, so all that is lost here is the
     * block reference, which the chunk load will restore along with everything
     * else about it.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        generators.forget(event.getChunk());
    }

    // --- helpers ---------------------------------------------------------------

    /** The generator furnace a container belongs to, or null. */
    private Furnace generator(Inventory top) {
        if (!(top instanceof FurnaceInventory furnace)) {
            return null;
        }
        Furnace holder = furnace.getHolder();
        return ShieldGenerators.marked(holder) ? holder : null;
    }

    private Furnace generatorFurnace(Block block) {
        if (block.getState() instanceof Furnace furnace && ShieldGenerators.marked(furnace)) {
            return furnace;
        }
        return null;
    }

    private static void refuse(Player player) {
        if (player != null) {
            player.sendMessage(Component.text("A shield generator does not smelt. That slot takes nothing.")
                    .color(NamedTextColor.RED));
        }
    }

    private void log(Level level, String event, Block block, String details) {
        plugin.getLogger().log(level, () -> "shield-generator event=" + event + " world="
                + block.getWorld().getName() + " x=" + block.getX() + " y=" + block.getY() + " z=" + block.getZ()
                + " " + details);
    }
}