# Symptom-Graph 项目开发规划与执行蓝图

## 1. 项目定位

Symptom-Graph 是一个面向研究资料沉淀的碎片舆论语料采集与索引系统。

系统通过截图上传、Gemini 多模态识别、结构化存储和 Obsidian Markdown 输出，把中文互联网平台上的原始言论保存为可检索、可追溯、可引用的研究资料库。

MVP 的目标不是做完整舆情平台，而是完成一条稳定、可展示、可扩展的采集链路，便于后续继续扩展，也便于作为 Java 后端简历项目展示。

## 2. 核心原则

- 低干扰：系统不做二次意识形态解释，不写摘要，不发表评论。
- 原文优先：`raw_content` 和 `context_target` 必须来自截图中实际可见文字。
- 不编造：无法识别的信息返回 `null` 或空数组。
- 现象性标签：标签可以由模型自由生成，但必须是经验性、现象性标签，不对发言者做心理诊断或人格判断。
- 可追溯：每条语料必须能追溯到原始截图、图片 hash 和采集批次。
- 可索引：数据同时进入 MySQL 和 Obsidian Markdown，便于查询、整理和后续写作。

## 3. 技术栈

- JDK 17
- Spring Boot 3.x
- Thymeleaf
- MyBatis-Plus
- MySQL 8.0
- 阿里云 OSS 私有 Bucket
- Gemini 多模态 API
- SHA-256 图片去重
- Java File I/O Markdown 输出
- Knife4j 或 Swagger 接口文档

## 4. MVP 核心链路

```text
上传截图
-> 计算 SHA-256 image_hash
-> 查询是否重复
-> 非重复时上传 OSS 私有 Bucket
-> 调用 Gemini 多模态 API
-> 解析截图中所有可见评论
-> 多条语料写入 MySQL
-> 为每条语料生成一个 Markdown 文件
-> Thymeleaf 页面展示识别结果与 signed URL 图片预览
```

重复图片默认返回已有结果。

预留 `force=true` 参数，用于强制重新识别。

## 5. 数据库设计

### corpus_record

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | 主键 |
| capture_id | VARCHAR(64) | 一次截图采集 ID |
| comment_index | INT | 同一截图内第几条评论 |
| raw_content | TEXT | 截图中可见的原始评论 |
| context_target | TEXT | 截图中可见的上下文原文 |
| platform | VARCHAR(64) | 来源平台 |
| original_publish_time | DATETIME NULL | 原评论发布时间，可为空 |
| collected_time | DATETIME | 系统采集时间 |
| oss_bucket | VARCHAR(128) | OSS Bucket 名称 |
| oss_object_key | VARCHAR(512) | OSS Object Key |
| image_hash | VARCHAR(64) | 图片 SHA-256 |
| tags | JSON | 模型生成标签数组，数据库中不带 `#` |
| model_raw_response | JSON 或 TEXT | Gemini 原始返回 |
| parse_status | VARCHAR(32) | `SUCCESS`、`MODEL_FAILED`、`PARSE_FAILED` 等 |
| error_message | TEXT NULL | 错误信息 |
| markdown_path | VARCHAR(512) | Markdown 文件路径 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

MVP 阶段使用单表实现一图多评论。

后续可演进为：

```text
capture_record
corpus_record
```

## 6. Gemini 返回结构

```json
{
  "platform": "小红书",
  "context_target": "截图中可见的上下文原文",
  "original_publish_time": null,
  "items": [
    {
      "comment_index": 1,
      "raw_content": "第一条评论原文",
      "tags": ["医疗焦虑", "恐艾"]
    }
  ]
}
```

Prompt 必须强调：

```text
只能提取截图中实际可见文字。
不得推测、不得总结、不得改写、不得补全。
context_target 必须是截图中可见的上下文原文。
raw_content 必须是截图中可见的评论原文。
如果无法识别，返回 null 或空数组。
tags 不带 #。
tags 必须是现象性标签，不得对发言者做心理诊断或人格判断。
```

## 7. OSS 私有访问方案

