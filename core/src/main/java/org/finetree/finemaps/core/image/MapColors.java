package org.finetree.finemaps.core.image;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Minecraft map color palette utilities.
 * Contains the full map color palette and conversion methods.
 */
public final class MapColors {

    private MapColors() {}

    /**
     * Base colors used in Minecraft maps.
     * Each base color has 4 shades (multipliers: 180, 220, 255, 135).
     */
    private static final int[][] BASE_COLORS = {
        // Transparent (index 0-3)
        {0, 0, 0},
        // Grass (index 4-7)
        {127, 178, 56},
        // Sand (index 8-11)
        {247, 233, 163},
        // Wool (index 12-15)
        {199, 199, 199},
        // Fire (index 16-19)
        {255, 0, 0},
        // Ice (index 20-23)
        {160, 160, 255},
        // Metal (index 24-27)
        {167, 167, 167},
        // Plant (index 28-31)
        {0, 124, 0},
        // Snow (index 32-35)
        {255, 255, 255},
        // Clay (index 36-39)
        {164, 168, 184},
        // Dirt (index 40-43)
        {151, 109, 77},
        // Stone (index 44-47)
        {112, 112, 112},
        // Water (index 48-51)
        {64, 64, 255},
        // Wood (index 52-55)
        {143, 119, 72},
        // Quartz (index 56-59)
        {255, 252, 245},
        // Color Orange (index 60-63)
        {216, 127, 51},
        // Color Magenta (index 64-67)
        {178, 76, 216},
        // Color Light Blue (index 68-71)
        {102, 153, 216},
        // Color Yellow (index 72-75)
        {229, 229, 51},
        // Color Lime (index 76-79)
        {127, 204, 25},
        // Color Pink (index 80-83)
        {242, 127, 165},
        // Color Gray (index 84-87)
        {76, 76, 76},
        // Color Light Gray (index 88-91)
        {153, 153, 153},
        // Color Cyan (index 92-95)
        {76, 127, 153},
        // Color Purple (index 96-99)
        {127, 63, 178},
        // Color Blue (index 100-103)
        {51, 76, 178},
        // Color Brown (index 104-107)
        {102, 76, 51},
        // Color Green (index 108-111)
        {102, 127, 51},
        // Color Red (index 112-115)
        {153, 51, 51},
        // Color Black (index 116-119)
        {25, 25, 25},
        // Gold (index 120-123)
        {250, 238, 77},
        // Diamond (index 124-127)
        {92, 219, 213},
        // Lapis (index 128-131)
        {74, 128, 255},
        // Emerald (index 132-135)
        {0, 217, 58},
        // Podzol (index 136-139)
        {129, 86, 49},
        // Nether (index 140-143)
        {112, 2, 0},
        // Terracotta White (index 144-147)
        {209, 177, 161},
        // Terracotta Orange (index 148-151)
        {159, 82, 36},
        // Terracotta Magenta (index 152-155)
        {149, 87, 108},
        // Terracotta Light Blue (index 156-159)
        {112, 108, 138},
        // Terracotta Yellow (index 160-163)
        {186, 133, 36},
        // Terracotta Lime (index 164-167)
        {103, 117, 53},
        // Terracotta Pink (index 168-171)
        {160, 77, 78},
        // Terracotta Gray (index 172-175)
        {57, 41, 35},
        // Terracotta Light Gray (index 176-179)
        {135, 107, 98},
        // Terracotta Cyan (index 180-183)
        {87, 92, 92},
        // Terracotta Purple (index 184-187)
        {122, 73, 88},
        // Terracotta Blue (index 188-191)
        {76, 62, 92},
        // Terracotta Brown (index 192-195)
        {76, 50, 35},
        // Terracotta Green (index 196-199)
        {76, 82, 42},
        // Terracotta Red (index 200-203)
        {142, 60, 46},
        // Terracotta Black (index 204-207)
        {37, 22, 16},
        // Crimson Nylium (index 208-211)
        {189, 48, 49},
        // Crimson Stem (index 212-215)
        {148, 63, 97},
        // Crimson Hyphae (index 216-219)
        {92, 25, 29},
        // Warped Nylium (index 220-223)
        {22, 126, 134},
        // Warped Stem (index 224-227)
        {58, 142, 140},
        // Warped Hyphae (index 228-231)
        {86, 44, 62},
        // Warped Wart Block (index 232-235)
        {20, 180, 133},
        // Deepslate (index 236-239)
        {100, 100, 100},
        // Raw Iron (index 240-243)
        {216, 175, 147},
        // Glow Lichen (index 244-247)
        {127, 167, 150},
    };

