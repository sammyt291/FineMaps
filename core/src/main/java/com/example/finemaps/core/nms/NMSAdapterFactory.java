package com.example.finemaps.core.nms;

import com.example.finemaps.api.nms.NMSAdapter;
import org.bukkit.Bukkit;

import java.util.logging.Logger;

/**
 * Factory for creating version-specific NMS adapters.
 */
public final class NMSAdapterFactory {

    private NMSAdapterFactory() {}

    /**
     * Creates an NMS adapter for the current server version.
     *
     * @param logger Logger instance
     * @return The appropriate NMS adapter
     * @throws UnsupportedOperationException if version is not supported
     */
    public static NMSAdapter createAdapter(Logger logger) {
        String version = getServerVersion();
        logger.info("Detected server version: " + version);

        // FineMaps requires Minecraft 1.21+ (Java 21 bytecode).
        int major = getMajorVersion();
        if (major > 0 && major < 21) {
            throw new UnsupportedOperationException(
                "FineMaps requires Minecraft 1.21+ (detected: " + Bukkit.getBukkitVersion() + ")."
            );
        }

        // Check for Folia
        boolean isFolia = isFolia();
        if (isFolia) {
            logger.info("Folia detected - using Folia-compatible adapter");
        }

        // Check if ProtocolLib is available
        if (isProtocolLibAvailable()) {
            logger.info("ProtocolLib found - using ProtocolLib adapter for full functionality");
            return new ProtocolLibAdapter(logger, version, isFolia);
        }
        
        // Fall back to universal Bukkit adapter (basic mode, no version-specific code)
        logger.info("ProtocolLib not found - using Bukkit adapter (basic mode)");
        return new BukkitNMSAdapter(logger);
    }
    
    /**
     * Checks if ProtocolLib is available.
     *
     * @return true if ProtocolLib is loaded
     */
    public static boolean isProtocolLibAvailable() {
        try {
            Class.forName("com.comphenix.protocol.ProtocolLibrary");
            return org.bukkit.Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Gets the NMS version string from the server.
     *
     * @return Version string like "v1_21_R1" or "1.21.1"
     */
    public static String getServerVersion() {
        String packageName = Bukkit.getServer().getClass().getPackage().getName();
        
        // Try to get version from package name (older method)
        if (packageName.contains(".v")) {
            return packageName.substring(packageName.lastIndexOf('.') + 1);
        }
        
        // Fall back to Bukkit version for newer servers
        String bukkitVersion = Bukkit.getBukkitVersion();
        // Format: "1.21.1-R0.1-SNAPSHOT"
        int dash = bukkitVersion.indexOf('-');
        if (dash > 0) {
            return bukkitVersion.substring(0, dash);
        }
        return bukkitVersion;
    }

    /**
     * Gets the major version number (e.g., 21 for 1.21.x).
     *
     * @return Major version number
     */
    public static int getMajorVersion() {
        String version = Bukkit.getBukkitVersion();
        // Format: "1.21.1-R0.1-SNAPSHOT"
        String[] parts = version.split("[.-]");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Gets the minor version number (e.g., 1 for 1.21.1).
     *
     * @return Minor version number
     */
    public static int getMinorVersion() {
        String version = Bukkit.getBukkitVersion();
        String[] parts = version.split("[.-]");
        if (parts.length >= 3) {
            try {
                return Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Checks if running on Folia.
     *
     * @return true if Folia
     */
    public static boolean isFolia() {
        // Server name
        try {
            String name = Bukkit.getName();
            if (name != null && name.equalsIgnoreCase("Folia")) return true;
        } catch (Throwable ignored) {
        }
        try {
            if (Bukkit.getServer() != null) {
                String name = Bukkit.getServer().getName();
                if (name != null && name.equalsIgnoreCase("Folia")) return true;
            }
        } catch (Throwable ignored) {
        }

        // Version string (case-insensitive)
        try {
            String v = Bukkit.getVersion();
            if (v != null && v.toLowerCase().contains("folia")) return true;
        } catch (Throwable ignored) {
        }

        // Presence of Folia classes
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable ignored) {
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            return true;
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable ignored) {
        }

        // Presence of Folia scheduler accessors on Bukkit
        try {
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            return true;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        return false;
    }

    /**
     * Checks if running on Paper.
     *
     * @return true if Paper
     */
    public static boolean isPaper() {
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            return true;
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("io.papermc.paper.configuration.Configuration");
                return true;
            } catch (ClassNotFoundException e2) {
                return false;
            }
        }
    }

    /**
     * Checks if a specific version is supported.
     *
     * @param major Major version
     * @param minor Minor version
     * @return true if supported
     */
    public static boolean isVersionSupported(int major, int minor) {
        int currentMajor = getMajorVersion();
        int currentMinor = getMinorVersion();
        
        if (currentMajor > major) return true;
        if (currentMajor < major) return false;
        return currentMinor >= minor;
    }

    /**
     * Checks if block displays are available (always true on 1.21+).
     *
     * @return true if block displays are supported
     */
    public static boolean supportsBlockDisplays() {
        return true;
    }
}