- OSS Bucket 使用私有读写权限。
- 数据库保存 `oss_bucket` 和 `oss_object_key`。
- Web 页面展示时，后端生成临时 signed URL。
- Markdown 文件不保存 signed URL，避免过期失效。
- Markdown 只保存 `oss_object_key` 和 `image_hash` 作为证据链引用。

## 8. 图片去重策略

- 上传后计算 SHA-256。
- 根据 `image_hash` 查询已有记录。
- 如果已存在且未传 `force=true`，跳过 OSS 上传和 Gemini 调用，直接返回已有结果。
- 如果传入 `force=true`，允许重新调用 Gemini。
- 重新识别后覆盖原 Markdown 文件，避免 Obsidian 输出目录膨胀。

## 9. Markdown 输出规则

输出目录暂定：

```text
obsidian-output/
```

一条评论生成一个 Markdown 文件。

文件名建议：

```text
{capture_id}-{comment_index}-{platform}.md
```

Front Matter 示例：

```yaml
---
id: 123
capture_id: "20260528_xxx"
comment_index: 1
platform: "小红书"
original_publish_time:
collected_time: "2026-05-28 20:30:00"
tags:
  - 医疗焦虑
  - 恐艾
obsidian_tags:
  - "#医疗焦虑"
  - "#恐艾"
image_hash: "..."
oss_object_key: "corpus/2026/05/xxx.png"
---
```

正文示例：

```md
# 小红书语料 123

## 原始评论

> 截图中提取出的评论原文

## 上下文原文

> 截图中可见的上下文原文

## 证据链

- image_hash: `...`
- oss_object_key: `corpus/2026/05/xxx.png`

## 研究备注
```

## 10. Web 展示页面

使用 Thymeleaf 提供极简页面：

- 上传截图。
- 可选 `force=true`。
- 上传后展示 signed URL 图片预览。
- 展示平台。
- 展示上下文原文。
- 展示所有评论。
- 展示 tags。
- 展示 Markdown 文件路径。
- 如果图片重复，提示“该截图已存在，已返回历史识别结果”。

## 11. API 设计

### 上传识别

```http
POST /api/v1/corpus/upload
Content-Type: multipart/form-data
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| file | File | 是 | 截图 |
| force | Boolean | 否 | 是否强制重新识别，默认 false |

### 查询详情

```http
GET /api/v1/corpus/{id}
```

### 按 capture 查询

```http
GET /api/v1/corpus/captures/{captureId}
```

### 获取图片临时访问链接

```http
GET /api/v1/corpus/{id}/image-url
```

## 12. 里程碑

### Milestone 1：工程初始化

- [x] 初始化 Spring Boot 3.x 项目。
- [x] 使用 JDK 17。
- [x] 引入 MyBatis-Plus、MySQL、Thymeleaf、OSS SDK、HTTP Client、Validation、Knife4j。
- [x] 配置 `application.yml`。
- [x] 密钥通过环境变量注入。

### Milestone 2：数据库与实体

- [x] 创建 `corpus_record` 表。
- [x] 创建 Entity、Mapper、Service。
- [x] 支持根据 `image_hash` 查询已有采集记录。
- [x] 支持按 `capture_id` 查询一组评论。

### Milestone 3：OSS 私有存储

- [x] 实现图片上传到私有 Bucket。
- [x] 保存 `oss_bucket` 和 `oss_object_key`。
- [x] 实现 signed URL 生成。
- [x] Web 页面使用 signed URL 预览图片。

说明：Milestone 3 已提供 OSS 上传结果中的 `bucket` 和 `objectKey`，正式写入 `corpus_record.oss_bucket` / `corpus_record.oss_object_key` 将在 Milestone 5 核心采集链路接入时完成。

### Milestone 4：Gemini 多模态识别

- [x] 实现 Gemini API 调用。
- [x] 固化 Prompt。
- [x] 解析 `items` 数组。
- [x] 保存 `model_raw_response`。
- [x] 处理模型失败、JSON 解析失败等异常。

说明：Milestone 4 已新增 `GeminiVisionService` 独立识别服务，模型通过 `GEMINI_MODEL` / `app.gemini.model` 配置切换，暂不提供页面或上传接口级模型选择。`model_raw_response` 当前保留完整 Gemini API 响应，供 Milestone 5 入库使用。

### Milestone 5：核心采集链路

- [x] 实现 `POST /api/v1/corpus/upload`。
- [x] 完成去重逻辑。
- [x] 非重复时执行 OSS 上传、Gemini 识别、MySQL 入库、Markdown 生成。
- [x] 重复时直接返回已有结果。
- [x] `force=true` 时重新识别并覆盖 Markdown。

说明：Milestone 5 已新增 `CorpusIngestionService` 核心编排链路。重复图片且 `force=false` 时直接返回历史记录；`force=true` 时复用已有 OSS 对象，重新调用多模态 Provider，并且只有重新识别成功后才删除旧识别记录、按相同命名规则覆盖 Markdown，避免模型失败破坏历史语料。Markdown 当前为最小闭环实现，详细输出规则与 Obsidian 细节继续在 Milestone 6 完善。

### Milestone 6：Markdown 输出

- [x] 输出到项目目录下 `obsidian-output/`。
- [x] 一条评论一个 Markdown。
- [x] 数据库 tags 不带 `#`。
- [x] Markdown 中额外生成 Obsidian 标签。
- [x] 不嵌入 signed URL。

