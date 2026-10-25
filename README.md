# RushB

基于 Kotlin 和 Jetpack Compose 的独立 CS2 赛事数据工具，支持 Android 8.0 及以上。数据来自 HLTV 的公开页面、移动页面响应，以及 Valve 官方邀请榜单。

RushB 与 HLTV 不存在隶属或背书关系，使用独立名称。项目不提供官方账号或完整性凭据，也不保证来源服务在所有网络下始终可访问。

## 当前版本：0.10.6

| 赛事 | 比赛数据 | 外观设置 |
| --- | --- | --- |
| ![赛事](docs/screenshots/review-v0106/after/03-matches.png) | ![比赛数据](docs/screenshots/review-v0106/after/08-statistics.png) | ![外观设置](docs/screenshots/review-v0106/after/01-appearance.png) |

图片来自实际 Android 模拟器。完整的 [UI 修订记录](docs/UI-REVIEW-v0106.md)、[独立审阅](docs/UI-INDEPENDENT-v0106-final.md) 和 [测试范围](docs/VALIDATION.md) 均在仓库中；克隆后可用浏览器打开 [前后对比画廊](docs/LIGHT-THEME-REVIEW-v0106.html)。

## 功能

- **赛事首页**：日期切换、赛事分组和折叠，按地区、级别与比赛状态筛选，按时间或重要程度排序。重点赛事优先，比赛支持收藏。
- **比赛详情**：分析、七张地图 BP、逐图和系列比分、Both／T／CT 选手统计、头像与对位矩阵。局内比分与地图胜场分开，过期内容不会继续标为实时。
- **赛事赛程**：月历和跨日赛事色条、已知参赛队伍、赛事概览、每日比赛、分组及对阵。重点时间线按明确的 Tier 1／Major 标记筛选。
- **排名与档案**：HLTV 和 Valve VRS 分开显示日期与口径，赛区有文字和彩色侧边。可进入战队、选手的阵容、资料、比赛、赛事、统计、荣誉与相关新闻。
- **资讯**：RSS、网页月度归档、搜索、封面、原生正文与实体跳转，已读内容支持本机恢复。
- **外观与交互**：深色、浅色、跟随系统；固定或悬浮导航、拖动选择页面、自定义启动页。时间按设备时区显示，统计尽量保持同屏。
- **本地资料**：收藏、关注、已读资料、图片与已验证响应缓存。更新失败保留原内容和采集时间，并提供网页连接恢复入口。

0.10.6 移除了队标组件额外绘制的灰底，改进浅色层级、筛选状态、统计列间距，并将外观设置统一为整行入口和单选面板。

## 数据接入与限制

手机默认独立读取数据，不需要电脑中继或 USB 转发。可在“我的 → 数据连接”选择直接 HTTPS 或内置浏览器；需要网站验证时在手机内完成，浏览器会话仅保存在应用私有目录。

移动 JSON、HTML、图片分别校验和缓存；实体 ID、结构及内容类型通过检查后才写入。请求有间隔、大小限制和失败冷却，保留 403、验证页、完整性要求、429 等不同错误。合法的 0 与缺失值分开处理。

以下范围仍需完善：

- 真机 Wi-Fi／移动网络、更多 Android 版本和不同来源响应的持续验证。
- 需要官方应用身份的完整性授权，不能靠复制请求头获得；一次成功读取不代表长期可用。
- 部分长期统计口径、历史深层分页、少量图片来源和复杂赛事分组仍有缺失。
- 当前没有账号同步、系统通知、社区、Fantasy 或赔率服务。
- Valve VRS 使用官方邀请榜单，不能视为 HLTV 的实时 VRS 排名。

详见 [验证记录](docs/VALIDATION.md)、[开发路线](docs/ROADMAP.md) 和 [移动协议调查](docs/HLTV-MOBILE-API-RESEARCH.md)。模拟器结果不替代真机验收。

## 构建与运行

需要 JDK 17 或以上、Android SDK Platform 37.2、Build Tools 36.0.0。Gradle Wrapper 和依赖版本已固定。首次构建需要联网下载依赖；SDK 路径使用本机配置，不提交到仓库。

Windows：

~~~powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
~~~

Linux / macOS：

~~~sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
~~~

调试 APK 输出到 app/build/outputs/apk/debug/，提供 ARM64、ARM 32 位和 x86_64。源码仓库不包含 APK、签名密钥或 SDK；正式发布需要开发者配置自己的签名。

模拟器回归、离线 JS 检查及可选联网专项见 [构建与测试](docs/BUILDING.md)。开发用电脑中继保留为 [可选诊断工具](tools/hltv-relay/README.md)。

## 工程结构

~~~text
app/src/main/java/dev/matchscope/app/
  data/                 数据来源、解析、校验、缓存和记录
  ui/                   Compose 页面、导航、主题与组件
app/src/main/assets/    来源声明、Valve 快照、受限网页读取扩展
app/src/test/           JVM 单元测试与离线解析样本
app/src/androidTest/    模拟器回归及显式启用的联网专项
tools/                 离线检查、模拟器运行脚本和开发诊断
docs/                  构建、研究、审阅、验证记录与截图
~~~

早期视觉参考保留在 [index-1.html](index-1.html)，其中的展示数据不作为正式来源。测试样本仅用于回归，不打包进生产应用。

## 参与开发与许可证

遵循 [贡献规则](CONTRIBUTING.md)：验证完成后及时合入 main，确认远程合并成功后删除对应临时分支；不强推主分支，不删除未合并工作。

代码采用 [AGPL-3.0](LICENSE)，沿用本仓库的原许可证。GeckoView 使用 MPL 2.0，对应源码及声明见 [引擎说明](app/src/main/assets/geckoview-notice.txt)。其他依赖、HLTV 页面内容、徽标、照片与 Valve 数据保留各自权利，不因 RushB 的代码许可证而改变权属。
