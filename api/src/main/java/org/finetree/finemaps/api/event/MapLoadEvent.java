package org.finetree.finemaps.api.event;

import org.finetree.finemaps.api.map.StoredMap;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Called when a stored map is being loaded for a player.
 * Can be cancelled to prevent the map from being sent.
 */
public class MapLoadEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final StoredMap map;
    private final Player player;
    private final int virtualId;
    private boolean cancelled;

    public MapLoadEvent(StoredMap map, Player player, int virtualId) {
        this.map = map;
        this.player = player;
        this.virtualId = virtualId;
        this.cancelled = false;
    }

    /**
     * Gets the map being loaded.
     *
     * @return The stored map
     */
    public StoredMap getMap() {
        return map;
    }

    /**
     * Gets the player the map is being loaded for.
     *
     * @return The player
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the virtual map ID assigned for this session.
     *
     * @return The virtual ID (0-32000)
     */
    public int getVirtualId() {
        return virtualId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
