# Symptom-Graph

Symptom-Graph 是一个面向研究资料沉淀的中文互联网截图语料采集与索引系统。系统把截图中的可见评论提取为结构化记录，同时写入 MySQL 和 Obsidian Markdown，保留原始截图证据链，便于后续检索、整理和引用。

MVP 目标是完成一条稳定、可展示、可扩展的采集链路。

## 技术栈

- JDK 17
- Spring Boot 3.3.x
- Thymeleaf
- MyBatis-Plus
- MySQL 8.0
- Redis / Redisson Bloom Filter
- RabbitMQ
- Aliyun OSS 私有 Bucket
- Gemini / OpenRouter 多模态 Provider
- Knife4j / OpenAPI
- Java File I/O Markdown 输出

## 核心链路

```text
上传截图
-> 计算 SHA-256 image_hash
-> Bloom Filter + MySQL 查询去重
-> 非重复时上传 OSS 私有 Bucket
-> 插入 capture_record PROCESSING 任务记录
-> 投递 RabbitMQ corpus.process.queue
-> 上传接口立即返回 captureRecordId / captureId
-> Consumer 后台下载 OSS 原图并调用多模态 Provider
-> 识别成功后写入 MySQL 多条语料记录
-> 为 SUCCESS 记录生成 Markdown
```

新图上传会立即返回 `PROCESSING` 状态。后台 Consumer 会从私有 OSS 下载原图，调用当前配置的多模态 Provider，识别成功后写入 MySQL 语料记录并生成 Markdown。前端或调用方可通过 `captureRecordId` 追踪任务状态，通过 `captureId` 查询最终评论语料。

重复图片默认跳过 OSS 上传和模型调用，直接返回历史识别结果。对已有图片传入 `force=true` 时，现阶段仍保留同步重识别逻辑：复用已有 OSS 对象，重新调用配置的多模态 Provider；只有重新识别成功后才替换旧识别记录并按稳定文件名覆盖 Markdown，避免模型失败破坏历史语料。

当前完整链路和详细注释说明见 `docs/current-chain-summary.md`。

## 运行依赖

本项目当前需要以下外部服务：

- MySQL：存储 `corpus_record` 语料记录。
- Aliyun OSS：私有 Bucket 保存原始截图。
- RabbitMQ：异步削峰，承载 `corpus.process.queue` 后台识别任务。
- Redis：可选，用于 Redisson Bloom Filter 优化图片 hash 去重。
- Gemini 或 OpenRouter：至少配置一个可用的多模态识别 Provider。

Redis Bloom Filter 默认关闭，未配置 Redis 时系统仍使用 MySQL 索引查重。RabbitMQ 是异步识别链路的必要依赖，新图上传会在投递队列后返回。

## 数据表设计

当前已演进为 `capture_record` + `corpus_record` 双表模型。`capture_record` 用于承载截图采集任务状态，`corpus_record` 用于承载识别出的评论语料。新图上传不再创建 `corpus_record PROCESSING` 占位记录；Consumer 成功识别后才写入一条或多条 `corpus_record`，空结果和最终失败只更新 `capture_record`。

### capture_record

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `capture_id` | 一次截图采集 ID |
| `image_hash` | 图片 SHA-256 |
| `oss_bucket` | OSS Bucket 名称 |
| `oss_object_key` | OSS Object Key |
| `mime_type` | 上传图片 MIME 类型 |
| `provider` | 多模态 Provider |
| `model` | 多模态模型名称 |
| `process_status` | `PROCESSING`、`SUCCESS`、`MODEL_FAILED`、`PARSE_FAILED`、`EMPTY_RESULT` 等 |
| `retry_count` | 后台处理重试次数 |
| `last_error_type` | 最后一次错误类型 |
| `last_failed_at` | 最后一次失败时间 |
| `error_message` | 错误信息 |
| `model_raw_response` | 多模态 Provider 原始返回 |
| `duplicate` | 是否重复截图任务 |
| `force` | 是否强制重新识别 |
| `created_at` / `updated_at` | 创建和更新时间 |

