# 未来优化方向备忘

本文记录当前链路复盘中发现的后续优化方向。这里的内容是设计备忘，不代表当前已经实现；后续实施时仍应以 `SYMPTOM_GRAPH_PLAN.md` 的里程碑和范围为准。

## 记录约定

后续讨论代码、链路或业务语义时，如果发现未来可能的优化点或扩展方向，应优先记录到本文。记录时尽量不要只写一个 TODO，而是保留以下信息：

- 触发语境：是在解释哪段代码、哪条链路或哪个业务状态时发现的。
- 当前设计：现有实现为什么这样做，已经解决了什么问题。
- 潜在问题：它在什么场景下可能带来混淆、风险或扩展成本。
- 优化想法：可以如何改，是否需要保持兼容，是否适合分阶段做。
- 归属判断：更适合放入哪个后续 Milestone，或者暂时只作为设计备忘。

如果某个优化点还不适合立即实现，应明确标注为“未来方向”或“待验证假设”，避免和当前已完成能力混淆。

## 1. 命名清晰化

当前系统中有三类 ID 容易混淆：

| 当前名称 | 实际含义 | 建议内部语义名 |
| --- | --- | --- |
| `capture_record.id` / `captureRecordId` | 截图处理任务表主键 | `captureTaskId` |
| `capture_id` / `captureId` | 一次截图采集批次号 | `captureBatchId` |
| `corpus_record.id` / `recordId` | 语料记录主键，当前多用于旧链路兼容 | `corpusRecordId` |

建议优先做低风险内部整理：

- 保持数据库字段、API JSON 字段和 URL 不变。
- Java 局部变量、私有方法和日志文案中使用更明确的命名。
- `processingRecord` 这类旧链路变量改为 `legacyProcessingCorpusRecord`，明确它不是当前主链路的任务状态。
- `getByCaptureId` / `listByCaptureId` 可在内部逐步改为 `getByCaptureBatchId` / `listByCaptureBatchId`，底层仍查询 `capture_id`。

## 2. Consumer 幂等保护

当前 Consumer 拿到 MQ 消息后，会定位 `capture_record` 并继续下载 OSS 图片、调用模型、写入语料。它还没有在处理前显式判断任务是否已经是终态。

后续可以在 Consumer 开始处理前增加状态判断：

| 当前任务状态 | 建议行为 |
| --- | --- |
| `PROCESSING` | 正常处理 |
| `SUCCESS` | 跳过，避免重复写入语料和重复生成 Markdown |
| `EMPTY_RESULT` | 跳过，避免重复调用模型 |
| `MODEL_FAILED` / `PARSE_FAILED` | 仅通过人工重试入口重新投递，不由旧消息继续处理 |

这样可以增强 MQ 重复投递、应用重启、手动重发消息等场景下的安全性。

## 3. EMPTY_RESULT 链路优化

当前 `EMPTY_RESULT` 表示模型调用完成，但没有识别出可保存语料：

```text
capture_record.process_status = EMPTY_RESULT
不写 corpus_record
不生成 Markdown
前端停止轮询并展示空结果
```

这个设计符合“不编造语料”的原则，但还有两个业务缝隙：

- 如果模型误判为空，当前 `POST /api/v1/corpus/capture-records/{id}/retry` 不允许重试 `EMPTY_RESULT`。
- 因为空结果不写 `corpus_record`，现有基于 `corpus_record.image_hash` 的去重可能无法命中空结果历史任务。

建议后续优化：

- 增加“重新识别空结果任务”的人工入口，可以允许 `EMPTY_RESULT` 重置为 `PROCESSING` 后重新投递。
- 去重时同时检查 `capture_record.image_hash`，如果同一图片已有 `EMPTY_RESULT`，默认返回已有空结果任务，而不是重复创建任务。
- 在管理页中清楚区分“空结果完成”和“识别失败”，避免用户误以为空结果是系统故障。

## 4. 失败与重试治理

当前失败状态已经分为：

| 状态 | 当前语义 |
| --- | --- |
| `MODEL_FAILED` | 模型调用、OSS 下载、外部依赖等可能可恢复的问题 |
| `PARSE_FAILED` | 模型返回结构解析失败或不可重试的结构化错误 |
| `EMPTY_RESULT` | 处理完成但没有可保存语料 |

