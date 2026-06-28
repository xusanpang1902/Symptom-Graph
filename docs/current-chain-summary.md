# 当前已实现链路总结

本文档记录 Symptom-Graph 当前已实现的端到端链路。重点说明每条链路的输入、输出、状态流转、关键类和设计注释，避免后续开发继续沿用旧的同步 MVP 思路。

## 0. 当前总览

```text
上传截图
-> 读取文件字节
-> 计算 SHA-256 image_hash
-> Redis Bloom Filter 预判 hash 是否可能存在
-> MySQL 做最终去重判断
-> 新图上传到阿里云 OSS 私有 Bucket
-> 写入 capture_record PROCESSING 任务记录
-> 投递 RabbitMQ corpus.process.queue
-> 上传接口立即返回 captureRecordId / captureId / PROCESSING
-> 前端展示 signed URL 图片预览并开始轮询 capture_record
-> Consumer 下载 OSS 原图字节
-> Consumer 调用 VisionRecognitionService
-> VisionRecognitionService 路由到 Gemini 或 OpenRouter
-> 解析模型返回的可见评论 items
-> 成功时写入一条或多条 corpus_record
-> 为 SUCCESS 语料生成 Obsidian Markdown
-> 更新 capture_record 为 SUCCESS / EMPTY_RESULT / MODEL_FAILED / PARSE_FAILED
-> /corpus/manage 查询、筛选和预览语料
-> PATCH /api/v1/corpus/{id}/review 保存人工校对版本
```

注释：当前系统已经不是“上传接口同步调用大模型”的 MVP。新图上传只负责创建任务和投递 MQ，真正的大模型识别在后台 Consumer 中完成。

## 1. 新图上传异步识别链路

### 1.1 入口

```http
POST /api/v1/corpus/upload
Content-Type: multipart/form-data
```

页面入口：

```http
GET /corpus/upload
```

关键类：

| 类 | 职责 |
| --- | --- |
| `CorpusUploadController` | REST 上传入口 |
| `CorpusPageController` | Thymeleaf 上传页入口 |
| `CorpusIngestionServiceImpl` | 上传主链路编排 |
| `ImageHashBloomFilterService` | Bloom Filter 前置去重 |
| `OssStorageService` | OSS 上传和 signed URL 生成 |
| `CaptureRecordService` | 截图任务表写入 |
| `CorpusProcessMessageProducer` | RabbitMQ 投递 |

### 1.2 步骤与注释

| 步骤 | 动作 | 注释 |
| --- | --- | --- |
| 1 | 校验上传文件不为空 | 避免空请求进入 OSS / MQ 链路 |
| 2 | 读取文件字节 | 后续计算 SHA-256 使用完整原图字节 |
| 3 | 计算 `image_hash` | 当前使用 SHA-256，作为图片去重主键 |
| 4 | 查询 Bloom Filter | 只做“可能存在/一定不存在”的前置判断 |
| 5 | 必要时查询 MySQL | Bloom Filter 有假阳性，因此命中时必须穿透 MySQL 最终确认 |
| 6 | 新图上传 OSS | 原图进入私有 Bucket，作为长期证据链 |
| 7 | 写入 `capture_record` | 状态为 `PROCESSING`，保存 OSS、hash、provider、model 等任务元数据 |
| 8 | 写入 Bloom Filter | 新图 hash 进入过滤器，减少后续 MySQL 查询 |
| 9 | 投递 RabbitMQ | 消息携带 `captureRecordId`、`captureId`、OSS object key、mime type 等 |
| 10 | 返回响应 | 立即返回，不等待模型识别完成 |

### 1.3 新图响应

```json
{
  "captureId": "20260605150000_abcd1234",
  "imageHash": "...",
  "captureRecordId": 1001,
  "recordId": null,
  "parseStatus": "PROCESSING",
  "duplicate": false,
  "force": false,
  "asyncSubmitted": true,
  "records": []
}
```

