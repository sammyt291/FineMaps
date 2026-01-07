package com.comphenix.protocol;

import org.bukkit.plugin.Plugin;

/**
 * Stub for ProtocolLib - replace with actual dependency when building.
 */
public class ProtocolLibrary {
    private static ProtocolManager manager = new ProtocolManager();
    
    public static ProtocolManager getProtocolManager() {
        return manager;
    }
    
    public static Plugin getPlugin() {
        return null;
    }
}