后续可以继续增强：

- 将状态字符串集中为常量或枚举，避免在多个类中重复写字符串。
- 记录任务级 provider、model、耗时、失败分类和最后一次错误详情。
- 管理页提供按状态筛选任务，并支持对失败任务、空结果任务分别采取不同操作。
- 对 DLQ 中的任务提供查看与重新投递能力，方便人工修复配置后恢复处理。

## 5. 查询、管理与人工校对

随着 `capture_record` 和 `corpus_record` 双表稳定，后续应把项目从“采集链路”扩展为“可管理资料库”：

- 增加任务列表页，按 `process_status`、`image_hash`、`capture_id`、时间范围筛选。
- 语料列表页已完成初版，支持按 `platform`、`tag`、`raw_content`、`context_target` 检索；后续可增强保存筛选条件、批量操作和标签建议。
- 人工校对流程已采用方案 B 完成初版，保存模型原始识别结果和最新人工修订结果。
- 校对状态 `UNREVIEWED`、`REVIEWED`、`CORRECTED` 已完成初版。
- Markdown 已支持模型版本 / 人工校对版本可选输出，并保留原始证据链。
- 未来如需审计每次修订，可新增 `corpus_review_revision` 表；当前只保存最新人工校对版本。

## 6. Provider 与模型治理

当前 Provider 通过全局配置选择 Gemini 或 OpenRouter。后续如果要展示模型治理能力，可以增加：

- 任务级 provider / model 选择，而不仅是全局配置。
- 每次识别记录 provider、model、耗时和成本估算。
- 统计模型失败率、空结果率、平均耗时和不同平台截图的识别效果。
- 支持同一截图用不同模型重新识别并比较结果。

这些能力适合和 Milestone 17 对齐实施。

### Provider 注册与路由健壮性

触发语境：阅读 `ConfiguredVisionRecognitionService` 构造器时，当前实现会把 Spring 注入的 `List<VisionRecognitionProvider>` 转成 `Map<providerName, provider>`，运行时再根据 `app.vision.provider` 选择具体 Provider。

当前设计：这种写法让核心识别入口只依赖 `VisionRecognitionService`，避免业务链路直接判断 Gemini / OpenRouter，也方便后续继续新增 Provider。

潜在问题：`Collectors.toMap` 在遇到重复 provider name 时会抛出 `IllegalStateException`，但错误信息对业务语义不够友好；另外当前缺少启动日志或显式校验，排查“配置了不存在的 provider”时主要依赖运行时报错。

优化想法：后续可以在启动阶段输出已注册 Provider 列表，并对重复 provider name、空 provider name、配置项不在已注册列表中等情况给出更明确的异常或健康检查提示。该方向适合归入 Milestone 17 的 Provider 与模型治理。

### Provider HTTP 异常分类

触发语境：阅读 `GeminiVisionServiceImpl.callGemini` 时，当前实现会把 `RestClientResponseException` 和其他 `RestClientException` 统一包装为 `GeminiRecognitionException(STATUS_MODEL_FAILED, ...)`。

当前设计：这样可以把 Gemini API 调用失败统一交给上层失败治理处理，Consumer 侧会根据 `MODEL_FAILED` 将其视为可重试的外部模型失败。

潜在问题：并非所有 HTTP 错误都适合自动重试。例如网络超时、限流、5xx 更适合重试；但 API key 错误、权限不足、模型名配置错误、部分 400 请求格式错误通常需要人工修复配置或代码后再处理。

优化想法：后续可以根据 HTTP status 和 Gemini 错误体进一步区分失败类型，例如 `MODEL_RATE_LIMITED`、`MODEL_AUTH_FAILED`、`MODEL_BAD_REQUEST`、`MODEL_SERVICE_UNAVAILABLE`，并让重试策略只自动重试真正可恢复的错误。该方向适合归入 Milestone 17 的 Provider 与模型治理，或者 Milestone 13 的失败重试治理增强。

## 7. 文档与展示材料

后续展示项目时，可以把当前讨论沉淀为以下材料：

- 新图异步链路时序图。
- 重复图、`force=true`、`EMPTY_RESULT`、失败重试和 DLQ 的分支图。
- `capture_record` 与 `corpus_record` 双表职责说明。
- 命名整理前后的概念对照表。
- 真实截图样例和失败样例复盘。