注释：`recordId` 在当前新图异步链路中为 `null`，因为系统不再创建 `corpus_record PROCESSING` 占位记录。`records` 也为空，因为评论语料只有在 Consumer 成功识别后才会写入。

## 2. 图片去重链路

### 2.1 判断流程

```text
计算 image_hash
-> Bloom Filter mightContain(image_hash)
-> false: 认为数据库中不存在，按新图处理
-> true: 查询 MySQL listByImageHash(image_hash)
-> MySQL 有记录: 返回历史结果或执行 force=true
-> MySQL 无记录: 按新图处理
```

### 2.2 设计注释

- Bloom Filter 只用于降低 MySQL 查询压力，不作为最终事实来源。
- Bloom Filter 判定“不存在”时可以跳过 MySQL，因为 Bloom Filter 没有假阴性。
- Bloom Filter 判定“可能存在”时必须查 MySQL，因为 Bloom Filter 存在假阳性。
- Redis 未启用或异常时，系统回退到 MySQL 查询，优先保证正确性。

## 3. 重复图与 force=true 链路

### 3.1 force=false 重复图

```text
image_hash 已存在
-> force=false
-> 不上传 OSS
-> 不投递 RabbitMQ
-> 不调用模型
-> 直接返回历史 corpus_record 结果
```

注释：重复图默认复用历史结果，避免重复调用多模态模型，也避免重复生成 Markdown。

### 3.2 force=true 已有图

```text
image_hash 已存在
-> force=true
-> 复用已有 OSS object key
-> 同步调用 VisionRecognitionService
-> 识别成功后删除旧 corpus_record
-> 写入新 corpus_record
-> 覆盖同名 Markdown
```

安全语义：

- 如果重新识别成功，才替换旧语料。
- 如果重新识别失败，不删除旧语料。
- 如果模型失败或解析失败，不覆盖旧 Markdown。

注释：`force=true` 目前仍保留同步重识别，是为了严格保证“失败不破坏旧数据”。后续如果要异步化 force 链路，需要先设计临时版本表或结果暂存机制。

## 4. 双表职责链路

### 4.1 capture_record

`capture_record` 是截图采集任务表，记录一张截图从上传到后台处理完成的状态。

关键字段：

| 字段 | 注释 |
| --- | --- |
| `id` | 任务主键，上传响应中的 `captureRecordId` |
| `capture_id` | 一次截图采集 ID，用于关联最终语料 |
| `image_hash` | 图片 SHA-256 |
| `oss_bucket` | OSS Bucket 名称 |
| `oss_object_key` | OSS 原图 object key |
| `mime_type` | 上传图片 MIME 类型 |
| `provider` | 当前配置的多模态 Provider |
| `model` | 当前配置的多模态模型 |
| `process_status` | 任务状态 |
| `retry_count` | 后台处理重试次数 |
| `last_error_type` | 最后一次错误类型 |
| `last_failed_at` | 最后一次失败时间 |
| `error_message` | 错误信息 |
| `model_raw_response` | 模型原始返回 |

状态说明：

| 状态 | 注释 |
| --- | --- |
| `PROCESSING` | 已入库并投递 MQ，等待或正在后台处理 |
| `SUCCESS` | 已成功识别，且已写入一条或多条 `corpus_record` |
| `EMPTY_RESULT` | 模型返回空 `items`，没有可保存语料 |
| `MODEL_FAILED` | 模型调用、OSS 下载、Markdown 导出或外部依赖异常 |
| `PARSE_FAILED` | 模型返回解析失败或不可重试解析错误 |

### 4.2 corpus_record

`corpus_record` 是评论语料表，一条记录对应一条识别出的评论。

关键规则：

- 新图异步链路不再写入 `corpus_record PROCESSING` 占位记录。
- 只有识别成功后才写入 `corpus_record`。
- 同一截图的多条评论共享同一个 `capture_id` 和 `image_hash`。
- 每条评论通过 `comment_index` 区分。
- `raw_content` 和 `context_target` 必须来自截图可见文字，不能补写或编造。
- 数据库中的 tags 不带 `#`。
- 模型原始字段不被人工校对覆盖。
- 人工校对版本写入 `reviewed_raw_content`、`reviewed_context_target`、`reviewed_tags` 等独立字段。
- `review_status` 区分 `UNREVIEWED`、`REVIEWED`、`CORRECTED`。

