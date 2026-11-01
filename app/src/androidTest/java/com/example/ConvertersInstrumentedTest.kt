package com.example

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.transfer.convertWebpToJpg
import com.example.transfer.isAnimatedWebP
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 真机 WebP→JPG 回归测试（对应 v1.4.1 修复）：
 * - 动态 WebP：旧实现 BitmapFactory 在 Android 12 上解码返回 null 导致"完全用不了"，
 *   新实现 ImageDecoder 取首帧，必须转换成功
 * - 带 alpha 的 WebP：透明区域必须铺白底而不是变黑
 * - 普通有损 WebP 正常转换；损坏文件优雅失败
 * 输入文件从 androidTest assets 拷贝到应用缓存目录，无外部依赖。
 */
@RunWith(AndroidJUnit4::class)
class ConvertersInstrumentedTest {

    // 目标应用上下文：转换 I/O 在该应用身份下执行
    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    // 插桩 APK 上下文：androidTest assets 打包在测试 APK 里，必须用它读取
    private val testAssets get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets

    private fun assetToCache(name: String): Pair<Uri, Uri> {
        val input = File(context.cacheDir, name)
        testAssets.open(name).use { ins -> input.outputStream().use { ins.copyTo(it) } }
        val output = File(context.cacheDir, name.substringBeforeLast('.') + "_out.jpg")
        return Uri.fromFile(input) to Uri.fromFile(output)
    }

    /** 动态 WebP（微信表情包同款）：必须转换成功且保留首帧红色 */
    @Test
    fun animatedWebpConvertsToFirstFrame() = runBlocking {
        val (input, output) = assetToCache("anim_sticker.webp")
        val header = input.path?.let { p -> File(p).readBytes().copyOf(48) }!!
        assertTrue("测试资源应是动图", isAnimatedWebP(header))

        val ok = convertWebpToJpg(context, input, output)
        assertTrue("动态 WebP 应转换成功（旧版在 Android 12 失败）", ok)

        val out = BitmapFactory.decodeFile(output.path!!)
        assertNotNull("输出应能被解码", out)
        assertEquals("输出尺寸与首帧一致", 120, out.width)
        val px = out.getPixel(60, 60)
        assertTrue("首帧应为红色系（取首帧）", (px shr 16 and 0xFF) > 180 && (px and 0xFF) < 120)
    }

    /** 半透明 WebP：透明区域铺白底，不能变黑 */
    @Test
    fun alphaWebpConvertsWithWhiteMatte() = runBlocking {
        val (input, output) = assetToCache("still_alpha.webp")
        val ok = convertWebpToJpg(context, input, output)
        assertTrue("带 alpha 的 WebP 应转换成功", ok)

        val out = BitmapFactory.decodeFile(output.path!!)
        assertNotNull(out)
        assertEquals(300, out.width)
        val px = out.getPixel(5, 5)
        // 源色 (30,144,255,160) 铺白底后 ≈ (114,185,255)；旧实现透明区变黑 (0,0,0) 附近
        assertEquals("R 通道应约为 114（白底合成）", 114f, (px shr 16 and 0xFF).toFloat(), 15f)
        assertEquals("G 通道应约为 185（白底合成）", 185f, (px shr 8 and 0xFF).toFloat(), 15f)
        assertTrue("B 通道应接近 255", (px and 0xFF) > 230)
    }

    /** 普通有损 WebP：正常转换且尺寸不变 */
    @Test
    fun lossyWebpConverts() = runBlocking {
        val (input, output) = assetToCache("photo.webp")
        val ok = convertWebpToJpg(context, input, output)
        assertTrue("普通 WebP 应转换成功", ok)
        val out = BitmapFactory.decodeFile(output.path!!)
        assertNotNull(out)
        assertEquals("输出尺寸应保持", 800, out.width)
    }

    /** 损坏文件：优雅失败而非崩溃 */
    @Test
    fun garbageFileFailsGracefully() = runBlocking {
        val bad = File(context.cacheDir, "garbage.webp")
        bad.writeBytes("this is not an image at all".toByteArray())
        val output = File(context.cacheDir, "garbage_out.jpg")
        val ok = convertWebpToJpg(context, Uri.fromFile(bad), Uri.fromFile(output))
        assertFalse("损坏文件应失败而不是崩溃", ok)
    }

    /** isAnimatedWebP 纯逻辑：ANIM/ANMF chunk 识别 */
    @Test
    fun animatedHeaderDetection() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // RIFF....WEBPVP8X 头部携带 ANIM chunk
        val anim = hex("52494646240000005745425056503858200A00000010000000" + "414E494D".repeat(4))
        assertTrue(anim.size >= 16 && isAnimatedWebP(anim))
        val still = hex("5249464624000000574542505650384C")
        assertFalse(isAnimatedWebP(still))
    }
}
