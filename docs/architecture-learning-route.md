# Symptom-Graph 架构学习路线

本文档用于后续复习 Symptom-Graph 的当前架构。学习时以 `SYMPTOM_GRAPH_PLAN.md`、`README.md` 和 `docs/current-chain-summary.md` 为准；`docs/project-learning-plan.md` 中部分内容仍停留在旧的同步 MVP 视角，只适合作为历史参考。

## 1. 学习目标

完成本路线后，应能做到：

- 用 1 分钟讲清项目定位和业务价值。
- 用 3 到 5 分钟讲清上传、去重、OSS、MQ、Consumer、Provider、MySQL、Markdown 的完整链路。
- 解释 `capture_record` 和 `corpus_record` 拆分的原因。
- 解释为什么核心链路依赖 `VisionRecognitionService`，而不是直接依赖 Gemini 或 OpenRouter。
- 能用《A Philosophy of Software Design》中的深模块、信息泄露、更通用的接口三个概念评价当前代码。

## 2. 第一阶段：先建立业务心智图

先读：

- `SYMPTOM_GRAPH_PLAN.md`
- `README.md`
- `docs/current-chain-summary.md`

要回答的问题：

- 这个项目为什么不是普通 OCR Demo？
- 为什么系统强调原文优先、不可编造和证据链？
- 为什么同时写 MySQL 和 Obsidian Markdown？
- 为什么 Markdown 不保存 signed URL？
- 为什么真实截图测试不能伪造？

推荐一句话理解：

```text
Symptom-Graph 不是让模型分析观点，而是把截图中实际可见的中文评论安全、稳定、可追溯地沉淀为可检索语料。
```

当前真实主链路：

```text
上传截图
-> 读取文件字节
-> 计算 SHA-256 image_hash
-> Bloom Filter + MySQL 去重
-> 新图上传 OSS 私有 Bucket
-> 写入 capture_record PROCESSING
-> 投递 RabbitMQ
-> 上传接口立即返回
-> Consumer 从 OSS 下载原图
-> 调用 VisionRecognitionService
-> Gemini / OpenRouter Provider 识别
-> 写入一条或多条 corpus_record
-> 生成 Obsidian Markdown
-> 更新 capture_record 状态
```

## 3. 第二阶段：理解入口和编排层

重点文件：

- `src/main/java/com/symptomgraph/controller/CorpusUploadController.java`
- `src/main/java/com/symptomgraph/controller/CorpusPageController.java`
- `src/main/java/com/symptomgraph/service/impl/CorpusIngestionServiceImpl.java`

重点方法：

- `CorpusIngestionServiceImpl.ingest(...)`
- `CorpusIngestionServiceImpl.ingestNewImageAsync(...)`
- `CorpusIngestionServiceImpl.findExistingRecords(...)`

学习顺序：

1. 从 Controller 看请求如何进入系统。
2. 进入 `ingest(...)`，区分三条路径：新图、重复图、`force=true`。
3. 看新图为什么只同步完成 OSS、任务入库和 MQ 投递。
4. 看重复图为什么直接返回历史语料。
5. 看 `force=true` 为什么仍保留同步重识别。

关键理解：

- 上传接口不再同步等待模型识别，避免长时间阻塞请求线程。
- 新图上传时只创建 `capture_record`，不创建 `corpus_record PROCESSING` 占位记录。
- `force=true` 保留同步路径，是为了保证“重新识别成功后才覆盖旧语料”的安全语义。

## 4. 第三阶段：理解异步 Consumer

重点文件：

- `src/main/java/com/symptomgraph/mq/CorpusProcessMessageListener.java`
- `src/main/java/com/symptomgraph/mq/CorpusProcessFailureClassifier.java`
- `src/main/java/com/symptomgraph/mq/CorpusProcessMessageProducer.java`
- `src/main/java/com/symptomgraph/config/RabbitMqConfig.java`

重点方法：

- `CorpusProcessMessageListener.handle(...)`
- `CorpusProcessMessageListener.buildRecognitionRecords(...)`
- `CorpusProcessMessageListener.persistRecognitionRecords(...)`
- `CorpusProcessMessageListener.handleFailure(...)`

要回答的问题：

- Consumer 为什么从 OSS 下载原图，而不是消息里直接带图片字节？
- 模型返回空 `items` 时为什么更新 `capture_record=EMPTY_RESULT`，而不是写一条空语料？
- 哪些失败会进入 retry queue？
- 哪些失败会进入 DLQ？
- 为什么失败状态主要写在 `capture_record` 上？

