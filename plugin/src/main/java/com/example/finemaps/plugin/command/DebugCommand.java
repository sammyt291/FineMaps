package com.example.finemaps.plugin.command;

import com.example.finemaps.api.map.MapData;
import com.example.finemaps.core.database.DatabaseProvider;
import com.example.finemaps.core.manager.MapManager;
import com.example.finemaps.plugin.FineMapsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debug/load-testing command suite.
 *
 * /debug seed <num>
 *   - Creates <num> maps in DB with unique, sequential names
 *
 * /debug placemaps <mapsPerSecond>
 *   - Builds a moving 2x2 wall of map item frames at world max height - 5
 *   - Teleports the player along while continuously placing maps in front
 *   - Consumes unique, sequential names from the last /debug seed run
 */
public class DebugCommand implements CommandExecutor, TabCompleter {

    private static final String DEBUG_PLUGIN_ID = "debug";
    private static final int WALL_WIDTH = 2;
    private static final int WALL_HEIGHT = 2;
    private static final int MAPS_PER_WALL = WALL_WIDTH * WALL_HEIGHT;

    private final FineMapsPlugin plugin;
    private final MapManager mapManager;
    private final DatabaseProvider database;

    // Single global seed/run state (intentionally global to guarantee uniqueness)
    private final AtomicBoolean seeding = new AtomicBoolean(false);
    private volatile SeedState seedState;

    // Only allow one placement runner at a time to avoid consuming duplicates
    private volatile BukkitTask activePlaceTask;

    public DebugCommand(FineMapsPlugin plugin) {
        this.plugin = plugin;
        this.mapManager = plugin.getMapManager();
        this.database = plugin.getDatabase();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used in-game.");
            return true;
        }
        if (!sender.hasPermission("finemaps.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use debug commands.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "seed":
                return handleSeed(player, args);
            case "placemaps":
                return handlePlaceMaps(player, args);
            case "stop":
                return handleStop(player);
            default:
                sendUsage(sender);
                return true;
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Debug Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/debug seed <num>" + ChatColor.GRAY + " - Seed <num> unique maps into DB");
        sender.sendMessage(ChatColor.YELLOW + "/debug placemaps <mapsPerSecond>" + ChatColor.GRAY + " - Place 2x2 walls continuously");
        sender.sendMessage(ChatColor.YELLOW + "/debug stop" + ChatColor.GRAY + " - Stop active placement task");
    }

    private boolean handleStop(Player player) {
        BukkitTask task = activePlaceTask;
        if (task != null) {
            task.cancel();
            activePlaceTask = null;
            player.sendMessage(ChatColor.GREEN + "Stopped map placement task.");
        } else {
            player.sendMessage(ChatColor.YELLOW + "No active map placement task.");
        }
        return true;
    }

    private boolean handleSeed(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /debug seed <num>");
            return true;
        }

        int num;
        try {
            num = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid number: " + args[1]);
            return true;
        }
        if (num <= 0) {
            player.sendMessage(ChatColor.RED + "num must be > 0");
            return true;
        }

        if (!seeding.compareAndSet(false, true)) {
            player.sendMessage(ChatColor.YELLOW + "A seed operation is already running.");
            return true;
        }

        // New run prefix; names become: <prefix>_<00000001..N>
        String prefix = "dbg_" + System.currentTimeMillis();
        SeedState state = new SeedState(prefix, num);
        seedState = state;

        player.sendMessage(ChatColor.GOLD + "Seeding " + num + " maps...");
        player.sendMessage(ChatColor.GRAY + "Prefix: " + ChatColor.WHITE + prefix);

        final int batchSize = 250;
        long startedAt = System.currentTimeMillis();

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int start = 1; start <= num; start += batchSize) {
            int batchStart = start;
            int batchEnd = Math.min(num, start + batchSize - 1);
            chain = chain.thenCompose(v -> seedBatch(player, state, batchStart, batchEnd, startedAt));
        }

