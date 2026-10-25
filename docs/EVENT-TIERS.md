# 赛程重点赛事的分类依据

2026-10-19 用户确认：时间线只显示明确标为 Tier 1／Major 的赛事。赛事名气、奖金、HLTV 排名、VRS 邀请数量、`featured`、`isBig` 均不作为分类依据。Valve 当前 Tier 1／Tier 2 邀请规则并非统一的世界前 30 名门槛，参见 [Valve 赛事规则](https://github.com/ValveSoftware/counter-strike_rules_and_regs/blob/main/tournament-operation-requirements.md)。

实现入口为 `data/EventEligibility.kt`。采用确切 HLTV event ID 与公开分类依据对应，避免把同一品牌的其他届次或资格赛自动归为重点赛事。移动赛事响应中的 `isMajor=true` 直接保留为 Major；该字段随赛事记录持久化，兼容没有字段的旧缓存。来源不明确时保持未分类。

## 已核对的记录

| HLTV ID | 赛事 | 分类 | 依据 |
|---|---|---|---|
| 8244 | ESL Pro League Season 24 | Tier 1 | [ESL 官方赛事页](https://pro.eslgaming.com/tour/zh/details/esl-pro-league-season-24/)，明确 Tier 1 Wildcard |
| 8932 | Thunderpick World Championship 2026 | Tier 1 | [Valve Tier 1 Events 分类表](https://liquipedia.net/counterstrike/Valve_Tier_1_Events)与[赛事条目](https://liquipedia.net/counterstrike/Thunderpick/World_Championship/2026)的检索结果，明确 Valve Tier 1；此次直接打开受限 |
| 8050 | PGL Masters Bucharest 2026 | Tier 1 | [PGL 官方赛事页](https://www.pglesports.com/cs2/pgl-masters-bucharest-2026/)，明确 Tier 1 |
| 8245 | IEM Beijing 2026 | Tier 1 | [赛事条目](https://liquipedia.net/counterstrike/Intel_Extreme_Masters/2026/Beijing)的检索结果，明确 Valve Tier 1；[ESL 官方日期](https://pro.eslgaming.com/tour/details/iem-beijing-2026/)交叉核对 |
| 9303、9304、8302 | PGL Major Singapore 2026 的各阶段 | Major | HLTV 移动赛事索引 `isMajor=true` 与对应的赛事 ID |

人工条目核对日期为 2026-10-19；由移动响应提供的分类不虚构人工核验日期。赛程日期、队伍和图片仍来自 HLTV，分类目录不补造这些内容。

## 范围与维护

- 当前目录覆盖已核实的上述赛事，并自动接纳来源明确标记的 Major；**不是已穷尽的未来 Tier 1 数据库**。未来赛事没有明确分类证据时不进入重点时间线。
- 8251 BLAST Rivals Fall 2026 本次仅核实到 Valve Wildcard，未将 Wildcard 一概等同于 Tier 1，因此未加入。以后有明确证据可按 ID 更新。
- “重点”是默认列表；“全部”保留月内所有已读取赛事，“已关注”按已知参赛队伍筛选。未知阵容不猜测关注关系。切换列表不把低级别赛事重新放进时间线。
- 时间线点击日期筛选当天赛事；另有明确入口查看当天比赛。状态、名称搜索和排序作用于列表。跨月赛事按范围展示，保留原始起止日期。
- 自动测试覆盖未分类赛事不能靠奖金／品牌入选、Major 来源分类、跨月边界、筛选组合、排序、关注队伍和缓存兼容。
- 0.9.2 首页沿用相同分类依据：默认 Major、Tier 1、其他赛事依次排列，不隐藏其他比赛。级别筛选支持 Tier 1／Major、单独 Major、单独 Tier 1、其他／未分级，不把未分级赛事标成 Tier 2。地区筛选读取赛事地点或线上赛区；缺少地点时显示“地区未明”，不由参赛战队国籍推断。按时间排序时跨赛事排序，并保留相应赛事标题。

新增人工条目时需核对赛事届次、HLTV ID、明确分类页面并记录核对日期，不使用名称关键词或排名猜测。
