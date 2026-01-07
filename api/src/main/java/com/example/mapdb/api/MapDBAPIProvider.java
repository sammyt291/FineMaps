package com.example.mapdb.api;

/**
 * Provider for the MapDB API instance.
 * Used internally to manage the singleton API instance.
 */
public final class MapDBAPIProvider {

    private static MapDBAPI api;

    private MapDBAPIProvider() {
        // Private constructor to prevent instantiation
    }

    /**
     * Gets the current API instance.
     *
     * @return The API instance, or null if not registered
     */
    public static MapDBAPI getAPI() {
        return api;
    }

    /**
     * Registers the API instance. Should only be called by the MapDB plugin.
     *
     * @param instance The API implementation
     * @throws IllegalStateException if API is already registered
     */
    public static void register(MapDBAPI instance) {
        if (api != null) {
            throw new IllegalStateException("MapDB API is already registered!");
        }
        api = instance;
    }

    /**
     * Unregisters the API instance. Should only be called by the MapDB plugin on disable.
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
