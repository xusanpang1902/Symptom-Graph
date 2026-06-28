# Milestone 16：人工校对与版本追踪规划

## 目标与边界

Milestone 16 的目标是在不破坏原始证据链的前提下，允许研究人员对模型识别出的语料进行人工校对，并能区分模型原始识别版本与人工修订版本。

本阶段只处理 `corpus_record` 中已识别成功的评论语料，不改动截图上传、OSS 证据链、RabbitMQ Consumer、Provider 路由和模型识别流程。认证授权、多人协作审计、完整操作日志和复杂版本树不纳入本阶段。

## 建议数据模型

保留现有模型识别字段作为原始版本：

- `raw_content`
- `context_target`
- `tags`
- `model_raw_response`

新增人工校对字段，避免覆盖模型原始结果：

- `review_status VARCHAR(32) NOT NULL DEFAULT 'UNREVIEWED'`
- `reviewed_raw_content TEXT NULL`
- `reviewed_context_target TEXT NULL`
- `reviewed_tags JSON NULL`
- `reviewed_at DATETIME NULL`
- `review_note TEXT NULL`

状态语义：

| 状态 | 含义 |
| --- | --- |
| `UNREVIEWED` | 尚未人工校对，默认状态 |
| `REVIEWED` | 已人工确认，内容未修改或不需要修改 |
| `CORRECTED` | 已人工修正正文、上下文或标签 |

当前不新增独立版本历史表。若后续需要保留每次修改记录，可在 Milestone 16 之后增加 `corpus_review_revision` 表。

## 接口与页面规划

后端新增只面向内部使用的校对接口：

```http
PATCH /api/v1/corpus/{id}/review
Content-Type: application/json
```

请求体建议：

```json
{
  "reviewStatus": "CORRECTED",
  "reviewedRawContent": "人工修正后的评论原文",
  "reviewedContextTarget": "人工修正后的上下文原文",
  "reviewedTags": ["标签一", "标签二"],
  "reviewNote": "可选校对备注"
}
```

接口规则：

- 只允许 `UNREVIEWED`、`REVIEWED`、`CORRECTED` 三种状态。
- `REVIEWED` 可以不提交修订字段，表示确认模型结果可用。
- `CORRECTED` 至少应提交一个修订字段。
- 标签入库前沿用现有清洗规则：去掉 `#` / `＃`、空值和重复项。
- 不修改 `image_hash`、`oss_object_key`、`model_raw_response`、`capture_id` 和 `comment_index`。

Thymeleaf 管理页可以先做最小增量：

- 在 `/corpus/manage` 结果卡片中展示校对状态。
- 提供“校对”入口，打开轻量表单编辑人工版本字段。
- 提交后刷新当前记录展示。

## Markdown 输出策略

Markdown 导出需要支持选择输出版本，但默认应保持兼容：

- 默认模式：继续输出模型原始版本，避免现有文件语义突变。
- 可选模式：当 `review_status=CORRECTED` 时输出人工修订版本。
- Front Matter 中增加 `review_status`，并在使用人工版本时增加 `content_version: "reviewed"`；模型原始版本使用 `content_version: "model"`。

如果本阶段实现页面提交后立即重导 Markdown，应只覆盖同一稳定文件名，不改变原有证据链字段。

## 实施顺序

1. 数据库与实体：更新 `schema.sql`、`CorpusRecord` 和公开响应 DTO，补充校对字段。
2. Service：新增校对请求 DTO 和校对方法，集中处理状态校验、标签清洗和更新字段。
3. API：新增 `PATCH /api/v1/corpus/{id}/review`，保持现有查询 API 可返回校对状态。
4. 页面：在 `/corpus/manage` 增加校对状态展示和最小编辑表单。
5. Markdown：增加输出版本选择能力，优先保持默认兼容模式。
6. 文档：更新 README、项目计划、交接文档和本规划的实施结果。

## 测试计划

- Service 测试：校验状态枚举、`CORRECTED` 至少一个修订字段、标签清洗、证据链字段不被修改。
- Controller 测试：成功校对、记录不存在、非法状态、非法空修正请求。
- Markdown 测试：模型版本默认输出、人工版本可选输出、Front Matter 包含校对状态。
- 管理页测试：`/corpus/manage` 包含校对状态与校对入口。

## 实施结果

已采用方案 B 完成初版实现：

- `corpus_record` 保留模型原始字段，并新增独立人工校对字段。
- 新增 `PATCH /api/v1/corpus/{id}/review`，由 `CorpusRecordService.review` 集中处理状态校验、标签清洗和字段更新。
- `GET /api/v1/corpus`、详情接口和按采集批次查询接口均返回校对状态与人工修订字段。
- `/corpus/manage` 已展示校对状态，并提供轻量校对表单提交人工版本。
- Markdown 默认输出模型版本；配置 `MARKDOWN_CONTENT_VERSION=reviewed` 后，`CORRECTED` 记录输出人工校对版本。
- 当前仍不做完整版本历史表、认证授权或多人审计。

验证命令：

```powershell
mvn test '-Dtest=CorpusQueryControllerTest,CorpusRecordReviewServiceTest,MarkdownExportServiceImplTest,CorpusPageControllerTest,CorpusRecordQueryIntegrationTest'
```

结果：23 个测试通过，覆盖页面 Controller、查询/校对 Controller、校对 Service、Markdown 输出和 MySQL 8 Testcontainers schema 初始化 / 查询集成。

## 当前默认决策

- 原始模型字段不覆盖，人工修订写入独立字段。
- 初版不做完整版本历史表，只做当前人工版本。
- 初版不做认证授权，继续沿用内部工具假设。
- Markdown 默认保持模型版本输出，人工版本输出作为可配置能力。
