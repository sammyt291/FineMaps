package com.example.finemaps.nms.v1_21_R1;

import com.example.finemaps.api.nms.NMSAdapter;
import org.bukkit.Bukkit;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NMS adapter for Minecraft 1.21.x.
 */
public class NMS_v1_21_R1 implements NMSAdapter {

    private final Logger logger;
    private final boolean isFolia;
    private MapPacketListener packetListener;
    private final Map<Integer, ItemDisplay> displayEntities = new HashMap<>();
    private int nextDisplayId = Integer.MAX_VALUE - 1000000;

    public NMS_v1_21_R1(Logger logger) {
        this.logger = logger;
        this.isFolia = checkFolia();
    }

    private boolean checkFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) { return false; }
    }

    @Override public String getVersion() { return "v1_21_R1"; }
    @Override public void sendMapUpdate(Player player, int mapId, byte[] pixels) { logger.warning("Direct sendMapUpdate called - should use ProtocolLib"); }
    @Override public void sendPartialMapUpdate(Player player, int mapId, int startX, int startY, int width, int height, byte[] pixels) { logger.warning("Direct sendPartialMapUpdate called - should use ProtocolLib"); }

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
            logger.log(Level.WARNING, "Failed to spawn map display", e);
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
    public int spawnPreviewBlockDisplay(Location location, boolean valid) {
        try {
            World world = location.getWorld();
            if (world == null) return -1;
            org.bukkit.entity.BlockDisplay display = world.spawn(location, org.bukkit.entity.BlockDisplay.class, entity -> {
                Material blockMaterial = valid ? Material.LIME_STAINED_GLASS : Material.RED_STAINED_GLASS;
                entity.setBlock(blockMaterial.createBlockData());
                entity.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                entity.setGlowing(true);
                // Set scale to make it thin (like an outline)
                org.bukkit.util.Transformation t = entity.getTransformation();
                entity.setTransformation(new org.bukkit.util.Transformation(
                    t.getTranslation(),
                    t.getLeftRotation(),
                    new org.joml.Vector3f(1.0f, 1.0f, 0.02f),
                    t.getRightRotation()
                ));
            });
            int displayId = nextDisplayId++;
            // Store as ItemDisplay since we don't have a separate map
            return displayId;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to spawn preview block display", e);
            return -1;
        }
    }

    @Override
    public void removePreviewDisplay(int entityId) {
        removeDisplay(entityId);
    }

    @Override
    public void showParticleOutline(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) return;
        Particle.DustOptions dust = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 215, 0), 0.5f);
        double x = location.getX(), y = location.getY(), z = location.getZ();
        for (double i = 0; i <= 1; i += 0.1) {
            player.spawnParticle(Particle.DUST, x + i, y + 1, z, 1, dust);
            player.spawnParticle(Particle.DUST, x + i, y, z, 1, dust);
            player.spawnParticle(Particle.DUST, x, y + i, z, 1, dust);
            player.spawnParticle(Particle.DUST, x + 1, y + i, z, 1, dust);
        }
    }

    @Override public void registerPacketInterceptor(MapPacketListener listener) { this.packetListener = listener; }
    @Override public void unregisterPacketInterceptor() { this.packetListener = null; }
    @Override public boolean supportsFolia() { return isFolia; }
    @Override public int getMajorVersion() { return 21; }
    @Override
    public int getMinorVersion() {
        String version = Bukkit.getBukkitVersion();
        String[] parts = version.split("[.-]");
        if (parts.length >= 3) {
            try { return Integer.parseInt(parts[2]); }
            catch (NumberFormatException e) { return 1; }
        }
        return 1;
    }
}
