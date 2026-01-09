package org.finetree.finemaps.plugin.url;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort video decoder for URL imports.
 *
 * We intentionally do not ship a native decoder; instead we use ffmpeg if available on the host.
 */
public final class VideoDecoder {

    private VideoDecoder() {
    }

    public static AnimatedImage decode(Path file,
                                       String hintFromUrl,
                                       int fps,
                                       int maxFrames,
                                       String ffmpegPath) throws IOException {
        String hint = hintFromUrl != null ? hintFromUrl.toLowerCase(Locale.ROOT) : "";
        String name = file != null ? file.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        String fmt = name.endsWith(".webm") || hint.endsWith(".webm") ? "webm" : "mp4";

        int effectiveFps = fps > 0 ? fps : 20;
        int effectiveMaxFrames = maxFrames > 0 ? maxFrames : 300;
        String bin = (ffmpegPath != null && !ffmpegPath.isBlank()) ? ffmpegPath : "ffmpeg";

        Path tmpDir = Files.createTempDirectory("finemaps-video-");
        Path outDir = tmpDir.resolve("frames");
        Files.createDirectories(outDir);

        try {
            // Extract frames at the requested FPS, capped to maxFrames.
            // ffmpeg output pattern starts at 1 by default.
            Path pattern = outDir.resolve("frame_%05d.png");

            List<String> cmd = new ArrayList<>();
            cmd.add(bin);
            cmd.add("-hide_banner");
            cmd.add("-loglevel");
            cmd.add("error");
            cmd.add("-i");
            cmd.add(file.toAbsolutePath().toString());
            cmd.add("-an");
            cmd.add("-vf");
            cmd.add("fps=" + effectiveFps);
            cmd.add("-frames:v");
            cmd.add(Integer.toString(effectiveMaxFrames));
            cmd.add(pattern.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p;
            try {
                p = pb.start();
            } catch (IOException e) {
                throw new IOException("Failed to start ffmpeg ('" + bin + "'). Install ffmpeg or set images.ffmpeg-path in config.yml.", e);
            }

            String out = readAll(p.getInputStream());
            boolean finished;
            try {
                finished = p.waitFor(60, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while decoding video", ie);
            }
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("ffmpeg timed out while decoding video");
            }
            if (p.exitValue() != 0) {
                String msg = out != null && !out.isBlank() ? out.trim() : "ffmpeg exited with code " + p.exitValue();
                throw new IOException("ffmpeg failed to decode video: " + msg);
            }

            List<Path> frameFiles = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(outDir, "frame_*.png")) {
                for (Path f : ds) {
                    if (Files.isRegularFile(f)) frameFiles.add(f);
                }
            }
            frameFiles.sort(Comparator.comparing(pth -> pth.getFileName().toString()));

            if (frameFiles.isEmpty()) {
                throw new IOException("No frames were extracted from the video");
            }

            List<BufferedImage> frames = new ArrayList<>(frameFiles.size());
            for (Path f : frameFiles) {
                BufferedImage img = ImageIO.read(f.toFile());
                if (img != null) frames.add(toArgb(img));
            }
            if (frames.isEmpty()) {
                throw new IOException("Extracted frames could not be decoded as images");
            }

            return new AnimatedImage(frames, fmt);
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src == null) return null;
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) >= 0) {
            baos.write(buf, 0, r);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static void deleteRecursive(Path root) {
        if (root == null) return;
        try {
            if (!Files.exists(root)) return;
            Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
        } catch (IOException ignored) {
        }
    }
}

