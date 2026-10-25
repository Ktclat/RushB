# RushB 功能与来源研究

核验日期为 2026 年 10 月 18 日。RushB 使用独立名称，以原生 Android 阅读体验承载 CS2 赛事信息。用户提供的 HTML 作为视觉与导航参考，其随机生成数据不进入正式数据层。

本文保留历次调查的时间顺序，早期“首版”能力与访问失败不代表当前版本。当前功能与验证范围见 [项目说明](../README.md) 和 [验证记录](VALIDATION.md)。

## 功能与字段

| 模块 | 来源 | 已核验内容与实现边界 |
| --- | --- | --- |
| 比赛 | [HLTV Matches](https://www.hltv.org/matches) | 待开始与进行中比赛、时间、战队、赛事、赛制、筛选。只有 UTC 时间戳才能转换时区，网页显示时区不明时保留日期与未知时刻。 |
| 赛果 | [HLTV Results](https://www.hltv.org/results) | 历史比赛、比分、赛事、BO 类型。系列赛比分与地图回合分必须分开。 |
| 赛事 | [HLTV Events](https://www.hltv.org/events) | 赛事日期、地点、奖池、参赛数量；详情有参赛队伍与淘汰赛结构。首版只做资料快照浏览。 |
| 战队与排名 | [2026 年 10 月 12 日榜单](https://www.hltv.org/ranking/teams/2026/october/12) | 名次、HLTV 积分、变化、该期阵容。阵容不能无条件标为当前阵容。 |
| 统计 | [Rating 3.0 说明](https://www.hltv.org/news/42485/introducing-rating-30) | Rating 3.0 引入经济因素调整与 Round Swing；模型保存来源 Rating 版本，不自行计算或用 K/D 替代。 |
| 选手档案 | [NiKo](https://www.hltv.org/player/3741/niko) | 0.2 已接入真实姓名、国籍、年龄、当前战队、Rating 3.0 与能力指标；保留“过去三个月 / 43 张地图”的来源区间。 |
| VRS | [Valve 官方仓库](https://github.com/ValveSoftware/counter-strike_regional_standings) | 官方邀请榜单按日期、地区发布；独立保存积分与阵容。与 HLTV 世界排名是不同来源和不同口径。 |
| 新闻 | [HLTV 首页](https://www.hltv.org/) | 首页提供 RSS 入口；首版仅获取 RSS 标题、日期及原文链接，正文交由浏览器打开。 |

HLTV 还提供转会、地图统计、对比、MVP/EVP、历史年度 Top 20、论坛与 Fantasy。这些需要进一步明确数据覆盖范围和交互，不作为本次已经完成的功能。

## 首版资料快照

每条比赛都保存来源链接；快照状态是核验时的状态，不会因系统时钟前进而自行改变成实时赛况。

| 内容 | 来源 |
| --- | --- |
| Aurora 对 Falcons，10 月 18 日，BO3，未开始 | [比赛页面](https://www.hltv.org/matches/2399416/aurora-vs-falcons-thunderpick-world-championship-2026) |
| FURIA 对 BETBOOM，10 月 18 日，BO5 决赛，未开始 | [比赛页面](https://www.hltv.org/matches/2399417/furia-vs-betboom-thunderpick-world-championship-2026) |
| Falcons 对 BETBOOM，10 月 17 日，1 比 2，地图与部分统计 | [比赛页面](https://www.hltv.org/matches/2399415/falcons-vs-betboom-thunderpick-world-championship-2026) |
| Thunderpick World Championship 2026，10 月 14–18 日，马耳他，8 队，奖池 100 万美元 | [赛事页面](https://www.hltv.org/events/8932/thunderpick-world-championship-2026) |
| HLTV 前六：Spirit、Vitality、MOUZ、FURIA、Falcons、Aurora | [10 月 12 日榜单](https://www.hltv.org/ranking/teams/2026/october/12) |
| Valve 世界及地区邀请榜单 | [2026 年 10 月 5 日世界榜单](https://github.com/ValveSoftware/counter-strike_regional_standings/blob/main/invitation/2026/standings_global_2026_10_05.md)；同目录 europe、americas、asia 文件 |

0.1 未获得可验证的时间戳，保留了来源日期与未知时刻。0.2 已从真实 DOM 核验三个时间戳并校准初始资料：Aurora–Falcons 为 UTC+8 的 10 月 18 日 19:30；FURIA–BETBOOM 为同日 23:00；Falcons–BETBOOM 为同日 01:00（UTC 为 10 月 17 日 17:00）。各比赛保存独立的来源与采集时间，不再把来源网页的日期直接当成 UTC+8 日期。

## 接入方案

[gigobyte/HLTV](https://github.com/gigobyte/HLTV) 是非官方 Node.js HTML 解析库，文档说明请求数量与 Cloudflare 限制。其接口是解析网站的封装，不是 HLTV 提供的官方 API。本项目不依赖其运行时，也不将它的可用性视为保证。

首版将来源适配与 UI 分开：页面只消费 `Feed<T>`，读取成功后才提交缓存；来源受限或结构改变时保留资料。真实刷新采用 HTTPS、超时、请求间隔和短时冷却，不包含验证码自动化、登录 Cookie 提取或保护绕过。

后续如取得稳定数据服务，可通过 Repository 替换直连实现。集中服务有利于共享缓存和控制总请求量，但需要部署位置、维护方式与来源配额等具体约束；当前版本没有虚构服务器地址。

## 0.2 的 403 调查与接入证据

2026-10-18，本机直接访问 `/matches` 返回 HTTP 403、`cf-mitigated: challenge` 和验证页标题。Cloudflare 文档将此响应头定义为验证响应的识别方式，参见 [Detect a Challenge Page response](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/)。这是实际观察到的验证拦截，不是 JSON API 地址写错。

Android API 36 WebView 使用默认浏览器标识、JavaScript 和 DOM Storage，仍停留在验证页。Cloudflare 对嵌入式浏览器的支持有局限，参见 [Supported browsers](https://developers.cloudflare.com/cloudflare-challenges/reference/supported-browsers/)。普通 Android Chrome 能否打开网页、WebView 是否能通过验证、应用是否能读取页面，必须分别测试；当前不能据桌面成功推断手机成功。WebView 实现参考 [Android WebView 文档](https://developer.android.com/develop/ui/views/layout/webapps/webview)。

本机正常、有窗口的 Edge 独立会话能读取 `/matches`、普通 `/results`、比赛和选手档案。项目据此增加开发中继，由 Playwright 驱动系统 Edge，再将受限制的 DOM 快照交给 Android 解析。没有使用指纹隐藏插件、验证码自动解答或日常浏览器 Cookie 导出。遇到人工验证时保留窗口并提示用户操作；这不是站点验证永久可用性的保证。

用户同意开发初期使用电脑中继，并要求以后完成手机独立运行验证。服务只监听回环地址，手机经 USB 转发读取；部署与操作见 [中继说明](../tools/hltv-relay/README.md)。日期筛选的赛果 URL 仍触发验证，因此近期数据优先使用普通赛果页的有限返回范围。

## 真实页面中的解析差异

- 比赛列表使用 `.match-wrapper[data-match-id]`；LIVE 当前图回合分和系列图数位于不同属性，不能混用。
- 赛果精选区与列表会重复同一比赛；优先保留带日期的普通行。`data-zonedgrouping-entry-unix` 是赛果记录时间，保存为 `resultAt`，不冒充 `startsAt`。
- 比赛详情从 `.timeAndEvent` 的 `data-unix` 取得开赛时刻；从两队总统计表读取全部十名选手。K-D、ADR、KAST 采用传统数据列，避免混入隐藏的经济调整列；Rating 保留页面的 3.0 版本。
- 未开始比赛从阵容区域取得双方选手 ID，不凭空填写比分或赛后统计。
- 选手资料按实际标签读取 Rating 和能力指标，保留统计区间，不用旧版页面中固定位置的 KPR/爆头率字段推断新值。

经过裁剪的五份真实 DOM 位于 `app/src/test/resources/hltv/`，包含采集来源说明。运行证据和边界见 [验证记录](VALIDATION.md)。

## Android 技术选择

沿用本机可构建的工具链，使用 [AGP 9.4.0 官方兼容表](https://developer.android.com/build/releases/agp-9-4-0-release-notes) 对齐 Gradle 9.6.0、SDK Build Tools 36.0.0 与 JDK 17。最低系统版本为 Android 8.0；Compose 负责原生界面，ViewModel 保存会话状态，本地偏好保存关注和设置。


## 0.3 图片与档案补充（2026-10-18）

从 [Falcons 战队页](https://www.hltv.org/team/11283/falcons)、[NiKo 档案](https://www.hltv.org/player/3741/niko)、[Thunderpick 赛事页](https://www.hltv.org/events/8932/thunderpick-world-championship-2026) 以及已有比赛页面核验图片与资料字段。代码保留来源提供的带签名图片地址，不手工拼接 CDN 文件名、不改动查询参数，也不抓取广告背景作为赛事封面。

- 战队页 `.bodyshot-team` 提供当前选手 ID、昵称和全身照片；`.profile-team-stat` 中的 World ranking 与 Valve ranking 分开处理。
- 已结束比赛的照片由 `a[href=/player/…]` 标识，未开赛页面则使用 `.player-compare[data-player-id]`；两种结构都按选手 ID 关联照片和昵称，不能按显示顺序猜测。
- 选手主照片来自 `.player-summary-stat-box-left-bodyshot`；不使用侧栏“本周选手”的图片。
- 赛事当前图标来自 `.event-sidebar`，日期、地点、奖池与队伍数量来自 `table.info`；不误用“相关赛事”的上一届图标。
- 真实页面有 PNG 和 SVG 徽标，明暗两套图片需选择与应用浅色背景对应的版本。缺少图片时显示本地占位，不影响其余资料。

图片通过手机侧 Coil 从 `img-cdn.hltv.org` 按需读取，缓存最多 64 MiB，内存缓存上限为进程可用内存的 12%。仅允许 HTTPS 和已识别的徽标/照片目录，重定向也校验。现有 SDK 36 使用 Coil 3.5.0；3.6.x 需要 SDK 37，暂未因此扩大工程升级范围。依据：[Coil 图片加载与缓存](https://coil-kt.github.io/coil/image_loaders/)、[SVG 支持](https://coil-kt.github.io/coil/svgs/)、[版本记录](https://coil-kt.github.io/coil/changelog/)。

海报式战队、选手和赛事卡片由 Compose 排版，使用真实身份图片；它们不是官方宣传海报。图片权利不包含在项目代码的 AGPL-3.0 许可内。CDN 图片加载成功只证明图片路径可用，不等于手机已独立读取 HLTV 的赛事 HTML。


## 0.4 手机独立接入（2026-10-18）

在同一 Android 16 模拟器和默认 NAT 网络中做对照：没有启动电脑中继，没有 adb reverse 转发，也未设置模拟器 HTTP 代理。系统 WebView 133.0.6943.137 停在浏览器验证页；普通 Android Chrome 能打开 HLTV。内置官方 GeckoView 158 后，RushB 能直接读取正文并在原生界面生成真实赛程/赛果。

采用 Mozilla 官方 Maven 稳定包 `org.mozilla.geckoview:geckoview:158.0.20261007201254`。该包的 AAR 元数据要求 compileSdk 37.2，因此仅提升编译平台，保留 minSdk 26 与 targetSdk 36。按 arm64-v8a、armeabi-v7a 和 x86_64 分包并压缩原生库。依据：[GeckoView 入门](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/geckoview-quick-start.html)、[Android 编译平台配置](https://developer.android.com/build)。

GeckoRuntime 使用应用私有的持久浏览器配置。每个页面请求使用独立 GeckoSession，默认用户代理、TLS、JavaScript 和浏览器功能；不改指纹，不从 Chrome/Edge 导出 Cookie，不把手机验证结果转给另一种 HTTP 客户端。首次打开和每次需要重新读取时使用浏览器的 bypass-cache 加载标志，页面 TTL 缓存仍由 PageStore 管理。

依据 [GeckoView 与网页内容交互](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/web-extensions.html)，APK 内置只匹配 HTTPS HLTV 顶层页面的读取扩展。它在隔离的内容脚本环境中，收到本次读取指令才返回去掉脚本、样式、表单、iframe 的 DOM。不会读取 Cookie、输入值或向远端上传 DOM。原生端检查消息所属 session、顶层 frame、内容脚本来源、目标页面编号和请求编号；关闭/取消后的旧响应不能完成后续请求。最终解析通过后才写页面缓存。

挑战页留在可见浏览器供用户处理。自动等待只轮询本机 DOM，不重新发起页面请求；90 秒后停止自动轮询，仍可手动读取。发现挑战后不写成功缓存；解析阶段发现的挑战也会进入五分钟来源冷却。取消会关闭当前标签页，浏览器配置留在本机便于后续访问。

[Cloudflare 支持环境说明](https://developers.cloudflare.com/cloudflare-challenges/reference/supported-browsers/)指出嵌入式环境支持有限，并建议用真实设备验证；[挑战机制说明](https://developers.cloudflare.com/cloudflare-challenges/concepts/how-challenges-work/)也列出了修改浏览器 API、会话 IP 改变等可能导致失败的情况。因此，这次实测支持“已在指定 Android 环境实现独立读取”，不能推断任何设备/网络永久免于 403，或把模拟器当作真机验收。

主动刷新与自动恢复现已分开：UI 的刷新操作向 PageStore 传入 forceRefresh，绕过 TTL 命中但保留限速与挑战冷却。新增回归验证“新缓存仍需重新读取”“失败保留原时间”“主动刷新不能绕过冷却”；独立联网用例还会在一小时 TTL 内再次读取同一选手，断言来源为 NETWORK 且采集时间增长。


## Android 移动协议补充（2026-10-18）

后续 REA 分析已确认官方 Android 有移动 JSON 接口，并在 3.0.59 中发现 Play Integrity 会话链。此前“没有已确认的官方 API”应理解为没有承诺给第三方使用的稳定公共开发接口，不是不存在移动内部协议。普通请求仍被 Cloudflare 拒绝。完整路由、公开逆向库比较及验证边界见 [移动协议调查](HLTV-MOBILE-API-RESEARCH.md)。


## 0.6.1 移动 JSON 落地

Android 本机原生请求和内置浏览器 JSON 检测均已取得真实数据；手机默认来源已切换至独立的 JSON 适配层。此前电脑 403 仍是有效历史证据，不能代表这次 Android 请求。比赛对位矩阵、在线赛事索引、赛事完整响应内赛程、战队和选手赛事历史及新闻首页已接入。请求与缓存校验、模拟器全流程结果及完整性授权边界统一见 [当前验证记录](VALIDATION.md) 和 [网络证据](evidence/network-v061.json)。
