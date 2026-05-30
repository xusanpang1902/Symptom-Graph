# Symptom-Graph

Symptom-Graph 是一个面向研究资料沉淀的中文互联网截图语料采集与索引系统。系统把截图中的可见评论提取为结构化记录，同时写入 MySQL 和 Obsidian Markdown，保留原始截图证据链，便于后续检索、整理和引用。

MVP 目标不是完整舆情平台，而是完成一条稳定、可展示、可扩展的采集链路。

## 技术栈

- JDK 17
- Spring Boot 3.3.x
- Thymeleaf
- MyBatis-Plus
- MySQL 8.0
- Aliyun OSS 私有 Bucket
- Gemini / OpenRouter 多模态 Provider
- Knife4j / OpenAPI
- Java File I/O Markdown 输出

## 核心链路

```text
上传截图
-> 计算 SHA-256 image_hash
-> 根据 image_hash 查询是否重复
-> 非重复时上传 OSS 私有 Bucket
-> 调用配置的多模态 Provider
-> 解析截图中所有可见评论
-> 多条语料写入 MySQL
-> 为每条语料生成一个 Markdown 文件
-> Thymeleaf 页面展示识别结果与 signed URL 图片预览
```

重复图片默认跳过 OSS 上传和模型调用，直接返回历史识别结果。传入 `force=true` 时会复用已有 OSS 对象，重新调用配置的多模态 Provider；只有重新识别成功后才替换旧识别记录并按稳定文件名覆盖 Markdown，避免模型失败破坏历史语料。

## 数据表设计

MVP 使用单表 `corpus_record` 表达一图多评论。

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `capture_id` | 一次截图采集 ID |
| `comment_index` | 同一截图内第几条评论 |
| `raw_content` | 截图中可见的原始评论 |
| `context_target` | 截图中可见的上下文原文 |
| `platform` | 来源平台 |
| `original_publish_time` | 原评论发布时间，可为空 |
| `collected_time` | 系统采集时间 |
| `oss_bucket` | OSS Bucket 名称 |
| `oss_object_key` | OSS Object Key |
| `image_hash` | 图片 SHA-256 |
| `tags` | JSON 标签数组，数据库中不带 `#` |
| `model_raw_response` | 多模态 Provider 原始返回 |
| `parse_status` | `SUCCESS`、`MODEL_FAILED`、`PARSE_FAILED`、`EMPTY_RESULT` 等 |
| `error_message` | 错误信息 |
| `markdown_path` | Markdown 文件路径 |
| `created_at` / `updated_at` | 创建和更新时间 |

建表脚本位于 `src/main/resources/db/schema.sql`。

## 模型 Provider

截图识别通过通用 `VisionRecognitionService` 接入，当前支持：

- `gemini`：调用 Google Gemini 多模态 API。
- `openrouter`：调用 OpenRouter Chat Completions API，适用于 `qwen/qwen2.5-vl-72b-instruct` 等支持 image input 的模型。

通过环境变量切换 provider：

```text
VISION_PROVIDER=openrouter
```

OpenRouter 的 `Image` 分类常指图像生成模型，不等于截图理解。Symptom-Graph 需要支持 image input 的多模态理解模型；text-only 模型不能直接处理截图。

## Prompt 约束

系统调用多模态模型时固定使用严格提取型 Prompt，核心约束如下：

```text
只能提取截图中实际可见文字。
不得推测、不得总结、不得改写、不得补全。
context_target 必须是截图中可见的上下文原文。
raw_content 必须是截图中可见的评论原文。
如果无法识别，返回 null 或空数组。
tags 不带 #。
tags 必须是现象性标签，不得对发言者做心理诊断或人格判断。
```

期望返回结构：

```json
{
  "platform": "小红书",
  "context_target": "截图中可见的上下文原文",
  "original_publish_time": null,
  "items": [
    {
      "comment_index": 1,
      "raw_content": "第一条评论原文",
      "tags": ["医疗焦虑", "恐艾"]
    }
  ]
}
```

## 私有 OSS 与 Signed URL

OSS Bucket 按私有读写设计。数据库只保存 `oss_bucket` 和 `oss_object_key`，页面展示时由后端临时生成 signed URL。

Markdown 文件不会保存 signed URL，避免链接过期后污染长期研究资料，只保存：

- `image_hash`
- `oss_object_key`

这两个字段用于追溯原始截图证据链。

## 图片 Hash 去重

上传文件会先计算 SHA-256：

- `force=false` 且 `image_hash` 已存在：直接返回历史记录，不上传 OSS，不调用模型。
- `force=true` 且 `image_hash` 已存在：复用已有 OSS 对象，重新识别，覆盖同名 Markdown。
- `image_hash` 不存在：上传 OSS，调用模型，入库并输出 Markdown。

