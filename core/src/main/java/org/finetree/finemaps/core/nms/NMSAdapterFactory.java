package org.finetree.finemaps.core.nms;

import org.finetree.finemaps.api.nms.NMSAdapter;
import org.bukkit.Bukkit;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Factory for creating version-specific NMS adapters.
 */
public final class NMSAdapterFactory {

    private static final Pattern MINECRAFT_VERSION = Pattern.compile("(?<![0-9])1\\.(\\d+)(?:\\.(\\d+))?(?![0-9])");

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

        // FineMaps requires Minecraft 1.21+ (Java 21 bytecode).
        int major = getMajorVersion();
        if (major > 0 && major < 21) {
            throw new UnsupportedOperationException(
                "FineMaps requires Minecraft 1.21+ (detected: " + Bukkit.getBukkitVersion() + ")."
            );
        }

        // Check for Folia
        boolean isFolia = isFolia();

        // Check if ProtocolLib is available
        if (isProtocolLibAvailable()) {
            return new ProtocolLibAdapter(logger, version, isFolia);
        }
        
        // Fall back to universal Bukkit adapter (basic mode, no version-specific code)
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
        
        // Fall back to the Minecraft version reported by the API. Some server forks use
        // their own release number for getBukkitVersion(), so that value is not reliable.
        String minecraftVersion = getMinecraftVersion();
        return minecraftVersion != null ? minecraftVersion : Bukkit.getBukkitVersion();
    }

    /**
     * Gets the major version number (e.g., 21 for 1.21.x).
     *
     * @return Major version number
     */
    public static int getMajorVersion() {
        return getVersionComponent(0);
    }

    /**
     * Gets the minor version number (e.g., 1 for 1.21.1).
     *
     * @return Minor version number
     */
    public static int getMinorVersion() {
        return getVersionComponent(1);
    }

    private static int getVersionComponent(int index) {
        int[] version = parseMinecraftVersion(getMinecraftVersion());
        return version == null ? 0 : version[index];
    }

    /**
     * Returns the actual game version, rather than a fork-specific distribution version.
     */
    public static String getMinecraftVersion() {
        try {
            String version = Bukkit.getMinecraftVersion();
            if (parseMinecraftVersion(version) != null) return version;
        } catch (Throwable ignored) {
            // Retain compatibility with API implementations that do not expose this method.
        }

        String[] fallbackVersions = {Bukkit.getBukkitVersion(), Bukkit.getVersion()};
        for (String version : fallbackVersions) {
            int[] parsed = parseMinecraftVersion(version);
            if (parsed != null) {
                Matcher matcher = MINECRAFT_VERSION.matcher(version);
                if (matcher.find()) return matcher.group();
            }
        }
        return null;
    }

    static int[] parseMinecraftVersion(String version) {
        if (version == null) return null;
        Matcher matcher = MINECRAFT_VERSION.matcher(version);
        if (!matcher.find()) return null;
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
            return new int[] {major, minor};
        } catch (NumberFormatException ignored) {
            return null;
        }
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
