---
name: tina-ai-tools
description: TinaIDE feature:ai 工具、渠道、BYOK、流式响应和停止生成开发指南。用于新增/修改 AI 工具、工具参数、危险操作确认、工具 i18n、渠道配置、API key 存储、streaming 或 AI 与编辑器/文件系统集成。
---

# TinaIDE AI 工具与渠道

## 先读文件

- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/di/AiModule.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/viewmodel/AiChatViewModel.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/AiTool.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/ToolInitializer.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/ToolRegistry.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/ToolExecutionCoordinator.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/ToolParameterParser.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/ToolI18n.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/repository/ConversationRepository.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/channel/AiChannelRepository.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/channel/AiChannelApiKeyStore.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/api/AiApiClient.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/stream/ChatStreamController.kt`。
- `app/src/main/java/com/wuxianggujun/tinaide/ai/integration/AiToolsIntegrationManager.kt`。

## 工具链路

- Koin `aiModule` 初始化配置、渠道、会话仓库和工具注册。
- 内置工具注册路径：`ToolInitializer -> ToolRegistry`。
- 执行路径：`ToolExecutionCoordinator -> AiTool.execute()`。
- `AiChatViewModel` 编排会话、流式响应、工具调用和 UI 状态，不要把工具查找分支散落到 UI。
- 需要 app 能力时通过 callbacks 注入，参考 `AiToolsIntegrationManager`。

## 新增或修改工具

1. 实现或修改 `AiTool`。
2. 参数解析统一使用 `ToolParameterParser`，不要手写重复 JSON 转换。
3. 在 `ToolInitializer.registerBuiltInTools()` 注册。
4. 工具名、分类、状态、错误、参数说明走 `ToolI18n` 与 `core/i18n` 字符串资源。
5. 高危工具必须设置 `isDangerous`，必要时实现 `getDangerousConfirmation()`。
6. 补测试：registry、parser、execution、i18n、危险工具行为。
7. 执行/构建类工具优先走 callbacks，最终复用 `CompileProjectUseCase`、`ProcessManager`、`RunConfigurationManager`，不要在工具中直接裸跑命令。

## 渠道与 API key

- `AiChannelConfig` 不保存 API key。
- BYOK 密钥通过 `AiChannelApiKeyStore` / `AiChannelRepository.getApiKey()` 获取。
- API key、baseUrl、model 等输入进入持久化前要 `trim()`。
- `CUSTOM_BYOK` 是开源客户端实际可用路径；不要假设 `TINA_GATEWAY` 已可直接构建客户端。

## 流式与停止生成

- `AiApiClient` 使用 `OkHttpClientProvider.longConnection` 和 OpenAI-compatible SSE 解析链路。
- `ChatStreamController` 管理 content、reasoning、toolCalls、usage 状态。
- 停止生成由 `AiChatViewModel.stopGeneration` 控制。
- 部分流式消息保存依赖 `ChatStreamController.snapshotPartialMessage()`。

## 高风险误区

- 不要把明文 API key 放进普通 config、日志、测试 snapshot 或错误提示。
- 不要绕过 `ToolExecutionCoordinator` 自行执行工具。
- 不要新增用户可见工具文案但漏掉 `values-en`。
- 不要把高危文件/终端/构建操作标成普通工具。

## 验证

```powershell
./gradlew :feature:ai:testDebugUnitTest --console=plain
./gradlew :app:compileArm64DebugKotlin --console=plain
```

- 只改工具 schema/i18n 时至少跑 `feature:ai` 对应单测。
- 涉及 app callbacks 时补跑 app 编译或相关集成测试。