关键理解：

```text
capture_record = 截图采集任务
corpus_record = 识别出来的一条评论语料
```

在识别完成前，系统不知道一张截图里有几条评论，因此任务状态不应污染语料表。这是 Milestone 14 拆表的核心价值。

## 5. 第四阶段：理解数据模型

重点文件：

- `src/main/resources/db/schema.sql`
- `src/main/java/com/symptomgraph/entity/CaptureRecord.java`
- `src/main/java/com/symptomgraph/entity/CorpusRecord.java`
- `src/main/java/com/symptomgraph/dto/CaptureRecordResponse.java`
- `src/main/java/com/symptomgraph/dto/CorpusRecordResponse.java`

`capture_record` 关注任务：

- `capture_id`
- `image_hash`
- `oss_bucket`
- `oss_object_key`
- `provider`
- `model`
- `process_status`
- `retry_count`
- `last_error_type`
- `error_message`
- `model_raw_response`

`corpus_record` 关注语料：

- `capture_id`
- `comment_index`
- `raw_content`
- `context_target`
- `platform`
- `original_publish_time`
- `tags`
- `markdown_path`

学习重点：

- `capture_id` 是任务和语料之间的关联键。
- 一张截图可以生成多条 `corpus_record`。
- `image_hash` 用于图片级去重。
- `oss_object_key` 和 `image_hash` 构成长期证据链。

## 6. 第五阶段：理解 Provider 抽象

重点文件：

- `src/main/java/com/symptomgraph/service/VisionRecognitionService.java`
- `src/main/java/com/symptomgraph/service/VisionRecognitionProvider.java`
- `src/main/java/com/symptomgraph/service/impl/ConfiguredVisionRecognitionService.java`
- `src/main/java/com/symptomgraph/service/impl/GeminiVisionServiceImpl.java`
- `src/main/java/com/symptomgraph/service/impl/OpenRouterVisionRecognitionServiceImpl.java`
- `src/main/java/com/symptomgraph/service/impl/VisionRecognitionPrompt.java`
- `src/main/java/com/symptomgraph/service/impl/VisionRecognitionJsonParser.java`

调用关系：

```text
Consumer / force=true 链路
-> VisionRecognitionService
-> ConfiguredVisionRecognitionService
-> VisionRecognitionProvider
-> GeminiVisionServiceImpl 或 OpenRouterVisionRecognitionServiceImpl
-> VisionRecognitionJsonParser
```

要回答的问题：

- 为什么核心采集链路不直接调用 Gemini？
- 新增一个 Provider 应该改哪里？
- Gemini 和 OpenRouter 响应格式不同，为什么业务层仍能拿到统一结果？
- 为什么 Prompt 和 JSON 解析要尽量复用？

关键理解：

`VisionRecognitionService` 是当前项目里比较清晰的深模块：接口很小，只暴露“给我图片字节和 MIME，返回识别结果”；内部隐藏 Provider 选择、HTTP 调用、响应抽取和 JSON 解析差异。

## 7. 第六阶段：理解证据链和 Markdown 输出

重点文件：

- `src/main/java/com/symptomgraph/service/impl/AliyunOssStorageServiceImpl.java`
- `src/main/java/com/symptomgraph/service/impl/MarkdownExportServiceImpl.java`
- `src/main/java/com/symptomgraph/config/MarkdownProperties.java`
- `src/main/resources/templates/corpus-upload.html`

要回答的问题：

- 为什么 OSS Bucket 是私有的？
- signed URL 为什么只用于页面预览？
- Markdown 为什么只保存 `image_hash` 和 `oss_object_key`？
- 数据库 tags 和 Obsidian tags 为什么不完全一样？
- Markdown 写入失败为什么会被视为可重试错误？

关键理解：

MySQL 面向结构化查询，Markdown 面向长期阅读、整理和写作。二者不是重复存储，而是服务不同使用场景。

## 8. 第七阶段：用深模块评价当前架构

深模块的判断标准：

```text
接口简单，内部承担足够多复杂性。
```

当前较好的例子：

- `VisionRecognitionService`
- `OssStorageService`
- `MarkdownExportService`
- `ImageHashBloomFilterService`

这些模块的共同点：

- 调用方不需要知道具体 Provider、OSS SDK、Markdown 文件细节或 Redis 细节。
- 接口暴露的是业务动作，而不是底层实现步骤。
- 替换实现时，主链路改动较小。

可以继续观察的问题：

