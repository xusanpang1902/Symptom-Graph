# Session Handoff

本文档是跨 session 的启动入口，不替代 `SYMPTOM_GRAPH_PLAN.md`。新 session 开始时先读本文件和项目计划，里程碑与范围仍以项目计划为准。

## 当前状态

- 当前 Milestone：Milestone 17「Provider 与模型治理」后台第一阶段已完成。
- 已完成：上传接口任务级 `provider` / `model` 选择、Consumer 按任务路由模型、`recognition_run` 识别运行记录、基础 provider/model 聚合统计 API。
- 下一项未完成任务：Milestone 17 token 用量解析、价格配置与成本估算。
- 工作区存在未提交改动；继续前先执行 `git status --short` 确认。

## 推荐执行顺序

1. 提交当前改动，建议 commit message 使用 `feat: add corpus management, review workflow, and model governance`。
2. 将后续任务拆成 GitHub Issues，优先覆盖 token/成本估算、同图多模型重识别、结果比较与采纳、展示材料和真实截图测试集。
3. 优先实现 Milestone 17 token/成本估算，让 `recognition_run` 的 token 与成本字段具备真实数据。
4. 并行推进 Milestone 18 展示材料，先补架构图、核心链路时序图和项目讲解稿。
5. 等用户提供足够真实截图后，再回补 Milestone 12 的 20 张截图质量评估，不能伪造测试结果。

## 本次已完成能力

- `POST /api/v1/corpus/upload` 新增可选参数 `provider` / `model`，旧调用不传参数时继续使用全局配置。
- 新图异步任务将实际 provider/model 写入 `capture_record` 和 `CorpusProcessMessage`。
- `ConfiguredVisionRecognitionService` 新增 `VisionRecognitionOptions` 路由能力。
- Gemini/OpenRouter Provider 支持单次调用级 model 覆盖，不修改全局配置对象。
- RabbitMQ Consumer 和 `force=true` 同步重识别都会写入 `recognition_run`，记录 provider、model、状态、item 数、开始/结束时间、耗时、错误信息和原始响应。
- 新增 `GET /api/v1/recognition-runs/stats`，按 provider/model 聚合调用次数、成功率、空结果率、失败率、平均耗时、token 合计和成本合计。
- `recognition_run` 已预留 `input_tokens`、`output_tokens`、`total_tokens`、`estimated_cost` 字段，但当前还没有真实解析和估算。

## 验证结果

已执行完整测试：

```powershell
mvn test
```

结果：50 个测试通过，0 失败，0 错误。

注意：沙箱内首次 Maven 执行会因远程仓库网络权限失败；本次通过授权后完成依赖解析和测试执行。完整测试包含 Testcontainers MySQL 8，要求 Docker Desktop 可用。

## 关键入口

- 项目计划：[SYMPTOM_GRAPH_PLAN.md](../SYMPTOM_GRAPH_PLAN.md)
- 当前链路说明：[current-chain-summary.md](current-chain-summary.md)
- 后续优化记录：[future-optimization-notes.md](future-optimization-notes.md)
- Provider 路由核心：`src/main/java/com/symptomgraph/service/impl/ConfiguredVisionRecognitionService.java`
- 异步识别核心：`src/main/java/com/symptomgraph/mq/CorpusProcessMessageListener.java`
- 识别运行记录：`src/main/java/com/symptomgraph/entity/RecognitionRun.java`
- 统计接口：`src/main/java/com/symptomgraph/controller/RecognitionRunController.java`
