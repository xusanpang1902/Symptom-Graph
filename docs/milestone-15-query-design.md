# Milestone 15：内部语料查询 API 设计

## 目标与边界

本阶段提供面向受信任内网部署者、数据分析师和研究人员的只读语料查询 API。它用于在 MySQL 中定位、浏览和整理 `corpus_record`，不替代 Obsidian 的标签、图谱和知识管理能力。

首个交付只实现 API；Thymeleaf 管理页、认证授权和面向非技术人员的交互优化留待后续阶段。部署者应通过内网、反向代理或 VPN 限制访问。

查询主资源是“一条评论语料” `corpus_record`，不是截图处理任务 `capture_record`。图片仍存于私有 OSS：列表不生成 signed URL，调用方按需请求既有的 `GET /api/v1/corpus/{id}/image-url`。

## API 契约

```http
GET /api/v1/corpus
```

| 参数 | 语义 |
| --- | --- |
| `platform` | 平台精确匹配 |
| `parseStatus` | 语料解析状态精确匹配 |
| `tag` | 单个标签精确匹配 JSON 标签数组 |
| `captureId` | 截图采集批次精确匹配 |
| `collectedFrom` / `collectedTo` | 采集时间范围，ISO 本地日期时间，区间为 `[from, to)` |
| `keyword` | 单个关键词包含检索 |
| `searchFields` | 可重复参数，只能为 `rawContent` 或 `contextTarget` |
| `page` / `pageSize` | 页码与页面大小 |

示例：

```http
GET /api/v1/corpus?platform=小红书&tag=医疗焦虑&keyword=检测&searchFields=rawContent&searchFields=contextTarget&collectedFrom=2026-06-01T00:00:00&collectedTo=2026-07-01T00:00:00&page=1&pageSize=20
```

结构化条件之间使用 AND。两个指定文本字段之间使用 OR。`searchFields` 未传时，关键词同时搜索正文和上下文；未传 `keyword` 时，字段范围被忽略。

`page` 默认 1，低于 1 时归一化为 1；`pageSize` 默认 20，范围为 1 到 100。结果固定按 `collectedTime DESC, id DESC` 排序，并返回实际生效的 `page` 和 `pageSize`。

响应包含精确 `total`、`totalPages` 和 `records`。每条记录返回完整 `rawContent`、`contextTarget`、标签、来源和证据 hash；不暴露 `modelRawResponse`、OSS 内部对象信息、错误详情、图片二进制或 signed URL。

无匹配条件返回 `200 OK` 与空页。未知 `searchFields`、不支持的 `parseStatus`、无效时间格式或 `collectedFrom >= collectedTo` 返回 `400 Bad Request`。可安全修复的页码参数由服务端归一化。

## 实现与性能取舍

普通字段使用 MyBatis-Plus 条件查询；标签使用 MySQL `JSON_CONTAINS` 精确匹配；关键词使用数据库默认排序规则下的 `LIKE '%keyword%'`。关键词和标签值必须通过参数绑定，不拼接 SQL。

分页使用 MyBatis-Plus MySQL 分页拦截器，因此每次列表查询会执行精确 `COUNT` 和当前页查询。当前数据规模下，精确总量对研究整理和未来管理页更有价值。

`corpus_record` 增加 `(collected_time DESC, id DESC)` 索引以服务默认时间排序。当前不增加 JSON 多值索引、全文索引、Elasticsearch、标签关联表或多组复合索引；应以真实数据量、查询日志和 `EXPLAIN ANALYZE` 结果决定后续优化。

## 自动化验证

- MockMvc 覆盖 API JSON 响应与公开字段边界。
- Testcontainers 启动临时 MySQL 8，并使用项目 `db/schema.sql` 初始化，验证 JSON 标签、文本字段范围、时间边界、排序、分页和参数归一化。
- 执行 `mvn test` 会运行普通测试和容器集成测试。运行环境必须有可访问的 Docker daemon；Docker 不可用时，集成测试应明确失败而不是静默跳过。本阶段已在 Docker Desktop 下验证 Controller 与 MySQL 8 集成测试通过。

## 延后项

- Thymeleaf 管理页与研究人员交互体验。
- 认证、授权与角色模型。
- 多关键词 ANY/ALL、多标签组合、原评论发布时间筛选和标签建议接口。
- MySQL Full-Text、Elasticsearch、游标分页与更大规模检索优化。
