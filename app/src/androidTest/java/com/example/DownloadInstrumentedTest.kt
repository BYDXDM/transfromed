package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.transfer.doVideoDownloadWithRetry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机端到端下载回归测试（对应 v1.4.1 修复）：
 * B站 官方解析链路（spi buvid3 → view API → playurl durl → CDN 下载 → 落盘）。
 * 走真实网络，验证解析与下载在设备网络环境下可用；选用 360P 短视频控制流量。
 */
@RunWith(AndroidJUnit4::class)
class DownloadInstrumentedTest {

    @Test
    fun bilibiliVideoDownloadsEndToEnd(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 360P 未登录即可播放的官方 MV，体积可控
        NetworkDownloader.updateVideoPreferences(16, "")
        val output = File(context.cacheDir, "it_bili_out.mp4")
        output.delete()

        var lastMsg = ""
        val ok = doVideoDownloadWithRetry(
            context = context,
            url = "https://www.bilibili.com/video/BV1GJ411x7h7/",
            outputUri = android.net.Uri.fromFile(output),
            isAudio = false,
            onProgress = { _, msg -> lastMsg = msg }
        )

        assertTrue("B站端到端下载应成功（最后状态: $lastMsg）", ok)
        assertTrue("输出文件应存在且非空", output.exists() && output.length() > 100_000)
        // MP4 文件头校验（ftyp box）
        val head = output.inputStream().use { ins -> ByteArray(12).also { ins.read(it) } }
        val ftyp = String(head, 4, 4, Charsets.US_ASCII)
        assertTrue("输出应是合法 MP4 容器（ftyp）, head=${head.toList()}", ftyp == "ftyp")
        output.delete()
    }
}
