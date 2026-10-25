# HLTV Android 移动协议调查

核对日期：2026-10-18。对象：用户提供的官方 Android 3.0.59 安装包及公开项目源码。

**结论：官方 Android 的主要资料读取走结构化移动 JSON 接口。3.0.59 还包含 Google Play Integrity 握手和独立的完整性会话，不能把旧版逆向库的“无需 API key”理解为新版所有接口都能匿名直连。** 本次普通 GET 仍返回 Cloudflare 403，尚未获得可用于 RushB 联网验收的移动 JSON。

## 已核实的请求方式

移动基址为 `https://www.hltv.org/mobile`。`kc0` 构建 URL 和查询参数，通过 Ktor 客户端发送请求，再把成功响应转换成 `org.hltv.mobile.data` 下的实体。`MatchScreen` 返回 `MatchScreenData`；这是页面级 JSON 数据聚合，与当前 RushB 的 HTML 选择器解析路径不同。

| 用途 | 观察到的 GET 路由 | 参数 | 3.0.59 定位 |
| --- | --- | --- | --- |
| 比赛列表 | `/MatchesV4` | 无 | `kc0.P` |
| 比赛详情 | `/MatchScreen` | `matchId` | `kc0.N`，本轮 REA 方法证据 |
| 赛事列表 | `/EventsData2` | 无 | `kc0.w` |
| 赛事详情 | `/EventDetails2` | `eventId` | `kc0.t` |
| 战队档案 | `/TeamScreen` | `teamId` | `kc0.c0` |
| 选手档案 | `/PlayerScreen` | `playerId` | `kc0.X` |
| 选手对比 | `/PlayerCompare` | `playerIds` | `kc0.V`；列表编码仍需响应样本校准 |
| 战队／选手新闻 | `/TeamNews`、`/PlayerNews` | `teamId`、`playerId` | `kc0.d0`、`kc0.Y` |

除本轮 REA 直接检查的 `MatchScreen`，表中其他路由来自同一 APK 已保存的 Android SDK `dexdump` 指令边界证据，并与公开客户端源码交叉核对；不是声称每个端点都已联网成功。详细偏移见 `docs/evidence/mobile-api-research.json`。

`kc0.a` 只在已有值时附加 `PHPSESSID` / `autologin` Cookie；`kc0.N` 会检查 `Set-Cookie`，更新或清除登录状态。这说明登录是单独的会话机制，不能用“有 Cookie”概括所有访问条件。

## 新版的完整性校验

REA 定向分析发现以下链条，未执行握手、获取证明或导出参考应用会话：

1. `lv4.s` 构造 `/mobile/integrity/challenge`，解析 `IntegrityChallengeResponse`。
2. `pn7.a` 调用 Google Play Standard Integrity 提供者，包含 SHA-256 请求摘要处理，返回证明对象。
3. `c10.s` 构造 `/mobile/integrity/session`，发送结构化会话请求并解析 `IntegritySessionResponse`。
4. `fv4.a` 根据会话状态设置 `X-App-Integrity` 与 `X-App-Integrity-Provider`。没有可用会话的分支不附加它们。
5. `zf2.s` 识别要求完整性校验的响应，刷新会话后重发一次，并用 `IntegrityRetried` 标记防止重复循环；再次拒绝时记录 `IntegrityRejectedException`。

`kc0.N` 明确区分 `401 + X-App-Integrity-Error: required` 和普通错误。`403` 分支再调用 `kc0.r0`，根据 `Server`、`cf-ray`、`cf-mitigated` 判别 Cloudflare 来源；另有 404、408、429、5xx 和 JSON 转换失败分支。**Cloudflare 响应头本身不是失败证明：200 JSON 也可以由 Cloudflare 正常返回。**