- `CorpusIngestionServiceImpl` 是编排层，天然会比较宽；它是否仍承担了过多细节？
- `CorpusProcessMessageListener` 同时处理下载、识别、语料构建、Markdown、状态流转和失败重试，是否还能拆出更深的领域服务？

## 9. 第八阶段：用信息泄露评价当前架构

信息泄露的典型表现：

```text
同一类业务规则散落在多个模块中；修改规则时需要同时改多个地方。
```

当前值得关注的重复点：

- `CorpusIngestionServiceImpl` 和 `CorpusProcessMessageListener` 都有 tag 清洗逻辑。
- 两者都有模型结果转 `CorpusRecord` 的逻辑。
- 两者都有发布时间解析逻辑。
- 状态字符串如 `SUCCESS`、`EMPTY_RESULT`、`PROCESSING` 分散在多个类中。

这说明“如何把 VisionRecognitionResult 变成 CorpusRecord”这个知识还没有完全收敛。

可能的优化方向：

- 提取 `CorpusRecordFactory` 或 `RecognitionResultAssembler`。
- 提取 `TagSanitizer`。
- 将状态字符串收敛为 enum 或常量类。
- 让 Consumer 和 force=true 链路复用同一套“识别结果落库前构建规则”。

注意：这些是后续优化方向，不应在没有明确任务时贸然重构。

## 10. 第九阶段：用更通用的接口评价当前架构

更通用的接口不是“参数越多越通用”，而是让接口表达稳定的领域能力，而不是某个当前实现的偶然细节。

当前好的例子：

```java
VisionRecognitionResult recognize(byte[] imageBytes, String mimeType);
```

它表达的是“识别图片中的语料”，而不是：

- `callGemini(...)`
- `callOpenRouter(...)`
- `parseGeminiResponse(...)`
- `sendPromptToModel(...)`

因此新增 Provider 时，业务层不需要知道底层模型厂商。

当前需要谨慎的地方：

- `force=true` 同步链路和 MQ 异步链路都需要“识别并构造语料”，但目前没有一个更通用的领域接口承载这件事。
- 如果以后支持一批多图、人工校对、不同模型重跑，应该先设计稳定的领域接口，而不是把新参数一路塞进 Controller 和 Consumer。

## 11. 推荐复习顺序

第一次复习：

1. 读 `README.md` 的核心链路。
2. 读 `docs/current-chain-summary.md` 的 0 到 5 节。
3. 跟读 `CorpusIngestionServiceImpl.ingest(...)`。
4. 跟读 `CorpusProcessMessageListener.handle(...)`。
5. 画出新图上传时序图。

第二次复习：

1. 看 `capture_record` / `corpus_record` 字段。
2. 看查询接口如何按 `captureRecordId` 和 `captureId` 返回数据。
3. 看前端如何轮询任务状态。
4. 总结为什么拆表。

第三次复习：

1. 看 `VisionRecognitionService` / `VisionRecognitionProvider`。
2. 看 Gemini 和 OpenRouter 实现差异。
3. 看 Prompt 和 JSON Parser。
4. 用深模块和通用接口解释 Provider 抽象。

第四次复习：

1. 找出重复逻辑和信息泄露点。
2. 思考如何提取领域服务。
3. 对照 `SYMPTOM_GRAPH_PLAN.md`，判断优化是否属于当前 Milestone。

## 12. 自测问题

- 新图上传后，接口为什么立即返回 `PROCESSING`？
- 为什么新图上传时只写 `capture_record`，不写 `corpus_record PROCESSING`？
- 重复图 `force=false` 为什么不上传 OSS、不调用模型？
- `force=true` 为什么仍然同步执行？
- Bloom Filter 为什么不能作为最终去重依据？
- 模型返回空数组和模型失败有什么区别？
- 为什么 Markdown 不保存 signed URL？
- 新增一个多模态 Provider 需要改哪些类？
- 当前项目中哪些模块比较“深”？
- 当前项目中哪些业务规则存在信息泄露？

## 13. 下一步学习建议

短期先不要急着改代码。建议先做到：

- 能手画当前主链路时序图。
- 能解释双表拆分。
- 能指出 Provider 抽象的价值。
- 能指出至少 2 个信息泄露点和对应优化方向。

如果后续要做代码优化，优先选择低风险、收益明确的方向：

- 提取 tag 清洗逻辑。
- 收敛状态常量。
- 提取模型识别结果到语料记录的构建逻辑。

这些优化能直接对应《A Philosophy of Software Design》中的信息隐藏和深模块原则，同时不会扩大业务边界。
