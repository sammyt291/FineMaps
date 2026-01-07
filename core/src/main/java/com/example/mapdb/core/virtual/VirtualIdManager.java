package com.example.mapdb.core.virtual;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the mapping between 64-bit database map IDs and virtual map IDs (0-32000).
 * Each player can have their own virtual ID space to allow viewing different maps.
 */
public class VirtualIdManager {

    // Maximum vanilla map ID we'll use
    private static final int MAX_VIRTUAL_ID = 30000;
    
    // Reserve some IDs for actual vanilla maps
    private static final int MIN_VIRTUAL_ID = 1000;

    // Global mappings (for maps in the world visible to all)
    private final Map<Long, Integer> globalDbToVirtual = new ConcurrentHashMap<>();
    private final Map<Integer, Long> globalVirtualToDb = new ConcurrentHashMap<>();
    
    // Per-player mappings (for maps in hand/inventory)
    private final Map<UUID, PlayerVirtualIdSpace> playerSpaces = new ConcurrentHashMap<>();
    
    // Global virtual ID counter
    private final AtomicInteger nextGlobalVirtualId = new AtomicInteger(MIN_VIRTUAL_ID);
    
    // Tracks which virtual IDs are currently in use
    private final Set<Integer> usedGlobalIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final int maxVirtualIds;

    public VirtualIdManager(int maxVirtualIds) {
        this.maxVirtualIds = Math.min(maxVirtualIds, MAX_VIRTUAL_ID);
    }

    /**
     * Gets or creates a virtual ID for a database map ID (global scope).
     * Used for maps placed in the world.
     *
     * @param dbMapId The 64-bit database map ID
     * @return The virtual ID (0-32000)
     */
    public int getOrCreateGlobalVirtualId(long dbMapId) {
        return globalDbToVirtual.computeIfAbsent(dbMapId, id -> {
            int virtualId = allocateGlobalVirtualId();
            globalVirtualToDb.put(virtualId, id);
            return virtualId;
        });
    }

    /**
     * Gets or creates a virtual ID for a player's map.
     * Each player has their own virtual ID space for hand/inventory maps.
     *
     * @param playerUUID The player's UUID
     * @param dbMapId The 64-bit database map ID
     * @return The virtual ID for this player
     */
    public int getOrCreatePlayerVirtualId(UUID playerUUID, long dbMapId) {
        PlayerVirtualIdSpace space = playerSpaces.computeIfAbsent(
            playerUUID, 
            uuid -> new PlayerVirtualIdSpace()
        );
        return space.getOrCreateVirtualId(dbMapId);
    }

    /**
     * Gets the database map ID from a global virtual ID.
     *
     * @param virtualId The virtual ID
     * @return The database map ID, or -1 if not found
     */
    public long getGlobalDbMapId(int virtualId) {
        return globalVirtualToDb.getOrDefault(virtualId, -1L);
    }

    /**
     * Gets the database map ID from a player's virtual ID.
     *
     * @param playerUUID The player's UUID
     * @param virtualId The virtual ID
     * @return The database map ID, or -1 if not found
     */
    public long getPlayerDbMapId(UUID playerUUID, int virtualId) {
        PlayerVirtualIdSpace space = playerSpaces.get(playerUUID);
        if (space == null) {
            return -1L;
        }
        return space.getDbMapId(virtualId);
    }

    /**
     * Checks if a virtual ID is managed by us.
     *
     * @param virtualId The virtual ID to check
     * @return true if this is a managed virtual ID
     */
    public boolean isVirtualId(int virtualId) {
        return virtualId >= MIN_VIRTUAL_ID && virtualId <= maxVirtualIds;
    }

    /**
     * Releases a global virtual ID mapping.
     *
     * @param dbMapId The database map ID
     */
    public void releaseGlobalVirtualId(long dbMapId) {
        Integer virtualId = globalDbToVirtual.remove(dbMapId);
        if (virtualId != null) {
            globalVirtualToDb.remove(virtualId);
            usedGlobalIds.remove(virtualId);
        }
    }

