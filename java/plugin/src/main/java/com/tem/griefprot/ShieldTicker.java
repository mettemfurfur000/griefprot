package com.tem.griefprot;

import com.tem.griefprot.world.GriefWorld;
import java.util.logging.Level;
import org.bukkit.Chunk;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Advances shields and sweeps away decayed protection.
 *
 * <p>The ticker advances on the server thread once per 20 game ticks. Its clock
 * therefore measures game time rather than wall time: low TPS slows fuel
 * consumption and shield regeneration in real time, as expected for a
 * tick-based mechanic.
 *
 * <p>Decay runs on a timer rather than every tick. Sweeping every loaded chunk
 * every tick would be pure overhead, since the shortest interval a record can
 * have is measured in seconds.
 */
public final class ShieldTicker extends BukkitRunnable {

    /** How often the decay sweep runs, in game seconds. */
    private static final long DECAY_PERIOD_SECONDS = 5L;

    private final GriefProtPlugin plugin;

    private long gameSeconds;
    private long nextDecaySeconds = DECAY_PERIOD_SECONDS;

    public ShieldTicker(GriefProtPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (GriefWorld world : plugin.trackedWorlds()) {
            tickShields(world, gameSeconds);
        }

        if (gameSeconds >= nextDecaySeconds) {
            for (GriefWorld world : plugin.trackedWorlds()) {
                sweepDecay(world, gameSeconds);
            }
            nextDecaySeconds = gameSeconds + DECAY_PERIOD_SECONDS;
        }
        gameSeconds++;
    }

    /**
     * Ticks every shield in one dimension.
     *
     * <p>Iterating by index and re-reading the count is deliberate: a shield may
     * be destroyed while it ticks, which would leave a stale handle in the mirror
     * the world keeps, so the list is rebuilt from the store each time.
     */
    private void tickShields(GriefWorld world, long seconds) {
        int count = world.abi().shieldCount(world.handle());
        for (int index = 0; index < count; index++) {
            int shield = world.abi().shieldAt(world.handle(), index);
            if (shield == 0) {
                continue;
            }
            int granted = world.abi().shieldTick(shield, seconds);
            if (granted > 0 || plugin.getLogger().isLoggable(Level.FINE)) {
                var position = world.abi().shieldGetPos(shield);
                var state = world.abi().shieldGetState(shield);
                String message = "shield event=tick world=" + world.bukkit().getName()
                        + " x=" + position.x() + " y=" + position.y() + " z=" + position.z()
                        + " powered=" + state.powered() + " running=" + state.running()
                        + " fuel=" + state.fuel() + " points_granted=" + granted;
                if (granted > 0) {
                    plugin.getLogger().info(message);
                } else {
                    plugin.getLogger().fine(message);
                }
            }
        }
    }

    private void sweepDecay(GriefWorld world, long seconds) {
        for (Chunk chunk : world.bukkit().getLoadedChunks()) {
            world.decay(chunk, seconds, plugin.config());
        }
    }

    /** Vector helper for callers that want to show a shield's reach. */
    public static Vector reach(int range) {
        return new Vector(range, range, range);
    }
}