【当前项目状态】

Symptom-Graph 当前已经不是同步阻塞 MVP。项目已完成以下主链路：

上传截图 -> 计算 SHA-256 image_hash -> Redis Bloom Filter 预判 -> MySQL 最终去重 -> 新图上传阿里云 OSS 私有 Bucket -> 写入 capture_record PROCESSING 任务记录 -> 投递 RabbitMQ corpus.process.queue -> 上传接口立即返回 captureRecordId / captureId / PROCESSING -> 前端轮询 capture_record 任务状态 -> Consumer 下载 OSS 原图 -> 调用 VisionRecognitionService 路由到 Gemini 或 OpenRouter -> 成功后写入一条或多条 corpus_record -> 为 SUCCESS 语料生成 Obsidian Markdown -> 更新 capture_record 为 SUCCESS / EMPTY_RESULT / MODEL_FAILED / PARSE_FAILED。

【已完成能力】

1. Redis Bloom Filter 去重前置过滤。
2. RabbitMQ 异步削峰。
3. Consumer 后台识别。
4. Gemini / OpenRouter 多模型 Provider 策略模式。
5. RabbitMQ retry queue、DLQ、失败分类。
6. `capture_record` 与 `corpus_record` 双表拆分。
7. 新图异步链路不再创建 `corpus_record PROCESSING` 占位记录。
8. 前端基于 `GET /api/v1/corpus/capture-records/{id}` 轮询任务状态，成功后再按 `captureId` 拉取语料。
9. 任务表重试接口 `POST /api/v1/corpus/capture-records/{id}/retry`。
10. 历史语料重试接口 `POST /api/v1/corpus/{id}/retry` 仍保留兼容。

【当前文档】

当前完整链路总结见：

```text
docs/current-chain-summary.md
```

项目计划和里程碑见：

```text
SYMPTOM_GRAPH_PLAN.md
```

【后续建议任务】

下一阶段不要重复实现 Redis、RabbitMQ、DLQ 或双表拆分。建议从 Milestone 15 开始：

1. 增加按 `platform`、`process_status` / `parse_status`、`tag`、`capture_id`、时间范围的查询筛选。
2. 增加分页列表 API。
3. 增加 Thymeleaf 管理页。
4. 增加 `raw_content` / `context_target` 关键词检索。
5. 暂不引入 Elasticsearch，第一版优先使用 MySQL 查询能力。

【必须保留的约束】

1. 不破坏 `force=true` 安全语义：重新识别失败不能删除或覆盖旧语料。
2. 不破坏 `VisionRecognitionProvider` / `VisionRecognitionService` 多模型策略模式。
3. `raw_content` / `context_target` 必须来自截图可见文字，不得编造。
4. 标签必须是经验性、现象性标签，数据库中不带 `#`。
5. 真实 20 张截图测试不能伪造，必须等待用户提供真实截图。