## Obsidian Markdown 输出

默认输出目录：

```text
obsidian-output/
```

文件名规则：

```text
{capture_id}-{comment_index}-{platform}.md
```

示例：

```md
---
id: 123
capture_id: "20260528_xxx"
comment_index: 1
platform: "小红书"
original_publish_time:
collected_time: "2026-05-28 20:30:00"
tags:
  - "医疗焦虑"
  - "恐艾"
obsidian_tags:
  - "#医疗焦虑"
  - "#恐艾"
image_hash: "..."
oss_object_key: "corpus/2026/05/xxx.png"
---

# 小红书语料 123

## 原始评论

> 截图中提取出的评论原文

## 上下文原文

> 截图中可见的上下文原文

## 证据链

- image_hash: `...`
- oss_object_key: `corpus/2026/05/xxx.png`

## 研究备注
```

## 本地运行

### 1. 准备数据库

创建 MySQL 数据库后执行：

```sql
source src/main/resources/db/schema.sql;
```

### 2. 配置环境变量

必需环境变量：

```text
MYSQL_USERNAME=your_mysql_username
MYSQL_PASSWORD=your_mysql_password
ALIYUN_OSS_ENDPOINT=your_oss_endpoint
ALIYUN_OSS_BUCKET=your_private_bucket
ALIYUN_OSS_ACCESS_KEY_ID=your_access_key_id
ALIYUN_OSS_ACCESS_KEY_SECRET=your_access_key_secret
VISION_PROVIDER=openrouter
OPENROUTER_API_KEY=your_openrouter_api_key
```

常用可选环境变量：

```text
SERVER_PORT=8080
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=symptom_graph
ALIYUN_OSS_OBJECT_PREFIX=corpus/
ALIYUN_OSS_SIGNED_URL_EXPIRATION_MINUTES=30
OBSIDIAN_OUTPUT_DIR=obsidian-output
GEMINI_API_KEY=your_gemini_api_key
GEMINI_MODEL=gemini-1.5-flash
GEMINI_ENDPOINT=https://generativelanguage.googleapis.com/v1beta
OPENROUTER_MODEL=qwen/qwen3.6-flash
OPENROUTER_ENDPOINT=https://openrouter.ai/api/v1
OPENROUTER_REFERER=
OPENROUTER_TITLE=Symptom-Graph
```

Windows PowerShell 中可用以下命令确认当前终端进程是否能读取环境变量：

```powershell
$env:VISION_PROVIDER
$env:OPENROUTER_MODEL
if ($env:OPENROUTER_API_KEY) { "OPENROUTER_API_KEY is set" } else { "OPENROUTER_API_KEY is missing" }
```

如果通过系统环境变量界面新增变量，需要重启终端或 IDE 后再启动应用。`OPENROUTER_API_KEY` 只填写 key 本身，不需要包含 `Bearer ` 前缀。

### 3. 启动应用

```bash
mvn spring-boot:run
```

访问上传页面：

```text
http://localhost:8080/corpus/upload
```

OSS 验证页保留在：

```text
http://localhost:8080/oss-preview
```

## API

### 上传识别

```http
POST /api/v1/corpus/upload
Content-Type: multipart/form-data
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | File | 是 | 截图文件 |
| `force` | Boolean | 否 | 是否强制重新识别，默认 `false` |

`curl` 示例：

```bash
curl -X POST "http://localhost:8080/api/v1/corpus/upload" \
  -F "file=@sample.png" \
  -F "force=false"
```

### 查询详情

```http
GET /api/v1/corpus/{id}
```

### 按采集批次查询

```http
GET /api/v1/corpus/captures/{captureId}
```

### 获取图片临时访问链接

```http
GET /api/v1/corpus/{id}/image-url
```

返回：

```json
{
  "signedUrl": "https://..."
}
```

## 测试

运行自动化测试：

```bash
mvn test
```

当前测试覆盖：

- 核心采集编排链路。
- 图片 hash 去重和 `force=true` 重新识别。
- Gemini / OpenRouter 返回解析和异常处理。
- `force=true` 重新识别失败时保留历史记录。
- 空识别结果标记为 `EMPTY_RESULT`，不生成空语料 Markdown。
- 查询详情、按采集批次查询和 signed URL API。
- 模型 provider 路由。
- Markdown 输出格式。
- Thymeleaf 上传页和 signed URL 展示。

Milestone 8 的中文平台截图测试记录见 `docs/milestone-8-test-report.md`。
