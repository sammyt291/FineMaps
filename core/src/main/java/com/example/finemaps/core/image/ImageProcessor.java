package com.example.finemaps.core.image;

import com.example.finemaps.api.map.MapData;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Processes images into map-compatible pixel data.
 */
public class ImageProcessor {

    private static final int MAP_SIZE = MapData.MAP_WIDTH;
    private final ExecutorService executor;
    private final int connectionTimeout;
    private final int readTimeout;
    private final int maxImageSize;

    public ImageProcessor(int connectionTimeout, int readTimeout, int maxImageSize) {
        this.executor = Executors.newFixedThreadPool(2);
        this.connectionTimeout = connectionTimeout;
        this.readTimeout = readTimeout;
        this.maxImageSize = maxImageSize;
    }

    /**
     * Downloads and processes an image from a URL.
     *
     * @param url The image URL
     * @param widthBlocks Width in map blocks
     * @param heightBlocks Height in map blocks
     * @param dither Whether to apply dithering
     * @return CompletableFuture containing the processed pixel arrays
     */
    public CompletableFuture<byte[][]> processFromUrl(String url, int widthBlocks, int heightBlocks, boolean dither) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BufferedImage image = downloadImage(url);
                return processImage(image, widthBlocks, heightBlocks, dither);
            } catch (IOException e) {
                throw new RuntimeException("Failed to download or process image", e);
            }
        }, executor);
    }

    /**
     * Processes a BufferedImage into map pixel data.
     *
     * @param image The source image
     * @param widthBlocks Width in map blocks
     * @param heightBlocks Height in map blocks
     * @param dither Whether to apply dithering
     * @return Array of pixel arrays, one per map block [row][column]
     */
    public byte[][] processImage(BufferedImage image, int widthBlocks, int heightBlocks, boolean dither) {
        // Calculate total size and resize
        int totalWidth = widthBlocks * MAP_SIZE;
        int totalHeight = heightBlocks * MAP_SIZE;
        
        BufferedImage resized = resizeImage(image, totalWidth, totalHeight);
        
        // Apply dithering to the whole image if requested
        if (dither) {
            resized = applyFloydSteinbergDither(resized);
        }
        
        // Split into individual maps
        byte[][] result = new byte[widthBlocks * heightBlocks][];
        
        for (int y = 0; y < heightBlocks; y++) {
            for (int x = 0; x < widthBlocks; x++) {
                int index = y * widthBlocks + x;
                result[index] = extractMapPixels(resized, x * MAP_SIZE, y * MAP_SIZE);
            }
        }
        
        return result;
    }

    /**
     * Processes a single map from an image.
     *
     * @param image The source image
     * @param dither Whether to apply dithering
     * @return The pixel data for a single map
     */
    public byte[] processSingleMap(BufferedImage image, boolean dither) {
        BufferedImage resized = resizeImage(image, MAP_SIZE, MAP_SIZE);
        
        if (dither) {
            resized = applyFloydSteinbergDither(resized);
        }
        
        return extractMapPixels(resized, 0, 0);
    }

    /**
     * Downloads an image from a URL.
     */
    private BufferedImage downloadImage(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectionTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setRequestProperty("User-Agent", "FineMaps Plugin/1.0");
        
        try (InputStream in = conn.getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IOException("Could not decode image from URL");
            }
            
            // Check size limit
            if (image.getWidth() > maxImageSize || image.getHeight() > maxImageSize) {
                throw new IOException("Image exceeds maximum size: " + maxImageSize);
            }
            
            return image;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Resizes an image to the target dimensions.
     */
    private BufferedImage resizeImage(BufferedImage original, int targetWidth, int targetHeight) {
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        
        return resized;
    }

    /**
     * Applies Floyd-Steinberg dithering to reduce color banding.
     */
    private BufferedImage applyFloydSteinbergDither(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        // Work with floating point for error diffusion
        float[][] errR = new float[height][width];
        float[][] errG = new float[height][width];
        float[][] errB = new float[height][width];
        
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int alpha = (rgb >> 24) & 0xFF;
                
                // Skip transparent pixels
                if (alpha < 128) {
                    result.setRGB(x, y, 0);
                    continue;
                }
                
                // Get original color + accumulated error
                float r = ((rgb >> 16) & 0xFF) + errR[y][x];
                float g = ((rgb >> 8) & 0xFF) + errG[y][x];
                float b = (rgb & 0xFF) + errB[y][x];
                
                // Clamp to valid range
                r = Math.max(0, Math.min(255, r));
                g = Math.max(0, Math.min(255, g));
                b = Math.max(0, Math.min(255, b));
                
                // Find nearest palette color
                byte colorIndex = MapColors.getNearestColorIndex((int) r, (int) g, (int) b);
                Color nearest = MapColors.getColor(colorIndex & 0xFF);
                
                // Set the result pixel
                result.setRGB(x, y, (alpha << 24) | (nearest.getRed() << 16) | 
                             (nearest.getGreen() << 8) | nearest.getBlue());
                
                // Calculate error
                float errRVal = r - nearest.getRed();
                float errGVal = g - nearest.getGreen();
                float errBVal = b - nearest.getBlue();
                
                // Distribute error to neighboring pixels (Floyd-Steinberg pattern)
                distributeError(errR, errG, errB, x, y, width, height, 
                               errRVal, errGVal, errBVal);
            }
        }
        
        return result;
    }

    /**
     * Distributes quantization error to neighboring pixels.
     */
    private void distributeError(float[][] errR, float[][] errG, float[][] errB,
                                  int x, int y, int width, int height,
                                  float eR, float eG, float eB) {
        // Floyd-Steinberg error distribution pattern:
        //       X   7/16
        // 3/16 5/16 1/16
        
        if (x + 1 < width) {
            errR[y][x + 1] += eR * 7 / 16f;
            errG[y][x + 1] += eG * 7 / 16f;
            errB[y][x + 1] += eB * 7 / 16f;
        }
        
        if (y + 1 < height) {
            if (x > 0) {
                errR[y + 1][x - 1] += eR * 3 / 16f;
                errG[y + 1][x - 1] += eG * 3 / 16f;
                errB[y + 1][x - 1] += eB * 3 / 16f;
            }
            
            errR[y + 1][x] += eR * 5 / 16f;
            errG[y + 1][x] += eG * 5 / 16f;
            errB[y + 1][x] += eB * 5 / 16f;
            
            if (x + 1 < width) {
                errR[y + 1][x + 1] += eR * 1 / 16f;
                errG[y + 1][x + 1] += eG * 1 / 16f;
                errB[y + 1][x + 1] += eB * 1 / 16f;
            }
        }
    }

    /**
     * Extracts map pixel data from a region of an image.
     */
    private byte[] extractMapPixels(BufferedImage image, int startX, int startY) {
        byte[] pixels = new byte[MAP_SIZE * MAP_SIZE];
        
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                int rgb = image.getRGB(startX + x, startY + y);
                int alpha = (rgb >> 24) & 0xFF;
                
                if (alpha < 128) {
                    pixels[y * MAP_SIZE + x] = 0; // Transparent
                } else {
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    pixels[y * MAP_SIZE + x] = MapColors.getNearestColorIndex(r, g, b);
                }
            }
        }
        
        return pixels;
    }

    /**
     * Converts pixel data back to a BufferedImage.
     *
     * @param pixels The pixel data
     * @return A BufferedImage representation
     */
    public BufferedImage pixelsToImage(byte[] pixels) {
        BufferedImage image = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                int colorIndex = pixels[y * MAP_SIZE + x] & 0xFF;
                Color color = MapColors.getColor(colorIndex);
                image.setRGB(x, y, color.getRGB());
            }
        }
        
        return image;
    }

    /**
     * Shuts down the executor service.
     */
    public void shutdown() {
        executor.shutdown();
    }
}
