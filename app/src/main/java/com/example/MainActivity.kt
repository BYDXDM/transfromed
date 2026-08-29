package com.example

import android.Manifest
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.transfer.DownloadService
import com.example.transfer.DownloadStatus
import com.example.transfer.TransferHub
import com.example.transfer.getBatchFileSizeText
import com.example.transfer.suggestFileName
import com.example.ui.HistoryItemRow
import com.example.ui.SectionCardShape
import com.example.ui.SectionHeader
import com.example.ui.LogViewerDialog
import com.example.ui.AppearanceSettingsSheet
import com.example.ui.openFile
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemeMode
import com.example.viewmodel.AppIconType
import com.example.viewmodel.BackgroundType
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TransferHub.init(applicationContext)
        handleSendIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSendIntent(intent)
    }

    /** 接收系统分享（其他 App 通过"分享"发来的链接文本）。 */
    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                TransferHub.postSharedLink(text)
            }
        }
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
    // B站分P列表与当前选择
    var biliPages by remember { mutableStateOf<List<NetworkDownloader.BiliPage>>(emptyList()) }
    var selectedPage by remember { mutableStateOf<NetworkDownloader.BiliPage?>(null) }
    var pagesExpanded by remember { mutableStateOf(false) }
    // 剪贴板检测到的候选链接
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }
    var notifPermissionAsked by remember { mutableStateOf(false) }
    // 首次下载尚未选文件夹时挂起的请求，选完自动继续
    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }

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
            biliPages = emptyList()
            selectedPage = null
            return@LaunchedEffect
        }
        // 防抖：等待输入停顿后再抓取，避免每敲一个字符就发一次网络请求
        delay(700)
        if (videoUrl.isBlank()) return@LaunchedEffect
        // 同一链接已抓到过标题则不再请求
        if (videoUrl != titleFetchedForUrl || fetchedVideoTitle == null) {
            isFetchingVideoTitle = true
            val title = NetworkDownloader.fetchVideoTitle(context, videoUrl)
            if (title != null) {
                fetchedVideoTitle = title
                customVideoTitle = title
                titleFetchedForUrl = videoUrl
            }
            isFetchingVideoTitle = false
        }
        // 分P列表（仅B站链接）
        val isBili = videoUrl.contains("bilibili.com") || videoUrl.contains("b23.tv") || videoUrl.contains("BV")
        if (isBili) {
            val pages = NetworkDownloader.fetchBilibiliPages(context, videoUrl)
            biliPages = pages
            selectedPage = pages.firstOrNull()
            if (pages.size > 1) {
                Toast.makeText(context, "检测到 ${pages.size} 个分P，当前选 P1，可在上方切换", Toast.LENGTH_SHORT).show()
            }
        } else {
            biliPages = emptyList()
            selectedPage = null
        }
    }

    // 剪贴板自动识别：回到应用时检测支持的链接
    LifecycleResumeEffect(Unit) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                val clean = NetworkDownloader.sanitizeUrlInput(text)
                if (clean.startsWith("http") && clean != videoUrl && clean != clipboardSuggestion) {
                    clipboardSuggestion = clean
                }
            }
        } catch (e: Exception) {
            // 剪贴板不可访问时静默忽略
        }
        onPauseOrDispose { }
    }

    // 系统分享接入：消费分享进来的文本
    val sharedLink by TransferHub.sharedLink.collectAsStateWithLifecycle()
    LaunchedEffect(sharedLink) {
        sharedLink?.let { raw ->
            val clean = NetworkDownloader.sanitizeUrlInput(raw)
            if (clean.startsWith("http") && clean.isNotBlank()) {
                videoUrl = clean
                Toast.makeText(context, "已从分享导入链接", Toast.LENGTH_SHORT).show()
            }
            TransferHub.consumeSharedLink()
        }
    }

    val bgSettings by viewModel.bgSettings.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val parseEndpoints by viewModel.parseEndpoints.collectAsStateWithLifecycle()
    val videoQuality by viewModel.videoQuality.collectAsStateWithLifecycle()
    val biliSessdata by viewModel.biliSessdata.collectAsStateWithLifecycle()

    val historyList by viewModel.historyState.collectAsStateWithLifecycle()
    var selectedHistoryIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showBgSettingsSheet by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var selectedFilterTag by remember { mutableStateOf("全部") }
    var searchQuery by remember { mutableStateOf("") }

    // ===== 后台传输状态（前台服务承载）=====
    val downloadTasks by TransferHub.tasks.collectAsStateWithLifecycle()
    val activeTask by TransferHub.activeTask.collectAsStateWithLifecycle()
    val isConverting = activeTask != null

    // 将可配置的第三方解析接口（含开关状态）同步给下载器
    LaunchedEffect(parseEndpoints) {
        NetworkDownloader.updateParseEndpoints(parseEndpoints)
    }

    // 将画质/SESSDATA 偏好同步给下载器
    LaunchedEffect(videoQuality, biliSessdata) {
        NetworkDownloader.updateVideoPreferences(videoQuality, biliSessdata)
    }

    LaunchedEffect(Unit) {
        viewModel.syncRetention(context)
    }

    val animatedProgress by animateFloatAsState(
        targetValue = (activeTask?.progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.8f,
            stiffness = 300f
        ),
        label = "task_progress_anim"
    )

    // 通知权限（Android 13+ 首次入队时申请一次）
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifPermissionAsked = true
        if (!granted) {
            Toast.makeText(context, "未授予通知权限：后台下载仍会进行，但看不到完成通知", Toast.LENGTH_LONG).show()
        }
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !notifPermissionAsked) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
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
        if (TransferHub.enqueueConversion("epub", "EPUB转TXT", epubUris, treeUri.toString())) {
            viewModel.saveOutputDir("epub", treeUri.toString())
            DownloadService.start(context)
            ensureNotificationPermission()
            Toast.makeText(context, "已加入转换队列，可离开应用", Toast.LENGTH_SHORT).show()
            epubUris = emptyList()
        }
    }

    val epubSaver = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { startEpubConvert(it) }
    }

    fun startEpubWithMemory() {
        val last = viewModel.getOutputDir("epub")
        if (last != null) startEpubConvert(Uri.parse(last))
        else epubSaver.launch(null)
    }

    fun startMp4Convert(treeUri: Uri) {
        if (TransferHub.enqueueConversion("mp4", "MP4转M4A", mp4Uris, treeUri.toString())) {
            viewModel.saveOutputDir("mp4", treeUri.toString())
            DownloadService.start(context)
            ensureNotificationPermission()
            Toast.makeText(context, "已加入转换队列，可离开应用", Toast.LENGTH_SHORT).show()
            mp4Uris = emptyList()
        }
    }

    val mp4Saver = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { startMp4Convert(it) }
    }

    fun startMp4WithMemory() {
        val last = viewModel.getOutputDir("mp4")
        if (last != null) startMp4Convert(Uri.parse(last))
        else mp4Saver.launch(null)
    }

    fun startWebpConvert(treeUri: Uri) {
        if (TransferHub.enqueueConversion("webp", "WebP转JPG", webpUris, treeUri.toString())) {
            viewModel.saveOutputDir("webp", treeUri.toString())
            DownloadService.start(context)
            ensureNotificationPermission()
            Toast.makeText(context, "已加入转换队列，可离开应用", Toast.LENGTH_SHORT).show()
            webpUris = emptyList()
        }
    }

    val webpSaver = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { startWebpConvert(it) }
    }

    fun startWebpWithMemory() {
        val last = viewModel.getOutputDir("webp")
        if (last != null) startWebpConvert(Uri.parse(last))
        else webpSaver.launch(null)
    }

    // ===== 批量下载文件夹选择器 =====
    var queueFolderUri by remember { mutableStateOf<Uri?>(null) }
    var queueFolderName by remember { mutableStateOf("") }
    val queueFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // 部分提供方不支持持久化授权，忽略
            }
            queueFolderUri = it
            queueFolderName = DocumentFile.fromTreeUri(context, it)?.name ?: "已选择文件夹"
            viewModel.saveOutputDir("dlqueue", it.toString())
            // 首次下载：文件夹选好后自动继续挂起的下载
            pendingDownload?.let { pending ->
                if (TransferHub.enqueueDownload(pending.url, pending.isAudio, pending.fileName, it.toString(), pending.cid)) {
                    DownloadService.start(context)
                    ensureNotificationPermission()
                    Toast.makeText(context, "✅ 已加入队列: ${pending.fileName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "该链接已在队列中", Toast.LENGTH_SHORT).show()
                }
            }
            pendingDownload = null
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

    fun startDownload(isAudio: Boolean) {
        val ext = if (isAudio) "m4a" else "mp4"
        val targetName = suggestFileName(
            videoUrl, ext,
            fileNameTitle(customVideoTitle, fetchedVideoTitle, selectedPage, biliPages)
        )
        val folder = queueFolderUri
        if (folder == null) {
            // 首次下载：挂起请求，文件夹选好后自动继续
            pendingDownload = PendingDownload(videoUrl, isAudio, selectedPage?.cid, targetName)
            queueFolderPicker.launch(null)
            Toast.makeText(context, "选择下载文件夹后将自动开始下载", Toast.LENGTH_LONG).show()
        } else if (TransferHub.enqueueDownload(videoUrl, isAudio, targetName, folder.toString(), selectedPage?.cid)) {
            DownloadService.start(context)
            ensureNotificationPermission()
            Toast.makeText(context, "✅ 已加入队列: $targetName", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "该链接已在队列中", Toast.LENGTH_SHORT).show()
        }
    }

    if (showLogDialog) {
        LogViewerDialog(
            onDismissRequest = { showLogDialog = false }
        )
    }

    if (showBgSettingsSheet) {
        AppearanceSettingsSheet(
            viewModel = viewModel,
            onDismiss = { showBgSettingsSheet = false }
        )
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

            // ===== 剪贴板候选链接提示 =====
            AnimatedVisibility(
                visible = clipboardSuggestion != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = SectionCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = cardContainerColor.alpha)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "检测到剪贴板链接",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = {
                            clipboardSuggestion?.let { videoUrl = it }
                            clipboardSuggestion = null
                        }) {
                            Text("填入")
                        }
                        IconButton(onClick = { clipboardSuggestion = null }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "忽略",
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
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
                    fun doSongSearch() {
                        if (songQuery.isBlank()) {
                            songToast = "请输入歌名或歌手"
                            return
                        }
                        if (isSongSearching) return
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
                    }

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
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSongSearch() }),
                        trailingIcon = {
                            Row {
                                if (songQuery.isNotEmpty()) {
                                    IconButton(onClick = { songQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清空")
                                    }
                                }
                                IconButton(
                                    onClick = { doSongSearch() },
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
                            Text(if (songToast.isNotEmpty()) songToast else "B站优先、YouTube兜底，输入后按回车或点搜索键")
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
                        supportingText = { Text("支持分享导入、剪贴板识别、自动提纯链接与标题重命名", style = MaterialTheme.typography.labelSmall) },
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
                                                Toast.makeText(context, "已智能提取有效链接", Toast.LENGTH_SHORT).show()
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

                    // ---- B站分P选择 ----
                    if (biliPages.size > 1) {
                        ExposedDropdownMenuBox(
                            expanded = pagesExpanded,
                            onExpandedChange = { pagesExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = "P${selectedPage?.page ?: 1} · ${selectedPage?.part ?: ""}",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("分P选择") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pagesExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = pagesExpanded,
                                onDismissRequest = { pagesExpanded = false }
                            ) {
                                biliPages.forEach { page ->
                                    DropdownMenuItem(
                                        text = { Text("P${page.page} · ${page.part}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = {
                                            selectedPage = page
                                            pagesExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // ---- B站画质选择 ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "画质",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        listOf(16 to "360P", 32 to "480P", 64 to "720P", 80 to "1080P").forEach { (qn, label) ->
                            FilterChip(
                                selected = videoQuality == qn,
                                onClick = {
                                    viewModel.setVideoQuality(qn)
                                    if (qn >= 64 && biliSessdata.isBlank()) {
                                        Toast.makeText(context, "720P 及以上需在个性化设置中配置 SESSDATA，否则按未登录画质", Toast.LENGTH_LONG).show()
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { startDownload(false) },
                            enabled = videoUrl.isNotEmpty() && !isConverting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载视频 (MP4)", maxLines = 1)
                        }
                        Button(
                            onClick = { startDownload(true) },
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

            Spacer(modifier = Modifier.height(8.dp))

            // ===== 任务进度卡 =====
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
                                text = "任务处理中 (可离开应用)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            val pctDisplay = ((activeTask?.progress ?: 0f) * 100).toInt().coerceIn(0, 100)
                            Text(
                                text = "$pctDisplay%",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        Text(
                            text = "当前任务: ${activeTask?.name ?: ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth()
                        )

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        if (!activeTask?.text.isNullOrBlank()) {
                            Text(
                                text = activeTask?.text ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(
                            onClick = {
                                DownloadService.cancelAll(context)
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
                        val runningCount = downloadTasks.count { it.status == DownloadStatus.RUNNING }
                        val totalCount = downloadTasks.size
                        SectionHeader(
                            icon = Icons.Default.Queue,
                            title = "下载队列 ($runningCount/$totalCount)",
                            modifier = Modifier.weight(1f)
                        )
                        if (downloadTasks.isNotEmpty()) {
                            TextButton(
                                onClick = { TransferHub.clearFinishedQueue() },
                                enabled = downloadTasks.any { it.status != DownloadStatus.RUNNING }
                            ) {
                                Text("清空队列", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    // 队列为空时的提示
                    if (downloadTasks.isEmpty()) {
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
                        downloadTasks.forEach { task ->
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
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${if (task.isAudio) "M4A" else "MP4"}${if (task.cid != null) " · 分P" else ""} · ${task.message}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        // 操作按钮
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            // 下载完成：让用户自主选择是否打开文件
                                            if (task.status == DownloadStatus.DONE && !task.outputUri.isNullOrEmpty()) {
                                                IconButton(
                                                    onClick = {
                                                        openFile(
                                                            context,
                                                            task.outputUri,
                                                            if (task.isAudio) "下载音频(M4A)" else "下载视频(MP4)"
                                                        )
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.OpenInNew,
                                                        contentDescription = "打开文件",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                            if (task.status == DownloadStatus.FAILED) {
                                                IconButton(
                                                    onClick = {
                                                        TransferHub.retryTask(task.id)
                                                        DownloadService.start(context)
                                                    },
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
                                                    onClick = { TransferHub.removeTask(task.id) },
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
                                            progress = { task.progress.coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    } else if (task.status == DownloadStatus.DONE) {
                                        LinearProgressIndicator(
                                            progress = { 1f },
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

            val filteredHistoryList = remember(historyList, selectedFilterTag, searchQuery) {
                val byTag = when (selectedFilterTag) {
                    "EPUB" -> historyList.filter { it.conversionType.contains("EPUB", ignoreCase = true) }
                    "MP4" -> historyList.filter { it.conversionType.contains("MP4", ignoreCase = true) }
                    "WebP" -> historyList.filter { it.conversionType.contains("WebP", ignoreCase = true) }
                    "网络" -> historyList.filter { it.conversionType.contains("下载", ignoreCase = true) }
                    "B站" -> historyList.filter { it.fileName.contains("bilibili", ignoreCase = true) || it.conversionType.contains("B站", ignoreCase = true) }
                    "YouTube" -> historyList.filter { it.fileName.contains("youtube", ignoreCase = true) || it.conversionType.contains("YouTube", ignoreCase = true) }
                    "X" -> historyList.filter { it.fileName.contains("x_video", ignoreCase = true) || it.conversionType.contains("X", ignoreCase = true) }
                    else -> historyList
                }
                if (searchQuery.isBlank()) byTag
                else byTag.filter { it.fileName.contains(searchQuery, ignoreCase = true) }
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

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索历史记录") },
                placeholder = { Text("输入文件名关键词...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除搜索", modifier = Modifier.size(20.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

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

/** 组装用于文件命名的标题：多P时自动附加 P 编号。 */
private fun fileNameTitle(
    customTitle: String,
    fetchedTitle: String?,
    selectedPage: NetworkDownloader.BiliPage?,
    pages: List<NetworkDownloader.BiliPage>
): String? {
    val base = customTitle.ifBlank { fetchedTitle } ?: return null
    return if (selectedPage != null && pages.size > 1) "$base P${selectedPage.page}" else base
}

/** 首次下载时文件夹未选定，先挂起请求，选完文件夹自动继续。 */
private data class PendingDownload(
    val url: String,
    val isAudio: Boolean,
    val cid: Long?,
    val fileName: String
)
