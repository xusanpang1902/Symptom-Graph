# Session Handoff

本文档是跨 session 的启动入口，不替代 `SYMPTOM_GRAPH_PLAN.md`。新 session 开始时先读项目计划，再读本文档；里程碑与范围仍以项目计划为准。

## 当前状态

- 当前最高优先级原本是 Milestone 18 展示材料；本轮按用户要求新增 Milestone 19：飞书-Hermes 图片入口接入。
- 已完成：上传采集、OSS 证据链、RabbitMQ 异步识别、失败重试/DLQ、双表建模、查询管理、人工校对、Provider/model 任务级选择、token 用量解析、识别运行统计、语料分析工作台，以及飞书图片入口初版。
- 飞书入口当前通过 `POST /api/v1/feishu/events` 接收事件，支持飞书 `url_verification` 和未加密 `im.message.receive_v1` 图片消息。
- 飞书图片消息会写入 `feishu_ingestion_task` 做幂等和追踪，下载图片后复用 `CorpusIngestionService`，后续仍走现有 OSS、RabbitMQ、Consumer、Provider、MySQL 和 Markdown 链路。
- Consumer 完成 `capture_record` 后发布 `CaptureProcessingCompletedEvent`，飞书模块监听后主动回复原会话状态摘要。

## 本次已完成

- 新增飞书配置 `app.feishu.*`，默认 `FEISHU_ENABLED=false`。
- 新增 `feishu_ingestion_task` schema 和迁移脚本 `src/main/resources/db/migration/20260708_add_feishu_ingestion_task.sql`。
- 新增飞书适配层：`FeishuEventController`、`FeishuEventParser`、`FeishuImageIngestionService`、`FeishuReplyService`、`FeishuOpenApiClient` 和 `RestClientFeishuOpenApiClient`。
- 修改 `CorpusProcessMessageListener`，在成功、空结果和最终失败后发布完成事件；重试中不通知飞书。
- 新增测试：`FeishuEventControllerTest`、`FeishuImageIngestionServiceTest`，并更新 `CorpusProcessMessageListenerTest` 构造参数。
- 更新 `SYMPTOM_GRAPH_PLAN.md` 和 `docs/current-chain-summary.md`，记录 Milestone 19 和飞书链路边界。

## 验证结果

已通过：

```powershell
mvn test "-Dtest=FeishuEventControllerTest,FeishuImageIngestionServiceTest,CorpusProcessMessageListenerTest"
```

结果：11 个测试通过。

全量执行：

```powershell
mvn test
```

结果：失败。失败点不是本轮飞书测试：

- `CorpusRecordQueryIntegrationTest`：Testcontainers 找不到可用 Docker 环境。
- `CorpusRecordReviewServiceTest`：既有 MyBatis-Plus lambda cache 问题，报 `can not find lambda cache for this entity [com.symptomgraph.entity.CorpusRecord]`。

## 当前边界

- 计划中用户偏好“官方飞书 SDK”，但当前环境无法解析 Maven Central，未能可靠拉取 SDK；实现已把飞书调用隔离在 `FeishuOpenApiClient`，后续可替换为官方 SDK 实现。
- 当前飞书事件仅支持未加密回调，并校验 `verification-token`；`encrypt` 加密回调暂未实现。
- 当前飞书入口不解析 provider/model 指令，默认使用系统全局视觉 Provider 配置。
- 飞书多图消息按单个 `image_key` 任务模型预留，当前实现面向单图片消息事件。

## 已知未提交变更

- 本轮新增/修改：飞书入口代码、`CorpusProcessMessageListener`、配置、schema、迁移脚本、测试、`SYMPTOM_GRAPH_PLAN.md`、`docs/current-chain-summary.md`、本文档。
- 进入本轮前已存在且未处理：`README.md` 修改、`nextPrompt.md` 删除、`src/main/resources/db/migration/20260629_add_corpus_review_columns.sql` 未跟踪。

## 推荐下一步

1. 在有 Docker 的环境重跑全量测试，或先单独修复/隔离现有 Testcontainers 依赖。
2. 决定是否继续补飞书 `encrypt` 回调解密和官方 SDK 实现。
3. 配置真实飞书应用后，用一张真实截图做端到端手工验收：飞书发图、bot 回复受理、后台识别、bot 回复摘要、管理页可检索。
4. 若继续面试展示材料，回到 Milestone 18：架构图、时序图、讲解稿和真实样例记录。
