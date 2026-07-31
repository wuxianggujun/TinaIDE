---
name: tina-ai-tools
description: TinaIDE RikkaHub/AI 集成开发指南。用于修改内嵌 RikkaHub 入口、侧边栏聊天容器、模型/渠道边界、API key 安全边界、embedded 编译或 AI 与编辑器宿主集成。
---

# TinaIDE RikkaHub / AI 集成

## 先读文件

- `settings.gradle.kts`：`external/rikkahub` included build 和 `rikkahub-embedded` 依赖替换。
- `app/build.gradle.kts`：主 APK 对 `me.rerere.rikkahub:rikkahub-embedded` 的依赖。
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/DrawerContent.kt`。
- `app/src/main/java/com/wuxianggujun/tinaide/settings/SettingsActivity.kt`。
- `external/rikkahub/embedded/**`。
- `external/rikkahub/app/src/main/java/me/rerere/rikkahub/RikkaHubEmbeddedChatPane.kt`：包含 `RikkaHubEmbeddedChatPane` 与 `RikkaHubEmbeddedSettingsPane`。
- `docs/开发指南.md` 的 RikkaHub 接入落点。
- `docs/架构概览.md` 的 RikkaHub 接入说明。
- `feature/help/src/main/assets/help/getting-started.md` 与 `known-issues.md`。

## 当前事实

- TinaIDE 已移除自研 `feature:ai`、聊天仓储、渠道仓储和工具调用系统。
- AI 聊天、模型、渠道、MCP、API Key、流式响应和停止生成由 RikkaHub 自身维护。
- TinaIDE 主仓库只负责 embedded library 依赖、侧边栏入口、设置入口、宿主生命周期和帮助文档。
- RikkaHub 源码位于 `external/rikkahub` 子模块；改动子模块时必须先提交并推送子模块，再提交主仓库 gitlink。
- 主仓库不要新增 API Key 镜像存储、日志输出、导出配置或崩溃附件。

## 修改流程

1. 先判断改动属于宿主入口还是 RikkaHub 内部能力。
2. 宿主入口改动优先检查 `DrawerContent`、`SettingsActivity` 和 embedded 依赖边界。
3. 聊天、模型、渠道、MCP、API Key、停止生成等能力应落在 `external/rikkahub`。
4. 用户可见文案如果位于 TinaIDE 主仓库，必须走 `core/i18n`；如果位于 RikkaHub 子模块，按 RikkaHub 自身资源规则维护。
5. 涉及帮助文档时同步检查 `feature/help/src/main/assets/help/*.md`。

## 高风险误区

- 不要恢复 `feature:ai` 或旧 `AiTool`、`ToolRegistry`、`AiChannelRepository` 链路。
- 不要在 TinaIDE 主仓库保存 RikkaHub API Key 副本。
- 不要把 RikkaHub 内部页面逻辑搬到 `app/`。
- 不要只提交主仓库 gitlink 而忘记推送 `external/rikkahub` 子模块提交。
- 不要把 RikkaHub Problems report 中已确认的 AGP 内部 deprecation warning 当作编译失败。

## 验证

```powershell
./gradlew :rikkahub:embedded:compileDebugKotlin --no-daemon --console=plain
./gradlew :app:compileArm64DebugKotlin --no-daemon --console=plain
```

- 只改主仓库文档时至少检查 `git diff`、路径是否真实存在、帮助资产是否同步。
- 改 RikkaHub 源码时优先运行 embedded compile；涉及主 APK 宿主入口时再跑 app compile。
