package com.tem.griefprot;

import com.tem.griefprot.nativebridge.Abi;
import com.tem.griefprot.nativebridge.BreakResult;
import com.tem.griefprot.world.GriefWorld;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Turns a block break into a query against the native store.
 *
 * <p>Reinforcement is per face and {@code BlockBreakEvent} does not say which
 * face was struck, so the face is taken from the click that caused the break when
 * one was remembered. When it was not, the weakest reinforced face is used, since
 * that is the one an attacker could always have aimed at; assuming a stronger one
 * would promise protection the block does not actually have.
 *
 * <p>{@link #onBreak} runs at {@link EventPriority#HIGHEST} and only cancels when
 * the store says the block was protected, so plugins that legitimately override
 * a break still get their say afterwards. Cancelling is also what charges the
 * attacker's tool, since Paper never damages it on a cancelled break. Clearing
 * what the block was holding is left to {@link #onBreakApplied}, which only runs
 * for a break that went through.
 *
 * <p>Something that cannot be charged never reaches the store at all. A bare
 * hand, a stick or a spare block wears a reinforced wall down at no cost to the
 * attacker, which is the same free raid {@link ToolDamage} exists to close, one
 * currency further out.
 */
public final class BreakListener implements Listener {

    private final GriefProtPlugin plugin;
    private final BreakFaces faces = new BreakFaces();

    public BreakListener(GriefProtPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAim(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        faces.remember(event.getPlayer().getUniqueId(), event.getClickedBlock(), event.getBlockFace());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        GriefWorld world = plugin.grief(block);
        if (world == null) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            // Creative ignores protection, and the break is not cancelled, so
            // onBreakApplied wipes the record along with the block.
            return;
        }

        if (refuseUnarmed(world, block, player, event)) {
            return;
        }

        int face = resolveFace(world, player, block);
        BreakResult result = world.resolveBreak(block, face, true, true);
        if (!result.ok() || !result.protectedFromBreak()) {
            return;
        }

        event.setCancelled(true);
        chargeTool(player);
        player.sendMessage(feedback(result, face));
    }

    /**
     * Stops a break made with something that is not a tool, without spending the
     * block's protection.
     *
     * <p>Refused outright rather than allowed through for free. The alternative
     * leaves reinforcement as something to look at: a punch is slower than a pickaxe,
     * but the block still comes down, and the whole point of charging the tool is
     * that getting through a face should cost the attacker something.
     *
     * <p>The store is not consulted at all, so no durability and no shield point is
     * spent. Checking that the block is protected first keeps this off every
     * ordinary break of an ordinary block, which is most of them.
     *
     * @return true when the event was cancelled and nothing further should happen
     */
    private boolean refuseUnarmed(GriefWorld world, Block block, Player player, BlockBreakEvent event) {
        if (ToolDamage.isTool(player.getInventory().getItemInMainHand())) {
            return false;
        }
        if (!world.hasProtection(block.getX(), block.getY(), block.getZ())) {
            return false;
        }
        event.setCancelled(true);
        player.sendMessage(Component.text("That block is reinforced. Break it with a tool.")
                .color(NamedTextColor.RED));
        return true;
    }

    /**
     * Takes the swing's worth of durability off the tool.
     *
     * <p>Cancelling the break is what costs the attacker, so this has to happen
     * wherever the cancel does. Without it the swing is refunded for free: a
     * reinforced wall can be hit indefinitely without spending anything. The
     * cancel in {@link #refuseUnarmed} is the one case that does not come here,
     * because by then nothing in hand could be charged anyway.
     */
    private void chargeTool(Player player) {
        ToolDamage.charge(player, plugin.config().toolDamageMultiplier());
    }

    /**
     * Drops whatever the broken block was holding, once the break is final.
     *
     * <p>Deliberately at {@link EventPriority#MONITOR}, after every other plugin
     * has had its say: one that cancels the break later must leave a standing
     * block's protection alone, and wiping at HIGHEST would already have taken
     * it.
     *
     * <p>Nothing survives the block. The resolver only spends the face that was
     * struck, so a block broken from an unprotected side keeps its other faces
     * on air otherwise, and whatever is placed there later inherits them.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakApplied(BlockBreakEvent event) {
        GriefWorld world = plugin.grief(event.getBlock());
        if (world != null) {
            Protection.clearBlockAndPartner(plugin.generators(), world, event.getBlock());
        }
    }

    /**
     * Works out which face to resolve against.
     *
     * @return the remembered face if there is a trustworthy one, otherwise the
     *     weakest reinforced face, or {@code NONE} if the block is unreinforced
     */
    private int resolveFace(GriefWorld world, Player player, Block block) {
        BlockFace cached = faces.recall(player.getUniqueId(), block);
        if (cached != null) {
            int id = plugin.faces().toAbi(cached);
            if (id != Abi.NO_FACE) {
                return id;
            }
        }
        // Nothing trustworthy was remembered, so fall back to the weakest
        // reinforced face: the one an attacker could always have aimed at.
        int weakest = world.abi().weakestFace(world.handle(), block.getX(), block.getY(), block.getZ());
        return weakest == Abi.NO_FACE ? plugin.abi().faceNone() : weakest;
    }

    private Component feedback(BreakResult result, int face) {
        if (result.reinforcementHeld()) {
            return Component.text("The " + plugin.faces().describe(face) + " face holds.")
                    .color(NamedTextColor.GRAY);
        }
        int left = result.left();
        return Component.text("A shield absorbed that. ")
                .color(NamedTextColor.AQUA)
                .append(Component.text(left + " point" + (left == 1 ? "" : "s") + " left.")
                        .color(NamedTextColor.DARK_AQUA));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        faces.forget(event.getPlayer().getUniqueId());
    }
}