这些内容适合和 Milestone 18 的简历与面试展示材料一起推进。

## 8. 推荐 Issue 拆分与优先级

为便于后续 GitHub Issue 管理，当前未来路线图建议拆成以下任务。Issue 用来承载具体可验收任务，`SYMPTOM_GRAPH_PLAN.md` 继续作为总路线图和范围来源。

### P0：当前收口与成本透明

- `chore: commit current milestone 15-17 changes`
  - 目标：提交当前已完成的查询管理、人工校对和 Provider 治理改动，避免后续开发继续混入同一批 diff。
  - 验收：工作区关键业务改动已进入 Git 历史，commit message 能概括 Milestone 15/16/17 的主要能力。
- `feat: parse token usage and estimate recognition cost`
  - 目标：从 Gemini/OpenRouter 原始响应中解析 token usage，并根据模型价格配置估算调用成本。
  - 验收：`recognition_run.input_tokens`、`output_tokens`、`total_tokens`、`estimated_cost` 可写入真实数据；`GET /api/v1/recognition-runs/stats` 可返回成本汇总；缺失 token 的模型不会导致识别任务失败。

### P1：模型治理与展示材料

- `feat: rerun capture recognition with selected model`
  - 目标：支持同一截图使用指定 provider/model 重新识别，并生成新的 `recognition_run`。
  - 验收：同一 `capture_record` 可保留多次识别运行记录，默认不破坏当前正式语料。
- `feat: compare recognition results across models`
  - 目标：展示同一截图在不同模型下的识别状态、评论数量、耗时和错误信息。
  - 验收：后端能返回同一截图的多次识别结果对比数据；前端比较页面可后置。
- `feat: add model result adoption workflow`
  - 目标：允许人工选择某次识别结果作为正式语料来源。
  - 验收：采纳动作必须明确，不因重识别自动覆盖正式语料；证据链字段保持可追溯。
- `docs: add architecture and sequence diagrams`
  - 目标：补充系统架构图和核心链路时序图。
  - 验收：架构图覆盖上传接口、OSS、RabbitMQ、Consumer、Provider、MySQL、Markdown 输出；时序图区分新图、重复图、`force=true`、失败重试和 DLQ。
- `docs: prepare project walkthrough script`
  - 目标：准备项目讲解稿，突出工程亮点。
  - 验收：讲解稿能覆盖图片去重、异步削峰、私有 OSS 证据链、多模型策略、失败状态治理、人工校对和成本透明。

### P2：真实数据验证与链路韧性

- `test: complete 20 real screenshot evaluation set`
  - 目标：补足至少 20 张真实中文平台截图测试。
  - 验收：记录成功样例、失败样例、模型误识别样例、空结果样例和 Prompt/Provider 问题总结；不能伪造测试结果。
- `feat: add consumer idempotency guard`
  - 目标：Consumer 处理前检查任务是否已进入终态，避免重复 MQ 消息导致重复识别或重复写入。
  - 验收：`SUCCESS`、`EMPTY_RESULT`、最终失败任务不会被旧消息重复处理。
- `feat: allow retry for empty recognition results`
  - 目标：允许人工对 `EMPTY_RESULT` 任务重新投递识别。
  - 验收：空结果可通过明确操作重试，不与自动失败重试混淆。
- `feat: classify provider HTTP failures`
  - 目标：细分限流、鉴权失败、模型不存在、请求格式错误和服务不可用等 Provider 错误。
  - 验收：重试策略只自动重试真正可恢复的错误，配置错误进入可人工修复的失败状态。

### P3：规模化与产品化

- `feat: add full-text search backend`
  - 目标：在真实数据量增长后评估 MySQL Full-Text 或 Elasticsearch。
  - 验收：比当前 LIKE 检索更适合大规模语料搜索，并保持现有筛选 API 兼容。
- `feat: add authentication and management authorization`
  - 目标：为管理页和内部 API 增加认证授权。
  - 验收：上传、管理、校对、重试和模型治理接口具备基本访问控制。
- `feat: add budget dashboard UI`
  - 目标：在前台展示模型成本、累计截图数、累计评论数和资料库成长指标。
  - 验收：用户能看到成本透明和资料资产增长，但底层数据仍以 `recognition_run` 与 `corpus_record` 为准。
