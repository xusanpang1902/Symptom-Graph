# Symptom-Graph 架构、业务与需求笔记

本文档记录当前阶段对 Symptom-Graph 项目架构、业务定位、需求边界和后续演进建议的判断，供后续开发和复盘参考。

## 项目定位

Symptom-Graph 是一个面向研究资料沉淀的中文互联网截图语料采集与索引系统。

项目的目标不是做完整舆情平台，而是完成一条稳定、可展示、可扩展的采集链路：把中文互联网平台截图中的可见评论，转化成可检索、可追溯、可长期沉淀的研究语料。

它主要解决以下问题：

- 截图里的评论内容容易散落，无法系统整理。
- 研究时需要保留原始语境和证据链，不能只保存二次总结。
- 非结构化截图需要转为结构化数据，方便后续检索、归类和引用。
- 数据需要同时服务 MySQL 查询和 Obsidian 笔记沉淀。
- 模型输出需要受约束，避免过度解释和编造。

因此，Symptom-Graph 更接近一个研究型语料采集系统，而不是社媒运营工具、舆情看板或情绪分析平台。

## 核心业务对象

当前 MVP 的核心业务对象是 `corpus_record`。

一张截图可能包含多条评论，因此当前使用单表表达一图多评论关系。关键字段包括：

- `capture_id`：一次截图采集批次。
- `comment_index`：同一截图里的第几条评论。
- `raw_content`：截图中实际可见的评论原文。
- `context_target`：截图中实际可见的上下文原文。
- `platform`：来源平台，例如小红书、微博、知乎。
- `image_hash`：图片 SHA-256，用于去重和证据链。
- `oss_bucket` / `oss_object_key`：原始截图在 OSS 中的位置。
- `tags`：模型生成的现象性标签，数据库中不带 `#`。
- `model_raw_response`：模型原始返回，方便问题排查和证据追溯。
- `parse_status`：识别状态，例如 `SUCCESS`、`MODEL_FAILED`、`PARSE_FAILED`、`EMPTY_RESULT`。
- `markdown_path`：对应 Obsidian Markdown 文件路径。

这说明当前业务重心是语料记录，而不是用户、任务、工作空间或后台管理等更复杂的 SaaS 概念。

## 当前架构

当前项目是一个 Spring Boot 单体应用，采用清晰的分层结构：

```text
Web/API 层
-> CorpusIngestionService 核心编排层
-> OSS 存储服务
-> 多模态识别 Provider
-> MySQL 语料存储
-> Markdown 导出服务
```

核心链路如下：

```text
上传截图
-> 计算 SHA-256 image_hash
-> 判断是否重复
-> 非重复时上传阿里云 OSS 私有 Bucket
-> 调用 Gemini 或 OpenRouter 多模态模型
-> 解析平台、上下文、评论数组和标签
-> 写入 MySQL
-> 每条评论生成一个 Markdown 文件
-> 页面展示结果和 signed URL 图片预览
```

主要职责划分：

- `CorpusIngestionService` 负责业务编排。
- `OssStorageService` 负责图片上传和 signed URL 生成。
- `VisionRecognitionService` 负责统一模型识别入口。
- `VisionRecognitionProvider` 抽象 Gemini / OpenRouter 等模型 Provider 差异。
- `MarkdownExportService` 负责 Obsidian Markdown 输出。
- Controller 负责 API 或 Thymeleaf 页面入口。

该架构能展示文件上传、哈希去重、OSS 私有存储、signed URL、第三方 AI API 集成、Provider 抽象、MySQL 持久化、Markdown 输出、Thymeleaf 页面和自动化测试等能力。

## 模型 Provider 设计

项目已经从直接绑定 Gemini 演进为通用 `VisionRecognitionService` 入口。

当前调用关系：

```text
CorpusIngestionService
-> VisionRecognitionService
-> ConfiguredVisionRecognitionService
-> GeminiVisionServiceImpl / OpenRouterVisionRecognitionServiceImpl
```

这个设计的价值：

