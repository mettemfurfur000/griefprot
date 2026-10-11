package com.tem.griefprot;

import com.tem.griefprot.nativebridge.ChunkCounts;
import com.tem.griefprot.nativebridge.Pos;
import com.tem.griefprot.nativebridge.ShieldState;
import com.tem.griefprot.world.GriefWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Permission-gated administrative inspection and management commands. */
public final class AdminCommand implements CommandExecutor {

    private static final List<BlockFace> FACES =
            List.of(BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST);

    private final GriefProtPlugin plugin;

    public AdminCommand(GriefProtPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("griefprot.admin")) {
            sender.sendMessage("You do not have permission to use GriefProt admin tools.");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "stats" -> stats(sender, args);
            case "lookup" -> lookup(sender, args);
            case "protection" -> protection(sender, args);
            case "shield" -> shield(sender, args);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private boolean stats(CommandSender sender, String[] args) {
        if (args.length > 2) {
            sendUsage(sender);
            return true;
        }
        if (args.length == 2) {
            GriefWorld world = world(sender, args[1]);
            if (world != null) {
                sendStats(sender, world);
            }
            return true;
        }
        if (plugin.trackedWorlds().isEmpty()) {
            sender.sendMessage("GriefProt is not tracking any worlds.");
            return true;
        }
        for (GriefWorld world : plugin.trackedWorlds()) {
            sendStats(sender, world);
        }
        return true;
    }

    private void sendStats(CommandSender sender, GriefWorld world) {
        ChunkCounts chunks = world.chunkCounts();
        sender.sendMessage("GriefProt " + world.bukkit().getName()
                + ": memory=" + world.memoryBytes() + " bytes, chunks=" + chunks.loaded() + "/" + chunks.reserved()
                + " loaded/reserved, shields=" + world.abi().shieldCount(world.handle()));
        sender.sendMessage("Protection record totals are not exposed by the native API; use /griefprot admin lookup.");
    }

    private boolean lookup(CommandSender sender, String[] args) {
        Position position = parsePosition(sender, args, 1);
        if (position == null) {
            return true;
        }
        GriefWorld world = world(sender, position.worldName());
        if (world == null) {
            return true;
        }
        if (!world.abi().chunkIsLoaded(world.handle(), position.x() >> 4, position.z() >> 4)) {
            sender.sendMessage("That chunk is not loaded in GriefProt.");
            return true;
        }

        Block block = world.bukkit().getBlockAt(position.x(), position.y(), position.z());
        List<String> durability = new ArrayList<>(FACES.size());
        for (BlockFace face : FACES) {
            int value = world.abi().reinfFaceDurability(world.handle(), position.x(), position.y(), position.z(),
                    plugin.faces().toAbi(face));
            durability.add(face.name().toLowerCase(Locale.ROOT) + "=" + value);
        }
        int points = world.abi().pointsGet(world.handle(), position.x(), position.y(), position.z());
        sender.sendMessage(world.bukkit().getName() + " " + position.x() + "," + position.y() + "," + position.z()
                + " " + block.getType() + " | " + String.join(" ", durability) + " | shield points=" + points
                + " | " + describeFlags(world, position));
        return true;
    }

    /**
     * The block flags in words, because a bare bitmask is a thing only the library
     * can read and the flags are invisible everywhere else.
     */
    private String describeFlags(GriefWorld world, Position position) {
        int handle = world.handle();
        List<String> set = new ArrayList<>(2);
        if (world.abi().reinfFacesLocked(handle, position.x(), position.y(), position.z())) {
            set.add("faces locked");
        }
        if (world.abi().reinfPistonFree(handle, position.x(), position.y(), position.z())) {
            set.add("piston free");
        }
        return set.isEmpty() ? "no block flags" : "block flags: " + String.join(", ", set);
    }

    private boolean protection(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }
        String operation = args[1].toLowerCase(Locale.ROOT);
        int tail = operation.equals("add") ? 2 : 0;
        Position position = parsePosition(sender, args, 2, tail);
        if (position == null) {
            return true;
        }
        GriefWorld world = world(sender, position.worldName());
        if (world == null || !requireLoaded(sender, world, position)) {
            return true;
        }
        int x = position.x();
        int y = position.y();
        int z = position.z();
        switch (operation) {
            case "clear" -> {
                boolean hadReinforcement = world.abi().reinfClear(world.handle(), x, y, z);
                int oldPoints = world.abi().pointsGet(world.handle(), x, y, z);
                int removedPoints = oldPoints == 0 ? 0 : world.abi().pointsTake(world.handle(), x, y, z, oldPoints);
                if (!hadReinforcement && removedPoints == 0) {
                    sender.sendMessage("No protection was recorded at that block.");
                } else {
                    sender.sendMessage("Cleared reinforcement and " + removedPoints + " shield point(s).");
                }
            }
            case "unlock" -> {
                // The two block flags are one way in game, by design, so this is
                // the only way back for a block somebody locked or slicked by
                // mistake. Masking to all of them means a flag the library gains
                // later is cleared here too rather than needing a new subcommand.
                int mask = plugin.abi().blockFlagAll();
                int before = world.abi().reinfFlags(world.handle(), x, y, z);
                if (before == 0) {
                    sender.sendMessage("No block flags were set at that block.");
                } else if (!world.abi().reinfFlagsSet(world.handle(), x, y, z, 0, mask)) {
                    sender.sendMessage("Nothing was recorded at that block, so there was nothing to clear.");
                } else {
                    sender.sendMessage("Cleared the block flags at that position. Reinforcement is untouched.");
                }
            }
            case "add" -> {
                int positionAndValues = args.length - 2;
                if (positionAndValues != 2 && positionAndValues != 5 && positionAndValues != 6) {
                    // Coordinate form has 3 or 4 position tokens, then face and durability.
                    sendUsage(sender);
                    return true;
                }
                String faceText = args[args.length - 2];
                String durabilityText = args[args.length - 1];
                int face;
                int durability;
                try {
                    face = plugin.faces().toAbi(BlockFace.valueOf(faceText.toUpperCase(Locale.ROOT)));
                    durability = Integer.parseInt(durabilityText);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage("Face must be down, up, north, south, west, or east; durability must be a number.");
                    return true;
                }
                if (face == com.tem.griefprot.nativebridge.Abi.NO_FACE || durability <= 0) {
                    sender.sendMessage("Face must be a block face and durability must be positive.");
                    return true;
                }
                if (!world.abi().reinfAdd(world.handle(), x, y, z, face, durability)) {
                    sender.sendMessage("The reinforcement was refused (the face already has equal or greater durability).");
                } else {
                    sender.sendMessage("Set reinforcement on the " + faceText.toLowerCase(Locale.ROOT)
                            + " face to " + durability + ".");
                }
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    private boolean shield(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }
        String operation = args[1].toLowerCase(Locale.ROOT);
        if (operation.equals("list")) {
            if (args.length > 3) {
                sendUsage(sender);
                return true;
            }
            GriefWorld world = args.length == 3 ? world(sender, args[2]) : currentWorld(sender);
            if (world == null) {
                return true;
            }
            listShields(sender, world);
            return true;
        }
        if (operation.equals("target")) {
            return shieldTarget(sender, args);
        }

        int tail = switch (operation) {
            case "power", "fuel" -> 1;
            default -> 0;
        };
        Position position = parsePosition(sender, args, 2, tail);
        if (position == null) {
            return true;
        }
        GriefWorld world = world(sender, position.worldName());
        if (world == null || !requireLoaded(sender, world, position)) {
            return true;
        }
        int x = position.x();
        int y = position.y();
        int z = position.z();
        switch (operation) {
            case "create" -> {
                int handle = world.abi().shieldCreate(world.handle(), x, y, z);
                if (handle == 0) {
                    sender.sendMessage("Could not create a shield: " + plugin.abi().lastError());
                } else {
                    world.trackShield(handle);
                    sender.sendMessage("Created shield at " + x + "," + y + "," + z + ".");
                }
            }
            case "remove", "power", "fuel", "targets-clear" -> {
                int handle = shieldAt(world, x, y, z);
                if (handle == 0) {
                    sender.sendMessage("No shield is anchored at that block.");
                    return true;
                }
                switch (operation) {
                    case "remove" -> {
                        world.abi().shieldDestroy(handle);
                        world.untrackShield(handle);
                        // A generator block that keeps its marker gets its shield
                        // rebuilt the next time the chunk loads, so removing the
                        // shield on its own would look like it worked.
                        plugin.generators().unmark(world, blockAt(world, x, y, z));
                        sender.sendMessage("Removed shield at " + x + "," + y + "," + z + ".");
                    }
                    case "power" -> {
                        if (args.length < 3
                                || !(args[args.length - 1].equalsIgnoreCase("on")
                                        || args[args.length - 1].equalsIgnoreCase("off"))) {
                            sendUsage(sender);
                            return true;
                        }
                        boolean powered = args[args.length - 1].equalsIgnoreCase("on");
                        world.abi().shieldSetPowered(handle, powered);
                        sender.sendMessage("Shield power set to " + powered + ".");
                    }
                    case "fuel" -> {
                        if (args.length < 3) {
                            sendUsage(sender);
                            return true;
                        }
                        Integer fuel = parseNonNegative(sender, args[args.length - 1], "fuel");
                        if (fuel != null) {
                            world.abi().shieldSetFuel(handle, fuel);
                            sender.sendMessage("Shield fuel set to " + world.abi().shieldGetState(handle).fuel() + ".");
                        }
                    }
                    case "targets-clear" -> {
                        world.abi().shieldClearTargets(handle);
                        sender.sendMessage("Cleared shield targets.");
                    }
                    default -> throw new IllegalStateException("unreachable shield operation");
                }
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    private boolean shieldTarget(CommandSender sender, String[] args) {
        if (args.length != 9 && args.length != 10) {
            sendUsage(sender);
            return true;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        String worldName = args.length == 10 ? args[3] : currentWorldName(sender);
        if (worldName == null) {
            return true;
        }
        int offset = args.length == 10 ? 4 : 3;
        int[] coordinates = parseCoordinates(sender, args, offset, 6);
        if (coordinates == null) {
            return true;
        }
        GriefWorld world = world(sender, worldName);
        if (world == null) {
            return true;
        }
        Position anchor = new Position(worldName, coordinates[0], coordinates[1], coordinates[2]);
        Position target = new Position(worldName, coordinates[3], coordinates[4], coordinates[5]);
        if (!requireLoaded(sender, world, anchor) || !requireLoaded(sender, world, target)) {
            return true;
        }
        int handle = shieldAt(world, anchor.x(), anchor.y(), anchor.z());
        if (handle == 0) {
            sender.sendMessage("No shield is anchored at " + anchor.x() + "," + anchor.y() + "," + anchor.z() + ".");
            return true;
        }
        boolean changed = switch (action) {
            case "add" -> world.abi().shieldAddTarget(handle, target.x(), target.y(), target.z());
            case "remove" -> world.abi().shieldRemoveTarget(handle, target.x(), target.y(), target.z());
            default -> {
                sendUsage(sender);
                yield false;
            }
        };
        if (changed) {
            String verb = action.equals("add") ? "added" : "removed";
            sender.sendMessage("Shield target " + verb + " at " + target.x() + "," + target.y() + ","
                    + target.z() + ".");
        } else {
            sender.sendMessage("The shield target was not changed (already present, absent, or target limit reached).");
        }
        return true;
    }

    private void listShields(CommandSender sender, GriefWorld world) {
        int count = world.abi().shieldCount(world.handle());
        sender.sendMessage(world.bukkit().getName() + " has " + count + " shield(s).");
        for (int index = 0; index < count; index++) {
            int handle = world.abi().shieldAt(world.handle(), index);
            if (handle == 0) {
                continue;
            }
            Pos position = world.abi().shieldGetPos(handle);
            ShieldState state = world.abi().shieldGetState(handle);
            sender.sendMessage("  " + position.x() + "," + position.y() + "," + position.z()
                    + " powered=" + state.powered() + " running=" + state.running() + " fuel=" + state.fuel());
        }
    }

    private int shieldAt(GriefWorld world, int x, int y, int z) {
        return world.shieldHandleAt(x, y, z);
    }

    private static Block blockAt(GriefWorld world, int x, int y, int z) {
        return world.bukkit().getBlockAt(x, y, z);
    }

    private Position parsePosition(CommandSender sender, String[] args, int start) {
        return parsePosition(sender, args, start, 0);
    }

    private Position parsePosition(CommandSender sender, String[] args, int start, int trailing) {
        int count = args.length - start - trailing;
        if (count == 0 && sender instanceof Player player) {
            Block target = player.getTargetBlockExact(8);
            if (target == null) {
                sender.sendMessage("Look at a block or supply coordinates.");
                return null;
            }
            return new Position(player.getWorld().getName(), target.getX(), target.getY(), target.getZ());
        }
        String worldName;
        int coordinateStart;
        if (count == 4) {
            worldName = args[start];
            coordinateStart = start + 1;
        } else if (count == 3 && sender instanceof Player player) {
            worldName = player.getWorld().getName();
            coordinateStart = start;
        } else {
            sendUsage(sender);
            return null;
        }
        int[] coordinates = parseCoordinates(sender, args, coordinateStart, 3);
        return coordinates == null ? null : new Position(worldName, coordinates[0], coordinates[1], coordinates[2]);
    }

    private int[] parseCoordinates(CommandSender sender, String[] args, int start, int count) {
        int[] coordinates = new int[count];
        try {
            for (int i = 0; i < count; i++) {
                coordinates[i] = Integer.parseInt(args[start + i]);
            }
            return coordinates;
        } catch (NumberFormatException e) {
            sender.sendMessage("Coordinates must be whole numbers.");
            return null;
        }
    }

    private Integer parseNonNegative(CommandSender sender, String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                sender.sendMessage(name + " must not be negative.");
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            sender.sendMessage(name + " must be a whole number.");
            return null;
        }
    }

    private GriefWorld world(CommandSender sender, String name) {
        World bukkit = plugin.getServer().getWorld(name);
        if (bukkit == null) {
            sender.sendMessage("Unknown world: " + name);
            return null;
        }
        GriefWorld grief = plugin.grief(bukkit);
        if (grief == null) {
            sender.sendMessage("World is not tracked by GriefProt: " + name);
        }
        return grief;
    }

    private GriefWorld currentWorld(CommandSender sender) {
        if (sender instanceof Player player) {
            return world(sender, player.getWorld().getName());
        }
        sender.sendMessage("Console must specify a world.");
        return null;
    }

    private String currentWorldName(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getWorld().getName();
        }
        sender.sendMessage("Console must specify a world.");
        return null;
    }

    private boolean requireLoaded(CommandSender sender, GriefWorld world, Position position) {
        if (!world.abi().chunkIsLoaded(world.handle(), position.x() >> 4, position.z() >> 4)) {
            sender.sendMessage("Chunk " + (position.x() >> 4) + "," + (position.z() >> 4)
                    + " is not loaded in GriefProt.");
            return false;
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("Admin: stats [world] | lookup [world] <x> <y> <z> | "
                + "protection clear|add|unlock [world] <x> <y> <z> [face durability]");
        sender.sendMessage("Admin shields: list [world] | create|remove|targets-clear [world] <x> <y> <z> | "
                + "power|fuel [world] <x> <y> <z> <value> | target <add|remove> [world] "
                + "<shieldX> <shieldY> <shieldZ> <targetX> <targetY> <targetZ>");
    }

    private record Position(String worldName, int x, int y, int z) {}
}
