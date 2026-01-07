package com.example.finemaps.api.event;

import com.example.finemaps.api.map.StoredMap;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Called when a new map is being created.
 * Can be cancelled to prevent map creation.
 */
public class MapCreateEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final StoredMap map;
    private final Player creator;
    private final String pluginId;
    private boolean cancelled;

    public MapCreateEvent(StoredMap map, Player creator, String pluginId) {
        this.map = map;
        this.creator = creator;
        this.pluginId = pluginId;
        this.cancelled = false;
    }

    /**
     * Gets the map being created.
     *
     * @return The stored map
     */
    public StoredMap getMap() {
        return map;
    }

    /**
     * Gets the player creating the map.
     *
     * @return The creator, or null if created by system
     */
    public Player getCreator() {
        return creator;
    }

    /**
     * Gets the plugin creating the map.
     *
     * @return The plugin identifier
     */
    public String getPluginId() {
        return pluginId;
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
