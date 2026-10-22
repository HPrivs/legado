# Alibaba Cloud Qwen API 开发说明

> 最近核对：2026-08-27  
> 用途：记录 Legado 当前阿里云百炼 Qwen 接入所依赖的协议、模型与维护边界。  
> 官方入口：[首次调用千问 API](https://help.aliyun.com/zh/model-studio/first-api-call-to-qwen)、[OpenAI 兼容 Chat Completions](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)、[深度思考](https://help.aliyun.com/zh/model-studio/deep-thinking)、[Base URL 总览](https://help.aliyun.com/zh/model-studio/base-url)。

## 1. 当前接入范围

Legado 只接入百炼的 OpenAI 兼容 Chat Completions 文本调用：连接测试和短对话使用非流式 JSON，小说净化使用整章单请求 SSE。当前不接入 Responses API、Anthropic API、内置工具、联网搜索或多模态输入。

默认配置使用华北 2（北京）的兼容域名：

```text
Base URL: https://dashscope.aliyuncs.com/compatible-mode/v1
Endpoint:  https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

设置页允许编辑 Base URL。需要业务空间专属域名或其他地域时，应填写控制台提供的完整兼容 Base URL；API Key 必须与地域、业务空间和套餐匹配。

## 2. 当前 Qwen 3.8 模型

| 模型 ID | 定位 | 上下文 | 最大输出 |
|---|---|---:|---:|
| `qwen3.8-max` | 旗舰模型；App 默认 | 1M | 131072 |
| `qwen3.8-flash` | 高速、低成本 | 1M | 131072 |
| `qwen3.8-2.4t-a95b` | 开源 MoE 旗舰 | 1M | 131072 |
| `qwen3.8-27b` | 开源 Dense 模型 | 1M | 131072 |

模型、地域可用性、价格和限额会变化，发布前必须重新核对官方模型页和控制台。

## 3. 请求字段

Qwen 通过 `AiProvider.QWEN` 发送：

```json
{
  "model": "qwen3.8-max",
  "messages": [],
  "enable_thinking": true,
  "thinking_budget": 4096,
  "max_completion_tokens": 8192,
  "stream": false
}
```

- 思考开关使用顶层 `enable_thinking`，不发送 DeepSeek/MiMo 使用的 `thinking` 对象。
- 开启思考时固定发送 `thinking_budget=4096`，为小说净化保留可预测的低档推理预算；不同时发送 `reasoning_effort`。
- 输出预算使用 `max_completion_tokens`，不与 `max_tokens` 同时发送。
- `reasoning_content` 与 `content` 已由通用响应 DTO 和 SSE 解析器分别处理；思考内容不写入净化正文。

## 4. 连接测试与配置隔离

Qwen 连接测试发送一个关闭思考、最多 32 Token 的最小对话，以同时验证 Base URL、API Key 和所选模型。Qwen 与 DeepSeek、MiMo 分别保存 Base URL、API Key 和模型；服务商 ID 会进入净化缓存签名，API Key 不进入缓存键或日志。

## 5. 代码定位与维护规则

| 作用 | 文件 |
|---|---|
| 服务商、默认值、字段分派 | `app/src/main/java/io/legado/app/model/ai/client/AiProvider.kt` |
| 通用 Chat/SSE 客户端与 DTO | `DeepSeekClient.kt`、`DeepSeekModels.kt` |
| 设置与模型列表 | `pref_ai.xml`、`ai_arrays.xml`、`AiConfigFragment.kt` |
| 1M/131072 上下文保护 | `NovelPurificationContextGuard.kt` |
| 请求字段回归测试 | `DeepSeekClientTest.kt`、`NovelPurificationServiceTest.kt` |

维护时必须同步更新模型数组、上下文限制和测试；不得把套餐专属 Base URL 或 API Key 写死到代码，也不得在失败后自动产生付费重试。
