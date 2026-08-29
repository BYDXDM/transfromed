package com.example.transfer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

enum class DownloadStatus { WAITING, RUNNING, DONE, FAILED }

data class DownloadTask(
    val id: Long,
    val url: String,
    val isAudio: Boolean,   // true=音频(M4A) false=视频(MP4)
    val fileName: String,
    val folderUri: String,  // SAF 目录树 URI（用于服务内创建输出文件）
    val cid: Long? = null,  // B站分P cid（null=默认P1）
    val status: DownloadStatus = DownloadStatus.WAITING,
    val progress: Float = 0f,
    val message: String = "等待下载..."
)

/** 一次批量转换任务（EPUB/MP4/WebP），由前台服务在后台执行。 */
data class ConversionJob(
    val id: Long,
    val type: String,       // "epub" | "mp4" | "webp"
    val label: String,      // 展示名，如 "EPUB转TXT"
    val uris: List<Uri>,
    val treeUri: String     // 输出目录树 URI
)

/** 进度卡当前展示的任务（下载队列或批量转换共用）。 */
data class ActiveTask(
    val name: String,
    val progress: Float,
    val text: String
)

/**
 * 传输状态中心：下载队列 + 批量转换任务在 UI 与前台服务之间共享。
 * UI 只调用入队/取消等方法；真正的执行在 [DownloadService]。
 * 下载队列会持久化到 SharedPreferences，进程被杀后可恢复（RUNNING 重置为 WAITING）。
 */
object TransferHub {
    private const val PREFS = "transfer_hub_prefs"
    private const val KEY_TASKS = "download_tasks_json"
    private const val MAX_PERSISTED_TASKS = 60

    @Volatile
    private var appContext: Context? = null
    private val prefs by lazy {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _tasks.value = loadPersistedTasks()
        taskIdCounter.set((tasks.value.maxOfOrNull { it.id } ?: 0L) + 1)
    }

    /** 其他 App 分享进来的待处理文本（链接），由主界面消费后清空。 */
    private val _sharedLink = MutableStateFlow<String?>(null)
    val sharedLink: StateFlow<String?> = _sharedLink.asStateFlow()

    fun postSharedLink(text: String) {
        _sharedLink.value = text
    }

    fun consumeSharedLink() {
        _sharedLink.value = null
    }

    // ===== 下载队列 =====
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    // ===== 批量转换队列 =====
    private val _conversionJobs = MutableStateFlow<List<ConversionJob>>(emptyList())

    // ===== 当前活跃任务（进度卡展示用，下载与转换共用）=====
    private val _activeTask = MutableStateFlow<ActiveTask?>(null)
    val activeTask: StateFlow<ActiveTask?> = _activeTask.asStateFlow()

    private val taskIdCounter = AtomicLong(1L)
    private val conversionIdCounter = AtomicLong(1L)

    fun hasPendingWork(): Boolean =
        tasks.value.any { it.status == DownloadStatus.WAITING } ||
                _conversionJobs.value.isNotEmpty()

    // ---------------- UI 入口 ----------------

    /** 入队下载；同链接同格式已在等待/下载中时拒绝（去重）。 */
    fun enqueueDownload(
        url: String,
        isAudio: Boolean,
        fileName: String,
        folderUri: String,
        cid: Long? = null
    ): Boolean {
        if (folderUri.isBlank()) return false
        val duplicate = _tasks.value.any {
            it.url == url && it.isAudio == isAudio &&
                    (it.status == DownloadStatus.WAITING || it.status == DownloadStatus.RUNNING)
        }
        if (duplicate) return false
        val task = DownloadTask(
            id = taskIdCounter.incrementAndGet(),
            url = url,
            isAudio = isAudio,
            fileName = fileName,
            folderUri = folderUri,
            cid = cid
        )
        _tasks.value = _tasks.value + task
        persistTasks()
        return true
    }

    fun retryTask(id: Long) {
        updateTask(id) {
            if (it.status == DownloadStatus.FAILED) {
                it.copy(status = DownloadStatus.WAITING, progress = 0f, message = "等待重试...")
            } else it
        }
        persistTasks()
    }

    /** 删除单个任务（下载中的任务不可删）。 */
    fun removeTask(id: Long) {
        _tasks.value = _tasks.value.filter {
            !(it.id == id && it.status != DownloadStatus.RUNNING)
        }
        persistTasks()
    }

    /** 清空队列中未在运行的任务。 */
    fun clearFinishedQueue() {
        _tasks.value = _tasks.value.filter { it.status == DownloadStatus.RUNNING }
        persistTasks()
    }

    /** 入队批量转换；入参无效时返回 false。 */
    fun enqueueConversion(type: String, label: String, uris: List<Uri>, treeUri: String): Boolean {
        if (uris.isEmpty() || treeUri.isBlank()) return false
        _conversionJobs.value = _conversionJobs.value + ConversionJob(
            id = conversionIdCounter.incrementAndGet(),
            type = type,
            label = label,
            uris = uris,
            treeUri = treeUri
        )
        return true
    }