## 4.3 查询管理与人工校对链路

页面入口：

```http
GET /corpus/manage
```

查询 API：

```http
GET /api/v1/corpus
```

人工校对 API：

```http
PATCH /api/v1/corpus/{id}/review
Content-Type: application/json
```

链路说明：

```text
/corpus/manage 输入筛选条件
-> fetch GET /api/v1/corpus
-> 展示 corpus_record 列表、模型原始正文、上下文、tags、review_status
-> 用户点击“校对”
-> 页面提交 PATCH /api/v1/corpus/{id}/review
-> CorpusRecordService.review 校验状态与字段
-> 清洗 reviewedTags，去掉 # / ＃、空值和重复项
-> 只更新人工校对字段，不覆盖 raw_content / context_target / tags / model_raw_response
-> 页面刷新当前查询结果
```

状态语义：

| 状态 | 注释 |
| --- | --- |
| `UNREVIEWED` | 尚未人工校对；人工校对字段为空 |
| `REVIEWED` | 人工确认模型结果可用；可保存备注 |
| `CORRECTED` | 人工修正过正文、上下文或标签；至少一个人工修订字段有值 |

Markdown 版本选择：

- 默认 `MARKDOWN_CONTENT_VERSION=model`，继续输出模型识别版本。
- 配置 `MARKDOWN_CONTENT_VERSION=reviewed` 后，`CORRECTED` 记录输出人工校对版本。
- Front Matter 会记录 `review_status` 和 `content_version`。

## 5. Consumer 后台识别链路

### 5.1 入口

RabbitMQ 主队列：

```text
corpus.process.queue
```

关键类：

| 类 | 职责 |
| --- | --- |
| `CorpusProcessMessageListener` | RabbitMQ Consumer |
| `CorpusProcessMessage` | MQ 消息体 |
| `VisionRecognitionService` | 多模型识别统一入口 |
| `VisionRecognitionProvider` | Provider 策略接口 |
| `MarkdownExportService` | Markdown 输出 |
| `CorpusProcessFailureClassifier` | 失败分类 |

### 5.2 步骤与注释

| 步骤 | 动作 | 注释 |
| --- | --- | --- |
| 1 | 读取 `captureRecordId` | 当前新图链路以任务表为主，不再依赖 `recordId` |
| 2 | 查询 `capture_record` | 找到 OSS object key、mime type、任务状态等元数据 |
| 3 | 从 OSS 下载原图 | Consumer 处理的是私有 Bucket 中的原图字节 |
| 4 | 调用 `VisionRecognitionService` | 保持核心链路不绑定 Gemini 或 OpenRouter |
| 5 | Provider 路由 | 根据 `app.vision.provider` 路由到当前模型实现 |
| 6 | 解析结果 | 读取 `platform`、`context_target`、`original_publish_time`、`items` |
| 7 | 清洗 tags | 去掉 `#` / `＃`、空值和重复项 |
| 8 | 写入语料 | 有评论时写入一条或多条 `corpus_record` |
| 9 | 导出 Markdown | 只对 `SUCCESS` 语料生成 Markdown |
| 10 | 更新任务状态 | 成功、空结果、失败都写回 `capture_record` |

### 5.3 成功分支

```text
模型返回 items 非空
-> 为每个 item 构造 corpus_record
-> saveBatch(corpus_record)
-> MarkdownExportService.export(record)
-> updateBatchById(corpus_record) 写入 markdown_path
-> capture_record.process_status = SUCCESS
```

### 5.4 空结果分支

```text
模型返回 items 为空
-> 不写 corpus_record
-> 不生成 Markdown
-> capture_record.process_status = EMPTY_RESULT
-> capture_record.error_message = "Vision recognition returned no visible comment items"
```

