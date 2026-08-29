package com.example

import com.example.transfer.DownloadStatus
import com.example.transfer.DownloadTask
import com.example.transfer.TransferHub
import com.example.transfer.extractChapterTitle
import com.example.transfer.isAnimatedWebP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3 新特性：EPUB 章节标题、动图 WebP 检测、分P解析、画质 Cookie、队列持久化。 */
class FeatureLogicTest {

    // ===== EPUB 章节标题提取 =====

    @Test
    fun `extracts chapter title from title tag`() {
        val html = "<html><head><title>第三章 风起云涌</title></head><body><p>正文</p></body></html>"
        assertEquals("第三章 风起云涌", extractChapterTitle(html))
    }

    @Test
    fun `extracts chapter title from h1 fallback`() {
        val html = "<html><body><h1><span>初入江湖</span></h1><p>正文</p></body></html>"
        assertEquals("初入江湖", extractChapterTitle(html))
    }

    @Test
    fun `returns null when no title present`() {
        assertNull(extractChapterTitle("<html><body><p>只有正文</p></body></html>"))
    }

    // ===== 动图 WebP 检测 =====

    private fun webpHeader(extra: String): ByteArray {
        val header = ByteArray(12 + extra.length)
        "RIFF".toByteArray().copyInto(header, 0)
        "WEBP".toByteArray().copyInto(header, 8)
        extra.toByteArray().copyInto(header, 12)
        return header
    }

    @Test
    fun `detects animated webp by ANMF chunk`() {
        assertTrue(isAnimatedWebP(webpHeader("VP8X....ANMF....")))
    }

    @Test
    fun `static webp is not detected as animated`() {
        assertFalse(isAnimatedWebP(webpHeader("VP8 ....")))
    }

    @Test
    fun `non webp header is rejected`() {
        assertFalse(isAnimatedWebP("JPEG-JFIF-data-here".toByteArray()))
        assertFalse(isAnimatedWebP(ByteArray(10)))
    }

    // ===== B站分P列表解析 =====

    @Test
    fun `parses bilibili pages from view api json`() {
        val json = """
            {"code":0,"data":{"bvid":"BV1xx411c7m9","pages":[
                {"cid":100,"page":1,"part":"第一话 上"},
                {"cid":101,"page":2,"part":"第一话 下"}
            ]}}
        """.trimIndent()
        val pages = NetworkDownloader.parseBilibiliPages(json)
        assertEquals(2, pages.size)
        assertEquals(100L, pages[0].cid)
        assertEquals("第一话 上", pages[0].part)
        assertEquals(101L, pages[1].cid)
    }

    @Test
    fun `returns empty pages on error code or bad json`() {
        assertTrue(NetworkDownloader.parseBilibiliPages("""{"code":-404,"message":"啥都木有"}""").isEmpty())
        assertTrue(NetworkDownloader.parseBilibiliPages("not-json").isEmpty())
    }

    // ===== B站 Cookie 头（SESSDATA） =====

    @Test
    fun `cookie header includes sessdata when present`() {
        NetworkDownloader.updateVideoPreferences(quality = 80, sessdata = "abc123")
        val header = NetworkDownloader.biliCookieHeader("buvid-test")
        assertTrue(header.contains("buvid3=buvid-test"))
        assertTrue(header.contains("SESSDATA=abc123"))
    }

    @Test
    fun `cookie header omits sessdata when blank`() {
        NetworkDownloader.updateVideoPreferences(quality = 64, sessdata = "   ")
        val header = NetworkDownloader.biliCookieHeader("buvid-test")
        assertTrue(header.contains("buvid3=buvid-test"))
        assertFalse(header.contains("SESSDATA"))
    }

    // ===== 下载队列持久化 =====

    @Test
    fun `task list survives json round trip`() {
        val tasks = listOf(
            DownloadTask(1, "https://www.bilibili.com/video/BV1xx411c7m9", false, "video.mp4", "content://tree/folder", null),
            DownloadTask(2, "https://www.bilibili.com/video/BV1xx411c7m9", true, "audio.m4a", "content://tree/folder", 100),
            DownloadTask(3, "https://example.com/a.mp4", false, "a.mp4", "content://tree/folder", null, DownloadStatus.FAILED, 0.5f, "下载失败(已重试3次)")
        )
        val restored = TransferHub.deserializeTasks(TransferHub.serializeTasks(tasks))
        assertEquals(3, restored.size)
        assertEquals(tasks[0], restored[0])
        assertEquals(100L, restored[1].cid)
        assertEquals(DownloadStatus.FAILED, restored[2].status)
    }

    @Test
    fun `running tasks reset to waiting on restore`() {
        val tasks = listOf(
            DownloadTask(9, "https://example.com/b.mp4", false, "b.mp4", "content://tree/folder", null, DownloadStatus.RUNNING, 0.7f, "下载中")
        )
        val restored = TransferHub.deserializeTasks(TransferHub.serializeTasks(tasks))
        assertEquals(DownloadStatus.WAITING, restored[0].status)
        assertEquals(0f, restored[0].progress)
    }

    @Test
    fun `malformed json yields empty list`() {
        assertTrue(TransferHub.deserializeTasks("not-json").isEmpty())
        assertTrue(TransferHub.deserializeTasks("""[{"url":""}]""").isEmpty())
    }
}
