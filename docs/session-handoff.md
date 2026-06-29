# Session Handoff

本文档是跨 session 的启动入口，不替代 `SYMPTOM_GRAPH_PLAN.md`。新 session 开始时先读本文件和项目计划，里程碑与范围仍以项目计划为准。

## 当前状态

- 当前 Milestone：Milestone 17「Provider 与模型治理」继续推进中。
- 已完成：上传接口任务级 `provider` / `model` 选择、Consumer 按任务路由模型、`recognition_run` 识别运行记录、基础 provider/model 聚合统计 API、Gemini/OpenRouter token 用量解析与 token 聚合统计。
- 下一项未完成任务：Milestone 17 模型价格配置与成本估算，或同图多模型重识别与结果比较。
- 工作区存在未提交改动；继续前先执行 `git status --short` 确认。

## 推荐执行顺序

1. 提交当前改动，建议 commit message 使用 `feat: track recognition token usage`。
2. 如继续 Milestone 17，优先在“模型价格配置与成本估算”和“同图多模型重识别”之间选择一个方向。
3. 成本估算不要依赖外部实时价格源，优先使用本地配置或手工维护的模型价格表。
4. 并行推进 Milestone 18 展示材料，先补架构图、核心链路时序图和项目讲解稿。
5. 等用户提供足够真实截图后，再回补 Milestone 12 的 20 张截图质量评估，不能伪造测试结果。

## 本次已完成能力

- 新增 `RecognitionTokenUsageParser`，集中解析 Provider 原始响应中的 token usage。
- Gemini token 映射：`usageMetadata.promptTokenCount` -> `input_tokens`，`usageMetadata.candidatesTokenCount` -> `output_tokens`，`usageMetadata.totalTokenCount` -> `total_tokens`。
- OpenRouter token 映射：`usage.prompt_tokens` -> `input_tokens`，`usage.completion_tokens` -> `output_tokens`，`usage.total_tokens` -> `total_tokens`。
- 异步 Consumer 和 `force=true` 同步重识别都会在 `recognition_run` 收尾时写入 `input_tokens`、`output_tokens`、`total_tokens`。
- Provider 未返回 usage、Provider 不支持解析或原始响应不是 JSON 时，token 字段保持 `null`，不会导致识别任务失败。
- `GET /api/v1/recognition-runs/stats` 现在按 provider/model 聚合返回 `inputTokens`、`outputTokens`、`totalTokens`，缺失 token 按 0 汇总。
- `estimated_cost` 仍为预留字段，本轮未实现成本估算。

## 验证结果

已执行完整测试：

```powershell
mvn test
```

结果：54 个测试通过，0 失败，0 错误。

注意：沙箱内 Maven 首次执行因远程仓库网络权限失败；授权后完成依赖解析和测试执行。完整测试包含 Testcontainers MySQL 8，要求 Docker Desktop 可用。

## 关键入口

- 项目计划：[SYMPTOM_GRAPH_PLAN.md](../SYMPTOM_GRAPH_PLAN.md)
- 当前链路说明：[current-chain-summary.md](current-chain-summary.md)
- 后续优化记录：[future-optimization-notes.md](future-optimization-notes.md)
- token 解析：`src/main/java/com/symptomgraph/service/RecognitionTokenUsageParser.java`
- 异步识别核心：`src/main/java/com/symptomgraph/mq/CorpusProcessMessageListener.java`
- 同步重识别核心：`src/main/java/com/symptomgraph/service/impl/CorpusIngestionServiceImpl.java`
- 识别运行记录：`src/main/java/com/symptomgraph/entity/RecognitionRun.java`
- 统计接口：`src/main/java/com/symptomgraph/controller/RecognitionRunController.java`
