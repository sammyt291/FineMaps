package org.finetree.finemaps.api.event;

import org.finetree.finemaps.api.map.StoredMap;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Called when a stored map is being deleted.
 * Can be cancelled to prevent deletion.
 */
public class MapDeleteEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final StoredMap map;
    private final Player player;
    private boolean cancelled;

    public MapDeleteEvent(StoredMap map, Player player) {
        this.map = map;
        this.player = player;
        this.cancelled = false;
    }

    /**
     * Gets the map being deleted.
     *
     * @return The stored map
     */
    public StoredMap getMap() {
        return map;
    }

    /**
     * Gets the player deleting the map, if applicable.
     *
     * @return The player, or null if deleted by system
     */
    public Player getPlayer() {
        return player;
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
