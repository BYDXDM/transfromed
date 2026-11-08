# transformed Android 端（transfromed-android-fixed）

transformed 桌面工具的 Android 版：EPUB→TXT、MP4→MP3、WebP→JPG 转换 + B站/X/YouTube 视频下载（Kotlin + Compose 实现）。

## 身份说明

- remote 为 `https://github.com/BYDXDM/transfromed.git` —— 这是 transformed 家族唯一的 Android 远程仓库。
- 历史：工作区旧的 `transformed-android/`、`transformed-desktop-main/` 是同远程的旧克隆，2026-09-06 大扫除时已删除；本目录是后续重新拉取并继续开发的活跃克隆，目录名 "fixed" 源于 v1.4.1 修复工作。
- 与 `../transformed-desktop/` 是同一应用的两端：桌面端 Python/ttkbootstrap，Android 端 Kotlin/Compose，功能对齐。
- 起源于 AI Studio（Remix）脚手架：`metadata.json`（"Remix 格式转换"）、`applicationId = com.aistudio.converter.ebabcd`、`assets/.aistudio/`、`.env.example` 为痕迹；`app/` 下代码已完全本地化。

## 技术栈

Kotlin 2.2 + Jetpack Compose（BOM 2024.09）+ Room 2.7 + Retrofit/Moshi/OkHttp + KSP；AGP 9.1.1（CI 用 Gradle 9.3.1）；minSdk 24，targetSdk 36。当前 versionName 1.4.1（versionCode 6）。

## 构建运行

```bash
# 需本机 Android SDK（local.properties 指定路径）与 JDK 17
./gradlew assembleDebug      # 调试包，applicationId 带 .debug 后缀，不覆盖已装正式版
./gradlew assembleRelease    # 正式包（同样用 debug.keystore 签名，见下）
./gradlew test               # 单元测试（另有 androidTest 插桩测试）
```

- CI：`.github/workflows/build.yml` —— push 即在 ubuntu runner 上构建 debug APK 并上传 artifact，无需本机环境。
- 不想本地搭环境时，直接用 GitHub Actions 产物即可。

## 目录要点

- `app/src/main/java/com/example/` — `MainActivity.kt`、`NetworkDownloader.kt`（下载核心）、`data/`（Room 历史记录）、`transfer/`（转换与后台下载服务）、`ui/`（Compose 组件/主题/设置面板）、`viewmodel/`
- `get_latest.py` — 带 SSRF 防护的 Maven 查询脚本（查 arthenica `ffmpeg-kit-audio` LTS 版本；该库已停更，版本需手动锁定）；`res.json` 是一次查询的响应快照
- `debug.keystore` — 提交在仓库的调试签名；**正式包也用它签名**（与手机上已装的 v1.4 签名一致，可覆盖升级，见 `app/build.gradle.kts` 注释）
- `metadata.json` / `.env.example` / `assets/.aistudio/` — AI Studio 脚手架残留，可留作溯源

## 当前状态与断点

活跃（最新提交 2026-11-07 09:34）：

- v1.4.1 已修复 WebP转JPG 与视频下载（对应归档的 `transformed-android-v1.4.1-fixes.patch` 与 `transformed-v1.4.1-debug.apk`，现位于 `_archive/2026-11-07-root-sweep/`）
- 历史记录页按日期分组显示
- release 构建改用 debug.keystore 签名（免上传密钥、可覆盖升级）
- CI 曾因 cmdline-tools 16.0 移除 legacy tools 包全局失败，已改用 runner 预装 SDK（e9e1f99）

## 注意事项

- `NVIDIA Corporation/umdlogs/` 是 NVIDIA 驱动在本目录跑构建/模拟器时落下的空日志目录，与项目无关，可删（2026-11-07 盘点只读，未动）。
- `.env.example` 仅为占位注释（GEMINI_API_KEY 未启用），仓库无真实 `.env`；keystore 密码 "android" 写死在 gradle 中，属调试密钥常规做法，未见其他硬编码密钥。
- `build/`、`.gradle/`、`.kotlin/` 为构建产物，可随时清理重建。

## 签名轮换(2026-11-08)

- 仓库内曾提交的 `debug.keystore`(口令 "android" 写在构建脚本)已出库并删除;**新正式签名 `my-upload-key.jks` 只留本地**,经 `keystore.properties` 引用(已 gitignore)。
- release 构建逻辑:本地有 `keystore.properties` → 正式签名;缺失(如 CI)→ 回退 debug 签名仅供测试。
- **签名已变:发布 v1.5.0(versionCode 7)起,旧版用户需卸载重装。** 旧签名 APK 的历史安装包不受影响,但不再有升级路径。
- 同批附带 2026-11-07 审查的 P0 修复(下载重定向 SSRF 守卫 + DNS rebinding 防护 + B站路由误判修复),详见 CODE-REVIEW.md。