### corpus_record

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `capture_id` | 一次截图采集 ID |
| `comment_index` | 同一截图内第几条评论 |
| `raw_content` | 截图中可见的原始评论 |
| `context_target` | 截图中可见的上下文原文 |
| `platform` | 来源平台 |
| `original_publish_time` | 原评论发布时间，可为空 |
| `collected_time` | 系统采集时间 |
| `oss_bucket` | OSS Bucket 名称 |
| `oss_object_key` | OSS Object Key |
| `image_hash` | 图片 SHA-256 |
| `tags` | JSON 标签数组，数据库中不带 `#` |
| `model_raw_response` | 多模态 Provider 原始返回 |
| `parse_status` | `PROCESSING`、`SUCCESS`、`MODEL_FAILED`、`PARSE_FAILED`、`EMPTY_RESULT` 等 |
| `error_message` | 错误信息 |
| `retry_count` | 后台处理重试次数 |
| `last_error_type` | 最后一次错误类型 |
| `last_failed_at` | 最后一次失败时间 |
| `markdown_path` | Markdown 文件路径 |
| `created_at` / `updated_at` | 创建和更新时间 |

建表脚本位于 `src/main/resources/db/schema.sql`。

## 模型 Provider

截图识别通过通用 `VisionRecognitionService` 接入，当前支持：

- `gemini`：调用 Google Gemini 多模态 API。
- `openrouter`：调用 OpenRouter Chat Completions API，适用于 `qwen/qwen2.5-vl-72b-instruct` 等支持 image input 的模型。

通过环境变量切换 provider：

```text
VISION_PROVIDER=openrouter
```

OpenRouter 的 `Image` 分类常指图像生成模型，不等于截图理解。Symptom-Graph 需要支持 image input 的多模态理解模型；text-only 模型不能直接处理截图。

## Prompt 约束

系统调用多模态模型时固定使用严格提取型 Prompt，核心约束如下：

```text
只能提取截图中实际可见文字。
不得推测、不得总结、不得改写、不得补全。
context_target 必须是截图中可见的上下文原文。
raw_content 必须是截图中可见的评论原文。
如果无法识别，返回 null 或空数组。
tags 不带 #。
tags 必须是现象性标签，不得对发言者做心理诊断或人格判断。
```

期望返回结构：

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

## 私有 OSS 与 Signed URL

OSS Bucket 按私有读写设计。数据库只保存 `oss_bucket` 和 `oss_object_key`，页面展示时由后端临时生成 signed URL。

Markdown 文件不会保存 signed URL，避免链接过期后污染长期研究资料，只保存：

- `image_hash`
- `oss_object_key`

这两个字段用于追溯原始截图证据链。

## 图片 Hash 去重

上传文件会先计算 SHA-256：

- `force=false` 且 `image_hash` 已存在：直接返回历史记录，不上传 OSS，不调用模型。
- `force=true` 且 `image_hash` 已存在：复用已有 OSS 对象，重新识别，覆盖同名 Markdown。
- `image_hash` 不存在：上传 OSS，写入 `capture_record PROCESSING` 任务记录，投递 RabbitMQ 后立即返回。

可选开启 Redis Bloom Filter 作为 MySQL 查重前置过滤器。Bloom Filter 判定“不存在”时跳过 MySQL；判定“可能存在”时仍穿透 MySQL 做最终确认。

## 异步状态流转

新图上传采用 RabbitMQ 异步处理：

| 阶段 | `capture_record.process_status` | 说明 |
| --- | --- | --- |
| 上传成功并投递 MQ | `PROCESSING` | 已保存 OSS 原图和任务记录，等待 Consumer 识别 |
| 识别成功 | `SUCCESS` | 写入一条或多条 `corpus_record`，并生成 Markdown |
| 识别为空 | `EMPTY_RESULT` | 模型返回空 `items`，不写入空语料，不生成 Markdown |
| 模型调用失败 | `MODEL_FAILED` | 记录模型异常和错误信息 |
| 解析或其他异常 | `PARSE_FAILED` | 记录解析失败或后台处理异常 |

后台 Consumer 通过 `VisionRecognitionService` 调用当前配置的 Provider，因此异步化不会破坏 Gemini / OpenRouter 策略模式。

## RabbitMQ 重试与死信

后台识别失败后会按错误类型进行治理：

- 可重试错误：例如 `MODEL_FAILED`、OSS 下载失败、Markdown 导出失败和未知运行时异常。
- 不可重试错误：例如 `PARSE_FAILED`。
- 未超过最大重试次数时，消息进入 `corpus.process.retry.queue`，等待 TTL 后回到主队列。
- 超过最大重试次数或不可重试时，记录最终失败状态，并投递到 `corpus.process.dlq` 便于排查。
- 可通过 `POST /api/v1/corpus/capture-records/{id}/retry` 将失败任务重置为 `PROCESSING` 并重新投递主队列。
- 历史失败语料记录仍可通过 `POST /api/v1/corpus/{id}/retry` 兼容重试。

