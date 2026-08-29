package com.example

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三方解析接口白名单（B站音频兜底换源）的纯逻辑测试：
 * 默认列表形状、启用过滤、模板占位符替换。
 */
class ParseEndpointConfigTest {

    @After
    fun restoreDefaults() {
        NetworkDownloader.updateParseEndpoints(NetworkDownloader.defaultParseEndpoints())
    }

    @Test
    fun `default endpoints are https templates with placeholders`() {
        val defaults = NetworkDownloader.defaultParseEndpoints()
        assertTrue("应存在内置默认接口", defaults.isNotEmpty())
        defaults.forEach { ep ->
            assertTrue("模板必须为 https: ${ep.template}", ep.template.startsWith("https://"))
            assertTrue("名称不应为空: ${ep.template}", ep.name.isNotBlank())
            assertTrue(
                "模板应包含 {url} 或 {bvid} 占位符: ${ep.template}",
                ep.template.contains("{url}") || ep.template.contains("{bvid}")
            )
            assertTrue("默认接口应处于启用状态", ep.enabled)
        }
    }

    @Test
    fun `updateParseEndpoints keeps only enabled templates`() {
        val defaults = NetworkDownloader.defaultParseEndpoints()
        NetworkDownloader.updateParseEndpoints(defaults)
        assertEquals(defaults.size, NetworkDownloader.enabledParseApiTemplates().size)

        val withDisabled = defaults.mapIndexed { i, ep ->
            if (i == 0) ep.copy(enabled = false) else ep
        }
        NetworkDownloader.updateParseEndpoints(withDisabled)
        val templates = NetworkDownloader.enabledParseApiTemplates()
        assertEquals(defaults.size - 1, templates.size)
        assertFalse("禁用的接口不应参与轮询", templates.contains(withDisabled[0].template))
        assertTrue(templates.contains(withDisabled[1].template))
    }

    @Test
    fun `buildParseApiUrl replaces placeholders`() {
        assertEquals(
            "https://api.injahow.cn/bparse/?bv=BV1xx411c7m9&format=mp4",
            NetworkDownloader.buildParseApiUrl(
                "https://api.injahow.cn/bparse/?bv={bvid}&format=mp4",
                "BV1xx411c7m9",
                "https://www.bilibili.com/video/BV1xx411c7m9"
            )
        )
        assertEquals(
            "https://api.vvhan.com/api/bilibili?url=https://www.bilibili.com/video/BV1xx411c7m9",
            NetworkDownloader.buildParseApiUrl(
                "https://api.vvhan.com/api/bilibili?url={url}",
                "BV1xx411c7m9",
                "https://www.bilibili.com/video/BV1xx411c7m9"
            )
        )
        // 未识别出 bvid 时占位符替换为空串，与既有硬编码行为保持一致
        assertEquals(
            "https://api.injahow.cn/bparse/?bv=&format=mp4",
            NetworkDownloader.buildParseApiUrl(
                "https://api.injahow.cn/bparse/?bv={bvid}&format=mp4",
                null,
                "https://www.bilibili.com/video/x"
            )
        )
    }
}
