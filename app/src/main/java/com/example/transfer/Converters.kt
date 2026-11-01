package com.example.transfer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.NetworkDownloader
import com.example.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

// ============================================================================
// 通用转换与辅助逻辑（原 MainActivity 迁移至此，供前台服务与 UI 共用）
// ============================================================================

fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        }
    }
    return result ?: uri.path?.substringAfterLast('/') ?: "unknown"
}

fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

fun getBatchFileSizeText(context: Context, uris: List<Uri>): String {
    if (uris.isEmpty()) return ""
    var totalBytes = 0L
    for (uri in uris) {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    totalBytes += cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) { }
    }
    return if (totalBytes > 0) {
        val mb = totalBytes / (1024f * 1024f)
        String.format(Locale.getDefault(), "已选择 %d 个文件 (共约 %.1f MB)", uris.size, mb)
    } else {
        "已选择 ${uris.size} 个文件"
    }
}

fun suggestFileName(url: String, extension: String, customTitle: String? = null): String {
    if (!customTitle.isNullOrBlank()) {
        return NetworkDownloader.sanitizeFileName(customTitle, extension)
    }
    val cleanUrl = NetworkDownloader.sanitizeUrlInput(url)
    val bvMatch = Regex("""BV[a-zA-Z0-9]{10}""", RegexOption.IGNORE_CASE).find(cleanUrl)
    if (bvMatch != null) {
        return "bilibili_${bvMatch.value}.$extension"
    }
    val twitterMatch = Regex("""status/(\d+)""").find(cleanUrl)
    if (twitterMatch != null) {
        return "x_video_${twitterMatch.groupValues[1]}.$extension"
    }
    val ytMatch = Regex("""(?:v=|youtu\.be/|shorts/|embed/)([a-zA-Z0-9_-]{11})""").find(cleanUrl)
    if (ytMatch != null) {
        return "youtube_${ytMatch.groupValues[1]}.$extension"
    }
    val rawFileName = cleanUrl.substringAfterLast('/').substringBefore('?').substringBefore('#')
    if (rawFileName.isNotBlank() && rawFileName.length in 3..40 && !rawFileName.contains(".")) {
        return "${rawFileName}.$extension"
    }
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    return "media_download_$timeStamp.$extension"
}

suspend fun doConversionWithRetry(
    context: Context,
    inputUri: Uri,
    outputUri: Uri,
    converter: suspend (Context, Uri, Uri) -> Boolean
): Boolean {
    val fileName = getFileName(context, inputUri)
    AppLogger.i(context, "开始转换任务: $fileName", "格式转换")
    var retries = 0
    var success = false
    while (retries < 3 && !success) {
        if (!currentCoroutineContext().isActive) break
        success = converter(context, inputUri, outputUri)
        if (!success) {
            retries++
            AppLogger.w(context, "转换尝试失败 ($retries/3): $fileName", "格式转换")
        }
    }
    if (!success) {
        AppLogger.e(context, "转换彻底失败(重试3次): $fileName", "格式转换")
    } else {
        AppLogger.s(context, "转换处理成功: $fileName", "格式转换")
    }
    return success
}

suspend fun doVideoDownloadWithRetry(
    context: Context,
    url: String,
    outputUri: Uri,
    isAudio: Boolean,
    cid: Long? = null,
    onProgress: ((Float, String) -> Unit)? = null
): Boolean {
    val typeName = if (isAudio) "音频(M4A)" else "视频(MP4)"
    AppLogger.i(context, "开始解析与下载$typeName: $url${cid?.let { " (P cid=$it)" } ?: ""}", "视频解析")
    var retries = 0
    var success = false
    while (retries < 3 && !success) {
        if (!currentCoroutineContext().isActive) break
        success = NetworkDownloader.downloadVideo(context, url, outputUri, isAudio, onProgress, cid)
        if (!success) {
            retries++
            if (retries < 3) {
                AppLogger.w(context, "解析下载重试 ($retries/3): $url，等待 1.5 秒后重试...", "视频解析")
                kotlinx.coroutines.delay(1500L)  // 重试间隔：给网络恢复时间
            }
        }
    }
    if (!success) {
        AppLogger.e(context, "解析下载彻底失败(重试3次): $url", "视频解析")
    } else {
        AppLogger.s(context, "解析下载成功: $url", "视频解析")
    }
    return success
}

// ============================================================================
// EPUB → TXT
// ============================================================================

