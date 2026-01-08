package com.example.finemaps.plugin.url;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Downloads URL content to disk with a hard max-bytes guard.
 */
public final class UrlDownloader {

    private UrlDownloader() {
    }

    public static DownloadResult downloadToFile(URL url, Path targetFile, int connectTimeoutMs, int readTimeoutMs, long maxBytes) throws IOException {
        if (url == null) throw new IllegalArgumentException("url is null");
        if (targetFile == null) throw new IllegalArgumentException("targetFile is null");
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must be >= 0");

        Files.createDirectories(targetFile.getParent());

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "FineMaps Plugin/1.0");

        try {
            long declaredLen = conn.getContentLengthLong();
            if (declaredLen > 0 && maxBytes > 0 && declaredLen > maxBytes) {
                throw new IOException("Remote file too large (Content-Length=" + declaredLen + " bytes, max=" + maxBytes + ")");
            }

            String contentType = conn.getContentType();
            if (contentType != null) {
                int semi = contentType.indexOf(';');
                if (semi >= 0) contentType = contentType.substring(0, semi);
                contentType = contentType.trim().toLowerCase(Locale.ROOT);
            }

            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(targetFile)) {
                byte[] buf = new byte[8192];
                long total = 0;
                int r;
                while ((r = in.read(buf)) != -1) {
                    total += r;
                    if (maxBytes > 0 && total > maxBytes) {
                        throw new IOException("Remote file exceeds max download size (read " + total + " bytes, max " + maxBytes + ")");
                    }
                    out.write(buf, 0, r);
                }
                out.flush();
                return new DownloadResult(total, contentType);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(targetFile);
                } catch (IOException ignored) {
                }
                throw e;
            }
        } finally {
            conn.disconnect();
        }
    }

    public static final class DownloadResult {
        public final long bytes;
        public final String contentType;

        public DownloadResult(long bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType;
        }
    }
}

