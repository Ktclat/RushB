# HLTV 公开库与移动协议调查

本文件只覆盖公开库调查分支；新版 APK 的完整性校验与本机请求结果见 [移动协议调查](HLTV-MOBILE-API-RESEARCH.md)。

核对时间：2026-10-18。范围：公开 GitHub 源码、GitHub API 元数据、npm/PyPI 元数据；未安装或执行第三方项目，未调用 HLTV 移动端点，未导入登录会话。按 REA 技能的独立证据调查流程执行；此分支仅进行公开源代码研究，不冒称 REA 反编译或联网成功。

## 最有价值的发现

确实存在移动 JSON 协议逆向客户端：**[Leclowndu93150/pickem-prediction-model](https://github.com/Leclowndu93150/pickem-prediction-model)**。`hltv_api/client.py` 写明根据官方 Android **3.0.34** 逆向；源码设置基址 `https://www.hltv.org/mobile`，使用 JSON 请求与响应，和传统 HTML 抓取不是同一路径。其版本比本地待研究的 3.0.59 旧，不能假定字段完全一致。

- 固定版本：[client.py](https://github.com/Leclowndu93150/pickem-prediction-model/blob/e6ad6fe4f6e13201e11e65242a9cc86cb87f0fef/hltv_api/client.py)，本地 `pickem-client.py`。
- 读取端点包含 `MatchesV4`、`MatchScreen?matchId`、`EventsData2`、`EventDetails2?eventId`、`TeamScreen?teamId`、`PlayerScreen?playerId`、`PlayerCompare`、`v2/ranking`、`v2/search`。这是源码观察，不是本轮请求成功证明。
- 通用 GET 请求器未要求 API key、签名或固定密钥。登录 cookie 是可选输入，用于需要账户的功能；没有复制或使用任何实际 cookie。
- 其 403 处理并不是一套官方验证 SDK。源码依赖 curl_cffi TLS 指纹模拟、仿官方应用 User-Agent、可选代理池；403/407/429/5xx 进行最多 3 次重试，优先数字 Retry-After，否则指数退避加抖动，最终仍抛出错误。**不证明它今天能在我们的模拟器和网络稳定工作，也不应将其身份模拟和代理轮换直接带进 RushB。**
- 可借鉴部分：隔离的传输层、实体 DTO、每端点 TTL、成功 JSON 才入缓存、原子缓存写入、错误类型。`cache.py` 示例 TTL 为比赛 60 秒、赛事详情 1 小时、队伍/选手 1 小时；数值只是这个项目的选择，不能当成官方频率要求。
- `models.py` 有 `vrsForCast`、`expectedLineupData`、`allPlayers`、`team1FormMatches` / `team2FormMatches`、赛事 `groupings` 中 `swissGroup` / `regularGroup` / `eslGroup` 等字段。覆盖面值得对照 APK DTO。
- 质量限制：`Match.team1_score` / `team2_score` 用 Python `or` 选择回退值，可能把合法的 0 分当成缺失；模型不得直接照搬。未找到其原生 kill matrix 实现。项目提交集中在 2026-06-10，不能因此称为持续维护的成熟 SDK。

## 类型、维护与许可证

日期为默认分支最新提交（UTC），并非 GitHub 的 updated_at；后者可能只是星标等元数据变化。所有列出项目的 GitHub `archived` 都为 false，**未归档不等于仍积极维护**。

| 项目 | 实际类型与源码证据 | 最近提交 / 发布 | 许可证与判断 |
| --- | --- | --- | --- |
| [Leclowndu93150/pickem-prediction-model](https://github.com/Leclowndu93150/pickem-prediction-model) | 移动 JSON 逆向客户端；`hltv_api/client.py` | 分支 2026-06-10 22:43:29Z；客户端文件 2026-06-10 22:36:58Z；无 GitHub release | MIT；最相关，但 v3.0.34 协议、未本轮运行 |
| [gigobyte/HLTV](https://github.com/gigobyte/HLTV) | `got-scraping` 获取 HTML，Cheerio 风格解析；非移动 JSON | 2025-03-03 23:24:38Z（依赖更新）；npm 3.5.0 于 2023-09-20 19:51:31Z；GitHub release 同日 19:52:20Z | 仓库 LICENSE 为 MIT，npm 元数据 license 为 ISC，二者不一致；README 明确停止积极维护 |
| [Invoser/HLTV](https://github.com/Invoser/HLTV) | 网页解析 fork；2026 赛程选择器更新，另有 Puppeteer 浏览器内 Scorebot connector | 2026-08-07 16:27:16Z；无 GitHub release | MIT；有实质修复，但仍依赖网页/浏览器，不是独立移动 API |
| [cybersamuurai/HLTV](https://github.com/cybersamuurai/HLTV) | 网页解析 fork；`src/puppeteer-loader.ts` 用 puppeteer-real-browser，另有 MCP 包装 | 2026-08-21 18:30:09Z（清理脚本）；主要浏览器/MCP 改动在 2026-03；无 GitHub release | MIT；桌面浏览器依赖，不满足原生 Android 独立运行目标 |
| [akimerslys/hltv-async-api](https://github.com/akimerslys/hltv-async-api) | aiohttp + BeautifulSoup/lxml HTML 抓取；Parser 检查 challenge 并延迟/切换代理 | 2026-08-04 09:26:27Z 是西语 README 合并；Parser 最近 2024-07-30；PyPI 最新 0.8.3 于 2024-06-03 | 仓库 MIT；PyPI license 字段为空；不是移动 JSON，README 提到的 Selenium 将来计划不能算已实现 |
| [Gabrielcnetto/HLTV-api](https://github.com/Gabrielcnetto/HLTV-api) | 自托管 Go API；go-rod 浏览器打开网页并提取；heatmap 是网页元素截图 | 2025-12-15 00:29:00Z | MIT；“JSON API”指自己的输出包装，并非官方数据协议 |
| [RATING3PRO/HLTV-API](https://github.com/RATING3PRO/HLTV-API) | 自托管 Go API；标准 HTTP 获取 HTML、goquery 解析；403 直接 ErrUpstreamDenied | 2026-07-27 18:26:13Z | GitHub license=null 且源码树无 LICENSE；不要当作已有明确开源许可可直接复制 |
| [OldFella/hltv_api](https://github.com/OldFella/hltv_api) | 第三方托管服务（README 指向 api.csapi.de）；FastAPI 查询其 SQL 数据库，由另一个抓取器供数 | 2026-04-01 12:54:16Z；无 GitHub release | GPL-3.0；是第三方数据库/服务，不是官方实时移动 API；未测服务时效/可用性 |

原始元数据保存在对应 `*-meta.json`、`*-commits.json`、`*-releases.json`、`npm-hltv.json`、`pypi-hltv-async-api.json`。fork 检索边界是 gigobyte 最新 30 个 fork，再核对其中有近期独立提交的 Invoser、cybersamuurai、denis-ershov；后者最近仅文档规划，未发现功能实现，因此未作为候选推荐。

## 对位矩阵 / Performance

`gigobyte/HLTV/src/endpoints/getMatchMapStats.ts` 同时读取 stats 与 performance 网页，可借鉴回合、选手数据、overview 的语义拆分，但第 162 行仍是 `TODO: kill matrix`。本轮检查的 Invoser 和 cybersamuurai fork 同一文件也相同。

- [主库固定版本源码](https://github.com/gigobyte/HLTV/blob/dd291894359e85b57e70ffbefabeac58bb6b6d9f/src/endpoints/getMatchMapStats.ts#L162)
- [Invoser 固定版本源码](https://github.com/Invoser/HLTV/blob/fdb570c003da4f9a0a2876d065a1c0170a69cffe/src/endpoints/getMatchMapStats.ts#L162)
- [cybersamuurai 固定版本源码](https://github.com/cybersamuurai/HLTV/blob/d7cff125705ced63251c314018982874c8d40e38/src/endpoints/getMatchMapStats.ts#L162)

三个本地文件 SHA-256 均为 `aa2d3f6fe3733f30c3cd6a380b906953e551cf53f793eed764ac7d0e65ee93e1`。因此这些库**不能直接填上 RushB 的原生对位矩阵缺口**。移动客户端候选的已核对文件也没有该功能；这只是本轮搜索边界，不能断言全网不存在。

## 给 RushB 的实现建议

1. 以本地官方 3.0.59 APK 的请求与 DTO 为首要证据，公开移动客户端作为第二来源。优先研究无账户的首页、赛程、比赛、赛事、战队、选手读取。
2. 建立 `MobileJsonSource` 与现有 `HtmlSource` 的显式边界和 DTO 适配层，保留已有缓存、失败冷却、字段缺失语义。将 0 与 null 分开；区分比赛 ID / stats ID / mapstats ID。
3. 只有在模拟器上用 RushB 独立请求实际成功，并检查返回 Content-Type、必需字段与来源时，才启用移动 JSON 为默认源。现在的源码证据不能替代这一步。
4. 403 保留明确拒绝与冷却，不盲目模仿官方身份、重试风暴或轮换出口。不接入第三方托管服务来掩盖本地接入失败。
5. 对位矩阵继续优先对照 APK 数据模型和真实无凭据响应；如果证据仍缺，不伪造数据，也不把旧网页库 TODO 当成可用实现。

## 未知与边界

- 未证明移动协议是 HLTV 承诺支持的公共开发者 API。
- 未确认 3.0.34 与 3.0.59 所有字段一致、所有端点仍在线、是否受请求环境或账户状态限制。
- 未执行上述库的 TLS 模拟/代理代码，未验证 README 的“能避免 403”宣称。
- 公开客户端没有所需密钥并不意味着官方全部功能没有鉴权；账户和写操作不在本次实现范围。
- 缓存 fixtures、probe 脚本和作者过去成功的声明只能证明其历史意图，不能证明 RushB 当前网络成功。

`source-download-index.json` 记录下载的源码及固定 commit；`evidence-manifest.json` 记录静态证据文件大小和 SHA-256。所有本轮应用源码保持未修改。
