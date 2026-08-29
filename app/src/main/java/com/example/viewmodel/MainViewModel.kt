package com.example.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val _bgSettings = MutableStateFlow(loadBackgroundSettings())
    val bgSettings: StateFlow<BackgroundSettings> = _bgSettings.asStateFlow()

    private val _currentIcon = MutableStateFlow(loadAppIcon())
    val currentIcon: StateFlow<AppIconType> = _currentIcon.asStateFlow()

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

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
}