默认重试配置：

```text
CORPUS_PROCESS_MAX_RETRY_ATTEMPTS=3
CORPUS_PROCESS_RETRY_DELAY_MILLIS=10000
```

## Obsidian Markdown 输出

默认输出目录：

```text
obsidian-output/
```

文件名规则：

```text
{capture_id}-{comment_index}-{platform}.md
```

示例：

```md
---
id: 123
capture_id: "20260528_xxx"
comment_index: 1
platform: "小红书"
original_publish_time:
collected_time: "2026-05-28 20:30:00"
tags:
  - "医疗焦虑"
  - "恐艾"
obsidian_tags:
  - "#医疗焦虑"
  - "#恐艾"
image_hash: "..."
oss_object_key: "corpus/2026/05/xxx.png"
---

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

## 本地运行

### 1. 准备数据库

创建 MySQL 数据库后执行：

```sql
source src/main/resources/db/schema.sql;
```

### 2. 配置环境变量

必需环境变量：

```text
MYSQL_USERNAME=your_mysql_username
MYSQL_PASSWORD=your_mysql_password
ALIYUN_OSS_ENDPOINT=your_oss_endpoint
ALIYUN_OSS_BUCKET=your_private_bucket
ALIYUN_OSS_ACCESS_KEY_ID=your_access_key_id
ALIYUN_OSS_ACCESS_KEY_SECRET=your_access_key_secret
VISION_PROVIDER=openrouter
OPENROUTER_API_KEY=your_openrouter_api_key
```

常用可选环境变量：

```text
SERVER_PORT=8080
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=symptom_graph
ALIYUN_OSS_OBJECT_PREFIX=corpus/
ALIYUN_OSS_SIGNED_URL_EXPIRATION_MINUTES=30
OBSIDIAN_OUTPUT_DIR=obsidian-output
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest
RABBITMQ_VIRTUAL_HOST=/
CORPUS_PROCESS_EXCHANGE=corpus.process.exchange
CORPUS_PROCESS_QUEUE=corpus.process.queue
CORPUS_PROCESS_ROUTING_KEY=corpus.process
CORPUS_PROCESS_LISTENER_AUTO_STARTUP=true
CORPUS_PROCESS_RETRY_EXCHANGE=corpus.process.retry.exchange
CORPUS_PROCESS_RETRY_QUEUE=corpus.process.retry.queue
CORPUS_PROCESS_RETRY_ROUTING_KEY=corpus.process.retry
CORPUS_PROCESS_DLX_EXCHANGE=corpus.process.dlx.exchange
CORPUS_PROCESS_DLQ=corpus.process.dlq
CORPUS_PROCESS_DLQ_ROUTING_KEY=corpus.process.dlq
CORPUS_PROCESS_MAX_RETRY_ATTEMPTS=3
CORPUS_PROCESS_RETRY_DELAY_MILLIS=10000
BLOOM_FILTER_ENABLED=false
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DATABASE=0
GEMINI_API_KEY=your_gemini_api_key
GEMINI_MODEL=gemini-1.5-flash
GEMINI_ENDPOINT=https://generativelanguage.googleapis.com/v1beta
OPENROUTER_MODEL=qwen/qwen3.6-flash
OPENROUTER_ENDPOINT=https://openrouter.ai/api/v1
OPENROUTER_REFERER=
OPENROUTER_TITLE=Symptom-Graph
```

如果希望启用 Bloom Filter：

```text
BLOOM_FILTER_ENABLED=true
BLOOM_FILTER_NAME=symptom_graph_hash_bloom
BLOOM_FILTER_EXPECTED_INSERTIONS=100000
BLOOM_FILTER_FALSE_PROBABILITY=0.01
```

启动时系统会把 MySQL 中已有的 distinct `image_hash` 回灌到 Bloom Filter，避免空过滤器跳过历史数据查重。

Windows PowerShell 中可用以下命令确认当前终端进程是否能读取环境变量：

```powershell
$env:VISION_PROVIDER
$env:OPENROUTER_MODEL
if ($env:OPENROUTER_API_KEY) { "OPENROUTER_API_KEY is set" } else { "OPENROUTER_API_KEY is missing" }
```

如果通过系统环境变量界面新增变量，需要重启终端或 IDE 后再启动应用。`OPENROUTER_API_KEY` 只填写 key 本身，不需要包含 `Bearer ` 前缀。

### 3. 启动应用

如果你已有旧版本数据库，需要补充 Milestone 13 新增字段：

```sql
ALTER TABLE corpus_record
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '后台处理重试次数' AFTER error_message,
    ADD COLUMN last_error_type VARCHAR(64) NULL COMMENT '最后一次错误类型' AFTER retry_count,
    ADD COLUMN last_failed_at DATETIME NULL COMMENT '最后一次失败时间' AFTER last_error_type;

