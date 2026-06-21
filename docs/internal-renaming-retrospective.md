# 内部变量命名清晰化复盘

本文记录 Symptom-Graph 第一阶段内部命名清晰化的规划、执行、验收方式和影响面。它不是用户使用文档，而是后续复习项目架构、维护大型重命名任务、准备面试讲解时的参考材料。

## 1. 为什么要做这次命名整理

这次整理的直接原因不是功能缺失，而是概念密度变高后，变量名开始影响理解成本。

项目从早期同步 MVP 演进到 RabbitMQ 异步链路和 `capture_record` / `corpus_record` 双表模型后，同一条业务链路中同时出现了几类相近概念：

- `capture_record.id`：截图处理任务表主键，用来追踪异步任务状态。
- `capture_id`：一次截图采集批次号，用来关联同一张截图下的多条评论语料。
- `corpus_record.id`：语料记录主键，一条评论对应一条语料。
- 旧链路中的 `corpus_record PROCESSING` 占位记录：历史兼容逻辑，不再是当前主链路的任务状态来源。

如果这些概念都用 `record`、`records`、`captureId`、`processingRecord` 这类泛化变量名表达，代码仍然能运行，但阅读者需要不断回忆“这个 record 到底是任务、批次还是语料”。这种成本在大型工程中会持续放大。

所以这次任务的核心目标是：不改变业务行为，只让代码中的概念边界更清楚。

## 2. 我如何规划这项任务

规划时先做三件事。

第一，读取 `SYMPTOM_GRAPH_PLAN.md`，确认当前项目范围和里程碑。根据计划，当前命名清晰化属于 Milestone 18 的展示与学习材料方向，不应顺手推进 Milestone 15 查询检索、Milestone 16 人工校对或 Milestone 17 Provider 治理。

第二，确认改动边界。最终确定只做内部命名整理：

- 可以改 Java 局部变量、私有方法名、日志上下文和少量解释性注释。
- 不改数据库字段。
- 不改 API URL。
- 不改 JSON 字段。
- 不改 DTO 对外字段。
- 不改 Markdown 输出格式。

第三，按链路拆分执行顺序，而不是一次性全局替换。因为大型重命名最怕机械替换把不同语义的同名变量混在一起，所以这次按模块逐段处理：

- ingestion 编排链路：区分历史语料、新图任务和 `force=true` 重识别。
- Consumer 异步处理链路：区分 `capture_record` 任务和旧 `corpus_record PROCESSING` 兼容路径。
- Provider 识别链路：区分厂商响应体、模型正文和统一识别结果。
- JSON 解析器：区分模型正文和清理后的识别 JSON。
- Markdown 导出：区分文件名、输出路径和正文内容。

这个规划方式的重点是先固定语义边界，再动代码。否则变量名会变得“看起来更长”，但不一定更准确。

## 3. 关键命名对照

| 原有或容易混淆的名称 | 本次内部语义名 | 目的 |
| --- | --- | --- |
| `captureRecordId` | `captureTask` / `captureTaskId` 语义 | 表示 `capture_record` 是处理任务，不是语料本身 |
| `captureId` | `captureBatchId` 语义 | 表示它是采集批次号，底层字段仍叫 `capture_id` |
| `recordId` | `corpusRecordId` 语义 | 表示它指向 `corpus_record.id` |
| `processingRecord` | `legacyProcessingCorpusRecord` | 明确这是旧链路兼容的语料占位记录 |
| `records` | `corpusRecords` / `recognizedCorpusRecords` | 明确集合中保存的是语料记录 |
| `responseBody` | `providerResponseBody` | 表示这是 Gemini/OpenRouter 原始响应体 |
| `modelText` | `modelContentText` | 表示这是从厂商响应外壳里抽出的模型正文 |
| `result` | `recognitionResult` | 表示这是项目统一识别结果 |
| `filename` | `markdownFilename` | 表示这是 Markdown 文件名 |
| `outputPath` | `markdownOutputPath` | 表示这是 Markdown 文件输出路径 |
| `markdown` | `markdownContent` | 表示这是最终写入文件的 Markdown 正文 |

这些命名没有改变对外协议。比如 `captureId` 作为 DTO 字段和 API 字段仍然保留，只是在实现内部用更明确的局部变量表达它的真实含义。

## 4. 我如何完成这项任务

执行时采用“小步改动、及时验证”的方式。

第一步是整理 `CorpusIngestionServiceImpl`。这里是上传入口的核心编排类，需要区分三条路径：

- 重复图且 `force=false`：直接返回历史 `corpus_record`。
- 新图：创建 `capture_record` 任务，上传 OSS，投递 MQ。
- 已有图且 `force=true`：复用历史 OSS 对象，同步重识别，只有识别成功后才覆盖旧语料和 Markdown。

因此变量名从 `existingRecords`、`captureId`、`records` 调整为 `existingCorpusRecords`、`captureBatchId`、`recognizedCorpusRecords` 等。

第二步是整理 `CorpusProcessMessageListener`。这是最容易混淆的地方，因为它同时兼容新旧链路：

- 新链路以 `capture_record` 承载任务状态。
- 旧链路可能仍携带 `recordId`，指向 `corpus_record` 中的 `PROCESSING` 占位记录。

因此把 `processingRecord` 改为 `legacyProcessingCorpusRecord`，把 `captureRecord` 在内部表达为 `captureTask`，让读代码的人能一眼看出主链路和兼容链路的区别。

第三步是整理 Gemini / OpenRouter Provider。这里要分清三个层次：

