package com.example.finemaps.plugin.command;

import com.example.finemaps.api.map.MapData;
import com.example.finemaps.api.map.MultiBlockMap;
import com.example.finemaps.api.map.StoredMap;
import com.example.finemaps.core.config.FineMapsConfig;
import com.example.finemaps.core.image.ImageProcessor;
import com.example.finemaps.core.manager.MapManager;
import com.example.finemaps.plugin.FineMapsPlugin;
import com.example.finemaps.plugin.util.VanillaMapDatReader;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.World;

import javax.imageio.ImageIO;
import java.io.File;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Main command handler for FineMaps.
 */
public class FineMapsCommand implements CommandExecutor, TabCompleter {

    private final FineMapsPlugin plugin;
    private final MapManager mapManager;
    private final FineMapsConfig config;
    private final DebugCommand debugCommand;

    public FineMapsCommand(FineMapsPlugin plugin, DebugCommand debugCommand) {
        this.plugin = plugin;
        this.mapManager = plugin.getMapManager();
        this.config = plugin.getFineMapsConfig();
        this.debugCommand = debugCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "create":
                return handleCreate(sender, args);
            case "debug":
                return handleDebug(sender, label, args);
            case "import":
            case "importvanilla":
                return handleImportVanilla(sender, args);
            case "importall":
            case "importvanillaall":
                return handleImportAllVanilla(sender, args);
            case "url":
            case "fromurl":
                return handleFromUrl(sender, args);
            case "get":
            case "give":
                return handleGive(sender, args);
            case "delete":
            case "remove":
                return handleDelete(sender, args);
            case "list":
                return handleList(sender, args);
            case "info":
                return handleInfo(sender, args);
            case "reload":
                return handleReload(sender);
            case "stats":
                return handleStats(sender);
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + subCommand);
                sendHelp(sender);
                return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== FineMaps Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps url <name> <url> [width] [height] [dither]" + 
                          ChatColor.GRAY + " - Create map from URL with a name");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps import [mapId] [name]" +
                          ChatColor.GRAY + " - Import a vanilla filled map (held or by id)");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps importall [world]" +
                          ChatColor.GRAY + " - Import all vanilla map_*.dat files (optionally for one world)");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps get <name>" + 
                          ChatColor.GRAY + " - Get a map item by name");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps delete <name>" + 
                          ChatColor.GRAY + " - Delete a map by name");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps list [pluginId]" + 
                          ChatColor.GRAY + " - List maps");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps info <name>" + 
                          ChatColor.GRAY + " - Show map info by name");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps stats" + 
                          ChatColor.GRAY + " - Show statistics");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps reload" + 
                          ChatColor.GRAY + " - Reload config");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps debug <seed|placemaps|stop|inspect>" +
                          ChatColor.GRAY + " - Admin debug/load-test tools");
    }

    private boolean handleImportVanilla(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("finemaps.import")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to import vanilla maps.");
            return true;
        }

        // Check limit (import creates a new FineMaps map)
        if (!mapManager.canCreateMaps(player)) {
            int limit = mapManager.getMapLimit(player);
            player.sendMessage(ChatColor.RED + "You have reached your map limit (" + limit + ").");
            return true;
        }

        // Supported syntaxes:
        // - /finemaps import                      (uses held vanilla map, no name)
        // - /finemaps import <name>               (uses held vanilla map, stores name)
        // - /finemaps import <mapId> [name]       (imports by vanilla/bukkit map id)
        Integer bukkitMapId = null;
        String artName = null;

        if (args.length >= 2 && isInteger(args[1])) {
            bukkitMapId = Integer.parseInt(args[1]);
            if (args.length >= 3) {
                artName = args[2];
            }
        } else {
            if (args.length >= 2) {
                artName = args[1];
            }

            // Use held item
            org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || !plugin.getNmsAdapter().isFilledMap(hand)) {
                player.sendMessage(ChatColor.RED + "Hold a vanilla filled map, or specify a mapId.");
                player.sendMessage(ChatColor.GRAY + "Usage: /finemaps import [mapId] [name]");
                return true;
            }

            // Refuse importing FineMaps maps (already managed)
            if (mapManager.isStoredMap(hand)) {
                player.sendMessage(ChatColor.RED + "That map is already a FineMaps map.");
                player.sendMessage(ChatColor.GRAY + "To import vanilla maps, use a vanilla filled map item.");
                return true;
            }

            bukkitMapId = plugin.getNmsAdapter().getMapId(hand);
        }

        if (bukkitMapId == null || bukkitMapId < 0) {
            player.sendMessage(ChatColor.RED + "Could not determine the vanilla map id.");
            return true;
        }

        // Validate art name if provided (same rules as /finemaps url)
        if (artName != null) {
            if (!artName.matches("^[a-zA-Z0-9_-]+$")) {
                player.sendMessage(ChatColor.RED + "Art name can only contain letters, numbers, underscores, and hyphens.");
                return true;
            }
            if (artName.length() > 32) {
                player.sendMessage(ChatColor.RED + "Art name must be 32 characters or less.");
                return true;
            }
        }

        final int finalBukkitMapId = bukkitMapId;
        final String finalArtName = artName;

        // If a name was provided, ensure it isn't taken.
        CompletableFuture<Boolean> nameCheck = (finalArtName == null)
            ? CompletableFuture.completedFuture(false)
            : mapManager.isNameTaken("finemaps", finalArtName);

        nameCheck.thenAccept(taken -> {
            if (taken) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.RED + "An art with the name '" + finalArtName + "' already exists.");
                });
                return;
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.sendMessage(ChatColor.YELLOW + "Importing vanilla map #" + finalBukkitMapId + "...");
            });

            captureRenderedVanillaMapPixels(player, finalBukkitMapId, 60L).thenCompose(pixels -> {
                // Store as a new FineMaps map (snapshot)
                return mapManager.createMapWithName("finemaps", pixels, finalArtName);
            }).thenAccept(storedMap -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (finalArtName != null) {
                        mapManager.giveMapToPlayerWithName(player, storedMap.getId(), finalArtName);
                        player.sendMessage(ChatColor.GREEN + "Imported vanilla map #" + finalBukkitMapId + " as '" + finalArtName + "'.");
                    } else {
                        mapManager.giveMapToPlayer(player, storedMap.getId());
                        player.sendMessage(ChatColor.GREEN + "Imported vanilla map #" + finalBukkitMapId + " as FineMaps ID " + storedMap.getId() + ".");
                    }
                });
            }).exceptionally(err -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.RED + "Failed to import vanilla map: " + (err.getMessage() != null ? err.getMessage() : err.toString()));
                });
                return null;
            });
        });

        return true;
    }

    private boolean handleImportAllVanilla(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("finemaps.importall")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to bulk-import vanilla maps.");
            return true;
        }

        // Optional: /finemaps importall <world>
        List<World> worlds;
        if (args.length >= 2) {
            World w = Bukkit.getWorld(args[1]);
            if (w == null) {
                player.sendMessage(ChatColor.RED + "Unknown world: " + args[1]);
                return true;
            }
            worlds = Collections.singletonList(w);
        } else {
            worlds = new ArrayList<>(Bukkit.getWorlds());
        }

        // Collect files on main thread (world list is safe here).
        List<MapFile> files = new ArrayList<>();
        for (World w : worlds) {
            File dataDir = new File(w.getWorldFolder(), "data");
            File[] list = dataDir.listFiles();
            if (list == null) continue;
            for (File f : list) {
                String name = f.getName();
                if (!name.startsWith("map_") || !name.endsWith(".dat")) continue;
                int id = parseVanillaMapId(name);
                if (id < 0) continue;
                files.add(new MapFile(w.getName(), f, id));
            }
        }

        if (files.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No vanilla map_*.dat files found to import.");
            return true;
        }

        player.sendMessage(ChatColor.GOLD + "Found " + files.size() + " vanilla map files. Importing...");
        player.sendMessage(ChatColor.GRAY + "This runs asynchronously; you can keep playing.");

        AtomicInteger imported = new AtomicInteger();
        AtomicInteger importedUnnamed = new AtomicInteger();
        AtomicInteger skippedBad = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        // Import sequentially to avoid DB overload.
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (MapFile mf : files) {
            chain = chain.thenCompose(v -> CompletableFuture.supplyAsync(() -> {
                try {
                    Optional<byte[]> colorsOpt = VanillaMapDatReader.readColors(mf.file);
                    if (!colorsOpt.isPresent()) return ImportResult.skipBad(mf, "no-colors");
                    byte[] colors = colorsOpt.get();
                    if (colors.length != MapData.TOTAL_PIXELS) return ImportResult.skipBad(mf, "bad-length=" + colors.length);
                    return ImportResult.ok(mf, colors);
                } catch (Exception e) {
                    return ImportResult.fail(mf, e);
                }
            })).thenCompose(res -> {
                if (res.kind != ImportResult.Kind.OK) {
                    // Update counters and maybe log on main thread.
                    if (res.kind == ImportResult.Kind.SKIP_BAD) {
                        skippedBad.incrementAndGet();
                    } else if (res.kind == ImportResult.Kind.FAIL) {
                        failed.incrementAndGet();
                    }
                    maybeProgress(player, imported.get() + importedUnnamed.get() + skippedBad.get() + failed.get(), files.size());
                    return CompletableFuture.completedFuture(null);
                }

                String desiredName = generateVanillaImportName(res.worldName, res.vanillaId);

                return mapManager.isNameTaken("finemaps", desiredName).thenCompose(taken -> {
                    String nameToUse = taken ? null : desiredName;
                    return mapManager.createMapWithName("finemaps", res.colors, nameToUse).thenAccept(created -> {
                        if (nameToUse != null) {
                            imported.incrementAndGet();
                        } else {
                            importedUnnamed.incrementAndGet();
                        }
                        maybeProgress(player, imported.get() + importedUnnamed.get() + skippedBad.get() + failed.get(), files.size());
                    });
                }).exceptionally(err -> {
                    failed.incrementAndGet();
                    maybeProgress(player, imported.get() + importedUnnamed.get() + skippedBad.get() + failed.get(), files.size());
                    return null;
                });
            });
        }

        chain.whenComplete((v, err) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (err != null) {
                player.sendMessage(ChatColor.RED + "Bulk import finished with errors: " + err.getMessage());
            }
            player.sendMessage(ChatColor.GREEN + "Vanilla map import complete.");
            player.sendMessage(ChatColor.YELLOW + "Imported (named): " + ChatColor.WHITE + imported.get());
            player.sendMessage(ChatColor.YELLOW + "Imported (unnamed due to name collision): " + ChatColor.WHITE + importedUnnamed.get());
            player.sendMessage(ChatColor.YELLOW + "Skipped (unreadable/unsupported): " + ChatColor.WHITE + skippedBad.get());
            player.sendMessage(ChatColor.YELLOW + "Failed: " + ChatColor.WHITE + failed.get());
            player.sendMessage(ChatColor.GRAY + "Tip: use /finemaps get <name> for named imports (format: v_<world>_<id>).");
        }));

        return true;
    }

    private static final class MapFile {
        final String worldName;
        final File file;
        final int vanillaId;
        MapFile(String worldName, File file, int vanillaId) {
            this.worldName = worldName;
            this.file = file;
            this.vanillaId = vanillaId;
        }
    }

    private static final class ImportResult {
        enum Kind { OK, SKIP_BAD, FAIL }
        final Kind kind;
        final String worldName;
        final int vanillaId;
        final File file;
        final byte[] colors;

        private ImportResult(Kind kind, String worldName, int vanillaId, File file, byte[] colors) {
            this.kind = kind;
            this.worldName = worldName;
            this.vanillaId = vanillaId;
            this.file = file;
            this.colors = colors;
        }

        static ImportResult ok(MapFile mf, byte[] colors) {
            return new ImportResult(Kind.OK, mf.worldName, mf.vanillaId, mf.file, colors);
        }
        static ImportResult skipBad(MapFile mf, String reason) {
            return new ImportResult(Kind.SKIP_BAD, mf.worldName, mf.vanillaId, mf.file, null);
        }
        static ImportResult fail(MapFile mf, Exception e) {
            return new ImportResult(Kind.FAIL, mf.worldName, mf.vanillaId, mf.file, null);
        }
    }

    private void maybeProgress(Player player, int done, int total) {
        if (player == null) return;
        // Update every 100 maps and at the end.
        if (done == total || done % 100 == 0) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.sendMessage(ChatColor.GRAY + "Import progress: " + done + "/" + total);
            });
        }
    }

    private int parseVanillaMapId(String filename) {
        // map_123.dat
        try {
            if (!filename.startsWith("map_") || !filename.endsWith(".dat")) return -1;
            String mid = filename.substring("map_".length(), filename.length() - ".dat".length());
            return Integer.parseInt(mid);
        } catch (Exception e) {
            return -1;
        }
    }

    private String generateVanillaImportName(String worldName, int id) {
        String prefix = "v_";
        String cleanWorld = sanitizeName(worldName);
        String idStr = String.valueOf(id);

        // Name format: v_<world>_<id>, max 32 chars.
        int maxWorldLen = 32 - prefix.length() - 1 - idStr.length(); // 1 for underscore
        if (maxWorldLen <= 0) {
            return prefix + idStr;
        }
        if (cleanWorld.length() > maxWorldLen) {
            cleanWorld = cleanWorld.substring(0, maxWorldLen);
        }
        if (cleanWorld.isEmpty()) {
            return prefix + idStr;
        }
        return prefix + cleanWorld + "_" + idStr;
    }

    private String sanitizeName(String s) {
        if (s == null) return "";
        // Keep same rules as art names elsewhere: letters/numbers/_/-
        String cleaned = s.replaceAll("[^a-zA-Z0-9_-]", "_");
        // Collapse repeated underscores a bit
        cleaned = cleaned.replaceAll("_+", "_");
        if (cleaned.length() > 32) cleaned = cleaned.substring(0, 32);
        return cleaned;
    }

    /**
     * Captures the pixel bytes of a vanilla/Bukkit MapView by adding a temporary renderer that
     * reads the post-render canvas. This avoids NMS version-specific map-data access.
     *
     * @param player The player used to trigger rendering
     * @param bukkitMapId The vanilla/bukkit map id
     * @param timeoutTicks How long to try before failing
     */
    private CompletableFuture<byte[]> captureRenderedVanillaMapPixels(Player player, int bukkitMapId, long timeoutTicks) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();

        MapView view = Bukkit.getMap(bukkitMapId);
        if (view == null) {
            future.completeExceptionally(new IllegalArgumentException("Unknown map id " + bukkitMapId));
            return future;
        }

        // Renderer that snapshots the fully-rendered canvas into a byte[].
        MapRenderer captureRenderer = new MapRenderer(false) {
            private boolean done = false;

            @Override
            public void render(MapView map, MapCanvas canvas, Player renderPlayer) {
                if (done || future.isDone()) return;
                // We only need one render pass. Read the already-drawn pixels.
                byte[] pixels = new byte[MapData.TOTAL_PIXELS];
                int i = 0;
                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        pixels[i++] = canvas.getPixel(x, y);
                    }
                }
                done = true;
                future.complete(pixels);
            }
        };

        // Add our renderer last so it sees the final canvas.
        view.addRenderer(captureRenderer);

        // Repeatedly trigger map sending until render happens (or timeout).
        new BukkitRunnable() {
            long ticks = 0;

            @Override
            public void run() {
                if (future.isDone()) {
                    cleanup();
                    cancel();
                    return;
                }
                if (ticks++ >= timeoutTicks) {
                    future.completeExceptionally(new RuntimeException("Timed out waiting for map render"));
                    cleanup();
                    cancel();
                    return;
                }
                try {
                    player.sendMap(view);
                } catch (Throwable ignored) {
                    // Best-effort; render may still happen via normal tick updates
                }
            }

            private void cleanup() {
                try {
                    view.removeRenderer(captureRenderer);
                } catch (Throwable ignored) {
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        return future;
    }

    private boolean isInteger(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean handleDebug(CommandSender sender, String label, String[] args) {
        // /finemaps debug <sub...>
        String[] debugArgs = args.length >= 2 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        // Build usage label based on alias used (finemaps/fm/maps)
        String baseLabel = label + " debug";
        return debugCommand.handle(sender, baseLabel, debugArgs);
    }

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        
        if (!player.hasPermission("finemaps.create")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to create maps.");
            return true;
        }

        // Check limit
        if (!mapManager.canCreateMaps(player)) {
            int limit = mapManager.getMapLimit(player);
            player.sendMessage(ChatColor.RED + "You have reached your map limit (" + limit + ").");
            return true;
        }

        // Create a blank map
        byte[] pixels = new byte[MapData.TOTAL_PIXELS];
        Arrays.fill(pixels, (byte) 34); // White color

        mapManager.createMap("finemaps", pixels).thenAccept(map -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                mapManager.giveMapToPlayer(player, map.getId());
                player.sendMessage(ChatColor.GREEN + "Created blank map with ID: " + map.getId());
            });
        });

        return true;
    }

    private boolean handleFromUrl(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("finemaps.url")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to import images.");
            return true;
        }

        if (!config.getPermissions().isAllowUrlImport()) {
            player.sendMessage(ChatColor.RED + "URL image import is disabled.");
            return true;
        }

        if (args.length < 3) {
            player.sendMessage(ChatColor.RED + "Usage: /finemaps url <name> <url> [width] [height] [dither]");
            return true;
        }

        // Check limit
        if (!mapManager.canCreateMaps(player)) {
            int limit = mapManager.getMapLimit(player);
            player.sendMessage(ChatColor.RED + "You have reached your map limit (" + limit + ").");
            return true;
        }

        String artName = args[1];
        String urlStr = args[2];
        int width = 1;
        int height = 1;
        boolean dither = config.getImages().isDefaultDither();

        // Validate art name (alphanumeric, underscores, hyphens only)
        if (!artName.matches("^[a-zA-Z0-9_-]+$")) {
            player.sendMessage(ChatColor.RED + "Art name can only contain letters, numbers, underscores, and hyphens.");
            return true;
        }

        if (artName.length() > 32) {
            player.sendMessage(ChatColor.RED + "Art name must be 32 characters or less.");
            return true;
        }

        // Parse optional arguments
        if (args.length >= 4) {
            try {
                width = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid width: " + args[3]);
                return true;
            }
        }

        if (args.length >= 5) {
            try {
                height = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid height: " + args[4]);
                return true;
            }
        }

        if (args.length >= 6) {
            dither = Boolean.parseBoolean(args[5]);
        }

        // Validate dimensions
        if (width < 1 || width > config.getImages().getMaxWidth() ||
            height < 1 || height > config.getImages().getMaxHeight()) {
            player.sendMessage(ChatColor.RED + "Invalid dimensions. Max: " + 
                              config.getImages().getMaxWidth() + "x" + config.getImages().getMaxHeight());
            return true;
        }

        // Validate URL domain if configured
        List<String> allowedDomains = config.getPermissions().getAllowedDomains();
        if (!allowedDomains.isEmpty()) {
            try {
                String host = new URL(urlStr).getHost().toLowerCase();
                boolean allowed = allowedDomains.stream()
                    .anyMatch(domain -> host.endsWith(domain.toLowerCase()));
                if (!allowed) {
                    player.sendMessage(ChatColor.RED + "Images from that domain are not allowed.");
                    return true;
                }
            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "Invalid URL.");
                return true;
            }
        }

        final String finalArtName = artName;
        final int finalWidth = width;
        final int finalHeight = height;
        final boolean finalDither = dither;

        // Check if name is already taken
        mapManager.isNameTaken("finemaps", artName).thenAccept(taken -> {
            if (taken) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.RED + "An art with the name '" + finalArtName + "' already exists.");
                });
                return;
            }

            player.sendMessage(ChatColor.YELLOW + "Downloading and processing image...");

            // Process image asynchronously
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    BufferedImage image = ImageIO.read(new URL(urlStr));
                    if (image == null) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            player.sendMessage(ChatColor.RED + "Failed to download image.");
                        });
                        return;
                    }

                    // Check image size
                    int maxSize = config.getPermissions().getMaxImportSize();
                    if (image.getWidth() > maxSize || image.getHeight() > maxSize) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            player.sendMessage(ChatColor.RED + "Image too large. Max size: " + maxSize + "x" + maxSize);
                        });
                        return;
                    }

                    // Create map(s)
                    if (finalWidth == 1 && finalHeight == 1) {
                        // Single map - create with metadata (art name)
                        mapManager.createMapFromImageWithName("finemaps", image, finalDither, finalArtName).thenAccept(map -> {
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                mapManager.giveMapToPlayerWithName(player, map.getId(), finalArtName);
                                player.sendMessage(ChatColor.GREEN + "Created map '" + finalArtName + "' from image!");
                            });
                        });
                    } else {
                        // Multi-block map with name stored in group metadata
                        mapManager.createMultiBlockMapWithName("finemaps", image, finalWidth, finalHeight, finalDither, finalArtName)
                            .thenAccept(multiMap -> {
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    mapManager.giveMultiBlockMapToPlayerWithName(player, multiMap.getGroupId(), finalArtName);
                                    player.sendMessage(ChatColor.GREEN + "Created " + finalWidth + "x" + finalHeight + 
                                                      " map '" + finalArtName + "'!");
                                });
                            });
                    }
                } catch (Exception e) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        player.sendMessage(ChatColor.RED + "Error processing image: " + e.getMessage());
                    });
                }
            });
        });

        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("finemaps.get")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to get maps.");
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /finemaps get <name>");
            return true;
        }

        String artName = args[1];

        // First try to find a multi-block map group with this name
        mapManager.getGroupByName("finemaps", artName).thenAccept(optGroupId -> {
            if (optGroupId.isPresent()) {
                long groupId = optGroupId.get();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    mapManager.giveMultiBlockMapToPlayerWithName(player, groupId, artName);
                    player.sendMessage(ChatColor.GREEN + "Given map '" + artName + "'");
                });
            } else {
                // Try to find a single map with this name
                mapManager.getMapByName("finemaps", artName).thenAccept(optMapId -> {
                    if (optMapId.isPresent()) {
                        long mapId = optMapId.get();
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            mapManager.giveMapToPlayerWithName(player, mapId, artName);
                            player.sendMessage(ChatColor.GREEN + "Given map '" + artName + "'");
                        });
                    } else {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            player.sendMessage(ChatColor.RED + "Map not found: " + artName);
                        });
                    }
                });
            }
        });

        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("finemaps.delete")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to delete maps.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /finemaps delete <name>");
            return true;
        }

        String artName = args[1];

        // First try to find a multi-block map group with this name
        mapManager.getGroupByName("finemaps", artName).thenAccept(optGroupId -> {
            if (optGroupId.isPresent()) {
                long groupId = optGroupId.get();
                mapManager.deleteMultiBlockMap(groupId).thenAccept(success -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (success) {
                            sender.sendMessage(ChatColor.GREEN + "Deleted map '" + artName + "'");
                        } else {
                            sender.sendMessage(ChatColor.RED + "Failed to delete map '" + artName + "'");
                        }
                    });
                });
            } else {
                // Try to find a single map with this name
                mapManager.getMapByName("finemaps", artName).thenAccept(optMapId -> {
                    if (optMapId.isPresent()) {
                        long mapId = optMapId.get();
                        mapManager.deleteMap(mapId).thenAccept(success -> {
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                if (success) {
                                    sender.sendMessage(ChatColor.GREEN + "Deleted map '" + artName + "'");
                                } else {
                                    sender.sendMessage(ChatColor.RED + "Failed to delete map '" + artName + "'");
                                }
                            });
                        });
                    } else {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            sender.sendMessage(ChatColor.RED + "Map not found: " + artName);
                        });
                    }
                });
            }
        });

        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("finemaps.list")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to list maps.");
            return true;
        }

        String pluginId = args.length >= 2 ? args[1] : "finemaps";

        mapManager.getMapsByPlugin(pluginId).thenAccept(maps -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (maps.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No maps found for plugin: " + pluginId);
                    return;
                }

                sender.sendMessage(ChatColor.GOLD + "=== Maps for " + pluginId + " (" + maps.size() + ") ===");
                for (StoredMap map : maps.subList(0, Math.min(20, maps.size()))) {
                    String info = ChatColor.YELLOW + "" + map.getId();
                    if (map.isMultiBlock()) {
                        info += ChatColor.GRAY + " (group: " + map.getGroupId() + ", pos: " + 
                               map.getGridX() + "," + map.getGridY() + ")";
                    }
                    sender.sendMessage(info);
                }
                if (maps.size() > 20) {
                    sender.sendMessage(ChatColor.GRAY + "... and " + (maps.size() - 20) + " more");
                }
            });
        });

        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("finemaps.info")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to view map info.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /finemaps info <name>");
            return true;
        }

        String artName = args[1];

        // First try to find a multi-block map group with this name
        mapManager.getGroupByName("finemaps", artName).thenAccept(optGroupId -> {
            if (optGroupId.isPresent()) {
                long groupId = optGroupId.get();
                mapManager.getMultiBlockMap(groupId).thenAccept(optMultiMap -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!optMultiMap.isPresent()) {
                            sender.sendMessage(ChatColor.RED + "Map not found: " + artName);
                            return;
                        }

                        MultiBlockMap multiMap = optMultiMap.get();
                        sender.sendMessage(ChatColor.GOLD + "=== Map Info: " + artName + " ===");
                        sender.sendMessage(ChatColor.YELLOW + "Name: " + ChatColor.WHITE + artName);
                        sender.sendMessage(ChatColor.YELLOW + "Type: " + ChatColor.WHITE + "Multi-block");
                        sender.sendMessage(ChatColor.YELLOW + "Size: " + ChatColor.WHITE + 
                                          multiMap.getWidth() + "x" + multiMap.getHeight() + " blocks");
                        sender.sendMessage(ChatColor.YELLOW + "Group ID: " + ChatColor.WHITE + groupId);
                        sender.sendMessage(ChatColor.YELLOW + "Plugin: " + ChatColor.WHITE + multiMap.getPluginId());
                        sender.sendMessage(ChatColor.YELLOW + "Creator: " + ChatColor.WHITE + 
                                          (multiMap.getCreatorUUID() != null ? multiMap.getCreatorUUID() : "System"));
                        sender.sendMessage(ChatColor.YELLOW + "Created: " + ChatColor.WHITE + 
                                          new Date(multiMap.getCreatedAt()));
                    });
                });
            } else {
                // Try to find a single map with this name
                mapManager.getMapByName("finemaps", artName).thenAccept(optMapId -> {
                    if (optMapId.isPresent()) {
                        long mapId = optMapId.get();
                        mapManager.getMap(mapId).thenAccept(optMap -> {
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                if (!optMap.isPresent()) {
                                    sender.sendMessage(ChatColor.RED + "Map not found: " + artName);
                                    return;
                                }

                                StoredMap map = optMap.get();
                                sender.sendMessage(ChatColor.GOLD + "=== Map Info: " + artName + " ===");
                                sender.sendMessage(ChatColor.YELLOW + "Name: " + ChatColor.WHITE + artName);
                                sender.sendMessage(ChatColor.YELLOW + "Type: " + ChatColor.WHITE + "Single block");
                                sender.sendMessage(ChatColor.YELLOW + "ID: " + ChatColor.WHITE + map.getId());
                                sender.sendMessage(ChatColor.YELLOW + "Plugin: " + ChatColor.WHITE + map.getPluginId());
                                sender.sendMessage(ChatColor.YELLOW + "Creator: " + ChatColor.WHITE + 
                                                  (map.getCreatorUUID() != null ? map.getCreatorUUID() : "System"));
                                sender.sendMessage(ChatColor.YELLOW + "Created: " + ChatColor.WHITE + 
                                                  new Date(map.getCreatedAt()));
                                sender.sendMessage(ChatColor.YELLOW + "Last Accessed: " + ChatColor.WHITE + 
                                                  new Date(map.getLastAccessed()));
                            });
                        });
                    } else {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            sender.sendMessage(ChatColor.RED + "Map not found: " + artName);
                        });
                    }
                });
            }
        });

        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("finemaps.reload")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to reload.");
            return true;
        }

        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "Configuration reloaded.");
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("finemaps.stats")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to view stats.");
            return true;
        }

        mapManager.getMapCount("finemaps").thenAccept(count -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(ChatColor.GOLD + "=== FineMaps Stats ===");
                sender.sendMessage(ChatColor.YELLOW + "Total maps: " + ChatColor.WHITE + count);
                sender.sendMessage(ChatColor.YELLOW + "Database type: " + ChatColor.WHITE + 
                                  (config.getDatabase().isMySQL() ? "MySQL" : "SQLite"));
                sender.sendMessage(ChatColor.YELLOW + "NMS version: " + ChatColor.WHITE + 
                                  plugin.getNmsAdapter().getVersion());
            });
        });

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterStartsWith(args[0], Arrays.asList(
                "url", "get", "delete", "list", "info", "stats", "reload", "create", "debug", "import", "importall"
            ));
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("debug")) {
                return debugCommand.tabComplete(new String[]{args[1]});
            }
            if (sub.equals("url") || sub.equals("fromurl")) {
                return Collections.singletonList("<name>");
            }
            if (sub.equals("import") || sub.equals("importvanilla")) {
                return Arrays.asList("<mapId>", "<name>");
            }
            if (sub.equals("importall") || sub.equals("importvanillaall")) {
                // Suggest world names
                List<String> worlds = Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList());
                worlds.add(0, "all");
                return filterStartsWith(args[1], worlds);
            }
            if (sub.equals("get") || sub.equals("give") || sub.equals("delete") || 
                sub.equals("remove") || sub.equals("info")) {
                return Collections.singletonList("<name>");
            }
            if (sub.equals("list")) {
                return Collections.singletonList("<pluginId>");
            }
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("debug")) {
            String[] debugArgs = Arrays.copyOfRange(args, 1, args.length);
            return debugCommand.tabComplete(debugArgs);
        }

        if (args.length >= 3 && (args[0].equalsIgnoreCase("url") || args[0].equalsIgnoreCase("fromurl"))) {
            if (args.length == 3) return Collections.singletonList("<url>");
            if (args.length == 4) return Collections.singletonList("<width>");
            if (args.length == 5) return Collections.singletonList("<height>");
            if (args.length == 6) return Arrays.asList("true", "false");
        }

        return Collections.emptyList();
    }

    private List<String> filterStartsWith(String input, List<String> options) {
        String lower = input.toLowerCase();
        return options.stream()
            .filter(opt -> opt.toLowerCase().startsWith(lower))
            .collect(Collectors.toList());
    }
}
