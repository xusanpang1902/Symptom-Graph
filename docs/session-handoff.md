# Session Handoff

此文档是跨 session 的启动入口，不是项目计划或详细设计的替代品。

## 编写规范

每次准备结束一个 session 时，更新“当前交接记录”而不是不断追加工作日志。记录应保持简短、事实化，并满足以下规则：

1. 首先写明当前 Milestone、下一项未完成任务和工作区是否存在未提交改动。
2. 只记录会影响下一位执行者决策的信息：已完成内容、测试证据、环境前提、已知阻塞和直接入口文档。
3. 详细接口、架构取舍和故障复盘只链接到对应文档，不在本文件重复展开。
4. 记录已执行命令时，附带结果；未执行或无法执行时明确说明原因，不能写成已验证。
5. 新 session 开始时，先读取本文件和 `SYMPTOM_GRAPH_PLAN.md`，随后以项目计划作为里程碑与范围的唯一事实来源。
6. 完成新工作后，先更新项目计划和相关设计文档，再替换本节内容。

## 当前交接记录（2026-06-26）

### 当前状态

- 当前里程碑：Milestone 15“查询、筛选与检索能力增强”。
- 已完成：内部只读语料分页查询 API、结构化筛选、单关键词检索、MySQL JSON 标签精确匹配、页码分页、时间排序索引、MockMvc 与 Testcontainers 测试。
- 下一项未完成任务：Thymeleaf 查询管理页。它必须复用 `GET /api/v1/corpus`，不能在页面 Controller 中复制查询规则。
- 本次工作区包含未提交的 Milestone 15 源码、测试、README、计划和文档改动；继续前先执行 `git status --short` 确认状态。

### 关键入口

- [项目计划](../SYMPTOM_GRAPH_PLAN.md)：里程碑、范围与未完成任务。
- [查询设计](milestone-15-query-design.md)：API 契约、参数语义、性能取舍与延期项。
- [实现记录](milestone-15-implementation-record.md)：本次需求讨论、实现结构、故障修复和测试结果。
- [当前链路说明](current-chain-summary.md)：上传、任务、Consumer、语料与查询接口的整体关系。

### 已验证结果

Docker Desktop 已启动且 Testcontainers 可连接。以下命令已通过：

```powershell
mvn -o test '-Dtest=CorpusQueryControllerTest,CorpusRecordQueryIntegrationTest'
```

结果：9 个测试通过，其中 6 个 Controller 测试、3 个 MySQL 8 Testcontainers 集成测试。

### 重要实现与环境信息

- 查询入口：`GET /api/v1/corpus`；图片仍按记录 ID 调用既有 `GET /api/v1/corpus/{id}/image-url` 获取临时 signed URL。
- MySQL 8 将 `FORCE` 视为保留关键字。`capture_record.force` 已在 `schema.sql` 中以 `` `force` `` 定义，并在 `CaptureRecord` 中以 `@TableField("`force`")` 映射；后续不要移除该转义。
- 完整测试需要 Docker daemon；若 Docker 未启动，Testcontainers 应失败而不是跳过。
- 当前阶段明确未实现：管理页、认证授权、多关键词/多标签逻辑、原评论发布时间筛选、MySQL Full-Text、Elasticsearch、游标分页。
