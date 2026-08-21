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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

object NetworkDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)  // 大文件下载友好：CDN 偶发停顿不应导致超时
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val MOBILE_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    // ===== B站 buvid3 缓存（用于绕过 412 风控） =====
    @Volatile
    private var cachedBuvid3: String? = null
    private var buvid3FetchTime: Long = 0L
    private const val BUVID3_CACHE_TTL_MS = 30 * 60 * 1000L // 30 分钟有效期

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

        // 3. Direct AV match (e.g. av123456)
        val avMatch = Regex("""av\d+""", RegexOption.IGNORE_CASE).find(cleaned)
        if (avMatch != null) {
            return "https://www.bilibili.com/video/${avMatch.value}"
        }

        // 4. Domain match without scheme
        val domainMatch = Regex("""([a-zA-Z0-9-]+\.[a-zA-Z]{2,})/[^\s\u4e00-\u9fa5"'<>]+""", RegexOption.IGNORE_CASE).find(cleaned)
        if (domainMatch != null) {
            return "https://${domainMatch.value}".trimEnd(',', '.', ';', '!', '?', '，', '。', '！', '？', '；', '）', ')', '】', ']', '}', '\"', '\'')
        }

        return cleaned
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
                    lastReportTime = now
                    lastReportPct = totalPct
                    val mbRead = String.format(java.util.Locale.getDefault(), "%.1f MB", totalBytesRead / (1024f * 1024f))
                    val totalMb = if (contentLength > 0) String.format(java.util.Locale.getDefault(), " / %.1f MB", contentLength / (1024f * 1024f)) else ""
                    val pctInt = (totalPct * 100).toInt().coerceIn(0, 100)

                    onProgress(totalPct, "$statusPrefix: $pctInt% ($mbRead$totalMb)")
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
                if (cleanUrl.contains("bilibili.com") || cleanUrl.contains("b23.tv") || cleanUrl.contains("BV") || cleanUrl.contains("av")) {
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
        onProgress: ((Float, String) -> Unit)? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val inputUrl = sanitizeUrlInput(url)
                AppLogger.log(context, "智能分析视频链接: '$url' -> '$inputUrl'")
                onProgress?.invoke(0.05f, "正在分析视频链接 (5%)...")

                if (inputUrl.contains("bilibili.com") || inputUrl.contains("b23.tv") || inputUrl.contains("BV") || inputUrl.contains("av")) {
                    return@withContext downloadBilibiliMultiSource(context, inputUrl, outputUri, isMp3, onProgress)
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

        val avRegex = Regex("av(\\d+)", RegexOption.IGNORE_CASE)
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
    // 1. 哔哩哔哩 (Bilibili) 预留三种以上解析备用方案
    // ============================================================================
    private suspend fun downloadBilibiliMultiSource(
        context: Context, 
        rawUrl: String, 
        outputUri: Uri, 
        isMp3: Boolean,
        onProgress: ((Float, String) -> Unit)? = null
    ): Boolean {
        onProgress?.invoke(0.08f, "正在解析B站视频信息 (8%)...")
        AppLogger.log(context, "【B站】开始解析链接: $rawUrl")
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
        if (bvid != null) {
            AppLogger.log(context, "【B站-方案1】尝试网页 HTML window.__playinfo__ 直接提取...")
            try {
                val buvid3 = fetchBuvid3(context)
                val pageReq = Request.Builder()
                    .url("https://www.bilibili.com/video/$bvid/")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Cookie", "buvid3=$buvid3; CURRENT_FNVAL=16")
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
                                if (data.has("durl")) {
                                    val durl = data.optJSONArray("durl")
                                    if (durl != null && durl.length() > 0) {
                                        mediaUrl = durl.getJSONObject(0).optString("url")
                                        AppLogger.log(context, "【B站-方案1】成功获取durl视频流")
                                    }
                                } else if (data.has("dash")) {
                                    val dash = data.optJSONObject("dash")
                                    val videoArray = dash?.optJSONArray("video")
                                    if (videoArray != null && videoArray.length() > 0) {
                                        mediaUrl = videoArray.getJSONObject(0).optString("baseUrl")
                                        AppLogger.log(context, "【B站-方案1】成功获取dash视频流")
                                    }
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
                val infoRequest = Request.Builder()
                    .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Cookie", "buvid3=$buvid3")
                    .build()

                client.newCall(infoRequest).execute().use { infoResponse ->
                    val infoJson = JSONObject(infoResponse.body?.string() ?: "")
                    if (infoJson.optInt("code") == 0) {
                        val cid = infoJson.getJSONObject("data").getLong("cid")
                        
                        // 2a. Standard playurl API
                        val playUrlReq = Request.Builder()
                            .url("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=64&fnval=1")
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", "https://www.bilibili.com/")
                            .header("Cookie", "buvid3=$buvid3")
                            .build()
                        client.newCall(playUrlReq).execute().use { playRes ->
                            val playJson = JSONObject(playRes.body?.string() ?: "")
                            if (playJson.optInt("code") == 0) {
                                val durlArray = playJson.optJSONObject("data")?.optJSONArray("durl")
                                if (durlArray != null && durlArray.length() > 0) {
                                    mediaUrl = durlArray.getJSONObject(0).optString("url")
                                    AppLogger.log(context, "【B站-方案2】通过官方 Web PlayURL 成功抓取视频流")
                                }
                            }
                        }

                        // 2b. HTML5 Mobile PlayURL API fallback
                        if (mediaUrl.isNullOrEmpty()) {
                            val h5Req = Request.Builder()
                                .url("https://api.bilibili.com/x/v2/dm/playurl?bvid=$bvid&cid=$cid&qn=64&platform=html5")
                                .header("User-Agent", MOBILE_USER_AGENT)
                                .header("Referer", "https://m.bilibili.com/")
                                .build()
                            client.newCall(h5Req).execute().use { h5Res ->
                                val h5Json = JSONObject(h5Res.body?.string() ?: "")
                                if (h5Json.optInt("code") == 0) {
                                    val durlArray = h5Json.optJSONObject("data")?.optJSONObject("durl")?.optJSONArray("url")
                                    if (durlArray != null && durlArray.length() > 0) {
                                        mediaUrl = durlArray.getString(0)
                                        AppLogger.log(context, "【B站-方案2】通过移动端 HTML5 PlayURL 成功抓取")
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

        // 方案 3: 开放多渠道第三方换源 API (备用方案 3)
        if (mediaUrl.isNullOrEmpty()) {
            AppLogger.log(context, "【B站-方案3】尝试开放第三方多渠道换源解析...")
            val videoTargetUrl = if (bvid != null) "https://www.bilibili.com/video/$bvid" else resolvedUrl
            val openApis = listOf(
                "https://api.injahow.cn/bparse/?bv=${bvid ?: ""}&format=mp4",
                "https://api.xinrance.com/api/bilibili?url=$videoTargetUrl",
                "https://api.vvhan.com/api/bilibili?url=$videoTargetUrl",
                "https://tenapi.cn/v2/bilibili?url=$videoTargetUrl",
                "https://api.pearktrue.cn/api/bilibili/?url=$videoTargetUrl"
            )
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
    private suspend fun downloadStreamAndSave(
        context: Context,
        mediaUrl: String,
        referer: String,
        outputUri: Uri,
        isMp3: Boolean,
        isAudioStreamDirect: Boolean,
        onProgress: ((Float, String) -> Unit)? = null
    ): Boolean {
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

                // Content-Length 可疑检测：1KB~100KB 可能是错误页面而非真实媒体
                if (!isAudioStreamDirect && contentLength in 1..100_000) {
                    AppLogger.log(context, "⚠️ Content-Length 仅 ${contentLength}B，可能是错误页面而非媒体文件，继续尝试下载...")
                    onProgress?.invoke(0f, "警告: 响应体仅 ${contentLength / 1024}KB，可能非媒体文件")
                }

                if (isMp3 && !isAudioStreamDirect) {
                    // 场景：下载完整视频流，然后提取音频
                    val tempFile = File(context.cacheDir, "temp_download_${System.currentTimeMillis()}.mp4")
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
                    AppLogger.log(context, "视频文件下载完毕，正在提取音频为MP3文件...")
                    onProgress?.invoke(0.85f, "视频下载完毕，开始音频提取 (85%)...")
                    
                    val success = convertMp4ToAudio(
                        context = context, 
                        inputUri = Uri.fromFile(tempFile), 
                        outputUri = outputUri,
                        onProgress = onProgress,
                        basePct = 0.85f,
                        pctRange = 0.15f,
                        taskLabel = "提取MP3音频"
                    )
                    tempFile.delete()
                    if (success) {
                        onProgress?.invoke(1.0f, "音频处理保存完成 (100%)")
                        AppLogger.log(context, "媒体转码与保存完全成功!")
                    } else {
                        AppLogger.log(context, "音频提取转码过程失败")
                    }
                    success
                } else if (isMp3 && isAudioStreamDirect) {
                    // 场景：B站/YouTube 等纯音频 DASH 流（m4a/aac）。
                    // 修复：先下载到临时文件，再通过 MediaMuxer 封装为合法的 M4A/MP4 容器，
                    // 避免直接保存原始 DASH 分段导致播放器无法识别。
                    AppLogger.log(context, "检测到纯音频流(m4a/aac)，先下载再封装为 M4A 容器...")
                    val tempFile = File(context.cacheDir, "temp_audio_${System.currentTimeMillis()}.m4a")
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
                    tempFile.delete()
                    if (success) {
                        onProgress?.invoke(1.0f, "M4A 音频封装完成 (100%)")
                        AppLogger.log(context, "纯音频流已成功封装为合法的 M4A 容器")
                        true
                    } else {
                        AppLogger.log(context, "M4A 封装失败，尝试直接保存原始流...")
                        // fallback: 直接保存原始流
                        context.contentResolver.openOutputStream(outputUri)?.use { out ->
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
                        onProgress?.invoke(1.0f, "原始音频已直接保存 (100%)")
                        AppLogger.log(context, "已直接保存原始音频流（可能部分播放器不兼容）")
                        true
                    }
                } else {
                    context.contentResolver.openOutputStream(outputUri)?.use { out ->
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
                    onProgress?.invoke(1.0f, "文件下载保存成功 (100%)")
                    AppLogger.log(context, "文件已成功写入保存路径!")
                    true
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.log(context, "网络数据传输或写盘时抛出异常: ${e.message}")
            false
        }
    }
}