说明：Milestone 6 已完善 Markdown 输出规范。入库前会清洗 Gemini 返回的 tags，去掉 `#` / `＃`、空值和重复项；Markdown 文件名按 `{capture_id}-{comment_index}-{platform}.md` 稳定生成并做非法字符处理；Front Matter 同时输出数据库 tags 和 Obsidian `#` 标签；Markdown 只保存 `image_hash` 与 `oss_object_key`，不写 signed URL。

### Milestone 7：Thymeleaf 展示页

- [x] 上传表单。
- [x] force 参数开关。
- [x] 上传结果页。
- [x] 重复图片提示。
- [x] signed URL 图片预览。
- [x] 评论和 Markdown 路径展示。

说明：Milestone 7 已新增正式 Thymeleaf 采集页 `/corpus/upload`，复用 `CorpusIngestionService` 核心链路。页面支持截图上传、`force=true` 强制重新识别、重复图片提示、signed URL 图片预览、采集元数据、评论内容、tags、错误状态和 Markdown 路径展示。Milestone 3 的 `/oss-preview` 作为 OSS 验证页保留。

### Milestone 8：测试与 README

- [ ] 使用至少 20 张中文平台截图测试。
- [ ] 记录成功样例和失败样例。
- [x] 编写 README，说明项目背景、技术架构、核心链路、数据表设计、Gemini Prompt 约束、私有 OSS + signed URL、图片 hash 去重和 Obsidian 输出示例。

说明：Milestone 8 已新增 `README.md`，覆盖项目背景、技术架构、核心链路、数据表设计、Gemini Prompt 约束、私有 OSS + signed URL、图片 hash 去重、Obsidian 输出示例、本地运行和 API 使用说明。已完成至少 1 张真实中文平台截图的端到端验证：截图上传成功，多模态 Provider 成功识别出小红书评论，结果已写入本地 Markdown，且在阿里云 OSS 私有 Bucket 中确认存在对应证据链备份。当前有效样例 `capture_id=20260529220731_0a256a18`，识别 3 条评论，本地输出位于 `obsidian-output/20260529220731_0a256a18-1-小红书.md`、`obsidian-output/20260529220731_0a256a18-2-小红书.md`、`obsidian-output/20260529220731_0a256a18-3-小红书.md`，证据链字段包含 `image_hash=a98100192177869df76bb2be2dc153cb9872b053d56596042c0587e0c497fa2d` 与 `oss_object_key=corpus/2026/05/20260529220731_0a256a18/7dfccb89-1aeb-4b6f-b5c6-33bba1c67394.jpg`。已新增 `docs/milestone-8-test-report.md` 记录自动化回归结果、20 张截图测试表格模板和验收口径；已新增 `docs/project-learning-plan.md` 作为项目整体学习、简历展示和面试准备参考文档；“至少 20 张中文平台截图测试”和完整“成功/失败样例记录”仍未完成，后续需继续补足真实截图覆盖与失败样例。

### Milestone 9：OpenRouter 多模态 Provider 接入