        chain.whenComplete((v, err) -> {
            seeding.set(false);
            if (err != null) {
                plugin.getLogger().warning("Seed failed: " + err.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.RED + "Seed failed: " + err.getMessage());
                });
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                long ms = System.currentTimeMillis() - startedAt;
                player.sendMessage(ChatColor.GREEN + "Seed complete: " + num + " maps in " + ms + "ms");
                player.sendMessage(ChatColor.GRAY + "Ready for: " + ChatColor.YELLOW + "/debug placemaps <mapsPerSecond>");
            });
        });

        return true;
    }

    private CompletableFuture<Void> seedBatch(Player player, SeedState state, int startInclusive, int endInclusive, long startedAt) {
        List<CompletableFuture<?>> futures = new ArrayList<>(endInclusive - startInclusive + 1);

        for (int i = startInclusive; i <= endInclusive; i++) {
            String name = state.prefix + "_" + String.format(Locale.ROOT, "%08d", i);
            byte color = (byte) (i % 127); // stable + cheap
            byte[] pixels = new byte[MapData.TOTAL_PIXELS];
            Arrays.fill(pixels, color);

            futures.add(database.createMap(
                DEBUG_PLUGIN_ID,
                player.getUniqueId(),
                pixels,
                null,
                0,
                0,
                0,
                name
            ));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> {
                int done = state.seeded.addAndGet(endInclusive - startInclusive + 1);
                // Light progress ping every ~2k maps (sync to main thread)
                if (done % 2000 == 0 || done == state.total) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        long ms = System.currentTimeMillis() - startedAt;
                        player.sendMessage(ChatColor.GRAY + "Seed progress: " + ChatColor.WHITE + done + "/" + state.total + ChatColor.DARK_GRAY + " (" + ms + "ms)");
                    });
                }
            });
    }

    private boolean handlePlaceMaps(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /debug placemaps <mapsPerSecond>");
            return true;
        }

        int mapsPerSecond;
        try {
            mapsPerSecond = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid number: " + args[1]);
            return true;
        }
        if (mapsPerSecond <= 0) {
            player.sendMessage(ChatColor.RED + "mapsPerSecond must be > 0");
            return true;
        }

        SeedState state = seedState;
        if (state == null) {
            player.sendMessage(ChatColor.RED + "No seed run found. Run /debug seed <num> first.");
            return true;
        }
        if (seeding.get()) {
            player.sendMessage(ChatColor.YELLOW + "Seed still running. Wait for it to finish, then run placemaps.");
            return true;
        }

        // Cancel any existing runner
        if (activePlaceTask != null) {
            activePlaceTask.cancel();
            activePlaceTask = null;
        }

        World world = player.getWorld();
        int baseY = world.getMaxHeight() - 5;
        if (baseY < world.getMinHeight() + 5) {
            player.sendMessage(ChatColor.RED + "World height bounds are too small for this test.");
            return true;
        }

        BlockFace front = yawToFace(player.getLocation().getYaw());
        BlockFace right = rotateRight(front);
        if (front == null || right == null) {
            player.sendMessage(ChatColor.RED + "Could not determine facing direction. Try facing N/E/S/W.");
            return true;
        }

        Location start = player.getLocation().clone();
        start.setY(baseY + 1);
        start.setX(Math.floor(start.getX()) + 0.5);
        start.setZ(Math.floor(start.getZ()) + 0.5);
        start.setYaw(faceToYaw(front));
        start.setPitch(0f);

        player.teleport(start);

        player.sendMessage(ChatColor.GOLD + "Starting map placement at Y=" + baseY + " facing " + front);
        player.sendMessage(ChatColor.GRAY + "Using names: " + ChatColor.WHITE + state.prefix + "_00000001.." + String.format(Locale.ROOT, "%08d", state.total));

        PlaceState placeState = new PlaceState(start, front, right, mapsPerSecond);

        activePlaceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                handleStop(player);
                return;
            }

            // Convert desired rate into a per-tick budget and place whole 2x2 walls only
            placeState.budget += (placeState.mapsPerSecond / 20.0);

            while (placeState.budget >= MAPS_PER_WALL) {
                if (!placeOneWall(player, baseY, state, placeState)) {
                    handleStop(player);
                    return;
                }
                placeState.budget -= MAPS_PER_WALL;
                placeState.stepIndex++;
            }
        }, 1L, 1L);

        return true;
    }

    private boolean placeOneWall(Player player, int baseY, SeedState seed, PlaceState place) {
        // Compute player path location (move "right" each step) and keep wall "in front"
        Location playerPos = place.start.clone().add(
            place.right.getModX() * (place.stepIndex * 3.0),
            0.0,
            place.right.getModZ() * (place.stepIndex * 3.0)
        );
        playerPos.setYaw(faceToYaw(place.front));
        playerPos.setPitch(0f);

        // Wall plane is in front of the player
        Location wallBase = playerPos.clone().add(place.front.getModX() * 2.0, -1.0, place.front.getModZ() * 2.0);

        // Teleport first so frames immediately render for that client
        player.teleport(playerPos);

        // Place backing blocks + frames
        for (int dy = 0; dy < WALL_HEIGHT; dy++) {
            for (int dx = 0; dx < WALL_WIDTH; dx++) {
                String nextName = seed.nextName();
                if (nextName == null) {
                    player.sendMessage(ChatColor.RED + "Out of seeded map names. Seed more maps first.");
                    return false;
                }

                Optional<Long> mapId = mapManager.getMapByName(DEBUG_PLUGIN_ID, nextName).join();
                if (mapId.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Missing map for name: " + nextName);
                    return false;
                }

                int x = wallBase.getBlockX() + place.right.getModX() * dx;
                int y = baseY + dy;
                int z = wallBase.getBlockZ() + place.right.getModZ() * dx;

                Block backing = wallBase.getWorld().getBlockAt(x, y, z);
                if (backing.getType().isAir()) {
                    backing.setType(Material.SMOOTH_STONE, false);
                }

                // Spawn the item frame in the AIR block in front of the backing wall.
                // ItemFrames attach to the solid block behind them; spawning inside the backing block
                // causes them to immediately detach/pop off.
                BlockFace frameFacing = place.front.getOppositeFace(); // face toward the player
                Block airBlock = backing.getRelative(frameFacing);
                if (!airBlock.getType().isAir()) {
                    airBlock.setType(Material.AIR, false);
                }

                ItemFrame frame = wallBase.getWorld().spawn(airBlock.getLocation(), ItemFrame.class);
                frame.setFacingDirection(frameFacing, true);
                try {
                    frame.setFixed(true);
                } catch (NoSuchMethodError ignored) {
                }

                long id = mapId.get();
                ItemStack item = mapManager.createMapItem(id);
                frame.setItem(item, false);
                frame.setVisible(false);

                // Proactively load/initialize this map for the placing player
                mapManager.sendMapToPlayer(player, id);
            }
        }

        return true;
    }

    private static BlockFace yawToFace(float yaw) {
        float y = yaw % 360;
        if (y < 0) y += 360;
        if (y >= 315 || y < 45) return BlockFace.SOUTH;
        if (y < 135) return BlockFace.WEST;
        if (y < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    private static float faceToYaw(BlockFace face) {
        switch (face) {
            case SOUTH:
                return 0f;
            case WEST:
                return 90f;
            case NORTH:
                return 180f;
            case EAST:
                return -90f;
            default:
                return 0f;
        }
    }

    private static BlockFace rotateRight(BlockFace face) {
        switch (face) {
            case NORTH:
                return BlockFace.EAST;
            case EAST:
                return BlockFace.SOUTH;
            case SOUTH:
                return BlockFace.WEST;
            case WEST:
                return BlockFace.NORTH;
            default:
                return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterStartsWith(args[0], Arrays.asList("seed", "placemaps", "stop"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("seed")) {
            return Collections.singletonList("<num>");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("placemaps")) {
            return Collections.singletonList("<mapsPerSecond>");
        }
        return Collections.emptyList();
    }

    private static List<String> filterStartsWith(String input, List<String> options) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(opt);
            }
        }
        return out;
    }

    private static final class SeedState {
        private final String prefix;
        private final int total;
        private final AtomicInteger seeded = new AtomicInteger(0);
        private final AtomicInteger nextIndex = new AtomicInteger(1);

        private SeedState(String prefix, int total) {
            this.prefix = prefix;
            this.total = total;
        }

        private String nextName() {
            int idx = nextIndex.getAndIncrement();
            if (idx > total) {
                return null;
            }
            return prefix + "_" + String.format(Locale.ROOT, "%08d", idx);
        }
    }

    private static final class PlaceState {
        private final Location start;
        private final BlockFace front;
        private final BlockFace right;
        private final int mapsPerSecond;
        private int stepIndex = 0;
        private double budget = 0.0;

        private PlaceState(Location start, BlockFace front, BlockFace right, int mapsPerSecond) {
            this.start = start;
            this.front = front;
            this.right = right;
            this.mapsPerSecond = mapsPerSecond;
        }
    }
}

