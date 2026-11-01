package com.example.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.NetworkDownloader
import com.example.R
import com.example.viewmodel.AppIconType
import com.example.viewmodel.BackgroundType
import com.example.viewmodel.MainViewModel
import com.example.ui.theme.ThemeMode

/**
 * 个性化设置面板：主题模式、桌面图标、背景样式、第三方解析接口白名单、
 * B站 SESSDATA（高画质下载凭证）与背景效果参数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bgSettings by viewModel.bgSettings.collectAsStateWithLifecycle()
    val currentIcon by viewModel.currentIcon.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val parseEndpoints by viewModel.parseEndpoints.collectAsStateWithLifecycle()
    val biliSessdata by viewModel.biliSessdata.collectAsStateWithLifecycle()

    var newEndpointTemplate by remember { mutableStateOf("") }
    var sessdataInput by remember(biliSessdata) { mutableStateOf(biliSessdata) }

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

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                            SafeImage(
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
                    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                        SafeImage(
                            drawableRes = R.drawable.img_bg_abstract_glow_1785330326995,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.25f)),
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
                    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                        SafeImage(
                            drawableRes = R.drawable.img_bg_warm_pastel_1785330342798,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("温暖柔和", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                }

                // Option 4: DEFAULT_CUSTOM
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(90.dp)
                        .clickable { viewModel.updateBackgroundType(BackgroundType.DEFAULT_CUSTOM) },
                    border = if (bgSettings.type == BackgroundType.DEFAULT_CUSTOM) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                        SafeImage(
                            drawableRes = R.drawable.bg_default_custom,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.25f)),
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

            // ===== 第三方解析接口（B站音频兜底换源白名单）=====
            Text("第三方解析接口 (B站音频兜底)", style = MaterialTheme.typography.titleMedium)
            Text(
                "官方渠道解析失败时，按顺序尝试以下开放接口。添加的接口会收到视频链接，请只添加信任的节点；模板支持 {bvid} 与 {url} 占位符。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            parseEndpoints.forEach { ep ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                ep.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                ep.template,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = ep.enabled,
                            onCheckedChange = { viewModel.setParseEndpointEnabled(ep.template, it) }
                        )
                        IconButton(onClick = {
                            viewModel.removeParseEndpoint(ep.template)
                            Toast.makeText(context, "已删除: ${ep.name}", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "删除接口",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newEndpointTemplate,
                    onValueChange = { newEndpointTemplate = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("添加接口模板") },
                    placeholder = { Text("https://api.example.com/p?url={url}") },
                    singleLine = true
                )
                TextButton(
                    onClick = {
                        if (viewModel.addParseEndpoint(newEndpointTemplate)) {
                            newEndpointTemplate = ""
                            Toast.makeText(context, "接口已添加并启用", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                context,
                                "添加失败：需为 https 公网接口且包含 {url} 或 {bvid} 占位符",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    enabled = newEndpointTemplate.isNotBlank()
                ) {
                    Text("添加")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    viewModel.resetParseEndpoints()
                    newEndpointTemplate = ""
                    Toast.makeText(context, "已恢复默认解析接口", Toast.LENGTH_SHORT).show()
                }) {
                    Text("恢复默认接口")
                }
            }

            // ===== B站 SESSDATA（高画质下载凭证）=====
            Text("B站 SESSDATA (可选)", style = MaterialTheme.typography.titleMedium)
            Text(
                "下载 720P 及以上画质需要登录凭证。可从浏览器 B站 Cookie 中复制 SESSDATA 的值粘贴到这里，仅保存在本机；留空则按未登录画质（最高 480P）下载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = sessdataInput,
                onValueChange = { sessdataInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("SESSDATA") },
                placeholder = { Text("留空使用未登录画质") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    viewModel.setBiliSessdata(sessdataInput.trim())
                    Toast.makeText(context, "SESSDATA 已保存（留空则按未登录画质下载）", Toast.LENGTH_SHORT).show()
                }) {
                    Text("保存")
                }
            }

            // ===== 下载代理（X/YouTube 等被墙资源）=====
            val downloadProxy by viewModel.downloadProxy.collectAsStateWithLifecycle()
            var proxyInput by remember(downloadProxy) { mutableStateOf(downloadProxy) }
            Text("下载代理 (可选)", style = MaterialTheme.typography.titleMedium)
            Text(
                "X(Twitter) 与 YouTube 的媒体服务器在部分网络无法直连（B站不受影响）。" +
                    "让电脑开启代理软件并允许局域网连接，手机连同一 Wi-Fi，填入电脑的局域网地址，如 192.168.1.5:7890。" +
                    "留空表示直连。仅代理下载流量，不影响其他应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = proxyInput,
                    onValueChange = { proxyInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("代理地址 host:port") },
                    placeholder = { Text("留空直连，如 192.168.1.5:7890") },
                    singleLine = true
                )
                TextButton(
                    onClick = {
                        val spec = proxyInput.trim()
                        val valid = spec.isBlank() || NetworkDownloader.parseProxySpec(spec) != null
                        if (!valid) {
                            Toast.makeText(context, "代理格式无效，应为 host:port（如 192.168.1.5:7890）", Toast.LENGTH_LONG).show()
                        } else {
                            viewModel.setDownloadProxy(spec)
                            Toast.makeText(
                                context,
                                if (spec.isBlank()) "已保存：直连" else "代理已保存: $spec",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = proxyInput.trim() != downloadProxy
                ) {
                    Text("保存")
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("完成设置")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
