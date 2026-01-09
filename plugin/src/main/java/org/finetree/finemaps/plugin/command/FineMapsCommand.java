package org.finetree.finemaps.plugin.command;

import org.finetree.finemaps.api.map.MapData;
import org.finetree.finemaps.api.map.MultiBlockMap;
import org.finetree.finemaps.api.map.StoredMap;
import org.finetree.finemaps.api.map.ArtSummary;
import org.finetree.finemaps.core.config.FineMapsConfig;
import org.finetree.finemaps.core.image.ImageProcessor;
import org.finetree.finemaps.core.manager.MapManager;
import org.finetree.finemaps.core.util.ByteSizeParser;
import org.finetree.finemaps.core.util.FineMapsScheduler;
import org.finetree.finemaps.plugin.FineMapsPlugin;
import org.finetree.finemaps.plugin.util.VanillaMapDatReader;
import org.finetree.finemaps.plugin.url.AnimatedImage;
import org.finetree.finemaps.plugin.url.GenericImageDecoder;
import org.finetree.finemaps.plugin.url.GifFrameStreamer;
import org.finetree.finemaps.plugin.url.UrlCache;
import org.finetree.finemaps.plugin.url.UrlDownloader;
import org.finetree.finemaps.plugin.url.VideoDecoder;
import org.finetree.finemaps.plugin.recovery.PendingMapRecovery;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import redempt.redlib.inventorygui.InventoryGUI;
import redempt.redlib.inventorygui.ItemButton;
import redempt.redlib.inventorygui.PaginationPanel;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

/**
 * Main command handler for FineMaps.
 */
public class FineMapsCommand implements CommandExecutor, TabCompleter {

    private final FineMapsPlugin plugin;
    private final MapManager mapManager;
    private final FineMapsConfig config;
    private final DebugCommand debugCommand;

