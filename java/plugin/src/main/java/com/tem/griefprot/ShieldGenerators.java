package com.tem.griefprot;

import com.tem.griefprot.nativebridge.ShieldState;
import com.tem.griefprot.world.GriefWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Turns a placed block into a shield, and keeps that shield fed.
 *
 * <p>
 * A shield is otherwise unreachable: the library has an allocator and the admin
 * command has a subcommand, and nothing in between. This is the missing piece
 * --
 * a player places {@code shield-core-material}, spends {@code
 * shield-core-activation-item} on it, and from then on the block is a generator
 * whose fuel slot feeds a real shield anchored at the block.
 *
 * <h2>The block is the truth</h2>
 *
 * <p>
 * A generator is marked with a persistent data key on the block entity, so the
 * marker survives a restart, a chunk unload, and being walked away from. The
 * marker is what {@link #scan} looks for on chunk load, and the shield handle
 * is
 * rebuilt from it if the store has lost it. That ordering matters: the store is
 * where fuel and coverage live, but the block is what the player built, and
 * anything that leaves the two disagreeing resolves in favour of the block.
 *
 * <p>
 * It is also why {@link #onBlockGone} destroys the shield. A shield that
 * outlives its block keeps covering whatever was placed in the same
 * coordinates,
 * which is the same coordinate-keyed leak {@link Protection} exists to close
 * for
 * reinforcement.
 *
 * <h2>Why the fuel slot is polled rather than listened for</h2>
 *
 * <p>
 * Nothing fires when an item arrives in a furnace fuel slot unless the furnace
 * is about to light it, and a furnace only lights fuel when it has something to
 * smelt -- which is exactly what this generator refuses to have. So the poll is
 * the only signal that catches every route in: a player clicking, a hopper
 * dropping fuel down the top of it, a dispenser emptying into it. Once a second
 * is
 * far quicker than anyone can tell.
 *
 * <p>
 * That same rule is what makes the poll safe to run as the only thing touching
 * the slot. {@code FurnaceBurnEvent} is cancelled for a generator
 * unconditionally, so the furnace should never take an item out from under it:
 * every item that leaves that slot left because the generator wanted it to, and
 * the shield's fuel is the reason it went. {@link #refill} carries a second
 * path
 * for the day it does not hold, which credits whatever gauge the furnace left
 * rather than losing the coal.
 *
 * <h2>Fuel is vanilla burn time</h2>
 *
 * <p>
 * One fuel unit is one furnace second, and the shield drains
 * {@code shield-fuel-per-second} of them per game second, so a coal is 80 units and
 * runs a generator for 80 seconds. Fuel tiers therefore mean what they mean in
 * a
 * furnace, and neither knob has to be re-invented.
 *
 * <p>
 * Paper exposes no way to ask the server how long an item burns, so
 * {@link #burnSeconds} is a table. It names the fuels a player actually carries
 * and refuses everything else, so an unfamiliar item from another pack cannot
 * become fuel by accident.
 */
public final class ShieldGenerators {

    /**
     * The furnace slot that takes the thing to smelt. A generator refuses this
     * one, which is what stops the furnace ever lighting the fuel on its own.
     */
    public static final int INPUT_SLOT = 0;

    /** The furnace slot that takes fuel, and the one the shield is fed from. */
    public static final int FUEL_SLOT = 1;

    // result slot is 2, but it is never used and the generator refuses it anyway

    /**
     * Vanille furnace burn times in seconds, for the fuels worth naming.
     *
     * <p>
     * From the game's own smelting table. Everything outside this and the tag
     * checks in {@link #burnSeconds} is refused.
     */
    static final Map<Material, Integer> BURN_SECONDS = Map.ofEntries(
            Map.entry(Material.LAVA_BUCKET, 1000),
            Map.entry(Material.COAL_BLOCK, 800),
            Map.entry(Material.DRIED_KELP_BLOCK, 200),
            Map.entry(Material.BLAZE_ROD, 120),
            Map.entry(Material.COAL, 80),
            Map.entry(Material.CHARCOAL, 80),
            Map.entry(Material.BAMBOO_BLOCK, 15),
            Map.entry(Material.STRIPPED_BAMBOO_BLOCK, 15),
            Map.entry(Material.STICK, 5),
            Map.entry(Material.BOWL, 5),
            Map.entry(Material.SCAFFOLDING, 2),
            Map.entry(Material.BAMBOO, 2));

    /** A whole log, plank or wooden stair in vanilla. */
    static final int WOOD_SECONDS = 15;

    /** Vanilla gives a wooden slab half a plank, and fuel is whole seconds. */
    static final int WOOD_SLAB_SECONDS = 7;

    private static final NamespacedKey MARKER = new NamespacedKey("griefprot", "shield_generator");
    private static final byte MARKED = 1;

    private final GriefProtPlugin plugin;

    /**
     * Generators per world, in the order they were found.
     *
     * <p>
     * Only generators in loaded chunks are listed, so the fuel poll walks a few
     * blocks rather than every loaded chunk's block entities.
     */
    private final Map<UUID, List<Generator>> byWorld = new HashMap<>();

    public ShieldGenerators(GriefProtPlugin plugin) {
        this.plugin = plugin;
    }

    // --- identity --------------------------------------------------------------

    /** Whether this block is the one a generator is made from. */
    public boolean isCore(Block block) {
        return block.getType() == plugin.config().shieldCoreMaterial();
    }

    /** Whether this block has already been turned into a generator. */
    public boolean isGenerator(Block block) {
        return isCore(block) && block.getState() instanceof Furnace furnace && marked(furnace);
    }

    /** Whether a shield generator is standing at this position. */
    public boolean isGeneratorAt(World world, int x, int y, int z) {
        return isGenerator(world.getBlockAt(x, y, z));
    }

    /**
     * Whether a furnace has the generator marker on it.
     *
     * <p>
     * Separate from {@link #isGenerator} because the inventory events already
     * hold the block entity and re-deriving it from the block would be a second
     * lookup for the same answer.
     */
    public static boolean marked(Furnace furnace) {
        return furnace.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE);
    }

    // --- becoming a generator --------------------------------------------------

    /**
     * Turns a placed core block into a shield generator, spending the activation
     * item on it.
     *
     * <p>
     * The shield is created before the block is marked, so a refused create --
     * a shield already anchored here, a full shield pool -- leaves a plain furnace
     * rather than a marked block with no shield behind it, which is the one
     * arrangement nothing else here could recover from.
     *
     * <p>
     * The activation item is spent, not banked. Breaking the generator later
     * drops what the block itself drops and nothing more, so the item is the
     * standing price of having a generator rather than something the owner can
     * take back by wrecking their own work. Creative keeps it, matching every
     * other tool here.
     */
    public void activate(Player player, Block block, EquipmentSlot hand) {
        GriefWorld world = plugin.grief(block);
        if (world == null) {
            log(Level.WARNING, "activate_failed", block, "reason=world_not_tracked");
            return;
        }
        BlockState state = block.getState();
        if (!(state instanceof Furnace furnace)) {
            log(Level.WARNING, "activate_failed", block, "reason=not_furnace state=" + state.getType());
            return;
        }
        if (marked(furnace)) {
            log(Level.INFO, "activate_skipped", block, "reason=already_marked");
            refuse(player, "That is already a shield generator.");
            return;
        }

        FurnaceInventory inventory = furnace.getInventory();
        if (inventory.getSmelting() != null)
            if (!inventory.getSmelting().isEmpty() || !inventory.getResult().isEmpty()) {
                refuse(player, "Empty its input and output slots before turning it into a shield generator."
                        + " Nothing was spent.");
                return;
            }

        int handle = world.abi().shieldCreate(world.handle(), block.getX(), block.getY(), block.getZ());
        if (handle == 0) {
            log(Level.WARNING, "activate_failed", block, "reason=shield_create_failed error=" + plugin.abi().lastError());
            refuse(player, "That shield could not be made: " + plugin.abi().lastError());
            return;
        }

        furnace.getPersistentDataContainer().set(MARKER, PersistentDataType.BYTE, MARKED);
        furnace.setBurnTime((short) 0);
        boolean markedUpdated = furnace.update(false, false);
        if (!markedUpdated) {
            log(Level.WARNING, "activate_update_failed", block, "operation=write_generator_marker");
        }

        // A shield from the library starts full, which is what an admin creating
        // one wants. A generator is nothing until somebody puts fuel in it.
        world.abi().shieldSetFuel(handle, 0);
        world.trackShield(handle);
        register(world, block, handle);
        log(Level.INFO, "activated", block, "shield=" + handle + " fuel=0 marker_update=" + markedUpdated);

        player.sendMessage(Component.text("That " + name(plugin.config().shieldCoreMaterial())
                + " is a shield generator now. Put fuel in its fuel slot.")
                .color(NamedTextColor.GREEN));

        if (player.getGameMode() != GameMode.CREATIVE) {
            spend(player, hand);
        }
    }

    /**
     * Drops the shield a departing block was holding.
     *
     * <p>
     * Called from {@link Protection} rather than from the three listeners that
     * wipe protection, so the two layers of a vanished block always go together.
     * No marker is consulted: the block entity is on its way out, and what matters
     * is whether a generator was ever registered here.
     */
    public void onBlockGone(GriefWorld world, Block block) {
        Generator generator = take(world, block.getX(), block.getY(), block.getZ());
        if (generator == null) {
            return;
        }
        world.abi().shieldDestroy(generator.shield());
        world.untrackShield(generator.shield());
    }

    /**
     * Undoes {@link #activate}'s marker, for an admin removing a shield by hand.
     *
     * <p>
     * Without this the block keeps its marker and {@link #scan} builds the
     * shield again the next time the chunk loads, so the removal would appear to
     * work and then quietly not.
     */
    public void unmark(GriefWorld world, Block block) {
        Generator generator = take(world, block.getX(), block.getY(), block.getZ());
        if (generator == null) {
            return;
        }
        if (block.getState() instanceof Furnace furnace) {
            furnace.getPersistentDataContainer().remove(MARKER);
            furnace.update(false, false);
        }
    }

    // --- staying in step with the chunks ---------------------------------------

    /**
     * Picks up any generator in a chunk that has just loaded, creating its shield
     * if the store does not have one.
     *
     * <p>
     * Filtering by material before touching the block entity keeps the common
     * case, a chunk with no furnaces at all, to a single walk of the block entity
     * map. The states are fetched without snapshots: a snapshot copies the
     * inventory, and a chunk full of chests would pay for every one of them on
     * every load to read one byte of marker.
     */
    public void scan(Chunk chunk) {
        GriefWorld world = plugin.grief(chunk.getWorld());
        if (world == null) {
            return;
        }
        Material core = plugin.config().shieldCoreMaterial();
        for (BlockState state : chunk.getTileEntities(block -> block.getType() == core, false)) {
            if (state instanceof Furnace furnace && marked(furnace)) {
                Block block = state.getBlock();
                adopt(world, block);
            } else {
                log(Level.INFO, "scan_skipped", state.getBlock(),
                        "reason=" + (state instanceof Furnace ? "not_marked" : "not_furnace")
                                + " state=" + state.getType());
            }
        }
    }

    /**
     * Adopts the generators of a world whose chunks were already loaded when the
     * plugin started.
     *
     * <p>
     * {@link ChunkListener} only sees chunks that load from here on, so without
     * this a generator that existed before a plugin reload would never be found
     * again and would sit there unfed.
     */
    public void adoptLoadedChunks(World world) {
        GriefWorld grief = plugin.grief(world);
        if (grief == null) {
            return;
        }
        for (Chunk chunk : world.getLoadedChunks()) {
            scan(chunk);
        }
    }

    /** Forgets a whole world, on the way out. */
    public void forget(World world) {
        byWorld.remove(world.getUID());
    }

    /** Forgets the generators in a chunk that has just unloaded. */
    public void forget(Chunk chunk) {
        UUID world = chunk.getWorld().getUID();
        List<Generator> generators = byWorld.get(world);
        if (generators == null) {
            return;
        }
        int before = generators.size();
        generators.removeIf(generator -> inChunk(generator.block(), chunk));
        int removed = before - generators.size();
        log(Level.INFO, "chunk_unloaded", chunk.getWorld(), "chunk=" + chunk.getX() + "," + chunk.getZ()
                + " generators_forgotten=" + removed);
        if (generators.isEmpty()) {
            byWorld.remove(world);
        }
    }

    private void adopt(GriefWorld world, Block block) {
        if (contains(world, block.getX(), block.getY(), block.getZ())) {
            log(Level.INFO, "adopt_skipped", block, "reason=already_registered");
            return;
        }
        int handle = world.shieldHandleAt(block.getX(), block.getY(), block.getZ());
        boolean rebuilt = handle == 0;
        if (handle == 0) {
            handle = world.abi().shieldCreate(world.handle(), block.getX(), block.getY(), block.getZ());
            if (handle == 0) {
                log(Level.WARNING, "adopt_failed", block, "reason=shield_create_failed error=" + plugin.abi().lastError());
                return;
            }
            world.trackShield(handle);
            // Same reasoning as activate: a shield rebuilt from the block has
            // nothing in it, whatever it had before the store lost it.
            world.abi().shieldSetFuel(handle, 0);
        }
        register(world, block, handle);
        log(Level.INFO, "adopted", block, "shield=" + handle + " rebuilt=" + rebuilt);
    }

    private void register(GriefWorld world, Block block, int shield) {
        byWorld.computeIfAbsent(world.bukkit().getUID(), key -> new ArrayList<>())
                .add(new Generator(block, shield));
        log(Level.INFO, "registered", block, "shield=" + shield);
    }

    private boolean contains(GriefWorld world, int x, int y, int z) {
        List<Generator> generators = byWorld.get(world.bukkit().getUID());
        if (generators == null) {
            return false;
        }
        for (Generator generator : generators) {
            Block block = generator.block();
            if (block.getX() == x && block.getY() == y && block.getZ() == z) {
                return true;
            }
        }
        return false;
    }

    private Generator take(GriefWorld world, int x, int y, int z) {
        List<Generator> generators = byWorld.get(world.bukkit().getUID());
        if (generators == null) {
            return null;
        }
        for (int index = 0; index < generators.size(); index++) {
            Block block = generators.get(index).block();
            if (block.getX() == x && block.getY() == y && block.getZ() == z) {
                return generators.remove(index);
            }
        }
        return null;
    }

    // --- fuel ------------------------------------------------------------------

    /**
     * Takes whatever is in every loaded generator's fuel slot and turns it into
     * shield fuel.
     *
     * <p>
     * Whole stacks at once rather than one item per pass. A stack of 64 coal
     * is over an hour of running time, and at one item per pass the last of it
     * would sit in the slot for a minute looking ignored.
     */
    public void tick() {
        for (GriefWorld world : plugin.trackedWorlds()) {
            List<Generator> generators = byWorld.get(world.bukkit().getUID());
            if (generators == null || generators.isEmpty()) {
                // log(Level.INFO, "poll_world", world.bukkit(), "registered_generators=0");
                continue;
            }
            // log(Level.INFO, "poll_world", world.bukkit(), "registered_generators=" + generators.size());
            for (Generator generator : List.copyOf(generators)) {
                if (!world.bukkit().isChunkLoaded(generator.block().getX() >> 4, generator.block().getZ() >> 4)) {
                    // log(Level.INFO, "poll_skipped", generator.block(), "reason=chunk_unloaded");
                    continue;
                }
                refill(world, generator);
                showSmoke(world, generator);
            }
        }
    }

    private void refill(GriefWorld world, Generator generator) {
        Block block = generator.block();
        if (!(block.getState() instanceof Furnace furnace) || !marked(furnace)) {
            // The marker went, or the block did. Either way there is no longer a
            // generator here to feed, and leaving it registered would mean
            // re-reading a block entity that has nothing to say every second.
            log(Level.WARNING, "poll_unregistered", block, "reason="
                    + (block.getState() instanceof Furnace ? "marker_missing" : "not_furnace")
                    + " state=" + block.getType());
            take(world, block.getX(), block.getY(), block.getZ());
            return;
        }

        int lit = furnace.getBurnTime();
        if (lit > 0) {
            log(Level.WARNING, "furnace_lit", block, "burn_ticks=" + lit + " recovery=credit_gauge");
            /*
             * A furnace takes a fuel item out of its slot the moment it lights,
             * not when the gauge empties. The burn event is refused, so nothing
             * should ever light here, but if it ever did then the item would be
             * gone and the slot below would credit nothing for it. The gauge it
             * left behind is that same fuel, so it is credited instead, a second
             * at a time, and the gauge is put back to zero.
             */
            furnace.setBurnTime((short) 0);
            boolean updated = furnace.update(false, false);
            if (!updated) {
                log(Level.WARNING, "furnace_update_failed", block, "operation=clear_burn_time");
            }
            credit(world, block, lit / 20);
            return;
        }

        FurnaceInventory inventory = furnace.getSnapshotInventory();
        ItemStack fuel = inventory.getItem(FUEL_SLOT);
        if (fuel == null || fuel.getType().isAir()) {
            log(Level.FINE, "fuel_slot_empty", block, "slot=" + FUEL_SLOT);
            return;
        }
        int seconds = burnSeconds(fuel.getType()) * fuel.getAmount();
        if (seconds <= 0) {
            log(Level.INFO, "fuel_rejected", block, "slot=" + FUEL_SLOT + " item=" + fuel.getType()
                    + " amount=" + fuel.getAmount() + " reason=unsupported_fuel");
            return;
        }
        if (!inventory.isFuel(fuel)) {
            log(Level.INFO, "fuel_rejected", block, "slot=" + FUEL_SLOT + " item=" + fuel.getType()
                    + " amount=" + fuel.getAmount() + " reason=inventory_does_not_recognize_fuel");
            return;
        }

        Material fuelType = fuel.getType();
        int amount = fuel.getAmount();
        // A lava bucket is emptied rather than consumed, exactly as a furnace would
        // leave the bucket behind in the same slot.
        inventory.setItem(FUEL_SLOT, fuelType == Material.LAVA_BUCKET
                ? new ItemStack(Material.BUCKET)
                : null);
        boolean updated = furnace.update(false, false);
        if (!updated) {
            log(Level.WARNING, "furnace_update_failed", block, "operation=remove_fuel item=" + fuelType);
            return;
        }

        ItemStack remaining = block.getState() instanceof Furnace committed
                ? committed.getSnapshotInventory().getItem(FUEL_SLOT)
                : null;
        boolean removed = fuelType == Material.LAVA_BUCKET
                ? remaining != null && remaining.getType() == Material.BUCKET && remaining.getAmount() == 1
                : remaining == null || remaining.getType().isAir();
        if (!removed) {
            log(Level.WARNING, "fuel_removal_unverified", block, "item=" + fuelType + " slot_after="
                    + describe(remaining) + " fuel_not_credited=true");
            return;
        }
        log(Level.INFO, "fuel_consumed", block, "slot=" + FUEL_SLOT + " item=" + fuelType + " amount=" + amount
                + " credited_seconds=" + seconds + " slot_after=" + describe(remaining)
                + " removed=true inventory_update=" + updated);
        credit(world, block, seconds);
    }

    /**
     * Adds fuel to the shield anchored at a generator, and wakes it up.
     *
     * <p>
     * No ceiling. A generator holds exactly what has been put into it, because
     * a fuel slot that silently refuses a coal it could have run on for eighty
     * seconds is worse than a generator that lasts a long time, and the price of
     * the generator is a nether star rather than a fuel bill. {@code
     * shield-fuel-max} still says what a shield made by an admin command starts
     * with.
     */
    private void credit(GriefWorld world, Block block, int seconds) {
        if (seconds <= 0) {
            return;
        }
        int handle = world.shieldHandleAt(block.getX(), block.getY(), block.getZ());
        if (handle == 0) {
            log(Level.WARNING, "fuel_discarded", block, "seconds=" + seconds + " reason=no_shield_at_position");
            return;
        }
        ShieldState state = world.abi().shieldGetState(handle);
        // A shield from the library is unpowered until something turns it on, and
        // the library only runs one that is both powered and fuelled. Powering it
        // here rather than at conversion is what makes "put fuel in and it
        // starts" true without a generator ever holding a lit shield nobody paid
        // for.
        world.abi().shieldSetPowered(handle, true);
        world.abi().shieldSetFuel(handle, state.fuel() + seconds);
        ShieldState credited = world.abi().shieldGetState(handle);
        log(Level.INFO, "shield_credited", block, "shield=" + handle + " added_seconds=" + seconds
                + " fuel_before=" + state.fuel() + " fuel_after=" + credited.fuel()
                + " powered=" + credited.powered() + " running=" + credited.running());
    }

    private void showSmoke(GriefWorld world, Generator generator) {
        Block block = generator.block();
        if (!isGenerator(block)) {
            return;
        }
        ShieldState state = world.abi().shieldGetState(generator.shield());
        if (!state.running()) {
            return;
        }
        world.bukkit().spawnParticle(
                Particle.SMOKE, block.getX() + 0.5, block.getY() + 1.05, block.getZ() + 0.5,
                1, 0.04, 0.03, 0.04, 0.005);
    }

    /**
     * How many seconds of running time one item of this material is worth.
     *
     * <p>
     * Vanilla furnace seconds, so a coal is 80 and the shield drains one unit
     * of that per second. The tag checks stand in for the several dozen wooden
     * blocks vanilla values individually; none of the wood family is worth more
     * than a plank, and the one exception, a wooden slab at half a plank, is the
     * only distinction worth the line.
     */
    static int burnSeconds(Material material) {
        Integer named = BURN_SECONDS.get(material);
        if (named != null) {
            return named;
        }
        if (Tag.ITEMS_PLANKS.isTagged(material) || Tag.ITEMS_LOGS_THAT_BURN.isTagged(material)) {
            return WOOD_SECONDS;
        }
        if (Tag.ITEMS_WOODEN_SLABS.isTagged(material)) {
            return WOOD_SLAB_SECONDS;
        }
        return 0;
    }

    // --- helpers ---------------------------------------------------------------

    private static boolean inChunk(Block block, Chunk chunk) {
        // Shifting rather than dividing, so a negative coordinate still lands in
        // the chunk it is actually in.
        return (block.getX() >> 4) == chunk.getX() && (block.getZ() >> 4) == chunk.getZ();
    }

    private void spend(Player player, EquipmentSlot hand) {
        ItemStack stack = player.getInventory().getItem(hand);
        if (stack.getAmount() <= 1) {
            player.getInventory().setItem(hand, null);
        } else {
            stack.setAmount(stack.getAmount() - 1);
            player.getInventory().setItem(hand, stack);
        }
    }

    private static void refuse(Player player, String why) {
        player.sendMessage(Component.text(why).color(NamedTextColor.RED));
    }

    private static String name(Material material) {
        return material.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String describe(ItemStack item) {
        return item == null || item.getType().isAir()
                ? "empty"
                : item.getType() + "x" + item.getAmount();
    }

    private void log(Level level, String event, Block block, String details) {
        log(level, event, block.getWorld(), "x=" + block.getX() + " y=" + block.getY() + " z=" + block.getZ()
                + " " + details);
    }

    private void log(Level level, String event, World world, String details) {
        plugin.getLogger().log(level, () -> "shield-generator event=" + event + " world=" + world.getName()
                + " " + details);
    }

    /** A generator block, and the shield anchored to it. */
    private record Generator(Block block, int shield) {
    }
}