suspend fun convertEpubToTxt(
    context: Context,
    inputUri: Uri,
    outputUri: Uri,
    onProgress: ((Float, String) -> Unit)? = null,
    basePct: Float = 0f,
    pctRange: Float = 1f,
    taskLabel: String = "EPUB转换"
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            contentResolver.openInputStream(inputUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                        var zipEntry = zis.nextEntry
                        var chapterIndex = 0
                        while (zipEntry != null) {
                            currentCoroutineContext().ensureActive()
                            val name = zipEntry.name.lowercase()
                            if (name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml")) {
                                chapterIndex++
                                val rawHtml = zis.readBytes().toString(Charsets.UTF_8)
                                // 写入章节标题分隔行，提升 TXT 可读性
                                val chapterTitle = extractChapterTitle(rawHtml)
                                val header = buildString {
                                    append("━━━━━━ 第 ").append(chapterIndex).append(" 章")
                                    if (!chapterTitle.isNullOrBlank()) append(" · ").append(chapterTitle)
                                    append(" ━━━━━━").append("\n\n")
                                }
                                outputStream.write(header.toByteArray(Charsets.UTF_8))
                                outputStream.write(extractTextFromHtml(rawHtml).toByteArray(Charsets.UTF_8))
                                outputStream.write("\n\n".toByteArray(Charsets.UTF_8))

                                if (onProgress != null) {
                                    val localPct = (1f - Math.exp(-chapterIndex.toDouble() / 15.0).toFloat()).coerceIn(0.1f, 0.95f)
                                    val totalPct = basePct + (localPct * pctRange)
                                    val pctInt = (totalPct * 100).toInt().coerceIn(0, 100)
                                    onProgress(totalPct, "$taskLabel: $pctInt% (已处理 $chapterIndex 章节)")
                                }
                            }
                            zipEntry = zis.nextEntry
                        }
                        if (onProgress != null) {
                            val totalPct = basePct + pctRange
                            val pctInt = (totalPct * 100).toInt().coerceIn(0, 100)
                            onProgress(totalPct, "$taskLabel: $pctInt%")
                        }
                        return@withContext true
                    }
                }
            }
            false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.log(context, "EPUB转TXT发生异常: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}

fun extractTextFromHtml(html: String): String {
    var text = html.replace(Regex("<head[^>]*>.*?</head>", RegexOption.DOT_MATCHES_ALL), "")
    text = text.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
    text = text.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
    text = text.replace(Regex("<[^>]+>"), " ")
    text = text.replace(Regex("&nbsp;"), " ")
    text = text.replace(Regex("&lt;"), "<")
    text = text.replace(Regex("&gt;"), ">")
    text = text.replace(Regex("&amp;"), "&")
    text = text.replace(Regex("&quot;"), "\"")
    text = text.replace(Regex("&#39;"), "'")
    text = text.replace(Regex("&mdash;"), "—")
    text = text.replace(Regex("&hellip;"), "…")
    return text.replace(Regex("\\s+"), " ").trim()
}

/** 提取章节标题：优先 <title>，其次 <h1>/<h2>/<h3>；无则返回 null。 */
internal fun extractChapterTitle(html: String): String? {
    val patterns = listOf(
        Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE),
        Regex("""<h[1-3][^>]*>(.*?)</h[1-3]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    )
    for (pattern in patterns) {
        pattern.find(html)?.let { match ->
            val title = match.groupValues[1]
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (title.isNotBlank()) return title.take(80)
        }
    }
    return null
}

// ============================================================================
// MP4 → M4A（提取音频轨并封装）
// ============================================================================

suspend fun convertMp4ToAudio(
    context: Context,
    inputUri: Uri,
    outputUri: Uri,
    onProgress: ((Float, String) -> Unit)? = null,
    basePct: Float = 0f,
    pctRange: Float = 1f,
    taskLabel: String = "音频提取"
): Boolean {
    return withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var outPfd: ParcelFileDescriptor? = null
        var extractor: android.media.MediaExtractor? = null
        var muxer: android.media.MediaMuxer? = null
        try {
            val contentResolver = context.contentResolver
            pfd = contentResolver.openFileDescriptor(inputUri, "r") ?: return@withContext false
            extractor = android.media.MediaExtractor()
            extractor.setDataSource(pfd.fileDescriptor)

            var audioTrackIndex = -1
            var audioFormat: android.media.MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                AppLogger.log(context, "未找到可提取的音频轨道")
                return@withContext false
            }

            extractor.selectTrack(audioTrackIndex)

            val durationUs = if (audioFormat.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(android.media.MediaFormat.KEY_DURATION)
            } else {
                -1L
            }

            outPfd = contentResolver.openFileDescriptor(outputUri, "w") ?: return@withContext false

            muxer = android.media.MediaMuxer(outPfd.fileDescriptor, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val maxChunkSize = if (audioFormat.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
                val size = audioFormat.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)
                if (size > 0) size else 1024 * 1024
            } else {
                1024 * 1024
            }

            val buffer = ByteBuffer.allocate(maxChunkSize)
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var processedChunks = 0L

            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val chunkSize = extractor.readSampleData(buffer, 0)
                if (chunkSize < 0) {
                    break
                }
                bufferInfo.size = chunkSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                bufferInfo.offset = 0

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()

                processedChunks++
                if (processedChunks % 15 == 0L && onProgress != null) {
                    val sampleTimeUs = bufferInfo.presentationTimeUs
                    val localPct = if (durationUs > 0) {
                        (sampleTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                    } else {
                        (1f - Math.exp(-processedChunks.toDouble() / 800.0).toFloat()).coerceIn(0f, 0.98f)
                    }
                    val totalPct = basePct + (localPct * pctRange)
                    val pctInt = (totalPct * 100).toInt().coerceIn(0, 100)
                    onProgress(totalPct, "$taskLabel: $pctInt%")
                }
            }

            if (onProgress != null) {
                val totalPct = basePct + pctRange
                val pctInt = (totalPct * 100).toInt().coerceIn(0, 100)
                onProgress(totalPct, "$taskLabel: $pctInt%")
            }

            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.log(context, "提取音频过程失败: ${e.message}")
            e.printStackTrace()
            false
        } finally {
            try { muxer?.stop() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
            try { extractor?.release() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
            try { outPfd?.close() } catch (e: Exception) {}
        }
    }
}

// ============================================================================
// WebP → JPG
// ============================================================================

/**
 * 通过文件头判断是否为动态 WebP（RIFF....WEBP 容器内含 ANIM/ANMF chunk）。
 * 动图转 JPG 只会保留第一帧，调用方应据此提醒用户。
 */
internal fun isAnimatedWebP(header: ByteArray): Boolean {
    if (header.size < 16) return false
    val riff = header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()
    val webp = header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
    if (!riff || !webp) return false
    val tail = String(header, 12, header.size - 12, Charsets.US_ASCII)
    return tail.contains("ANIM") || tail.contains("ANMF")
}

/** JPEG 不支持透明像素：带 alpha 的位图先铺白底再合成，避免透明区域变黑。 */
internal fun flattenOntoWhite(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val rgb = android.graphics.Bitmap.createBitmap(
        bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(rgb)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    return rgb
}

/**
 * 解码图片字节（WebP/无损WebP/动图WebP首帧/PNG 等）。
 * API 28+ 优先 ImageDecoder：能解动态 WebP 首帧、无损与扩展格式；
 * 否则回退 BitmapFactory（动态 WebP 在旧系统上会返回 null）。
 * 超大图按采样率缩读，防止 OOM。
 */
internal fun decodeImageBitmap(bytes: ByteArray): android.graphics.Bitmap? {
    val maxPixels = 40_000_000L // 约 40MP 上限，防止 1 亿像素图直接 OOM
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        try {
            val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val pixels = info.size.width.toLong() * info.size.height.toLong()
                if (pixels > maxPixels) {
                    var sample = 1
                    while (pixels / (sample.toLong() * sample.toLong()) > maxPixels) sample++
                    decoder.setTargetSampleSize(sample)
                }
                // JPEG 压缩与白底合成需要可读取的软件位图
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            // ImageDecoder 拒绝的文件继续走 BitmapFactory 尝试
        }
    }

    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
            while (pixels / (sample.toLong() * sample.toLong()) > maxPixels) sample++
        }
        android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (e: Exception) {
        null
    }
}

suspend fun convertWebpToJpg(
    context: Context,
    inputUri: Uri,
    outputUri: Uri,
    onProgress: ((Float, String) -> Unit)? = null,
    basePct: Float = 0f,
    pctRange: Float = 1f,
    taskLabel: String = "WebP转JPG"
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke(basePct, "$taskLabel: 0%")

            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: run {
                    AppLogger.log(context, "WebP 转 JPG 失败: 无法读取输入文件")
                    return@withContext false
                }
            val bytes = inputStream.use { it.readBytes() }

            if (isAnimatedWebP(bytes)) {
                AppLogger.log(context, "检测到动态 WebP，转 JPG 将仅保留第一帧")
                onProgress?.invoke(basePct, "$taskLabel: 检测到动图，仅保留第一帧")
            }

            val bitmap = decodeImageBitmap(bytes)

            if (bitmap == null) {
                AppLogger.log(context, "WebP 解码失败: 无法解析图片文件（可能已损坏或为不支持的格式）")
                return@withContext false
            }

            onProgress?.invoke(basePct + pctRange * 0.5f, "$taskLabel: 50% (编码中)")

            val outputStream = context.contentResolver.openOutputStream(outputUri)
            if (outputStream == null) {
                AppLogger.log(context, "WebP 转 JPG 失败: 无法写入输出文件（文件夹授权可能已失效）")
                bitmap.recycle()
                return@withContext false
            }

            val flat = flattenOntoWhite(bitmap)
            val ok = flat.compress(
                android.graphics.Bitmap.CompressFormat.JPEG,
                90,
                outputStream
            )
            outputStream.flush()
            outputStream.close()
            if (flat !== bitmap) flat.recycle()
            bitmap.recycle()
            if (!ok) {
                AppLogger.log(context, "WebP 转 JPG 失败: JPEG 编码写入失败")
                return@withContext false
            }

            onProgress?.invoke(basePct + pctRange, "$taskLabel: 100%")
            AppLogger.log(context, "WebP 转 JPG 成功")
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.log(context, "WebP 转 JPG 失败: ${e.message}")
            false
        }
    }
}