    // Auto-generated names for /fm importall world map imports:
    // - v_<world>_<id>
    // - v_<id> (fallback if world name can't fit)
    private static final java.util.regex.Pattern VANILLA_WORLD_IMPORT_NAME =
        java.util.regex.Pattern.compile("^v_(?:\\d+|[a-zA-Z0-9_-]+_\\d+)$");

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
            case "buy":
                return handleBuy(sender, args);
            case "gui":
            case "gallery":
            case "arts":
                return handleGui(sender, args);
            case "config":
                return handleConfig(sender, args);
            case "debug":
                return handleDebug(sender, label, args);
            case "convert":
            case "import":
            case "importvanilla":
                return handleImportVanilla(sender, args);
            case "convertall":
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
            case "anim":
            case "animation":
                return handleAnim(sender, args);
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + subCommand);
                sendHelp(sender);
                return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== FineMaps Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps url <url> <name> [w] [h] [mode] [fps]" +
                          ChatColor.GRAY + " - Create map from URL (supports GIF/APNG/WEBP/MP4/WEBM)");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps convert [mapId] [name]" +
                          ChatColor.GRAY + " - Convert/import a vanilla filled map (held or by id)");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps import [mapId] [name]" +
                          ChatColor.GRAY + " - Import a vanilla filled map (held or by id)");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps convertall [world]" +
                          ChatColor.GRAY + " - Convert/import all vanilla map_*.dat files (optionally for one world)");
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
        sender.sendMessage(ChatColor.YELLOW + "/finemaps buy <name>" +
                          ChatColor.GRAY + " - Buy a map art (Vault)");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps gui" +
                          ChatColor.GRAY + " - Open paginated map art browser");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps config <get|set|reset> ..." +
                          ChatColor.GRAY + " - View/change config values");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps stats" + 
                          ChatColor.GRAY + " - Show statistics");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps reload" + 
                          ChatColor.GRAY + " - Reload config");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps anim <restart|pause|skip <time>>" +
                          ChatColor.GRAY + " - Control an animated map (held or looked-at frame)");
        sender.sendMessage(ChatColor.YELLOW + "/finemaps debug <seed|placemaps|stop|inspect>" +
                          ChatColor.GRAY + " - Admin debug/load-test tools");
    }

    private boolean handleBuy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("finemaps.buy")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to buy maps.");
            return true;
        }
        if (config.getEconomy() == null || !config.getEconomy().isEnableBuyCommand()) {
            player.sendMessage(ChatColor.RED + "/fm buy is disabled.");
            return true;
        }
        if (!plugin.isEconomyEnabled()) {
            player.sendMessage(ChatColor.RED + "Economy is not available (Vault/provider missing or disabled).");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /finemaps buy <name>");
            return true;
        }

        String name = args[1];
        buyArt(player, name, true);
        return true;
    }

    private boolean handleGui(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("finemaps.gui")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to open the GUI.");
            return true;
        }
        if (config.getGui() == null || !config.getGui().isEnabled()) {
            player.sendMessage(ChatColor.RED + "The GUI is disabled.");
            return true;
        }

        // Load/refresh arts asynchronously, then open GUI on main thread.
        mapManager.refreshArtSummaries("finemaps").thenAccept(arts -> {
            FineMapsScheduler.runForEntity(plugin, player, () -> {
                if (!player.isOnline()) return;
                openArtBrowserGui(player, arts);
            });
        });
        return true;
    }

    private boolean handleConfig(CommandSender sender, String[] args) {
        if (!sender.hasPermission("finemaps.config")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to manage config.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /finemaps config <get|set|reset> ...");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        redempt.redlib.config.ConfigManager cm = plugin.getConfigManager();
        if (cm == null) {
            sender.sendMessage(ChatColor.RED + "Config manager is not available.");
            return true;
        }
        FileConfiguration fc = cm.getConfig();

        switch (action) {
            case "get": {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /finemaps config get <path>");
                    return true;
                }
                String path = args[2];
                Object value = fc.get(path);
                if (value == null) {
                    sender.sendMessage(ChatColor.YELLOW + path + ChatColor.GRAY + " = " + ChatColor.RED + "<null>");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + path + ChatColor.GRAY + " = " + ChatColor.WHITE + String.valueOf(value));
                }
                return true;
            }
            case "set": {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /finemaps config set <path> <value>");
                    return true;
                }
                String path = args[2];
                String raw = args[3];

                Object existing = fc.get(path);
                Object parsed = parseConfigValue(existing, raw);
                fc.set(path, parsed);
                cm.save();
                plugin.reloadFineMapsConfiguration();
                sender.sendMessage(ChatColor.GREEN + "Set " + ChatColor.YELLOW + path + ChatColor.GREEN + " to " + ChatColor.WHITE + String.valueOf(parsed));
                return true;
            }
            case "reset": {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /finemaps config reset <path>");
                    return true;
                }
                String path = args[2];
                fc.set(path, null);
                cm.save();
                // Restore defaults for removed keys and reload.
                cm.saveDefaults();
                plugin.reloadFineMapsConfiguration();
                sender.sendMessage(ChatColor.GREEN + "Reset " + ChatColor.YELLOW + path + ChatColor.GREEN + " to default.");
                return true;
            }
            default:
                sender.sendMessage(ChatColor.RED + "Usage: /finemaps config <get|set|reset> ...");
                return true;
        }
    }

    private Object parseConfigValue(Object existing, String raw) {
        if (existing instanceof Boolean) {
            return Boolean.parseBoolean(raw);
        }
        if (existing instanceof Integer) {
            try { return Integer.parseInt(raw); } catch (Exception ignored) { return raw; }
        }
        if (existing instanceof Long) {
            try { return Long.parseLong(raw); } catch (Exception ignored) { return raw; }
        }
        if (existing instanceof Double || existing instanceof Float) {
            try { return Double.parseDouble(raw); } catch (Exception ignored) { return raw; }
        }
        // Heuristic if key doesn't exist yet.
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.parseBoolean(raw);
        }
        try {
            if (raw.contains(".")) return Double.parseDouble(raw);
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
        }
        return raw;
    }

    private void openArtBrowserGui(Player player, List<ArtSummary> arts) {
        if (player == null || !player.isOnline()) return;
        List<ArtSummary> list = (arts != null ? arts : Collections.<ArtSummary>emptyList())
            .stream()
            // Hide auto-imported vanilla world maps from the GUI (they can spam the gallery).
            .filter(a -> !isVanillaWorldImportArt(a))
            .collect(Collectors.toList());

        InventoryGUI gui = new InventoryGUI(54, ChatColor.DARK_GREEN + "FineMaps Arts");
        gui.setDestroyOnClose(true);

        // Fill bottom row with filler.
        gui.fill(45, 53, InventoryGUI.FILLER);

        PaginationPanel panel = new PaginationPanel(gui, InventoryGUI.FILLER);
        panel.addSlots(0, 44);

        boolean buyEnabled = plugin.isEconomyEnabled() && config.getEconomy() != null && config.getEconomy().isEnableBuyCommand();

        for (ArtSummary art : list) {
            ItemStack display = createGuiDisplayItem(art, buyEnabled);
            ItemButton btn = ItemButton.create(display, e -> {
                e.setCancelled(true);
                if (!player.isOnline()) return;
                if (buyEnabled) {
                    buyArt(player, art.getName(), false);
                } else {
                    if (!player.hasPermission("finemaps.get")) {
                        player.sendMessage(ChatColor.RED + "You don't have permission to get maps.");
                        return;
                    }
                    giveArtByName(player, art.getName());
                }
            });
            panel.addPagedButton(btn);
        }

        // Prev/Next buttons
        gui.addButton(ItemButton.create(namedButton(Material.ARROW, ChatColor.YELLOW + "Previous Page"), e -> {
            e.setCancelled(true);
            panel.prevPage();
            gui.update();
        }), 45);
        gui.addButton(ItemButton.create(namedButton(Material.ARROW, ChatColor.YELLOW + "Next Page"), e -> {
            e.setCancelled(true);
            panel.nextPage();
            gui.update();
        }), 53);

        panel.updatePage();
        gui.open(player);
    }

    private ItemStack namedButton(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createGuiDisplayItem(ArtSummary art, boolean buyEnabled) {
        if (art == null) return new ItemStack(Material.MAP);

        ItemStack item;
        if (art.isMultiBlock()) {
            item = mapManager.createMultiBlockMapItemWithName(art.getId(), art.getName());
            if (item == null) {
                item = new ItemStack(Material.MAP);
            }
        } else {
            item = mapManager.createMapItem(art.getId());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + "Map: " + art.getName());
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Size: 1x1");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
        }

        if (buyEnabled && config.getGui() != null && config.getGui().isShowCostInTooltip()) {
            double cost = costFor(art);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(ChatColor.GREEN + "Cost: " + ChatColor.WHITE + formatMoney(cost));
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    private String formatMoney(double amount) {
        Economy eco = plugin.getEconomy();
        try {
            if (eco != null) return eco.format(amount);
        } catch (Throwable ignored) {
        }
        return String.format(Locale.US, "%.2f", amount);
    }

    private double costFor(ArtSummary art) {
        if (art == null || config.getEconomy() == null) return 0.0;
        double base = Math.max(0.0, config.getEconomy().getCostPerMap());
        if (!art.isMultiBlock()) return base;
        if (!config.getEconomy().isMultiplyByTiles()) return base;
        return base * Math.max(1, art.getWidth()) * Math.max(1, art.getHeight());
    }

    private void buyArt(Player player, String name, boolean fromCommand) {
        if (player == null || name == null) return;
        Economy eco = plugin.getEconomy();
        if (eco == null || !plugin.isEconomyEnabled()) {
            player.sendMessage(ChatColor.RED + "Economy is not available.");
            return;
        }

        // Resolve art name (group first, then single).
        mapManager.getGroupByName("finemaps", name).thenAccept(optGroup -> {
            if (optGroup.isPresent()) {
                long groupId = optGroup.get();
                mapManager.getMultiBlockMap(groupId).thenAccept(optMap -> {
                    if (!optMap.isPresent()) {
                        FineMapsScheduler.runForEntity(plugin, player, () -> {
                            if (!player.isOnline()) return;
                            player.sendMessage(ChatColor.RED + "Map not found: " + name);
                        });
                        return;
                    }
                    MultiBlockMap mm = optMap.get();
                    ArtSummary art = new ArtSummary("finemaps", name, true, groupId, mm.getWidth(), mm.getHeight());
                    double cost = costFor(art);
                    attemptChargeAndGive(player, art, cost);
                });
                return;
            }
            mapManager.getMapByName("finemaps", name).thenAccept(optMapId -> {
                if (!optMapId.isPresent()) {
                    FineMapsScheduler.runForEntity(plugin, player, () -> {
                        if (!player.isOnline()) return;
                        player.sendMessage(ChatColor.RED + "Map not found: " + name);
                    });
                    return;
                }
                long mapId = optMapId.get();
                ArtSummary art = new ArtSummary("finemaps", name, false, mapId, 1, 1);
                double cost = costFor(art);
                attemptChargeAndGive(player, art, cost);
            });
        });
    }

    private void attemptChargeAndGive(Player player, ArtSummary art, double cost) {
        Economy eco = plugin.getEconomy();
        if (eco == null) return;

        FineMapsScheduler.runForEntity(plugin, player, () -> {
            if (!player.isOnline()) return;
            if (cost > 0.0) {
                try {
                    if (!eco.has(player, cost)) {
                        player.sendMessage(ChatColor.RED + "You need " + ChatColor.WHITE + formatMoney(cost) + ChatColor.RED + " to buy this.");
                        return;
                    }
                    net.milkbowl.vault.economy.EconomyResponse res = eco.withdrawPlayer(player, cost);
                    if (res == null || !res.transactionSuccess()) {
                        player.sendMessage(ChatColor.RED + "Purchase failed: " + (res != null ? res.errorMessage : "unknown"));
                        return;
                    }
                } catch (Throwable t) {
                    player.sendMessage(ChatColor.RED + "Purchase failed.");
                    return;
                }
            }

            if (art.isMultiBlock()) {
                mapManager.giveMultiBlockMapToPlayerWithName(player, art.getId(), art.getName());
            } else {
                mapManager.giveMapToPlayerWithName(player, art.getId(), art.getName());
            }
            player.sendMessage(ChatColor.GREEN + "Purchased " + ChatColor.YELLOW + art.getName() + ChatColor.GREEN + " for " + ChatColor.WHITE + formatMoney(cost));
        });
    }

    private void giveArtByName(Player player, String name) {
        if (player == null || name == null) return;
        mapManager.getGroupByName("finemaps", name).thenAccept(optGroupId -> {
            if (optGroupId.isPresent()) {
                long groupId = optGroupId.get();
                FineMapsScheduler.runForEntity(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    mapManager.giveMultiBlockMapToPlayerWithName(player, groupId, name);
                });
                return;
            }
            mapManager.getMapByName("finemaps", name).thenAccept(optMapId -> {
                if (!optMapId.isPresent()) {
                    FineMapsScheduler.runForEntity(plugin, player, () -> {
                        if (!player.isOnline()) return;
                        player.sendMessage(ChatColor.RED + "Map not found: " + name);
                    });
                    return;
                }
                long mapId = optMapId.get();
                FineMapsScheduler.runForEntity(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    mapManager.giveMapToPlayerWithName(player, mapId, name);
                });
            });
        });
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
                FineMapsScheduler.runForEntity(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    player.sendMessage(ChatColor.RED + "An art with the name '" + finalArtName + "' already exists.");
                });
                return;
            }

            FineMapsScheduler.runForEntity(plugin, player, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(ChatColor.YELLOW + "Importing vanilla map #" + finalBukkitMapId + "...");
            });

            captureRenderedVanillaMapPixels(player, finalBukkitMapId, 60L).thenCompose(pixels -> {
                // Store as a new FineMaps map (snapshot)
                return mapManager.createMapWithName("finemaps", pixels, finalArtName);
            }).thenAccept(storedMap -> {
                FineMapsScheduler.runForEntity(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    if (finalArtName != null) {
                        mapManager.giveMapToPlayerWithName(player, storedMap.getId(), finalArtName);
                        player.sendMessage(ChatColor.GREEN + "Imported vanilla map #" + finalBukkitMapId + " as '" + finalArtName + "'.");
                    } else {
                        mapManager.giveMapToPlayer(player, storedMap.getId());
                        player.sendMessage(ChatColor.GREEN + "Imported vanilla map #" + finalBukkitMapId + " as FineMaps ID " + storedMap.getId() + ".");
                    }
                });
            }).exceptionally(err -> {
                FineMapsScheduler.runForEntity(plugin, player, () -> {
                    if (!player.isOnline()) return;
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

        chain.whenComplete((v, err) -> FineMapsScheduler.runForEntity(plugin, player, () -> {
            if (!player.isOnline()) return;
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
            FineMapsScheduler.runForEntity(plugin, player, () -> {
                if (!player.isOnline()) return;
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
        final long[] ticks = new long[] {0L};
        final AtomicReference<FineMapsScheduler.Task> taskRef = new AtomicReference<>(null);

        Runnable cleanup = () -> {
            try {
                view.removeRenderer(captureRenderer);
            } catch (Throwable ignored) {
            }
        };

        Runnable tick = () -> {
            if (future.isDone()) {
                cleanup.run();
                FineMapsScheduler.Task t = taskRef.getAndSet(null);
                if (t != null) t.cancel();
                return;
            }
            if (ticks[0]++ >= timeoutTicks) {
                future.completeExceptionally(new RuntimeException("Timed out waiting for map render"));
                cleanup.run();
                FineMapsScheduler.Task t = taskRef.getAndSet(null);
                if (t != null) t.cancel();
                return;
            }
            try {
                if (player.isOnline()) {
                    player.sendMap(view);
                }
            } catch (Throwable ignored) {
                // Best-effort; render may still happen via normal tick updates
            }
        };

        FineMapsScheduler.Task handle = FineMapsScheduler.runForEntityRepeating(plugin, player, tick, 0L, 1L);
        if (handle == null) {
            handle = FineMapsScheduler.runSyncRepeating(plugin, tick, 0L, 1L);
        }
        taskRef.set(handle);

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
            FineMapsScheduler.runForEntity(plugin, player, () -> {
                if (!player.isOnline()) return;
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
            player.sendMessage(ChatColor.RED + "Usage: /finemaps url <url> <name> [w] [h] [mode] [fps]");
            player.sendMessage(ChatColor.GRAY + "Mode: raster | nearest");
            return true;
        }

        // Check limit
        if (!mapManager.canCreateMaps(player)) {
            int limit = mapManager.getMapLimit(player);
            player.sendMessage(ChatColor.RED + "You have reached your map limit (" + limit + ").");
            return true;
        }

        // New syntax:
        // /fm url <url> <name> [w] [h] [mode] [fps]
        // Legacy fallback:
        // /fm url <name> <url> [w] [h] [dither]
        String urlStr;
        String artName;

        if (looksLikeUrl(args[1]) && !looksLikeUrl(args[2])) {
            urlStr = args[1];
            artName = args[2];
        } else if (!looksLikeUrl(args[1]) && looksLikeUrl(args[2])) {
            // legacy
            artName = args[1];
            urlStr = args[2];
        } else {
            // default to new order
            urlStr = args[1];
            artName = args[2];
        }

        int width = 1;
        int height = 1;
        boolean raster = config.getImages().isDefaultDither();
        Integer fps = null;

        // Validate art name (alphanumeric, underscores, hyphens only)
        if (!artName.matches("^[a-zA-Z0-9_-]+$")) {
            player.sendMessage(ChatColor.RED + "Art name can only contain letters, numbers, underscores, and hyphens.");
            return true;
        }

        if (artName.length() > 32) {
            player.sendMessage(ChatColor.RED + "Art name must be 32 characters or less.");
            return true;
        }

        // Parse optional arguments based on whether we're in new or legacy mode
        boolean legacy = (!looksLikeUrl(args[1]) && looksLikeUrl(args[2]));

        // width
        if (args.length >= 4) {
            try {
                width = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid width: " + args[3]);
                return true;
            }
        }

        // height
        if (args.length >= 5) {
            try {
                height = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid height: " + args[4]);
                return true;
            }
        }

        if (args.length >= 6) {
            raster = parseRasterArg(args[5], config.getImages().isDefaultDither());
        }

        if (!legacy && args.length >= 7) {
            try {
                fps = Integer.parseInt(args[6]);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid fps: " + args[6]);
                return true;
            }
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
        final boolean finalRaster = raster;
        final Integer finalFps = fps;

        // Check if name is already taken
        mapManager.isNameTaken("finemaps", artName).thenAccept(taken -> {
            if (taken) {
                FineMapsScheduler.runForEntity(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    player.sendMessage(ChatColor.RED + "An art with the name '" + finalArtName + "' already exists.");
                });
                return;
            }

            player.sendMessage(ChatColor.YELLOW + "Downloading and processing image...");

            // Process image asynchronously
            FineMapsScheduler.runAsync(plugin, () -> {
                try {
                    // Download (with max-bytes sanity check) and cache original file on disk
                    Path downloaded = downloadAndCacheUrlImage(urlStr);

                    int effectiveFps = (finalFps != null ? finalFps : config.getImages().getDefaultAnimatedFps());
                    if (effectiveFps <= 0) effectiveFps = config.getImages().getDefaultAnimatedFps();

                    // Decode static or animated frames (images + optional video via ffmpeg)
                    String downloadedName = downloaded.getFileName().toString().toLowerCase(Locale.ROOT);
                    String urlHint = urlStr != null ? urlStr.toLowerCase(Locale.ROOT) : "";
                    AnimatedImage decoded;
                    boolean looksLikeVideo =
                        downloadedName.endsWith(".mp4") || downloadedName.endsWith(".webm") ||
                        urlHint.contains(".mp4") || urlHint.contains(".webm") ||
                        urlHint.contains("format=mp4") || urlHint.contains("format=webm");
                    if (looksLikeVideo) {
                        decoded = VideoDecoder.decode(
                            downloaded,
                            urlStr,
                            effectiveFps,
                            config.getImages().getMaxVideoFrames(),
                            resolveFfmpegPath()
                        );
                    } else if (downloadedName.endsWith(".gif") || urlHint.endsWith(".gif") || (urlHint.contains(".gif") && !urlHint.contains(".png"))) {
                        // Streaming GIF path: supports very long animations without holding all frames in RAM.
                        GifStreamResult res = processGifStreamingToCache(
                            urlStr,
                            downloaded,
                            finalWidth,
                            finalHeight,
                            finalRaster,
                            effectiveFps,
                            player,
                            config.getImages().getProcessorThreads()
                        );
                        if (res.firstFrame == null) throw new IOException("Could not decode GIF");

                        BufferedImage firstFrame = res.firstFrame;
                        if (res.totalFrames <= 1) {
                            // Treat as static
                            if (finalWidth == 1 && finalHeight == 1) {
                                mapManager.createMapFromImageWithName("finemaps", firstFrame, finalRaster, finalArtName).thenAccept(map -> {
                                    giveMapOrQueueRecovery(player, map.getId(), finalArtName);
                                });
                            } else {
                                mapManager.createMultiBlockMapWithName("finemaps", firstFrame, finalWidth, finalHeight, finalRaster, finalArtName)
                                    .thenAccept(multiMap -> {
                                        giveMultiBlockMapOrQueueRecovery(player, multiMap.getGroupId(), finalArtName, finalWidth, finalHeight);
                                    });
                            }
                            return;
                        }

                        // Animated: create maps from first frame, then start runtime animation updates from disk cache
                        final int fpsFinal = effectiveFps;
                        final int cachedFrameCount = res.totalFrames;
                        if (finalWidth == 1 && finalHeight == 1) {
                            mapManager.createMapFromImageWithName("finemaps", firstFrame, finalRaster, finalArtName).thenAccept(map -> {
                                plugin.getAnimationRegistry().registerAndStartSingleFromCache(finalArtName, map.getId(), fpsFinal, urlStr, 1, 1, finalRaster);
                                plugin.getAnimationRegistry().persistSingleDefinition(finalArtName, urlStr, 1, 1, finalRaster, fpsFinal, map.getId());
                                giveAnimatedMapOrQueueRecovery(player, map.getId(), -1, finalArtName, 1, 1, fpsFinal, cachedFrameCount);
                            });
                        } else {
                            mapManager.createMultiBlockMapWithName("finemaps", firstFrame, finalWidth, finalHeight, finalRaster, finalArtName)
                                .thenAccept(multiMap -> {
                                    List<Long> mapIds = tileOrderMapIds(multiMap, finalWidth, finalHeight);
                                    plugin.getAnimationRegistry().registerAndStartMultiFromCache(finalArtName, mapIds, finalWidth, finalHeight, fpsFinal, urlStr, finalRaster);
                                    plugin.getAnimationRegistry().persistMultiDefinition(finalArtName, urlStr, finalWidth, finalHeight, finalRaster, fpsFinal, multiMap.getGroupId());
                                    giveAnimatedMapOrQueueRecovery(player, -1, multiMap.getGroupId(), finalArtName, finalWidth, finalHeight, fpsFinal, cachedFrameCount);
                                });
                        }
                        return;
                    } else {
                        decoded = GenericImageDecoder.decode(
                            downloaded,
                            urlStr,
                            config.getImages().getMaxAnimatedFrames(),
                            config.getPermissions().getMaxImportSize()
                        );
                    }
                    if (decoded.frames == null || decoded.frames.isEmpty()) {
                        throw new IOException("Could not decode image");
                    }

                    // Sanity-check decoded image dimensions
                    int maxSize = config.getPermissions().getMaxImportSize();
                    BufferedImage sizeCheck = decoded.frames.get(0);
                    if (sizeCheck.getWidth() > maxSize || sizeCheck.getHeight() > maxSize) {
                        throw new IOException("Image too large. Max size: " + maxSize + "x" + maxSize);
                    }

                    // Precompute map color frames and write them to cache folder
                    ImageProcessor processor = new ImageProcessor(
                        config.getImages().getConnectionTimeout(),
                        config.getImages().getReadTimeout(),
                        config.getPermissions().getMaxImportSize()
                    );

                    int processorThreads = 0;
                    try {
                        processorThreads = config.getImages().getProcessorThreads();
                    } catch (Throwable ignored) {
                    }
                    final int cachedFrameCount = writeFramesAndColorsToCache(
                        urlStr, downloaded, decoded, finalWidth, finalHeight, finalRaster, effectiveFps, processor, player, processorThreads
                    );

                    BufferedImage firstFrame = decoded.frames.get(0);

                    if (!decoded.isAnimated()) {
                        // Create static map(s)
                        if (finalWidth == 1 && finalHeight == 1) {
                            mapManager.createMapFromImageWithName("finemaps", firstFrame, finalRaster, finalArtName).thenAccept(map -> {
                                giveMapOrQueueRecovery(player, map.getId(), finalArtName);
                            });
                        } else {
                            mapManager.createMultiBlockMapWithName("finemaps", firstFrame, finalWidth, finalHeight, finalRaster, finalArtName)
                                .thenAccept(multiMap -> {
                                    giveMultiBlockMapOrQueueRecovery(player, multiMap.getGroupId(), finalArtName, finalWidth, finalHeight);
                                });
                        }
                        return;
                    }

                    // Animated: create maps from first frame, then start runtime animation updates
                    if (finalWidth == 1 && finalHeight == 1) {
                        final int fpsFinal = effectiveFps;
                        mapManager.createMapFromImageWithName("finemaps", firstFrame, finalRaster, finalArtName).thenAccept(map -> {
                            plugin.getAnimationRegistry().registerAndStartSingleFromCache(finalArtName, map.getId(), fpsFinal, urlStr, 1, 1, finalRaster);
                            plugin.getAnimationRegistry().persistSingleDefinition(finalArtName, urlStr, 1, 1, finalRaster, fpsFinal, map.getId());
                            giveAnimatedMapOrQueueRecovery(player, map.getId(), -1, finalArtName, 1, 1, fpsFinal, cachedFrameCount);
                        });
                    } else {
                        final int fpsFinal = effectiveFps;
                        mapManager.createMultiBlockMapWithName("finemaps", firstFrame, finalWidth, finalHeight, finalRaster, finalArtName)
                            .thenAccept(multiMap -> {
                                List<Long> mapIds = tileOrderMapIds(multiMap, finalWidth, finalHeight);
                                plugin.getAnimationRegistry().registerAndStartMultiFromCache(finalArtName, mapIds, finalWidth, finalHeight, fpsFinal, urlStr, finalRaster);
                                plugin.getAnimationRegistry().persistMultiDefinition(finalArtName, urlStr, finalWidth, finalHeight, finalRaster, fpsFinal, multiMap.getGroupId());
                                giveAnimatedMapOrQueueRecovery(player, -1, multiMap.getGroupId(), finalArtName, finalWidth, finalHeight, fpsFinal, cachedFrameCount);
                            });
                    }
                } catch (Exception e) {
                    FineMapsScheduler.runForEntity(plugin, player, () -> {
                        if (!player.isOnline()) return;
                        player.sendMessage(ChatColor.RED + "Error processing image: " + e.getMessage());
                    });
                }
            });
        });

        return true;
    }

    private static final class GifStreamResult {
        final BufferedImage firstFrame;
        final int totalFrames;

        private GifStreamResult(BufferedImage firstFrame, int totalFrames) {
            this.firstFrame = firstFrame;
            this.totalFrames = totalFrames;
        }
    }

    /**
     * Stream-decodes a GIF and writes processed map-color frames directly to the per-URL cache,
     * without holding all decoded frames in memory.
     */
    private GifStreamResult processGifStreamingToCache(String urlStr,
                                                       Path gifFile,
                                                       int width,
                                                       int height,
                                                       boolean raster,
                                                       int fps,
                                                       Player progressPlayer,
                                                       int processorThreads) throws IOException {
        int maxCanvas = config.getPermissions().getMaxImportSize();
        int maxFrames = 0;
        try {
            maxFrames = config.getImages().getMaxAnimatedFrames();
        } catch (Throwable ignored) {
        }
        // 0 or less means "no cap"
        int effectiveMaxFrames = maxFrames > 0 ? maxFrames : 0;

        // Prepare cache dirs (same structure as the non-streaming path)
        Path baseDir = UrlCache.cacheDirForUrl(plugin.getDataFolder(), config.getImages().getUrlCacheFolder(), urlStr);
        Path variantDir = baseDir.resolve(String.format(Locale.ROOT, "variant_%dx%d_r%s_fps%d", width, height, raster ? "1" : "0", fps));
        Path framesDir = variantDir.resolve("frames");
        Path colorsDir = variantDir.resolve("colors");
        Files.createDirectories(framesDir);
        Files.createDirectories(colorsDir);

        // ImageProcessor (same as normal path)
        ImageProcessor processor = new ImageProcessor(
            config.getImages().getConnectionTimeout(),
            config.getImages().getReadTimeout(),
            maxCanvas
        );

        int threads = resolveProcessorThreads(processorThreads, 0);
        int queueCap = Math.max(2, threads * 2);
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
            threads,
            threads,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCap),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        AtomicInteger completed = new AtomicInteger(0);
        AtomicLong lastUpdateMs = new AtomicLong(0L);
        AtomicInteger lastPercent = new AtomicInteger(-1);
        AtomicInteger expectedTotal = new AtomicInteger(0);
        Object lock = new Object();

        final boolean writePngFrames = false; // avoid huge disk usage for long GIFs
        final java.util.concurrent.atomic.AtomicReference<BufferedImage> firstFrameRef = new java.util.concurrent.atomic.AtomicReference<>(null);

        final java.util.concurrent.atomic.AtomicReference<Throwable> workerError = new java.util.concurrent.atomic.AtomicReference<>(null);
        final AtomicInteger submitted = new AtomicInteger(0);

        try {
            int produced = GifFrameStreamer.stream(gifFile.toFile(), effectiveMaxFrames, maxCanvas, (idx, frameImg) -> {
                if (workerError.get() != null) {
                    throw new IOException("Frame processing failed", workerError.get());
                }

                if (idx == 0) {
                    firstFrameRef.compareAndSet(null, frameImg);
                }

                final int frameIndex = idx;
                submitted.incrementAndGet();
                exec.execute(() -> {
                    try {
                        if (frameImg == null) return;

                        if (writePngFrames) {
                            File outFrame = framesDir.resolve(String.format(Locale.ROOT, "frame_%06d.png", frameIndex)).toFile();
                            try {
                                ImageIO.write(frameImg, "png", outFrame);
                            } catch (Exception ignored) {
                            }
                        }

                        if (width == 1 && height == 1) {
                            byte[] pixels = processor.processSingleMap(frameImg, raster);
                            Files.write(colorsDir.resolve(String.format(Locale.ROOT, "frame_%06d.bin", frameIndex)), pixels);
                        } else {
                            byte[][] tiles = processor.processImage(frameImg, width, height, raster);
                            Path out = colorsDir.resolve(String.format(Locale.ROOT, "frame_%06d.bin", frameIndex));
                            try (java.io.OutputStream os = Files.newOutputStream(out)) {
                                for (byte[] t : tiles) {
                                    if (t != null) os.write(t);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        workerError.compareAndSet(null, t);
                    } finally {
                        int done = completed.incrementAndGet();
                        maybeSendGifProgress(progressPlayer, done, expectedTotal.get(), lastUpdateMs, lastPercent, lock);
                    }
                });
            }, total -> expectedTotal.set(total));

            // Wait for workers to finish
            exec.shutdown();
            while (!exec.awaitTermination(1, TimeUnit.SECONDS)) {
                if (workerError.get() != null) break;
            }
            Throwable err = workerError.get();
            if (err != null) {
                if (err instanceof IOException) throw (IOException) err;
                throw new IOException("Failed while processing GIF frames", err);
            }

            int totalFrames = Math.min(produced, submitted.get());

            // Send final 100% progress
            maybeSendGifProgress(progressPlayer, totalFrames, totalFrames, lastUpdateMs, lastPercent, lock);

            // Write meta for debugging (best-effort)
            try {
                String meta = "url: " + urlStr + "\n" +
                    "original: " + (gifFile != null ? gifFile.getFileName() : "original.gif") + "\n" +
                    "format: gif\n" +
                    "frames: " + totalFrames + "\n" +
                    "w: " + width + "\n" +
                    "h: " + height + "\n" +
                    "raster: " + raster + "\n" +
                    "fps: " + fps + "\n";
                Files.writeString(variantDir.resolve("meta.txt"), meta);
            } catch (IOException ignored) {
            }

            return new GifStreamResult(firstFrameRef.get(), totalFrames);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while processing GIF", ie);
        } finally {
            exec.shutdownNow();
        }
    }

    private void maybeSendGifProgress(Player player,
                                      int done,
                                      int total,
                                      AtomicLong lastUpdateMs,
                                      AtomicInteger lastPercent,
                                      Object lock) {
        if (player == null) return;
        long now = System.currentTimeMillis();

        // If total is known, show percentage-based progress bar
        if (total > 0) {
            int pct = Math.min(100, Math.max(0, (done * 100) / total));
            synchronized (lock) {
                int prevPct = lastPercent.get();
                long prevMs = lastUpdateMs.get();
                boolean timeOk = (now - prevMs) >= 200L;
                boolean force = (pct == 0 || pct == 100);
                if (!force && (!timeOk || pct == prevPct)) return;
                lastPercent.set(pct);
                lastUpdateMs.set(now);
            }
            String bar = progressBar(pct, 20);
            String msg = ChatColor.YELLOW + "Rasterising " + ChatColor.WHITE + pct + "%" + ChatColor.DARK_GRAY +
                " [" + bar + ChatColor.DARK_GRAY + "] " + ChatColor.GRAY + done + "/" + total;
            FineMapsScheduler.runForEntity(plugin, player, () -> {
                if (!player.isOnline()) return;
                sendActionBar(player, msg);
            });
        } else {
            // Total unknown, show frame count only
            synchronized (lock) {
                long prevMs = lastUpdateMs.get();
                if ((now - prevMs) < 250L) return;
                int prev = lastPercent.get();
                if (done == prev) return;
                lastPercent.set(done);
                lastUpdateMs.set(now);
            }
            String msg = ChatColor.YELLOW + "Rasterising " + ChatColor.WHITE + done + ChatColor.GRAY + " frames...";
            FineMapsScheduler.runForEntity(plugin, player, () -> {
                if (!player.isOnline()) return;
                sendActionBar(player, msg);
            });
        }
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
                FineMapsScheduler.runForEntity(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    mapManager.giveMultiBlockMapToPlayerWithName(player, groupId, artName);
                    player.sendMessage(ChatColor.GREEN + "Given map '" + artName + "'");
                });
            } else {
                // Try to find a single map with this name
                mapManager.getMapByName("finemaps", artName).thenAccept(optMapId -> {
                    if (optMapId.isPresent()) {
                        long mapId = optMapId.get();
                        FineMapsScheduler.runForEntity(plugin, player, () -> {
                            if (!player.isOnline()) return;
                            mapManager.giveMapToPlayerWithName(player, mapId, artName);
                            player.sendMessage(ChatColor.GREEN + "Given map '" + artName + "'");
                        });
                    } else {
                        FineMapsScheduler.runForEntity(plugin, player, () -> {
                            if (!player.isOnline()) return;
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
                    FineMapsScheduler.runSync(plugin, () -> {
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
                            FineMapsScheduler.runSync(plugin, () -> {
                                if (success) {
                                    sender.sendMessage(ChatColor.GREEN + "Deleted map '" + artName + "'");
                                } else {
                                    sender.sendMessage(ChatColor.RED + "Failed to delete map '" + artName + "'");
                                }
                            });
                        });
                    } else {
                        FineMapsScheduler.runSync(plugin, () -> sender.sendMessage(ChatColor.RED + "Map not found: " + artName));
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
            FineMapsScheduler.runSync(plugin, () -> {
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
                    FineMapsScheduler.runSync(plugin, () -> {
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
                            FineMapsScheduler.runSync(plugin, () -> {
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
                        FineMapsScheduler.runSync(plugin, () -> sender.sendMessage(ChatColor.RED + "Map not found: " + artName));
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

        plugin.reloadFineMapsConfiguration();
        sender.sendMessage(ChatColor.GREEN + "Configuration reloaded.");
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("finemaps.stats")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to view stats.");
            return true;
        }

        mapManager.getMapCount("finemaps").thenAccept(count -> {
            FineMapsScheduler.runSync(plugin, () -> {
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

    private boolean handleAnim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("finemaps.anim")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to control animations.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /finemaps anim <restart|pause|skip <time>>");
            return true;
        }

        long targetMapId = resolveTargetDbMapId(player);
        if (targetMapId <= 0) {
            player.sendMessage(ChatColor.RED + "Hold a FineMaps animated map, or look at an item frame with one.");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "restart": {
                boolean ok = plugin.getAnimationRegistry().restartByDbMapId(targetMapId);
                if (!ok) {
                    player.sendMessage(ChatColor.RED + "That map is not currently animated.");
                    return true;
                }
                plugin.getAnimationRegistry().savePersistedState();
                player.sendMessage(ChatColor.GREEN + "Animation restarted.");
                return true;
            }
            case "pause": {
                Boolean paused = plugin.getAnimationRegistry().togglePauseByDbMapId(targetMapId);
                if (paused == null) {
                    player.sendMessage(ChatColor.RED + "That map is not currently animated.");
                    return true;
                }
                plugin.getAnimationRegistry().savePersistedState();
                player.sendMessage(paused ? (ChatColor.YELLOW + "Animation paused.") : (ChatColor.GREEN + "Animation resumed."));
                return true;
            }
            case "skip": {
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "Usage: /finemaps anim skip <h:m:s | m:s | s>");
                    return true;
                }
                long ms = parseHmsToMillis(args[2]);
                if (ms == 0) {
                    player.sendMessage(ChatColor.RED + "Invalid time: " + args[2]);
                    player.sendMessage(ChatColor.GRAY + "Example: 10  |  1:30  |  0:02:15");
                    return true;
                }
                boolean ok = plugin.getAnimationRegistry().skipByDbMapId(targetMapId, ms);
                if (!ok) {
                    player.sendMessage(ChatColor.RED + "That map is not currently animated.");
                    return true;
                }
                plugin.getAnimationRegistry().savePersistedState();
                player.sendMessage(ChatColor.GREEN + "Skipped forward " + formatMillisShort(ms) + ".");
                return true;
            }
            default:
                player.sendMessage(ChatColor.RED + "Usage: /finemaps anim <restart|pause|skip <time>>");
                return true;
        }
    }

    private long resolveTargetDbMapId(Player player) {
        if (player == null) return -1;

        // 1) Try looked-at item frame (best UX for placed maps).
        ItemStack frameItem = getLookedAtItemFrameItem(player, 5.0);
        if (frameItem != null && mapManager.isStoredMap(frameItem)) {
            long id = mapManager.getMapIdFromItem(frameItem);
            if (id > 0) return id;
        }

        // 2) Fall back to held items.
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && mapManager.isStoredMap(main)) {
            long id = mapManager.getMapIdFromItem(main);
            if (id > 0) return id;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null && mapManager.isStoredMap(off)) {
            long id = mapManager.getMapIdFromItem(off);
            if (id > 0) return id;
        }
        return -1;
    }

    private ItemStack getLookedAtItemFrameItem(Player player, double maxDistance) {
        if (player == null) return null;
        try {
            // Use rayTraceEntities when available (keep reflective for compatibility across forks).
            java.lang.reflect.Method m = player.getClass().getMethod("rayTraceEntities", double.class);
            Object rtr = m.invoke(player, maxDistance);
            if (rtr != null) {
                java.lang.reflect.Method getHitEntity = rtr.getClass().getMethod("getHitEntity");
                Object hit = getHitEntity.invoke(rtr);
                if (hit instanceof org.bukkit.entity.ItemFrame) {
                    return ((org.bukkit.entity.ItemFrame) hit).getItem();
                }
            }
        } catch (Throwable ignored) {
        }

        // Fallback: nearest item frame in view cone.
        org.bukkit.util.Vector eye = player.getEyeLocation().toVector();
        org.bukkit.util.Vector dir = player.getEyeLocation().getDirection().normalize();
        double bestDistSq = maxDistance * maxDistance;
        org.bukkit.entity.ItemFrame best = null;

        for (org.bukkit.entity.Entity e : player.getNearbyEntities(maxDistance, maxDistance, maxDistance)) {
            if (!(e instanceof org.bukkit.entity.ItemFrame)) continue;
            if (!player.hasLineOfSight(e)) continue;
            org.bukkit.util.Vector to = e.getLocation().add(0.0, 0.5, 0.0).toVector().subtract(eye);
            double distSq = to.lengthSquared();
            if (distSq > bestDistSq) continue;
            double dot = to.normalize().dot(dir);
            if (dot < 0.975) continue; // ~12.5 degrees cone
            bestDistSq = distSq;
            best = (org.bukkit.entity.ItemFrame) e;
        }
        return best != null ? best.getItem() : null;
    }

    private long parseHmsToMillis(String raw) {
        if (raw == null) return 0L;
        String s = raw.trim();
        if (s.isEmpty()) return 0L;
        String[] parts = s.split(":");
        try {
            long h = 0, m = 0, sec = 0;
            if (parts.length == 3) {
                h = Long.parseLong(parts[0]);
                m = Long.parseLong(parts[1]);
                sec = Long.parseLong(parts[2]);
            } else if (parts.length == 2) {
                m = Long.parseLong(parts[0]);
                sec = Long.parseLong(parts[1]);
            } else if (parts.length == 1) {
                sec = Long.parseLong(parts[0]);
            } else {
                return 0L;
            }
            if (h < 0 || m < 0 || sec < 0) return 0L;
            long totalSec = (h * 3600L) + (m * 60L) + sec;
            if (totalSec <= 0) return 0L;
            return totalSec * 1000L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String formatMillisShort(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) return h + "h" + m + "m" + s + "s";
        if (m > 0) return m + "m" + s + "s";
        return s + "s";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterStartsWith(args[0], Arrays.asList(
                "url", "get", "delete", "list", "info", "stats", "reload", "create", "debug", "anim",
                "import", "importall", "convert", "convertall"
                , "buy", "gui", "config"
            ));
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("debug")) {
                return debugCommand.tabComplete(new String[]{args[1]});
            }
            if (sub.equals("config")) {
                return filterStartsWith(args[1], Arrays.asList("get", "set", "reset"));
            }
            if (sub.equals("anim") || sub.equals("animation")) {
                return filterStartsWith(args[1], Arrays.asList("restart", "pause", "skip"));
            }
            if (sub.equals("url") || sub.equals("fromurl")) {
                return Collections.singletonList("<url>");
            }
            if (sub.equals("import") || sub.equals("importvanilla")) {
                return Arrays.asList("<mapId>", "<name>");
            }
            if (sub.equals("convert")) {
                return Arrays.asList("<mapId>", "<name>");
            }
            if (sub.equals("importall") || sub.equals("importvanillaall")) {
                // Suggest world names
                List<String> worlds = Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList());
                worlds.add(0, "all");
                return filterStartsWith(args[1], worlds);
            }
            if (sub.equals("convertall")) {
                // Suggest world names
                List<String> worlds = Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList());
                worlds.add(0, "all");
                return filterStartsWith(args[1], worlds);
            }
            if (sub.equals("get") || sub.equals("give") || sub.equals("delete") ||
                sub.equals("remove") || sub.equals("info") || sub.equals("buy")) {
                return filterStartsWith(args[1], getCachedArtNames());
            }
            if (sub.equals("list")) {
                return Collections.singletonList("<pluginId>");
            }
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("debug")) {
            String[] debugArgs = Arrays.copyOfRange(args, 1, args.length);
            return debugCommand.tabComplete(debugArgs);
        }

        if (args.length >= 3 && (args[0].equalsIgnoreCase("anim") || args[0].equalsIgnoreCase("animation"))) {
            if (args.length == 3 && args[1].equalsIgnoreCase("skip")) {
                return Collections.singletonList("<time>");
            }
        }

        if (args.length >= 3 && (args[0].equalsIgnoreCase("url") || args[0].equalsIgnoreCase("fromurl"))) {
            if (args.length == 3) return Collections.singletonList("<name>");
            if (args.length == 4) return Collections.singletonList("<w>");
            if (args.length == 5) return Collections.singletonList("<h>");
            if (args.length == 6) return Arrays.asList("raster", "nearest");
            if (args.length == 7) return Collections.singletonList("<fps>");
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("config")) {
            if (args.length == 3) {
                return Collections.singletonList("<path>");
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
                return Collections.singletonList("<value>");
            }
        }

        return Collections.emptyList();
    }

    private List<String> getCachedArtNames() {
        List<ArtSummary> arts = mapManager.getCachedArtSummaries("finemaps");
        if (arts.isEmpty()) return Collections.singletonList("<name>");
        return arts.stream().map(ArtSummary::getName).distinct().collect(Collectors.toList());
    }

    private List<String> filterStartsWith(String input, List<String> options) {
        String lower = input.toLowerCase();
        return options.stream()
            .filter(opt -> opt.toLowerCase().startsWith(lower))
            .collect(Collectors.toList());
    }

    private boolean looksLikeUrl(String s) {
        if (s == null) return false;
        String t = s.toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    private boolean parseRasterArg(String raw, boolean defaultVal) {
        if (raw == null) return defaultVal;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return defaultVal;
        // Preferred explicit modes:
        // - raster  = dithered (Floyd–Steinberg) palette mapping
        // - nearest = nearest-color palette mapping (no dithering)
        if (s.equals("raster")) return true;
        if (s.equals("nearest") || s.equals("nearestcolor") || s.equals("nearest-colors") || s.equals("nearest-colour") ||
            s.equals("quantize") || s.equals("quantise") || s.equals("palette") || s.equals("nodither") || s.equals("no-dither")) {
            return false;
        }

        // Backwards compatibility (old scripts/configs):
        if (s.equals("true") || s.equals("yes") || s.equals("1")) return true;
        if (s.equals("noraster") || s.equals("false") || s.equals("no") || s.equals("0")) return false;
        return Boolean.parseBoolean(s);
    }

    private boolean isVanillaWorldImportArt(ArtSummary art) {
        if (art == null) return false;
        String name = art.getName();
        if (name == null) return false;
        return VANILLA_WORLD_IMPORT_NAME.matcher(name).matches();
    }

    private Path downloadAndCacheUrlImage(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        boolean cacheEnabled = config.getImages().isUrlCacheEnabled();
        long maxBytes = ByteSizeParser.parseToBytes(config.getImages().getMaxUrlDownloadSize());

        Path baseDir;
        if (cacheEnabled) {
            baseDir = UrlCache.cacheDirForUrl(plugin.getDataFolder(), config.getImages().getUrlCacheFolder(), urlStr);
            Files.createDirectories(baseDir);

            // Reuse cached original.* if present
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir, "original.*")) {
                for (Path p : ds) {
                    if (Files.isRegularFile(p)) return p;
                }
            } catch (IOException ignored) {
            }
        } else {
            baseDir = plugin.getDataFolder().toPath().resolve("tmp-url");
            Files.createDirectories(baseDir);
        }

        Path tmp = baseDir.resolve("download.tmp");
        UrlDownloader.DownloadResult res = UrlDownloader.downloadToFile(
            url,
            tmp,
            config.getImages().getConnectionTimeout(),
            config.getImages().getReadTimeout(),
            maxBytes
        );

        String ext = guessExtension(urlStr, res.contentType);
        Path out = baseDir.resolve("original." + ext);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        // Save source URL for debugging
        try {
            Files.writeString(baseDir.resolve("source.url.txt"), urlStr);
        } catch (IOException ignored) {
        }

        return out;
    }

    private String guessExtension(String urlStr, String contentType) {
        String lower = urlStr != null ? urlStr.toLowerCase(Locale.ROOT) : "";
        String path = lower;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.endsWith(".png") || path.endsWith(".apng")) return "png";
        if (path.endsWith(".gif")) return "gif";
        if (path.endsWith(".webp")) return "webp";
        if (path.endsWith(".mp4")) return "mp4";
        if (path.endsWith(".webm")) return "webm";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "jpg";

        // Heuristics for "extensionless" URLs (CDNs/proxies that omit suffixes)
        if (lower.contains(".webm") || lower.contains("format=webm")) return "webm";
        if (lower.contains(".mp4") || lower.contains("format=mp4")) return "mp4";

        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT).trim();
            int semi = ct.indexOf(';');
            if (semi >= 0) ct = ct.substring(0, semi).trim();
            if (ct.startsWith("image/png")) return "png";
            if (ct.startsWith("image/gif")) return "gif";
            if (ct.startsWith("image/webp")) return "webp";
            if (ct.startsWith("image/jpeg")) return "jpg";
            if (ct.startsWith("video/mp4")) return "mp4";
            if (ct.startsWith("video/webm")) return "webm";
        }
        return "bin";
    }

    /**
     * Resolve the ffmpeg binary path.
     *
     * Order:
     * - config images.ffmpeg-path (if set and works)
     * - "ffmpeg" from PATH (if works)
     * - <plugin data folder>/ffmpeg (if present/works)
     */
    private String resolveFfmpegPath() {
        String configured = null;
        try {
            configured = config.getImages().getFfmpegPath();
        } catch (Throwable ignored) {
        }

        // 1) Configured path
        if (configured != null && !configured.isBlank()) {
            String p = configured.trim();
            if (canExecuteFfmpeg(p)) return p;
        }

        // 2) PATH
        if (canExecuteFfmpeg("ffmpeg")) return "ffmpeg";

        // 3) Plugin data folder root
        try {
            File data = plugin.getDataFolder();
            if (data != null) {
                File local = new File(data, "ffmpeg");
                if (local.isFile() && canExecuteFfmpeg(local.getAbsolutePath())) {
                    return local.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }

        // Fall back to whatever the user configured (or PATH) so the decoder error is explicit.
        if (configured != null && !configured.isBlank()) return configured.trim();
        return "ffmpeg";
    }

    private boolean canExecuteFfmpeg(String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(path, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int writeFramesAndColorsToCache(String urlStr,
                                           Path originalFile,
                                           AnimatedImage decoded,
                                           int width,
                                           int height,
                                           boolean raster,
                                           int fps,
                                           ImageProcessor processor,
                                           Player progressPlayer,
                                           int processorThreads) throws IOException {
        // Always write processed color frames to a stable, per-URL cache directory so animations
        // survive plugin reload/restart (even if "url cache" is disabled for original downloads).
        Path baseDir = UrlCache.cacheDirForUrl(plugin.getDataFolder(), config.getImages().getUrlCacheFolder(), urlStr);

        Path variantDir = baseDir.resolve(String.format(Locale.ROOT, "variant_%dx%d_r%s_fps%d", width, height, raster ? "1" : "0", fps));

        Path framesDir = variantDir.resolve("frames");
        Path colorsDir = variantDir.resolve("colors");
        Files.createDirectories(framesDir);
        Files.createDirectories(colorsDir);

        // Simple metadata file for debugging
        try {
            String meta = "url: " + urlStr + "\n" +
                "original: " + originalFile.getFileName() + "\n" +
                "format: " + decoded.format + "\n" +
                "frames: " + decoded.frames.size() + "\n" +
                "w: " + width + "\n" +
                "h: " + height + "\n" +
                "raster: " + raster + "\n" +
                "fps: " + fps + "\n";
            Files.writeString(variantDir.resolve("meta.txt"), meta);
        } catch (IOException ignored) {
        }

        final int totalFrames = (decoded.frames != null ? decoded.frames.size() : 0);
        final boolean writePngFrames = config.getImages().isUrlCacheEnabled();

        final AtomicInteger completed = new AtomicInteger(0);
        final AtomicLong lastUpdateMs = new AtomicLong(0L);
        final AtomicInteger lastPercent = new AtomicInteger(-1);
        final Object progressLock = new Object();

        int threads = resolveProcessorThreads(processorThreads, totalFrames);
        ExecutorService exec = null;
        try {
            if (threads > 1) {
                exec = Executors.newFixedThreadPool(threads);
            }

            List<Future<?>> futures = exec != null ? new ArrayList<>(totalFrames) : null;

            for (int i = 0; i < totalFrames; i++) {
                final int idx = i;
                final BufferedImage frame = decoded.frames.get(i);

                Runnable work = () -> {
                    try {
                        if (frame == null) return;

                        // Save original-res frame as PNG (optional)
                        if (writePngFrames) {
                            File outFrame = framesDir.resolve(String.format(Locale.ROOT, "frame_%06d.png", idx)).toFile();
                            try {
                                ImageIO.write(frame, "png", outFrame);
                            } catch (Exception ignored) {
                            }
                        }

                        if (width == 1 && height == 1) {
                            byte[] pixels = processor.processSingleMap(frame, raster);
                            Files.write(colorsDir.resolve(String.format(Locale.ROOT, "frame_%06d.bin", idx)), pixels);
                        } else {
                            byte[][] tiles = processor.processImage(frame, width, height, raster);

                            // Write concatenated tiles without buffering the whole frame in memory
                            Path out = colorsDir.resolve(String.format(Locale.ROOT, "frame_%06d.bin", idx));
                            try (java.io.OutputStream os = Files.newOutputStream(out)) {
                                for (byte[] t : tiles) {
                                    if (t != null) os.write(t);
                                }
                            }
                        }
                    } catch (IOException ioe) {
                        throw new RuntimeException(ioe);
                    } finally {
                        int done = completed.incrementAndGet();
                        maybeSendRasterProgress(progressPlayer, done, totalFrames, lastUpdateMs, lastPercent, progressLock);
                    }
                };

                if (exec != null) {
                    futures.add(exec.submit(work));
                } else {
                    work.run();
                }
            }

            if (futures != null) {
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        if (cause instanceof RuntimeException && cause.getCause() instanceof IOException) {
                            throw (IOException) cause.getCause();
                        }
                        throw new IOException("Failed while processing frames", cause);
                    }
                }
            }
        } finally {
            if (exec != null) {
                exec.shutdownNow();
            }
        }

        // Final 100% update.
        maybeSendRasterProgress(progressPlayer, totalFrames, totalFrames, lastUpdateMs, lastPercent, progressLock);
        return totalFrames;
    }

    private int resolveProcessorThreads(int configured, int totalFrames) {
        int t = configured;
        if (t <= 0) {
            // Keep at least 1 thread, and avoid starving the server.
            int cpu = Runtime.getRuntime().availableProcessors();
            t = Math.max(1, cpu);
        }
        if (totalFrames > 0) t = Math.min(t, totalFrames);
        return Math.max(1, t);
    }

    private void maybeSendRasterProgress(Player player,
                                         int done,
                                         int total,
                                         AtomicLong lastUpdateMs,
                                         AtomicInteger lastPercent,
                                         Object lock) {
        if (player == null || total <= 0) return;
        int pct = Math.min(100, Math.max(0, (done * 100) / total));
        long now = System.currentTimeMillis();

        synchronized (lock) {
            int prevPct = lastPercent.get();
            long prevMs = lastUpdateMs.get();
            boolean timeOk = (now - prevMs) >= 200L;
            boolean force = (pct == 0 || pct == 100);
            if (!force && (!timeOk || pct == prevPct)) return;
            lastPercent.set(pct);
            lastUpdateMs.set(now);
        }

        String bar = progressBar(pct, 20);
        String msg;
        if (total == 1) {
            // Single frame - don't show "1/1", just show the progress bar
            msg = ChatColor.YELLOW + "Rasterising " + ChatColor.WHITE + pct + "%" + ChatColor.DARK_GRAY +
                " [" + bar + ChatColor.DARK_GRAY + "]";
        } else {
            msg = ChatColor.YELLOW + "Rasterising " + ChatColor.WHITE + pct + "%" + ChatColor.DARK_GRAY +
                " [" + bar + ChatColor.DARK_GRAY + "] " + ChatColor.GRAY + done + "/" + total;
        }

        FineMapsScheduler.runForEntity(plugin, player, () -> {
            if (!player.isOnline()) return;
            sendActionBar(player, msg);
        });
    }

    private String progressBar(int percent, int width) {
        int w = Math.max(5, width);
        int filled = (int) Math.round((percent / 100.0) * w);
        filled = Math.max(0, Math.min(w, filled));
        String on = "█".repeat(filled);
        String off = "░".repeat(w - filled);
        return ChatColor.GREEN + on + ChatColor.DARK_GRAY + off;
    }

    private void sendActionBar(Player player, String message) {
        if (player == null || message == null) return;
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
        } catch (Throwable ignored) {
        }
    }

    private List<Long> tileOrderMapIds(org.finetree.finemaps.api.map.MultiBlockMap multiMap, int width, int height) {
        // Build a [y][x] ordered list from grid coords
        long[] ids = new long[width * height];
        Arrays.fill(ids, -1L);
        for (org.finetree.finemaps.api.map.StoredMap m : multiMap.getMaps()) {
            int x = m.getGridX();
            int y = m.getGridY();
            if (x < 0 || y < 0 || x >= width || y >= height) continue;
            ids[y * width + x] = m.getId();
        }
        List<Long> out = new ArrayList<>();
        for (long id : ids) out.add(id);
        return out;
    }

    /**
     * Gives a single map to a player, or queues for recovery if player is offline.
     */
    private void giveMapOrQueueRecovery(Player player, long mapId, String artName) {
        FineMapsScheduler.runForEntity(plugin, player, () -> {
            if (player.isOnline()) {
                mapManager.giveMapToPlayerWithName(player, mapId, artName);
                player.sendMessage(ChatColor.GREEN + "Created map '" + artName + "' from image!");
            } else {
                // Player disconnected, queue for recovery
                queueForRecovery(player, artName, mapId, -1, 1, 1);
            }
        });
    }

    /**
     * Gives a multi-block map to a player, or queues for recovery if player is offline.
     */
    private void giveMultiBlockMapOrQueueRecovery(Player player, long groupId, String artName, int width, int height) {
        FineMapsScheduler.runForEntity(plugin, player, () -> {
            if (player.isOnline()) {
                mapManager.giveMultiBlockMapToPlayerWithName(player, groupId, artName);
                player.sendMessage(ChatColor.GREEN + "Created " + width + "x" + height + " map '" + artName + "'!");
            } else {
                // Player disconnected, queue for recovery
                queueForRecovery(player, artName, -1, groupId, width, height);
            }
        });
    }

    /**
     * Gives an animated map to a player, or queues for recovery if player is offline.
     */
    private void giveAnimatedMapOrQueueRecovery(Player player, long mapId, long groupId, String artName, 
                                                 int width, int height, int fps, int frameCount) {
        FineMapsScheduler.runForEntity(plugin, player, () -> {
            if (player.isOnline()) {
                if (mapId > 0) {
                    mapManager.giveMapToPlayerWithName(player, mapId, artName);
                    player.sendMessage(ChatColor.GREEN + "Created animated map '" + artName + "' (" + fps + " fps, " + frameCount + " frames)");
                } else if (groupId > 0) {
                    mapManager.giveMultiBlockMapToPlayerWithName(player, groupId, artName);
                    player.sendMessage(ChatColor.GREEN + "Created animated " + width + "x" + height + 
                        " map '" + artName + "' (" + fps + " fps, " + frameCount + " frames)");
                }
            } else {
                // Player disconnected, queue for recovery
                queueForRecovery(player, artName, mapId, groupId, width, height);
            }
        });
    }

    /**
     * Queues a map for recovery when the player next logs in.
     */
    private void queueForRecovery(Player player, String artName, long mapId, long groupId, int width, int height) {
        PendingMapRecovery recovery = plugin.getPendingMapRecovery();
        if (recovery == null) return;

        if (mapId > 0) {
            recovery.addPendingSingleMap(player.getUniqueId(), artName, mapId);
        } else if (groupId > 0) {
            recovery.addPendingMultiBlockMap(player.getUniqueId(), artName, groupId, width, height);
        }
        plugin.getLogger().info("Queued map '" + artName + "' for recovery for player " + player.getName());
    }
}
