# Xiaomi MiMo API 开发说明

> 最近核对：2026-08-22  
> 用途：记录 Legado 当前 MiMo 接入所依赖的官方协议、代码映射和维护边界。  
> 官方入口：[首次调用 API](https://mimo.mi.com/docs/zh-CN/quick-start/summary/first-api-call)、[模型列表](https://mimo.mi.com/docs/zh-CN/quick-start/summary/model)、[OpenAI Chat Completions](https://mimo.mi.com/docs/zh-CN/api/chat/openai-api)、[深度思考](https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/text-generation/deep-thinking)、[速率限制](https://mimo.mi.com/docs/zh-CN/api/guidance/rate-limit)、[错误码](https://mimo.mi.com/docs/zh-CN/api/guidance/error-codes)。

## 1. 当前接入范围

Legado 只接入 MiMo 的 OpenAI 兼容 Chat Completions 文本接口：连接测试和短对话使用非流式 JSON，小说净化使用整章单请求 SSE 流式输出。不接入 Responses API、Anthropic API、工具调用、联网搜索、多模态、ASR 或 TTS。

按量付费默认配置：

```text
Base URL: https://api.xiaomimimo.com/v1
Endpoint:  https://api.xiaomimimo.com/v1/chat/completions
API Key:   sk-xxxxx
```

Token Plan 使用不同凭证和 Base URL：

```text
Base URL: https://token-plan-cn.xiaomimimo.com/v1
API Key:  tp-xxxxx
```

设置页允许用户编辑 Base URL，因此两种方式不需要增加第二套协议代码。客户端统一发送官方支持的：

```http
Authorization: Bearer <API_KEY>
Content-Type: application/json
```

## 2. 当前文本模型

| 模型 | 用途 | 上下文 | 最大输出 | RPM | TPM |
|---|---|---:|---:|---:|---:|
| `mimo-v2.5-pro` | 复杂推理、深度分析、长文档处理；App 默认 | 1M | 128K | 100 | 10M |
| `mimo-v2.5` | 通用文本生成和全模态理解；App 当前只传文本 | 1M | 128K | 100 | 10M |

`mimo-v2-pro`、`mimo-v2-omni`、`mimo-v2-flash` 等 V2 模型已于 2026-06-30 下线，不得重新加入候选列表。

## 3. 与 DeepSeek 请求的差异

两家都使用 `POST <Base URL>/chat/completions`、Bearer 鉴权、`messages`、`thinking.type` 和相近的 JSON/SSE 响应结构，并都支持 `stream=false/true`；但输出预算字段不同：

| 服务商 | 输出预算字段 | 思考强度字段 |
|---|---|---|
| DeepSeek | `max_tokens` | 思考开启时发送 `reasoning_effort=high` |
| Xiaomi MiMo | `max_completion_tokens` | 当前官方 MiMo Chat 示例未定义 `reasoning_effort`，App 不发送 |

这两个输出预算字段不能同时发送。`AiProvider.createChatRequest` 是唯一的服务商字段分派点；业务层不能自行拼接供应商 JSON。

MiMo 非思考、非流式短对话请求示意：

```json
{
  "model": "mimo-v2.5-pro",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "name": "novel_text", "content": "..."}
  ],
  "thinking": {"type": "disabled"},
  "max_completion_tokens": 8192,
  "stream": false
}
```

小说净化使用相同字段映射，但设置 `stream=true`，并在同一请求中按 SSE `delta.content` 累计整章正文。

## 4. 思考模式

`mimo-v2.5-pro` 和 `mimo-v2.5` 均支持：

```json
{"thinking": {"type": "enabled"}}
```

官方默认开启。Legado 始终显式发送 `enabled` 或 `disabled`，使用设置页现有“思考模式”开关。小说净化是单轮、无工具请求，不需要回传历史 `reasoning_content`。

## 5. 连接测试

DeepSeek 的连接测试继续使用 `GET /models`。MiMo 当前接入只依赖官方明确文档化的 Chat Completions，因此连接测试发送一个 `thinking=disabled`、输出上限 32 token 的最小对话请求。该测试会产生极少量 token 用量，但能同时验证 Base URL、API Key、模型和真实对话端点。

测试连接不会自动重试 HTTP 429。MiMo 官方说明文本模型当前为 100 RPM、10M TPM；App 阅读任务仍使用已有单请求串行队列，避免并发放大费用和限流风险。

## 6. 错误处理

| HTTP | 含义 | App 行为 |
|---:|---|---|
| 400 | 请求格式或模型错误 | 显示请求错误，不自动重试 |
| 401 | API Key 或认证方式错误 | 显示认证失败 |
| 402 | 余额不足 | 显示余额不足 |
| 403 | 地区限制或风控 | 显示 HTTP 错误及官方详情 |
| 421 | 内容拦截 | 保留原文，显示 HTTP 错误 |
| 429 | 频率或套餐额度超限 | 显示限流，不自动付费重试 |
| 500 / 503 | 服务端故障 | 保留原文，交由用户决定是否重做 |

虽然官方建议高并发业务对 429 采用指数退避，小说净化仍遵守本项目“失败不自动重复请求”的费用边界。

## 7. 代码定位

| 作用 | 文件/符号 |
|---|---|
| 服务商、默认值、配置读取、请求字段分派 | `model/ai/client/AiProvider.kt` |
| OpenAI 兼容 HTTPS 客户端 | `model/ai/client/DeepSeekClient.kt` 中的 `OpenAiCompatibleClient` |
| MiMo 最小对话连接测试 | `model/ai/client/AiConnectionTester.kt` |
| 通用请求/响应 DTO | `model/ai/client/DeepSeekModels.kt`；文件名是早期单服务商遗留，结构已由三家共用 |
| 设置和服务商界面切换 | `ui/ai/AiConfigFragment.kt`、`res/xml/pref_ai.xml` |
| 模型和服务商候选 | `res/values/ai_arrays.xml` |
| 小说净化配置及缓存签名 | `model/ai/purify/NovelPurificationRuntimeConfig.kt` |
| 服务商输出预算字段应用 | `model/ai/purify/NovelPurificationService.kt` |
| MiMo 1M/128K 上下文保护 | `model/ai/purify/NovelPurificationContextGuard.kt` |

DeepSeek、MiMo 与 Qwen 分别保存 Base URL、API Key 和模型。当前服务商 ID 也进入 AI 缓存签名；切换服务商后不会错误复用另一家生成的章节结果。API Key 仍不进入缓存键、日志或异常文本。

## 8. 维护规则

1. 只从 Xiaomi MiMo 官方文档更新模型 ID、长度和字段。
2. 新增 MiMo 模型时同步修改 `ai_arrays.xml`、`NovelPurificationContextGuard` 和测试。
3. 不把 V2 已下线模型重新加入候选。
4. 不向 MiMo 请求发送 DeepSeek 专用 `reasoning_effort`。
5. 不同时发送 `max_tokens` 与 `max_completion_tokens`。
6. 修改服务商、模型、思考状态、Base URL 或提示词后必须使缓存签名变化。
7. 不因 429、500、503 或校验失败自动重复小说净化请求。
