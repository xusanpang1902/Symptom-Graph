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
- [ ] 新增 Consumer 后台调用 `VisionRecognitionService`，并按识别结果更新 MySQL 与生成 Markdown。
- [ ] 保持 `force=true` 只有重新识别成功后才覆盖旧记录的安全语义。

说明：Milestone 10 阶段一已完成 Redis Bloom Filter 去重优化。当前默认 `BLOOM_FILTER_ENABLED=false`，因此未配置 Redis 时仍保持原 MySQL 去重行为；开启后通过 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE` 连接 Redis，并初始化 `symptom_graph_hash_bloom`。Bloom Filter 仅作为 MySQL 去重前置过滤器，不作为最终存在性依据；命中时仍穿透查询 MySQL，未命中时才跳过 MySQL。阶段二已新增 `spring-boot-starter-amqp`、`RabbitMqConfig`、`CorpusProcessMessage` 和 `CorpusProcessMessageProducer`；新图上传现在只同步完成 OSS 上传、`PROCESSING` 占位记录入库、Bloom Filter 写入和 RabbitMQ 投递，然后立即返回 `recordId` / `captureId` / `parseStatus=PROCESSING`。重复图 `force=false` 仍返回历史结果；已有图 `force=true` 在 Consumer 未完成前暂时保留原同步重识别路径，继续保持“识别成功后才覆盖旧记录”的安全语义。Consumer 后台识别和异步 `force=true` 覆盖逻辑将在阶段三实现。
