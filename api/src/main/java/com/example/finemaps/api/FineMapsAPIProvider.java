package com.example.finemaps.api;

/**
 * Provider for the FineMaps API instance.
 * Used internally to manage the singleton API instance.
 */
public final class FineMapsAPIProvider {

    private static FineMapsAPI api;

    private FineMapsAPIProvider() {
        // Private constructor to prevent instantiation
    }

    /**
     * Gets the current API instance.
     *
     * @return The API instance, or null if not registered
     */
    public static FineMapsAPI getAPI() {
        return api;
    }

    /**
     * Registers the API instance. Should only be called by the FineMaps plugin.
     *
     * @param instance The API implementation
     * @throws IllegalStateException if API is already registered
     */
    public static void register(FineMapsAPI instance) {
        if (api != null) {
            throw new IllegalStateException("FineMaps API is already registered!");
        }
        api = instance;
    }

    /**
     * Unregisters the API instance. Should only be called by the FineMaps plugin on disable.
     */
    public static void unregister() {
        api = null;
    }

    /**
     * Checks if the API is available.
     *
     * @return true if the API is registered and available
     */
    public static boolean isAvailable() {
        return api != null;
    }
}