ALTER TABLE corpus_record
    ADD INDEX idx_collected_time_id (collected_time DESC, id DESC);
```

全新数据库可直接执行 `src/main/resources/db/schema.sql`。

如果你已有旧版本数据库，还需要补充 Milestone 14 新增的 `capture_record` 表：

```sql
CREATE TABLE IF NOT EXISTS capture_record (
    id BIGINT NOT NULL COMMENT '主键',
    capture_id VARCHAR(64) NOT NULL COMMENT '一次截图采集 ID',
    image_hash VARCHAR(64) NOT NULL COMMENT '图片 SHA-256',
    oss_bucket VARCHAR(128) NOT NULL COMMENT 'OSS Bucket 名称',
    oss_object_key VARCHAR(512) NOT NULL COMMENT 'OSS Object Key',
    mime_type VARCHAR(128) NULL COMMENT '上传图片 MIME 类型',
    provider VARCHAR(64) NULL COMMENT '多模态 Provider',
    model VARCHAR(128) NULL COMMENT '多模态模型名称',
    process_status VARCHAR(32) NOT NULL COMMENT '采集任务处理状态',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '后台处理重试次数',
    last_error_type VARCHAR(64) NULL COMMENT '最后一次错误类型',
    last_failed_at DATETIME NULL COMMENT '最后一次失败时间',
    error_message TEXT NULL COMMENT '错误信息',
    model_raw_response JSON NULL COMMENT '多模态 Provider 原始返回',
    duplicate TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否重复截图任务',
    force TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否强制重新识别',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_capture_id (capture_id),
    KEY idx_capture_image_hash (image_hash),
    KEY idx_capture_process_status (process_status),
    KEY idx_capture_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='截图采集任务记录';
```

```bash
mvn spring-boot:run
```

访问上传页面：

```text
http://localhost:8080/corpus/upload
```

OSS 验证页保留在：

```text
http://localhost:8080/oss-preview
```

## 当前验证状态

当前链路已完成至少 1 张真实中文平台截图的端到端验证：上传截图、调用多模态 Provider、写入结构化结果、输出本地 Obsidian Markdown，并在阿里云 OSS 私有 Bucket 中确认存在原图证据链备份。

已验证样例：

| 字段 | 值 |
| --- | --- |
| 平台 | 小红书 |
| `capture_id` | `20260529220731_0a256a18` |
| 评论数 | 3 |
| `image_hash` | `a98100192177869df76bb2be2dc153cb9872b053d56596042c0587e0c497fa2d` |
| `oss_object_key` | `corpus/2026/05/20260529220731_0a256a18/7dfccb89-1aeb-4b6f-b5c6-33bba1c67394.jpg` |

本地 Markdown 输出：

- `obsidian-output/20260529220731_0a256a18-1-小红书.md`
- `obsidian-output/20260529220731_0a256a18-2-小红书.md`
- `obsidian-output/20260529220731_0a256a18-3-小红书.md`

Milestone 8 的 20 张截图覆盖测试仍需继续补足。

## API

### 上传识别

```http
POST /api/v1/corpus/upload
Content-Type: multipart/form-data
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | File | 是 | 截图文件 |
| `force` | Boolean | 否 | 是否强制重新识别，默认 `false` |

非重复新图上传成功后，当前接口会立即返回 `PROCESSING` 任务，核心字段包括：

| 字段 | 说明 |
| --- | --- |
| `captureRecordId` | 初始 `capture_record` 任务 ID，Consumer 会同步更新该任务状态 |
| `recordId` | 新图异步链路中为 `null`；仅历史兼容链路可能返回 |
| `captureId` | 本次截图采集 ID |
| `imageHash` | 图片 SHA-256 |
| `parseStatus` | 当前为 `PROCESSING` |
| `asyncSubmitted` | 是否已投递 RabbitMQ |

示例响应：

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

后台处理完成后：

- 识别成功：`capture_record.process_status=SUCCESS`，同一 `captureId` 下新增一条或多条 `corpus_record`，并生成 Markdown。
- 识别为空：`capture_record.process_status=EMPTY_RESULT`，不写入空语料，不生成 Markdown。
- 模型或解析失败：`capture_record.process_status=MODEL_FAILED` 或 `PARSE_FAILED`，写入 `errorMessage`。

`curl` 示例：

```bash
curl -X POST "http://localhost:8080/api/v1/corpus/upload" \
  -F "file=@sample.png" \
  -F "force=false"
```

### 分页查询语料

```http
GET /api/v1/corpus?platform=小红书&tag=医疗焦虑&keyword=检测&searchFields=rawContent&searchFields=contextTarget&page=1&pageSize=20
```

支持 `platform`、`parseStatus`、单个 `tag`、`captureId`、`collectedFrom`、`collectedTo`、`keyword` 与重复的 `searchFields` 参数。时间范围为 `[collectedFrom, collectedTo)`；关键词未传 `searchFields` 时同时搜索正文与上下文。结果按采集时间和 ID 倒序分页返回，默认 20 条、最大 100 条。完整接口契约见 `docs/milestone-15-query-design.md`。

### 查询详情

```http
GET /api/v1/corpus/{id}
```

### 按采集批次查询

```http
GET /api/v1/corpus/captures/{captureId}
```

### 查询截图采集任务

```http
GET /api/v1/corpus/capture-records/{id}
```

### 获取图片临时访问链接

```http
GET /api/v1/corpus/{id}/image-url
```

返回：

```json
{
  "signedUrl": "https://..."
}
```

### 失败任务重新投递

```http
POST /api/v1/corpus/capture-records/{id}/retry
```

仅允许 `MODEL_FAILED` 或 `PARSE_FAILED` 任务重试。接口会将 `capture_record` 重置为 `PROCESSING`，清空错误信息和重试字段，并重新投递 RabbitMQ 主队列。

历史兼容接口：

```http
POST /api/v1/corpus/{id}/retry
```

仅允许 `MODEL_FAILED` 或 `PARSE_FAILED` 语料记录重试。

## 测试

运行自动化测试：

```bash
mvn test
```

当前测试覆盖：

- 核心采集编排链路。
- Redis Bloom Filter 前置查重和 MySQL 回退路径。
- RabbitMQ 新图投递、`capture_record PROCESSING` 任务记录和异步响应。
- RabbitMQ Consumer 成功识别、空结果和模型失败状态流转。
- RabbitMQ retry queue、DLQ、失败分类和手动重投递 API。
- 图片 hash 去重和已有图 `force=true` 重新识别。
- Gemini / OpenRouter 返回解析和异常处理。
- `force=true` 重新识别失败时保留历史记录。
- 空识别结果标记为 `EMPTY_RESULT`，不生成空语料 Markdown。
- 查询详情、按采集批次查询和 signed URL API。
- 内部语料分页查询 API、关键词字段范围和 MySQL JSON 标签匹配（Testcontainers MySQL 8）。
- 模型 provider 路由。
- Markdown 输出格式。
- Thymeleaf 上传页和 signed URL 展示。

Milestone 8 的中文平台截图测试记录见 `docs/milestone-8-test-report.md`。

## 后续路线

当前 MVP 已完成截图上传、去重、私有 OSS、RabbitMQ 异步识别、任务表拆分、多模型 Provider、MySQL 入库和 Obsidian Markdown 输出。当前链路总结和详细注释见 `docs/current-chain-summary.md`，后续计划详见 `SYMPTOM_GRAPH_PLAN.md` 的“后续扩展路线”，重点方向包括：

- 补充 Thymeleaf 查询管理页，并在数据规模增长后评估全文检索。
- 补足至少 20 张真实中文平台截图测试集。
- 增加人工校对、版本追踪和 Provider 识别质量统计。
- 补充架构图、时序图和项目讲解材料。

前端异步轮询已完成：新图上传返回 `PROCESSING` 后，页面会自动请求 `GET /api/v1/corpus/capture-records/{id}` 查询任务状态；任务成功后再请求 `GET /api/v1/corpus/captures/{captureId}` 并刷新最终识别结果。

Milestone 12 已建立真实截图质量评估框架，详见 `docs/milestone-12-quality-evaluation.md`。实际 20 张截图测试仍需要提供真实中文平台截图后执行。
