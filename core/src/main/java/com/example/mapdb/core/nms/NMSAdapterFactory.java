package com.example.mapdb.core.nms;

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

        // Check for Folia
        boolean isFolia = isFolia();
        if (isFolia) {
            logger.info("Folia detected - using Folia-compatible adapter");
        }

        // Use ProtocolLib-based adapter for broad compatibility
        return new ProtocolLibAdapter(logger, version, isFolia);
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
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
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
     * Checks if block displays are available (1.19.4+).
     *
     * @return true if block displays are supported
     */
    public static boolean supportsBlockDisplays() {
        return isVersionSupported(19, 4) || getMajorVersion() >= 20;
    }
}