    // ---------------- 服务入口 ----------------

    internal fun updateTask(id: Long, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }

    internal fun updateTaskProgress(id: Long, progress: Float, message: String) {
        updateTask(id) { it.copy(progress = progress, message = message) }
    }

    internal fun updateTaskMessage(id: Long, message: String) {
        updateTask(id) { it.copy(message = message) }
    }

    /** 设置/更新进度卡任务；name 为 null 表示只更新进度与文本。 */
    internal fun setActive(name: String?, progress: Float?, text: String?) {
        val current = _activeTask.value ?: ActiveTask(name ?: "", progress ?: 0f, text ?: "")
        _activeTask.value = current.copy(
            name = name ?: current.name,
            progress = progress ?: current.progress,
            text = text ?: current.text
        )
    }

    internal fun clearActive() {
        _activeTask.value = null
    }

    internal fun failRunningTasks(message: String) {
        _tasks.value = _tasks.value.map {
            if (it.status == DownloadStatus.RUNNING) {
                it.copy(status = DownloadStatus.FAILED, message = message)
            } else it
        }
        persistTasks()
    }

    /**
     * 工作循环：依次消费批量转换与下载队列，没有待处理工作时返回。
     * processDownload / processConversion 由前台服务提供（含重试与历史入库）。
     */
    suspend fun runWorkerLoop(
        processDownload: suspend (DownloadTask) -> Boolean,
        processConversion: suspend (ConversionJob) -> Unit
    ) {
        try {
            while (currentCoroutineContext().isActive) {
                val job = _conversionJobs.value.firstOrNull()
                if (job != null) {
                    _conversionJobs.value = _conversionJobs.value.filterNot { it.id == job.id }
                    _activeTask.value = ActiveTask("批量转换 ${job.label}", 0f, "准备中...")
                    try {
                        processConversion(job)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        // 单个转换任务的异常不终止整个工作循环
                    }
                    continue
                }

                val task = _tasks.value.firstOrNull { it.status == DownloadStatus.WAITING }
                    ?: break
                updateTask(task.id) {
                    it.copy(status = DownloadStatus.RUNNING, progress = 0f, message = "准备下载...")
                }
                _activeTask.value = ActiveTask("队列下载: ${task.fileName}", 0f, "正在下载...")
                val ok = try {
                    processDownload(task)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    false
                }
                updateTask(task.id) {
                    if (ok) {
                        it.copy(status = DownloadStatus.DONE, progress = 1f, message = "下载完成")
                    } else {
                        it.copy(status = DownloadStatus.FAILED, message = "下载失败(已重试3次)")
                    }
                }
                persistTasks()
            }
        } finally {
            _activeTask.value = null
        }
    }

    // ---------------- 持久化 ----------------

    private fun persistTasks() {
        val ctx = appContext ?: return
        prefs?.edit()?.putString(KEY_TASKS, serializeTasks(_tasks.value))?.apply()
    }

    private fun loadPersistedTasks(): List<DownloadTask> {
        val json = prefs?.getString(KEY_TASKS, null) ?: return emptyList()
        return deserializeTasks(json).takeLast(MAX_PERSISTED_TASKS)
    }

    internal fun serializeTasks(list: List<DownloadTask>): String {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("url", t.url)
                    .put("isAudio", t.isAudio)
                    .put("fileName", t.fileName)
                    .put("folderUri", t.folderUri)
                    .put("cid", t.cid ?: JSONObject.NULL)
                    .put("status", t.status.name)
                    .put("progress", t.progress.toDouble())
                    .put("message", t.message)
            )
        }
        return arr.toString()
    }

    internal fun deserializeTasks(json: String): List<DownloadTask> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                val fileName = o.optString("fileName")
                if (url.isBlank() || fileName.isBlank()) return@mapNotNull null
                DownloadTask(
                    id = o.optLong("id"),
                    url = url,
                    isAudio = o.optBoolean("isAudio"),
                    fileName = fileName,
                    folderUri = o.optString("folderUri"),
                    cid = if (o.has("cid") && !o.isNull("cid")) o.optLong("cid") else null,
                    status = try {
                        DownloadStatus.valueOf(o.optString("status"))
                    } catch (e: Exception) {
                        DownloadStatus.WAITING
                    },
                    progress = o.optDouble("progress", 0.0).toFloat(),
                    message = o.optString("message")
                )
            }.map {
                // 进程被杀时下载中的任务状态不可信，重置为等待
                if (it.status == DownloadStatus.RUNNING) {
                    it.copy(status = DownloadStatus.WAITING, progress = 0f, message = "等待下载...")
                } else it
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
