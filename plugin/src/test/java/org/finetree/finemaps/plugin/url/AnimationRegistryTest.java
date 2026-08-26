package org.finetree.finemaps.plugin.url;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnimationRegistryTest {
    @Test
    void computesSmallestRectangularPatch() {
        byte[] previous = new byte[128 * 128];
        byte[] current = previous.clone();
        current[20 * 128 + 10] = 1;
        current[22 * 128 + 12] = 2;

        AnimationRegistry.MapPatch patch = AnimationRegistry.MapPatch.between(previous, current);

        assertNotNull(patch);
        assertEquals(10, patch.startX());
        assertEquals(20, patch.startY());
        assertEquals(3, patch.width());
        assertEquals(3, patch.height());
        assertArrayEquals(new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 2}, patch.pixels());
    }

    @Test
    void suppressesUnchangedFrames() {
        byte[] frame = new byte[128 * 128];
        AnimationRegistry.MapPatch patch = AnimationRegistry.MapPatch.between(frame, frame.clone());
        assertNotNull(patch);
        assertTrue(patch.isEmpty());
    }

    @Test
    void requestsFullUpdateWithoutValidBaseline() {
        assertNull(AnimationRegistry.MapPatch.between(null, new byte[128 * 128]));
        assertNull(AnimationRegistry.MapPatch.between(new byte[4], new byte[128 * 128]));
    }

    @Test
    void bandwidthLimiterRejectsPayloadAboveBurstCeiling() {
        AnimationRegistry.BandwidthLimiter limiter = new AnimationRegistry.BandwidthLimiter(1024, 2048);
        assertFalse(limiter.tryConsume(4096));
        assertTrue(limiter.tryConsume(2048));
        assertFalse(limiter.tryConsume(4096));
    }

    @Test
    void separatesDistantDirtyRegionsWhenCheaperThanBoundingBox() {
        byte[] previous = new byte[128 * 128];
        byte[] current = previous.clone();
        current[0] = 1;
        current[127 * 128 + 127] = 2;

        AnimationRegistry.PatchPlan plan = AnimationRegistry.PatchPlan.between(previous, current);

        assertFalse(plan.full());
        assertEquals(2, plan.patches().size());
        assertEquals(512, plan.payloadBytes());
    }

    @Test
    void selectsFullTileForChangesAcrossEveryDirtyBlock() {
        byte[] previous = new byte[128 * 128];
        byte[] current = new byte[128 * 128];
        java.util.Arrays.fill(current, (byte) 1);

        AnimationRegistry.PatchPlan plan = AnimationRegistry.PatchPlan.between(previous, current);

        assertTrue(plan.full());
        assertEquals(128 * 128, plan.payloadBytes());
    }

    @Test
    void adaptiveQualityUsesMotionAndPressureRatherThanMapCount() {
        byte[] previous = new byte[128 * 128];
        byte[] lowMotion = previous.clone();
        lowMotion[0] = 1;
        byte[] highMotion = new byte[128 * 128];
        java.util.Arrays.fill(highMotion, (byte) 1);

        assertEquals(128, AnimationRegistry.AdaptiveQuality.effectiveResolution(
            previous, lowMotion, 1.0, Long.MAX_VALUE));
        assertEquals(64, AnimationRegistry.AdaptiveQuality.effectiveResolution(
            previous, highMotion, 1.0, Long.MAX_VALUE));
        assertEquals(32, AnimationRegistry.AdaptiveQuality.effectiveResolution(
            previous, lowMotion, 0.05, Long.MAX_VALUE));
    }

    @Test
    void reducedResolutionRepeatsPalettePixelsDeterministically() {
        byte[] pixels = new byte[128 * 128];
        for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) i;

        byte[] first = AnimationRegistry.AdaptiveQuality.reduceResolution(pixels, 64);
        byte[] second = AnimationRegistry.AdaptiveQuality.reduceResolution(pixels, 64);

        assertArrayEquals(first, second);
        assertEquals(first[0], first[1]);
        assertEquals(first[128], first[129]);
    }

    @Test
    void contentCacheReusesEqualTilesAndEvictsLeastRecentlyUsed() {
        AnimationRegistry.TileContentCache cache = new AnimationRegistry.TileContentCache(2);
        byte[] first = new byte[] {1};
        byte[] duplicate = new byte[] {1};
        byte[] second = new byte[] {2};
        byte[] third = new byte[] {3};

        assertSame(first, cache.canonicalize(first));
        assertSame(first, cache.canonicalize(duplicate));
        cache.canonicalize(second);
        cache.canonicalize(third);

        assertEquals(2, cache.size());
        assertSame(duplicate, cache.canonicalize(duplicate));
    }
}