- [x] 新增通用 `VisionRecognitionService` 接口，解除核心采集链路与 Gemini 命名绑定。
- [x] 保留 Gemini provider 实现。
- [x] 新增 OpenRouter provider 实现，支持 `qwen/qwen2.5-vl-72b-instruct` 等 image input 模型。
- [x] 支持通过 `app.vision.provider` 在 `gemini` / `openrouter` 间切换。
- [x] 复用统一 Prompt 和 JSON 解析逻辑。
- [x] 补充 provider 路由、OpenRouter 响应解析和采集链路测试。
- [x] 更新 README 的模型配置说明。

说明：Milestone 9 已新增通用 `VisionRecognitionService` / `VisionRecognitionProvider`，核心采集链路不再直接依赖 Gemini 命名接口；新增 `ConfiguredVisionRecognitionService` 根据 `app.vision.provider` 路由到 `gemini` 或 `openrouter`；新增 OpenRouter Chat Completions 图片输入实现，默认模型调整为 `qwen/qwen3.6-flash`，以匹配当前 OpenRouter API key 允许的 Alibaba provider 并避免 `qwen/qwen2.5-vl-72b-instruct` 在 provider 限制下返回 404。Gemini 和 OpenRouter 共用统一 Prompt 与 JSON 解析器，README 已补充 OpenRouter 配置和 text-only 模型限制说明。

补充：已补齐 `GET /api/v1/corpus/{id}`、`GET /api/v1/corpus/captures/{captureId}`、`GET /api/v1/corpus/{id}/image-url` 查询接口；识别成功但 `items` 为空时使用 `EMPTY_RESULT` 状态记录，不生成空语料 Markdown。Milestone 8 的 20 张真实截图测试仍由人工提供截图后执行。

### Milestone 10：Redis Bloom Filter 与 RabbitMQ 异步削峰优化

- [x] 引入 Redisson 客户端依赖。
- [x] 新增 `symptom_graph_hash_bloom` 图片 hash 布隆过滤器配置，默认预计元素 10 万、误判率 0.01。
- [x] 新增 `ImageHashBloomFilterService`，在 Redis 未启用或访问异常时保守回退到 MySQL 去重。
- [x] 启动时将 MySQL 中已有的 distinct `image_hash` 回灌到 Bloom Filter，避免空过滤器跳过历史数据查重。
- [x] 修改 `CorpusIngestionService` 去重入口：先查 Bloom Filter；当 Bloom Filter 判定不存在时跳过 MySQL；当判定可能存在时继续查询 MySQL 做最终确认。
- [x] 引入 RabbitMQ Exchange / Queue / Routing Key 配置。
- [x] 上传新图时同步完成 hash、OSS 上传、`PROCESSING` 基础记录入库和 MQ 投递后立即返回。
- [x] 新增 Consumer 后台调用 `VisionRecognitionService`，并按识别结果更新 MySQL 与生成 Markdown。
- [x] 保持 `force=true` 只有重新识别成功后才覆盖旧记录的安全语义。

