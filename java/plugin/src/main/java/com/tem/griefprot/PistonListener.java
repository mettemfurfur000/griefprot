package com.tem.griefprot;

import com.tem.griefprot.nativebridge.Abi;
import com.tem.griefprot.world.GriefWorld;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

/**
 * Moves protection along with the blocks a piston pushes or pulls.
 *
 * <p>Piston motion is expressed as a direction, not as a set of moved blocks, and
 * the library expects exactly that: {@code gp_abi_move_block} takes the direction
 * a block travels and works out the source and destination itself.
 *
 * <p>The event reports the whole move, but the library moves one block's
 * protection at a time, so each moved block is passed individually. If the store
 * refuses any move, for example because the merge would exceed the face cap, the
 * piston event is cancelled and earlier protection transfers are rolled back.
 * Source-face checks differ by event: retracts guard the travel-facing side,
 * while extensions guard the opposite side.
 *
 * <h2>The chain</h2>
 *
 * <p>A slime or honey block hauls up to twelve further blocks along behind it, and
 * the event is not obliged to list every one of them. The chain is therefore
 * rebuilt here from the event's own list plus whatever those blocks drag, and then
 * walked front to back so a block is only moved once the cell it enters has been
 * vacated.
 *
 * <p>A block marked piston free, by a slime ball held against an unprotected side,
 * is exempt from the rules here altogether. Its protection still travels with it
 * like anyone else's, so the block stays reinforced wherever the piston leaves
 * it; what it loses is the veto.
 *
 * <p>Both halves of that matter. A wall can be pulled apart from the back by
 * hauling a block out with a slime, and the block that comes out is listed nowhere
 * near the block the plugin is asked about; and a chain walked back to front drops
 * each record into a cell that still holds the record ahead of it, which the
 * library merges. That merge sums durability and shield points, so walking it in
 * the wrong order is what lets one block's protection ride another block out and
 * stack with it on the way back.
 */
public final class PistonListener implements Listener {

    /**
     * Materials a piston hauls further blocks along behind itself.
     *
     * <p>Both are {@code canSlide} blocks, which is the same property that decides
     * this in the server. Honey is here for the same reason slime is: it is the
     * other half of the same mechanic, and leaving it out would reopen the hole
     * through the honey variant.
     */
    private static final Set<Material> DRAGS_NEIGHBOURS =
            EnumSet.of(Material.SLIME_BLOCK, Material.HONEY_BLOCK);

    private final GriefProtPlugin plugin;

