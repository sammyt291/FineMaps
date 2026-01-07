package com.comphenix.protocol;

import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.entity.Player;

/**
 * Stub for ProtocolLib - replace with actual dependency when building.
 */
public class ProtocolManager {
    
    public PacketContainer createPacket(PacketType type) {
        return new PacketContainer(type);
    }
    
    public void sendServerPacket(Player player, PacketContainer packet) throws Exception {
        // Stub
    }
    
    public void addPacketListener(PacketAdapter adapter) {
        // Stub
    }
    
    public void removePacketListener(PacketAdapter adapter) {
        // Stub
    }
}
