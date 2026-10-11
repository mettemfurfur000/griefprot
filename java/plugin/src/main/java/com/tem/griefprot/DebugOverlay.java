package com.tem.griefprot;

import com.tem.griefprot.world.GriefWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import net.kyori.adventure.text.Component;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Particle overlays for testers, plus the one face overlay a brush always gets.
 *
 * <p>The command-driven modes are opt-in and gated behind {@code griefprot.debug}.
 * The brush overlay is not: a player holding a brush sees the face they are about
 * to reinforce, because finding out afterwards that the face was already plated
 * costs them the nugget and tells them nothing they could have acted on.
 */
public final class DebugOverlay extends BukkitRunnable implements CommandExecutor, Listener {

    private static final List<BlockFace> FACES =
            List.of(BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST);
    /** Particles per edge of the shield wireframe. */
    private static final int LINE_SEGMENTS = 4;
    /**
     * Cells per axis of a reinforced face. Four is a 5x5 plane, which at the
     * default radius of 2 is a couple of thousand particles in the worst case
     * where every block in range is reinforced on all six faces.
     */
    private static final int FACE_DIVISIONS = 4;
    private static final Color EMPTY_COLOR = Color.fromRGB(255, 48, 48);
    private static final Color COPPER_COLOR = Color.fromRGB(255, 196, 48);
    private static final Color IRON_COLOR = Color.fromRGB(64, 224, 96);
    private static final Color SHIELD_COLOR = Color.fromRGB(64, 192, 255);

    private final GriefProtPlugin plugin;
    private final Map<UUID, Mode> enabled = new HashMap<>();

    public DebugOverlay(GriefProtPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Debug overlays are only available to players.");
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("overlay")) {
            return false;
        }

