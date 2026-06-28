# 项目完整度总览

更新时间：2026-06-28

本文用于快速判断 Symptom-Graph 当前完成度、剩余缺口和下一步优先级。里程碑与范围仍以 `SYMPTOM_GRAPH_PLAN.md` 为准。

## 当前完成度判断

项目已经从最初 MVP 采集链路演进为一个可运行、可查询、可人工校对的截图语料资料库原型。当前核心后端链路基本闭环，适合作为 Java 后端项目展示；真实数据质量评估、生产级安全治理和模型治理仍未完成。

| 模块 | 完成度 | 当前状态 |
| --- | --- | --- |
| 工程初始化 | 完成 | Spring Boot 3.x、JDK 17、MyBatis-Plus、Thymeleaf、MySQL、OSS、RabbitMQ、Redis Bloom Filter、Knife4j 依赖已接入 |
| 截图采集链路 | 完成 | 上传、hash 去重、OSS 私有存储、RabbitMQ 异步识别、Consumer 入库、Markdown 输出已闭环 |
| 多模态 Provider | 完成初版 | 支持 Gemini / OpenRouter 通过 `app.vision.provider` 全局切换 |
| 任务状态与失败治理 | 完成初版 | `capture_record` 承载任务状态；支持 retry queue、DLQ、失败分类和人工重投递 |
| 查询与管理 | 完成初版 | `GET /api/v1/corpus` 支持分页筛选和关键词检索；`/corpus/manage` 提供只读管理与图片预览 |
| 人工校对 | 完成初版 | 采用方案 B，保留模型原始字段，新增人工校对字段和 `PATCH /api/v1/corpus/{id}/review` |
| Markdown 资料输出 | 完成初版 | 默认输出模型版本；可配置 `MARKDOWN_CONTENT_VERSION=reviewed` 输出人工校对版本 |
| 自动化测试 | 较完整 | Controller、Service、Markdown、MQ、Provider、Testcontainers MySQL 集成测试均有覆盖 |
| 真实截图质量评估 | 未完成 | 已有模板和验收表，但仍缺至少 20 张真实中文平台截图测试结果 |
| 权限与安全 | 未完成 | 当前假设受信任内网使用，未做认证授权和角色控制 |
| 全文检索与规模化 | 未完成 | 当前使用 MySQL LIKE / JSON 查询；Elasticsearch 或 MySQL Full-Text 延后 |
| Provider 治理 | 未完成 | 尚未记录任务级 provider/model、耗时、失败率、空结果率和成本估算 |
| 展示材料 | 部分完成 | 已有学习路线和优化备忘；架构图、时序图、讲解稿和 3-5 个真实演示样例仍待补充 |

## 当前可演示能力

- 上传中文平台截图，生成 `capture_record` 任务并异步识别。
- 私有 OSS 保存原图，页面按需生成 signed URL 预览。
- 使用 Bloom Filter + MySQL 做图片 hash 去重；重复图默认返回历史结果。
- 后台 Consumer 调用 Gemini 或 OpenRouter，并将截图中可见评论写入 `corpus_record`。
- 一条评论生成一个 Obsidian Markdown 文件，保留 `image_hash` 和 `oss_object_key` 证据链。
- `/corpus/upload` 支持上传、状态轮询和识别结果展示。
- `/corpus/manage` 支持平台、状态、标签、采集批次、时间范围和关键词检索。
- 人工校对可保存最新人工版本，不覆盖模型原始版本。
- Markdown 可选择模型版本或人工校对版本输出。

## 主要未完成事项

- Milestone 12：至少 20 张真实中文平台截图测试、成功/失败/误识别/空结果样例记录、Prompt/Provider 问题总结。
- Milestone 17：接口级或任务级模型选择、provider/model/耗时/token 或成本记录、失败率与空结果率统计、同图多模型比较。
- Milestone 18：系统架构图、核心链路时序图、项目讲解稿、3-5 个真实截图演示样例。
- 横向增强：认证授权、多关键词/多标签组合、原评论发布时间筛选、全文检索、游标分页、人工校对 revision 历史表。

## 推荐下一步

如果目标是提升项目展示效果，优先做 Milestone 18 的架构图、时序图和讲解稿。

如果目标是提升真实可用性，优先补 Milestone 12 的真实截图测试集。

如果目标是继续增强后端工程深度，优先进入 Milestone 17 的 Provider 与模型治理。

当前不建议立即引入 Elasticsearch、复杂权限体系或人工校对 revision 表，除非先确认真实数据规模和使用频率。
