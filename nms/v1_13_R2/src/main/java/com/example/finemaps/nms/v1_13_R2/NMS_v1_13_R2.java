package com.example.finemaps.nms.v1_13_R2;

import com.example.finemaps.api.nms.NMSAdapter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

import java.util.logging.Logger;

/**
 * NMS adapter for Minecraft 1.13.2.
 */
public class NMS_v1_13_R2 implements NMSAdapter {

    private final Logger logger;

    public NMS_v1_13_R2(Logger logger) {
        this.logger = logger;
    }

    @Override public String getVersion() { return "v1_13_R2"; }
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
    @Override public int spawnMapDisplay(Location location, int mapId) { return -1; }
    @Override public void removeDisplay(int entityId) {}
    @Override public boolean supportsBlockDisplays() { return false; }
    @Override public int spawnPreviewBlockDisplay(Location location, boolean valid) { return -1; }
    @Override public void removePreviewDisplay(int entityId) {}

    @Override
    public void showParticleOutline(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) return;
        double x = location.getX(), y = location.getY(), z = location.getZ();
        for (double i = 0; i <= 1; i += 0.1) {
            world.spawnParticle(Particle.FLAME, x + i, y + 1, z, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.FLAME, x + i, y, z, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.FLAME, x, y + i, z, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.FLAME, x + 1, y + i, z, 1, 0, 0, 0, 0);
        }
    }

    @Override public void registerPacketInterceptor(MapPacketListener listener) {}
    @Override public void unregisterPacketInterceptor() {}
    @Override public boolean supportsFolia() { return false; }
    @Override public int getMajorVersion() { return 13; }
    @Override public int getMinorVersion() { return 2; }
}