说明：Milestone 10 已完成 Redis Bloom Filter 与 RabbitMQ 异步削峰主链路。当前默认 `BLOOM_FILTER_ENABLED=false`，因此未配置 Redis 时仍保持原 MySQL 去重行为；开启后通过 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE` 连接 Redis，并初始化 `symptom_graph_hash_bloom`。Bloom Filter 仅作为 MySQL 去重前置过滤器，不作为最终存在性依据；命中时仍穿透查询 MySQL，未命中时才跳过 MySQL。RabbitMQ 阶段已新增 `spring-boot-starter-amqp`、`RabbitMqConfig`、`CorpusProcessMessage`、`CorpusProcessMessageProducer` 和 `CorpusProcessMessageListener`；新图上传当前只同步完成 OSS 上传、`capture_record PROCESSING` 任务入库、Bloom Filter 写入和 RabbitMQ 投递，然后立即返回 `captureRecordId` / `captureId` / `parseStatus=PROCESSING`。Consumer 会从私有 OSS 下载原图字节，复用当前 `VisionRecognitionService` 策略路由调用 Gemini/OpenRouter；识别成功时写入一条或多条 `corpus_record` 并为 `SUCCESS` 记录生成 Markdown；空结果更新 `capture_record=EMPTY_RESULT`；模型或解析异常更新为 `MODEL_FAILED` / `PARSE_FAILED` 并记录错误。重复图 `force=false` 仍返回历史结果；已有图 `force=true` 继续保留同步重识别路径，从而维持“识别成功后才覆盖旧记录”的安全语义。

## 13. 后续优化 Milestone

以下内容为 Milestone 10 之后的增强路线。实施时仍应遵守“原文优先、不可编造、可追溯、可索引”的核心原则。

### Milestone 11：前端轮询与异步状态展示

- [x] 上传后展示 `PROCESSING` 状态、`captureRecordId` 和 `captureId`。
- [x] 页面通过 `GET /api/v1/corpus/capture-records/{id}` 轮询任务状态，并在成功后通过 `GET /api/v1/corpus/captures/{captureId}` 拉取识别结果。
- [x] 识别完成后自动展示评论、tags、错误信息和 Markdown 路径。
- [x] 区分展示 `PROCESSING`、`SUCCESS`、`EMPTY_RESULT`、`MODEL_FAILED`、`PARSE_FAILED`。
- [x] 对重复图返回历史结果和异步新图处理给出差异化提示。

说明：Milestone 11 已在 Thymeleaf 上传页中加入轻量前端轮询。异步新图上传后，页面会保留截图 signed URL 预览和 `captureRecordId` / `captureId` / `PROCESSING` 元数据，并每 2 秒请求 `GET /api/v1/corpus/capture-records/{id}` 查询任务状态；任务成功后再请求 `GET /api/v1/corpus/captures/{captureId}` 刷新评论、tags、Markdown 路径和记录数量。轮询最多执行 120 次，达到上限后提示用户手动刷新或通过 capture 查询接口查看结果。

### Milestone 12：真实截图测试集与质量评估

- [ ] 补足 Milestone 8 中至少 20 张中文平台截图测试。
- [ ] 记录成功样例、失败样例、模型误识别样例和空结果样例。
- [ ] 对不同平台截图进行覆盖，例如小红书、微博、知乎、贴吧、B 站评论区等。
- [x] 建立人工验收表：截图编号、平台、预期评论数、实际评论数、parse_status、错误信息、Markdown 路径。
- [ ] 总结 Prompt 或 Provider 的识别问题。

说明：Milestone 12 已新增 `docs/milestone-12-quality-evaluation.md`，建立真实截图测试集构成、执行步骤、API 辅助命令、质量验收口径、20 张截图记录表、成功样例模板、失败样例模板、模型误识别样例模板和 Prompt/Provider 问题汇总表。当前仓库内未发现可执行的真实截图样本，因此“至少 20 张中文平台截图测试”、成功/失败/误识别样例记录和 Prompt/Provider 问题总结仍待用户提供截图后执行，不能伪造测试结果。

### Milestone 13：RabbitMQ 重试、死信队列与任务恢复

- [x] 为 `corpus.process.queue` 增加重试队列和死信队列。
- [x] 记录每个任务的最大重试次数、最后失败时间和失败原因。
- [x] 区分可重试错误和不可重试错误，例如模型限流、网络超时、JSON 格式错误、OSS 下载失败。
- [x] 提供失败任务重新投递接口，便于人工修复配置后重跑。

说明：Milestone 13 最初在 `corpus_record` 上完成轻量失败治理，新增 retry queue、DLQ 和失败分类。Milestone 14 拆表后，新图异步链路的失败治理已迁移到 `capture_record`：Consumer 通过 `CorpusProcessFailureClassifier` 区分可重试和不可重试异常，`MODEL_FAILED`、OSS 下载失败、Markdown 导出失败和未知运行时异常会在未超过 `CORPUS_PROCESS_MAX_RETRY_ATTEMPTS` 时投递 retry queue，retry queue 通过 TTL 回到主队列；`PARSE_FAILED` 或超过重试上限后写入最终失败状态并投递 DLQ。当前主要重试接口为 `POST /api/v1/corpus/capture-records/{id}/retry`；历史 `POST /api/v1/corpus/{id}/retry` 仍保留用于旧失败语料记录。

### Milestone 14：任务表拆分与采集批次建模

- [x] 新增 `capture_record` 表，专门承载截图采集任务、处理状态、OSS 对象、image_hash、重试次数和错误信息。
- [x] 将 `corpus_record` 收敛为纯语料表，只保存一条评论一条记录。
- [x] 将当前 `PROCESSING` 占位记录从 `corpus_record` 迁移到 `capture_record`，避免任务状态与语料内容混用。
- [x] 新增 `docs/current-chain-summary.md`，带详细注释总结当前上传、去重、OSS、MQ、Consumer、任务状态、轮询、重试/DLQ 和查询接口链路。
- [ ] 支持一图多评论、一批多图和后续人工校对流程。

说明：Milestone 14.1 / 14.2 已完成增量接入。当前已新增 `capture_record` 表、`CaptureRecord` Entity、Mapper、Service，并在新图上传时写入 `capture_record.process_status=PROCESSING`；`CorpusUploadResponse` 和 `CorpusProcessMessage` 已新增 `captureRecordId`，上传页也展示 `capture_record_id`。Milestone 14.3 已完成 Consumer 侧任务状态同步：处理成功时将 `capture_record.process_status` 更新为 `SUCCESS` 或 `EMPTY_RESULT` 并保存 `model_raw_response` / `error_message`；可重试失败时保持 `PROCESSING` 并更新 `retry_count`、`last_error_type`、`last_failed_at`；最终失败时更新为 `MODEL_FAILED` / `PARSE_FAILED` 并同步错误信息。Milestone 14.4 已移除新图异步上传时的 `corpus_record PROCESSING` 占位记录，新图上传只创建 `capture_record` 任务；Consumer 在成功识别后才写入一条或多条 `corpus_record`，空结果和最终失败只更新 `capture_record`；前端轮询改为先请求 `GET /api/v1/corpus/capture-records/{id}` 获取任务状态，成功后再按 `captureId` 拉取评论语料；新增 `POST /api/v1/corpus/capture-records/{id}/retry` 用于任务表重试。历史 `POST /api/v1/corpus/{id}/retry` 仍保留用于兼容旧失败语料记录。

### Milestone 15：查询、筛选与检索能力增强

- [x] 增加按 `platform`、`parse_status`、`tag`、`capture_id`、时间范围查询。
- [x] 增加分页列表 API。
- [x] 增加 Thymeleaf 管理页。
- [x] 增加按 `raw_content` / `context_target` 的关键词检索。
- [x] 增加只读语料分析工作台，支持总量、采集批次、平台、标签、解析状态、校对状态和采集日期聚合。
- [ ] 后续可考虑接入 Elasticsearch 或 MySQL Full-Text，用于更大规模语料检索。

建议优先级：中。该扩展能让项目从“采集链路”进一步变成“可检索资料库”。

说明：Milestone 15 已新增内部只读 `GET /api/v1/corpus` 分页查询 API，查询主资源为 `corpus_record`。支持平台、解析状态、单个 JSON 精确标签、采集批次、`collected_time` 半开时间范围和正文/上下文单关键词检索；文本字段通过重复 `searchFields=rawContent&searchFields=contextTarget` 参数指定，未指定时搜索两字段。结果按 `collected_time DESC, id DESC` 返回，默认 20 条、最大 100 条，并包含精确 `total` / `totalPages`。列表返回完整正文和上下文、标签、采集时间与图片 hash；私有 OSS 图片继续通过既有单记录 signed URL 接口按需获取。已新增 Thymeleaf 只读管理页 `/corpus/manage`，页面复用 `GET /api/v1/corpus` 做筛选、关键词检索和分页，不在页面 Controller 中复制查询规则；图片预览通过既有 `GET /api/v1/corpus/{id}/image-url` 按需获取临时 signed URL。已新增只读语料分析工作台 `/corpus/analytics` 和 `GET /api/v1/corpus/analytics`，复用同一套查询条件聚合总记录数、distinct 采集批次数、解析状态、校对状态、平台、标签和采集日期分布，作为面向研究者、数据分析者和运营分析场景的第一版数据概览。已新增 MyBatis-Plus MySQL 分页拦截器、`(collected_time DESC, id DESC)` 索引、MockMvc API 测试与 Testcontainers MySQL 8 集成测试；已在 Docker Desktop 环境验证 Controller 测试和 MySQL 集成测试通过。为使 `schema.sql` 可在 MySQL 8 初始化，已将 `capture_record.force` 保留关键字列和 MyBatis-Plus 映射显式转义。当前计划内查询、管理页与基础分析能力已完成；Elasticsearch / MySQL Full-Text 作为更大规模检索方向保留到后续真实数据量增长后评估。认证授权、多关键词/多标签逻辑、原评论发布时间筛选及全文检索明确延后；详细契约和性能取舍记录于 `docs/milestone-15-query-design.md`，讨论与实现过程记录于 `docs/milestone-15-implementation-record.md`。

跨 session 的状态、验证结果和下一步入口记录于 `docs/session-handoff.md`；该文档需在每个 session 结束时覆盖更新，里程碑与范围仍以本计划为准。

### Milestone 16：人工校对与版本追踪

- [x] 增加人工修正 `raw_content`、`context_target`、`tags` 的能力。
- [x] 保存模型原始识别结果和人工修订结果，避免覆盖证据链。
- [x] 增加校对状态，例如 `UNREVIEWED`、`REVIEWED`、`CORRECTED`。
- [x] Markdown 中可选择输出模型识别版本或人工校对版本。

建议优先级：中。该扩展适合在真实资料沉淀场景中提升语料质量。
说明：Milestone 16 已按方案 B 完成初版人工校对与版本追踪。`corpus_record.raw_content` / `context_target` / `tags` 保留为模型原始版本，新增 `review_status`、`reviewed_raw_content`、`reviewed_context_target`、`reviewed_tags`、`reviewed_at`、`review_note` 保存人工校对版本，避免覆盖证据链。新增 `PATCH /api/v1/corpus/{id}/review`，支持 `UNREVIEWED`、`REVIEWED`、`CORRECTED` 三种状态，`CORRECTED` 至少提交一个人工修订字段，并复用标签清洗规则去掉 `#` / `＃`、空值和重复项。`/corpus/manage` 已增量增加校对状态展示和轻量校对表单。Markdown 默认继续输出模型版本；当 `MARKDOWN_CONTENT_VERSION=reviewed` / `app.markdown.content-version=reviewed` 且记录为 `CORRECTED` 时，输出人工校对版本，并在 Front Matter 写入 `review_status` 与 `content_version`。初版不做完整版本历史表，后续如需审计每次修改可增加 revision 表；规划与取舍记录见 `docs/milestone-16-review-planning.md`。

