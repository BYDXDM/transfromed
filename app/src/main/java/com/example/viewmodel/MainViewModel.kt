package com.example.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.lifecycle.viewModelScope
import com.example.NetworkDownloader
import com.example.ParseEndpointConfig
import com.example.R
import com.example.data.AppDatabase
import com.example.data.ConversionHistory
import com.example.data.ConversionRepository
import com.example.utils.AppLogger
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

enum class BackgroundType {
    DEFAULT,
    DEFAULT_CUSTOM,
    PRESET_ABSTRACT_GLOW,
    PRESET_WARM_PASTEL,
    CUSTOM_IMAGE
}

enum class AppIconType(val aliasName: String, val displayName: String, val drawableRes: Int) {
    DEFAULT("com.example.MainActivity", "自定义图标", R.drawable.ic_custom_icon),
    ORIGINAL("com.example.MainActivityOriginal", "默认极简", R.drawable.ic_app_launcher_logo_1785330614683),
    CYBER("com.example.MainActivityCyber", "赛博极光", R.drawable.icon_cyber_neon_1785330795598),
    WARM("com.example.MainActivityWarm", "暖色柔光", R.drawable.icon_warm_sunset_1785330809481),
    GOLD("com.example.MainActivityGold", "尊享黑金", R.drawable.icon_gold_dark_1785330822356)
}