    /**
     * Shade multipliers for each base color.
     */
    private static final double[] SHADE_MULTIPLIERS = {
        180.0 / 255.0,  // Dark
        220.0 / 255.0,  // Normal
        255.0 / 255.0,  // Bright
        135.0 / 255.0   // Darker
    };

    /**
     * Pre-computed full color palette (all shades).
     */
    private static final Color[] FULL_PALETTE;
    
    /**
     * Cache for nearest color lookups.
     */
    private static final Map<Integer, Byte> COLOR_CACHE = new HashMap<>();

    static {
        // Initialize full palette
        FULL_PALETTE = new Color[BASE_COLORS.length * 4];
        for (int baseIndex = 0; baseIndex < BASE_COLORS.length; baseIndex++) {
            int[] baseColor = BASE_COLORS[baseIndex];
            for (int shade = 0; shade < 4; shade++) {
                int index = baseIndex * 4 + shade;
                if (baseIndex == 0) {
                    // Transparent colors
                    FULL_PALETTE[index] = new Color(0, 0, 0, 0);
                } else {
                    double multiplier = SHADE_MULTIPLIERS[shade];
                    int r = Math.min(255, (int) (baseColor[0] * multiplier));
                    int g = Math.min(255, (int) (baseColor[1] * multiplier));
                    int b = Math.min(255, (int) (baseColor[2] * multiplier));
                    FULL_PALETTE[index] = new Color(r, g, b);
                }
            }
        }
    }

    /**
     * Gets the full color palette.
     *
     * @return Array of all map colors
     */
    public static Color[] getPalette() {
        return FULL_PALETTE.clone();
    }

    /**
     * Gets the color at a specific palette index.
     *
     * @param index The color index (0-247)
     * @return The color, or transparent if out of range
     */
    public static Color getColor(int index) {
        if (index < 0 || index >= FULL_PALETTE.length) {
            return new Color(0, 0, 0, 0);
        }
        return FULL_PALETTE[index];
    }

    /**
     * Finds the nearest map color index for an RGB color.
     *
     * @param r Red component (0-255)
     * @param g Green component (0-255)
     * @param b Blue component (0-255)
     * @return The nearest color index (4-247, skipping transparent)
     */
    public static byte getNearestColorIndex(int r, int g, int b) {
        int rgb = (r << 16) | (g << 8) | b;
        
        // Check cache
        Byte cached = COLOR_CACHE.get(rgb);
        if (cached != null) {
            return cached;
        }

        int nearestIndex = 4; // Start from first non-transparent
        double nearestDistance = Double.MAX_VALUE;

        for (int i = 4; i < FULL_PALETTE.length; i++) {
            Color c = FULL_PALETTE[i];
            double distance = colorDistanceSquared(r, g, b, c.getRed(), c.getGreen(), c.getBlue());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = i;
            }
        }

        byte result = (byte) nearestIndex;
        
        // Cache the result (limit cache size)
        if (COLOR_CACHE.size() < 100000) {
            COLOR_CACHE.put(rgb, result);
        }

        return result;
    }

    /**
     * Finds the nearest map color index for a Color object.
     *
     * @param color The color to match
     * @return The nearest color index
     */
    public static byte getNearestColorIndex(Color color) {
        // Handle transparent colors
        if (color.getAlpha() < 128) {
            return 0; // Transparent
        }
        return getNearestColorIndex(color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * Calculates the squared distance between two colors.
     * Uses weighted Euclidean distance for better perceptual matching.
     */
    private static double colorDistanceSquared(int r1, int g1, int b1, int r2, int g2, int b2) {
        // Weighted distance (human eye is more sensitive to green)
        double rMean = (r1 + r2) / 2.0;
        double dr = r1 - r2;
        double dg = g1 - g2;
        double db = b1 - b2;

        // Use the CIEDE2000-inspired weighting
        double rWeight = 2 + rMean / 256.0;
        double gWeight = 4.0;
        double bWeight = 2 + (255 - rMean) / 256.0;

        return rWeight * dr * dr + gWeight * dg * dg + bWeight * db * db;
    }

    /**
     * Gets the number of colors in the palette.
     *
     * @return The palette size
     */
    public static int getPaletteSize() {
        return FULL_PALETTE.length;
    }

    /**
     * Checks if a color index is valid.
     *
     * @param index The color index
     * @return true if valid
     */
    public static boolean isValidIndex(int index) {
        return index >= 0 && index < FULL_PALETTE.length;
    }

    /**
     * Checks if a color index is transparent.
     *
     * @param index The color index
     * @return true if transparent (0-3)
     */
    public static boolean isTransparent(int index) {
        return index >= 0 && index < 4;
    }
}