### Milestone 17：Provider 与模型治理

- [x] 支持接口级或任务级模型选择，而不仅是全局 `app.vision.provider`。
- [x] 上传页支持前端选择 provider/model，并从配置读取候选模型。
- [x] 记录每次识别使用的 provider、model 和耗时。
- [x] 补充不同 Provider 响应中的 token 用量解析。
- [ ] 补充模型价格配置与成本估算规则。
- [x] 增加模型失败率、空结果率和平均耗时统计。
- [ ] 支持同一截图用不同模型重新识别并比较结果。

建议优先级：中。该扩展能让项目更适合展示“多模型 Provider 策略”和模型治理能力。

说明：Milestone 17 后台第一阶段已完成。`POST /api/v1/corpus/upload` 新增可选 `provider` / `model` 参数；未传时继续回退到全局 `app.vision.provider` 和对应默认模型。Thymeleaf 上传页 `/corpus/upload` 已新增 provider 下拉、model 候选输入和自定义模型输入能力，候选项由 `app.gemini.model-options` 与 `app.openrouter.model-options` 配置维护，切换 provider 时前端自动切换默认模型。新图异步任务会把实际 provider/model 写入 `capture_record` 和 `CorpusProcessMessage`，RabbitMQ Consumer 通过 `VisionRecognitionOptions` 按任务选择 Provider 和模型，重试/死信消息也保留该选择；`force=true` 同步重识别同样使用本次请求的 provider/model。Gemini 与 OpenRouter Provider 已支持单次调用级模型覆盖，不修改全局配置对象。新增 `recognition_run` 表、Entity、Mapper、Service，用于记录每次识别运行的 provider、model、状态、item_count、started_at、finished_at、duration_ms、错误信息和原始响应，并预留 input/output/total tokens 与 estimated_cost 字段。新增 `RecognitionTokenUsageParser`，从 Gemini `usageMetadata.promptTokenCount` / `candidatesTokenCount` / `totalTokenCount` 和 OpenRouter `usage.prompt_tokens` / `completion_tokens` / `total_tokens` 中解析 token 用量；异步 Consumer 与 `force=true` 同步重识别都会在 `recognition_run` 收尾时写入 `input_tokens`、`output_tokens`、`total_tokens`。新增 `GET /api/v1/recognition-runs/stats`，按 provider/model 聚合调用次数、成功率、空结果率、失败率、平均耗时、输入 token 合计、输出 token 合计、总 token 合计和成本合计。当前成本估算仍未启用，`estimated_cost` 保持预留字段；同图多模型重识别、结果比较和采纳流程仍未实现。

