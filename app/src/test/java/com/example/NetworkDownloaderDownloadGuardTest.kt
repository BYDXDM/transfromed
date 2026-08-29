package com.example

import android.content.Context
import android.net.Uri
import com.example.utils.AppLogger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

/**
 * 下载链路 URL 安全校验的接线测试（回归防护）：
 * 证明 downloadVideo / downloadStreamAndSave 在发起任何网络请求与任何
 * Android 平台调用之前，会先拒绝非 http(s) 或指向本机/内网/保留地址的链接。
 *
 * 实现方式：AppLogger.disableLogging() 使所有日志调用直接返回，
 * 因此在纯 JVM 环境即可执行；Context / Uri 为 Mockito mock，
 * 通过 verifyNoInteractions 证明被测路径未触碰 Android 平台。
 */
class NetworkDownloaderDownloadGuardTest {

    private val context: Context = mock(Context::class.java)
    private val outputUri: Uri = mock(Uri::class.java)

    @Before
    fun quietLogs() {
        AppLogger.disableLogging()
    }

    @After
    fun restoreLogs() {
        AppLogger.enableLogging()
    }

    private class Recorder {
        val pcts = mutableListOf<Float>()
        val messages = mutableListOf<String>()
        val onProgress: (Float, String) -> Unit = { pct, msg ->
            pcts.add(pct)
            messages.add(msg)
        }
    }

    @Test
    fun `downloadVideo rejects loopback and private hosts before any request`() = runBlocking {
        val blockedUrls = listOf(
            "http://127.0.0.1:8080/video.mp4",
            "http://localhost/x.mp4",
            "http://sub.localhost/x.mp4",
            "http://192.168.1.10/video.mp4",
            "http://10.0.0.5/a.mp4",
            "http://172.16.3.9/a.mp4",
            "http://172.31.255.1/a.mp4",
            "http://169.254.10.10/a.mp4",
            "http://100.64.0.1/a.mp4",
            "http://0.0.0.0/a.mp4"
        )
        for (url in blockedUrls) {
            val rec = Recorder()
            val ok = NetworkDownloader.downloadVideo(context, url, outputUri, isMp3 = false, onProgress = rec.onProgress)
            assertFalse("应拒绝 $url", ok)
            assertTrue(
                "应给出安全错误提示: $url, 实际=${rec.messages}",
                rec.messages.any { it.contains("不安全") }
            )
            assertTrue(
                "拒绝时进度应回到 0: $url",
                rec.pcts.lastOrNull() == 0f
            )
        }
    }

    @Test
    fun `downloadVideo rejects non-http schemes`() = runBlocking {
        val blockedUrls = listOf(
            "file:///data/local/tmp/a.mp4",
            "gopher://127.0.0.1/x",
            "javascript:alert(1)"
        )
        for (url in blockedUrls) {
            val rec = Recorder()
            val ok = NetworkDownloader.downloadVideo(context, url, outputUri, isMp3 = false, onProgress = rec.onProgress)
            assertFalse("应拒绝 $url", ok)
            assertTrue(
                "应给出安全错误提示: $url, 实际=${rec.messages}",
                rec.messages.any { it.contains("不安全") }
            )
        }
    }

    @Test
    fun `guard runs before platform routing for bilibili-style url on private host`() = runBlocking {
        // 该链接包含 BV 号，若门禁缺失会进入 B 站解析流程并发起网络请求；
        // 现在必须在任何平台调用/网络请求之前被拒绝。
        val rec = Recorder()
        val ok = NetworkDownloader.downloadVideo(
            context,
            "http://127.0.0.1/video/BV1xx411c7m9",
            outputUri,
            isMp3 = true,
            onProgress = rec.onProgress
        )
        assertFalse(ok)
        assertTrue(rec.messages.any { it.contains("不安全") })
        verifyNoInteractions(context)
        verifyNoInteractions(outputUri)
    }

    @Test
    fun `downloadStreamAndSave rejects non-public media url before request`() = runBlocking {
        val rec = Recorder()
        val ok = NetworkDownloader.downloadStreamAndSave(
            context = context,
            mediaUrl = "http://192.168.0.5/media.mp4",
            referer = "https://www.bilibili.com/",
            outputUri = outputUri,
            isMp3 = false,
            isAudioStreamDirect = false,
            onProgress = rec.onProgress
        )
        assertFalse(ok)
        assertTrue(
            "应给出媒体地址不安全提示, 实际=${rec.messages}",
            rec.messages.any { it.contains("不安全") }
        )
        verifyNoInteractions(context)
    }
}
