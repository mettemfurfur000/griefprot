package com.tem.griefprot;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Tool;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Charges the tool that lost a break to protection.
 *
 * <p>Paper's {@code destroyBlock} fires {@code BlockBreakEvent} before it damages
 * the tool and returns false the moment the event is cancelled, so stopping a
 * break silently refunds the swing. That makes a raid free: a reinforced wall can
 * be hit indefinitely at no cost to the attacker, which is the opposite of what
 * the reinforcement is there to do. Charging here puts that cost back.
 *
 * <p>The amount is whatever the held item declares as its {@code damage_per_block},
 * read rather than derived. That keeps this plugin out of the business of
 * reproducing Minecraft's mining arithmetic, which moved into the tool data
 * component in 1.21.5 and would be a formula to get wrong here. Unbreaking and
 * Mending still apply, because the charge goes through the server's own item
 * damage rather than writing to the item directly.
 *
 * <p>Which items may break through protection at all is {@link #isTool}, and the
 * two together are the whole cost model: an attacker spends tool durability to
 * wear a face down, and something that cannot be charged does not get to spend
 * the block's protection either.
 *
 * <p>Note that the declared rate is a flat 1 for every vanilla tool at this
 * version: {@code ToolMaterial#applyToolProperties} builds the component with a
 * literal 1, so the per-tier mining speed lives in the component's rules and
 * never reaches the wear. That makes {@code tool-damage-multiplier}, not the
 * item, the thing that sets the real cost of a raid.
 */
final class ToolDamage {

    private ToolDamage() {}

    /**
     * Whether this item is a tool at all.
     *
     * <p>The question {@link #perBlock} cannot answer, and the reason the two are
     * separate: a player breaking protected blocks costs themselves durability,
     * so an item that can never be charged has no business spending the block's
     * protection either. Otherwise the raid is free in the one currency that
     * actually costs anything: bare hands, a stick, or a spare block would grind
     * a reinforced wall down at no cost to the attacker, which is exactly the hole
     * charging the tool exists to close.
     *
     * <p>Unbreakable is not treated as not a tool. A netherite pickaxe with the
     * unbreakable component is still a pickaxe, and a player who has earned one
     * should not be told their tool cannot break stone. What matters is the
     * {@code minecraft:tool} component, which is what the server itself would
     * charge wear against.
     *
     * <p>Read through {@link ItemStack#getData} for the reason given on
     * {@link #perBlock}: the tool component lives on the item's definition, so a
     * vanilla tool carries no override on the stack.
     */
    static boolean isTool(ItemStack item) {
        return !item.getType().isAir() && item.getData(DataComponentTypes.TOOL) != null;
    }

    /**
     * Durability this item loses per block broken, or 0 when it cannot be
     * charged: nothing in hand, something that is not a tool, or an unbreakable
     * one.
     *
     * <p>Read through {@link ItemStack#getData} rather than {@code ItemMeta}.
     * The tool component lives on the item's own definition, not on the stack, so
     * a vanilla pickaxe carries no tool override and a meta that asks whether the
     * stack has one answers no for every unmodified tool in the game.
     */
    static int perBlock(ItemStack tool) {
        if (tool.getType().isAir() || tool.hasData(DataComponentTypes.UNBREAKABLE)) {
            return 0;
        }
        Tool component = tool.getData(DataComponentTypes.TOOL);
        return component == null ? 0 : component.damagePerBlock();
    }

    /**
 * Takes a stopped break's worth of durability off whatever the player holds.
 *
 * <p>Uses the slot overload rather than {@link ItemStack#damage}, and that
 * distinction is the whole reason this method exists as it does. The stack
 * overload resolves to {@code damageItemStack(stack, amount, null)}, and the
 * break callback vanilla registers is gated on the slot:
 *
 * <pre>
 * lambda$hurtAndBreak$0(slot, entity, item) {
 *     if (slot != null) entity.onEquippedItemBroken(item, slot);
 * }
 * </pre>
 *
 * <p>With no slot that never runs, so the tool still empties but takes the break
 * sound, the break particles, the ITEM_BROKEN statistic and the Mending roll
 * with it. The slot overload passes {@code EquipmentSlot.HAND} and gets all
 * four, and damages the held item in place, so there is no inventory read and
 * write back to get out of step with the server.
 *
 * @param multiplier scales the charge, where 1 is the item's own rate; 0
 *     leaves the tool alone entirely
 * @return how much was charged, 0 when nothing was
 */
    static int charge(Player player, double multiplier) {
        if (Double.isNaN(multiplier) || multiplier <= 0.0) {
            return 0;
        }
        int amount = scaled(perBlock(player.getInventory().getItemInMainHand()), multiplier);
        if (amount <= 0) {
            return 0;
        }
        player.damageItemStack(EquipmentSlot.HAND, amount);
        return amount;
    }

    /**
     * Rounds a charge to whole durability, upwards.
     *
     * <p>Rounding up rather than truncating is deliberate: at a multiplier of
     * 0.4 a truncating charge would be 0 for any tool declaring 1 per block, so
     * the setting would silently stop the raid cost existing at all instead of
     * making it cheaper.
     *
     * <p>Only NaN charges nothing. An infinite or off-the-scale product
     * saturates to the maximum instead, because a multiplier too large to
     * compute is a tool about to be destroyed rather than a raid that costs
     * nothing, and a truncated 0 here would hand the cost back.
     */
    static int scaled(int base, double multiplier) {
        if (base <= 0 || Double.isNaN(multiplier) || multiplier <= 0.0) {
            return 0;
        }
        double exact = base * multiplier;
        if (Double.isNaN(exact)) {
            return 0;
        }
        if (exact >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(exact);
    }
}