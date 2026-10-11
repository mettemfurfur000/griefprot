package com.tem.griefprot;

import com.tem.griefprot.config.OppositeSide;
import com.tem.griefprot.nativebridge.Abi;
import com.tem.griefprot.world.BukkitWorldPolicy;
import com.tem.griefprot.world.GriefWorld;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Puts the face tools in players' hands: reinforcement with a brush, a flint that
 * locks a block against new faces, and a slime ball that makes it pushable.
 *
 * <p>All three are clicks on a block face, so all three follow the same shape.
 * Right click, keep the tool against the block, and after a moment the action
 * lands. Nothing happens on the click itself. The hold is what makes it a
 * decision: a plate used to go on the instant the button did, which turned a
 * misclick into a permanent change to someone's wall.
 *
 * <p>Reinforcement is upgrade only. A tier equal to or weaker than what the face
 * already carries is refused, and nothing is spent.
 *
 * <p>The block face comes from {@code PlayerInteractEvent#getBlockFace}, which is
 * the side of the block the player was aiming at, so reinforcing the top of a
 * chest does not reinforce the front.
 *
 * <p>Sneaking sends the plate somewhere the crosshair is not pointing, which is
 * the only way to reach a face that cannot be aimed at. Where exactly it lands is
 * {@link OppositeSide}: the far side of the same block by default, which reaches
 * the underside of a wall, or the block behind the clicked one, which reaches the
 * interior of a wall more than one block thick.
 *
 * <p>{@link #tick()} is what turns a finished hold into the action itself. It is
 * driven by the scheduler rather than by the click stream so the timing does not
 * depend on how often the server repeats interact events.
 */
public final class InteractListener implements Listener {

    private final GriefProtPlugin plugin;
    private final FaceHold holds = new FaceHold();

    public InteractListener(GriefProtPlugin plugin) {
        this.plugin = plugin;
    }

    // --- starting a hold ------------------------------------------------------

    /**
     * Begins or continues a hold with whichever tool the clicking hand holds.
     *
     * <p>Both hands are accepted, not just the main one. The brush and the nugget
     * it takes are two different items, so insisting the brush be in a particular
     * hand would mean the other one was empty and the player had to care about
     * which was which. The hand that fired decides nothing: the two interact events
     * a click produces are deduplicated by {@link FaceHold#arm}, which ignores a
     * repeat for a hold already in progress.
     *
     * <p>The event is cancelled once the hold is ours. Nothing here places a block
     * and none of these three items place one either, so there is no vanilla
     * behaviour to lose; leaving it uncancelled would let a brush on, say, a
     * decorated pot do whatever that pot does on a right click.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFaceTool(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        FaceTool tool = FaceTool.forMaterial(item.getType());
        if (tool == null) {
            // A nugget on its own does nothing. The plate is applied by the brush,
            // so a click with only a nugget is an ordinary click again.
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        GriefWorld world = plugin.grief(block);
        if (world == null) {
            return;
        }
        if (!(world.policy() instanceof BukkitWorldPolicy policy) || !policy.isProtectable(block)) {
            return;
        }
        Player player = event.getPlayer();

        if (tool.needsTierItem() && tierFor(tierItem(player, event.getHand()).getType()) == 0) {
            /*
             * The action bar, not chat. Holding the button re-fires this event
             * several times a second, so a refusal sent to chat would say the same
             * sentence four times a second for as long as the player holds it. The
             * bar holds one line and no hold is running to overwrite it, so it
             * stays up until they act on it.
             */
            player.sendActionBar(Component.text(
                            "Hold a copper or iron nugget in your " + handName(FaceTool.otherHand(event.getHand()))
                                    + " hand to reinforce.")
                    .color(NamedTextColor.RED));
            return;
        }

        event.setCancelled(true);

        boolean started = holds.arm(new FaceHold.Pending(
                player.getUniqueId(),
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ(),
                event.getBlockFace(),
                tool,
                event.getHand(),
                player.isSneaking(),
                System.currentTimeMillis()));
        if (started) {
            player.sendActionBar(Component.text(tool.verb() + "...").color(NamedTextColor.GRAY));
        }
    }

    // --- finishing one --------------------------------------------------------

    /**
     * Abandons holds whose player is no longer doing the thing, and applies the
     * ones that have run long enough.
     *
     * <p>Runs on the scheduler rather than off the interact stream, so the hold
     * takes the same time whatever the server's repeat cadence is doing.
     */
    public void tick() {
        if (holds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long holdMillis = plugin.config().brushHoldTicks() * 50L;

        for (FaceHold.Pending hold : holds.pending()) {
            Player player = plugin.getServer().getPlayer(hold.player());
            if (player == null || walkedAway(player, hold)) {
                holds.cancel(hold.player());
                continue;
            }
            if (!FaceHold.due(hold, now, holdMillis)) {
                player.sendActionBar(Component.text(FaceHold.progress(hold, now, holdMillis))
                        .color(NamedTextColor.GRAY));
                continue;
            }

            holds.cancel(hold.player());
            player.sendActionBar(Component.empty());
            GriefWorld world = plugin.grief(player.getWorld());
            if (world == null) {
                continue;
            }
            complete(world, player, hold);
        }
    }

    /**
     * Whether the player has stopped doing whatever they were doing.
     *
     * <p>The button cannot be watched, so this is built from the things that can
     * be seen: the tool is gone, the player is dead, they shifted, or the crosshair
     * has moved off the block. Letting go mid hold is not one of them, and cannot
     * be, so the action still lands if the player releases and stands there. That is
     * the forgiving direction; the guards here are all about not spending a plate
     * on a block the player is no longer looking at.
     */
    private boolean walkedAway(Player player, FaceHold.Pending hold) {
        if (player.isDead() || player.isSneaking() != hold.sneaking()) {
            return true;
        }
        if (player.getInventory().getItem(hold.hand()).getType() != hold.tool().material()) {
            return true;
        }
        Block aimed = player.getTargetBlockExact(AimedFace.TARGET_RANGE);
        if (aimed == null) {
            return true;
        }
        return !aimed.getWorld().getUID().equals(hold.world())
                || aimed.getX() != hold.x()
                || aimed.getY() != hold.y()
                || aimed.getZ() != hold.z();
    }

    /** Applies a hold that has been held long enough. */
    private void complete(GriefWorld world, Player player, FaceHold.Pending hold) {
        World bukkit = world.bukkit();
        if (hold.y() < bukkit.getMinHeight() || hold.y() >= bukkit.getMaxHeight()) {
            return;
        }
        Block block = bukkit.getBlockAt(hold.x(), hold.y(), hold.z());
        switch (hold.tool()) {
            case BRUSH -> reinforce(world, player, hold, block);
            case LOCK_FACES -> lockFaces(world, player, block);
            case PISTON_FREE -> pistonFree(world, player, block, hold.face());
        }
    }

    /**
     * Puts a plate on the aimed face.
     *
     * <p>The tier comes from the nugget in the other hand rather than from the
     * brush, which is why {@link FaceTool#needsTierItem()} exists at all: there is
     * one brush in the game and it has no opinion about how hard a wall should be.
     */
    private void reinforce(GriefWorld world, Player player, FaceHold.Pending hold, Block block) {
        EquipmentSlot tierHand = FaceTool.otherHand(hold.hand());
        Material tier = tierItem(player, hold.hand()).getType();
        int points = tierFor(tier);
        if (points == 0) {
            // The other hand went somewhere in the middle of the hold. This one is
            // safe to say in chat: the hold is finished, so it cannot repeat.
            refuse(player, "That needs a copper or iron nugget in the other hand. Nothing was spent.");
            return;
        }

        Target target = resolveTarget(world, player, block, hold.face(), hold.sneaking());
        if (target == null) {
            return; // resolveTarget already said why
        }

        int face = plugin.faces().toAbi(target.face());
        int held = world.abi().reinfFaceDurability(world.handle(), target.x(), target.y(), target.z(), face);
        if (held >= points) {
            refuse(player, "That face is already reinforced at least this well.");
            return;
        }
        if (world.abi().reinfFacesLocked(world.handle(), target.x(), target.y(), target.z())) {
            refuse(
                    player,
                    "That block's faces are locked. Existing faces can still be reinforced, but it will not take another side.");
            return;
        }

        if (!world.abi().reinfAdd(world.handle(), target.x(), target.y(), target.z(), face, points)) {
            refuse(player, "That plate would not go on. Nothing was spent.");
            return;
        }

        player.sendMessage(Component.text(
                        "Reinforced the " + plugin.faces().describe(face) + " face" + target.where() + " with "
                                + name(tier) + ".")
                .color(NamedTextColor.GREEN));

        // Creative players keep the item so the mechanic can be explored without
        // farming.
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        consumeOne(player, tierHand);
    }

    /**
     * Stops the block taking a face it does not already carry.
     *
     * <p>Needs something to lock. Setting the flag on a block with no protection
     * would do nothing at all, and a record holding only the flag could not be
     * cleaned up until the block was broken, so the library refuses it outright.
     * Saying so beats spending the player's click on a no-op.
     */
    private void lockFaces(GriefWorld world, Player player, Block block) {
        int handle = world.handle();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        if (world.abi().reinfFaceCount(handle, x, y, z) == 0) {
            refuse(player, "There is nothing on that block to lock yet. Reinforce a face first.");
            return;
        }
        int flag = plugin.abi().blockFlagLockFaces();
        if (!world.abi().reinfFlagsSet(handle, x, y, z, flag, flag)) {
            refuse(player, "That block could not be locked. Nothing was spent.");
            return;
        }
        player.sendMessage(Component.text(
                        "Locked that block's faces. It will not take another side, but the ones it has can still be reinforced.")
                .color(NamedTextColor.GREEN));
    }

    /**
     * Waives the piston's rules for the block, so a reinforced one can still be
     * part of a moving build.
     *
     * <p>Only from a side the block has no protection on. The point of the flag is
     * to open a hole in a block you have already built around, and requiring an
     * unprotected side means the decision is made on a face the owner can see is
     * bare rather than on one that is holding a plate. A block with nothing on it
     * at all is refused too: there is no protection here for a piston to be getting
     * in the way of.
     */
    private void pistonFree(GriefWorld world, Player player, Block block, BlockFace clicked) {
        int handle = world.handle();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        if (world.abi().reinfFaceCount(handle, x, y, z) == 0) {
            refuse(player, "That block has no protection on it for a piston to be getting in the way of.");
            return;
        }
        int clickedFace = plugin.faces().toAbi(clicked);
        int held = clickedFace == Abi.NO_FACE
                ? 0
                : world.abi().reinfFaceDurability(handle, x, y, z, clickedFace);
        if (held > 0) {
            refuse(player, "Slick it from a side with no reinforcement on it, so the choice is one you can see.");
            return;
        }
        int flag = plugin.abi().blockFlagPistonFree();
        if (!world.abi().reinfFlagsSet(handle, x, y, z, flag, flag)) {
            refuse(player, "That block could not be marked pushable. Nothing was spent.");
            return;
        }
        player.sendMessage(Component.text(
                        "Marked that block pushable. Pistons move it like anything else, and its protection travels with it.")
                .color(NamedTextColor.GREEN));
    }

    // --- targeting ------------------------------------------------------------

    /** Where a plate lands: a position, a face on it, and how to describe that. */
    private record Target(int x, int y, int z, BlockFace face, String where) {}

    /**
     * Works out which block and face a plate goes on, or tells the player why it
     * cannot and returns null.
     *
     * <p>Telling the player from inside here is deliberate. Every failure is worth a
     * sentence rather than silence: they aimed at a block that plainly exists, and
     * being told nothing reads as a bug rather than as a rule.
     */
    private Target resolveTarget(
            GriefWorld world, Player player, Block block, BlockFace clicked, boolean sneaking) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        OppositeSide mode = sneaking ? plugin.config().oppositeSide() : OppositeSide.OFF;
        if (mode == OppositeSide.SAME_BLOCK) {
            // The block never moves, so there is nothing to look up and nothing
            // that can be missing. Only the face changes.
            return new Target(x, y, z, sameBlockFace(clicked), " on the far side of the block");
        }
        if (mode == OppositeSide.THROUGH) {
            OppositeSite site = oppositeSite(clicked, x, y, z);
            String refusal = refuseThrough(world, site);
            if (refusal != null) {
                refuse(player, refusal);
                return null;
            }
            return new Target(
                    site.x(),
                    site.y(),
                    site.z(),
                    site.face(),
                    " on the block behind it (" + site.x() + ", " + site.y() + ", " + site.z() + ")");
        }
        // OFF: the clicked face, which is protectable because the click was
        // refused otherwise.
        return new Target(x, y, z, clicked, "");
    }

    /**
     * The face on the same block, diametrically opposite the one clicked.
     *
     * <p>{@link OppositeSide#SAME_BLOCK}. The block is the one under the
     * crosshair either way, so unlike {@link #oppositeSite} there is no lookup,
     * no neighbouring block, and nothing that can fail on a missing neighbour.
     */
    static BlockFace sameBlockFace(BlockFace clicked) {
        return clicked.getOppositeFace();
    }

    /**
     * Where a {@link OppositeSide#THROUGH} plate goes: past {@code clicked} and
     * onto the block behind it.
     *
     * <p>
     * Free of the server so the arithmetic can be pinned, and deliberately so.
     * Getting the direction backwards neither throws nor looks wrong: the plate
     * lands on a face that exists, carries a real plate, and is one the player
     * can never aim at again. The player gets a green confirmation and a wall
     * just as soft as before.
     *
     * <p>The clicked face points from the clicked block toward the player, so the
     * block behind it is one step that way. The face the plate takes is the
     * other side of the same plane, which is what makes the two touch.
     *
     * @param clicked the face the player aimed at
     * @return the block behind it and, on that block, the face pointing back
     */
    static OppositeSite oppositeSite(BlockFace clicked, int x, int y, int z) {
        return new OppositeSite(
                x + clicked.getModX(), y + clicked.getModY(), z + clicked.getModZ(), clicked.getOppositeFace());
    }

    /** The far side of a clicked face: a block position and a face on it. */
    record OppositeSite(int x, int y, int z, BlockFace face) {}

    /**
     * Why a plate cannot go on the block behind the one that was clicked, or
     * null when it can.
     *
     * <p>The bounds check comes first because it is the one that also keeps the
     * block lookup itself in range: stepping through the bottom face of the
     * lowest block in the world leaves the build limits entirely.
     *
     * <p>The chunk check matters because {@code gp_abi_reinf_add} creates a chunk
     * rather than refusing one. Placing against a block the server has not
     * loaded would therefore spend the item on a record in a chunk that may
     * never be released.
     */
    private String refuseThrough(GriefWorld world, OppositeSite site) {
        World bukkit = world.bukkit();
        if (site.y() < bukkit.getMinHeight() || site.y() >= bukkit.getMaxHeight()) {
            return "There is nothing behind that face to reinforce.";
        }
        // Shifting rather than dividing keeps negative coordinates correct, which
        // is the same reason GriefWorld shifts when it walks its shield index.
        if (!bukkit.isChunkLoaded(site.x() >> 4, site.z() >> 4)) {
            return "The block behind that face is not loaded, so the plate would go nowhere.";
        }
        Block behind = bukkit.getBlockAt(site.x(), site.y(), site.z());
        if (!(world.policy() instanceof BukkitWorldPolicy policy) || !policy.isProtectable(behind)) {
            return "The block behind that face cannot be reinforced.";
        }
        return null;
    }

    // --- helpers --------------------------------------------------------------

    /** The item in the hand opposite the one holding the tool. */
    private ItemStack tierItem(Player player, EquipmentSlot toolHand) {
        return player.getInventory().getItem(FaceTool.otherHand(toolHand));
    }

    /** Reinforcement value for a tier item, or 0 if it is not a tier item. */
    private int tierFor(Material material) {
        if (material == Material.COPPER_NUGGET) {
            return plugin.config().copperTierPoints();
        }
        if (material == Material.IRON_NUGGET) {
            return plugin.config().ironTierPoints();
        }
        return 0;
    }

    /**
     * Removes one reinforcement item from the hand that held it.
     *
     * <p>Handled explicitly rather than by cancelling the interaction, because the
     * player is also right-clicking the block and that may have its own meaning.
     * The hand is passed in rather than assumed to be the main one: the brush is
     * allowed in either, so the nugget may be in either too.
     */
    private void consumeOne(Player player, EquipmentSlot hand) {
        ItemStack stack = player.getInventory().getItem(hand);
        if (stack.getAmount() <= 1) {
            player.getInventory().setItem(hand, null);
        } else {
            stack.setAmount(stack.getAmount() - 1);
            player.getInventory().setItem(hand, stack);
        }
    }

    private void refuse(Player player, String why) {
        player.sendMessage(Component.text(why).color(NamedTextColor.RED));
    }

    private static String name(Material material) {
        return material.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String handName(EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND ? "off" : "main";
    }

    /** Drops the player's hold when they leave, rather than letting it outlive them. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        holds.cancel(event.getPlayer().getUniqueId());
    }

    /** The holds in progress, for the overlay to stay quiet over. */
    FaceHold holds() {
        return holds;
    }

    /**
     * Keeps protection from surviving a block that was replaced without a break
     * event ever firing, a piston shove being the usual way.
     *
     * <p>
     * Without this, a reinforced block that a piston shoves away would keep its
     * record at the old coordinates, protecting whatever moved into them. Both
     * layers go: shield points are coordinates too, so leaving them behind would
     * hand a new block the coverage of one that no longer exists.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        GriefWorld world = plugin.grief(event.getBlock());
        if (world != null) {
            Protection.clearBlockAndPartner(plugin.generators(), world, event.getBlock());
        }
    }
}