    /**
     * Releases a player's virtual ID mapping.
     *
     * @param playerUUID The player's UUID
     * @param dbMapId The database map ID
     */
    public void releasePlayerVirtualId(UUID playerUUID, long dbMapId) {
        PlayerVirtualIdSpace space = playerSpaces.get(playerUUID);
        if (space != null) {
            space.releaseVirtualId(dbMapId);
        }
    }

    /**
     * Clears all virtual IDs for a player (on disconnect).
     *
     * @param playerUUID The player's UUID
     */
    public void clearPlayerSpace(UUID playerUUID) {
        playerSpaces.remove(playerUUID);
    }

    /**
     * Gets all active global virtual IDs.
     *
     * @return Set of active virtual IDs
     */
    public Set<Integer> getActiveGlobalIds() {
        return new HashSet<>(usedGlobalIds);
    }

    /**
     * Gets the database map ID for any virtual ID (checks global and all players).
     *
     * @param virtualId The virtual ID
     * @return The database map ID, or -1 if not found
     */
    public long resolveVirtualId(int virtualId) {
        // Check global first
        long dbId = getGlobalDbMapId(virtualId);
        if (dbId != -1) {
            return dbId;
        }
        
        // Check all player spaces
        for (PlayerVirtualIdSpace space : playerSpaces.values()) {
            dbId = space.getDbMapId(virtualId);
            if (dbId != -1) {
                return dbId;
            }
        }
        
        return -1;
    }

    /**
     * Performs cleanup of stale mappings.
     *
     * @param activeDbIds Set of database map IDs that are still active
     */
    public void cleanup(Set<Long> activeDbIds) {
        // Cleanup global mappings
        Set<Long> toRemove = new HashSet<>();
        for (Long dbId : globalDbToVirtual.keySet()) {
            if (!activeDbIds.contains(dbId)) {
                toRemove.add(dbId);
            }
        }
        toRemove.forEach(this::releaseGlobalVirtualId);
    }

    private int allocateGlobalVirtualId() {
        for (int attempts = 0; attempts < maxVirtualIds; attempts++) {
            int id = nextGlobalVirtualId.getAndIncrement();
            if (id > maxVirtualIds) {
                nextGlobalVirtualId.set(MIN_VIRTUAL_ID);
                id = nextGlobalVirtualId.getAndIncrement();
            }
            
            if (usedGlobalIds.add(id)) {
                return id;
            }
        }
        throw new IllegalStateException("No available virtual IDs");
    }

    /**
     * Per-player virtual ID space.
     */
    private class PlayerVirtualIdSpace {
        private final Map<Long, Integer> dbToVirtual = new ConcurrentHashMap<>();
        private final Map<Integer, Long> virtualToDb = new ConcurrentHashMap<>();
        private final AtomicInteger nextId = new AtomicInteger(MIN_VIRTUAL_ID);
        private final Set<Integer> usedIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

        int getOrCreateVirtualId(long dbMapId) {
            return dbToVirtual.computeIfAbsent(dbMapId, id -> {
                int virtualId = allocateId();
                virtualToDb.put(virtualId, id);
                return virtualId;
            });
        }

        long getDbMapId(int virtualId) {
            return virtualToDb.getOrDefault(virtualId, -1L);
        }

        void releaseVirtualId(long dbMapId) {
            Integer virtualId = dbToVirtual.remove(dbMapId);
            if (virtualId != null) {
                virtualToDb.remove(virtualId);
                usedIds.remove(virtualId);
            }
        }

        private int allocateId() {
            for (int attempts = 0; attempts < maxVirtualIds; attempts++) {
                int id = nextId.getAndIncrement();
                if (id > maxVirtualIds) {
                    nextId.set(MIN_VIRTUAL_ID);
                    id = nextId.getAndIncrement();
                }
                
                if (usedIds.add(id)) {
                    return id;
                }
            }
            throw new IllegalStateException("No available virtual IDs for player");
        }
    }
}
