package com.example

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliMediaSelectionTest {
    @Test
    fun `prefers muxed durl for mp4`() {
        val data = JSONObject("""
            {"durl":[{"url":"https://cdn.example/video.mp4"}],"dash":{"video":[{"baseUrl":"https://cdn.example/video-only"}]}}
        """.trimIndent())

        val selection = NetworkDownloader.selectBilibiliMedia(data, isMp3 = false)

        assertEquals("https://cdn.example/video.mp4", selection.mediaUrl)
        assertFalse(selection.isAudioStreamDirect)
    }

    @Test
    fun `uses dash audio directly for mp3 even when durl exists`() {
        val data = JSONObject("""
            {"durl":[{"url":"https://cdn.example/video.mp4"}],"dash":{"audio":[{"baseUrl":"","backupUrl":["https://cdn.example/audio.m4a"]}]}}
        """.trimIndent())

        val selection = NetworkDownloader.selectBilibiliMedia(data, isMp3 = true)

        assertEquals("https://cdn.example/audio.m4a", selection.mediaUrl)
        assertTrue(selection.isAudioStreamDirect)
    }

    @Test
    fun `rejects non-http media urls`() {
        val data = JSONObject("""
            {"durl":[{"url":"javascript:bad"}],"dash":{"video":[{"baseUrl":"file:///bad"}]}}
        """.trimIndent())

        val selection = NetworkDownloader.selectBilibiliMedia(data, isMp3 = false)

        assertEquals(null, selection.mediaUrl)
    }
}
