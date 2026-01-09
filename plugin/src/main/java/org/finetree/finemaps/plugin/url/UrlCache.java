package org.finetree.finemaps.plugin.url;

import java.io.File;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Small helper for per-URL cache directories.
 */
public final class UrlCache {

    private UrlCache() {
    }

    public static Path cacheDirForUrl(File pluginDataFolder, String cacheFolderName, String url) {
        if (pluginDataFolder == null) throw new IllegalArgumentException("pluginDataFolder is null");
        if (cacheFolderName == null) cacheFolderName = "url-cache";
        String key = sha256Hex(url != null ? url : "");
        return pluginDataFolder.toPath().resolve(cacheFolderName).resolve(key);
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // Should never happen in a standard JVM
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}