后续路线图：Milestone 17 的下一步可在“模型价格配置与成本估算”和“同图多模型重识别”之间选择。成本估算应优先采用本地配置而不是外部实时价格源，使 `estimated_cost` 在价格信息稳定时再写入真实数据；同图多模型重识别允许同一张截图使用不同 provider/model 生成多次识别运行记录；再进一步实现模型结果比较与采纳流程，用于选择某次识别结果沉淀为正式语料。上述能力完成前，前台 UI 仅需保持最小调用入口，不提前设计复杂比较页面。

### Milestone 18：简历与面试展示材料

- [ ] 补充系统架构图，覆盖上传接口、OSS、RabbitMQ、Consumer、Provider、MySQL 和 Markdown 输出。
- [ ] 补充核心链路时序图，区分新图、重复图、`force=true` 和模型失败路径。
- [ ] 准备项目讲解稿，突出图片去重、异步削峰、私有 OSS 证据链、多模型策略和失败状态管理。
- [x] 新增 `docs/architecture-learning-route.md`，作为当前异步双表架构、模块职责和《A Philosophy of Software Design》相关设计复盘的学习路线文档。
- [x] 新增 `docs/future-optimization-notes.md`，记录命名清晰化、Consumer 幂等、`EMPTY_RESULT`、失败重试治理、查询管理、人工校对和 Provider 治理等未来优化方向，并约定后续讨论中发现优化点时保留触发语境、当前设计、潜在问题、优化想法和归属判断。
- [x] 完成第一阶段内部命名清晰化，重点覆盖识别链路和 Markdown 导出链路。
- [ ] 准备 3 到 5 个真实截图演示样例，展示从上传到 Obsidian Markdown 输出的完整闭环。

