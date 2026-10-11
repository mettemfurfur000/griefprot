package com.tem.griefprot;

import com.tem.griefprot.config.ConfigLoader;
import com.tem.griefprot.config.PluginConfig;
import com.tem.griefprot.nativebridge.Abi;
import com.tem.griefprot.world.GriefWorld;
import com.tem.griefprot.world.WorldPolicy;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point.
 *
 * <p>Startup order matters and is deliberate: the native library is loaded and
 * its ABI checked before any world exists, because a mismatch found after the
 * first world is created is far harder to diagnose than one found immediately.
 */
public final class GriefProtPlugin extends JavaPlugin {

    /** Native major version this plugin's bindings were written against. */
    private static final int EXPECTED_ABI_MAJOR = 1;

    private Abi abi;
    private Faces faces;
    private PluginConfig config;
    private WorldPolicy policy;
    private ShieldGenerators generators;
    private final Map<UUID, GriefWorld> worlds = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = ConfigLoader.load(getConfig(), message -> getLogger().warning("griefprot: " + message));
        // Built before the library, because a failed load calls disablePlugin,
        // which calls onDisable, which walks the worlds this owns.
        generators = new ShieldGenerators(this);

        try {
            abi = new Abi();
            abi.verify(EXPECTED_ABI_MAJOR, 0);
        } catch (Throwable t) {
            // Without a working, matching library there is nothing to run. Failing
            // here is far kinder than letting every block event fail later.
            getLogger().log(Level.SEVERE, "griefprot could not start", t);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info(() -> "native library ok, abi " + (abi.getVersion() >>> 16) + "."
                + (abi.getVersion() & 0xFFFF));

        faces = new Faces(abi);
        HostSink.ensureInitialised();
        policy = WorldPolicy.from(this);

        for (World world : getServer().getWorlds()) {
            addWorld(world);
        }

        getServer().getPluginManager().registerEvents(new ChunkListener(this), this);
        getServer().getPluginManager().registerEvents(new BreakListener(this), this);
        getServer().getPluginManager().registerEvents(new ExplosionListener(this), this);
        InteractListener interact = new InteractListener(this);
        getServer().getPluginManager().registerEvents(interact, this);
        getServer().getPluginManager().registerEvents(new PistonListener(this), this);
        getServer().getPluginManager().registerEvents(new ShieldGeneratorListener(this), this);
        DebugOverlay debugOverlay = new DebugOverlay(this);
        getServer().getPluginManager().registerEvents(debugOverlay, this);
        Objects.requireNonNull(getCommand("griefprot"), "plugin.yml must declare the griefprot command")
                .setExecutor(new GriefProtCommand(debugOverlay, new AdminCommand(this)));
        debugOverlay.runTaskTimer(this, 1L, 10L);
        // Holds are resolved off the click stream, so the one that is actually
        // responsible for them is this one. Left to the interact events it would
        // take as long as the server felt like repeating them.
        getServer().getScheduler().runTaskTimer(this, interact::tick, 1L, 1L);
        // Shield callbacks inspect Bukkit blocks, so ticking must stay on the
        // server thread. The clock has one-second precision; per-tick runs add
        // no timing accuracy.
        new ShieldTicker(this).runTaskTimer(this, 1L, 20L);
        // Fuel sits in a furnace slot, and nothing says when it arrives, so this
        // has to read the world. It is on the main thread for that reason and
        // costs one visit per loaded generator per second.
        getServer().getScheduler().runTaskTimer(this, generators::tick, 20L, 20L);

        getLogger().info("enabled for " + worlds.size() + " dimension(s)");
    }

    @Override
    public void onDisable() {
        // Reverse order so the callback hosts go away before the scheduler can
        // fire again.
        for (World world : getServer().getWorlds()) {
            removeWorld(world);
        }
        getLogger().info("disabled");
    }

    /** Brings a dimension under management. Called for worlds present at startup and later. */
    public void addWorld(World world) {
        if (worlds.containsKey(world.getUID())) {
            return;
        }
        Path dataFile = getDataFolder().toPath().resolve("worlds").resolve(world.getName() + ".gpdata");
        try {
            GriefWorld griefWorld = GriefWorld.open(abi, world, policy, config, dataFile, getLogger());
            worlds.put(world.getUID(), griefWorld);
            getLogger().info("tracking " + world.getName() + " at " + dataFile);
            // A world can already have loaded chunks when the plugin starts, and
            // those will not raise a ChunkLoadEvent here, so the generators in
            // them are adopted by hand.
            generators.adoptLoadedChunks(world);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "could not track " + world.getName(), e);
        }
    }

    /** Saves and releases a dimension. */
    public void removeWorld(World world) {
        generators.forget(world);
        GriefWorld griefWorld = worlds.remove(world.getUID());
        if (griefWorld != null) {
            griefWorld.close();
        }
    }

    /** The native binding for a Bukkit world, or {@code null} if it is not tracked. */
    public GriefWorld grief(World world) {
        return worlds.get(world.getUID());
    }

    /** The native binding for a block's world, or {@code null} if not tracked. */
    public GriefWorld grief(org.bukkit.block.Block block) {
        return grief(block.getWorld());
    }

    /** Native binding. */
    public Abi abi() {
        return abi;
    }

    /** Bukkit face translation against the library's ids. */
    public Faces faces() {
        return faces;
    }

    /** Startup configuration. */
    public PluginConfig config() {
        return config;
    }

    /** The shield generators a player has built, and the rules they run on. */
    public ShieldGenerators generators() {
        return generators;
    }

    /** Every tracked dimension. */
    public Collection<GriefWorld> trackedWorlds() {
        return worlds.values();
    }
}