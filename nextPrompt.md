【角色与背景】
你现在是一位资深的 Java 后端架构师。当前项目 Symptom-Graph 是一个基于 Spring Boot 3 + MyBatis-Plus 构建的非结构化截图语料采集系统。

当前的 MVP 主链路是完全同步阻塞的：
接收上传请求 -> 计算 SHA-256 (image_hash) -> MySQL 全表查询去重 -> 上传阿里云 OSS -> 同步调用大模型 (Gemini/OpenRouter) 解析 -> 写入 MySQL -> 生成本地 Markdown 文件 -> 返回前端结果。

【当前任务】
为了提升系统的并发能力、接口响应时间（RT）以及防止大模型 API 被并发打爆，请帮我引入以下三个高阶优化特性。请严格按照以下三个阶段，逐步为我生成或修改代码，绝对不要破坏现有的 force=true 覆盖逻辑和 VisionRecognitionProvider 策略模式。

阶段一：引入 Redis 布隆过滤器（Bloom Filter）优化去重性能
痛点：目前每次上传都需要拿着 image_hash 去 MySQL 做全表查，存在性能瓶颈。
要求：

引入 Redis（建议使用 Redisson 客户端，方便使用内置的布隆过滤器）。

在项目启动时或配置类中，初始化一个名为 symptom_graph_hash_bloom 的布隆过滤器（预计元素 10万，误判率 0.01）。

修改 CorpusIngestionService 中的去重逻辑：先查布隆过滤器。如果布隆过滤器说“不存在”，直接走后续上传流程（并把 hash 加入过滤器）；如果说“存在”，由于布隆过滤器有假阳性特性，请再穿透去 MySQL 查一次做最终确认。

阶段二：利用 RabbitMQ 实现异步削峰与解耦（替代传统的 @Async）
痛点：调用大模型耗时极长，且多图并发上传会导致系统崩溃和 API 限流。
要求：

引入 Spring Boot 的 RabbitMQ 依赖，配置基本的 Exchange（交换机）和 Queue（队列），例如 corpus.process.queue。

重构上传主干逻辑：当接口接收到一张新图片（非重复）时，同步完成这几步极快的基础操作：

计算 Hash。

上传原图到阿里云 OSS。

在 corpus_record 表中插入一条基础记录，将 parse_status 设为 PROCESSING（处理中）。

构建一个包含 recordId、imageUrl 等必要信息的 Message，发送到 RabbitMQ 队列。

接口立即向前端返回 HTTP 200 和当前记录的 id（不再原地等待大模型响应）。

阶段三：编写消费端逻辑（Consumer）
要求：

创建一个 RabbitMQ 监听器（Listener），监听 corpus.process.queue。

消费者拿到消息后，在后台线程去调用 VisionRecognitionService 获取大模型解析结果。

如果解析成功：将多条评论结果更新回 MySQL，将状态改为 SUCCESS，并调用 MarkdownExportService 生成 Markdown 文件。

如果解析失败或出现异常：捕获异常，将状态更新为 MODEL_FAILED 或 PARSE_FAILED，记录错误信息。

【输出要求】
请先给我一份改造方案的代码结构清单（例如需要新增哪些依赖、哪些配置类、修改哪些核心方法）。在我确认清单无误后，再逐个为我输出具体的 Java 代码。请在核心网络、队列投递和数据库状态流转的代码上方添加详细的中文注释。