package com.boucle.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class PolarDeviceTimeTest {
    @Test
    fun `heure en UTC a la seconde, relisible par le motif du SDK Polar`() {
        val now = OffsetDateTime.parse("2026-11-21T14:11:45.476364+01:00").toInstant()

        val text = polarDeviceTime(now)

        assertEquals("2026-11-21T13:11:45Z", text)
        // Motif utilisé par BDBleApiImpl.doFirstTimeUse (SDK 8.6.0).
        val sdkFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH)
        assertEquals(LocalDateTime.of(2026, 11, 21, 13, 11, 45), LocalDateTime.parse(text, sdkFormat))
    }
}
