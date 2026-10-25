# HLTV 解析回归样本

2026-10-18 04:00–04:49 UTC，通过项目自己的正常 Edge 会话读取以下公开页面。文件只保留解析字段所需的 DOM；已移除脚本、广告、表单、事件处理属性；保留身份图片的 src/srcset 地址，未收录图片二进制。比分、时间戳、标签和指标值未改写。这些样本用于离线回归，不作为应用实时资料。

| 文件 | 原页面 | 保留内容 |
| --- | --- | --- |
| matches-2026-10-18.html | https://www.hltv.org/matches | 前六场，包括一场 LIVE，系列比分 0–0 与当前图回合比分 9–11 |
| results-2026-10-18.html | https://www.hltv.org/results | 前六行，包括精选区与普通列表重复的 Falcons 比赛 |
| match-2026-10-18.html | https://www.hltv.org/matches/2399415/falcons-vs-betboom-thunderpick-world-championship-2026 | 两队、时间、地图、Veto、双方十人全场统计 |
| player-2026-10-18.html | https://www.hltv.org/player/3741/niko | 身份、国籍、年龄、战队、统计标签和区间 |
| upcoming-2026-10-18.html | https://www.hltv.org/matches/2399416/aurora-vs-falcons-thunderpick-world-championship-2026 | 04:20 UTC 补充采集：未开始比赛的准确时间、赛制和两队阵容 |

补充样本：`team-2026-10-18.html` 来自 [Falcons](https://www.hltv.org/team/11283/falcons)，04:49 UTC 采集；`event-2026-10-18.html` 来自 [Thunderpick 赛事](https://www.hltv.org/events/8932/thunderpick-world-championship-2026)，同一分钟采集。保留当前五人阵容、照片、徽标、世界排名、教练，以及赛事日期/地点/奖池/队伍数。

`data-zonedgrouping-entry-unix` 在赛果页为赛果记录时间；比赛详情的 `data-unix` 才用作开赛时间。测试分别断言两种字段，避免把赛果时间当成开赛时间。

第三方页面内容不因本项目代码的 AGPL-3.0 许可证而改变权属。

## 2026-10-24 新闻列表样本

`news-home-2026-10-24.html` 和 `news-archive-2026-10-24.html` 分别来自 Android 模拟器内 RushB 的实际 Gecko 页面读取：

- https://www.hltv.org/ ，采集于 `2026-10-24T04:40:47.072976Z`，31 个新闻行。
- https://www.hltv.org/news/archive/2026/october ，采集于 `2026-10-24T04:47:14.614686Z`，189 个新闻行。

电脑中继关闭，使用应用自身网页会话。样本仅保留原始完整 `a.newsline.article` 和新闻标题节点，外层重建为 `.index` 容器；不改写新闻文本、日期、链接或图片地址。首页相对时间不转换成精确发布时间，归档日期仅保留年月日。归档样本也复制到 androidTest 的独立 assets，验证网页交接、缓存恢复和拒绝页面不能覆盖旧缓存；生产 APK 不包含这两个样本。采集回执和 SHA-256 见 `docs/evidence/news-sources-v0102.json`。

`news-match-2026-10-24.html` 来自文章 `45785/eternal-fire-keep-singapore-major-dream-alive-with-draculan-s7-victory`，应用于 `2026-10-24T05:31:52.309Z` 直接读取。只抽出原始比赛结果与双方统计表，外层 article/标题/引言用于独立解析回归。验证 SINNERS 1–2 Eternal Fire、三张地图和十位选手的字段及跳转，避免把桌面／手机两份选手姓名拼接显示。原始来源成功回执见 `docs/evidence/website-news-v0102-final.json`。