Google 的完整性判定包含应用包名、签名识别、安装许可和设备等信号，后端决定如何使用结果。由此推断，RushB 的独立包名和签名不能通过复制官方请求头自动取得同等身份。不能从客户端静态代码得知 HLTV 当前在哪些端点、设备或环境强制执行这些检查。[Google Play Integrity 概述](https://developer.android.com/google/play/integrity/overview)，[判定字段](https://developer.android.com/google/play/integrity/verdicts)。

已观察到两层不同的限制：Cloudflare 在请求抵达业务 JSON 前即可拒绝；应用完整性是客户端代码中存在的另一条校验链。本次只实际收到前者，**没有把本次 403 归因于已经发生的 Play Integrity 拒绝**。

## 对功能补齐的价值

同一 APK 的已核对 serializer 字段与公开库模型表明，移动协议覆盖了此前需求中的大部分结构：

| 需求 | APK 中的结构证据 | 接入时必须保留的区别 |
| --- | --- | --- |
| VRS 预测／本场影响 | `vrsForCast`；`VrsMatchForecast` 的双方当前／胜／负分数及排名 | 投影总分与增量尚须实际响应和页面一起核对 |
| 地图对比 | `mapStats`、双方 `TeamMapStats`、地图列表及 `veto` | 胜率、选图率、禁图率不是同一指标 |
| 近期表现／历史交战 | `team1FormMatches`、`team2FormMatches`、`headToHead` | 系列赛分数与单图回合分数分开 |
| 单图统计／对位矩阵 | `MatchMapStats.playerStats`、`teamStats`、`killMatrixes` | 每张地图独立；矩阵有列选手 ID、行选手 ID 和 kills/deaths |
| 赛事分组与对阵 | 赛事分组模型；公开库有 `swissGroup`、`regularGroup`、`eslGroup` | 分组类型需以 3.0.59 实际 payload 校准，不推测未知赛制 |
| 选手／战队资料与新闻 | `PlayerScreen`、`TeamScreen`、独立新闻路由 | 当前阵容、历史比赛和统计时间范围分开 |

此前模型证据见 [REA 比赛模型摘要](evidence/rea-v06-summary.json)。矩阵类型的实际 JSON 枚举、行列方向、缺失值和比赛／统计／地图统计 ID 映射仍须用真实响应校准；不能因为存在模型就宣布原生矩阵已经完成接入。

## 找到的公开实现

| 项目 | 类型与维护情况 | RushB 可参考的部分 |
| --- | --- | --- |
| [pickem-prediction-model](https://github.com/Leclowndu93150/pickem-prediction-model/blob/e6ad6fe4f6e13201e11e65242a9cc86cb87f0fef/hltv_api/client.py) | 移动 JSON 逆向客户端，源码注明 Android 3.0.34；MIT；最后代码提交 2026-06-10 | 路由、DTO、按端点缓存。最相关，但不是新版协议的可用性保证 |
| [gigobyte/HLTV](https://github.com/gigobyte/HLTV) | HTML 抓取；README 声明不再积极维护；npm 最新 3.5.0 发布于 2023-09-20 | 网页解析语义；不能解决移动端完整性校验 |
| [Invoser/HLTV](https://github.com/Invoser/HLTV)、[cybersamuurai/HLTV](https://github.com/cybersamuurai/HLTV) | 2026 年有实质改动的 fork，仍采用网页／浏览器方案 | 近期网页选择器修复，不能直接当 Android SDK |
| [hltv-async-api](https://github.com/akimerslys/hltv-async-api) | Python HTML 抓取；2026 最新提交只是文档翻译，Parser 最后修改于 2024-07-30 | 不宜仅按仓库最近更新时间判断维护状态 |

移动客户端候选的通用 GET 没有必需 API key 或签名，登录 Cookie 是可选项；它依赖 `curl_cffi` 身份／TLS 模拟及可选代理应对拒绝。未在本轮运行它，未验证其“避免 403”的宣称；其客户端源码未覆盖本次 APK 观察到的完整性会话链。

还发现两个具体质量限制：它用 Python `or` 回退比分，可能把合法的 0 当缺失；常用 Node 主库及两个 fork 的 `getMatchMapStats.ts` 仍保留 `TODO: kill matrix`。不能直接复制模型或把这些库当作已实现的对位数据源。其他 Go／托管 API 大多是网页或数据库的 JSON 包装。

完整八项比较、许可证差异、固定 commit 和检索边界见 [公开库调查](HLTV-LIBRARIES.md)。本轮针对 `X-App-Integrity` / `integrity/session` 的追加搜索没有找到可核实的新版 HLTV 开源实现；这不是“全网不存在”的结论。

## 实际请求与验证

本机在 2026-10-18 13:57:07 UTC 向 `MatchScreen?matchId=2399415` 发出一次普通 GET：RushB 身份、Accept JSON、无登录／完整性凭据、无自动重试。0.46 秒后收到 HTTP 403，Content-Type 为 HTML，5573 字节，为 Cloudflare 拒绝页。

响应摘要 SHA-256：`fa7a8a4b169337ba4fb50a7fa388790163a3c6c19b12d2197517ead771e89f3e`。元数据保存在 [调查证据](evidence/mobile-api-research.json)。这是电脑上的请求，**不是 Android 无中继联网通过，也不是官方 APK 的运行时测试**。本轮没有操作实体手机。

新增 [单次诊断工具](../tools/hltv-mobile-probe.py)，便于后续在网络条件改变时复查：

```powershell
python tools/hltv-mobile-probe.py match --id 2399415
```

它只允许表中六类只读路由，实体 ID 必须为正整数；使用 RushB 身份，不读取浏览器会话，不自动重试或跟随重定向。分别记录完整性要求、Cloudflare 挑战／拒绝、普通 401／403、429、重定向、HTTP 错误、错误内容类型和无效 JSON。正常 JSON 仅保存为“结构未验证”的独立样本；失败不存 HTML 正文、不记录 Cookie、不改应用缓存。输出在 `.local/hltv-api-probe/`，退出码 0 只代表收到可解析的对象／数组，2 代表请求或格式未通过。

17 项离线检查通过，覆盖错误分类、200 + Cloudflare 头正常 JSON、0 与 null、参数拒绝、超大响应，以及拒绝响应不保存 payload／Cookie。检查不发网络请求，不能替代端到端验收。

## 对 RushB 的落地决定

- 数据源设计增加明确的移动 JSON 适配方向：按比赛、赛事、战队、选手路由映射到现有实体，保留当前 HTML 读取与缓存，不把浏览器页面和 JSON 混入同一解析器。
- 先拿到 RushB 自身可正常读取的真实 JSON，再校准 DTO 和渲染；默认数据源切换必须通过模拟器无中继、无端口转发、首次读取／刷新／离线恢复的验收。目前不具备这个条件，0.6.0 默认源保持原状。
- 优先补齐 JSON 能可靠给出的单图统计、击杀矩阵、赛事分组与完整档案。缺失值保持 null，0 保持 0，缓存按来源／实体／版本分开，错误响应不覆盖成功资料。
- 失败分类与有限重试是可借鉴的设计；官方应用的完整性证明依赖其身份，不能把 Cookie、请求头或旧库指纹当作 RushB 的长期访问契约。值得进一步明确的是移动 JSON 是否存在面向独立客户端的访问方式或豁免，当前没有这方面证据。

本轮交付为协议调查、证据索引和诊断工具；未把未验证的移动接口切为生产源，未重建 APK，也未将旧版联网或界面测试结果计入本轮。

## REA 证据与可靠范围

实际使用 REA 6.5.0 的 `inspect-android-package`、`search-android-classes`、`inspect-android-class`、`inspect-android-method`，通过 JADX headless MCP 0.7.1 静态分析。工具仅以进程环境加载工作区 JAR，未修改全局 REA／Codex 配置。

- APK：`org.hltv.android` 3.0.59 / code 124；SHA-256 `e92b3d85ee65861488285161f8986ae8f453db3bfdb90dfa62f7bb0ef3784188`。
- JADX JAR：SHA-256 `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`，与 [官方 0.7.1 发布资产](https://github.com/1013503897/jadx-headless-mcp/releases/tag/v0.7.1) 的摘要一致。
- 包证据：`ev_613d0d18402d55ba6870767e78c9c95031ef0bdd595c475d97c8bc1614154c5b`。
- 请求构造 `kc0.U`：`ev_f7922891833bea5d4103d8c285a60a0a90af8a22078e693fb6276d4ee1180930`。
- 比赛请求 `kc0.N`：`ev_d317010f1c731c25d2ad19375f0855bf3fd88b0ffaa655303ea9ab39f9eb3598`。
- 完整性请求头 `fv4.a`：`ev_95d5b74080f1c58f66dc78cb5550f9c1b13637b1166653cee4fd42a41517c479`。
- 挑战 `lv4.s`：`ev_0a7a60cce8fb85b1db131df79a2f8acc3cf340393a97f283c6fd4a807bddc552`。
- 会话交换 `c10.s`：`ev_abd7840dc8cbbbc0d805a33891d34e91376fca503c31dcda63098b883d9435b4`。
- 重试拦截器 `zf2.s`：`ev_bedc0c4a0e6dc45a4b5494bc010026307006cd692ede38be7ab57c44f9c479e4`。

`kc0.N`、`zf2.s` 的 Java 还原失败，REA 已回退到 Smali；`pn7.a`、`pv4.a` 等方法的 Java 输出含重复块或类型推断警告。因此没有将反编译文本当成可直接编译的原源码，也没有据不可靠控制流给出精确重试间隔或缓存 TTL。路径、头部和目标模型等局部事实保留方法定位，原始结果与摘要见 [机器可读证据索引](evidence/mobile-api-research.json)。APK 未运行，签名真实性未由 REA 校验，静态链条不证明服务端当前强制策略。


## 0.6.1 后续 Android 运行时验证（2026-10-18）

上述电脑 403 和静态调查结论保留为当时结果。随后在 `emulator-5554`（Android 16 / API 36 / x86_64）中，由 RushB 自身的 OkHttp 请求 `MatchScreen?matchId=2399415`，收到 HTTP 200、application/json、262,142 字节，并验证比赛 ID 为 2399415。未传登录 Cookie 或完整性头，没有桌面中继、ADB reverse 或模拟器 HTTP 代理。

在该环境下，MatchesV4、EventsData2、EventDetails2、TeamScreen、PlayerScreen、TeamNews、PlayerNews 也取得有效响应；四个 Brackets 嵌入文档由手机直接读取。生产 ViewModel 和 repository 的全流程检查确认六类主体数据均来自各次刷新开始后的 NETWORK 结果，已切换为手机默认来源。内置 GeckoView 的 JSON 路径单独实测通过；当前设置中的“网页验证”用于连接检测，并不自动替换生产请求的传输方式。

真实响应校准了 VRS 总分与增量、地图使用率、Both 与单图统计、对位矩阵行列方向，以及战队／选手的完整响应内赛事历史。矩阵按选手 ID 对齐，只有所有已完成地图均有完整矩阵才生成系列赛总览；没有把缺失值当零。移动 JSON 的球队字段差异、待定队伍、合法 0 比分、新闻地址及赛事嵌入身份均已校验。

这次成功请求没有出现 `X-App-Integrity-Error: required`；因此没有实现或宣称取得官方完整性会话。RushB 已区分完整性要求与 Cloudflare 403，并以受限冷却和缓存处理；错误分类测试不证明 HLTV 后端会为 RushB 接受 Play Integrity 证明。未来若强制授权，需要独立客户端获准的服务端接入安排。

准确时间、来源、响应摘要、最终流程与限制见 [0.6.1 网络证据](evidence/network-v061.json) 和 [验证记录](VALIDATION.md)。早期调查中“未接入”的描述只对应 0.6.0 阶段，当前版本已完成上述范围。


## 0.6.2 连接恢复与资料扩充（2026-10-19）

手机浏览器现已作为实际 JSON 连接方式提供选择，非仅检测入口。原生与浏览器两次生产流程分别取得八类 JSON 与四个新对阵，均无电脑中继。此前网络失败、Cloudflare 挑战和浏览器对阵等待超时也保留证据；增加持久冷却、完整性拒绝共享限制、新闻／对阵部分失败保护及对阵等待上限。

按真实 JSON 扩充荣誉、队友、相关选手、奖金分布、赛事选手榜和图片字段。REA 保存的 PlayerNews／TeamNews 请求仍只发现实体 ID 参数，未据此编造 offset 分页契约。图片 CDN 的匿名请求仍可单独收到 403，JSON 成功不能代替图片验收。

完整性方面没有新的后端授权证据，未进行官方身份或完整性会话复用。详细结果和原始来源索引见[0.6.2 验证记录](VALIDATION.md)。