### 5.5 失败分支

```text
Consumer 发生异常
-> CorpusProcessFailureClassifier 分类
-> 可重试且未超限: 更新 capture_record 重试字段并投递 retry queue
-> 不可重试或超限: 更新 capture_record 最终失败状态并投递 DLQ
```

兼容注释：Consumer 仍支持历史消息中的 `recordId`。如果旧消息携带 `recordId` 并且存在旧 `corpus_record PROCESSING` 记录，第一条评论会复用旧记录，避免历史 MQ 消息无法处理。

## 6. 前端轮询链路

### 6.1 页面行为

上传页：

```http
GET /corpus/upload
```

异步新图上传成功后，页面拿到：

- `captureRecordId`
- `captureId`
- `parseStatus=PROCESSING`
- OSS signed URL 图片预览

### 6.2 轮询流程

```text
每 2 秒请求 GET /api/v1/corpus/capture-records/{id}
-> processStatus = PROCESSING: 继续轮询
-> processStatus = SUCCESS: 请求 GET /api/v1/corpus/captures/{captureId}
-> processStatus = EMPTY_RESULT: 展示空结果提示
-> processStatus = MODEL_FAILED / PARSE_FAILED: 展示错误信息
```

注释：前端不再通过空的 `corpus_record` 列表判断任务是否仍在处理，而是直接查询 `capture_record`。这避免了任务状态和语料内容混用。

## 7. RabbitMQ 重试与 DLQ 链路

### 7.1 队列配置

| 名称 | 说明 |
| --- | --- |
| `corpus.process.exchange` | 主交换机 |
| `corpus.process.queue` | 主队列 |
| `corpus.process` | 主 routing key |
| `corpus.process.retry.exchange` | 重试交换机 |
| `corpus.process.retry.queue` | 重试队列 |
| `corpus.process.retry` | 重试 routing key |
| `corpus.process.dlx.exchange` | DLQ 交换机 |
| `corpus.process.dlq` | 死信队列 |

### 7.2 失败分类

| 失败类型 | 是否重试 | 注释 |
| --- | --- | --- |
| `MODEL_FAILED` | 是 | 模型限流、网络波动等通常可能恢复 |
| OSS 下载失败 | 是 | 外部存储访问失败可能是临时问题 |
| Markdown 导出失败 | 是 | 本地文件系统异常可能恢复 |
| 未知运行时异常 | 是 | 保守重试，避免偶发异常直接失败 |
| `PARSE_FAILED` | 否 | 模型返回结构无法解析，重试价值较低 |

### 7.3 重试流程

```text
失败发生
-> nextRetryCount = message.retryCount + 1
-> retryable && nextRetryCount <= maxRetryAttempts
-> capture_record.process_status 保持 PROCESSING
-> 更新 retry_count / last_error_type / last_failed_at / error_message
-> 投递 corpus.process.retry.queue
-> TTL 到期后回到 corpus.process.queue
```

### 7.4 DLQ 流程

```text
不可重试 或 超过最大重试次数
-> capture_record.process_status = MODEL_FAILED / PARSE_FAILED
-> 更新错误信息和最后失败时间
-> 投递 corpus.process.dlq
```

注释：主队列没有强行绑定 DLX 参数，避免本地已有旧队列时 RabbitMQ 因队列参数不一致启动失败。当前 DLQ 采用 Consumer 手动投递。

## 8. 手动重试链路

### 8.1 当前主接口

```http
POST /api/v1/corpus/capture-records/{id}/retry
```

流程：

```text
查询 capture_record
-> 校验 process_status 必须是 MODEL_FAILED 或 PARSE_FAILED
-> 重置 process_status = PROCESSING
-> retry_count = 0
-> 清空 last_error_type / last_failed_at / error_message
-> 构建 CorpusProcessMessage
-> 投递 corpus.process.queue
```

### 8.2 历史兼容接口

```http
POST /api/v1/corpus/{id}/retry
```

