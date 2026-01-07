package com.example.mapdb.nms.v1_20_R3;

import com.example.mapdb.api.nms.NMSAdapter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * NMS adapter for Minecraft 1.20.4.
 */
public class NMS_v1_20_R3 implements NMSAdapter {

    private final Logger logger;
    private final Map<Integer, ItemDisplay> displayEntities = new HashMap<>();
    private int nextDisplayId = Integer.MAX_VALUE - 1000000;

    public NMS_v1_20_R3(Logger logger) {
        this.logger = logger;
    }

    @Override public String getVersion() { return "v1_20_R3"; }
    @Override public void sendMapUpdate(Player player, int mapId, byte[] pixels) {}
    @Override public void sendPartialMapUpdate(Player player, int mapId, int startX, int startY, int width, int height, byte[] pixels) {}

    @Override
    public ItemStack createMapItem(int mapId) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta != null) { meta.setMapId(mapId); item.setItemMeta(meta); }
        return item;
    }

    @Override
    public int getMapId(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) return -1;
        MapMeta meta = (MapMeta) item.getItemMeta();
        return meta != null && meta.hasMapId() ? meta.getMapId() : -1;
    }

    @Override
    public ItemStack setMapId(ItemStack item, int mapId) {
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta != null) { meta.setMapId(mapId); item.setItemMeta(meta); }
        return item;
    }

    @Override public boolean isFilledMap(ItemStack item) { return item != null && item.getType() == Material.FILLED_MAP; }

    @Override
    public int spawnMapDisplay(Location location, int mapId) {
        try {
            World world = location.getWorld();
            if (world == null) return -1;
            
            ItemDisplay display = world.spawn(location, ItemDisplay.class, entity -> {
                entity.setItemStack(createMapItem(mapId));
                entity.setBillboard(ItemDisplay.Billboard.FIXED);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            });
            
            int displayId = nextDisplayId++;
            displayEntities.put(displayId, display);
            return displayId;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void removeDisplay(int entityId) {
        ItemDisplay display = displayEntities.remove(entityId);
        if (display != null && display.isValid()) display.remove();
    }

    @Override public boolean supportsBlockDisplays() { return true; }

    @Override
    public void showParticleOutline(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) return;
        Particle.DustOptions dust = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 215, 0), 0.5f);
        double x = location.getX(), y = location.getY(), z = location.getZ();
        for (double i = 0; i <= 1; i += 0.1) {
            player.spawnParticle(Particle.REDSTONE, x + i, y + 1, z, 1, dust);
            player.spawnParticle(Particle.REDSTONE, x + i, y, z, 1, dust);
            player.spawnParticle(Particle.REDSTONE, x, y + i, z, 1, dust);
            player.spawnParticle(Particle.REDSTONE, x + 1, y + i, z, 1, dust);
        }
    }

    @Override public void registerPacketInterceptor(MapPacketListener listener) {}
    @Override public void unregisterPacketInterceptor() {}
    @Override public boolean supportsFolia() { return false; }
    @Override public int getMajorVersion() { return 20; }
    @Override public int getMinorVersion() { return 4; }
}