说明：第一阶段命名清晰化仅调整 Java 内部变量、私有方法和少量解释性注释，不修改数据库字段、API URL、JSON 字段或对外 DTO 字段。当前已将主链路中的任务表主键语义明确为 `captureTask`，将 `capture_id` 的内部语义明确为 `captureBatchId`，将旧 `corpus_record PROCESSING` 兼容路径命名为 `legacyProcessingCorpusRecord`，并在 Gemini/OpenRouter Provider、统一 JSON 解析器和 Markdown 导出服务中区分厂商响应体、模型正文、统一识别结果、Markdown 文件名、输出路径和正文内容。

后续路线图：Milestone 18 优先补系统架构图和核心链路时序图，架构图覆盖上传接口、OSS、RabbitMQ、Consumer、Provider、MySQL、Markdown 输出和管理页；时序图区分新图、重复图、`force=true`、`EMPTY_RESULT`、模型失败重试和 DLQ 路径。随后准备项目讲解稿，重点突出图片 hash 去重、异步削峰、私有 OSS 证据链、多模型 Provider 策略、失败状态治理、人工校对和成本透明。真实演示样例以 3 到 5 张截图为第一阶段目标，必须来自真实平台截图，不伪造识别结果。

建议优先级：中。该扩展不直接改变业务能力，但能显著提升项目展示效果。