注释：该接口用于历史失败语料记录。新图异步链路优先使用 `capture_record` 任务重试接口。

## 9. 查询链路

| 接口 | 返回 | 注释 |
| --- | --- | --- |
| `GET /api/v1/corpus` | `CorpusPageResponse` | 分页查询语料，支持平台、状态、标签、采集批次、时间范围和关键词检索 |
| `GET /api/v1/corpus/{id}` | `CorpusRecordResponse` | 查询单条语料详情 |
| `GET /api/v1/corpus/captures/{captureId}` | `List<CorpusRecordResponse>` | 查询同一截图下的所有评论语料 |
| `GET /api/v1/corpus/capture-records/{id}` | `CaptureRecordResponse` | 查询截图采集任务状态 |
| `GET /api/v1/corpus/{id}/image-url` | signed URL | 根据语料记录生成临时 OSS 访问链接 |
| `PATCH /api/v1/corpus/{id}/review` | `CorpusRecordResponse` | 保存人工校对状态和人工修订版本 |
| `POST /api/v1/corpus/capture-records/{id}/retry` | `CaptureRecordResponse` | 重试失败任务 |

注释：`/corpus/manage` 复用 `GET /api/v1/corpus` 做查询管理，并按需调用 signed URL 与人工校对接口。

## 10. Markdown 输出链路

### 10.1 输出规则

```text
obsidian-output/{capture_id}-{comment_index}-{platform}.md
```

每条 `SUCCESS` 语料生成一个 Markdown 文件。

### 10.2 保存内容

Markdown 中保存：

- 语料 ID
- `capture_id`
- `comment_index`
- `platform`
- `original_publish_time`
- `tags`
- Obsidian `#` tags
- `image_hash`
- `oss_object_key`
- `raw_content`
- `context_target`

Markdown 中不保存 signed URL。

注释：signed URL 会过期，不适合作为长期证据链。长期证据链只保存稳定的 `image_hash` 和 `oss_object_key`。

## 11. Provider 策略链路

```text
Consumer
-> VisionRecognitionService
-> ConfiguredVisionRecognitionService
-> 根据 app.vision.provider 选择 Provider
-> GeminiVisionServiceImpl 或 OpenRouterVisionRecognitionServiceImpl
-> VisionRecognitionJsonParser 解析统一 JSON 结构
```

注释：核心采集链路依赖 `VisionRecognitionService` 抽象，不直接依赖 Gemini 或 OpenRouter。新增 Provider 时应实现 `VisionRecognitionProvider`，不要把模型厂商逻辑写回上传或 Consumer 主流程。

## 12. 当前已知边界

- 至少 20 张真实中文平台截图测试仍待补足，不能伪造测试结果。
- `force=true` 处理已有图片时仍为同步重识别链路，用于保留“成功后才覆盖旧语料”的安全语义。
- 旧 `POST /api/v1/corpus/{id}/retry` 和旧 `recordId` 消息兼容逻辑仍保留，用于历史失败记录和历史 MQ 消息。
- Milestone 15 的分页、筛选、关键词检索和 Thymeleaf 管理页已完成初版。
- Milestone 16 的人工校对流程已完成初版，但只保存最新人工修订版本，不保存每次修订历史。
- 当前还没有一批多图上传、认证授权、任务级模型选择、Provider 质量统计或全文检索。

## 13. 后续开发提醒

后续不要重复实现以下已完成能力：

- Redis Bloom Filter 前置去重。
- RabbitMQ 异步削峰。
- retry queue / DLQ。
- `capture_record` / `corpus_record` 双表拆分。
- 前端 `capture_record` 状态轮询。
- `GET /api/v1/corpus` 分页查询和 `/corpus/manage` 管理页。
- `PATCH /api/v1/corpus/{id}/review` 人工校对初版。

建议下一阶段优先从 Milestone 17 的 Provider 与模型治理、Milestone 12 的真实截图质量评估或 Milestone 18 的展示材料中选择一个方向推进。