        Mode requested = switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "reinforcement" -> Mode.REINFORCEMENT;
            case "shield" -> Mode.SHIELD;
            case "details" -> Mode.DETAILS;
            case "off" -> null;
            default -> null;
        };
        if (requested == null && !args[1].equalsIgnoreCase("off")) {
            player.sendMessage("Usage: /griefprot overlay <reinforcement|shield|details|off>");
            return true;
        }

        if (requested == null || enabled.get(player.getUniqueId()) == requested) {
            enabled.remove(player.getUniqueId());
            player.sendMessage("Protection overlay disabled.");
            return true;
        }

        enabled.put(player.getUniqueId(), requested);
        player.sendMessage("Showing " + requested.name().toLowerCase(java.util.Locale.ROOT) + " overlay "
                + "for the block you are looking at. Use /griefprot overlay off to stop.");
        return true;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player == null) {
                continue;
            }
            Mode mode = enabled.get(player.getUniqueId());
            boolean brushing = holdingBrush(player);
            if (mode == null && !brushing) {
                continue;
            }

            Block block = player.getTargetBlockExact(AimedFace.TARGET_RANGE);
            if (block == null) {
                if (mode == Mode.DETAILS) {
                    player.sendActionBar(Component.empty());
                }
                continue;
            }
            GriefWorld world = plugin.grief(block);
            if (world == null) {
                if (mode == Mode.DETAILS) {
                    player.sendActionBar(Component.text("World is not tracked by GriefProt."));
                }
                continue;
            }

            if (mode == Mode.REINFORCEMENT) {
                showReinforcement(player, block, world, plugin.config().debugOverlayRadius());
            } else if (mode == Mode.SHIELD) {
                showShield(player, block, world, plugin.config().debugOverlayRadius());
            } else if (mode == Mode.DETAILS && !brushing) {
                // Holding a brush owns the action bar. Details is a debug mode and
                // can wait until the player puts the brush away; a hold in progress
                // would be fighting it for the same line several times a second.
                showDetails(player, block, world);
            }

            if (brushing) {
                showAimedFace(player, block, world);
            }
        }
    }

    /** Whether either hand holds a brush, which is what earns the face overlay. */
    private static boolean holdingBrush(Player player) {
        return FaceTool.BRUSH
                == FaceTool.forMaterial(player.getInventory().getItemInMainHand().getType())
                || FaceTool.BRUSH == FaceTool.forMaterial(player.getInventory().getItemInOffHand().getType());
    }

    /**
     * Draws the one face the player is aiming at, in the reinforcement overlay's
     * own colours.
     *
     * <p>Red is the same red the overlay uses for a face at zero durability, which
     * is the honest reading here: that is exactly what this face is. Green and
     * orange are the tier colours, so the drawing answers "is this one protected,
     * and how well" without the player having to learn a second visual language.
     */
    private void showAimedFace(Player player, Block block, GriefWorld world) {
        BlockFace face = AimedFace.of(player, block);
        if (face == null) {
            return;
        }
        int durability = world.abi().reinfFaceDurability(
                world.handle(), block.getX(), block.getY(), block.getZ(), plugin.faces().toAbi(face));
        drawFace(player, block, face, durabilityColor(
                durability, plugin.config().copperTierPoints(), plugin.config().ironTierPoints()));
    }

    private void showDetails(Player player, Block block, GriefWorld world) {
        int[] durability = new int[FACES.size()];
        for (int i = 0; i < FACES.size(); i++) {
            int face = plugin.faces().toAbi(FACES.get(i));
            durability[i] = world.abi().reinfFaceDurability(
                    world.handle(), block.getX(), block.getY(), block.getZ(), face);
        }
        int shield = world.abi().pointsGet(world.handle(), block.getX(), block.getY(), block.getZ());
        player.sendActionBar(Component.text(detailsText(block.getX(), block.getY(), block.getZ(), durability, shield)));
    }

    static String detailsText(int x, int y, int z, int[] durability, int shieldPoints) {
        if (durability.length != FACES.size()) {
            throw new IllegalArgumentException("expected durability for all six block faces");
        }
        List<String> entries = new ArrayList<>(FACES.size());
        for (int i = 0; i < FACES.size(); i++) {
            entries.add(faceShortName(FACES.get(i)) + ":" + durability[i]);
        }
        return "Block " + x + "," + y + "," + z + " | " + String.join(" ", entries)
                + " | shield:" + shieldPoints;
    }

    private static String faceShortName(BlockFace face) {
        return switch (face) {
            case DOWN -> "D";
            case UP -> "U";
            case NORTH -> "N";
            case SOUTH -> "S";
            case WEST -> "W";
            case EAST -> "E";
            default -> throw new IllegalArgumentException("not a block face: " + face);
        };
    }

    private void showReinforcement(Player player, Block center, GriefWorld world, int radius) {
        forEachBlockInRadius(center, radius, block -> showReinforcementBlock(player, block, world));
    }

    private void showReinforcementBlock(Player player, Block block, GriefWorld world) {
        for (BlockFace face : FACES) {
            int id = plugin.faces().toAbi(face);
            int durability = world.abi().reinfFaceDurability(
                    world.handle(), block.getX(), block.getY(), block.getZ(), id);
            if (durability > 0) {
                drawFace(player, block, face, durabilityColor(
                        durability, plugin.config().copperTierPoints(), plugin.config().ironTierPoints()));
            }
        }
    }

    private void showShield(Player player, Block center, GriefWorld world, int radius) {
        forEachBlockInRadius(center, radius, block -> {
            if (world.abi().pointsGet(world.handle(), block.getX(), block.getY(), block.getZ()) > 0) {
                drawCube(player, block, SHIELD_COLOR);
            }
        });
    }

    private void forEachBlockInRadius(Block center, int radius, Consumer<Block> action) {
        int radiusSquared = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!withinRadius(dx, dy, dz, radiusSquared)) {
                        continue;
                    }
                    int x = center.getX() + dx;
                    int y = center.getY() + dy;
                    int z = center.getZ() + dz;
                    if (y < center.getWorld().getMinHeight() || y >= center.getWorld().getMaxHeight()
                            || !center.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    action.accept(center.getWorld().getBlockAt(x, y, z));
                }
            }
        }
    }

    static boolean withinRadius(int dx, int dy, int dz, int radiusSquared) {
        return dx * dx + dy * dy + dz * dz <= radiusSquared;
    }

    /**
     * Draws a reinforced face as a filled plane rather than an outline.
     *
     * <p>An outline cannot say which side of an edge is protected: a face drawn
     * only on its four edges shares every one of them with the face opposite it,
     * so a reinforced block and a bare neighbour light up the same line. Filling
     * the face makes the side unambiguous at any viewing angle.
     */
    private void drawFace(Player player, Block block, BlockFace face, Color color) {
        double[][] corners = faceCorners(block.getX(), block.getY(), block.getZ(), face);
        DustOptions dust = new DustOptions(color, 0.8f);
        for (double[] point : faceGrid(corners, FACE_DIVISIONS)) {
            player.spawnParticle(Particle.DUST, point[0], point[1], point[2], 1, 0, 0, 0, 0, dust);
        }
    }

    /**
     * The four corners of a block face, wound so that c0 to c1 and c0 to c3 are
     * the two edges meeting at c0.
     */
    static double[][] faceCorners(int x, int y, int z, BlockFace face) {
        return switch (face) {
            case DOWN -> new double[][] {{x, y, z}, {x + 1, y, z}, {x + 1, y, z + 1}, {x, y, z + 1}};
            case UP -> new double[][] {{x, y + 1, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}};
            case NORTH -> new double[][] {{x, y, z}, {x + 1, y, z}, {x + 1, y + 1, z}, {x, y + 1, z}};
            case SOUTH -> new double[][] {
                {x, y, z + 1}, {x + 1, y, z + 1}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}
            };
            case WEST -> new double[][] {{x, y, z}, {x, y, z + 1}, {x, y + 1, z + 1}, {x, y + 1, z}};
            case EAST -> new double[][] {
                {x + 1, y, z}, {x + 1, y, z + 1}, {x + 1, y + 1, z + 1}, {x + 1, y + 1, z}
            };
            default -> throw new IllegalArgumentException("not a block face: " + face);
        };
    }

    /**
     * Samples a face as a {@code (divisions + 1)^2} grid, its edges included.
     *
     * <p>The grid is the whole plane rather than a frame plus a fill, so there is
     * one particle layout to reason about and no inner ring that can fall a step
     * behind the outline.
     */
    static List<double[]> faceGrid(double[][] corners, int divisions) {
        if (corners.length != 4) {
            throw new IllegalArgumentException("a block face has four corners, got " + corners.length);
        }
        if (divisions < 1) {
            throw new IllegalArgumentException("a face needs at least one division, got " + divisions);
        }

        int side = divisions + 1;
        double[][] points = new double[side * side][];
        int at = 0;
        for (int row = 0; row < side; row++) {
            double v = (double) row / divisions;
            for (int column = 0; column < side; column++) {
                points[at++] = new double[] {
                    bilinear(corners[0][0], corners[1][0], corners[2][0], corners[3][0], column, v, divisions),
                    bilinear(corners[0][1], corners[1][1], corners[2][1], corners[3][1], column, v, divisions),
                    bilinear(corners[0][2], corners[1][2], corners[2][2], corners[3][2], column, v, divisions)
                };
            }
        }
        return List.of(points);
    }

    /** One coordinate of a point on the quad, {@code column} across and {@code v} down. */
    private static double bilinear(double c0, double c1, double c2, double c3, int column, double v, int divisions) {
        double across = (double) column / divisions;
        return lerp(lerp(c0, c1, across), lerp(c3, c2, across), v);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private void drawCube(Player player, Block block, Color color) {
        // The wireframe indexes corners 0-3 along the bottom face and 4-7 along
        // the top, which is exactly how faceCorners already orders them.
        double[][] corners = new double[8][];
        System.arraycopy(faceCorners(block.getX(), block.getY(), block.getZ(), BlockFace.DOWN), 0, corners, 0, 4);
        System.arraycopy(faceCorners(block.getX(), block.getY(), block.getZ(), BlockFace.UP), 0, corners, 4, 4);
        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        DustOptions dust = new DustOptions(color, 1.0f);
        for (int[] edge : edges) {
            drawLine(player, corners[edge[0]], corners[edge[1]], dust);
        }
    }

    private void drawLine(Player player, double[] start, double[] end, DustOptions dust) {
        for (int step = 0; step < LINE_SEGMENTS; step++) {
            double t = (double) step / LINE_SEGMENTS;
            player.spawnParticle(
                    Particle.DUST,
                    start[0] + (end[0] - start[0]) * t,
                    start[1] + (end[1] - start[1]) * t,
                    start[2] + (end[2] - start[2]) * t,
                    1,
                    0,
                    0,
                    0,
                    0,
                    dust);
        }
    }

    static Color durabilityColor(int durability, int copperTier, int ironTier) {
        if (durability <= 0) {
            return EMPTY_COLOR;
        }

        int lowerTier = Math.min(copperTier, ironTier);
        int upperTier = Math.max(copperTier, ironTier);
        if (durability <= lowerTier) {
            return blend(EMPTY_COLOR, COPPER_COLOR, (double) durability / lowerTier);
        }
        if (durability < upperTier) {
            return blend(COPPER_COLOR, IRON_COLOR, (double) (durability - lowerTier) / (upperTier - lowerTier));
        }
        return IRON_COLOR;
    }

    private static Color blend(Color start, Color end, double amount) {
        double t = Math.max(0.0, Math.min(1.0, amount));
        return Color.fromRGB(
                (int) Math.round(start.getRed() + (end.getRed() - start.getRed()) * t),
                (int) Math.round(start.getGreen() + (end.getGreen() - start.getGreen()) * t),
                (int) Math.round(start.getBlue() + (end.getBlue() - start.getBlue()) * t));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        enabled.remove(event.getPlayer().getUniqueId());
    }

    private enum Mode {
        REINFORCEMENT,
        SHIELD,
        DETAILS
    }

}
