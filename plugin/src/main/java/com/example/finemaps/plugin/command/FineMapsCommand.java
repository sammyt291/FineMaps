package com.example.finemaps.plugin.command;

import com.example.finemaps.api.map.MapData;
import com.example.finemaps.api.map.MultiBlockMap;
import com.example.finemaps.api.map.StoredMap;
import com.example.finemaps.core.config.FineMapsConfig;
import com.example.finemaps.core.image.ImageProcessor;
import com.example.finemaps.core.manager.MapManager;
import com.example.finemaps.plugin.FineMapsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main command handler for FineMaps.
 */
public class FineMapsCommand implements CommandExecutor, TabCompleter {

    private final FineMapsPlugin plugin;
    private final MapManager mapManager;
    private final FineMapsConfig config;

    public FineMapsCommand(FineMapsPlugin plugin) {
        this.plugin = plugin;
        this.mapManager = plugin.getMapManager();
        this.config = plugin.getFineMapsConfig();
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
        if (!sender.hasPermission("finemaps.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to reload.");
            return true;
        }

        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "Configuration reloaded.");
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("finemaps.admin")) {
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
                "url", "get", "delete", "list", "info", "stats", "reload", "create"
            ));
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("url") || sub.equals("fromurl")) {
                return Collections.singletonList("<name>");
            }
            if (sub.equals("get") || sub.equals("give") || sub.equals("delete") || 
                sub.equals("remove") || sub.equals("info")) {
                return Collections.singletonList("<name>");
            }
            if (sub.equals("list")) {
                return Collections.singletonList("<pluginId>");
            }
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
