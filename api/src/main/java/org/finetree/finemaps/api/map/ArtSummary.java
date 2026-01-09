package org.finetree.finemaps.api.map;

/**
 * Represents a named "art" entry in FineMaps.
 *
 * This corresponds to either:
 * - a single stored map (mapId, 1x1)
 * - a multi-block group (groupId, WxH)
 */
public class ArtSummary {

    private final String pluginId;
    private final String name;
    private final boolean multiBlock;
    private final long id; // mapId for single, groupId for multi-block
    private final int width;
    private final int height;

    public ArtSummary(String pluginId, String name, boolean multiBlock, long id, int width, int height) {
        this.pluginId = pluginId;
        this.name = name;
        this.multiBlock = multiBlock;
        this.id = id;
        this.width = width;
        this.height = height;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getName() {
        return name;
    }

    public boolean isMultiBlock() {
        return multiBlock;
    }

    public long getId() {
        return id;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}
