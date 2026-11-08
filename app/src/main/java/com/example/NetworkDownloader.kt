package com.example

import android.content.Context
import android.net.Uri
import com.example.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.example.transfer.convertMp4ToAudio
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 第三方解析接口配置（B站音频兜底换源）。
 * template 中可使用占位符 {bvid}（B站视频ID）与 {url}（视频页地址），
 * 下载时按启用顺序逐一尝试。
 */
data class ParseEndpointConfig(
    val name: String,
    val template: String,
    val enabled: Boolean = true
)

object NetworkDownloader {
    // 下载代理（可空）：X/YouTube 等媒体 CDN 在部分网络不可直达，可配置
    // 局域网 HTTP 代理（如电脑上的 Clash 192.168.x.x:7890）转发下载流量
    @Volatile private var proxyHost: String? = null
    @Volatile private var proxyPort: Int = -1

    @Volatile
    private var client = buildClient()

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)  // 大文件下载友好：CDN 偶发停顿不应导致超时
            // P0 安全修复：关闭 OkHttp 自动重定向——自动跟随的目标从未经过 isPublicHttpUrl
            // 门禁，存在"302 → 内网地址"的 SSRF 绕过。改由 RedirectGuardInterceptor 手动
            // 逐跳跟随，每一跳 Location 都重新走同一套 SSRF 校验，最多跟随 MAX_REDIRECT_FOLLOW 次。
            .followRedirects(false)
            .followSslRedirects(false)
            // DNS 钉扎：连接使用的解析结果与 isPublicAddress 校验共用同一份，
            // 封死"校验时解析到公网、连接时再解析被劫持到内网"的 DNS rebinding。
            .dns(PublicDnsOnly())
            .addInterceptor(RedirectGuardInterceptor())
        val host = proxyHost
        if (host != null && proxyPort in 1..65535) {
            builder.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, proxyPort)))
        }
        return builder.build()
    }

    /** 手动重定向跟随次数上限：防止无限重定向循环拖死下载线程。 */
    private const val MAX_REDIRECT_FOLLOW = 5

    /** 与 OkHttp 自动重定向语义一致的 3xx 状态码集合。 */
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

    /** 重定向目标未通过 SSRF 校验（非公网 http/https）时抛出，调用层据此给出"链接不安全"提示。 */
    private class UnsafeRedirectException(message: String) : java.io.IOException(message)

    /**
     * 手动重定向跟随拦截器（SSRF 修复核心）：
     * 每个被允许的 3xx 响应取出 Location，经 HttpUrl.resolve 归一化为绝对地址后
     * 重新走 isPublicHttpUrl 门禁（scheme / 内网主机名 / DNS 解析逐 IP 校验），通过才发起下一跳；
     * 校验失败、Location 缺失或跳数超过 MAX_REDIRECT_FOLLOW 一律中止并抛 IOException。
     * 301/302/303 的 POST 降级为 GET（与 OkHttp 自动跟随语义一致）；307/308 保留原方法与请求体；
     * 跨主机跳转时丢弃 Cookie/Authorization 头，防止 SESSDATA 等凭证随跳转外泄。
     */
    private class RedirectGuardInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            var hops = 0
            var response = chain.proceed(request)
            while (response.code in REDIRECT_STATUSES) {
                val code = response.code
                val location = response.header("Location")
                val requestUrl = request.url
                response.close() // 3xx 响应体无需读取，关闭后再发下一跳，避免连接泄漏
                if (hops >= MAX_REDIRECT_FOLLOW) {
                    throw java.io.IOException("重定向次数超过上限($MAX_REDIRECT_FOLLOW)，已中止请求")
                }
                if (location.isNullOrBlank()) {
                    throw java.io.IOException("HTTP $code 重定向缺少 Location 头，已中止请求")
                }
                val nextUrl = requestUrl.resolve(location)
                    ?: throw java.io.IOException("重定向 Location 无法解析为合法地址: $location")
                if (!isPublicHttpUrl(nextUrl.toString())) {
                    throw UnsafeRedirectException("重定向目标未通过公网安全校验: $nextUrl")
                }
                val method = when {
                    code == 307 || code == 308 -> request.method
                    request.method == "POST" -> "GET"
                    else -> request.method
                }
                val body = if (method == request.method) request.body else null
                val nextRequestBuilder = request.newBuilder().url(nextUrl)
                if (requestUrl.host != nextUrl.host) {
                    nextRequestBuilder.removeHeader("Cookie").removeHeader("Authorization")
                }
                request = nextRequestBuilder.method(method, body).build()
                hops++
                response = chain.proceed(request)
            }
            return response
        }
    }

    /**
     * DNS 钉扎解析（防 rebinding）：所有直连请求的域名解析结果逐 IP 过 isPublicAddress
     * 校验（与 isPublicHttpUrl 同一套 IP 判定），只把公网 IP 交给连接层——校验与连接共用
     * 同一份解析结果，杜绝"校验时公网、连接时内网"。解析不出公网 IP 直接抛 UnknownHostException。
     * 注：配置了下载代理时目标域名由代理侧解析，不经过本类；代理地址本身由用户显式配置。
     */
    private class PublicDnsOnly : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val safe = java.net.InetAddress.getAllByName(hostname).filter { isPublicAddress(it) }
            if (safe.isEmpty()) {
                throw java.net.UnknownHostException("主机 $hostname 未解析到公网地址，拒绝连接")
            }
            return safe
        }
    }

    /**
     * 解析用户输入的代理配置。支持 "host:port"、"http://host:port"、
     * "socks5://host:port"（统一按 HTTP 代理转发）。无效输入返回 null。
     */
    internal fun parseProxySpec(spec: String?): Pair<String, Int>? {
        val cleaned = spec?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // 剥离 scheme（大小写不敏感），统一按 HTTP 代理转发
        val lower = cleaned.lowercase()
        val noScheme = when {
            lower.startsWith("https://") -> cleaned.substring("https://".length)
            lower.startsWith("http://") -> cleaned.substring("http://".length)
            lower.startsWith("socks5://") -> cleaned.substring("socks5://".length)
            lower.startsWith("socks://") -> cleaned.substring("socks://".length)
            else -> cleaned
        }.trimEnd('/')
        val parts = noScheme.split(":")
        val host = parts.getOrNull(0)?.trim().orEmpty()
        val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    /** 设置/清除下载代理；spec 为空串或 null 表示直连。 */
    fun updateProxy(spec: String?) {
        val parsed = parseProxySpec(spec)
        proxyHost = parsed?.first
        proxyPort = parsed?.second ?: -1
        client = buildClient()
    }

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val MOBILE_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    // ===== B站 buvid3 缓存（用于绕过 412 风控） =====
    @Volatile
    private var cachedBuvid3: String? = null
    private var buvid3FetchTime: Long = 0L
    private const val BUVID3_CACHE_TTL_MS = 30 * 60 * 1000L // 30 分钟有效期

    // ===== 第三方解析接口（可配置白名单，B站音频兜底换源）=====
    // 用户可在"个性化设置"中开关/增删；由 MainViewModel 持久化并推送至此。
    @Volatile
    private var configuredParseEndpoints: List<ParseEndpointConfig> = defaultParseEndpoints()

    // ===== 视频下载偏好（B站画质 / SESSDATA），由 MainViewModel 推送 =====
    private val VALID_QUALITIES = setOf(16, 32, 64, 80)
    @Volatile
    private var preferredQuality: Int = 64
    @Volatile
    private var sessdataCookie: String? = null

    /** quality: B站 qn 值 16=360P 32=480P 64=720P 80=1080P；sessdata 可为空（未登录画质）。 */
    fun updateVideoPreferences(quality: Int, sessdata: String?) {
        preferredQuality = if (quality in VALID_QUALITIES) quality else 64
        sessdataCookie = sessdata?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** B站请求 Cookie 头：buvid3 必带（防 412），SESSDATA 可选（解锁高画质）。 */
    internal fun biliCookieHeader(buvid3: String): String = buildString {
        append("buvid3=").append(buvid3)
        sessdataCookie?.let { append("; SESSDATA=").append(it) }
    }

    /** 内置默认换源接口（与历史硬编码列表一致，保证既有行为不变）。 */
    fun defaultParseEndpoints(): List<ParseEndpointConfig> = listOf(
        ParseEndpointConfig("api.injahow.cn", "https://api.injahow.cn/bparse/?bv={bvid}&format=mp4"),
        ParseEndpointConfig("api.xinrance.com", "https://api.xinrance.com/api/bilibili?url={url}"),
        ParseEndpointConfig("api.vvhan.com", "https://api.vvhan.com/api/bilibili?url={url}"),
        ParseEndpointConfig("tenapi.cn", "https://tenapi.cn/v2/bilibili?url={url}"),
        ParseEndpointConfig("api.pearktrue.cn", "https://api.pearktrue.cn/api/bilibili/?url={url}")
    )

    /** 用主界面配置的启用列表替换内置列表。 */
    fun updateParseEndpoints(endpoints: List<ParseEndpointConfig>) {
        configuredParseEndpoints = endpoints.filter { it.enabled }
    }

    internal fun enabledParseApiTemplates(): List<String> =
        configuredParseEndpoints.map { it.template }

    /** 将模板中的 {bvid}/{url} 占位符替换为实际值（bvid 为空时替换为空串，与既有行为一致）。 */
    internal fun buildParseApiUrl(template: String, bvid: String?, pageUrl: String): String =
        template.replace("{bvid}", bvid ?: "").replace("{url}", pageUrl)

    /**
     * 通过 B站 spi 接口获取真实的 buvid3 cookie 值。
     * 返回缓存值（如果未过期），否则发起网络请求并缓存结果。
     * 修复：B站搜索接口会校验 buvid3，使用 "infoc" 假值会返回 412。
     */
    suspend fun fetchBuvid3(context: Context): String {
        val now = System.currentTimeMillis()
        val cached = cachedBuvid3
        if (cached != null && (now - buvid3FetchTime) < BUVID3_CACHE_TTL_MS) {
            return cached
        }
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.bilibili.com/x/frontend/finger/spi")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: ""
                        val json = JSONObject(body)
                        if (json.optInt("code") == 0) {
                            val buvid3 = json.optJSONObject("data")?.optString("b_3")
                            if (!buvid3.isNullOrBlank()) {
                                cachedBuvid3 = buvid3
                                buvid3FetchTime = System.currentTimeMillis()
                                AppLogger.log(context, "【B站】成功获取真实 buvid3: ${buvid3.take(16)}...")
                                return@withContext buvid3
                            }
                        }
                    }
                    AppLogger.log(context, "【B站】获取 buvid3 失败，使用 fallback")
                    return@withContext "infoc"
                }
            } catch (e: Exception) {
                AppLogger.log(context, "【B站】获取 buvid3 异常: ${e.message}，使用 fallback")
                return@withContext "infoc"
            }
        }
    }

    /**
     * 请求前置安全校验：仅允许 http/https，且目标 host 不得为
     * localhost / 环回 / 私有 / 链路本地 / 保留地址（含 DNS 解析后的 IP 判断）。
     * 用户输入的任意 URL 与解析得到的媒体直链都必须经过此检查。
     */
    fun isPublicHttpUrl(url: String): Boolean {
        val parsed = try {
            url.toHttpUrlOrNull()
        } catch (e: Exception) {
            null
        } ?: return false

        if (parsed.scheme !in setOf("http", "https")) return false
        val host = parsed.host
        if (host.isBlank()) return false

        // 字符串层面的常见内网主机名 / 保留段拦截（先拦 localhost，避免无谓 DNS）
        val hostLower = host.lowercase()
        if (hostLower == "localhost" || hostLower.endsWith(".localhost") || hostLower.endsWith(".local") || hostLower == "0.0.0.0") {
            return false
        }

        // DNS 解析后逐个 IP 校验，防止域名指向内网的绕过
        return try {
            val addresses = java.net.InetAddress.getAllByName(host)
            if (addresses.isEmpty()) return false
            addresses.all { isPublicAddress(it) }
        } catch (e: Exception) {
            // 无法解析的 host 一律拒绝，宁可误杀不可放行内网
            false
        }
    }

    /**
     * 单个 IP 的公网判定：排除环回/私有/链路本地/保留/组播地址（0.0.0.0/8、10/8、
     * 100.64/10 CGNAT、127/8、169.254/16、172.16/12、192.168/16、fc00::/7 ULA）。
     * isPublicHttpUrl 与 PublicDnsOnly（DNS 钉扎）共用同一套判定，避免两处逻辑漂移。
     */
    internal fun isPublicAddress(addr: java.net.InetAddress): Boolean {
        val isPrivateV4 = run {
            val b = addr.address
            b.size == 4 && (
                b[0] == 0.toByte() ||                       // 0.0.0.0/8 "this network"
                b[0] == 10.toByte() ||                      // 10.0.0.0/8
                (b[0] == 100.toByte() && b[1] in 64..127) ||// 100.64.0.0/10 CGNAT
                (b[0] == 127.toByte()) ||                   // 127.0.0.0/8
                (b[0] == 169.toByte() && b[1] == 254.toByte()) || // 169.254.0.0/16
                (b[0] == 172.toByte() && b[1] in 16..31) || // 172.16.0.0/12
                (b[0] == 192.toByte() && b[1] == 168.toByte())    // 192.168.0.0/16
                )
        }
        val isUlaV6 = addr.address.size == 16 &&
                ((addr.address[0].toInt() and 0xFF) in 0xFC..0xFD) // fc00::/7 ULA
        return !addr.isLoopbackAddress &&
                !addr.isSiteLocalAddress &&
                !addr.isLinkLocalAddress &&
                !addr.isAnyLocalAddress &&
                !addr.isMulticastAddress &&
                !isPrivateV4 &&
                !isUlaV6
    }

    fun sanitizeUrlInput(rawInput: String): String {
        if (rawInput.isBlank()) return ""

        val cleaned = rawInput.trim()
            .replace("https：//", "https://")
            .replace("http：//", "http://")
            .replace("：", ":")
            .replace("／", "/")

        // 1. Direct extraction starting from http:// or https://
        val httpIdx = cleaned.indexOf("https://", ignoreCase = true)
            .takeIf { it != -1 } 
            ?: cleaned.indexOf("http://", ignoreCase = true).takeIf { it != -1 } ?: -1

        if (httpIdx != -1) {
            val substringFromHttp = cleaned.substring(httpIdx)
            val urlRegex = Regex("""https?://[^\s\u4e00-\u9fa5"'<>]+""", RegexOption.IGNORE_CASE)
            val match = urlRegex.find(substringFromHttp)
            if (match != null) {
                return match.value.trimEnd(',', '.', ';', '!', '?', '，', '。', '！', '？', '；', '）', ')', '】', ']', '}', '\"', '\'')
            }
        }

        // 2. Direct BV match (e.g. BV1xx411c7m9)
        val bvMatch = Regex("""BV[a-zA-Z0-9]{10}""").find(cleaned)
        if (bvMatch != null) {
            return "https://www.bilibili.com/video/${bvMatch.value}"
        }

        // 3. Direct AV match (e.g. av123456)。前后不能是字母数字，避免 "sav123" 之类误判
        val avMatch = Regex("""(?<![a-zA-Z0-9])av(\d+)""", RegexOption.IGNORE_CASE).find(cleaned)
        if (avMatch != null) {
            return "https://www.bilibili.com/video/av${avMatch.groupValues[1]}"
        }

        // 4. Domain match without scheme
        val domainMatch = Regex("""([a-zA-Z0-9-]+\.[a-zA-Z]{2,})/[^\s\u4e00-\u9fa5"'<>]+""", RegexOption.IGNORE_CASE).find(cleaned)
        if (domainMatch != null) {
            return "https://${domainMatch.value}".trimEnd(',', '.', ';', '!', '?', '，', '。', '！', '？', '；', '）', ')', '】', ']', '}', '\"', '\'')
        }

        return cleaned
    }

    /**
     * B站链接路由判定：域名命中（bilibili.com / b23.tv 及其子域），
     * 或包含独立的 BV 号 / av 号（词边界正则，前后不能是字母数字）。
     * 替代旧的 contains("av") 子串匹配，避免 .avi / have / caravan 等直链
     * 被误送进 B站解析链路导致直链下载与标题抓取失效。
     */
    internal fun isBilibiliUrl(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase()
        if (host != null &&
            (host == "bilibili.com" || host.endsWith(".bilibili.com") ||
                host == "b23.tv" || host.endsWith(".b23.tv"))
        ) return true
        return url.contains(Regex("""BV[a-zA-Z0-9]{10}""")) ||
                url.contains(Regex("""(?<![a-zA-Z0-9])av\d+""", RegexOption.IGNORE_CASE))
    }

    fun extractYouTubeVideoId(url: String): String? {
        val cleanUrl = sanitizeUrlInput(url)

        val vMatch = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""").find(cleanUrl)
        if (vMatch != null) return vMatch.groupValues[1]

        val shortMatch = Regex("""youtu\.be/([a-zA-Z0-9_-]{11})""").find(cleanUrl)
        if (shortMatch != null) return shortMatch.groupValues[1]

        val shortsMatch = Regex("""youtube\.com/shorts/([a-zA-Z0-9_-]{11})""").find(cleanUrl)
        if (shortsMatch != null) return shortsMatch.groupValues[1]

        val embedMatch = Regex("""youtube\.com/embed/([a-zA-Z0-9_-]{11})""").find(cleanUrl)
        if (embedMatch != null) return embedMatch.groupValues[1]

        if (cleanUrl.matches(Regex("""[a-zA-Z0-9_-]{11}"""))) {
            return cleanUrl
        }

        return null
    }

    suspend fun copyStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        contentLength: Long,
        basePct: Float,
        pctRange: Float,
        statusPrefix: String,
        onProgress: ((Float, String) -> Unit)?
    ): Long {
        val buffer = ByteArray(32 * 1024)
        var bytesRead: Int
        var totalBytesRead = 0L
        var lastReportTime = 0L
        var lastReportPct = -1f
        var speedWindowStart = 0L
        var speedWindowBytes = 0L
        var speedBytesPerSec = 0f

        while (input.read(buffer).also { bytesRead = it } != -1) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            output.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead

            if (onProgress != null) {
                val filePct = if (contentLength > 0) {
                    (totalBytesRead.toFloat() / contentLength).coerceIn(0f, 1f)
                } else {
                    (1f - Math.exp(-totalBytesRead.toDouble() / (5 * 1024 * 1024)).toFloat()).coerceIn(0f, 0.95f)
                }
                val totalPct = basePct + (filePct * pctRange)
                val now = System.currentTimeMillis()

                if (now - lastReportTime >= 100L || Math.abs(totalPct - lastReportPct) >= 0.005f || filePct >= 1.0f) {
                    // 速度窗口：约每 800ms 刷新一次 MB/s
                    if (speedWindowStart == 0L) {
                        speedWindowStart = now
                        speedWindowBytes = totalBytesRead
                    } else if (now - speedWindowStart >= 800) {
                        val secs = (now - speedWindowStart) / 1000f
                        if (secs > 0) speedBytesPerSec = (totalBytesRead - speedWindowBytes) / secs
                        speedWindowStart = now
                        speedWindowBytes = totalBytesRead
                    }
                    lastReportTime = now
                    lastReportPct = totalPct
                    val mbRead = String.format(java.util.Locale.getDefault(), "%.1f MB", totalBytesRead / (1024f * 1024f))
                    val totalMb = if (contentLength > 0) String.format(java.util.Locale.getDefault(), " / %.1f MB", contentLength / (1024f * 1024f)) else ""
                    val speedText = if (speedBytesPerSec > 0) String.format(java.util.Locale.getDefault(), " · %.1f MB/s", speedBytesPerSec / (1024f * 1024f)) else ""
                    val pctInt = (totalPct * 100).toInt().coerceIn(0, 100)

                    onProgress(totalPct, "$statusPrefix: $pctInt% ($mbRead$totalMb)$speedText")
                }
            }
        }
        output.flush()
        return totalBytesRead
    }

    fun sanitizeFileName(title: String, extension: String): String {
        if (title.isBlank()) return "download.$extension"
        val clean = title.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim('.', ' ')
        val truncated = if (clean.length > 70) clean.substring(0, 70).trim() else clean
        val safeName = if (truncated.isBlank()) "video_download" else truncated
        return if (safeName.endsWith(".$extension", ignoreCase = true)) safeName else "$safeName.$extension"
    }

    suspend fun fetchVideoTitle(context: Context, rawUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val cleanUrl = sanitizeUrlInput(rawUrl)
                if (cleanUrl.isBlank()) return@withContext null
                // 安全校验：任意用户输入的 URL 抓取标题前必须检查目标是否公网 http(s)
                if (!isPublicHttpUrl(cleanUrl)) {
                    AppLogger.w(context, "标题抓取目标未通过安全校验: $cleanUrl", "视频解析")
                    return@withContext null
                }

                // 1. YouTube Title
                val ytId = extractYouTubeVideoId(cleanUrl)
                if (ytId != null) {
                    try {
                        val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$ytId&format=json"
                        val req = Request.Builder().url(oembedUrl).header("User-Agent", USER_AGENT).build()
                        client.newCall(req).execute().use { res ->
                            if (res.isSuccessful) {
                                val json = JSONObject(res.body?.string() ?: "")
                                val title = json.optString("title")
                                if (!title.isNullOrBlank()) return@withContext title
                            }
                        }
                    } catch (e: Exception) { }

                    try {
                        val noembedUrl = "https://noembed.com/embed?url=https://www.youtube.com/watch?v=$ytId"
                        val req = Request.Builder().url(noembedUrl).header("User-Agent", USER_AGENT).build()
                        client.newCall(req).execute().use { res ->
                            if (res.isSuccessful) {
                                val json = JSONObject(res.body?.string() ?: "")
                                val title = json.optString("title")
                                if (!title.isNullOrBlank()) return@withContext title
                            }
                        }
                    } catch (e: Exception) { }
                }

                // 2. Bilibili Title
                if (isBilibiliUrl(cleanUrl)) {
                    val resolved = resolveFinalUrl(cleanUrl)
                    val bvid = extractBvid(context, resolved)
                    if (bvid != null) {
                        try {
                            val buvid3 = fetchBuvid3(context)
                            val req = Request.Builder()
                                .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
                                .header("User-Agent", USER_AGENT)
                                .header("Referer", "https://www.bilibili.com/")
                                .header("Cookie", "buvid3=$buvid3")
                                .build()
                            client.newCall(req).execute().use { res ->
                                if (res.isSuccessful) {
                                    val json = JSONObject(res.body?.string() ?: "")
                                    if (json.optInt("code") == 0) {
                                        val title = json.optJSONObject("data")?.optString("title")
                                        if (!title.isNullOrBlank()) return@withContext title
                                    }
                                }
                            }
                        } catch (e: Exception) { }
                    }
                }

                // 3. Twitter Title
                val statusMatch = Regex("status/(\\d+)").find(cleanUrl)
                if (statusMatch != null) {
                    val statusId = statusMatch.groupValues[1]
                    try {
                        val req = Request.Builder()
                            .url("https://api.fxtwitter.com/status/$statusId")
                            .header("User-Agent", USER_AGENT)
                            .build()
                        client.newCall(req).execute().use { res ->
                            if (res.isSuccessful) {
                                val json = JSONObject(res.body?.string() ?: "")
                                val tweet = json.optJSONObject("tweet")
                                val text = tweet?.optString("text")
                                val author = tweet?.optJSONObject("author")?.optString("name")
                                if (!text.isNullOrBlank()) {
                                    val summary = if (text.length > 50) text.substring(0, 50) + "..." else text
                                    return@withContext if (!author.isNullOrBlank()) "${author}_$summary" else summary
                                }
                            }
                        }
                    } catch (e: Exception) { }
                }

                // 4. HTML Page Title Fallback
                try {
                    val targetUrl = if (cleanUrl.startsWith("http")) cleanUrl else "https://$cleanUrl"
                    val req = Request.Builder().url(targetUrl).header("User-Agent", USER_AGENT).build()
                    client.newCall(req).execute().use { res ->
                        if (res.isSuccessful) {
                            val html = res.body?.string() ?: ""
                            val ogMatch = Regex("""<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                            if (ogMatch != null) return@withContext ogMatch.groupValues[1].trim()

                            val titleMatch = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)
                            if (titleMatch != null) {
                                return@withContext titleMatch.groupValues[1]
                                    .replace("_哔哩哔哩_bilibili", "")
                                    .replace(" - YouTube", "")
                                    .replace(" / X", "")
                                    .trim()
                            }
                        }
                    }
                } catch (e: Exception) { }

                null
            } catch (e: Exception) {
                null
            }
        }
    }


    // ===== B站分P信息 =====
    data class BiliPage(val cid: Long, val page: Int, val part: String)

    /** 从 view API JSON 中解析分P列表（纯逻辑，可单测）。 */
    internal fun parseBilibiliPages(json: String): List<BiliPage> {
        return try {
            val root = JSONObject(json)
            if (root.optInt("code") != 0) return emptyList()
            val pages = root.optJSONObject("data")?.optJSONArray("pages") ?: return emptyList()
            (0 until pages.length()).mapNotNull { i ->
                val p = pages.optJSONObject(i) ?: return@mapNotNull null
                val cid = p.optLong("cid")
                if (cid <= 0) return@mapNotNull null
                BiliPage(
                    cid = cid,
                    page = p.optInt("page", i + 1),
                    part = p.optString("part").ifBlank { "P${i + 1}" }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 获取B站视频分P列表（多P视频用于分P选择；失败返回空列表）。 */
    suspend fun fetchBilibiliPages(context: Context, rawUrl: String): List<BiliPage> {
        return withContext(Dispatchers.IO) {
            try {
                val cleanUrl = sanitizeUrlInput(rawUrl)
                if (!isPublicHttpUrl(cleanUrl)) return@withContext emptyList()
                val bvid = extractBvid(context, resolveFinalUrl(cleanUrl)) ?: return@withContext emptyList()
                val buvid3 = fetchBuvid3(context)
                val req = Request.Builder()
                    .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Cookie", biliCookieHeader(buvid3))
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@withContext emptyList()
                    parseBilibiliPages(res.body?.string() ?: "")
                }
            } catch (e: Exception) {
                AppLogger.log(context, "【B站】获取分P列表失败: ${e.message}")
                emptyList()
            }
        }
    }

    // 歌曲搜索：统一返回结果列表（videoId 存 bvid 或 yt videoId, source 标记来源）
    data class SongResult(
        val videoId: String,
        val title: String,
        val channel: String,
        val duration: String,
        val source: String = "youtube"
    )

    // 从 B 站搜索视频（歌曲），返回结果列表（source 标记 bilibili）
    // 修复：使用真实 buvid3 cookie 绕过 412 风控
    suspend fun searchBilibiliMusic(context: Context, query: String, limit: Int = 5): List<SongResult> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<SongResult>()
            if (query.isBlank()) return@withContext results
            try {
                val buvid3 = fetchBuvid3(context)
                val enc = java.net.URLEncoder.encode(query, "UTF-8")
                val searchUrl = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=$enc"
                val req = Request.Builder().url(searchUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Cookie", "buvid3=$buvid3")
                    .build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val json = JSONObject(res.body?.string() ?: "")
                        if (json.optInt("code") == 0) {
                            val resultArr = json.optJSONObject("data")?.optJSONArray("result")
                            if (resultArr != null) {
                                for (i in 0 until resultArr.length()) {
                                    if (results.size >= limit) break
                                    val item = resultArr.optJSONObject(i)
                                    if (item == null) continue
                                    if (item.optString("type") != "video") continue
                                    val bvid = item.optString("bvid")
                                    var title = item.optString("title")
                                        .replace(Regex("</?em>"), "")
                                        .replace(Regex("<em class=\"keyword\">"), "")
                                        .trim()
                                    val author = item.optString("author")
                                    val duration = item.optString("duration")
                                    if (!bvid.isNullOrBlank() && !title.isNullOrBlank()) {
                                        results.add(SongResult(
                                            videoId = bvid,
                                            title = title,
                                            channel = author,
                                            duration = duration,
                                            source = "bilibili"
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(context, "【歌曲搜索-B站】失败: ${e.message}")
            }
            results
        }
    }

    suspend fun searchYouTubeMusic(context: Context, query: String, limit: Int = 5): List<SongResult> {
        return withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val results = mutableListOf<SongResult>()

            // 方案 1: Piped 搜索 API (JSON, 简洁，复用现有第三方源)
            try {
                val searchUrl = "https://pipedapi.kavin.rocks/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&filter=videos"
                val req = Request.Builder().url(searchUrl)
                    .header("User-Agent", USER_AGENT).build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val json = JSONObject(res.body?.string() ?: "")
                        val items = json.optJSONArray("items")
                        if (items != null) {
                            for (i in 0 until items.length()) {
                                if (results.size >= limit) break
                                val item = items.optJSONObject(i)
                                val id = item?.optString("url")?.substringAfter("watch?v=")
                                val title = item?.optString("title")
                                if (!id.isNullOrBlank() && !title.isNullOrBlank()) {
                                    results.add(SongResult(
                                        videoId = id,
                                        title = title,
                                        channel = item?.optString("uploaderName") ?: "",
                                        duration = item?.optString("duration") ?: ""
                                    ))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(context, "【歌曲搜索-方案1】Piped 失败: ${e.message}")
            }
            if (results.isNotEmpty()) return@withContext results

            // 方案 2: YouTube innertube 搜索 API
            try {
                val body = JSONObject().apply {
                    put("query", query)
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20240701.00.00")
                            put("hl", "zh-CN")
                        })
                    })
                }
                val req = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/search")
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create("application/json".toMediaType(), body.toString()))
                    .build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val json = JSONObject(res.body?.string() ?: "")
                        val contents = json.optJSONObject("contents")
                            ?.optJSONObject("twoColumnSearchResultsRenderer")
                            ?.optJSONObject("primaryContents")
                            ?.optJSONObject("sectionListRenderer")
                            ?.optJSONArray("contents")
                        if (contents != null) {
                            for (i in 0 until contents.length()) {
                                if (results.size >= limit) break
                                val itemSection = contents.optJSONObject(i)
                                    ?.optJSONObject("itemSectionRenderer")
                                    ?.optJSONArray("contents") ?: continue
                                for (j in 0 until itemSection.length()) {
                                    if (results.size >= limit) break
                                    val videoRenderer = itemSection.optJSONObject(j)
                                        ?.optJSONObject("videoRenderer") ?: continue
                                    val id = videoRenderer.optString("videoId")
                                    val title = videoRenderer.optJSONObject("title")
                                        ?.optJSONArray("runs")
                                        ?.optJSONObject(0)
                                        ?.optString("text")
                                    if (!id.isNullOrBlank() && !title.isNullOrBlank()) {
                                        val lengthText = videoRenderer.optJSONObject("lengthText")
                                            ?.optJSONArray("runs")
                                            ?.optJSONObject(0)
                                            ?.optString("text") ?: ""
                                        val channel = videoRenderer.optJSONObject("ownerText")
                                            ?.optJSONArray("runs")
                                            ?.optJSONObject(0)
                                            ?.optString("text") ?: ""
                                        results.add(SongResult(
                                            videoId = id,
                                            title = title,
                                            channel = channel,
                                            duration = lengthText
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(context, "【歌曲搜索-方案2】innertube 失败: ${e.message}")
            }
            results
        }
    }

    suspend fun downloadVideo(
        context: Context,
        url: String,
        outputUri: Uri,
        isMp3: Boolean,
        onProgress: ((Float, String) -> Unit)? = null,
        cid: Long? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val inputUrl = sanitizeUrlInput(url)
                AppLogger.log(context, "智能分析视频链接: '$url' -> '$inputUrl'")
                onProgress?.invoke(0.05f, "正在分析视频链接 (5%)...")

                // 安全校验：拒绝非 http(s) 与指向本机/内网/保留地址的链接
                if (!isPublicHttpUrl(inputUrl)) {
                    AppLogger.w(context, "链接未通过安全校验（非 http/https 或指向内网/保留地址）: $inputUrl", "视频解析")
                    onProgress?.invoke(0f, "错误: 链接不安全或无效（仅支持公网 http/https 地址）")
                    return@withContext false
                }

                if (isBilibiliUrl(inputUrl)) {
                    return@withContext downloadBilibiliMultiSource(context, inputUrl, outputUri, isMp3, onProgress, cid)
                } else if (inputUrl.contains("twitter.com") || inputUrl.contains("x.com")) {
                    return@withContext downloadTwitterMultiSource(context, inputUrl, outputUri, isMp3, onProgress)
                } else if (inputUrl.contains("youtube.com") || inputUrl.contains("youtu.be") || inputUrl.contains("yt.be")) {
                    return@withContext downloadYouTubeMultiSource(context, inputUrl, outputUri, isMp3, onProgress)
                } else if (inputUrl.startsWith("http://") || inputUrl.startsWith("https://")) {
                    return@withContext downloadDirectMediaUrl(context, inputUrl, outputUri, isMp3, onProgress)
                }
                
                AppLogger.log(context, "未能识别出支持的视频链接: $inputUrl")
                false
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogger.log(context, "视频下载过程发生异常: ${e.localizedMessage}")
                e.printStackTrace()
                false
            }
        }
    }

    private suspend fun downloadDirectMediaUrl(
        context: Context,
        rawUrl: String,
        outputUri: Uri,
        isMp3: Boolean,
        onProgress: ((Float, String) -> Unit)? = null
    ): Boolean {
        val resolvedUrl = resolveFinalUrl(rawUrl)
        onProgress?.invoke(0.10f, "正在连接网络服务 (10%)...")
        return downloadStreamAndSave(
            context = context,
            mediaUrl = resolvedUrl,
            referer = rawUrl,
            outputUri = outputUri,
            isMp3 = isMp3,
            isAudioStreamDirect = false,
            onProgress = onProgress
        )
    }

    private fun resolveFinalUrl(inputUrl: String): String {
        var url = inputUrl
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { res ->
                res.request.url.toString()
            }
        } catch (e: Exception) {
            url
        }
    }

    private fun extractBvid(context: Context, resolvedUrl: String): String? {
        val bvidRegex = Regex("BV[a-zA-Z0-9]{10}")
        val bvidMatch = bvidRegex.find(resolvedUrl)
        if (bvidMatch != null) return bvidMatch.value

        val avRegex = Regex("""(?<![a-zA-Z0-9])av(\d+)""", RegexOption.IGNORE_CASE)
        val avMatch = avRegex.find(resolvedUrl)
        if (avMatch != null) {
            val aid = avMatch.groupValues[1]
            try {
                val req = Request.Builder()
                    .url("https://api.bilibili.com/x/web-interface/view?aid=$aid")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .build()
                client.newCall(req).execute().use { res ->
                    val json = JSONObject(res.body?.string() ?: "")
                    if (json.optInt("code") == 0) {
                        return json.optJSONObject("data")?.optString("bvid")
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(context, "AV号转BV号失败: ${e.message}")
            }
        }
        return null
    }

    // ============================================================================
    // Bilibili response helpers
    // ============================================================================
    internal data class BilibiliMediaSelection(
        val mediaUrl: String?,
        val isAudioStreamDirect: Boolean
    )

    /**
     * Selects a playable URL from the official Bilibili playurl response.
     * durl is preferred because it is a muxed MP4 and therefore includes audio.
     * For MP3, a DASH audio track is preferred to avoid downloading the whole video.
     */
    internal fun isExpectedMp4ContentType(contentType: String?): Boolean {
        val normalized = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return normalized.isEmpty() || normalized.startsWith("video/") || normalized == "application/octet-stream"
    }

    internal fun selectBilibiliMedia(data: JSONObject, isMp3: Boolean): BilibiliMediaSelection {
        // durl is a muxed MP4. It is the only safe video selection because a DASH
        // video track alone has no audio and must not be saved as a silent MP4.
        val durl = data.optJSONArray("durl")
        if (!isMp3 && durl != null) {
            for (index in 0 until durl.length()) {
                val url = durl.optJSONObject(index)?.optString("url").orEmpty()
                if ((url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) {
                    return BilibiliMediaSelection(url, false)
                }
            }
        }

        if (isMp3) {
            val audio = data.optJSONObject("dash")?.optJSONArray("audio")
            if (audio != null) {
                for (index in 0 until audio.length()) {
                    val item = audio.optJSONObject(index) ?: continue
                    val url = item.optString("baseUrl").ifBlank {
                        item.optJSONArray("backupUrl")?.optString(0).orEmpty()
                    }
                    if ((url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) {
                        return BilibiliMediaSelection(url, true)
                    }
                }
            }

            // MP3 can fall back to a muxed MP4 when no DASH audio track exists.
            if (durl != null) {
                for (index in 0 until durl.length()) {
                    val url = durl.optJSONObject(index)?.optString("url").orEmpty()
                    if ((url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) {
                        return BilibiliMediaSelection(url, false)
                    }
                }
            }
        }

        return BilibiliMediaSelection(null, false)
    }

    // ============================================================================
    // 1. 哔哩哔哩 (Bilibili) 官方接口解析与备用方案
    // ============================================================================
    private suspend fun downloadBilibiliMultiSource(
        context: Context,
        rawUrl: String,
        outputUri: Uri,
        isMp3: Boolean,
        onProgress: ((Float, String) -> Unit)? = null,
        cid: Long? = null
    ): Boolean {
        onProgress?.invoke(0.08f, "正在解析B站视频信息 (8%)...")
        AppLogger.log(context, "【B站】开始解析链接: $rawUrl${cid?.let { " (指定分P cid=$it)" } ?: ""}")
        val resolvedUrl = resolveFinalUrl(rawUrl)

        val bvid = extractBvid(context, resolvedUrl)
        if (bvid == null) {
            AppLogger.log(context, "【B站】未能识别视频BVID，尝试直接网页流抓取...")
        } else {
            AppLogger.log(context, "【B站】成功识别BVID: $bvid")
        }

        var mediaUrl: String? = null
        var isAudioStreamDirect = false

        // 方案 1: 网页HTML window.__playinfo__ 正则解析 (主方案)
        // 注：网页 playinfo 只对应 P1；用户明确选择了分P（cid != null）时跳过
        if (bvid != null && cid == null) {
            AppLogger.log(context, "【B站-方案1】尝试网页 HTML window.__playinfo__ 直接提取...")
            try {
                val buvid3 = fetchBuvid3(context)
                val pageReq = Request.Builder()
                    .url("https://www.bilibili.com/video/$bvid/")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Cookie", biliCookieHeader(buvid3) + "; CURRENT_FNVAL=16")
                    .build()
                client.newCall(pageReq).execute().use { pageRes ->
                    val html = pageRes.body?.string() ?: ""
                    val playInfoRegex = Regex("""window\.__playinfo__\s*=\s*(\{.*?\})\s*</script>""")
                    val match = playInfoRegex.find(html)
                    if (match != null) {
                        val playJson = JSONObject(match.groupValues[1])
                        val data = playJson.optJSONObject("data")
                        if (data != null) {
                            if (isMp3 && data.has("dash")) {
                                val dash = data.optJSONObject("dash")
                                val audioArray = dash?.optJSONArray("audio")
                                if (audioArray != null && audioArray.length() > 0) {
                                    mediaUrl = audioArray.getJSONObject(0).optString("baseUrl")
                                    if (mediaUrl.isNullOrEmpty()) {
                                        val backup = audioArray.getJSONObject(0).optJSONArray("backupUrl")
                                        if (backup != null && backup.length() > 0) {
                                            mediaUrl = backup.getString(0)
                                        }
                                    }
                                    if (!mediaUrl.isNullOrEmpty()) {
                                        isAudioStreamDirect = true
                                        AppLogger.log(context, "【B站-方案1】成功获取纯音频Dash流")
                                    }
                                }
                            }

                            if (mediaUrl.isNullOrEmpty()) {
                                // Use the same policy as the official API. In MP4 mode this
                                // deliberately rejects video-only DASH tracks with no audio.
                                val selection = selectBilibiliMedia(data, isMp3)
                                mediaUrl = selection.mediaUrl
                                isAudioStreamDirect = selection.isAudioStreamDirect
                                if (!mediaUrl.isNullOrEmpty()) {
                                    AppLogger.log(context, "【B站-方案1】成功获取${if (isAudioStreamDirect) "DASH音频" else "durl合并媒体"}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(context, "【B站-方案1】网页解析失败: ${e.message}")
            }
        }

        // 方案 2: 官方 REST Web API & Mobile HTML5 API (备用方案 2)
        if (mediaUrl.isNullOrEmpty() && bvid != null) {
            AppLogger.log(context, "【B站-方案2】切换至 Bilibili 官方 API 接口 (view & playurl)...")
            try {
                val buvid3 = fetchBuvid3(context)
                val cookieHeader = biliCookieHeader(buvid3)
                val infoRequest = Request.Builder()
                    .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Cookie", cookieHeader)
                    .build()

                client.newCall(infoRequest).execute().use { infoResponse ->
                    val infoJson = JSONObject(infoResponse.body?.string() ?: "")
                    if (infoJson.optInt("code") == 0) {
                        // 用户指定分P时使用指定 cid，否则取默认 P1
                        val effectiveCid = cid ?: infoJson.getJSONObject("data").getLong("cid")

                        // 2a. Standard playurl API
                        // fnval=0 requests a muxed MP4 (video + audio), which is
                        // the safest format for MP4 downloads and avoids silent DASH files.
                        val quality = preferredQuality
                        val playUrlRequests = if (isMp3) {
                            // DASH exposes a direct audio track; avoid downloading a full MP4.
                            listOf(
                                "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$effectiveCid&qn=$quality&fnval=16",
                                "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$effectiveCid&qn=$quality&fnval=0"
                            )
                        } else {
                            // Only request muxed MP4 for video downloads. A video-only DASH
                            // track must never be saved as an apparently valid silent MP4.
                            listOf(
                                "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$effectiveCid&qn=$quality&fnval=0"
                            )
                        }
                        for (playUrl in playUrlRequests) {
                            if (!mediaUrl.isNullOrEmpty()) break
                            val playUrlReq = Request.Builder()
                                .url(playUrl)
                                .header("User-Agent", USER_AGENT)
                                .header("Referer", "https://www.bilibili.com/")
                                .header("Cookie", cookieHeader)
                                .build()
                            client.newCall(playUrlReq).execute().use { playRes ->
                                val playJson = JSONObject(playRes.body?.string() ?: "")
                                val code = playJson.optInt("code", -1)
                                val data = playJson.optJSONObject("data")
                                if (code == 0 && data != null) {
                                    val selection = selectBilibiliMedia(data, isMp3)
                                    mediaUrl = selection.mediaUrl
                                    isAudioStreamDirect = selection.isAudioStreamDirect
                                    if (!mediaUrl.isNullOrEmpty()) {
                                        AppLogger.log(context, "【B站-方案2】官方 PlayURL 成功，格式=${if (isAudioStreamDirect) "DASH音频" else "MP4/视频"}")
                                    }
                                } else {
                                    AppLogger.log(context, "【B站-方案2】PlayURL 返回 code=$code，继续尝试备用格式")
                                }
                            }
                        }

                        // 2b. HTML5 Mobile PlayURL API fallback
                        // 注意：低清晰度兜底应走 x/player/playurl + platform=html5，
                        // 旧的 x/v2/dm/playurl 是弹幕接口（恒失败）；cid 也必须用本视频
                        // 的 effectiveCid，否则 URL 里会拼出 cid=null
                        if (mediaUrl.isNullOrEmpty()) {
                            val h5Req = Request.Builder()
                                .url("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$effectiveCid&qn=16&platform=html5")
                                .header("User-Agent", MOBILE_USER_AGENT)
                                .header("Referer", "https://m.bilibili.com/")
                                .build()
                            client.newCall(h5Req).execute().use { h5Res ->
                                val h5Json = JSONObject(h5Res.body?.string() ?: "")
                                if (h5Json.optInt("code") == 0) {
                                    val durlArray = h5Json.optJSONObject("data")?.optJSONArray("durl")
                                    if (durlArray != null && durlArray.length() > 0) {
                                        val url = durlArray.optJSONObject(0)?.optString("url").orEmpty()
                                        if (url.startsWith("http")) {
                                            mediaUrl = url
                                            AppLogger.log(context, "【B站-方案2】通过移动端 HTML5 PlayURL 成功抓取")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(context, "【B站-方案2】官方API解析失败: ${e.message}")
            }
        }

        // 第三方解析器可能把 DASH 音频 m4a 当作“视频链接”返回；MP4 仅接受官方 durl。
        if (!isMp3 && mediaUrl.isNullOrEmpty()) {
            AppLogger.log(context, "【B站】官方合并 MP4 未返回 durl，拒绝第三方音频流回退")
            return false
        }

        // 方案 3: 开放多渠道第三方换源 API（仅 MP3 的最后回退；接口列表可在个性化设置中配置）
        if (isMp3 && mediaUrl.isNullOrEmpty()) {
            AppLogger.log(context, "【B站-方案3】尝试开放第三方多渠道换源解析...")
            val videoTargetUrl = if (bvid != null) "https://www.bilibili.com/video/$bvid" else resolvedUrl
            val openApis = enabledParseApiTemplates().map { buildParseApiUrl(it, bvid, videoTargetUrl) }
            if (openApis.isEmpty()) {
                AppLogger.log(context, "【B站-方案3】未启用任何第三方解析接口，已跳过")
            }
            for (apiUrl in openApis) {
                try {
                    val parseReq = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", USER_AGENT)
                        .build()
                    client.newCall(parseReq).execute().use { parseRes ->
                        val respStr = parseRes.body?.string() ?: ""
                        if (respStr.startsWith("http")) {
                            mediaUrl = respStr.trim()
                        } else if (respStr.startsWith("{")) {
                            val json = JSONObject(respStr)
                            mediaUrl = json.optString("url", 
                                json.optString("video_url", 
                                json.optString("data", 
                                json.optJSONObject("data")?.optString("url", ""))))
                        }
                        if (!mediaUrl.isNullOrEmpty() && mediaUrl!!.startsWith("http")) {
                            AppLogger.log(context, "【B站-方案3】开放换源节点解析成功: $apiUrl")
                            break
                        } else {
                            mediaUrl = null
                        }
                    }
                } catch (e: Exception) {
                    // Try next open API
                }
            }
        }

        if (mediaUrl.isNullOrEmpty()) {
            AppLogger.log(context, "【B站】三种备用解析方案均未能提取到视频播放地址")
            return false
        }

        return downloadStreamAndSave(
            context = context,
            mediaUrl = mediaUrl!!,
            referer = "https://www.bilibili.com/",
            outputUri = outputUri,
            isMp3 = isMp3,
            isAudioStreamDirect = isAudioStreamDirect,
            onProgress = onProgress
        )
    }

    // ============================================================================
    // 2. X / Twitter 预留三种以上解析备用方案
    // ============================================================================
    private suspend fun downloadTwitterMultiSource(
        context: Context, 
        rawUrl: String, 
        outputUri: Uri, 
        isMp3: Boolean,
        onProgress: ((Float, String) -> Unit)? = null
    ): Boolean {
        onProgress?.invoke(0.10f, "正在解析 X (Twitter) 媒体信息 (10%)...")
        AppLogger.log(context, "【X/Twitter】开始解析链接: $rawUrl")

        val idRegex = Regex("status/(\\d+)")
        val match = idRegex.find(rawUrl)
        if (match == null) {
            AppLogger.log(context, "【X/Twitter】无法找到推文Status ID")
            return false
        }
        val statusId = match.groupValues[1]

        var videoUrl: String? = null

        // 方案 1: FxTwitter API (主方案)
        AppLogger.log(context, "【X/Twitter-方案1】尝试 FxTwitter API 提取...")
        try {
            val apiUrls = listOf(
                "https://api.fxtwitter.com/status/$statusId",
                "https://api.fixupx.com/status/$statusId"
            )
            for (apiUrl in apiUrls) {
                try {
                    val apiReq = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", USER_AGENT)
                        .build()
                    client.newCall(apiReq).execute().use { apiRes ->
                        val json = JSONObject(apiRes.body?.string() ?: "")
                        if (json.optInt("code") == 200) {
                            val tweet = json.optJSONObject("tweet")
                            val media = tweet?.optJSONObject("media")
                            if (media != null) {
                                if (media.has("videos")) {
                                    videoUrl = media.optJSONArray("videos")?.optJSONObject(0)?.optString("url")
                                } else if (media.has("all")) {
                                    val all = media.optJSONArray("all")
                                    if (all != null) {
                                        for (i in 0 until all.length()) {
                                            val item = all.optJSONObject(i)
                                            if (item?.optString("type") == "video") {
                                                videoUrl = item.optString("url")
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!videoUrl.isNullOrEmpty()) {
                        AppLogger.log(context, "【X/Twitter-方案1】成功从 $apiUrl 提取视频流")
                        break
                    }
                } catch (e: Exception) {
                    // try next
                }
            }
        } catch (e: Exception) {
            AppLogger.log(context, "【X/Twitter-方案1】失败: ${e.message}")
        }

        // 方案 2: VxTwitter API & TwitSave (备用方案 2)
        if (videoUrl.isNullOrEmpty()) {
            AppLogger.log(context, "【X/Twitter-方案2】尝试 VxTwitter 与 TwitSave API 备用节点...")
            try {
                // 2a. VxTwitter API
                val vxReq = Request.Builder()
                    .url("https://api.vxtwitter.com/Twitter/status/$statusId")
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(vxReq).execute().use { vxRes ->
                    val json = JSONObject(vxRes.body?.string() ?: "")
                    if (json.has("media_urls")) {
                        val mediaArray = json.optJSONArray("media_urls")
                        if (mediaArray != null && mediaArray.length() > 0) {
                            for (i in 0 until mediaArray.length()) {
                                val m = mediaArray.getString(i)
                                if (m.contains(".mp4") || m.contains("video.twimg.com")) {
                                    videoUrl = m
                                    break
                                }
                            }
                        }
                    }
                }

                // 2b. TwitSave parser fallback
                if (videoUrl.isNullOrEmpty()) {
                    val tsReq = Request.Builder()
                        .url("https://twitsave.com/info?url=https://x.com/i/status/$statusId")
                        .header("User-Agent", USER_AGENT)
                        .build()
                    client.newCall(tsReq).execute().use { tsRes ->
                        val html = tsRes.body?.string() ?: ""
                        val linkRegex = Regex("""href="(https://twitsave\.com/download\?[^"]+)"""")
                        val linkMatch = linkRegex.find(html)
                        if (linkMatch != null) {
                            videoUrl = linkMatch.groupValues[1]
                            AppLogger.log(context, "【X/Twitter-方案2】通过 TwitSave 提取成功")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(context, "【X/Twitter-方案2】失败: ${e.message}")
            }
        }

        // 方案 3: Cobalt API 与 Mirror 元数据抓取 (备用方案 3)
        if (videoUrl.isNullOrEmpty()) {
            AppLogger.log(context, "【X/Twitter-方案3】尝试 Cobalt 转换节点与网页 Meta 抓取...")
            try {
                // 3a. Cobalt API
                val payload = JSONObject().apply {
                    put("url", "https://x.com/i/status/$statusId")
                }
                val body = RequestBody.create("application/json".toMediaType(), payload.toString())
                val cobaltReq = Request.Builder()
                    .url("https://api.cobalt.tools/")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .post(body)
                    .build()

                client.newCall(cobaltReq).execute().use { cobRes ->
                    val json = JSONObject(cobRes.body?.string() ?: "")
                    val url = json.optString("url")
                    if (url.startsWith("http")) {
                        videoUrl = url
                        AppLogger.log(context, "【X/Twitter-方案3】通过 Cobalt 接口解析成功")
                    }
                }

                // 3b. HTML Meta Tag Scraper
                if (videoUrl.isNullOrEmpty()) {
                    val pageReq = Request.Builder()
                        .url("https://fixupx.com/status/$statusId")
                        .header("User-Agent", USER_AGENT)
                        .build()
                    client.newCall(pageReq).execute().use { pageRes ->
                        val html = pageRes.body?.string() ?: ""
                        val metaRegex = Regex("""<meta\s+property="(?:og:video|og:video:url)"\s+content="([^"]+)"""")
                        val metaMatch = metaRegex.find(html)
                        if (metaMatch != null) {
                            videoUrl = metaMatch.groupValues[1]
                            AppLogger.log(context, "【X/Twitter-方案3】通过 Meta Tag 正则捕获成功")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(context, "【X/Twitter-方案3】失败: ${e.message}")
            }
        }

        if (videoUrl.isNullOrEmpty()) {
            AppLogger.log(context, "【X/Twitter】三种备用解析方案均未能提取到媒体链接")
            return false
        }

        return downloadStreamAndSave(
            context = context,
            mediaUrl = videoUrl!!,
            referer = "https://x.com/",
            outputUri = outputUri,
            isMp3 = isMp3,
            isAudioStreamDirect = false,
            onProgress = onProgress
        )
    }

    // ============================================================================
    // 3. YouTube 视频下载与三种以上解析备用方案
    // ============================================================================
                private suspend fun downloadYouTubeMultiSource(
        context: Context,
        rawUrl: String,
        outputUri: Uri,
        isMp3: Boolean,
        onProgress: ((Float, String) -> Unit)? = null
    ): Boolean {
        onProgress?.invoke(0.10f, "正在解析 YouTube 视频信息 (10%)...")
        AppLogger.log(context, "【YouTube】开始解析链接: $rawUrl")

        val videoId = extractYouTubeVideoId(rawUrl)
        if (videoId == null) {
            AppLogger.log(context, "【YouTube】无法识别有效的 YouTube Video ID")
            return false
        }
        AppLogger.log(context, "【YouTube】识别视频ID: $videoId")

        var mediaUrl: String? = null
        var isAudioStreamDirect = false

        // 方案 1: youtube-dl-android 内置引擎 — 使用 TubeKit 解析
        // 通过模拟 YouTube Player API 请求来获取视频流
        AppLogger.log(context, "【YouTube-方案1】尝试 YouTube Player API 直接解析...")
        try {
            // 模拟 YouTube 内嵌播放器请求，获取加密流
            val playerReq = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .header("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(RequestBody.create(
                    "application/json".toMediaType(),
                    JSONObject().apply {
                        put("videoId", videoId)
                        put("context", JSONObject().apply {
                            put("client", JSONObject().apply {
                                put("clientName", "ANDROID")
                                put("clientVersion", "19.09.37")
                                put("androidSdkVersion", 30)
                                put("hl", "en")
                            })
                        })
                    }.toString()
                ))
                .build()

            client.newCall(playerReq).execute().use { res ->
                if (res.isSuccessful) {
                    val json = JSONObject(res.body?.string() ?: "")
                    val streamingData = json.optJSONObject("streamingData")
                    if (streamingData != null) {
                        if (isMp3) {
                            val adapt = streamingData.optJSONArray("adaptiveFormats")
                            if (adapt != null) {
                                for (i in 0 until adapt.length()) {
                                    val item = adapt.getJSONObject(i)
                                    val mime = item.optString("mimeType", "")
                                    if (mime.contains("audio/mp4")) {
                                        mediaUrl = item.optString("url")
                                        if (!mediaUrl.isNullOrEmpty()) {
                                            isAudioStreamDirect = true
                                            break
                                        }
                                    }
                                }
                                if (mediaUrl.isNullOrEmpty()) {
                                    for (i in 0 until adapt.length()) {
                                        val item = adapt.getJSONObject(i)
                                        val mime = item.optString("mimeType", "")
                                        if (mime.contains("audio/")) {
                                            mediaUrl = item.optString("url")
                                            if (!mediaUrl.isNullOrEmpty()) {
                                                isAudioStreamDirect = true
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (mediaUrl.isNullOrEmpty()) {
                            val formats = streamingData.optJSONArray("formats")
                            if (formats != null && formats.length() > 0) {
                                for (i in 0 until formats.length()) {
                                    val item = formats.getJSONObject(i)
                                    mediaUrl = item.optString("url")
                                    if (!mediaUrl.isNullOrEmpty()) break
                                }
                            }
                            if (mediaUrl.isNullOrEmpty() && streamingData.has("formats")) {
                                // 尝试获取 cipher 加密流并手动解密
                                val expires = streamingData.optJSONObject("expiresInSeconds")
                                // 简化处理 - 用 adaptiveFormats 的 url 或 signatureCipher
                                if (!isMp3) {
                                    val adapt = streamingData.optJSONArray("adaptiveFormats")
                                    if (adapt != null) {
                                        for (i in 0 until adapt.length()) {
                                            val item = adapt.getJSONObject(i)
                                            val mime = item.optString("mimeType", "")
                                            if (mime.contains("video/mp4")) {
                                                var url = item.optString("url", "")
                                                if (url.isNullOrEmpty()) {
                                                    val cipher = item.optString("signatureCipher", "")
                                                    if (!cipher.isNullOrEmpty()) {
                                                        // 简化的 signature 处理
                                                        for (part in cipher.split("&")) {
                                                            if (part.startsWith("url=")) {
                                                                url = java.net.URLDecoder.decode(part.substring(4), "UTF-8")
                                                                break
                                                            }
                                                        }
                                                    }
                                                }
                                                if (!url.isNullOrEmpty() && url.startsWith("http")) {
                                                    mediaUrl = url
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (!mediaUrl.isNullOrEmpty()) {
                            AppLogger.log(context, "【YouTube-方案1】Player API 解析成功")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log(context, "【YouTube-方案1】失败: ${e.message}")
        }

        // 方案 2: 原生页面 HTML 解析 (备用)
        if (mediaUrl.isNullOrEmpty()) {
            AppLogger.log(context, "【YouTube-方案2】尝试原生页面 HTML 解析...")
            try {
                val pageReq = Request.Builder()
                    .url("https://www.youtube.com/watch?v=$videoId")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                client.newCall(pageReq).execute().use { res ->
                    val html = res.body?.string() ?: ""
                    val prRegex = Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});""")
                    val match = prRegex.find(html)
                    if (match != null) {
                        val json = JSONObject(match.groupValues[1])
                        val streamingData = json.optJSONObject("streamingData")
                        if (streamingData != null) {
                            if (isMp3 && streamingData.has("adaptiveFormats")) {
                                val adapt = streamingData.optJSONArray("adaptiveFormats")
                                if (adapt != null) {
                                    for (i in 0 until adapt.length()) {
                                        val item = adapt.getJSONObject(i)
                                        if (item.optString("mimeType", "").contains("audio/")) {
                                            mediaUrl = item.optString("url")
                                            if (!mediaUrl.isNullOrEmpty()) {
                                                isAudioStreamDirect = true
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                            if (mediaUrl.isNullOrEmpty() && streamingData.has("formats")) {
                                val formats = streamingData.optJSONArray("formats")
                                if (formats != null && formats.length() > 0) {
                                    mediaUrl = formats.getJSONObject(0).optString("url")
                                }
                            }
                            if (!mediaUrl.isNullOrEmpty()) {
                                AppLogger.log(context, "【YouTube-方案2】原生页面解析成功")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(context, "【YouTube-方案2】失败: ${e.message}")
            }
        }

        // 方案 3: 第三方解析 API (最后备用)
        if (mediaUrl.isNullOrEmpty()) {
            AppLogger.log(context, "【YouTube-方案3】尝试第三方解析 API...")
            try {
                val apiList = listOf(
                    "https://inv.nadeko.net/api/v1/videos/$videoId",
                    "https://pipedapi.kavin.rocks/streams/$videoId"
                )
                for (apiUrl in apiList) {
                    try {
                        val req = Request.Builder().url(apiUrl)
                            .header("User-Agent", USER_AGENT).build()
                        client.newCall(req).execute().use { res ->
                            if (res.isSuccessful) {
                                val json = JSONObject(res.body?.string() ?: "")
                                if (isMp3 && json.has("adaptiveFormats")) {
                                    val adapt = json.optJSONArray("adaptiveFormats")
                                    if (adapt != null && adapt.length() > 0) {
                                        mediaUrl = adapt.getJSONObject(0).optString("url")
                                        if (!mediaUrl.isNullOrEmpty()) isAudioStreamDirect = true
                                    }
                                }
                                if (mediaUrl.isNullOrEmpty() && json.has("formatStreams")) {
                                    val fs = json.optJSONArray("formatStreams")
                                    if (fs != null && fs.length() > 0) {
                                        mediaUrl = fs.getJSONObject(0).optString("url")
                                    }
                                }
                                if (mediaUrl.isNullOrEmpty() && json.has("videoStreams")) {
                                    val vs = json.optJSONArray("videoStreams")
                                    if (vs != null && vs.length() > 0) {
                                        mediaUrl = vs.getJSONObject(0).optString("url")
                                    }
                                }
                            }
                        }
                        if (!mediaUrl.isNullOrEmpty()) {
                            AppLogger.log(context, "【YouTube-方案3】第三方 API 解析成功")
                            break
                        }
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) {
                AppLogger.log(context, "【YouTube-方案3】失败: ${e.message}")
            }
        }

        if (mediaUrl.isNullOrEmpty()) {
            AppLogger.log(context, "【YouTube】所有方案均未能提取到媒体链接")
            return false
        }

        return downloadStreamAndSave(
            context = context,
            mediaUrl = mediaUrl!!,
            referer = "https://www.youtube.com/",
            outputUri = outputUri,
            isMp3 = isMp3,
            isAudioStreamDirect = isAudioStreamDirect,
            onProgress = onProgress
        )
    }

    // ============================================================================
    // 通用媒体下载流保存与转码逻辑
    // ============================================================================
    // internal：供单元测试直接校验 URL 门禁接线（Kotlin 测试源集可见）
    internal suspend fun downloadStreamAndSave(
        context: Context,
        mediaUrl: String,
        referer: String,
        outputUri: Uri,
        isMp3: Boolean,
        isAudioStreamDirect: Boolean,
        onProgress: ((Float, String) -> Unit)? = null
    ): Boolean {
        // 安全校验：解析得到的媒体直链同样必须指向公网 http(s) 地址
        if (!isPublicHttpUrl(mediaUrl)) {
            AppLogger.w(context, "媒体地址未通过安全校验，拒绝下载: $mediaUrl", "视频解析")
            onProgress?.invoke(0f, "错误: 媒体地址不安全，已拒绝下载")
            return false
        }
        AppLogger.log(context, "连接媒体服务地址: $mediaUrl")
        // 优化：添加完整的请求头以兼容各平台的防盗链机制
        val downloadReq = Request.Builder()
            .url(mediaUrl)
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Connection", "keep-alive")
            .build()

        // 输出流获取失败（目录授权失效/文件被删）必须显式失败，
        // 否则会空转下载后报"下载成功"
        fun openOutput(): OutputStream? {
            val out = context.contentResolver.openOutputStream(outputUri)
            if (out == null) {
                AppLogger.w(context, "无法打开输出文件（文件夹授权可能已失效）", "视频解析")
                onProgress?.invoke(0f, "错误: 无法写入输出文件，请在主界面重新选择下载文件夹")
            }
            return out
        }

        return try {
            client.newCall(downloadReq).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorMsg = when (response.code) {
                        403 -> "HTTP 403: 防盗链/风控拦截，可能需要更换请求来源"
                        404 -> "HTTP 404: 资源不存在，链接可能已失效"
                        429 -> "HTTP 429: 请求过于频繁，被限流"
                        in 500..599 -> "HTTP ${response.code}: 服务器内部错误，请稍后重试"
                        else -> "HTTP ${response.code}: 下载失败"
                    }
                    AppLogger.log(context, "下载媒体流失败: $errorMsg")
                    onProgress?.invoke(0f, "错误: $errorMsg")
                    return false
                }

                val body = response.body ?: return false
                val contentLength = body.contentLength()
                val contentType = body.contentType()?.toString()

                // MP4 downloads must never write a DASH audio response into a video document.
                if (!isMp3 && !isExpectedMp4ContentType(contentType)) {
                    AppLogger.log(context, "拒绝非视频响应作为 MP4 保存: Content-Type=$contentType")
                    onProgress?.invoke(0f, "错误: 服务返回音频流，已拒绝保存为 MP4，请重试")
                    return false
                }

                // Content-Length 可疑检测：1KB~100KB 可能是错误页面而非真实媒体
                if (!isAudioStreamDirect && contentLength in 1..100_000) {
                    AppLogger.log(context, "⚠️ Content-Length 仅 ${contentLength}B，可能是错误页面而非媒体文件，继续尝试下载...")
                    onProgress?.invoke(0f, "警告: 响应体仅 ${contentLength / 1024}KB，可能非媒体文件")
                }

                if (isMp3 && contentType?.substringBefore(';')?.trim()?.lowercase() == "audio/mpeg") {
                    // 场景：直链本身就是真 MP3 文件，直接落盘保存原始字节，
                    // 不再经过 MediaExtractor+MediaMuxer 重封装（MP3 轨道并非所有设备都支持封装进 MP4）。
                    AppLogger.log(context, "检测到 audio/mpeg 直链，直接保存原始 MP3")
                    val written = openOutput()?.use { out ->
                        body.byteStream().use { input ->
                            copyStreamWithProgress(
                                input = input,
                                output = out,
                                contentLength = contentLength,
                                basePct = 0.15f,
                                pctRange = 0.85f,
                                statusPrefix = "正在下载 MP3",
                                onProgress = onProgress
                            )
                        }
                    }
                    if (written == null) return false
                    onProgress?.invoke(1.0f, "MP3 保存完成 (100%)")
                    return true
                }

                if (isMp3 && !isAudioStreamDirect) {
                    // 场景：下载完整视频流，然后提取音频
                    val tempFile = File(context.cacheDir, "temp_download_${System.currentTimeMillis()}.mp4")
                    try {
                        tempFile.outputStream().use { fileOut ->
                            body.byteStream().use { input ->
                                copyStreamWithProgress(
                                    input = input,
                                    output = fileOut,
                                    contentLength = contentLength,
                                    basePct = 0.15f,
                                    pctRange = 0.70f,
                                    statusPrefix = "正在下载媒体源",
                                    onProgress = onProgress
                                )
                            }
                        }
                        AppLogger.log(context, "视频文件下载完毕，正在提取音频为M4A文件...")
                        onProgress?.invoke(0.85f, "视频下载完毕，开始音频封装 (85%)...")

                        val success = convertMp4ToAudio(
                            context = context,
                            inputUri = Uri.fromFile(tempFile),
                            outputUri = outputUri,
                            onProgress = onProgress,
                            basePct = 0.85f,
                            pctRange = 0.15f,
                            taskLabel = "封装M4A音频"
                        )
                        if (success) {
                            onProgress?.invoke(1.0f, "音频处理保存完成 (100%)")
                            AppLogger.log(context, "媒体转码与保存完全成功!")
                        } else {
                            AppLogger.log(context, "音频提取转码过程失败")
                        }
                        success
                    } finally {
                        tempFile.delete()
                    }
                } else if (isMp3 && isAudioStreamDirect) {
                    // 场景：B站/YouTube 等纯音频 DASH 流（m4a/aac）。
                    // 先下载到临时文件，再通过 MediaMuxer 封装为合法的 M4A/MP4 容器，
                    // 避免直接保存原始 DASH 分段导致播放器无法识别。
                    AppLogger.log(context, "检测到纯音频流(m4a/aac)，先下载再封装为 M4A 容器...")
                    val tempFile = File(context.cacheDir, "temp_audio_${System.currentTimeMillis()}.m4a")
                    try {
                        tempFile.outputStream().use { fileOut ->
                            body.byteStream().use { input ->
                                copyStreamWithProgress(
                                    input = input,
                                    output = fileOut,
                                    contentLength = contentLength,
                                    basePct = 0.15f,
                                    pctRange = 0.70f,
                                    statusPrefix = "正在下载纯音频",
                                    onProgress = onProgress
                                )
                            }
                        }
                        AppLogger.log(context, "纯音频下载完毕，正在封装为 M4A 容器...")
                        onProgress?.invoke(0.85f, "音频下载完毕，开始封装 (85%)...")

                        val success = convertMp4ToAudio(
                            context = context,
                            inputUri = Uri.fromFile(tempFile),
                            outputUri = outputUri,
                            onProgress = onProgress,
                            basePct = 0.85f,
                            pctRange = 0.15f,
                            taskLabel = "封装M4A音频"
                        )
                        if (success) {
                            onProgress?.invoke(1.0f, "M4A 音频封装完成 (100%)")
                            AppLogger.log(context, "纯音频流已成功封装为合法的 M4A 容器")
                            true
                        } else {
                            AppLogger.log(context, "M4A 封装失败，尝试直接保存原始流...")
                            // fallback: 直接保存原始流
                            val written = openOutput()?.use { out ->
                                tempFile.inputStream().use { input ->
                                    copyStreamWithProgress(
                                        input = input,
                                        output = out,
                                        contentLength = tempFile.length(),
                                        basePct = 0.85f,
                                        pctRange = 0.15f,
                                        statusPrefix = "直接保存原始音频",
                                        onProgress = onProgress
                                    )
                                }
                            }
                            if (written == null) return false
                            onProgress?.invoke(1.0f, "原始音频已直接保存 (100%)")
                            AppLogger.log(context, "已直接保存原始音频流（可能部分播放器不兼容）")
                            true
                        }
                    } finally {
                        tempFile.delete()
                    }
                } else {
                    val written = openOutput()?.use { out ->
                        body.byteStream().use { input ->
                            copyStreamWithProgress(
                                input = input,
                                output = out,
                                contentLength = contentLength,
                                basePct = 0.15f,
                                pctRange = 0.85f,
                                statusPrefix = if (isAudioStreamDirect) "正在下载纯音频" else "正在下载视频数据",
                                onProgress = onProgress
                            )
                        }
                    }
                    if (written == null) return false
                    onProgress?.invoke(1.0f, "文件下载保存成功 (100%)")
                    AppLogger.log(context, "文件已成功写入保存路径!")
                    true
                }
            }
        } catch (e: UnsafeRedirectException) {
            // 重定向链中出现内网/非公网目标：先于通用 IOException 捕获，给出与初始门禁一致的安全提示
            AppLogger.w(context, "重定向目标未通过安全校验，已中止下载: ${e.message}", "视频解析")
            onProgress?.invoke(0f, "错误: 重定向目标不安全，已拒绝下载")
            false
        } catch (e: java.io.IOException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 连接层失败（DNS/超时/被墙/代理拒绝）单独给出可行动提示
            AppLogger.w(context, "媒体下载连接/写入失败: ${e.message}", "视频解析")
            onProgress?.invoke(0f, "错误: 无法连接媒体服务器（网络受限或资源被屏蔽，可在个性化设置中配置下载代理）")
            false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.log(context, "网络数据传输或写盘时抛出异常: ${e.message}")
            false
        }
    }
}
