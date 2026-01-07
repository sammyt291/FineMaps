package com.example.mapdb.nms.v1_12_R1;

import com.example.mapdb.core.nms.NMSAdapter;
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
            
            // Create map update packet
            PacketPlayOutMap packet = new PacketPlayOutMap(
                mapId,          // Map ID
                (byte) 0,       // Scale
                false,          // Tracking position
                new java.util.ArrayList<>(),  // Icons
                pixels,         // Colors
                0,              // Start X
                0,              // Start Y
                128,            // Width
                128             // Height
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
                mapId,
                (byte) 0,
                false,
                new java.util.ArrayList<>(),
                pixels,
                startX,
                startY,
                width,
                height
            );
            
            nmsPlayer.playerConnection.sendPacket(packet);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send partial map update", e);
        }
    }

    @Override
    public ItemStack createMapItem(int mapId) {
        // In 1.12, maps use durability as the map ID
        return new ItemStack(Material.MAP, 1, (short) mapId);
    }

    @Override
    public int getMapId(ItemStack item) {
        if (item == null || item.getType() != Material.MAP) {
            return -1;
        }
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

    @Override
    public int spawnMapDisplay(Location location, int mapId) {
        // Block displays not supported in 1.12
        return -1;
    }

    @Override
    public void removeDisplay(int entityId) {
        // Not needed in 1.12
    }

    @Override
    public boolean supportsBlockDisplays() {
        return false;
    }

    @Override
    public void showParticleOutline(Player player, Location location) {
        // Show flame particles in a square pattern
        org.bukkit.World world = location.getWorld();
        if (world == null) return;
        
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        
        for (double i = 0; i <= 1; i += 0.1) {
            world.spawnParticle(org.bukkit.Particle.FLAME, x + i, y + 1, z, 1, 0, 0, 0, 0);
            world.spawnParticle(org.bukkit.Particle.FLAME, x + i, y, z, 1, 0, 0, 0, 0);
            world.spawnParticle(org.bukkit.Particle.FLAME, x, y + i, z, 1, 0, 0, 0, 0);
            world.spawnParticle(org.bukkit.Particle.FLAME, x + 1, y + i, z, 1, 0, 0, 0, 0);
        }
    }

    @Override
    public void registerPacketInterceptor(MapPacketListener listener) {
        this.packetListener = listener;
        // In 1.12, we would use ProtocolLib for packet interception
        // This is handled by the ProtocolLibAdapter in core
    }

    @Override
    public void unregisterPacketInterceptor() {
        this.packetListener = null;
    }

    @Override
    public boolean supportsFolia() {
        return false;
    }

    @Override
    public int getMajorVersion() {
        return 12;
    }

    @Override
    public int getMinorVersion() {
        return 2;
    }
}