data class BackgroundSettings(
    val type: BackgroundType = BackgroundType.DEFAULT_CUSTOM,
    val customUriString: String? = null,
    val dimAlpha: Float = 0.35f,
    val cardAlpha: Float = 0.88f
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ConversionRepository
    val historyState: StateFlow<List<ConversionHistory>>

    private val prefs = application.getSharedPreferences("app_bg_prefs", Context.MODE_PRIVATE)

    // ===== 登录凭证专用加密存储（EncryptedSharedPreferences，主密钥存 Android Keystore，
    //       不可随云备份迁移；该文件已被备份规则排除，见 res/xml/backup_rules.xml 与 data_extraction_rules.xml）=====
    private val securePrefs: SharedPreferences by lazy {
        try {
            createSecurePrefs()
        } catch (e: Exception) {
            // 密钥环损坏（如系统还原/备份恢复后 Keystore 密钥不匹配）时重建：
            // 凭证丢失优于持续崩溃
            getApplication<Application>().deleteSharedPreferences(SECURE_PREFS_FILE)
            createSecurePrefs()
        }
    }

    private fun createSecurePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(getApplication())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            getApplication(),
            SECURE_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _bgSettings = MutableStateFlow(loadBackgroundSettings())
    val bgSettings: StateFlow<BackgroundSettings> = _bgSettings.asStateFlow()

    private val _currentIcon = MutableStateFlow(loadAppIcon())
    val currentIcon: StateFlow<AppIconType> = _currentIcon.asStateFlow()

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // ===== 第三方解析接口白名单（B站音频兜底换源）=====
    private val _parseEndpoints = MutableStateFlow(loadParseEndpoints())
    val parseEndpoints: StateFlow<List<ParseEndpointConfig>> = _parseEndpoints.asStateFlow()

    // ===== 视频下载偏好：B站画质 / SESSDATA =====
    private val _videoQuality = MutableStateFlow(prefs.getInt("video_quality", 64))
    val videoQuality: StateFlow<Int> = _videoQuality.asStateFlow()

    private val _biliSessdata = MutableStateFlow(loadBiliSessdata())
    val biliSessdata: StateFlow<String> = _biliSessdata.asStateFlow()

    // ===== 下载代理（X/YouTube 等被墙资源经局域网代理转发）=====
    private val _downloadProxy = MutableStateFlow(prefs.getString("download_proxy", "") ?: "")
    val downloadProxy: StateFlow<String> = _downloadProxy.asStateFlow()

    fun setDownloadProxy(spec: String) {
        val trimmed = spec.trim()
        _downloadProxy.value = trimmed
        prefs.edit().putString("download_proxy", trimmed).apply()
        NetworkDownloader.updateProxy(trimmed)
        AppLogger.log(
            getApplication(),
            if (trimmed.isBlank()) "下载代理已清除（直连）" else "下载代理已设置为: $trimmed",
            tag = "设置"
        )
    }

    fun setVideoQuality(quality: Int) {
        _videoQuality.value = quality
        prefs.edit().putInt("video_quality", quality).apply()
    }

    fun setBiliSessdata(sessdata: String) {
        _biliSessdata.value = sessdata
        if (sessdata.isBlank()) {
            securePrefs.edit().remove(KEY_SESSDATA).apply()
        } else {
            securePrefs.edit().putString(KEY_SESSDATA, sessdata).apply()
        }
        // 清理历史版本遗留的明文凭证
        prefs.edit().remove(KEY_SESSDATA).apply()
        AppLogger.log(getApplication(), "B站 SESSDATA 已${if (sessdata.isBlank()) "清空（未登录画质）" else "更新（高画质）"}", tag = "设置")
    }

    /** SESSDATA 只存加密 prefs；旧版本明文残留一次性迁移（转加密并删除明文键）。 */
    private fun loadBiliSessdata(): String {
        val legacy = prefs.getString(KEY_SESSDATA, null)
        if (!legacy.isNullOrEmpty()) {
            securePrefs.edit().putString(KEY_SESSDATA, legacy).apply()
            prefs.edit().remove(KEY_SESSDATA).apply()
            return legacy
        }
        return securePrefs.getString(KEY_SESSDATA, "") ?: ""
    }

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ConversionRepository(database.conversionHistoryDao())
        
        historyState = repository.allHistory
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )
    }

    private fun loadBackgroundSettings(): BackgroundSettings {
        val typeName = prefs.getString("bg_type", BackgroundType.DEFAULT_CUSTOM.name) ?: BackgroundType.DEFAULT_CUSTOM.name
        val type = try { BackgroundType.valueOf(typeName) } catch (e: Exception) { BackgroundType.DEFAULT_CUSTOM }
        val uri = prefs.getString("bg_uri", null)
        val dim = prefs.getFloat("bg_dim", 0.35f)
        val cardAlpha = prefs.getFloat("card_alpha", 0.88f)
        return BackgroundSettings(
            type = type,
            customUriString = uri,
            dimAlpha = dim,
            cardAlpha = cardAlpha
        )
    }

    fun updateBackgroundType(type: BackgroundType, customUri: String? = null) {
        val currentUri = customUri ?: _bgSettings.value.customUriString
        val newSettings = _bgSettings.value.copy(
            type = type,
            customUriString = currentUri
        )
        _bgSettings.value = newSettings
        prefs.edit()
            .putString("bg_type", newSettings.type.name)
            .putString("bg_uri", newSettings.customUriString)
            .apply()
    }

    fun updateDimAlpha(dim: Float) {
        val newSettings = _bgSettings.value.copy(dimAlpha = dim)
        _bgSettings.value = newSettings
        prefs.edit().putFloat("bg_dim", dim).apply()
    }

    fun updateCardAlpha(cardAlpha: Float) {
        val newSettings = _bgSettings.value.copy(cardAlpha = cardAlpha)
        _bgSettings.value = newSettings
        prefs.edit().putFloat("card_alpha", cardAlpha).apply()
    }

    private fun loadThemeMode(): ThemeMode {
        val name = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return try { ThemeMode.valueOf(name) } catch (e: Exception) { ThemeMode.SYSTEM }
    }

    fun updateThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    private fun loadAppIcon(): AppIconType {
        val name = prefs.getString("app_icon_type", AppIconType.DEFAULT.name) ?: AppIconType.DEFAULT.name
        return try { AppIconType.valueOf(name) } catch (e: Exception) { AppIconType.DEFAULT }
    }

    fun setAppIcon(context: Context, iconType: AppIconType) {
        val previousIcon = _currentIcon.value
        _currentIcon.value = iconType
        prefs.edit().putString("app_icon_type", iconType.name).apply()

        if (previousIcon == iconType) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val pkgName = context.packageName

                // Enable the selected icon, disable ALL others including MainActivity
                AppIconType.entries.forEach { icon ->
                    val state = if (icon == iconType) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    val componentName = ComponentName(pkgName, icon.aliasName)
                    if (pm.getComponentEnabledSetting(componentName) != state) {
                        pm.setComponentEnabledSetting(
                            componentName,
                            state,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.log(context, "切换应用图标提示: ${e.message}")
            }
        }
    }

    fun syncRetention(context: Context) {
        val savedIcon = loadAppIcon()
        _currentIcon.value = savedIcon

        val savedBg = loadBackgroundSettings()
        _bgSettings.value = savedBg

        val savedTheme = loadThemeMode()
        _themeMode.value = savedTheme

        _parseEndpoints.value = loadParseEndpoints()

        if (!savedBg.customUriString.isNullOrEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val uri = android.net.Uri.parse(savedBg.customUriString)
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Uri permission might already be granted or non-persistable
                }
            }
        }
        AppLogger.log(context, "外观设置自动恢复: 图标[${savedIcon.displayName}], 背景[${savedBg.type.name}], 主题[${savedTheme.displayName}]")
    }

    // ===== 输出目录记忆（每个转换类型记住上次的文件夹）=====
    fun saveOutputDir(type: String, uriString: String) {
        prefs.edit().putString("last_dir_$type", uriString).apply()
    }

    fun getOutputDir(type: String): String? {
        return prefs.getString("last_dir_$type", null)
    }

    fun clearOutputDir(type: String) {
        prefs.edit().remove("last_dir_$type").apply()
    }

    // ===== 第三方解析接口：加载 / 持久化 / 增删改 =====

    private fun loadParseEndpoints(): List<ParseEndpointConfig> {
        val json = prefs.getString("parse_endpoints", null)
            ?: return NetworkDownloader.defaultParseEndpoints()
        return try {
            val arr = JSONArray(json)
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val template = o.optString("template")
                if (template.isBlank()) {
                    null
                } else {
                    ParseEndpointConfig(
                        name = o.optString("name").ifBlank { "接口" },
                        template = template,
                        enabled = o.optBoolean("enabled", true)
                    )
                }
            }
            if (list.isEmpty()) NetworkDownloader.defaultParseEndpoints() else list
        } catch (e: Exception) {
            NetworkDownloader.defaultParseEndpoints()
        }
    }

    private fun persistParseEndpoints(list: List<ParseEndpointConfig>) {
        val arr = JSONArray()
        list.forEach { ep ->
            arr.put(
                JSONObject()
                    .put("name", ep.name)
                    .put("template", ep.template)
                    .put("enabled", ep.enabled)
            )
        }
        prefs.edit().putString("parse_endpoints", arr.toString()).apply()
    }

    fun setParseEndpointEnabled(template: String, enabled: Boolean) {
        _parseEndpoints.value = _parseEndpoints.value.map {
            if (it.template == template) it.copy(enabled = enabled) else it
        }
        persistParseEndpoints(_parseEndpoints.value)
    }

    /**
     * 添加自定义接口模板。要求：https、包含 {url} 或 {bvid} 占位符、
     * 域名为公网地址（用替换占位符后的样例 URL 做安全校验）、不与现有条目重复。
     */
    fun addParseEndpoint(rawTemplate: String): Boolean {
        val template = rawTemplate.trim()
        if (!template.startsWith("https://", ignoreCase = true)) return false
        if (!template.contains("{url}") && !template.contains("{bvid}")) return false
        if (_parseEndpoints.value.any { it.template.equals(template, ignoreCase = true) }) return false

        val sample = template
            .replace("{url}", "https://www.bilibili.com/video/BV1xx411c7m9")
            .replace("{bvid}", "BV1xx411c7m9")
        if (!NetworkDownloader.isPublicHttpUrl(sample)) return false

        val host = Regex("""^https://([A-Za-z0-9.-]+(?::\d+)?)""", RegexOption.IGNORE_CASE)
            .find(template)?.groupValues?.get(1)?.lowercase()
            ?: return false

        _parseEndpoints.value = _parseEndpoints.value +
                ParseEndpointConfig(name = host, template = template, enabled = true)
        persistParseEndpoints(_parseEndpoints.value)
        return true
    }

    fun removeParseEndpoint(template: String) {
        _parseEndpoints.value = _parseEndpoints.value.filter { it.template != template }
        persistParseEndpoints(_parseEndpoints.value)
    }

    fun resetParseEndpoints() {
        _parseEndpoints.value = NetworkDownloader.defaultParseEndpoints()
        persistParseEndpoints(_parseEndpoints.value)
    }

    fun resetAppearance(context: Context) {
        val defaultBg = BackgroundSettings(BackgroundType.DEFAULT_CUSTOM)
        _bgSettings.value = defaultBg
        _themeMode.value = ThemeMode.SYSTEM

        prefs.edit()
            .putString("bg_type", defaultBg.type.name)
            .remove("bg_uri")
            .putFloat("bg_dim", defaultBg.dimAlpha)
            .putFloat("card_alpha", defaultBg.cardAlpha)
            .putString("theme_mode", ThemeMode.SYSTEM.name)
            .apply()

        // 通过 setAppIcon 统一处理状态、持久化与实际组件切换，避免三者不一致
        setAppIcon(context, AppIconType.DEFAULT)

        AppLogger.log(context, "已全部恢复默认外观与主题")
    }

    fun addHistory(fileName: String, conversionType: String, isSuccess: Boolean, outputUri: String? = null) {
        viewModelScope.launch {
            repository.insert(
                ConversionHistory(
                    fileName = fileName,
                    conversionType = conversionType,
                    isSuccess = isSuccess,
                    outputUri = outputUri
                )
            )
        }
    }

    fun deleteHistory(id: Int) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }

    fun deleteHistories(ids: List<Int>) {
        viewModelScope.launch {
            repository.deleteBatch(ids)
        }
    }

    companion object {
        /** 加密 prefs 文件名（不随云备份/设备迁移，见 res/xml 备份排除规则）。 */
        private const val SECURE_PREFS_FILE = "bili_secure_prefs"
        /** SESSDATA 键名（旧版在 app_bg_prefs 明文，现已迁入加密 prefs，键名保持一致）。 */
        private const val KEY_SESSDATA = "bili_sessdata"
    }
}
