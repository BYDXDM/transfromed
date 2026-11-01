package com.example.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.data.AppDatabase
import com.example.data.ConversionHistory
import com.example.data.ConversionRepository
import com.example.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台传输服务：承载下载队列与批量转换的后台执行，
 * 进程在用户离开应用或锁屏后仍保持前台优先级，任务不再被系统静默杀死。
 * 进度通过系统通知展示，任务全部结束后自动停止。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workerJob: Job? = null
    private lateinit var repository: ConversionRepository
    private var lastNotifUpdate = 0L
    private var completionNotifId = NOTIF_PROGRESS_ID + 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TransferHub.init(applicationContext)
        repository = ConversionRepository(
            AppDatabase.getDatabase(applicationContext).conversionHistoryDao()
        )
        createNotificationChannels()
        startForegroundCompat()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            requestCancel()
            return START_NOT_STICKY
        }
        ensureWorker()
        return START_STICKY
    }

    override fun onDestroy() {
        workerJob?.cancel()
        workerJob = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun ensureWorker() {
        if (workerJob?.isActive == true) return
        if (!TransferHub.hasPendingWork()) {
            stopSelf()
            return
        }
        workerJob = scope.launch {
            try {
                while (currentCoroutineContext().isActive) {
                    if (!TransferHub.hasPendingWork()) break
                    TransferHub.runWorkerLoop(::processDownload, ::processConversion)
                    delay(300) // 小窗口避免入队与退出的竞态
                }
            } finally {
                if (!TransferHub.hasPendingWork()) stopSelf()
            }
        }
    }

    /** UI 取消按钮入口：取消当前任务并把运行中任务标记为失败。 */
    fun requestCancel() {
        TransferHub.failRunningTasks("用户取消")
        TransferHub.clearActive()
        workerJob?.cancel()
        workerJob = null
        stopSelf()
    }

    // ================= 下载 =================

    private suspend fun processDownload(task: DownloadTask): Boolean {
        val docDir = DocumentFile.fromTreeUri(this, Uri.parse(task.folderUri))
        // 目录授权失效（重启/换机后常见）时提前失败并给出可行动的提示，
        // 而不是卡在重试后报含糊的"下载失败"
        if (docDir == null || !docDir.canWrite()) {
            TransferHub.updateTaskMessage(task.id, "下载文件夹不可用或授权已失效，请在主界面重新选择下载文件夹")
            AppLogger.w(this, "下载目录不可写: ${task.folderUri}", "下载队列")
            return false
        }
        val mimeType = if (task.isAudio) "audio/mp4" else "video/mp4"
        val docFile = docDir.createFile(mimeType, task.fileName)
        if (docFile == null) {
            TransferHub.updateTaskMessage(task.id, "无法在所选文件夹创建文件，请更换下载文件夹后重试")
            AppLogger.w(this, "创建下载文件失败: ${task.fileName}", "下载队列")
            return false
        }
        val onProgress: (Float, String) -> Unit = { pct, status ->
            TransferHub.updateTaskProgress(task.id, pct, status)
            TransferHub.setActive(null, pct, "[队列] ${task.fileName}: $status")
            updateProgressNotification(pct, "[队列] ${task.fileName}: $status")
        }
        val success = doVideoDownloadWithRetry(
            context = this,
            url = task.url,
            outputUri = docFile.uri,
            isAudio = task.isAudio,
            cid = task.cid,
            onProgress = onProgress
        )
        if (success) {
            TransferHub.updateTaskOutput(task.id, docFile.uri.toString())
        } else {
            // 下载失败时清掉预创建的 0 字节占位文件，避免目录里留下垃圾
            try { docFile.delete() } catch (e: Exception) { }
        }
        repository.insert(
            ConversionHistory(
                fileName = task.fileName,
                conversionType = if (task.isAudio) "下载音频(M4A)" else "下载视频(MP4)",
                isSuccess = success,
                outputUri = if (success) docFile.uri.toString() else null
            )
        )
        if (success) {
            showCompletionNotification("下载完成", task.fileName, docFile.uri, task.isAudio)
            Toast.makeText(this, "✅ ${task.fileName} 下载完成", Toast.LENGTH_SHORT).show()
        }
        return success
    }

    // ================= 批量转换 =================

    private suspend fun processConversion(job: ConversionJob) {
        val docDir = DocumentFile.fromTreeUri(this, Uri.parse(job.treeUri))
        // 目录授权失效时整批都无法落盘：直接给出明确原因并记录历史，
        // 避免用户只看到"有文件失败"却查不到任何记录
        if (docDir == null || !docDir.canWrite()) {
            AppLogger.w(this, "批量${job.label}输出目录不可写: ${job.treeUri}", "格式转换")
            repository.insert(
                ConversionHistory(
                    fileName = "(批量${job.label})",
                    conversionType = job.label,
                    isSuccess = false,
                    outputUri = null
                )
            )
            Toast.makeText(this, "输出文件夹不可用或授权已失效，请重新选择输出目录后重试", Toast.LENGTH_LONG).show()
            return
        }
        val total = job.uris.size
        var hasError = false
        job.uris.forEachIndexed { index, inputUri ->
            val fileName = getFileName(this, inputUri)
            val baseName = fileName.substringBeforeLast(".")
            val (mime, outName) = when (job.type) {
                "epub" -> "text/plain" to "$baseName.txt"
                "mp4" -> "audio/mp4" to "$baseName.m4a"
                else -> "image/jpeg" to "$baseName.jpg"
            }
            val docFile = docDir.createFile(mime, outName)
            val fileBasePct = index.toFloat() / total
            val filePctRange = 1f / total
            TransferHub.setActive(
                "批量转换 ${job.label}",
                fileBasePct,
                "处理第 ${index + 1}/$total 个: $fileName (0%)"
            )
            updateProgressNotification(
                fileBasePct,
                "(${index + 1}/$total) $fileName"
            )
            if (docFile == null) {
                hasError = true
                // 创建文件失败也必须留痕，否则用户无从排查
                repository.insert(
                    ConversionHistory(
                        fileName = fileName,
                        conversionType = job.label,
                        isSuccess = false,
                        outputUri = null
                    )
                )
                return@forEachIndexed
            }
            val onProgress: (Float, String) -> Unit = { pct, status ->
                TransferHub.setActive(
                    null,
                    fileBasePct + pct * filePctRange,
                    "处理中 (${index + 1}/$total): $fileName (${(pct * 100).toInt()}%)"
                )
                updateProgressNotification(fileBasePct + pct * filePctRange, status)
            }
            val success = doConversionWithRetry(this, inputUri, docFile.uri) { ctx, inUri, outUri ->
                when (job.type) {
                    "epub" -> convertEpubToTxt(
                        context = ctx, inputUri = inUri, outputUri = outUri,
                        onProgress = onProgress,
                        basePct = fileBasePct, pctRange = filePctRange, taskLabel = "EPUB解析"
                    )
                    "mp4" -> convertMp4ToAudio(
                        context = ctx, inputUri = inUri, outputUri = outUri,
                        onProgress = onProgress,
                        basePct = fileBasePct, pctRange = filePctRange, taskLabel = "音频提取"
                    )
                    else -> convertWebpToJpg(
                        context = ctx, inputUri = inUri, outputUri = outUri,
                        onProgress = onProgress,
                        basePct = fileBasePct, pctRange = filePctRange, taskLabel = "WebP转JPG"
                    )
                }
            }
            if (!success) {
                // 转换失败时清掉预创建的 0 字节占位输出文件
                try { docFile.delete() } catch (e: Exception) { }
            }
            repository.insert(
                ConversionHistory(
                    fileName = fileName,
                    conversionType = job.label,
                    isSuccess = success,
                    outputUri = if (success) docFile.uri.toString() else null
                )
            )
            if (!success) hasError = true
        }
        if (hasError) {
            Toast.makeText(this, "批量${job.label}有文件失败（详见历史记录）", Toast.LENGTH_LONG).show()
        } else {
            showCompletionNotification("批量${job.label}完成", "共 $total 个文件处理成功")
            Toast.makeText(this, "批量${job.label}全部完成！", Toast.LENGTH_SHORT).show()
        }
    }

    // ================= 通知 =================

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "传输进度", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示下载与转换的实时进度"
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "任务完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "下载或转换完成通知"
                }
            )
        }
    }

    private fun progressBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                0,
                "取消全部",
                PendingIntent.getService(
                    this, 0,
                    Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )

    private fun startForegroundCompat() {
        val notification = progressBuilder()
            .setContentTitle("传输服务运行中")
            .setContentText("准备处理任务...")
            .setProgress(100, 0, true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIF_PROGRESS_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateProgressNotification(progress: Float, text: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdate < NOTIF_THROTTLE_MS) return
        lastNotifUpdate = now
        val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
        val notification = progressBuilder()
            .setContentTitle("任务处理中")
            .setContentText(text)
            .setProgress(100, pct, pct <= 0)
            .build()
        notifySafely(NOTIF_PROGRESS_ID, notification)
    }

    private fun showCompletionNotification(
        title: String,
        text: String,
        outputUri: Uri? = null,
        isAudio: Boolean = false
    ) {
        val builder =
            NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
        // 让用户自主选择是否立即打开文件：点击通知本体或"打开"按钮均可
        if (outputUri != null) {
            val mime = if (isAudio) "audio/*" else "video/*"
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(outputUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openPendingIntent = PendingIntent.getActivity(
                this,
                outputUri.hashCode(),
                viewIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(openPendingIntent)
            builder.addAction(0, "打开文件", openPendingIntent)
        }
        notifySafely(completionNotifId++, builder.build())
    }

    private fun notifySafely(id: Int, notification: Notification) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(id, notification)
        } catch (e: Exception) {
            // 无通知权限时静默忽略，不影响任务本身
        }
    }

    companion object {
        const val CHANNEL_PROGRESS = "transfer_progress"
        const val CHANNEL_DONE = "transfer_done"
        const val NOTIF_PROGRESS_ID = 1001
        const val ACTION_CANCEL = "com.example.transfer.ACTION_CANCEL"
        private const val NOTIF_THROTTLE_MS = 700L

        @Volatile
        private var instance: DownloadService? = null

        /** 启动/唤醒服务（幂等）；有未完成任务时会开始消费。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
            )
        }

        /** UI 取消入口：无论服务是否存活都先把状态标记为失败。 */
        fun cancelAll(context: Context) {
            TransferHub.failRunningTasks("用户取消")
            TransferHub.clearActive()
            instance?.requestCancel() ?: run {
                // 服务未运行：直接清掉前台状态兜底
                context.stopService(Intent(context, DownloadService::class.java))
            }
        }
    }
}