- `providerResponseBody`：厂商 HTTP API 的原始返回。
- `modelContentText`：从厂商响应外壳中提取出来的模型正文。
- `recognitionResult`：解析成项目统一 DTO 后的识别结果。

这个区分能帮助理解 Provider 抽象：Gemini 和 OpenRouter 的外壳不同，但最终都要转成统一的 `VisionRecognitionResult`。

第四步是整理 `MarkdownExportServiceImpl`。这里不改输出内容，只把内部构建过程拆清楚：

- `markdownFilename`：稳定文件名。
- `markdownOutputPath`：实际输出路径。
- `markdownContent`：要写入文件的正文。
- `databaseTags`：数据库中的无 `#` 标签，后续再生成 Obsidian tags。

同时保留注释说明：Markdown 是长期研究资料，只保存稳定证据链字段，不写会过期的 signed URL。

第五步是更新 `SYMPTOM_GRAPH_PLAN.md`。按照项目约定，完成进展、决策或边界变化后，需要同步项目计划。这里记录了“第一阶段内部命名清晰化完成”，并明确未修改数据库字段、API URL、JSON 字段或对外 DTO 字段。

## 5. 我如何检测完成情况

这类任务不能只靠“编译通过”判断，因为命名整理的风险主要在语义误改和遗漏。因此检测分成四层。

第一层是工作区检查。

执行 `git status --short` 确认改动文件是否符合预期。本次主要涉及：

- `CorpusIngestionServiceImpl`
- `CorpusProcessMessageListener`
- `GeminiVisionServiceImpl`
- `OpenRouterVisionRecognitionServiceImpl`
- `VisionRecognitionJsonParser`
- `ConfiguredVisionRecognitionService`
- `MarkdownExportServiceImpl`
- `SYMPTOM_GRAPH_PLAN.md`

第二层是静态搜索。

使用 `rg` 搜索旧方法名和旧变量名，确认主链路里没有残留明显的旧命名，例如：

```powershell
rg -n "processingRecord|buildRecognitionRecords|persistRecognitionRecords|findExistingRecords|buildCaptureRecord|responseBody|modelText" src\main\java\com\symptomgraph
```

搜索结果显示主链路旧命名已清理。`OssPreviewController` 中仍有 `generateCaptureId`，它属于 OSS 验证页，不在本次 recognition / markdown 范围内，因此没有强行扩展修改范围。

第三层是 diff 复核。

查看 `git diff --stat` 和关键文件 diff，确认变动性质主要是变量名、私有方法名和注释，而不是协议、SQL、DTO 字段或业务分支变化。

第四层是聚焦测试。

执行的测试命令是：

```powershell
mvn "-Dtest=CorpusProcessMessageListenerTest,MarkdownExportServiceImplTest,ConfiguredVisionRecognitionServiceTest,GeminiVisionServiceImplTest,OpenRouterVisionRecognitionServiceImplTest,CorpusIngestionServiceImplTest" test
```

测试结果：

```text
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

这些测试覆盖了 Consumer 异步处理、Markdown 导出、Provider 路由、Gemini/OpenRouter 适配和 ingestion 主链路，能证明本次整理没有破坏核心行为。

## 6. 这次变动会影响哪些方面

会影响的方面：

- 代码可读性：后续阅读者更容易区分任务、批次、语料和旧链路兼容记录。
- 架构表达：讲解双表模型时，可以更自然地区分 `capture_record` 和 `corpus_record` 职责。
- 维护成本：后续继续做查询、人工校对、Provider 治理时，变量语义更稳定。
- 学习材料：这次整理可以作为项目复杂度上升后如何做低风险重构的案例。
- 注释价值：关键注释集中解释“为什么这样分层”，而不是重复代码表面行为。

不会影响的方面：

- 不影响数据库 schema。
- 不影响 API URL。
- 不影响 JSON 字段名。
- 不影响前端调用。
- 不影响 Markdown 文件格式。
- 不影响 OSS object key、image hash、signed URL 生成逻辑。
- 不影响 RabbitMQ 消息字段。
- 不影响 `force=true` 成功后才覆盖旧语料和 Markdown 的安全语义。

换句话说，这次变动是内部表达层面的重构，不是功能层面的重构。

## 7. 从这次任务学到的方法

大型命名整理不能靠一次全局替换完成。更稳妥的方法是：

1. 先读项目计划，明确当前里程碑和不能越界的范围。
2. 找出真正混淆的业务概念，而不是看到短变量就改长。
3. 先确定命名词汇表，再按模块小步修改。
4. 对外协议不动，内部语义先清晰。
5. 每改一类模块，就用静态搜索和测试确认没有破坏行为。
6. 最后把决策写回项目计划或学习文档，避免以后忘记为什么这样命名。

这套方法适用于后续类似任务，例如失败状态枚举化、Provider 治理命名、人工校对版本命名、查询筛选条件命名等。

## 8. 后续可继续优化的点

本次为了控制风险，没有继续做以下事情：

- 没有把 DTO 字段 `captureId` 改成 `captureBatchId`。
- 没有把数据库字段 `capture_id` 改名。
- 没有新增 `getByCaptureBatchId` / `listByCaptureBatchId` 这类 service 别名。
- 没有把状态字符串集中改成枚举。
- 没有拆分 `CorpusProcessMessageListener` 的职责。

这些都可以作为后续优化，但应分别归入对应 Milestone，而不是混在一次命名清晰化里完成。当前阶段最重要的是：让现有链路的核心概念先变得可读、可讲、可维护。
