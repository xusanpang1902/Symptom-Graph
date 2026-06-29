# Session Handoff

本文档是跨 session 的启动入口，不替代 `SYMPTOM_GRAPH_PLAN.md`。新 session 开始时先读项目计划，再读本文档；里程碑与范围仍以项目计划为准。

## 当前状态

- 当前优先方向：在已有采集、查询、校对和模型治理能力之上，增强面向研究者、数据分析者、市场运营者和舆论分析者的语料分析体验。
- 已完成：Milestone 17 的 provider/model 任务级选择、上传页模型切换、`recognition_run` 记录、token 用量解析和 provider/model 聚合统计。
- 本轮新增：Milestone 15 增加只读语料分析工作台 `/corpus/analytics`，提供与语料管理页一致的筛选条件，并聚合总量、采集批次、平台、标签、解析状态、校对状态和采集日期。
- 暂缓事项：成本估算仍不实现，避免依赖外部不稳定价格信息；同图多模型比较对当前目标用户优先级较低。
- 继续前先运行 `git status --short`，确认是否存在用户未提交改动。

## 推荐下一步

1. 提交本轮分析工作台改动，建议 commit message：`feat: add corpus analytics dashboard`。
2. 下一轮优先考虑“配置功能拓展”：把 provider/model 候选、默认模型、分析页展示维度等配置能力做得更清晰，而不是先做复杂模型效果比较。
3. 如果继续做前端，可把 `/corpus/manage` 与 `/corpus/analytics` 抽取出共享筛选交互，降低后续维护成本。
4. 等真实截图样本足够后，再回补 Milestone 12 的 20 张截图质量评估，不伪造测试结果。

## 本次已完成能力

- 新增 `GET /api/v1/corpus/analytics`，复用 `CorpusQueryRequest` 的平台、解析状态、标签、采集批次、时间范围、关键词和搜索字段筛选。
- 新增 `CorpusAnalyticsResponse`，返回 `totalRecords`、`distinctCaptureCount`、`parseStatusCounts`、`reviewStatusCounts`、`platformCounts`、`tagCounts`、`dailyCounts`。
- `CorpusRecordServiceImpl` 将查询条件构造抽成共享方法，分页查询和分析聚合使用同一套过滤规则。
- 新增 Thymeleaf 页面 `/corpus/analytics`，提供筛选表单、关键指标卡片和无第三方图表依赖的条形分布视图。
- `/corpus/manage` 顶部增加到分析工作台的入口。
- 补充 Controller、页面和 Testcontainers MySQL 集成测试；集成测试文件改为 ASCII 测试数据，避免终端编码导致断言损坏。

## 验证结果

已执行完整测试：

```powershell
mvn test
```

结果：57 个测试通过，0 失败，0 错误，0 跳过。测试包含 Testcontainers MySQL 8，要求 Docker Desktop 可用。

本轮追加验收：

```powershell
git diff --check
mvn test "-Dtest=CorpusQueryControllerTest,CorpusPageControllerTest,CorpusRecordQueryIntegrationTest"
```

结果：`git diff --check` 仅提示 Windows 换行转换，无空白错误；与分析工作台直接相关的 18 个测试通过，0 失败，0 错误，0 跳过。另已对 `corpus-analytics.html` 做模板关键字段检查，并用 `node --check` 校验页面内脚本语法通过。

## 关键入口

- 项目计划：`SYMPTOM_GRAPH_PLAN.md`
- 分析响应 DTO：`src/main/java/com/symptomgraph/dto/CorpusAnalyticsResponse.java`
- 语料查询 API：`src/main/java/com/symptomgraph/controller/CorpusQueryController.java`
- 页面路由：`src/main/java/com/symptomgraph/controller/CorpusPageController.java`
- 聚合实现：`src/main/java/com/symptomgraph/service/impl/CorpusRecordServiceImpl.java`
- 分析页面：`src/main/resources/templates/corpus-analytics.html`
- 管理页面入口：`src/main/resources/templates/corpus-manage.html`
- API 测试：`src/test/java/com/symptomgraph/controller/CorpusQueryControllerTest.java`
- 页面测试：`src/test/java/com/symptomgraph/controller/CorpusPageControllerTest.java`
- MySQL 集成测试：`src/test/java/com/symptomgraph/service/impl/CorpusRecordQueryIntegrationTest.java`
