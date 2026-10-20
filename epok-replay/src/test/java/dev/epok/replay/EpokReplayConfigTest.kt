package dev.epok.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EpokReplayConfigTest {
    @Test fun defaultsToTheEpokIngestReplayRoute() {
        assertEquals("https://ingest.getepok.dev/v1/rum/mobile/android", EpokReplayConfig("epk_x").host)
    }
    @Test fun trailingSlashIsNormalised() {
        assertEquals("http://10.0.2.2:4320/v1/rum/mobile/android", EpokReplayConfig("epk_x", "http://10.0.2.2:4320/").host)
    }
    @Test fun rejectsBlankKeysAndNonHttpEndpoints() {
        assertThrows(IllegalArgumentException::class.java) { EpokReplayConfig(" ") }
        assertThrows(IllegalArgumentException::class.java) { EpokReplayConfig("epk_x", "ftp://x") }
    }
}
