package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NetworkDownloader 的请求前置安全校验（isPublicHttpUrl）与链接提纯（sanitizeUrlInput）测试。
 * 仅使用 IP 字面量与本机可解析主机名，避免单元测试依赖真实 DNS。
 */
class NetworkDownloaderUrlGuardTest {

    // ===== isPublicHttpUrl：仅允许公网 http/https =====

    @Test
    fun `rejects non http schemes and malformed urls`() {
        assertFalse(NetworkDownloader.isPublicHttpUrl("ftp://example.com/file"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("file:///etc/passwd"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("javascript:alert(1)"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("not a url"))
        assertFalse(NetworkDownloader.isPublicHttpUrl(""))
    }

    @Test
    fun `rejects localhost and loopback`() {
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://localhost:8080/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://sub.localhost/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://127.0.0.1/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://127.1.2.3/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://[::1]/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://0.0.0.0/x"))
    }

    @Test
    fun `rejects private link-local and reserved ranges`() {
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://10.1.2.3/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://192.168.1.1/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://172.16.0.9/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://172.20.0.5/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://172.31.255.1/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://169.254.10.10/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://100.64.0.1/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://[fc00::1]/x"))
        assertFalse(NetworkDownloader.isPublicHttpUrl("http://[fe80::1]/x"))
    }

    @Test
    fun `accepts public http urls`() {
        assertTrue(NetworkDownloader.isPublicHttpUrl("https://8.8.8.8/x"))
        assertTrue(NetworkDownloader.isPublicHttpUrl("http://172.32.0.1/x")) // 172.16/12 之外视为公网
        assertTrue(NetworkDownloader.isPublicHttpUrl("https://11.0.0.1/x"))
    }

    // ===== sanitizeUrlInput：链接提纯 =====

    @Test
    fun `extracts url from share text`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7m9",
            NetworkDownloader.sanitizeUrlInput("快看这个视频 https://www.bilibili.com/video/BV1xx411c7m9 超好看！")
        )
    }

    @Test
    fun `builds url from bare bvid`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7m9",
            NetworkDownloader.sanitizeUrlInput("BV1xx411c7m9")
        )
    }

    @Test
    fun `av number requires word boundary`() {
        assertEquals(
            "https://www.bilibili.com/video/av170001",
            NetworkDownloader.sanitizeUrlInput("av170001")
        )
        // "sav170001" 中的 av 不是独立词，不应被误判为 av 号
        assertEquals("sav170001", NetworkDownloader.sanitizeUrlInput("sav170001"))
    }
}
