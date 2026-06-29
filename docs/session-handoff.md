# Session Handoff

本文档是跨 session 的启动入口，不替代 `SYMPTOM_GRAPH_PLAN.md`。新 session 开始时先读项目计划，再读本文档；里程碑与范围仍以项目计划为准。

## 当前状态

- 当前最高优先级：Milestone 18，先完成面试展示闭环，而不是继续扩展成本估算或同图多模型比较。
- 已完成：上传采集、OSS 证据链、RabbitMQ 异步识别、失败重试/DLQ、双表建模、查询管理、人工校对、Provider/model 任务级选择、token 用量解析、识别运行统计和语料分析工作台。
- 已调整：Milestone 17 的“模型价格配置与成本估算”和“同图多模型重识别与结果比较”明确后移到 Milestone 18 之后。
- 用户下一步：输入真实截图材料，并整理真实测试结果。
- 系统下一步：围绕真实样例补齐架构图、核心链路时序图、项目讲解稿和展示材料。
- 继续前先运行 `git status --short`，确认是否存在用户未提交改动。

## 推荐下一步

1. 用户先准备 3 到 5 张真实截图作为面试演示样例，不需要一次性补满 20 张。
2. 每张截图测试结果优先填写到 `docs/milestone-12-quality-evaluation.md` 的“第一阶段演示样例记录”表。
3. 根据真实样例补 Milestone 18：系统架构图、核心链路时序图和项目讲解稿。
4. 完成展示闭环后，再评估是否继续做成本估算、同图多模型比较、Elasticsearch、权限体系或 revision 历史表。

## 本次已完成调整

- 更新 `SYMPTOM_GRAPH_PLAN.md`：把成本估算和同图多模型比较从当前 Milestone 17 阻塞项调整为 Milestone 18 之后的增强项。
- 更新 `docs/milestone-12-quality-evaluation.md`：新增 3 到 5 张真实截图的第一阶段演示样例记录表。
- 更新 `docs/project-completeness-summary.md`：同步当前项目完整度，标明 Provider 治理展示闭环已基本完成，主要缺口转向真实样例和展示材料。
- 覆盖更新本文档，明确下一阶段由用户输入真实图片材料，项目侧优先补展示材料。

## 当前展示完整度判断

- 作为 Java 后端面试项目，当前工程能力已经具备较高展示价值。
- 主要亮点：图片 hash 去重、私有 OSS 证据链、RabbitMQ 异步削峰、失败重试/DLQ、双表任务建模、多 Provider 策略、token 用量解析、查询/校对/分析页面、Markdown/Obsidian 输出。
- 当前最大缺口不是后端功能，而是真实截图样例、质量评估记录、架构图、时序图和讲解稿。

## 验证结果

本轮仅调整路线图和文档，未修改业务代码。

建议提交前执行：

```powershell
git diff --check
```

如后续补充图表或讲解稿，不需要跑完整 `mvn test`；如修改业务代码或模板，再执行相关测试。

## 关键入口

- 项目计划：`SYMPTOM_GRAPH_PLAN.md`
- 当前完整度总结：`docs/project-completeness-summary.md`
- 真实截图测试与演示样例记录：`docs/milestone-12-quality-evaluation.md`
- 当前链路说明：`docs/current-chain-summary.md`
- 后续优化记录：`docs/future-optimization-notes.md`
- 架构学习路线：`docs/architecture-learning-route.md`
