package org.finetree.finemaps.core.nms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NMSAdapterFactoryTest {

    @Test
    void parsesStandardMinecraftVersions() {
        assertArrayEquals(new int[] {21, 4}, NMSAdapterFactory.parseMinecraftVersion("1.21.4"));
        assertArrayEquals(new int[] {21, 0}, NMSAdapterFactory.parseMinecraftVersion("1.21-R0.1-SNAPSHOT"));
    }

    @Test
    void extractsMinecraftVersionFromServerDescription() {
        assertArrayEquals(new int[] {21, 11},
            NMSAdapterFactory.parseMinecraftVersion("Paper build 116 (MC: 1.21.11)"));
    }

    @Test
    void parsesCalendarMinecraftVersions() {
        assertArrayEquals(new int[] {26, 2}, NMSAdapterFactory.parseMinecraftVersion("26.2"));
        assertArrayEquals(new int[] {26, 2},
            NMSAdapterFactory.parseMinecraftVersion("Paper 26.2-116 (MC: 26.2)"));
    }

    @Test
    void rejectsUnrelatedVersionNumbers() {
        assertNull(NMSAdapterFactory.parseMinecraftVersion("Paper build 116"));
    }

    @Test
    void identifiesFoliaByServerIdentityOnly() {
        assertTrue(NMSAdapterFactory.isFoliaServer("Folia"));
        assertTrue(NMSAdapterFactory.isFoliaServer("git-Folia-42 (MC: 26.2)"));
        assertFalse(NMSAdapterFactory.isFoliaServer("Paper"));
        assertFalse(NMSAdapterFactory.isFoliaServer("Paper 26.2-116 (MC: 26.2)"));
    }
}
