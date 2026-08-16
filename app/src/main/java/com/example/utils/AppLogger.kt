package com.example.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel(val label: String) {
    ALL("全部"),
    INFO("信息"),
    SUCCESS("成功"),
    WARN("警告"),
    ERROR("错误"),
    DEBUG("调试")
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val rawLine: String
)

object AppLogger {
    @Volatile
    private var isLogging = true
    private const val MAX_SIZE = 512 * 1024L // 512KB
    private const val MAX_LOG_LINES = 2000

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    
    private var isInitialized = false
    private val idCounter = AtomicLong(0)
    
    // 文件写入锁：防止多个 IO 协程并发写日志文件导致数据竞争
    private val fileLock = Any()

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        CoroutineScope(Dispatchers.IO).launch {
            _logs.value = getLogEntriesFromFile(context)
        }
    }

    fun enableLogging() {
        isLogging = true
    }

    fun disableLogging() {
        isLogging = false
    }

    @Synchronized
    fun log(context: Context, message: String, level: LogLevel = LogLevel.INFO, tag: String = "系统") {
        if (!isLogging) return
        
        init(context.applicationContext)
        
        val date = Date()
        val timeFull = dateFormat.format(date)
        val timeShort = timeFormat.format(date)
        
        val formattedLine = "[$timeFull] [${level.name}] [$tag] $message"
        
        val entry = LogEntry(
            id = idCounter.incrementAndGet(),
            timestamp = timeShort,
            level = level,
            tag = tag,
            message = message,
            rawLine = formattedLine
        )

        val currentList = _logs.value.toMutableList()
        currentList.add(entry)
        if (currentList.size > MAX_LOG_LINES) {
            currentList.removeAt(0)
        }
        _logs.value = currentList
        
        CoroutineScope(Dispatchers.IO).launch {
            synchronized(fileLock) {
                val file = File(context.filesDir, "app_log.txt")
                try {
                    file.appendText(formattedLine + "\n")
                    if (file.length() > MAX_SIZE) {
                        manageLogFile(file)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun i(context: Context, message: String, tag: String = "系统") = log(context, message, LogLevel.INFO, tag)
    fun s(context: Context, message: String, tag: String = "系统") = log(context, message, LogLevel.SUCCESS, tag)
    fun w(context: Context, message: String, tag: String = "警告") = log(context, message, LogLevel.WARN, tag)
    fun e(context: Context, message: String, tag: String = "异常") = log(context, message, LogLevel.ERROR, tag)
    fun d(context: Context, message: String, tag: String = "调试") = log(context, message, LogLevel.DEBUG, tag)

    private fun getLogEntriesFromFile(context: Context): List<LogEntry> {
        val file = File(context.filesDir, "app_log.txt")
        if (!file.exists()) return emptyList()
        
        return try {
            val lines = file.readLines()
            lines.takeLast(MAX_LOG_LINES).mapNotNull { line ->
                if (line.isBlank()) null
                else parseLogLine(idCounter.incrementAndGet(), line)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getLogEntries(context: Context): List<LogEntry> {
        init(context.applicationContext)
        return _logs.value
    }

    fun parseLogLine(id: Long, line: String): LogEntry {
        try {
            var level = LogLevel.INFO
            for (l in LogLevel.values()) {
                if (l != LogLevel.ALL && line.contains("[${l.name}]")) {
                    level = l
                    break
                }
            }
            if (line.contains("失败") || line.contains("异常") || line.contains("Error") || line.contains("failed")) {
                level = LogLevel.ERROR
            } else if (line.contains("成功") || line.contains("完成")) {
                level = LogLevel.SUCCESS
            }

            val timestampMatch = Regex("\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d+)?)\\]").find(line)?.groupValues?.get(1) ?: ""
            val timeShort = if (timestampMatch.isNotEmpty() && timestampMatch.length >= 19) {
                timestampMatch.substring(11, 19)
            } else {
                timeFormat.format(Date())
            }

            val bracketMatches = Regex("\\[([^\\]]+)\\]").findAll(line).map { it.groupValues[1] }.toList()
            
            val tag = if (bracketMatches.size >= 3) bracketMatches[2] else if (bracketMatches.size >= 2 && bracketMatches[1] != level.name) bracketMatches[1] else "运行"

            var msg = line
            bracketMatches.forEach { bracket ->
                msg = msg.replace("[$bracket]", "")
            }
            msg = msg.trim()

            return LogEntry(
                id = id,
                timestamp = timeShort,
                level = level,
                tag = tag,
                message = msg.ifEmpty { line },
                rawLine = line
            )
        } catch (e: Exception) {
            return LogEntry(
                id = id,
                timestamp = timeFormat.format(Date()),
                level = LogLevel.INFO,
                tag = "系统",
                message = line,
                rawLine = line
            )
        }
    }

    @Synchronized
    fun getLogText(context: Context): String {
        val file = File(context.filesDir, "app_log.txt")
        return if (file.exists()) {
            try {
                file.readText()
            } catch (e: Exception) {
                "读取日志失败: ${e.message}"
            }
        } else {
            "暂无日志记录"
        }
    }

    private fun manageLogFile(file: File) {
        if (file.exists() && file.length() > MAX_SIZE) {
            try {
                val lines = file.readLines()
                if (lines.size > MAX_LOG_LINES) {
                    val keptLines = lines.takeLast(MAX_LOG_LINES / 2)
                    file.writeText(keptLines.joinToString("\n") + "\n")
                }
            } catch (e: Exception) {
                file.delete()
            }
        }
    }

    @Synchronized
    fun clearLog(context: Context) {
        synchronized(fileLock) {
            val file = File(context.filesDir, "app_log.txt")
            if (file.exists()) {
                file.delete()
            }
        }
        _logs.value = emptyList()
    }
}
