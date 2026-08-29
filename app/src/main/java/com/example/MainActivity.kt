package com.example

import android.net.Uri

import android.content.Context
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemeMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.BrightnessAuto
import com.example.viewmodel.MainViewModel
import com.example.viewmodel.BackgroundType
import com.example.viewmodel.BackgroundSettings
import com.example.viewmodel.AppIconType
import com.example.data.ConversionHistory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Schedule
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import com.example.ui.LogViewerDialog
import com.example.utils.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import java.util.concurrent.atomic.AtomicLong

// ===== 下载队列数据模型 =====
enum class DownloadStatus { WAITING, RUNNING, DONE, FAILED }

data class DownloadTask(
    val id: Long,
    val url: String,
    val isAudio: Boolean,   // true=音频(M4A) false=视频(MP4)
    val fileName: String,
    val status: DownloadStatus,
    val progress: Float,
    val message: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            MyApplicationTheme(themeMode = themeMode) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ConverterApp(modifier = Modifier.padding(innerPadding), viewModel = viewModel)
                }
            }
        }
    }
}

// 统一的功能分区卡片：大圆角 + 图标徽章标题
private val SectionCardShape = RoundedCornerShape(20.dp)

@Composable
fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterApp(modifier: Modifier = Modifier, viewModel: MainViewModel = viewModel()) {
    var epubUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var mp4Uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var webpUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    var videoUrl by remember { mutableStateOf("") }
    var fetchedVideoTitle by remember { mutableStateOf<String?>(null) }
    var isFetchingVideoTitle by remember { mutableStateOf(false) }
    var customVideoTitle by remember { mutableStateOf("") }
    // 记录已成功抓取过标题的 URL，避免重复请求（选歌、二次粘贴等场景直接复用）
    var titleFetchedForUrl by remember { mutableStateOf("") }

    // 歌曲搜索下载状态
    var songQuery by remember { mutableStateOf("") }
    var songResults by remember { mutableStateOf<List<NetworkDownloader.SongResult>>(emptyList()) }
    var isSongSearching by remember { mutableStateOf(false) }
    var showSongResults by remember { mutableStateOf(false) }
    var songToast by remember { mutableStateOf("") }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(videoUrl) {
        if (videoUrl.isBlank()) {
            fetchedVideoTitle = null
            customVideoTitle = ""
            isFetchingVideoTitle = false
            return@LaunchedEffect
        }
        // 同一链接已抓到过标题则不再请求
        if (videoUrl == titleFetchedForUrl && fetchedVideoTitle != null) return@LaunchedEffect
        // 防抖：等待输入停顿后再抓取，避免每敲一个字符就发一次网络请求
        kotlinx.coroutines.delay(700)
        if (videoUrl.isBlank() || (videoUrl == titleFetchedForUrl && fetchedVideoTitle != null)) return@LaunchedEffect
        isFetchingVideoTitle = true
        val title = NetworkDownloader.fetchVideoTitle(context, videoUrl)
        if (title != null) {
            fetchedVideoTitle = title
            customVideoTitle = title
            titleFetchedForUrl = videoUrl
        }
        isFetchingVideoTitle = false
    }
    var currentJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var currentTaskName by remember { mutableStateOf("") }
    var taskProgress by remember { mutableFloatStateOf(0f) }
    var taskProgressText by remember { mutableStateOf("") }

    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val historyList by viewModel.historyState.collectAsStateWithLifecycle()
    val bgSettings by viewModel.bgSettings.collectAsStateWithLifecycle()
    val currentIcon by viewModel.currentIcon.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    var selectedHistoryIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showBgSettingsSheet by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var logDialogText by remember { mutableStateOf("") }
    var selectedFilterTag by remember { mutableStateOf("全部") }
    val animatedProgress by animateFloatAsState(
        targetValue = taskProgress.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.8f,
            stiffness = 300f
        ),
        label = "task_progress_anim"
    )

    val updateProgressOnMain = remember(coroutineScope) {
        { pct: Float, status: String ->
            coroutineScope.launch(Dispatchers.Main) {
                taskProgress = pct
                taskProgressText = status
            }
            Unit
        }
    }

    // ===== 下载队列状态 =====
    var downloadQueue by remember { mutableStateOf<List<DownloadTask>>(emptyList()) }
    var queueRunning by remember { mutableStateOf(false) }
    var queueFolderUri by remember { mutableStateOf<Uri?>(null) }
    var queueFolderName by remember { mutableStateOf("") }
    val queueTaskIdCounter = remember { AtomicLong(0) }
    val queueWorkerJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val isConverting = currentJob != null || queueRunning

    fun clearQueue() {
        downloadQueue = downloadQueue.filter { it.status == DownloadStatus.RUNNING }
    }

    fun startQueueWorker() {
        if (queueRunning) return
        val folderUri = queueFolderUri ?: return
        queueRunning = true
        val job = coroutineScope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val nextTask = downloadQueue.firstOrNull { it.status == DownloadStatus.WAITING }
                        ?: break
                    // Update status to RUNNING on main thread
                    withContext(Dispatchers.Main) {
                        downloadQueue = downloadQueue.map {
                            if (it.id == nextTask.id) it.copy(status = DownloadStatus.RUNNING, progress = 0f, message = "准备下载...")
                            else it
                        }
                    }
                    // Create output file in selected folder
                    val docDir = DocumentFile.fromTreeUri(context, folderUri)
                    val mimeType = if (nextTask.isAudio) "audio/mp4" else "video/mp4"
                    val docFile = docDir?.createFile(mimeType, nextTask.fileName)
                    if (docFile == null) {
                        withContext(Dispatchers.Main) {
                            downloadQueue = downloadQueue.map {
                                if (it.id == nextTask.id) it.copy(status = DownloadStatus.FAILED, message = "无法创建文件")
                                else it
                            }
                        }
                        continue
                    }
                    // Update progress callback for this task
                    val onQueueProgress: (Float, String) -> Unit = { pct, status ->
                        val taskId = nextTask.id
                        coroutineScope.launch(Dispatchers.Main) {
                            downloadQueue = downloadQueue.map {
                                if (it.id == taskId) it.copy(progress = pct, message = status)
                                else it
                            }
                        }
                        Unit
                    }
                    // Also update the main progress card
                    val onMainProgress: (Float, String) -> Unit = { pct, status ->
                        coroutineScope.launch(Dispatchers.Main) {
                            taskProgress = pct
                            taskProgressText = "[队列] ${nextTask.fileName}: $status"
                        }
                        Unit
                    }
                    val combinedProgress: (Float, String) -> Unit = { pct, status ->
                        onQueueProgress(pct, status)
                        onMainProgress(pct, status)
                        Unit
                    }
                    try {
                        coroutineScope.launch(Dispatchers.Main) {
                            currentTaskName = "队列下载: ${nextTask.fileName}"
                            taskProgress = 0f
                            taskProgressText = "正在下载..."
                        }
                        val success = doVideoDownloadWithRetry(
                            context = context,
                            url = nextTask.url,
                            outputUri = docFile.uri,
                            isAudio = nextTask.isAudio,
                            onProgress = combinedProgress
                        )
                        val resultStatus = if (success) DownloadStatus.DONE else DownloadStatus.FAILED
                        val resultMsg = if (success) "下载完成" else "下载失败(已重试3次)"
                        viewModel.addHistory(
                            nextTask.fileName,
                            if (nextTask.isAudio) "下载音频(M4A)" else "下载视频(MP4)",
                            success,
                            if (success) docFile.uri.toString() else null
                        )
                        withContext(Dispatchers.Main) {
                            downloadQueue = downloadQueue.map {
                                if (it.id == nextTask.id) it.copy(status = resultStatus, progress = if (success) 1f else it.progress, message = resultMsg)
                                else it
                            }
                            if (success) {
                                Toast.makeText(context, "✅ ${nextTask.fileName} 下载完成", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        withContext(Dispatchers.Main) {
                            downloadQueue = downloadQueue.map {
                                if (it.id == nextTask.id) it.copy(status = DownloadStatus.FAILED, message = "异常: ${e.message}")
                                else it
                            }
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    queueRunning = false
                    queueWorkerJob.value = null
                    // Clear main progress if queue is done
                    if (downloadQueue.none { it.status == DownloadStatus.RUNNING }) {
                        taskProgress = 0f
                        taskProgressText = ""
                    }
                    // 竞态保护：worker 退出的同时若有新任务入队（入队时 queueRunning 仍为 true），
                    // 任务会永久滞留 WAITING，这里兜底重启 worker
                    if (downloadQueue.any { it.status == DownloadStatus.WAITING }) {
                        startQueueWorker()
                    }
                }
            }
        }
        queueWorkerJob.value = job
    }

    fun addToQueue(url: String, isAudio: Boolean, fileName: String) {
        val id = queueTaskIdCounter.incrementAndGet()
        val task = DownloadTask(
            id = id,
            url = url,
            isAudio = isAudio,
            fileName = fileName,
            status = DownloadStatus.WAITING,
            progress = 0f,
            message = "等待下载..."
        )
        downloadQueue = downloadQueue + task
        if (!queueRunning) {
            startQueueWorker()
        }
    }

    fun retryQueueItem(id: Long) {
        downloadQueue = downloadQueue.map {
            if (it.id == id && it.status == DownloadStatus.FAILED) {
                it.copy(status = DownloadStatus.WAITING, progress = 0f, message = "等待重试...")
            } else it
        }
        if (!queueRunning) {
            startQueueWorker()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.syncRetention(context)
    }

    val customBgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore if not a persistable document Uri
            }
            viewModel.updateBackgroundType(BackgroundType.CUSTOM_IMAGE, it.toString())
        }
    }

    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) epubUris = uris
    }

    val mp4Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) mp4Uris = uris
    }

    val webpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) webpUris = uris
    }

    fun startEpubConvert(treeUri: Uri) {
        currentJob = coroutineScope.launch {
            currentTaskName = "批量转换 EPUB"
            taskProgress = 0f
            taskProgressText = "正在准备文件列表..."
            var hasError = false
            try {
                val total = epubUris.size
                for ((index, inputUri) in epubUris.withIndex()) {
                    if (!isActive) break
                    val fileName = getFileName(context, inputUri)
                    val baseName = fileName.substringBeforeLast(".")
                    val docDir = DocumentFile.fromTreeUri(context, treeUri)
                    val docFile = docDir?.createFile("text/plain", "$baseName.txt")
                    val fileBasePct = index.toFloat() / total
                    val filePctRange = 1f / total

                    taskProgress = fileBasePct
                    taskProgressText = "转换第 ${index + 1}/$total 个: $fileName (0%)"

                    if (docFile != null) {
                        val success = doConversionWithRetry(context, inputUri, docFile.uri) { ctx, inUri, outUri ->
                            convertEpubToTxt(
                                context = ctx, 
                                inputUri = inUri, 
                                outputUri = outUri,
                                onProgress = { pct, status ->
                                    updateProgressOnMain(pct, "处理中 (${index + 1}/$total): $fileName (${(pct * 100).toInt()}%)")
                                },
                                basePct = fileBasePct,
                                pctRange = filePctRange,
                                taskLabel = "EPUB解析"
                            )
                        }
                        viewModel.addHistory(fileName, "EPUB转TXT", success, if (success) docFile.uri.toString() else null)
                        if (!success) hasError = true
                    } else {
                        hasError = true
                    }
                }
                if (isActive) {
                    taskProgress = 1.0f
                    taskProgressText = "批量转换已全部完成 (100%)"
                    if (hasError) {
                        errorMessage = "批量转换 EPUB 时有文件转换失败达3次！"
                        showErrorDialog = true
                    } else {
                        Toast.makeText(context, "批量保存成功！", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                epubUris = emptyList()
                currentJob = null
            }
        }
    }

    val epubSaver = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { treeUri ->
            viewModel.saveOutputDir("epub", treeUri.toString())
            startEpubConvert(treeUri)
        }
    }

    fun startEpubWithMemory() {
        val last = viewModel.getOutputDir("epub")
        if (last != null) startEpubConvert(android.net.Uri.parse(last))
        else epubSaver.launch(null)
    }

    fun startMp4Convert(treeUri: Uri) {
        currentJob = coroutineScope.launch {
            currentTaskName = "批量转换 MP4"
            taskProgress = 0f
            taskProgressText = "正在准备媒体转换..."
            var hasError = false
            try {
                val total = mp4Uris.size
                for ((index, inputUri) in mp4Uris.withIndex()) {
                    if (!isActive) break
                    val fileName = getFileName(context, inputUri)
                    val baseName = fileName.substringBeforeLast(".")
                    val docDir = DocumentFile.fromTreeUri(context, treeUri)
                    val docFile = docDir?.createFile("audio/mp4", "$baseName.m4a")
                    val fileBasePct = index.toFloat() / total
                    val filePctRange = 1f / total

                    taskProgress = fileBasePct
                    taskProgressText = "提取第 ${index + 1}/$total 个音频: $fileName (0%)"

                    if (docFile != null) {
                        val success = doConversionWithRetry(context, inputUri, docFile.uri) { ctx, inUri, outUri ->
                            convertMp4ToAudio(
                                context = ctx, 
                                inputUri = inUri, 
                                outputUri = outUri,
                                onProgress = { pct, status ->
                                    updateProgressOnMain(pct, "音频提取 (${index + 1}/$total): $fileName (${(pct * 100).toInt()}%)")
                                },
                                basePct = fileBasePct,
                                pctRange = filePctRange,
                                taskLabel = "音频提取"
                            )
                        }
                        viewModel.addHistory(fileName, "MP4转M4A", success, if (success) docFile.uri.toString() else null)
                        if (!success) hasError = true
                    } else {
                        hasError = true
                    }
                }
                if (isActive) {
                    taskProgress = 1.0f
                    taskProgressText = "批量提取已全部完成 (100%)"
                    if (hasError) {
                        errorMessage = "批量转换 MP4 时有文件转换失败达3次！"
                        showErrorDialog = true
                    } else {
                        Toast.makeText(context, "批量保存成功！", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                mp4Uris = emptyList()
                currentJob = null
            }
        }
    }

    val mp4Saver = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { treeUri ->
            viewModel.saveOutputDir("mp4", treeUri.toString())
            startMp4Convert(treeUri)
        }
    }

    fun startMp4WithMemory() {
        val last = viewModel.getOutputDir("mp4")
        if (last != null) startMp4Convert(android.net.Uri.parse(last))
        else mp4Saver.launch(null)
    }

    fun startWebpConvert(treeUri: Uri) {
        currentJob = coroutineScope.launch {
            currentTaskName = "批量转换 WebP"
            taskProgress = 0f
            taskProgressText = "正在准备文件列表..."
            var hasError = false
            try {
                val total = webpUris.size
                for ((index, inputUri) in webpUris.withIndex()) {
                    if (!isActive) break
                    val fileName = getFileName(context, inputUri)
                    val baseName = fileName.substringBeforeLast(".")
                    val docDir = DocumentFile.fromTreeUri(context, treeUri)
                    val docFile = docDir?.createFile("image/jpeg", "$baseName.jpg")
                    val fileBasePct = index.toFloat() / total
                    val filePctRange = 1f / total

                    taskProgress = fileBasePct
                    taskProgressText = "转换第 ${index + 1}/$total 个: $fileName (0%)"

                    if (docFile != null) {
                        val success = doConversionWithRetry(context, inputUri, docFile.uri) { ctx, inUri, outUri ->
                            convertWebpToJpg(
                                context = ctx,
                                inputUri = inUri,
                                outputUri = outUri,
                                onProgress = { pct, status ->
                                    updateProgressOnMain(pct, "处理中 (${index + 1}/$total): $fileName (${(pct * 100).toInt()}%)")
                                },
                                basePct = fileBasePct,
                                pctRange = filePctRange,
                                taskLabel = "WebP转JPG"
                            )
                        }
                        viewModel.addHistory(fileName, "WebP转JPG", success, if (success) docFile.uri.toString() else null)
                        if (!success) hasError = true
                    } else {
                        hasError = true
                    }
                }
                if (isActive) {
                    taskProgress = 1.0f
                    taskProgressText = "批量转换已全部完成 (100%)"
                    if (hasError) {
                        errorMessage = "批量转换 WebP 时有文件转换失败达3次！"
                        showErrorDialog = true
                    } else {
                        Toast.makeText(context, "批量保存成功！", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                webpUris = emptyList()
                currentJob = null
            }
        }
    }

    val webpSaver = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { treeUri ->
            viewModel.saveOutputDir("webp", treeUri.toString())
            startWebpConvert(treeUri)
        }
    }
    fun startWebpWithMemory() {
        // 有记住的目录直接用，否则弹选择器
        val last = viewModel.getOutputDir("webp")
        if (last != null) {
            startWebpConvert(android.net.Uri.parse(last))
        } else {
            webpSaver.launch(null)
        }
    }

    // ===== 批量下载文件夹选择器 =====
    val queueFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            queueFolderUri = it
            queueFolderName = DocumentFile.fromTreeUri(context, it)?.name ?: "已选择文件夹"
            viewModel.saveOutputDir("dlqueue", it.toString())
            Toast.makeText(context, "已选择下载文件夹，可批量添加视频", Toast.LENGTH_SHORT).show()
            // 如果有等待中的任务，开始消费
            if (downloadQueue.any { t -> t.status == DownloadStatus.WAITING }) {
                startQueueWorker()
            }
        }
    }

    // 启动时恢复上次选择的下载文件夹
    LaunchedEffect(Unit) {
        val savedDir = viewModel.getOutputDir("dlqueue")
        if (savedDir != null) {
            val uri = Uri.parse(savedDir)
            queueFolderUri = uri
            queueFolderName = DocumentFile.fromTreeUri(context, uri)?.name ?: "已选择文件夹"
        }
    }

    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text("发生错误") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = false }) {
                    Text("确定")
                }
            }
        )
    }

    if (showLogDialog) {
        LogViewerDialog(
            onDismissRequest = { showLogDialog = false }
        )
    }

    if (showBgSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBgSettingsSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("个性化设置 (背景与主题)", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { 
                        viewModel.resetAppearance(context)
                        Toast.makeText(context, "已恢复默认外观与主题(自定义图标+自定义背景)", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("重置默认")
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("图标与背景持久保留机制已启用", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "• 桌面图标保留状态: ${currentIcon.displayName} (重新启动后自动维持此图标)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "• 自定义背景保留状态: ${
                                when (bgSettings.type) {
                                    BackgroundType.CUSTOM_IMAGE -> "自定义相册图片"
                                    BackgroundType.PRESET_ABSTRACT_GLOW -> "炫彩极光"
                                    BackgroundType.PRESET_WARM_PASTEL -> "温暖柔和"
                                    BackgroundType.DEFAULT_CUSTOM -> "自定义默认背景"
                    BackgroundType.DEFAULT -> "系统默认背景"
                                }
                            } (SharedPreferences 本地固化)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                viewModel.syncRetention(context)
                                Toast.makeText(context, "当前图标与背景设置已成功锁定并永久保留！", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("强制重新同步保留设置")
                            }
                        }
                    }
                }

                Text("主题模式 (日间 / 夜间)", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val isSelected = themeMode == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.updateThemeMode(mode)
                                Toast.makeText(context, "已设置主题: ${mode.displayName}", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text(mode.displayName) },
                            leadingIcon = {
                                when (mode) {
                                    ThemeMode.LIGHT -> Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(18.dp))
                                    ThemeMode.DARK -> Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(18.dp))
                                    ThemeMode.SYSTEM -> Icon(Icons.Default.BrightnessAuto, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text("桌面应用图标", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppIconType.entries.forEach { icon ->
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(92.dp)
                                .clickable {
                                    viewModel.setAppIcon(context, icon)
                                    Toast.makeText(context, "图标已切换为: ${icon.displayName}", Toast.LENGTH_SHORT).show()
                                },
                            border = if (currentIcon == icon) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                com.example.ui.SafeImage(
                                    drawableRes = icon.drawableRes,
                                    contentDescription = icon.displayName,
                                    modifier = Modifier.size(42.dp),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = icon.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Text("背景样式与来源", style = MaterialTheme.typography.titleMedium)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Option 1: Pick from Gallery
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(90.dp)
                            .clickable { customBgPicker.launch("image/*") },
                        border = if (bgSettings.type == BackgroundType.CUSTOM_IMAGE) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("相册选择", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Option 2: Preset Abstract Glow
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(90.dp)
                            .clickable { viewModel.updateBackgroundType(BackgroundType.PRESET_ABSTRACT_GLOW) },
                        border = if (bgSettings.type == BackgroundType.PRESET_ABSTRACT_GLOW) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            com.example.ui.SafeImage(
                                drawableRes = R.drawable.img_bg_abstract_glow_1785330326995,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(com.example.ui.theme.AppSurfaceDarkOverlay),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("炫彩极光", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                    }

                    // Option 3: Preset Warm Pastel
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(90.dp)
                            .clickable { viewModel.updateBackgroundType(BackgroundType.PRESET_WARM_PASTEL) },
                        border = if (bgSettings.type == BackgroundType.PRESET_WARM_PASTEL) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            com.example.ui.SafeImage(
                                drawableRes = R.drawable.img_bg_warm_pastel_1785330342798,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(com.example.ui.theme.AppSurfaceDarkOverlay),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("温暖柔和", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                    }

                    // Option 4: DEFAULT_CUSTOM (your custom bg)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(90.dp)
                            .clickable { viewModel.updateBackgroundType(BackgroundType.DEFAULT_CUSTOM) },
                        border = if (bgSettings.type == BackgroundType.DEFAULT_CUSTOM) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            com.example.ui.SafeImage(
                                drawableRes = R.drawable.bg_default_custom,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(com.example.ui.theme.AppSurfaceDarkOverlay),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("自定义默认", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                    }

                    // Option 5: Default (no bg)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(90.dp)
                            .clickable { viewModel.updateBackgroundType(BackgroundType.DEFAULT) },
                        border = if (bgSettings.type == BackgroundType.DEFAULT) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("默认背景", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (bgSettings.type != BackgroundType.DEFAULT) {
                    Text("效果参数调节", style = MaterialTheme.typography.titleMedium)

                    Text("背景暗化度: ${(bgSettings.dimAlpha * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = bgSettings.dimAlpha,
                        onValueChange = { viewModel.updateDimAlpha(it) },
                        valueRange = 0.0f..0.85f
                    )

                    Text("卡片透光度: ${(bgSettings.cardAlpha * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = bgSettings.cardAlpha,
                        onValueChange = { viewModel.updateCardAlpha(it) },
                        valueRange = 0.4f..1.0f
                    )
                }

                Button(
                    onClick = { showBgSettingsSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("完成设置")
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    val cardContainerColor = if (bgSettings.type == BackgroundType.DEFAULT) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = bgSettings.cardAlpha)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Render Background layer
        when (bgSettings.type) {
            BackgroundType.CUSTOM_IMAGE -> {
                if (!bgSettings.customUriString.isNullOrEmpty()) {
                    AsyncImage(
                        model = bgSettings.customUriString,
                        contentDescription = "自定义背景图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            BackgroundType.PRESET_ABSTRACT_GLOW -> {
                com.example.ui.SafeImage(
                    drawableRes = R.drawable.img_bg_abstract_glow_1785330326995,
                    contentDescription = "炫彩极光背景",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            BackgroundType.PRESET_WARM_PASTEL -> {
                com.example.ui.SafeImage(
                    drawableRes = R.drawable.img_bg_warm_pastel_1785330342798,
                    contentDescription = "温暖柔和背景",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            BackgroundType.DEFAULT_CUSTOM -> {
                com.example.ui.SafeImage(
                    drawableRes = R.drawable.bg_default_custom,
                    contentDescription = "默认自定义背景",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            BackgroundType.DEFAULT -> {
                // Default theme background surface
            }
        }

        // Overlay mask to ensure content readability
        if (bgSettings.type != BackgroundType.DEFAULT) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = bgSettings.dimAlpha))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Transformed",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "多格式转换 · 视频解析下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showBgSettingsSheet = true }) {
                        Icon(
                            Icons.Default.Wallpaper,
                            contentDescription = "个性化外观设置",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { showLogDialog = true }) {
                        Icon(
                            Icons.Default.Article,
                            contentDescription = "查看运行日志",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = {
                        val nextMode = when (themeMode) {
                            ThemeMode.SYSTEM -> ThemeMode.LIGHT
                            ThemeMode.LIGHT -> ThemeMode.DARK
                            ThemeMode.DARK -> ThemeMode.SYSTEM
                        }
                        viewModel.updateThemeMode(nextMode)
                        Toast.makeText(context, "已切换主题模式: ${nextMode.displayName}", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = when (themeMode) {
                                ThemeMode.LIGHT -> Icons.Default.LightMode
                                ThemeMode.DARK -> Icons.Default.DarkMode
                                ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                            },
                            contentDescription = "切换主题模式",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape,
                colors = CardDefaults.cardColors(containerColor = cardContainerColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionHeader(icon = Icons.Default.MenuBook, title = "批量 EPUB 转 TXT")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { epubPicker.launch(arrayOf("application/epub+zip", "application/epub")) }, enabled = !isConverting) {
                            Text("批量导入 EPUB文件")
                        }
                        if (epubUris.isNotEmpty()) {
                            OutlinedButton(onClick = { epubUris = emptyList() }) {
                                Text("清空")
                            }
                        }
                    }
                    if (epubUris.isNotEmpty()) {
                        val epubSizeText = remember(epubUris) { getBatchFileSizeText(context, epubUris) }
                        Text(epubSizeText, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { startEpubWithMemory() },
                            enabled = epubUris.isNotEmpty() && !isConverting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (viewModel.getOutputDir("epub") != null) "转换到上次目录" else "选目录并转换")
                        }
                        OutlinedButton(
                            onClick = { epubSaver.launch(null) },
                            enabled = !isConverting
                        ) { Text("换目录") }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape,
                colors = CardDefaults.cardColors(containerColor = cardContainerColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionHeader(icon = Icons.Default.MusicNote, title = "批量 MP4 转 M4A 音频")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { mp4Picker.launch(arrayOf("video/mp4")) }, enabled = !isConverting) {
                            Text("批量导入 MP4文件")
                        }
                        if (mp4Uris.isNotEmpty()) {
                            OutlinedButton(onClick = { mp4Uris = emptyList() }) {
                                Text("清空")
                            }
                        }
                    }
                    if (mp4Uris.isNotEmpty()) {
                        val mp4SizeText = remember(mp4Uris) { getBatchFileSizeText(context, mp4Uris) }
                        Text(mp4SizeText, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { startMp4WithMemory() },
                            enabled = mp4Uris.isNotEmpty() && !isConverting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (viewModel.getOutputDir("mp4") != null) "转换到上次目录" else "选目录并转换")
                        }
                        OutlinedButton(
                            onClick = { mp4Saver.launch(null) },
                            enabled = !isConverting
                        ) { Text("换目录") }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape,
                colors = CardDefaults.cardColors(containerColor = cardContainerColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionHeader(icon = Icons.Default.Image, title = "批量 WebP 转 JPG")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { webpPicker.launch(arrayOf("image/webp")) }, enabled = !isConverting) {
                            Text("批量导入 WebP文件")
                        }
                        if (webpUris.isNotEmpty()) {
                            OutlinedButton(onClick = { webpUris = emptyList() }) {
                                Text("清空")
                            }
                        }
                    }
                    if (webpUris.isNotEmpty()) {
                        val webpSizeText = remember(webpUris) { getBatchFileSizeText(context, webpUris) }
                        Text(webpSizeText, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { startWebpWithMemory() },
                            enabled = webpUris.isNotEmpty() && !isConverting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (viewModel.getOutputDir("webp") != null) "转换到上次目录" else "选目录并转换")
                        }
                        OutlinedButton(
                            onClick = { webpSaver.launch(null) },
                            enabled = !isConverting
                        ) {
                            Text("换目录")
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape,
                colors = CardDefaults.cardColors(containerColor = cardContainerColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionHeader(icon = Icons.Default.CloudDownload, title = "视频解析下载 (B站 / X / YouTube)")

                    // ---- 歌曲搜索下载 ----
                    OutlinedTextField(
                        value = songQuery,
                        onValueChange = { songQuery = it },
                        label = { Text("歌曲搜索") },
                        placeholder = { Text("输入歌手 / 歌名，如: 周杰伦 晴天") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            Row {
                                if (songQuery.isNotEmpty()) {
                                    IconButton(onClick = { songQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清空")
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (songQuery.isNotBlank() && !isSongSearching) {
                                            coroutineScope.launch {
                                                isSongSearching = true
                                                try {
                                                    // B站优先（国内直连、无需代理），搜不到再退回 YouTube
                                                    var r = NetworkDownloader.searchBilibiliMusic(context, songQuery.trim(), 8)
                                                    var usedSource = "B站"
                                                    if (r.isEmpty()) {
                                                        r = NetworkDownloader.searchYouTubeMusic(context, songQuery.trim(), 5)
                                                        usedSource = "YouTube"
                                                    }
                                                    if (r.isNotEmpty()) {
                                                        songResults = r
                                                        showSongResults = true
                                                        songToast = "共 ${r.size} 个结果（来源: $usedSource），点选下载音频 (M4A)"
                                                    } else {
                                                        songToast = "B站/YouTube 都未搜到歌曲，请换歌名或检查网络"
                                                    }
                                                } catch (e: Exception) {
                                                    songToast = "搜索失败: ${e.message}"
                                                } finally {
                                                    isSongSearching = false
                                                }
                                            }
                                        } else if (songQuery.isBlank()) {
                                            songToast = "请输入歌名或歌手"
                                        }
                                    },
                                    enabled = !isSongSearching
                                ) {
                                    if (isSongSearching) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = "搜索歌曲")
                                    }
                                }
                            }
                        },
                        supportingText = {
                            Text(if (songToast.isNotEmpty()) songToast else "B站优先、YouTube兜底，自动搜索可点选直接下载音频 (M4A)")
                        }
                    )

                    // 搜索结果选择对话框
                    if (showSongResults) {
                        AlertDialog(
                            onDismissRequest = { showSongResults = false },
                            title = { Text("选择要下载的歌曲") },
                            text = {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    songResults.forEach { r ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                videoUrl = if (r.source == "bilibili") {
                                                    "https://www.bilibili.com/video/${r.videoId}"
                                                } else {
                                                    "https://www.youtube.com/watch?v=${r.videoId}"
                                                }
                                                customVideoTitle = r.title
                                                fetchedVideoTitle = r.title
                                                titleFetchedForUrl = videoUrl
                                                showSongResults = false
                                                songToast = "已选择: ${r.title}，点击下方\"下载音频\"即可加入队列"
                                            },
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            Column(Modifier.padding(10.dp)) {
                                                Text(r.title, fontWeight = FontWeight.Bold, maxLines = 2)
                                                Text(
                                                    if (r.source == "bilibili") "[B站] ${r.channel}   ${r.duration}"
                                                    else "[YouTube] ${r.channel}   ${r.duration}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showSongResults = false }) { Text("取消") }
                            }
                        )
                    }

                    OutlinedTextField(
                        value = videoUrl,
                        onValueChange = { input ->
                            // Automatically extract clean URL if input contains full URL or share text
                            videoUrl = NetworkDownloader.sanitizeUrlInput(input)
                        },
                        label = { Text("视频链接") },
                        placeholder = { Text("粘贴 B站(BV/AV) / X(Twitter) / YouTube 或直链") },
                        supportingText = { Text("支持自动提纯有效链接与自动读取视频标题重命名，预留多种备用换源节点", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            Row {
                                if (videoUrl.isNotEmpty()) {
                                    IconButton(onClick = { videoUrl = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清空链接")
                                    }
                                }
                                IconButton(onClick = {
                                    try {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clipData = clipboard.primaryClip
                                        if (clipData != null && clipData.itemCount > 0) {
                                            val text = clipData.getItemAt(0).text?.toString() ?: ""
                                            if (text.isNotEmpty()) {
                                                val cleanUrl = NetworkDownloader.sanitizeUrlInput(text)
                                                videoUrl = cleanUrl
                                                Toast.makeText(context, "已智能提取有效链接: $cleanUrl", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "无法获取剪贴板内容", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "粘贴剪贴板")
                                }
                            }
                        }
                    )

                    if (isFetchingVideoTitle) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("正在自动抓取视频真实标题...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (fetchedVideoTitle != null || videoUrl.isNotBlank()) {
                        OutlinedTextField(
                            value = customVideoTitle,
                            onValueChange = { customVideoTitle = it },
                            label = { Text("视频标题 (自动抓取重命名/可修改)") },
                            placeholder = { Text("输入保存的文件名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Default.Title, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingIcon = {
                                if (fetchedVideoTitle != null) {
                                    IconButton(onClick = { customVideoTitle = fetchedVideoTitle ?: "" }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "重置标题")
                                    }
                                }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val targetName = suggestFileName(videoUrl, "mp4", customVideoTitle.ifBlank { fetchedVideoTitle })
                                if (queueFolderUri == null) {
                                    // 先选文件夹，选完后需手动再点一次
                                    queueFolderPicker.launch(null)
                                    Toast.makeText(context, "请先选择下载文件夹，然后再次点击下载", Toast.LENGTH_LONG).show()
                                } else {
                                    addToQueue(videoUrl, false, targetName)
                                    Toast.makeText(context, "✅ 已加入队列: $targetName", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = videoUrl.isNotEmpty() && !isConverting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载视频 (MP4)", maxLines = 1)
                        }
                        Button(
                            onClick = {
                                val targetName = suggestFileName(videoUrl, "m4a", customVideoTitle.ifBlank { fetchedVideoTitle })
                                if (queueFolderUri == null) {
                                    queueFolderPicker.launch(null)
                                    Toast.makeText(context, "请先选择下载文件夹，然后再次点击下载", Toast.LENGTH_LONG).show()
                                } else {
                                    addToQueue(videoUrl, true, targetName)
                                    Toast.makeText(context, "✅ 已加入队列: $targetName", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = videoUrl.isNotEmpty() && !isConverting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载音频 (M4A)", maxLines = 1)
                        }
                    }

                    // 文件夹选择状态 + 选择按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        if (queueFolderUri != null) {
                            Text(
                                text = "📁 $queueFolderName",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "未选择文件夹 (需先选文件夹再下载)",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { queueFolderPicker.launch(null) }) {
                            Text(if (queueFolderUri != null) "更换" else "选择文件夹")
                        }
                    }
                }
            }
            
            AnimatedVisibility(
                visible = isConverting,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = SectionCardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = bgSettings.cardAlpha))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "任务处理中",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            val pctDisplay = (taskProgress * 100).toInt().coerceIn(0, 100)
                            Text(
                                text = "$pctDisplay%",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        Text(
                            text = "当前任务: $currentTaskName",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (taskProgress <= 0.001f) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = animatedProgress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }

                        if (taskProgressText.isNotEmpty()) {
                            Text(
                                text = taskProgressText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(
                            onClick = {
                                currentJob?.cancel()
                                currentJob = null
                                // 同时取消队列 worker
                                queueWorkerJob.value?.cancel()
                                queueWorkerJob.value = null
                                queueRunning = false
                                // 将队列中 RUNNING 的任务标记回 WAITING
                                downloadQueue = downloadQueue.map {
                                    if (it.status == DownloadStatus.RUNNING)
                                        it.copy(status = DownloadStatus.FAILED, message = "用户取消")
                                    else it
                                }
                                taskProgress = 0f
                                taskProgressText = ""
                                Toast.makeText(context, "任务已取消", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("取消当前任务")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== 下载队列面板 =====
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape,
                colors = CardDefaults.cardColors(containerColor = cardContainerColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 标题行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val runningCount = downloadQueue.count { it.status == DownloadStatus.RUNNING }
                        val totalCount = downloadQueue.size
                        SectionHeader(
                            icon = Icons.Default.Queue,
                            title = "下载队列 ($runningCount/$totalCount)",
                            modifier = Modifier.weight(1f)
                        )
                        if (downloadQueue.isNotEmpty()) {
                            TextButton(
                                onClick = { clearQueue() },
                                enabled = downloadQueue.any { it.status != DownloadStatus.RUNNING }
                            ) {
                                Text("清空队列", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    // 队列为空时的提示
                    if (downloadQueue.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "队列为空，粘贴链接后点击下载即加入队列",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // 逐个任务显示
                        downloadQueue.forEach { task ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = when (task.status) {
                                        DownloadStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        DownloadStatus.DONE -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                                        DownloadStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                        DownloadStatus.WAITING -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    }
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 状态图标 + 文件名
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = when (task.status) {
                                                    DownloadStatus.WAITING -> Icons.Outlined.Schedule
                                                    DownloadStatus.RUNNING -> Icons.Default.Downloading
                                                    DownloadStatus.DONE -> Icons.Default.CheckCircle
                                                    DownloadStatus.FAILED -> Icons.Default.ErrorOutline
                                                },
                                                contentDescription = task.status.name,
                                                tint = when (task.status) {
                                                    DownloadStatus.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    DownloadStatus.RUNNING -> MaterialTheme.colorScheme.primary
                                                    DownloadStatus.DONE -> MaterialTheme.colorScheme.tertiary
                                                    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                                                },
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = task.fileName,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${if (task.isAudio) "M4A" else "MP4"} · ${task.message}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        // 操作按钮
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            if (task.status == DownloadStatus.FAILED) {
                                                IconButton(
                                                    onClick = { retryQueueItem(task.id) },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Refresh,
                                                        contentDescription = "重试",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                            if (task.status != DownloadStatus.RUNNING) {
                                                IconButton(
                                                    onClick = {
                                                        downloadQueue = downloadQueue.filter { it.id != task.id }
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.DeleteOutline,
                                                        contentDescription = "删除",
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 进度条
                                    if (task.status == DownloadStatus.RUNNING) {
                                        LinearProgressIndicator(
                                            progress = task.progress.coerceIn(0f, 1f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    } else if (task.status == DownloadStatus.DONE) {
                                        LinearProgressIndicator(
                                            progress = 1f,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = MaterialTheme.colorScheme.tertiary,
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val filteredHistoryList = remember(historyList, selectedFilterTag) {
                when (selectedFilterTag) {
                    "EPUB" -> historyList.filter { it.conversionType.contains("EPUB", ignoreCase = true) }
                    "MP4" -> historyList.filter { it.conversionType.contains("MP4", ignoreCase = true) }
                    "WebP" -> historyList.filter { it.conversionType.contains("WebP", ignoreCase = true) }
                    "网络" -> historyList.filter { it.conversionType.contains("下载", ignoreCase = true) }
                    "B站" -> historyList.filter { it.fileName.contains("bilibili", ignoreCase = true) || it.conversionType.contains("B站", ignoreCase = true) }
                    "YouTube" -> historyList.filter { it.fileName.contains("youtube", ignoreCase = true) || it.conversionType.contains("YouTube", ignoreCase = true) }
                    "X" -> historyList.filter { it.fileName.contains("x_video", ignoreCase = true) || it.conversionType.contains("X", ignoreCase = true) }
                    else -> historyList
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(
                    icon = Icons.Default.History,
                    title = "转换历史 (${filteredHistoryList.size})",
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (filteredHistoryList.isNotEmpty()) {
                        val allSelected = filteredHistoryList.all { history -> selectedHistoryIds.contains(history.id) }
                        TextButton(onClick = {
                            selectedHistoryIds = if (allSelected) {
                                selectedHistoryIds - filteredHistoryList.map { it.id }.toSet()
                            } else {
                                selectedHistoryIds + filteredHistoryList.map { it.id }.toSet()
                            }
                        }) {
                            Text(if (allSelected) "取消全选" else "全选")
                        }
                    }
                    if (selectedHistoryIds.isNotEmpty()) {
                        TextButton(onClick = {
                            viewModel.deleteHistories(selectedHistoryIds.toList())
                            selectedHistoryIds = emptySet()
                        }) {
                            Text("批量删除 (${selectedHistoryIds.size})", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("全部", "EPUB", "MP4", "WebP", "网络", "B站", "YouTube", "X").forEach { tag ->
                    FilterChip(
                        selected = selectedFilterTag == tag,
                        onClick = { selectedFilterTag = tag },
                        label = { Text(tag) }
                    )
                }
            }

            if (filteredHistoryList.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = cardContainerColor)
                ) {
                    Box(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无相关转换或下载记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredHistoryList.forEach { history ->
                        HistoryItemRow(
                            history = history,
                            isSelected = selectedHistoryIds.contains(history.id),
                            cardAlpha = bgSettings.cardAlpha,
                            onSelectToggle = {
                                selectedHistoryIds = if (selectedHistoryIds.contains(history.id)) {
                                    selectedHistoryIds - history.id
                                } else {
                                    selectedHistoryIds + history.id
                                }
                            },
                            onDeleteSingle = {
                                viewModel.deleteHistory(history.id)
                                selectedHistoryIds = selectedHistoryIds - history.id
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItemRow(
    history: ConversionHistory,
    isSelected: Boolean,
    cardAlpha: Float = 0.88f,
    onSelectToggle: () -> Unit,
    onDeleteSingle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = cardAlpha)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardAlpha)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .clickable { onSelectToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelectToggle() },
                modifier = Modifier.padding(end = 4.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = history.fileName, 
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 按类型上色的徽章，便于快速区分记录来源
                    val (badgeContainer, badgeContent) = when {
                        history.conversionType.contains("EPUB", ignoreCase = true) ->
                            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                        history.conversionType.contains("M4A", ignoreCase = true) ->
                            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                        history.conversionType.contains("MP4", ignoreCase = true) ->
                            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                        history.conversionType.contains("WebP", ignoreCase = true) ->
                            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                        else ->
                            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                    }
                    Surface(
                        color = badgeContainer,
                        contentColor = badgeContent,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = history.conversionType,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        text = formatTimestamp(history.timestamp), 
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = if (history.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = if (history.isSuccess) "成功" else "失败",
                tint = if (history.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp).size(20.dp)
            )
            if (history.isSuccess && !history.outputUri.isNullOrEmpty()) {
                val context = LocalContext.current
                IconButton(
                    onClick = { openFile(context, history.outputUri, history.conversionType) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "打开",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipData = ClipData.newPlainText("output_uri", history.outputUri)
                            clipboard.setPrimaryClip(clipData)
                            Toast.makeText(context, "已复制路径: ${history.outputUri}", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "复制路径失败", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制路径",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { shareFile(context, history.outputUri) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "分享",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(
                onClick = onDeleteSingle,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "删除记录",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

fun openFile(context: Context, uriString: String, conversionType: String) {
    try {
        val uri = Uri.parse(uriString)
        val mimeType = when {
            conversionType.contains("TXT", ignoreCase = true) -> "text/plain"
            conversionType.contains("M4A", ignoreCase = true) || conversionType.contains("MP3", ignoreCase = true) -> "audio/*"
            conversionType.contains("MP4", ignoreCase = true) -> "video/*"
            conversionType.contains("JPG", ignoreCase = true) -> "image/*"
            else -> context.contentResolver.getType(uri) ?: "*/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "打开文件"))
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开文件: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

fun shareFile(context: Context, uriString: String) {
    try {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享文件"))
    } catch (e: Exception) {
        Toast.makeText(context, "无法分享文件", Toast.LENGTH_SHORT).show()
    }
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
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
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
    onProgress: ((Float, String) -> Unit)? = null
): Boolean {
    val typeName = if (isAudio) "音频(M4A)" else "视频(MP4)"
    AppLogger.i(context, "开始解析与下载$typeName: $url", "视频解析")
    var retries = 0
    var success = false
    while (retries < 3 && !success) {
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
        success = NetworkDownloader.downloadVideo(context, url, outputUri, isAudio, onProgress)
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

fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

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
                            coroutineContext.ensureActive()
                            val name = zipEntry.name.lowercase()
                            if (name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml")) {
                                chapterIndex++
                                val text = extractTextFromHtml(zis.readBytes().toString(Charsets.UTF_8))
                                outputStream.write(text.toByteArray(Charsets.UTF_8))
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
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            val contentResolver = context.contentResolver
            pfd = contentResolver.openFileDescriptor(inputUri, "r") ?: return@withContext false
            extractor = MediaExtractor()
            extractor.setDataSource(pfd.fileDescriptor)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
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

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                -1L
            }

            outPfd = contentResolver.openFileDescriptor(outputUri, "w") ?: return@withContext false
            
            muxer = MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val maxChunkSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                val size = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                if (size > 0) size else 1024 * 1024
            } else {
                1024 * 1024
            }

            val buffer = ByteBuffer.allocate(maxChunkSize)
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var processedChunks = 0L

            while (true) {
                coroutineContext.ensureActive()
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
        String.format(java.util.Locale.getDefault(), "已选择 %d 个文件 (共约 %.1f MB)", uris.size, mb)
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
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(Date())
    return "media_download_$timeStamp.$extension"
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
                ?: return@withContext false
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (bitmap == null) {
                AppLogger.log(context, "WebP 解码失败: 无法解析图片文件")
                return@withContext false
            }

            onProgress?.invoke(basePct + pctRange * 0.5f, "$taskLabel: 50% (编码中)")

            val outputStream = context.contentResolver.openOutputStream(outputUri)
                ?: return@withContext false

            bitmap.compress(
                android.graphics.Bitmap.CompressFormat.JPEG,
                90,
                outputStream
            )
            outputStream.flush()
            outputStream.close()
            bitmap.recycle()

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