- 核心采集链路不依赖某个模型厂商。
- 可以通过 `VISION_PROVIDER` 切换 `gemini` 或 `openrouter`。
- Prompt 和 JSON 解析逻辑可以复用。
- 后续接入 Qwen、Claude、火山、百炼等 Provider 时，不需要重写核心业务链路。

多模态模型的可用性、价格、响应格式和稳定性都会变化，提前抽象 Provider 是合理的架构决策。

## 去重与 force 逻辑

图片去重是项目的重要业务规则。

默认逻辑：

```text
同一 image_hash 已存在
-> 不上传 OSS
-> 不调用模型
-> 直接返回历史结果
```

该逻辑可以避免重复消耗模型调用费用、重复污染数据库、重复生成 Markdown，以及 OSS 中出现重复图片。

`force=true` 的逻辑也需要保持谨慎：

```text
同一 image_hash 已存在，force=true
-> 复用已有 OSS 对象
-> 重新调用模型
-> 只有识别成功后才删除旧记录并覆盖 Markdown
-> 如果失败，保留历史记录
```

这个设计避免了重新识别失败时破坏原本可用的历史数据。

## 证据链设计

项目强调证据链，具体体现在：

- 原图保存到 OSS 私有 Bucket。
- 数据库存储 `image_hash`。
- 数据库存储 `oss_object_key`。
- Markdown 不保存 signed URL。
- 页面展示时动态生成 signed URL。
- 模型原始返回保存到 `model_raw_response`。

设计逻辑：

- signed URL 是临时访问凭证，不适合长期写入研究笔记。
- `oss_object_key` 和 `image_hash` 是长期证据字段。
- Markdown 是研究资料沉淀，不应该依赖会过期的 URL。
- MySQL 负责结构化检索，Obsidian 负责长期阅读和写作整理。

## 当前需求边界

当前 MVP 已包含：

- 截图上传。
- 图片 hash 去重。
- OSS 私有存储。
- signed URL 预览。
- Gemini / OpenRouter 多模态识别。
- 多评论解析。
- MySQL 入库。
- Markdown 输出。
- Thymeleaf 上传与结果展示页面。
- 查询 API。
- 空结果和失败状态处理。
- 基础自动化测试。

当前 MVP 暂不包含：

- 用户登录和权限系统。
- 多项目或多资料库隔离。
- 后台管理系统。
- 复杂搜索页面。
- 标签体系治理。
- 人工校对流程。
- 批量上传。
- 向量检索。
- 统计分析看板。
- 自动爬虫采集。
- 舆情趋势分析。

这些后续都可以扩展，但不应该过早混入当前 MVP，否则项目会失焦。

## 当前业务状态

当前项目状态可以概括为：MVP 核心链路已被真实样例验证，下一步是扩大样本测试，沉淀测试报告和失败边界。

已经确认：

- 主链路已跑通。
- 本地 Markdown 已成功输出。
- OSS Bucket 中已确认存在证据链备份。
- 已有一个真实小红书截图样例，识别出 3 条评论。
- README 和项目计划已经同步记录该事实。
- Milestone 8 还未完全完成，因为 20 张中文平台截图测试和完整成功/失败样例尚未补足。

## 后续建议

短期建议优先收尾 Milestone 8，而不是马上扩展新功能：

1. 补齐 20 张截图测试。
2. 记录成功样例和失败样例。
3. 观察模型最常见的问题，例如漏评论、上下文错位、标签过度解释、平台识别错误。
4. 根据失败样例微调 Prompt。
5. 再考虑是否加入人工校对和修正能力。

如果继续往产品化方向推进，下一批最值得做的功能是：

1. 批量上传。
2. 采集结果列表页。
3. 按平台、标签、时间查询。
4. 人工修正 `raw_content`、`context_target` 和 `tags`。
5. 导出或重建 Markdown。
6. 更完整的失败重试机制。

如果目标是简历项目展示，当前架构已经具备较好的完整度。后续重点应优先补完整测试样例、截图证据、链路说明和架构图，而不是盲目堆功能。
