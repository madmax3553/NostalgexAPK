package io.github.madmax3553.nostalgex.channels

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the real channels.json from the repo root, the same file the tvOS app and
 * the web tuner use. Unit tests run with the module directory (android/app) as the
 * working directory, so the file is two levels up.
 */
class ChannelConfigTest {

    private val config: ChannelConfig by lazy {
        val file = File("../../channels.json")
        check(file.exists()) { "channels.json not found at ${file.absolutePath}" }
        ChannelConfigLoader.parse(file.readText())
    }

    @Test
    fun parsesSharedConfigShape() {
        assertEquals(37, config.version)
        assertEquals(131, config.channels.size)
        assertEquals(14, config.bundles.size)
        assertEquals(3, config.exclusiveRules.size)
    }

    @Test
    fun channelIdsAreUniqueAndStatic() {
        val ids = config.channels.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // ChannelIDSpaceTests in the Swift suite: config ids must stay below the runtime space.
        assertTrue(ids.all { it < DYNAMIC_CHANNEL_ID_BASE })
    }

    @Test
    fun exactlyOnePremiereChannel() {
        val premieres = config.channels.filter { it.premiere }.map { it.id }
        assertEquals(listOf(403), premieres)
    }

    @Test
    fun legacyChannelIdBecomesSingleItemList() {
        // The third exclusive rule in channels.json uses the legacy `channelID` key.
        val legacy = config.exclusiveRules.last()
        assertEquals(1, legacy.channelIDs.size)
    }

    @Test
    fun everyBundleChannelExists() {
        val ids = config.channels.map { it.id }.toSet()
        for (bundle in config.bundles) {
            for (id in bundle.channelIDs) {
                assertTrue("bundle ${bundle.id} references missing channel $id", id in ids)
            }
        }
    }

    @Test
    fun seasonalBundleKeepsActiveMonths() {
        val scream = config.bundles.first { it.id == "seasonal" }
        assertEquals(listOf(10), scream.activeMonths)
        assertFalse(config.bundles.first { it.id == "nostalgex" }.activeMonths != null)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val text = """{"version":1,"newField":true,"channels":[
            {"id":1,"number":1,"name":"X","colorHex":"#FFFFFF","futureThing":3}]}"""
        val parsed = ChannelConfigLoader.parse(text)
        assertEquals("X", parsed.channels.single().name)
    }
}
