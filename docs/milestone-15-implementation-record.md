# Milestone 15：查询、筛选与检索实现记录

## 1. 背景与目标收敛

Milestone 15 的初始目标是让 Symptom-Graph 从“完成采集链路”进一步成为“可检索资料库”。讨论首先明确了该能力的服务对象和边界：它面向部署者、数据分析师与研究人员直接调用后端接口整理语料；它不替代 Obsidian，后者仍承担标签浏览、知识管理和图谱关联。

因此，首个交付先建立稳定、可复用的内部只读 API；随后补充 Thymeleaf 管理页时，页面严格复用该 API，不在页面 Controller 中复制查询规则。认证授权和更丰富的交互仍在后续阶段建设。

## 2. 主要设计决策与取舍

| 决策 | 实现结果 | 取舍理由 |
| --- | --- | --- |
| 主查询资源 | `corpus_record` | 研究对象是一条评论语料；`capture_record` 继续承担截图任务状态，不混入同一列表。 |
| API 形态 | 单一 `GET /api/v1/corpus` | 只读查询参数扁平，便于浏览器、Swagger、脚本和管理页复用。 |
| 分页 | 页码分页，默认 20、最大 100 | 支持总量、跳页和未来后台表格；当前数据量下精确总数的价值高于性能成本。 |
| 默认排序 | `collected_time DESC, id DESC` | 最新采集语料优先，且时间相同时保持稳定页边界。 |
| 文本检索 | 单关键词、普通 `LIKE` 包含匹配 | 满足初版中文语料定位；不提前引入 Full-Text、分词或 Elasticsearch。 |
| 检索字段 | 可重复 `searchFields` 参数 | 仅支持 `rawContent`、`contextTarget`；使用唯一规范，避免同时支持逗号格式带来的长期兼容复杂度。 |
| 标签查询 | 单个标签 JSON 精确匹配 | 避免子串误命中；多标签 ANY/ALL 逻辑留待后续。 |
| 时间范围 | 仅 `collectedTime`，半开区间 `[from, to)` | 采集时间完整、统一且稳定；原评论发布时间可能缺失或精度不一致。 |
| 图片访问 | 按需请求既有 signed URL 接口 | 列表只返回 `id` 和 `imageHash`，不批量生成会过期的私有 OSS URL。 |

参数语义遵循“可安全修复则归一化、无法可靠推断则拒绝”的原则：`page < 1` 归一化为 1，超出上限的 `pageSize` 归一化为 100；未知搜索字段、未知解析状态和矛盾时间范围返回 `400 Bad Request`；合法但无数据命中时返回 `200 OK` 与空页。

## 3. 实现结果

### 查询接口

新增：

```http
GET /api/v1/corpus
```

支持以下可选参数：

- `platform`、`parseStatus`、`tag`、`captureId`：精确筛选，结构化条件之间为 AND。
- `collectedFrom`、`collectedTo`：ISO 本地日期时间，区间为 `[collectedFrom, collectedTo)`。
- `keyword`：单个关键词包含匹配。
- `searchFields`：重复参数，例如 `searchFields=rawContent&searchFields=contextTarget`；未传时搜索正文和上下文。
- `page`、`pageSize`：页码分页。

响应提供 `page`、`pageSize`、`total`、`totalPages` 和 `records`。列表记录包含完整 `rawContent`、`contextTarget`、标签、平台、采集时间、批次、评论序号、状态和图片 hash；不暴露 `modelRawResponse`、错误详情、OSS 内部对象信息或 signed URL。

### Thymeleaf 管理页

新增：

```http
GET /corpus/manage
```

页面提供平台、解析状态、标签、采集批次、采集时间范围、单关键词、检索字段和分页参数的只读查询表单。前端通过 `fetch` 调用 `GET /api/v1/corpus` 拉取结果，并通过既有 `GET /api/v1/corpus/{id}/image-url` 按需获取单条记录截图预览。页面 Controller 只返回模板，不承载查询语义。

### 后端组织

实现遵循“按变化原因分离”的方式：

```text
CorpusQueryController
    -> HTTP 参数绑定与 JSON 响应
CorpusRecordService.search
    -> 查询语义、参数归一化与组合条件
MyBatis-Plus / CorpusRecordMapper
    -> MySQL 分页、JSON 标签匹配与 SQL 执行
```

新增 `CorpusQueryRequest`、`CorpusQueryPage`、`CorpusPageResponse` 和 `CorpusQueryRecordResponse`，将数据库实体、查询输入和公开列表输出隔离，避免表字段直接成为 API 契约。

### 数据库与性能

- 增加 MyBatis-Plus MySQL 分页拦截器，支持精确 `COUNT` 与当前页结果。
- 在 `corpus_record` 新增 `(collected_time DESC, id DESC)` 索引。
- 标签通过 `JSON_CONTAINS(tags, JSON_QUOTE(?))` 精确匹配，并使用参数绑定。
- 初版不增加 JSON 多值索引、全文索引、Elasticsearch、标签关联表或多组复合索引；后续以真实查询日志和 `EXPLAIN ANALYZE` 决定优化。

## 4. 测试过程与故障修复

### 自动化测试设计

新增两层测试：

- MockMvc Controller 测试：验证分页列表 JSON、完整正文/上下文返回以及不暴露 signed URL、模型原始响应等字段。
- Testcontainers MySQL 8 集成测试：用项目真实 `db/schema.sql` 启动临时数据库，验证 JSON 标签精确匹配、正文与上下文检索范围、半开时间范围、分页排序、参数归一化和非法条件处理。

执行命令：

```powershell
mvn -o test '-Dtest=CorpusQueryControllerTest,CorpusRecordQueryIntegrationTest'
```

最终结果：9 个测试通过，其中 Controller 测试 6 个，MySQL 8 集成测试 3 个。

### 发现并修复的问题

首次运行 Testcontainers 时，MySQL 8 无法执行 `schema.sql`。根因是 `capture_record` 表中的 `force` 使用了 MySQL 保留关键字 `FORCE`，导致建表 SQL 在该列处报语法错误。

修复措施：

- `schema.sql` 将列名改为 `` `force` ``。
- `CaptureRecord` 为该字段增加 `@TableField("`force`")`，保证 MyBatis-Plus 生成 SQL 时同样转义。

该问题并非查询 API 本身造成，但真实 MySQL 自动化测试使它在实施阶段被发现并修复。

## 5. 文档与后续工作

- [查询设计文档](milestone-15-query-design.md)：保留接口契约、参数规则、性能取舍和延期项。
- `README.md`：补充查询示例、索引迁移语句和测试覆盖说明。
- `SYMPTOM_GRAPH_PLAN.md`：更新 Milestone 15 的完成项、范围边界与测试结果。

本阶段明确未实现：认证与角色权限、多关键词布尔逻辑、多标签组合、原评论发布时间筛选、标签建议接口、MySQL Full-Text、Elasticsearch、游标分页和更完整的研究人员交互体验。
