# DeepSeek API 开发与查阅手册

> 本文只记录 DeepSeek 协议。App 当前还支持 Xiaomi MiMo 和 Alibaba Cloud Qwen；字段差异和代码映射见 [MIMO_API_DEVELOPMENT.md](./MIMO_API_DEVELOPMENT.md) 与 [QWEN_API_DEVELOPMENT.md](./QWEN_API_DEVELOPMENT.md)。三家共用 `OpenAiCompatibleClient`，服务商差异由 `AiProvider` 分派。

> 面向对象：Legado AI 功能开发者、项目维护者，以及后续查阅 API 的 AI 助手。  
> 整理日期：2026-07-13，最近核对：2026-08-22（Asia/Shanghai）。  
> 官方文档入口：[DeepSeek API 文档（中文）](https://api-docs.deepseek.com/zh-cn/)。  
> 说明：模型、价格、限额和 Beta 能力可能调整。开发前优先核对官方链接，本文件记录的是上述日期可见的官方说明和本项目的实现约定。

## 1. 先看结论

本项目是 Kotlin Android App，调用 DeepSeek 应使用项目已有的 Kotlin、OkHttp、Gson 和协程，不需要 Python、Node.js、OpenAI SDK 或 Retrofit。

首期推荐方案：

| 项目 | 采用值 |
|---|---|
| API 格式 | OpenAI 兼容格式 |
| Base URL | `https://api.deepseek.com` |
| 对话接口 | `POST /chat/completions` |
| 鉴权 | `Authorization: Bearer <API_KEY>` |
| Content-Type | `application/json` |
| 默认模型 | `deepseek-v4-pro` |
| 快速/低成本模型 | `deepseek-v4-flash` |
| 当前输出 | 短对话/连接测试使用 `stream: false`；小说净化使用整章一次 `stream: true` SSE 请求 |
| JSON | 项目已有 Gson |
| HTTP | 项目已有 OkHttp 5 |
| 并发与取消 | Kotlin 协程 + OkHttp `Call.cancel()` |

不要再把 `deepseek-chat` 或 `deepseek-reasoner` 写入新代码。它们计划于北京时间 2026-07-24 23:59 弃用；当前模型是 `deepseek-v4-flash` 和 `deepseek-v4-pro`。详见[首次调用](https://api-docs.deepseek.com/zh-cn/)及[更新日志](https://api-docs.deepseek.com/zh-cn/updates/)。

## 2. 官方文档每个部分的作用

### 2.1 快速开始

#### 首次调用 API

作用：确认 Base URL、API Key、模型名和最小请求格式，是排查“地址、鉴权或模型写错”的第一入口。

与本项目有关的内容：

- OpenAI 兼容 Base URL：`https://api.deepseek.com`。
- Anthropic 兼容 Base URL：`https://api.deepseek.com/anthropic`。
- 常规对话终点：`POST /chat/completions`。
- `stream=false` 返回一个完整 JSON；`stream=true` 返回 SSE 增量。

官方链接：[首次调用 API](https://api-docs.deepseek.com/zh-cn/)

#### 模型与价格

作用：选择模型、估算成本、了解上下文和输出长度，以及确认某项能力是否支持。

整理时的官方信息：

| 模型 | 定位建议 | 上下文 | 最大输出 | 思考模式 | JSON/Tools |
|---|---|---:|---:|---|---|
| `deepseek-v4-flash` | 响应快、成本低，适合短问答、摘要、翻译 | 1M | 384K | 支持，默认开启 | 支持 |
| `deepseek-v4-pro` | 更重质量，适合复杂分析、工具调用和长内容 | 1M | 384K | 支持，默认开启 | 支持 |

价格会变，不在代码中硬编码。界面若展示价格，应从可更新配置获取或直接跳转官方页面。

官方链接：[模型与价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)

#### Token 用量计算

作用：理解计费、上下文截断和响应中的 `usage`。

开发要点：

- 实际 token 数以响应的 `usage` 为准，不要用字符数精确推算。
- 重点记录 `prompt_tokens`、`completion_tokens`、`total_tokens`。
- 缓存分析还要记录 `prompt_cache_hit_tokens` 和 `prompt_cache_miss_tokens`。
- 思考模式可以从 `completion_tokens_details.reasoning_tokens` 观察推理 token。

官方链接：[Token 用量计算](https://api-docs.deepseek.com/zh-cn/quick_start/token_usage/)

#### 限速与隔离

作用：处理并发、429、不同用户之间的缓存/调度隔离和长等待连接。

开发要点：

- 并发按账号计算，不按单个 API Key 计算。
- 超过并发限制会收到 HTTP 429。
- 可选 `user_id` 仅允许 `[a-zA-Z0-9\-_]+`，最长 512，不能包含隐私信息。
- 非流式等待期间服务端可能持续发送空行。
- 流式等待期间可能收到 SSE 注释 `: keep-alive`。
- 服务器可能在模型开始推理前保持长连接，因此 OkHttp 的读取超时不能设置得过短。

对于本地个人 App，首期不必传 `user_id`。如果以后支持多用户服务端代理，应生成不可逆、无隐私的业务用户标识。

官方链接：[限速与隔离](https://api-docs.deepseek.com/zh-cn/quick_start/rate_limit/)

#### 错误码

作用：将 HTTP 状态转换为用户能理解的提示，并决定是否重试。

| 状态 | 含义 | App 行为 |
|---:|---|---|
| 400 | 请求格式错误 | 不自动重试；记录服务端错误信息，检查消息、思维链或工具调用拼接 |
| 401 | API Key 错误 | 不重试；提示检查密钥 |
| 402 | 余额不足 | 不重试；提示充值或切换账号 |
| 422 | 参数错误 | 不重试；提示配置或版本不兼容 |
| 429 | 达到速率/并发限制 | 指数退避后有限重试，允许用户取消 |
| 500 | 服务端故障 | 短暂退避后有限重试 |
| 503 | 服务繁忙 | 短暂退避后有限重试 |

不要对 400、401、402、422 无限重试。网络断开、429、500、503 最多进行少量带抖动的退避重试。

官方链接：[错误码](https://api-docs.deepseek.com/zh-cn/quick_start/error_codes/)

#### 接入 Agent 工具

作用：告诉 Claude Code、GitHub Copilot、OpenCode 等外部 Agent 如何把 DeepSeek 作为后端。这不是 Android App 内调用 API 的必需部分。

本项目只有在开发者想让编程助手使用 DeepSeek 时才需要此节；App 内 AI 功能仍走 Kotlin + OkHttp。

### 2.2 API 指南

#### 思考模式

作用：控制是否返回推理过程，以及推理强度。

OpenAI 兼容请求字段：

```json
{
  "thinking": { "type": "enabled" },
  "reasoning_effort": "high"
}
```

规则：

- `thinking.type` 可为 `enabled` 或 `disabled`，默认 `enabled`。
- `reasoning_effort` 正式值为 `low`、`high` 或 `max`。
- 思考模式下，`temperature`、`top_p`、`presence_penalty`、`frequency_penalty` 不生效；为兼容性可能不会报错。
- 思考内容位于 `reasoning_content`，最终回答位于 `content`，UI 必须分别处理。
- 普通多轮对话没有工具调用时，历史思维链不必回传。
- 思考模式配合工具调用时，相关 assistant 消息的 `reasoning_content` 必须完整回传，否则可能返回 400。

对 Legado 的建议：普通阅读辅助默认开启思考，但 UI 可折叠展示 `reasoning_content`；快速翻译等任务允许关闭思考。

官方链接：[思考模式](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/)

#### 多轮对话

作用：说明对话历史如何维护。

DeepSeek `/chat/completions` 是无状态接口。服务器不会替 App 保存会话；每次请求都必须把需要的历史消息放进 `messages`。

基本顺序：

```text
system → user → assistant → user → assistant ...
```

开发时要自己负责：

- 保存或临时维护消息历史。
- 在超出上下文前裁剪旧消息或做摘要。
- 保留 system 消息和当前任务必需的上下文。
- 不要只发送本轮用户输入然后期待服务端记得上一轮。

官方链接：[多轮对话](https://api-docs.deepseek.com/zh-cn/guides/multi_round_chat/)

#### 对话前缀续写（Beta）

作用：指定 assistant 回答的开头，让模型从该前缀继续写，适合限定代码块或固定格式开头。

要求：

- Base URL 改为 `https://api.deepseek.com/beta`。
- 最后一条消息必须是 `assistant`。
- 最后一条 assistant 消息设置 `prefix: true`。

首期阅读助手不需要此功能。Beta 功能应隔离在独立配置或实验开关中，不能影响普通对话 Base URL。

官方链接：[对话前缀续写](https://api-docs.deepseek.com/zh-cn/guides/chat_prefix_completion/)

#### FIM 补全（Beta）

作用：根据 `prompt` 前缀和可选 `suffix` 后缀补全中间内容，主要用于代码或文本补全。

要求：

- Base URL：`https://api.deepseek.com/beta`。
- 终点：`POST /completions`，不是 `/chat/completions`。
- 官方指南注明最大补全长度 4K。
- 该能力不是聊天接口，响应正文在 `choices[].text`。

阅读 App 首期不需要 FIM；以后做“续写/补全文本”时再接入。

官方链接：[FIM 指南](https://api-docs.deepseek.com/zh-cn/guides/fim_completion/)；[FIM 接口](https://api-docs.deepseek.com/zh-cn/api/create-completion/)

#### JSON Output

作用：要求模型输出可被程序解析的合法 JSON，适合摘要结果、实体提取、章节标签和结构化操作。

请求字段：

```json
"response_format": { "type": "json_object" }
```

注意：

- system 或 user 提示词必须明确包含“json”，并给出期望结构或示例。
- `max_tokens` 要足够，否则 JSON 可能被截断。
- 必须检查 `finish_reason == "length"`。
- 官方说明存在偶发空 `content` 的可能，调用方要校验并允许有限重试。
- 即使是 JSON Output，也要对字段、类型和业务范围做二次校验。

官方链接：[JSON Output](https://api-docs.deepseek.com/zh-cn/guides/json_mode/)

#### Tool Calls

作用：让模型决定调用 App 提供的函数。模型只产生函数名与参数，真正的函数必须由 App 执行。

标准流程：

1. App 在 `tools` 中声明函数及 JSON Schema。
2. 模型返回 `tool_calls`。
3. App 校验函数名和参数，执行白名单函数。
4. App 把结果作为 `role=tool`、带对应 `tool_call_id` 的消息加入历史。
5. 再请求模型生成最终回答，直到没有新的工具调用。

安全要求：

- 永远不要直接执行模型返回的任意代码、Shell、URL 或文件路径。
- 工具名必须匹配本地白名单。
- 参数需要 Schema 和业务范围双重验证。
- 写文件、删除数据、购买等操作必须二次确认。
- 工具循环必须设置最大轮数。

`strict` 模式属于 Beta，需使用 `/beta` Base URL、设置 `strict: true`，并遵守官方支持的 JSON Schema 子集。

官方链接：[Tool Calls](https://api-docs.deepseek.com/zh-cn/guides/tool_calls/)

#### 上下文硬盘缓存

作用：复用相同请求前缀，降低输入费用并改善延迟。该能力默认开启，不需额外 API 参数。

开发建议：

- system prompt 和稳定的长文本放在消息前部。
- 多轮对话追加消息，不要每轮重新排列已有消息。
- 不要为了“优化”而把稳定前缀插入随机 ID 或时间戳。
- 通过 `usage.prompt_cache_hit_tokens` 与 `prompt_cache_miss_tokens` 观察效果。
- 缓存是尽力而为，不保证每次命中，也不能代替 App 自己保存会话。

官方链接：[上下文硬盘缓存](https://api-docs.deepseek.com/zh-cn/guides/kv_cache/)

#### Anthropic API

作用：兼容 Anthropic SDK 和生态，Base URL 为 `https://api.deepseek.com/anthropic`。

当前 Android App 不使用 Anthropic 格式，因为现有设置和请求设计均采用 OpenAI Chat Completions。只有未来需要复用 Anthropic 消息协议时才考虑切换，不能把两套请求 DTO 混用。

官方链接：[Anthropic API](https://api-docs.deepseek.com/zh-cn/guides/anthropic_api/)

### 2.3 API 文档

#### 基本信息与鉴权

所有请求通过 Bearer Token 鉴权：

```http
Authorization: Bearer <DEEPSEEK_API_KEY>
```

API Key 不应写进源码、日志、异常、截图、备份或 Git。官方基本信息：[DeepSeek API](https://api-docs.deepseek.com/zh-cn/api/deepseek-api/)。

#### 对话补全

作用：本项目最主要的接口。

```http
POST https://api.deepseek.com/chat/completions
Content-Type: application/json
Authorization: Bearer <API_KEY>
```

核心请求字段：

| 字段 | 必需 | 用途 |
|---|---|---|
| `model` | 是 | `deepseek-v4-pro` 或 `deepseek-v4-flash` |
| `messages` | 是 | 至少一条 system/user/assistant/tool 消息 |
| `thinking` | 否 | `{ "type": "enabled" }` 或 `disabled` |
| `reasoning_effort` | 否 | `low`、`high` 或 `max` |
| `max_tokens` | 否 | 限制最大输出；还受上下文总长度限制 |
| `stream` | 否 | `true` 使用 SSE |
| `stream_options` | 否 | 流式时可用 `include_usage` |
| `response_format` | 否 | `text` 或 `json_object` |
| `stop` | 否 | 一个字符串或最多 16 个字符串 |
| `tools` | 否 | 最多 128 个 function 定义 |
| `tool_choice` | 否 | `none`、`auto`、`required` 或指定函数 |
| `user_id` | 否 | 业务用户隔离标识，不得含隐私 |

非流式重点响应字段：

```text
choices[0].message.content
choices[0].message.reasoning_content
choices[0].message.tool_calls
choices[0].finish_reason
usage.*
```

`finish_reason` 需要完整处理：

- `stop`：正常完成。
- `length`：达到输出或上下文限制，内容可能不完整。
- `content_filter`：内容被过滤。
- `tool_calls`：需要执行工具并继续请求。
- `insufficient_system_resource`：服务端资源不足导致中断，可提示重试。

官方接口：[对话补全](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/)

#### 列出模型

```http
GET /models
```

作用：动态查看账号当前可用模型。首期可以使用本地受支持列表；以后可用此接口做“刷新模型”，但必须保留服务端返回未知字段的兼容性。

官方链接：[列出模型](https://api-docs.deepseek.com/zh-cn/api/list-models/)

#### 查询余额

```http
GET /user/balance
```

作用：显示账号是否可调用，以及人民币/美元的总余额、赠金和充值余额。它不是每次聊天前的必需请求，频繁查询只会增加延迟。

官方链接：[查询余额](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/)

### 2.4 新闻、其它资源、FAQ 与更新日志

- 新闻：理解模型发布背景，不作为稳定 API 契约。
- 其它资源：SDK、示例和社区项目入口；第三方代码必须审查后使用。
- FAQ：遇到计费、账号、输出行为等常见问题时查阅。
- 更新日志：每次开发/发布 AI 功能前检查，尤其关注模型名、弃用日期、参数和兼容性变化。

官方链接：[更新日志](https://api-docs.deepseek.com/zh-cn/updates/)

## 3. Legado 中应该怎么开发

### 3.1 当前已有基础

本项目已经具备：

- Kotlin 与 Android 生命周期。
- OkHttp 5：`app/src/main/java/io/legado/app/help/http/HttpHelper.kt`。
- 协程化 OkHttp、JSON POST：`app/src/main/java/io/legado/app/help/http/OkHttpUtils.kt`。
- Gson：`io.legado.app.utils.GSON`。
- `INTERNET` 权限。
- AI 设置页及 `PreferKey.aiApiBaseUrl`、`aiApiKey`、`aiModel`、`aiThinking`。

无需新增 Retrofit、OpenAI Java SDK 或 SSE 库。OkHttp 的 `ResponseBody.source()` 足以解析 SSE。

### 3.2 推荐目录与职责

```text
ui/ai/
├── AiFeatureActivity.kt       # 页面容器
├── AiConfigFragment.kt        # API 配置
├── AiChatFragment.kt          # 对话 UI（后续）
└── AiViewModel.kt             # UI 状态、发送、取消、重试

model/ai/
├── DeepSeekClient.kt          # HTTP、SSE、错误映射
├── DeepSeekModels.kt          # 请求/响应 DTO
├── AiRepository.kt            # 会话、上下文裁剪、业务用例
└── AiPrompt.kt                # 稳定的 system prompt
```

依赖方向：

```text
Activity/Fragment → AiViewModel → AiRepository → DeepSeekClient → OkHttp
```

UI 不直接拼 JSON，不直接访问 API Key，也不直接持有 OkHttp `Call`。

### 3.3 建议的数据模型

字段名要与 API JSON 一致。可以使用 Gson 的 `@SerializedName` 将 Kotlin 驼峰字段映射为下划线字段。

```kotlin
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val thinking: Thinking? = null,
    @SerializedName("reasoning_effort")
    val reasoningEffort: String? = null,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = true,
    @SerializedName("stream_options")
    val streamOptions: StreamOptions? = StreamOptions(includeUsage = true)
)

data class ChatMessage(
    val role: String,
    val content: String?,
    @SerializedName("reasoning_content")
    val reasoningContent: String? = null,
    @SerializedName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id")
    val toolCallId: String? = null
)

data class Thinking(val type: String)

data class StreamOptions(
    @SerializedName("include_usage")
    val includeUsage: Boolean
)
```

不要一开始就把所有 OpenAI 兼容字段都加入业务层。先实现本 App 真正使用的字段，再按功能扩充。

### 3.4 最小非流式请求

```json
{
  "model": "deepseek-v4-pro",
  "messages": [
    { "role": "system", "content": "你是阅读应用中的助手。" },
    { "role": "user", "content": "概括这一段内容。" }
  ],
  "thinking": { "type": "enabled" },
  "reasoning_effort": "high",
  "stream": false
}
```

当前非流式请求仍用于验证配置、鉴权、DTO 与错误处理；小说净化已接入下节的 SSE 解析。

### 3.5 OkHttp 请求约定

请求构造要点：

```kotlin
val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
val json = GSON.toJson(request)

okHttpClient.newCallResponse {
    url(endpoint)
    header("Authorization", "Bearer $apiKey")
    header("Accept", if (request.stream) "text/event-stream" else "application/json")
    postJson(json)
}
```

必须做到：

- `baseUrl.trimEnd('/')`，避免双斜杠。
- API Key 为空时在发请求前报错。
- 只允许 `https`，开发调试若允许 HTTP 必须显式提示风险。
- 不记录 Authorization 请求头和完整请求对象。
- 用 `response.use {}` 确保连接释放。
- 非 2xx 先读取错误体，再转换为统一异常。
- ViewModel 清理或用户点击停止时取消 OkHttp Call。

### 3.6 SSE 流式解析

官方流式协议是 `text/event-stream`：

```text
data: {JSON chunk}

data: {JSON chunk}

data: [DONE]
```

解析规则：

1. 按行读取，不要一次 `body.string()` 把整个流读完。
2. 忽略空行。
3. 忽略以 `:` 开头的 SSE 注释，包括 `: keep-alive`。
4. 只处理以 `data:` 开头的行，并去掉前缀及前导空格。
5. 数据等于 `[DONE]` 时正常结束。
6. JSON chunk 的 `delta.reasoning_content` 追加到“思考内容”。
7. `delta.content` 追加到“最终回答”。
8. 最后一个 usage chunk 可能 `choices=[]`，不能假设 `choices[0]` 永远存在。
9. 保存最后的 `finish_reason` 并转换为完成、截断、过滤、工具调用或资源不足状态。
10. 取消协程时关闭 body 并取消 Call。

小说净化只使用同一整章请求的 `delta.content`，忽略 `delta.reasoning_content`。累计正文可提前在内存中节流显示；收到 `[DONE]` 时正常结束，对于兼容服务在明确返回 `finish_reason` 后直接关闭连接的情况也接受为完整流。只有 `finish_reason=stop` 且全文校验通过后才写入缓存。

适合暴露给 ViewModel 的事件：

```kotlin
sealed interface AiStreamEvent {
    data class ReasoningDelta(val text: String) : AiStreamEvent
    data class ContentDelta(val text: String) : AiStreamEvent
    data class UsageUpdate(val usage: Usage) : AiStreamEvent
    data class Finished(val reason: String?) : AiStreamEvent
}
```

不要让 RecyclerView 每收到一个 token 就完整刷新整条消息。应在内存中累积，并以合适的节奏合并更新 UI。

### 3.7 会话与上下文

最简单的会话状态：

```text
system message
user message 1
assistant message 1
user message 2
assistant message 2
```

上下文过长时：

1. 保留 system prompt。
2. 保留当前问题和最近若干轮。
3. 优先丢弃与当前任务无关的旧轮次。
4. 需要长期语境时，将旧历史总结成一条稳定摘要。
5. UI 明确告诉用户旧上下文是否已被压缩。

与书籍内容结合时，不要默认把整本书上传。优先发送用户选择的段落、当前章节或必要摘要，兼顾隐私、费用和上下文。

### 3.8 API Key 安全

当前设置页使用普通 SharedPreferences，只适合个人开发阶段。正式发布前建议：

- 用 Android Keystore 支持的加密存储保护 API Key。
- 备份逻辑明确排除 AI Key。
- 日志、崩溃报告和调试信息永不输出 Key。
- UI 仅展示固定长度掩码，不展示实际长度。
- 提供“清除 API Key”。
- 不把个人 Key 打包进 APK；公开 App 更安全的方案是自有后端代理鉴权，但这会引入服务器成本、滥用防护和隐私责任。

### 3.9 UI 状态

ViewModel 至少维护：

```text
Idle
Sending
Streaming(reasoning, content)
Completed(content, usage, finishReason)
Failed(userMessage, retryable)
Cancelled
```

用户操作：发送、停止、重新生成、复制、清空会话。请求进行时要防止重复点击产生无意并发。

## 4. 推荐开发阶段

### 阶段一：连接测试

- 校验 Base URL、API Key 和模型。
- 非流式发送一条最小消息。
- 正确展示 200、401、402、422、429、500、503。
- 可选接入 `/models` 和 `/user/balance` 做“测试连接”。

### 阶段二：流式对话

- SSE 逐行解析。
- 分开展示思考和回答。
- 支持停止生成和生命周期取消。
- 展示 `finish_reason` 与 token 用量。

### 阶段三：阅读场景

- 选中文字后解释、翻译、摘要。
- 当前章节摘要。
- 基于当前段落提问。
- 明确显示将要发送给 API 的文本范围。

### 阶段四：结构化和工具能力

- JSON Output 生成标签、角色、地点等结构。
- Tool Calls 仅开放经过审核的只读工具。
- 所有有副作用工具增加确认和审计。

## 5. 测试清单

### 配置

- Base URL 有/无尾斜杠都能工作。
- 空 Key 在本地拦截。
- 错误 Key 显示 401，不泄露 Key。
- 两个模型均可切换。
- 思考开关正确改变请求 JSON。

### 非流式

- 解析普通 `content`。
- 解析 `reasoning_content`。
- `choices` 为空或字段缺失时不崩溃。
- `finish_reason=length/content_filter/insufficient_system_resource` 有明确提示。

### 流式

- 能忽略空行和 `: keep-alive`。
- 能处理 `[DONE]`。
- 能处理只有 reasoning、只有 content、两者交替出现的 chunk。
- 能处理 `include_usage` 产生的空 choices chunk。
- 断网、切后台、旋转屏幕和主动停止不泄漏连接。
- UTF-8 中文被拆在多个网络缓冲区时仍正常显示。

### 多轮与工具

- 第二轮请求包含需要的历史消息。
- 普通对话不依赖服务端记忆。
- 思考模式工具调用完整回传 assistant 的 reasoning/tool_calls。
- 非白名单工具、非法参数和超出轮数的工具循环被拒绝。

### 安全

- Git、日志、备份、异常信息无 API Key。
- 用户知道哪些书籍文本将发送到第三方服务。
- 清除配置后 Key 不可继续使用。

## 6. API 快速索引

| 需求 | 方法与路径 | 文档 |
|---|---|---|
| 普通/流式聊天 | `POST /chat/completions` | [对话补全](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/) |
| FIM 补全（Beta） | `POST /beta/completions`（以 Beta Base URL 组合） | [FIM 补全](https://api-docs.deepseek.com/zh-cn/api/create-completion/) |
| 列出模型 | `GET /models` | [列出模型](https://api-docs.deepseek.com/zh-cn/api/list-models/) |
| 查询余额 | `GET /user/balance` | [查询余额](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/) |
| 思考参数 | `thinking`、`reasoning_effort` | [思考模式](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/) |
| 结构化 JSON | `response_format` | [JSON Output](https://api-docs.deepseek.com/zh-cn/guides/json_mode/) |
| 函数工具 | `tools`、`tool_choice` | [Tool Calls](https://api-docs.deepseek.com/zh-cn/guides/tool_calls/) |
| 缓存用量 | `usage.prompt_cache_*` | [上下文硬盘缓存](https://api-docs.deepseek.com/zh-cn/guides/kv_cache/) |
| 错误处理 | HTTP 状态码 | [错误码](https://api-docs.deepseek.com/zh-cn/quick_start/error_codes/) |
| 模型与价格 | 动态信息 | [模型与价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/) |
| 版本变化 | 弃用、新模型、能力更新 | [更新日志](https://api-docs.deepseek.com/zh-cn/updates/) |

## 7. 后续 AI 查阅本文件时的规则

后续实现或修改 DeepSeek 功能时：

1. 先读本文件的“先看结论”“Legado 中应该怎么开发”和对应功能章节。
2. 模型名、价格、限额、上下文长度、参数支持情况属于易变信息，必须再访问对应官方链接确认。
3. 接口 DTO 以[对话补全 API](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/)为准，指南用于理解流程，不能用旧示例覆盖新接口契约。
4. 保持本项目依赖方向：UI → ViewModel → Repository → Client → OkHttp。
5. 不引入新网络库，除非现有 OkHttp/Gson/协程确实无法满足需求并说明理由。
6. 任何日志、测试数据和提交内容都不得包含真实 API Key 或用户书籍正文。
7. 完成功能后同步更新本文件中的实现状态、文件路径和已知限制。
