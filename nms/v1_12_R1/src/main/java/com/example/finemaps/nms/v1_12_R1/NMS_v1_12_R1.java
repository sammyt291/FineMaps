package com.example.finemaps.nms.v1_12_R1;

import com.example.finemaps.api.nms.NMSAdapter;
import net.minecraft.server.v1_12_R1.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.v1_12_R1.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_12_R1.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NMS adapter for Minecraft 1.12.2.
 */
public class NMS_v1_12_R1 implements NMSAdapter {

    private final Logger logger;
    private MapPacketListener packetListener;

    public NMS_v1_12_R1(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String getVersion() {
        return "v1_12_R1";
    }

    @Override
    public void sendMapUpdate(Player player, int mapId, byte[] pixels) {
        try {
            EntityPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            
            PacketPlayOutMap packet = new PacketPlayOutMap(
                mapId, (byte) 0, false,
                new java.util.ArrayList<>(),
                pixels, 0, 0, 128, 128
            );
            
            nmsPlayer.playerConnection.sendPacket(packet);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send map update", e);
        }
    }

    @Override
    public void sendPartialMapUpdate(Player player, int mapId, int startX, int startY,
                                      int width, int height, byte[] pixels) {
        try {
            EntityPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            
            PacketPlayOutMap packet = new PacketPlayOutMap(
                mapId, (byte) 0, false,
                new java.util.ArrayList<>(),
                pixels, startX, startY, width, height
            );
            
            nmsPlayer.playerConnection.sendPacket(packet);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send partial map update", e);
        }
    }

    @Override
    public ItemStack createMapItem(int mapId) {
        return new ItemStack(Material.MAP, 1, (short) mapId);
    }

    @Override
    public int getMapId(ItemStack item) {
        if (item == null || item.getType() != Material.MAP) return -1;
        return item.getDurability();
    }

    @Override
    public ItemStack setMapId(ItemStack item, int mapId) {
        item.setDurability((short) mapId);
        return item;
    }

    @Override
    public boolean isFilledMap(ItemStack item) {
        return item != null && item.getType() == Material.MAP;
    }

    @Override public int spawnMapDisplay(Location location, int mapId) { return -1; }
    @Override public void removeDisplay(int entityId) {}
    @Override public boolean supportsBlockDisplays() { return false; }
    @Override public int spawnPreviewBlockDisplay(Location location, boolean valid, float scaleX, float scaleY, float scaleZ) { return -1; }
    @Override public void removePreviewDisplay(int entityId) {}

    @Override
    public void showParticleOutline(Player player, Location location) {
        org.bukkit.World world = location.getWorld();
        if (world == null) return;
        double x = location.getX(), y = location.getY(), z = location.getZ();
        for (double i = 0; i <= 1; i += 0.1) {
            world.spawnParticle(org.bukkit.Particle.FLAME, x + i, y + 1, z, 1, 0, 0, 0, 0);
            world.spawnParticle(org.bukkit.Particle.FLAME, x + i, y, z, 1, 0, 0, 0, 0);
            world.spawnParticle(org.bukkit.Particle.FLAME, x, y + i, z, 1, 0, 0, 0, 0);
            world.spawnParticle(org.bukkit.Particle.FLAME, x + 1, y + i, z, 1, 0, 0, 0, 0);
        }
    }

    @Override public void registerPacketInterceptor(MapPacketListener listener) { this.packetListener = listener; }
    @Override public void unregisterPacketInterceptor() { this.packetListener = null; }
    @Override public boolean supportsFolia() { return false; }
    @Override public int getMajorVersion() { return 12; }
    @Override public int getMinorVersion() { return 2; }
}