    public PistonListener(GriefProtPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent event) {
        if (!handle(event, event.getDirection(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        // Paper reports the pulled block's travel direction, not the piston facing.
        if (!handle(event, event.getDirection(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    private boolean handle(
            BlockPistonEvent event, BlockFace direction, List<Block> blocks) {
        GriefWorld world = plugin.grief(event.getBlock());
        if (world == null) {
            return true;
        }
        int motion = plugin.faces().toAbi(direction);
        if (motion == Abi.NO_FACE) {
            return true;
        }

        World bukkit = event.getBlock().getWorld();
        Predicate<Pos> drags = pos -> dragsNeighbours(
                bukkit.getBlockAt(pos.x(), pos.y(), pos.z()).getType());
        Predicate<Pos> inWorld = pos ->
                pos.y() >= bukkit.getMinHeight() && pos.y() < bukkit.getMaxHeight();

        List<Pos> chain = orderForVacating(dragChain(snapshot(blocks), direction, drags, inWorld), direction);

        /*
         * A shield generator is bolted down, and checked here rather than in
         * `refusal` because it is not a rule about faces. Its shield is anchored
         * at the block, so moving the block would leave the shield behind
         * covering whatever arrived in the coordinates the generator used to be
         * in -- a shield with no generator, which is the one arrangement a player
         * could neither explain nor undo. The piston free flag does not reach
         * this either: slicking a block waives the rules about reinforcement,
         * and says nothing about being allowed to relocate a machine.
         */
        Pos generator = firstGenerator(world, chain);
        if (generator != null) {
            plugin.getLogger().warning("cancelling piston event at " + describe(event)
                    + ": shield generator at " + generator + " cannot be moved");
            return false;
        }

        Refusal refusal = refusal(
                chain,
                direction,
                event instanceof BlockPistonRetractEvent,
                (pos, face) -> world.abi().reinfFaceDurability(
                        world.handle(), pos.x(), pos.y(), pos.z(), plugin.faces().toAbi(face)),
                drags,
                pos -> world.abi().reinfPistonFree(world.handle(), pos.x(), pos.y(), pos.z()));
        if (refusal != null) {
            plugin.getLogger().warning("cancelling piston event at " + describe(event) + ": " + refusal);
            return false;
        }

        List<Move> completed = new ArrayList<>();
        for (Pos from : chain) {
            if (!world.hasProtection(from.x(), from.y(), from.z())) {
                continue;
            }
            Pos to = from.offset(direction);

            /*
             * The chain is walked front to back, so anything that shared this cell
             * has already moved out. Anything still here is not part of the move,
             * and the library would merge this record into it: two blocks becoming
             * one, with their durabilities and shield points summed.
             */
            if (world.hasProtection(to.x(), to.y(), to.z())) {
                plugin.getLogger().warning("cancelling piston event at " + describe(event)
                        + ": block " + from + " would be pushed onto protected block " + to
                        + ", stacking their protection");
                rollback(world, completed, direction.getOppositeFace());
                return false;
            }

            if (!world.abi().moveBlock(
                    world.handle(),
                    from.x(), from.y(), from.z(),
                    to.x(), to.y(), to.z(),
                    motion)) {
                plugin.getLogger().warning("piston protection move rejected; cancelling event at "
                        + describe(event) + " for block " + from + ": " + world.abi().lastError());
                rollback(world, completed, direction.getOppositeFace());
                return false;
            }
            completed.add(new Move(from, to));
        }
        return true;
    }

    static BlockFace sourceFaceToGuard(BlockFace travelDirection, boolean retract) {
        return retract ? travelDirection : travelDirection.getOppositeFace();
    }

    /** The first shield generator in the chain, or null when there is none. */
    private Pos firstGenerator(GriefWorld world, List<Pos> chain) {
        for (Pos at : chain) {
            if (plugin.generators().isGeneratorAt(world.bukkit(), at.x(), at.y(), at.z())) {
                return at;
            }
        }
        return null;
    }

    /**
     * Expands the event's moved blocks into everything the piston displaces.
     *
     * <p>Only slime and honey haul anything, and only the single block directly
     * behind them, so the chain grows as a straight line along {@code direction}
     * from whatever the event already listed. Running to a fixed point picks up a
     * row of them, where each one drags the next along.
     *
     * <p>Behind is {@code direction} reversed on purpose. A sticky block travels
     * forwards and takes whatever is at its back with it, exactly as it does in
     * the server, so the block it hauls sits opposite the way the piston is
     * pushing.
     *
     * @param moves   positions the event reported, in its own order
     * @param drags   whether a position holds a block that hauls its neighbour
     * @param inWorld whether a position is inside the build limits
     */
    static List<Pos> dragChain(
            Collection<Pos> moves, BlockFace direction, Predicate<Pos> drags, Predicate<Pos> inWorld) {
        BlockFace behind = direction.getOppositeFace();
        LinkedHashSet<Pos> chain = new LinkedHashSet<>(moves);
        boolean grew = true;
        while (grew) {
            grew = false;
            // Copied because the loop adds to the set it walks.
            for (Pos at : List.copyOf(chain)) {
                if (!drags.test(at)) {
                    continue;
                }
                Pos dragged = at.offset(behind);
                if (inWorld.test(dragged) && chain.add(dragged)) {
                    grew = true;
                }
            }
        }
        return new ArrayList<>(chain);
    }

    /**
     * Orders the chain front to back, so every cell a block moves into has already
     * been vacated by the block ahead of it.
     *
     * <p>Walking it the other way is the bug this exists to prevent: a block would
     * be moved into a cell the next block ahead still occupies, and the library
     * merges the two records rather than refusing. Durability on a shared face is
     * summed by that merge, which is correct for one block arriving somewhere else
     * and wrong here, where two separate blocks were never supposed to meet.
     */
    static List<Pos> orderForVacating(Collection<Pos> chain, BlockFace direction) {
        List<Pos> ordered = new ArrayList<>(chain);
        ordered.sort(Comparator.comparingInt((Pos pos) -> pos.along(direction)).reversed());
        return ordered;
    }

    /**
     * Whether a piston moving this block hauls the one behind it along too.
     */
    static boolean dragsNeighbours(Material material) {
        return DRAGS_NEIGHBOURS.contains(material);
    }

    /**
     * Looks for the reason this move must be refused, or null when it may go ahead.
     *
     * <p>Three rules, each read as a face rather than as a block, because a block
     * carrying protection on its back is not the same thing as one carrying it on
     * the side the piston is touching.
     *
     * <ul>
     *   <li>A block may not move away from a reinforced face, nor into one.
     *   <li>A slime or honey block sitting against a reinforced face refuses the
     *       move, whichever side that is.
     * </ul>
     *
     * <p>The second rule is deliberately broader than "the block it hauls". A
     * sticky block is the one material a piston can move without touching the face
     * it is about to attack: everything else is pressed on the side the piston
     * reaches, which the first rule already covers, but a slime grips whatever is
     * behind it <em>and</em> simply sits against whatever else it happens to be
     * touching. Narrowing the rule to the hauled block, and to the hauled block's
     * leading face, left the case where the face it touches is reinforced while the
     * face the piston presses is not, which is exactly how a wall comes apart one
     * block at a time.
     *
     * <p>{@code exempt} is the block level piston freedom flag, and it overrides
     * all three for the block it is set on: that block ignores the rules rather
     * than winning an argument against them. It also stops a block's protection
     * refusing somebody else's move, since an exempt block is one a piston is
     * allowed to be near regardless of what is on its faces.
     *
     * <p>What an exempt block does not get is the store to ignore it.
     * {@code gp_world_move_block} still refuses to push one block into a
     * reinforced face, because that is the destination's protection and not the
     * mover's, and the move loop still refuses to land a record on top of another
     * one, because that merge would sum two blocks' durability together.
     */
    static Refusal refusal(
            List<Pos> chain,
            BlockFace direction,
            boolean retract,
            FaceDurability durability,
            Predicate<Pos> drags,
            Predicate<Pos> exempt) {
        BlockFace sourceFace = sourceFaceToGuard(direction, retract);
        BlockFace enteredFace = direction.getOppositeFace();

        for (Pos at : chain) {
            if (exempt.test(at)) {
                continue;
            }

            int source = durability.at(at, sourceFace);
            if (source > 0) {
                return new Refusal(Kind.SOURCE_FACE, at, sourceFace, source);
            }

            int entered = durability.at(at.offset(direction), enteredFace);
            if (entered > 0) {
                return new Refusal(Kind.ENTERED_FACE, at.offset(direction), enteredFace, entered);
            }

            if (!drags.test(at)) {
                continue;
            }
            for (BlockFace touching : BlockFace.values()) {
                Pos neighbour = at.offset(touching);
                if (exempt.test(neighbour)) {
                    continue;
                }
                BlockFace shared = touching.getOppositeFace();
                int held = durability.at(neighbour, shared);
                if (held > 0) {
                    return new Refusal(Kind.STICKY_TOUCH, neighbour, shared, held);
                }
            }
        }
        return null;
    }

    private void rollback(GriefWorld world, List<Move> completed, BlockFace reverseDirection) {
        int reverseMotion = plugin.faces().toAbi(reverseDirection);
        for (int i = completed.size() - 1; i >= 0; i--) {
            Move move = completed.get(i);
            if (!world.abi().moveBlock(
                    world.handle(),
                    move.to().x(), move.to().y(), move.to().z(),
                    move.from().x(), move.from().y(), move.from().z(),
                    reverseMotion)) {
                plugin.getLogger().severe("failed to roll back piston protection move from "
                        + move.to() + " to " + move.from()
                        + ": " + world.abi().lastError());
            }
        }
    }

    private static String describe(BlockPistonEvent event) {
        Block block = event.getBlock();
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    /**
     * Copies the moved blocks.
     *
     * <p>Iterating the live list while the piston is still being applied is
     * fragile, and the snapshot also guards against a block being visited twice
     * when the library moves protection and the event lists both ends of the move.
     */
    private static List<Pos> snapshot(List<Block> blocks) {
        List<Pos> positions = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            positions.add(new Pos(block.getX(), block.getY(), block.getZ()));
        }
        return positions;
    }

    /**
     * A block position, kept independent of Bukkit's {@code Block}.
     *
     * <p>Positions rather than blocks throughout, because the chain this listener
     * walks covers blocks the event never handed out and the server has not moved
     * yet, so there is nothing to ask a {@code Block} for.
     */
    record Pos(int x, int y, int z) {
        @Override
        public String toString() {
            return x + "," + y + "," + z;
        }

        Pos offset(BlockFace direction) {
            return switch (direction) {
                case EAST -> new Pos(x + 1, y, z);
                case WEST -> new Pos(x - 1, y, z);
                case SOUTH -> new Pos(x, y, z + 1);
                case NORTH -> new Pos(x, y, z - 1);
                case UP -> new Pos(x, y + 1, z);
                case DOWN -> new Pos(x, y - 1, z);
                default -> this;
            };
        }

        /** How far along {@code direction} this position sits. Front is larger. */
        int along(BlockFace direction) {
            return switch (direction) {
                case EAST -> x;
                case WEST -> -x;
                case SOUTH -> z;
                case NORTH -> -z;
                case UP -> y;
                case DOWN -> -y;
                default -> 0;
            };
        }
    }

    private record Move(Pos from, Pos to) {}

    /** Reads the durability a record holds on one face of one block. */
    @FunctionalInterface
    interface FaceDurability {
        int at(Pos pos, BlockFace face);
    }

    enum Kind {
        /** A block would move away from a reinforced face. */
        SOURCE_FACE("would move away from reinforced"),
        /** A block would move into a reinforced face. */
        ENTERED_FACE("would move into reinforced"),
        /** A slime or honey block is touching a reinforced face. */
        STICKY_TOUCH("pressed against reinforced");

        private final String wording;

        Kind(String wording) {
            this.wording = wording;
        }
    }

    /** Why a move was refused, carrying enough context to name the block in a log. */
    record Refusal(Kind kind, Pos pos, BlockFace face, int durability) {
        @Override
        public String toString() {
            String subject = kind == Kind.STICKY_TOUCH ? "a slime or honey block is " : "block " + pos + " ";
            return subject + kind.wording + " " + face.name().toLowerCase(Locale.ROOT)
                    + " face at " + pos + " (durability=" + durability + ")";
        }
    }
}