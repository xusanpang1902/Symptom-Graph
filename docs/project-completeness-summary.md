# 项目完整度总览

更新时间：2026-06-29

本文用于快速判断 Symptom-Graph 当前完成度、剩余缺口和下一步优先级。里程碑与范围仍以 `SYMPTOM_GRAPH_PLAN.md` 为准。

## 当前完成度判断

项目已经从最初 MVP 采集链路演进为一个可运行、可查询、可人工校对、可做基础分析的截图语料资料库原型。当前核心后端链路和面试展示所需的模型治理能力基本闭环；主要缺口已经从业务功能转向真实样例、质量评估和展示材料。

| 模块 | 完成度 | 当前状态 |
| --- | --- | --- |
| 工程初始化 | 完成 | Spring Boot 3.x、JDK 17、MyBatis-Plus、Thymeleaf、MySQL、OSS、RabbitMQ、Redis Bloom Filter、Knife4j 依赖已接入 |
| 截图采集链路 | 完成 | 上传、hash 去重、OSS 私有存储、RabbitMQ 异步识别、Consumer 入库、Markdown 输出已闭环 |
| 多模态 Provider | 完成初版 | 支持 Gemini / OpenRouter 全局切换、上传任务级 provider/model 选择和候选模型配置 |
| 任务状态与失败治理 | 完成初版 | `capture_record` 承载任务状态；支持 retry queue、DLQ、失败分类和人工重投递 |
| 查询、管理与分析 | 完成初版 | `GET /api/v1/corpus` 支持分页筛选和关键词检索；`/corpus/manage` 提供只读管理与图片预览；`/corpus/analytics` 提供平台、标签、状态和日期聚合 |
| 人工校对 | 完成初版 | 采用方案 B，保留模型原始字段，新增人工校对字段和 `PATCH /api/v1/corpus/{id}/review` |
| Markdown 资料输出 | 完成初版 | 默认输出模型版本；可配置 `MARKDOWN_CONTENT_VERSION=reviewed` 输出人工校对版本 |
| 自动化测试 | 较完整 | Controller、Service、Markdown、MQ、Provider、Testcontainers MySQL 集成测试均有覆盖 |
| 真实截图质量评估 | 未完成 | 已有模板和验收表；当前先补 3-5 张演示样例，再扩展到至少 20 张真实中文平台截图测试 |
| 权限与安全 | 未完成 | 当前假设受信任内网使用，未做认证授权和角色控制 |
| 全文检索与规模化 | 未完成 | 当前使用 MySQL LIKE / JSON 查询；Elasticsearch 或 MySQL Full-Text 延后 |
| Provider 治理 | 当前展示闭环完成 | 已记录任务级 provider/model、耗时、token 用量、失败率、空结果率和平均耗时；成本估算与同图多模型比较后移到 Milestone 18 之后 |
| 展示材料 | 部分完成 | 已有学习路线和优化备忘；架构图、时序图、讲解稿和 3-5 个真实演示样例仍待补充 |

## 当前可演示能力

- 上传中文平台截图，生成 `capture_record` 任务并异步识别。
- 私有 OSS 保存原图，页面按需生成 signed URL 预览。
- 使用 Bloom Filter + MySQL 做图片 hash 去重；重复图默认返回历史结果。
- 后台 Consumer 调用 Gemini 或 OpenRouter，并将截图中可见评论写入 `corpus_record`。
- 一条评论生成一个 Obsidian Markdown 文件，保留 `image_hash` 和 `oss_object_key` 证据链。
- `/corpus/upload` 支持上传、状态轮询和识别结果展示。
- `/corpus/manage` 支持平台、状态、标签、采集批次、时间范围和关键词检索。
- `/corpus/analytics` 支持语料总量、采集批次、平台、标签、解析状态、校对状态和采集日期聚合。
- 人工校对可保存最新人工版本，不覆盖模型原始版本。
- Markdown 可选择模型版本或人工校对版本输出。
- 上传页支持选择 provider/model，识别运行记录可统计耗时、状态和 token 用量。

## 主要未完成事项

- Milestone 12：先完成 3-5 张真实截图演示样例记录，再补至少 20 张真实中文平台截图测试、成功/失败/误识别/空结果样例记录、Prompt/Provider 问题总结。
- Milestone 18：系统架构图、核心链路时序图、项目讲解稿、3-5 个真实截图演示样例。
- Milestone 18 之后：模型价格配置与成本估算、同图多模型重识别与结果比较。
- 横向增强：认证授权、多关键词/多标签组合、原评论发布时间筛选、全文检索、游标分页、人工校对 revision 历史表。

## 推荐下一步

当前最高优先级是 Milestone 18 的面试展示闭环：架构图、时序图、讲解稿和 3-5 个真实截图演示样例。

用户将先输入真实图片材料并整理结果；测试结果优先补到 `docs/milestone-12-quality-evaluation.md` 的第一阶段演示样例记录表。

当前不建议立即引入 Elasticsearch、复杂权限体系、人工校对 revision 表、成本估算或同图多模型比较，除非先完成真实样例和展示材料。
