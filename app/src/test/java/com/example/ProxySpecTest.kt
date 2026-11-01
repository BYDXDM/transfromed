package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 下载代理解析的回归测试：设置面板保存代理前与 NetworkDownloader 更新代理
 * 时都会用到 parseProxySpec，必须对脏输入保持稳健。
 */
class ProxySpecTest {

    @Test
    fun `parses plain host port`() {
        assertEquals("192.168.1.5" to 7890, NetworkDownloader.parseProxySpec("192.168.1.5:7890"))
    }

    @Test
    fun `parses with http scheme and trailing slash`() {
        assertEquals("127.0.0.1" to 8080, NetworkDownloader.parseProxySpec("http://127.0.0.1:8080/"))
    }

    @Test
    fun `parses socks scheme as http forward proxy`() {
        assertEquals("10.0.0.2" to 1080, NetworkDownloader.parseProxySpec("socks5://10.0.0.2:1080"))
    }

    @Test
    fun `trims whitespace`() {
        assertEquals("192.168.0.3" to 7897, NetworkDownloader.parseProxySpec("  192.168.0.3:7897  "))
    }

    @Test
    fun `blank and null mean direct connection`() {
        assertNull(NetworkDownloader.parseProxySpec(null))
        assertNull(NetworkDownloader.parseProxySpec(""))
        assertNull(NetworkDownloader.parseProxySpec("   "))
    }

    @Test
    fun `rejects missing port bad port and hostless input`() {
        assertNull(NetworkDownloader.parseProxySpec("192.168.1.5"))
        assertNull(NetworkDownloader.parseProxySpec("192.168.1.5:"))
        assertNull(NetworkDownloader.parseProxySpec("192.168.1.5:abc"))
        assertNull(NetworkDownloader.parseProxySpec("192.168.1.5:99999"))
        assertNull(NetworkDownloader.parseProxySpec("192.168.1.5:0"))
        assertNull(NetworkDownloader.parseProxySpec(":7890"))
    }
}
