package com.example

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliMp4GuardTest {
    @Test
    fun `accepts video or application mp4 response for MP4 download`() {
        assertTrue(NetworkDownloader.isExpectedMp4ContentType("video/mp4"))
        assertTrue(NetworkDownloader.isExpectedMp4ContentType("application/octet-stream"))
        assertTrue(NetworkDownloader.isExpectedMp4ContentType(null))
    }

    @Test
    fun `rejects audio m4a response for MP4 download`() {
        assertFalse(NetworkDownloader.isExpectedMp4ContentType("audio/mp4"))
        assertFalse(NetworkDownloader.isExpectedMp4ContentType("audio/mp4; charset=binary"))
        assertFalse(NetworkDownloader.isExpectedMp4ContentType("audio/mpeg"))
    }
}
