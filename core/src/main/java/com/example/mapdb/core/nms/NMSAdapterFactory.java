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

        // Check if ProtocolLib is available
        if (isProtocolLibAvailable()) {
            logger.info("ProtocolLib found - using ProtocolLib adapter for full functionality");
            return new ProtocolLibAdapter(logger, version, isFolia);
        }
        
        // Fall back to native NMS adapter (basic mode)
        logger.info("ProtocolLib not found - using native NMS adapter (basic mode)");
        return createNativeAdapter(logger, version);
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
     * Creates a native NMS adapter for the current version.
     *
     * @param logger Logger instance
     * @param version Server version
     * @return Native NMS adapter
     */
    private static NMSAdapter createNativeAdapter(Logger logger, String version) {
        int major = getMajorVersion();
        
        try {
            String adapterClass;
            if (major >= 21) {
                adapterClass = "com.example.mapdb.nms.v1_21_R1.NMS_v1_21_R1";
            } else if (major >= 20) {
                adapterClass = "com.example.mapdb.nms.v1_20_R3.NMS_v1_20_R3";
            } else if (major >= 19) {
                adapterClass = "com.example.mapdb.nms.v1_19_R3.NMS_v1_19_R3";
            } else if (major >= 18) {
                adapterClass = "com.example.mapdb.nms.v1_18_R2.NMS_v1_18_R2";
            } else if (major >= 17) {
                adapterClass = "com.example.mapdb.nms.v1_17_R1.NMS_v1_17_R1";
            } else if (major >= 16) {
                adapterClass = "com.example.mapdb.nms.v1_16_R3.NMS_v1_16_R3";
            } else if (major >= 13) {
                adapterClass = "com.example.mapdb.nms.v1_13_R2.NMS_v1_13_R2";
            } else {
                adapterClass = "com.example.mapdb.nms.v1_12_R1.NMS_v1_12_R1";
            }
            
            Class<?> clazz = Class.forName(adapterClass);
            return (NMSAdapter) clazz.getConstructor(Logger.class).newInstance(logger);
        } catch (Exception e) {
            logger.warning("Failed to load native NMS adapter, using fallback: " + e.getMessage());
            return new FallbackNMSAdapter(logger, version);
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
