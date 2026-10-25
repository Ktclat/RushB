# 构建与测试

## 环境

- JDK 17 或以上；本地验证使用 JDK 23，CI 使用 JDK 21。
- Android SDK Platform `android-37.2`、Build Tools `36.0.0`、Platform Tools。
- 项目自带 Gradle Wrapper 9.6.0，固定下载摘要；AGP 9.4.0、Compose 编译插件 2.4.20。
- Node.js 22 或以上用于读取扩展、中继策略的离线检查；Python 3.10 或以上用于模拟器运行脚本及诊断工具。

Android Studio 打开项目根目录后配置 SDK。命令行可设置 `ANDROID_HOME`，或让 IDE 生成仅用于本机的 `local.properties`。不要提交 SDK 绝对路径、代理地址、签名配置或凭据。

SDK Manager 安装包示例：

```sh
sdkmanager "platforms;android-37.2" "build-tools;36.0.0" "platform-tools"
```

## 常规构建

Windows：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
node tools/test-reader.mjs
node --test tools/hltv-relay/policy.test.mjs
```

Linux / macOS 使用 `./gradlew` 替代 `gradlew.bat`。首次下载依赖需要联网；上述 JVM、JS 检查不访问真实 HLTV。

APK 在 `app/build/outputs/apk/debug/`，提供 arm64-v8a、armeabi-v7a、x86_64。调试包包含完整 GeckoView，引擎使安装包较大。当前最低 Android 8.0 / API 26，目标 API 36；编译平台 37.2 不代表最低安装版本升至 37。发布包需要开发者自己的签名，不使用仓库提供的通用密钥。

## 模拟器常规回归

先启动专用 Android 测试模拟器，并完成上面的两个 APK 构建任务。脚本要求明确指定设备，避免误操作日常手机：

```powershell
adb devices
python tools/run-android-tests.py --serial emulator-5554 --abi x86_64 --install
```

脚本安装应用和测试 APK，运行排除联网诊断类的常规回归，检查 instrumentation 的实际成功结果。测试会修改专用设备中的测试状态，不应对保存重要数据的设备运行。没有 `--install` 时使用设备上已有的应用及测试 APK；日志可通过 `--output .local/android-tests.log` 保存。

常规回归覆盖解析适配、缓存记录、页面导航、设置持久化、主题、布局及 UI 交互，不能证明 HLTV 在当前网络始终可访问。

## 真实联网专项

联网专项不在默认 CI 中运行。使用独立测试设备，关闭电脑中继及 ADB reverse，再明确选择对应测试类和参数。先阅读各测试类的入口条件，避免批量连续发起请求。

- `MobileStandaloneTest`：移动 JSON 单路由。
- `MobileWorkflowTest`：生产 ViewModel 与 repository 的完整手机路径。
- `WebsiteNewsNetworkTest`：网页新闻读取。
- `MobileMediaProbeTest`、`BrowserMediaProbeTest`、`BrowserArtworkSessionTest`：图片和浏览器会话专项。
- `PhoneSideStatisticsTest`：真实比赛阵营统计。

请求失败时记录 403、验证页、完整性要求、429 或结构错误，保留原始资料时间；不要通过反复刷新绕过冷却。应用自己的验证会话不应导出到仓库。

## 持续集成

GitHub Actions 在 `main` 推送和 Pull Request 上运行 JVM、Lint、调试包构建及 JS 离线检查。工作流只授予仓库读取权限，不使用站点账号、签名密钥或浏览器状态。模拟器实测按上述流程单独进行。
