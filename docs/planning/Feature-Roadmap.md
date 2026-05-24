# TinaIDE 功能路线图

> 更新日期：2026-05-24

TinaIDE 是一个面向 Android 设备的轻量级 C/C++ IDE。本文档作为当前仓库“已实现功能总览 + 待实现规划”的主文档。

---

## 📊 项目状态概览（维护友好版）

为了避免“统计表”快速过期，这里用更可维护的方式概括当前状态：

- 已具备稳定闭环：编辑器（多标签 + Tree-sitter + clangd）、构建/运行/调试（原生工具链 + 可选 PRoot 模式）、终端、多语言 UI、Git 基础流、插件（主题/片段/菜单扩展/LSP/脚本与 hybrid/项目模板/APK 导出）。
- 开源版已经移除账号登录、第三方登录、激活码/许可证、会员和官方 AI 额度入口；私有后端与管理端不再随公开仓库分发，插件与依赖包索引改由公开 GitHub Registry 承载。
- 近期最值得优先补齐：分屏编辑体验完善（上下分屏、同文件双视图）、用户自定义项目模板、扩充内置 C/C++ 片段库、插件系统的快捷键/工具栏/设置页（书签基础能力已实现，后续以体验优化为主）。
- 体验完善（小缺口）：代码中仍有少量 UI 入口是明确 TODO（见下方“代码 TODO”清单）。

## 🟠 代码扫描发现的“未接入”小功能（TODO）

这些点在代码中以 `TODO` 明确标注，通常属于 **入口未接入 / 交互未完成**，适合穿插在日常迭代中快速补齐。

- [ ] 工具链配置页：帮助入口未实现（`feature/workspace/src/main/java/.../ui/workspace/ToolchainConfigActivity.kt`）
- [ ] 工具链配置页：服务协议链接未实现（`feature/workspace/src/main/java/.../ui/workspace/ToolchainConfigActivity.kt`）
- [ ] 工具链配置页：隐私政策链接未实现（`feature/workspace/src/main/java/.../ui/workspace/ToolchainConfigActivity.kt`）
- [ ] 安装/引导页：帮助页面/联系支持入口未实现（`feature/workspace/src/main/java/.../ui/workspace/components/InstallContentComponents.kt`）
- [ ] 底部面板：Goto Line 交互未接入（`app/src/main/java/.../ui/compose/components/BottomPanel.kt`）
- [ ] 调试变量：点击变量展开详情未实现（`app/src/main/java/.../ui/compose/components/BottomPanel.kt`）
- [ ] Git 面板：切换到 Git 面板的入口待完善（`app/src/main/java/.../MainActivity.kt`）
- [ ] Git：提交详情展示未实现（`app/src/main/java/.../MainActivity.kt`）
- [ ] Git：提交信息"表情选择"未实现（`app/src/main/java/.../ui/compose/components/DrawerGitPanel.kt`）
- [ ] Git：提交信息"历史记录"未实现（`app/src/main/java/.../ui/compose/components/DrawerGitPanel.kt`）

---

## ✅ 已完成功能

### 编辑器核心

| 功能 | 说明 | 主要文件 |
|------|------|----------|
| 多标签编辑器 | Tina Editor + Tree-sitter 高亮 | `EditorContainer.kt`, `DocumentSession.kt` |
| 语法高亮 | C/C++/CMake/Bash/JSON/Make/YAML | [tree-sitter-queries](../../app/src/main/assets/tree-sitter-queries/) |
| 代码折叠 | 行号区域折叠图标、快捷键支持 | `FoldingManager.java` |
| 代码格式化 | clang-format 集成 | `CodeFormatter.kt` |
| 头文件跳转 | include 路径解析和跳转 | `HeaderNavigationTextAction.kt` |
| 全局搜索 | 跨文件搜索、正则表达式 | `ProjectSearchEngine.kt`, `GlobalSearchScreen.kt` |
| LSP 集成 | clangd（native / PRoot / remote）+ CMake/Make 内建语言服务 | `LspEditorManager.kt`, `LspClientSession.kt` |
| **智能补全增强** | 枚举/类成员补全、参数提示、模糊搜索 | [`CxxCompletionEngine.kt`](../../feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/completion/cxx/CxxCompletionEngine.kt), [`ProjectSymbolIndexService.kt`](../../feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/symbol/ProjectSymbolIndexService.kt) |
| **参数提示** | 函数签名、当前参数高亮 | `CxxSignatureHelpProvider.kt`, `LocalSignatureHelpWindow.kt` |
| **插件主题** | 通过插件自定义编辑器主题（60+ 颜色项） | [`ConfigEditorColorScheme.kt`](../../feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/theme/ConfigEditorColorScheme.kt), [`PluginEditorThemeRegistry.kt`](../../feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/theme/PluginEditorThemeRegistry.kt) |
| **代码片段** | 插件系统实现，支持占位符和跳转 | [`PluginSnippetManager.kt`](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginSnippetManager.kt) |
| 符号面板 | 底部面板符号 Tab，前缀搜索和跳转 | `SymbolsContent.kt` |

### 构建与调试

| 功能 | 说明 | 主要文件 |
|------|------|----------|
| 原生 / PRoot 编译 | `CompileProjectUseCase` 统一调度 native toolchain 与可选 PRoot 环境 | `CompileProjectUseCase.kt`, `PRootEnvironment.kt` |
| CMake 构建 | 项目检测、配置、构建 | `CMakeBuildExecutor.kt`, `BuildSystemDetector.kt` |
| 运行配置 | 命令行参数、工作目录、终端联动 | `RunConfiguration.kt`, `RunConfigDialog.kt` |
| LLDB 调试 | 断点、单步、变量查看、调用栈 | `DebugSessionService.kt`, `PRootDebugger.kt` |
| 终端 | 多终端、状态持久化、Bash + Zsh | `TerminalSessionManager.kt`, `ZshInstaller.kt` |
| 构建日志 | 底部面板构建输出 | `BuildOutputContent.kt` |
| **代码格式化** | clang-format 集成，支持自定义配置 | [`CodeFormatter.kt`](../../core/compile/src/main/java/com/wuxianggujun/tinaide/core/format/CodeFormatter.kt), [`ClangFormatConfigManager.kt`](../../core/compile/src/main/java/com/wuxianggujun/tinaide/core/format/ClangFormatConfigManager.kt) |
| **PRoot 日志系统** | 会话日志记录、错误诊断、自动重试 | [`PRootSessionLogger.kt`](../../core/proot/src/main/java/com/wuxianggujun/tinaide/core/proot/PRootSessionLogger.kt), [`PRootLogActivity.kt`](../../feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/PRootLogActivity.kt) |

### Git 集成

| 功能 | 说明 | 主要文件 |
|------|------|----------|
| 本地操作 | status/diff/commit/log/checkout | `GitService.kt`, `GitViewModel.kt` |
| 文件树状态 | Git 状态标记（修改/新增/删除） | `FileTree.kt`, `FileTreeItem.kt` |
| 远程操作 | push/pull/fetch/clone | `GitService.kt`, `GitSyncDialog.kt` |
| 凭据管理 | HTTPS（Android Keystore 加密） | `AndroidGitCredentialManager.kt` |
| SSH 密钥 | 生成/导入，支持口令私钥 | `SshKeyManager.kt` |
| 冲突解决 | 合并/变基冲突处理 | `GitMergeConflictDialog.kt` |
| 远程仓库管理 | 添加/删除/修改 | `GitRemoteDialog.kt` |
| Git 日志 | 提交历史查看 | `GitLogPanel.kt` |

### 插件系统

| 功能 | 说明 | 主要文件 |
|------|------|----------|
| 插件管理器 | 安装/卸载/启用/禁用 | [`PluginManager.kt`](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginManager.kt) |
| 主题插件 | JSON 格式主题配置 | [`PluginEditorThemeRegistry.kt`](../../feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/theme/PluginEditorThemeRegistry.kt) |
| 代码片段插件 | JSON 格式片段配置 | [`PluginSnippetManager.kt`](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginSnippetManager.kt) |
| 文件树菜单扩展 | 右键菜单扩展 | [`PluginMenuResolver.kt`](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginMenuResolver.kt) |
| 编辑器菜单扩展 | Tab 长按菜单扩展 | [`PluginMenuResolver.kt`](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginMenuResolver.kt) |
| 脚本 / Hybrid 插件 | Lua 运行时、权限确认、日志与宿主 API 白名单 | [`ScriptPluginManager.kt`](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/ScriptPluginManager.kt) |
| 内置插件安装 | assets 内置插件自动安装 | `BundledPluginsInstaller.kt` |

### 其他功能

| 功能 | 说明 | 主要文件 |
|------|------|----------|
| 文件查看器 | JSON/Hex/图片/Markdown 预览 | `JsonViewerScreen.kt`, `MarkdownViewerScreen.kt` |
| 主题系统 | 亮色/暗色/灰色/跟随系统 | `TinaDarkColorScheme.kt`, `TinaLightColorScheme.kt` |
| 国际化 | 中文/英文双语 | `values/strings.xml`, `values-en/strings.xml` |
| 开源版身份策略 | 不内置账号登录、第三方登录、激活码/许可证或会员入口 | `ProfileScreen.kt`, `AiConfigStrategy.kt` |
| 项目模板 | 单文件、CMake 可执行/CMake 库、Make 可执行、NDK Shared Library；插件可追加 Zip 模板 | `ProjectTemplateInstaller.kt` |
| 文件树管理 | Compose UI + 上下文菜单 | `FileTree.kt`, `DrawerContent.kt` |
| 底部面板 | 构建日志、诊断、调试、符号 | `BottomPanel.kt` |
| 后端服务 | 私有后端与管理端已迁出公开仓库 | `server/README.md` |
| **服务器配置同步** | 客户端配置动态下发、后台定时同步 | [`ServerConfigManager.kt`](../../core/config/src/main/java/com/wuxianggujun/tinaide/core/config/ServerConfigManager.kt), `ServerConfigSyncWorker.kt` |
| **Android 15+ 兼容** | 16KB 页面对齐支持（rsync/tree-sitter） | `docker/rsync-build/`, `external/android-rsync/` |

### 开源版身份策略

| 功能 | 说明 | 主要文件 |
|------|------|----------|
| 账号与第三方登录 | 开源版不提供账号系统、第三方 SDK 或 OAuth 登录入口 | `ProfileScreen.kt`, `app/build.gradle.kts`, `AndroidManifest.xml` |
| 激活码系统 | 开源版不提供激活码/许可证激活入口，测试页和兼容壳已删除 | - |
| AI 使用方式 | 默认使用用户自带 API Key 的 BYOK 模式 | `AiConfigStrategy.kt`, `AiPreferences.kt` |
| 服务端能力 | 反馈、日志上传等 TinaServer 客户端能力保留；版本更新改为 GitHub Release 自动检查 | `core/network`, `feature/help` |
| 市场分发 | 插件与依赖包索引改为公开 GitHub Registry，继续保留本地安装、下载历史和插件系统 | `core/plugin`, `core/packages`, `docs/registry` |

### 开发者工具

| 功能 | 说明 | 主要文件 |
|------|------|----------|
| LSP 测试框架 | 内置 LSP 协议测试工具 | [`LspTestManager.kt`](../../core/lsp/src/main/java/com/wuxianggujun/tinaide/testing/lsp/LspTestManager.kt), [`ClangdTestScreen.kt`](../../app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/ClangdTestScreen.kt) |
| 开发者选项 | 设置中的调试功能入口 | [`DeveloperOptionsSection.kt`](../../feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/DeveloperOptionsSection.kt) |

---

## 🔴 高优先级（重点优化）

### 书签功能

**状态**：✅ 已完成（基础能力）

快速标记和跳转到重要代码位置。

**已实现**：
- [x] 书签数据模型
- [x] 书签管理器（BookmarkManager）
- [x] 行号区域书签渲染
- [x] 书签列表面板
- [x] 快捷键跳转（上一个/下一个）
- [x] 书签持久化（跨会话）
- [x] 书签备注

**技术要点**：
- 扩展 `BreakpointAwareCodeEditor`
- JSON 持久化存储
- 类似断点的视觉标记

---

## 🟡 中优先级待实现

### 分屏编辑

**工作量**：🟡 小到中（体验完善 3-5 天）
**用户价值**：⭐⭐⭐

同时查看多个文件或同一文件的不同位置。

**当前状态**：🟡 基础左右分栏已接入
- [x] 左右分屏容器（`EditorContainer`）
- [x] 顶部菜单启用 / 关闭分屏
- [x] 当前标签移动到副分栏
- [x] 不同文件分屏查看（非 Diff View）
- [x] 拖拽调整分屏比例
- [x] 分栏焦点、Tab 归属、关闭后的状态归一化
- [x] 状态层单元测试覆盖

**待完善**：
- [ ] 上下分屏布局
- [ ] 同一文件双视图 / 不同位置查看
- [ ] 分屏状态持久化（如确认符合用户预期）

---

### 用户自定义项目模板

**工作量**：🟢 小（3-5 天）
**用户价值**：⭐⭐⭐

快速创建符合个人习惯的项目结构。

**待实现**：
- [ ] 模板管理器（TemplateManager）
- [ ] 模板变量替换（${PROJECT_NAME}、${AUTHOR}）
- [ ] 模板编辑对话框
- [ ] 模板导入/导出

---

### 内置 C/C++ 代码片段

**工作量**：🟢 小（2-3 天）
**用户价值**：⭐⭐⭐

代码片段系统已通过插件实现，只需添加内置片段插件。

**当前状态**：✅ 已提供最小可用的内置片段插件（可继续扩充片段库）

- 插件位置：`app/src/main/assets/bundled_plugins/sample.snippets.cpp/`
- 插件 ID：`sample.snippets.cpp`

**已实现**：
- [x] 内置 C/C++ 常用片段（示例：`fori`, `main`）
- [x] 打包为内置插件（随 APK 发布，启动时自动安装/更新）

---

## 🟢 低优先级待实现

### 插件系统完善

**待实现**：
- [ ] 快捷键配置插件支持
- [ ] 编辑器工具栏扩展
- [ ] 插件设置页 / 配置 Schema
- [ ] 脚本插件 API / 权限 / 生命周期收敛

---

### 编辑器增强

**待实现**：
- [ ] 编辑器小地图（Minimap）
- [ ] 多光标编辑
- [ ] 列选择模式
- [ ] 文件对比（Diff View）
- [ ] Git 修改指示（Gutter）
- [ ] Git blame 信息

---

### 更多语言支持

**待实现**：
- [ ] Python 语法高亮 + 运行
- [ ] Rust 支持（如空间允许）
- [ ] JavaScript/TypeScript 语法高亮

---

### 云同步功能

**待实现**：
- [ ] 云备份 ZIP（手动触发）
- [ ] 项目同步
- [ ] 设置同步
- [ ] 云同步服务 API（私有后端或单独的最小公开服务另行设计）

---

## 🎯 推荐实施顺序

| 阶段 | 功能 | 工作量 | 状态 |
|------|------|--------|------|
| **第一阶段** | Git 远程操作 | 2 周 | ✅ 已完成 |
| | 智能补全增强 | 2 周 | ✅ 已完成 |
| | 编辑器主题自定义 | 1 周 | ✅ 已完成 |
| | 代码片段系统 | 1 周 | ✅ 已完成 |
| **第二阶段** | 书签功能 | 3-5 天 | ✅ 已完成 |
| | 分屏编辑体验完善 | 3-5 天 | 🟡 基础已接入 |
| | 用户自定义模板 | 3-5 天 | ⏳ 待实现 |
| **第三阶段** | 插件系统完善 | 2-3 周 | ⏳ 长期 |
| | 编辑器增强 | 1-2 周 | ⏳ 长期 |
| | 更多语言支持 | 视语言而定 | ⏳ 长期 |
| | 云同步功能 | 2-3 周 | ⏳ 长期 |

---

## 📚 相关文档

| 文档 | 说明 |
|------|------|
| [clangd-completion-fast-path.md](../clangd-completion-fast-path.md) | 本地候选优先 + LSP 异步追加 |
| [Branch-Management-Guide.md](../guides/Branch-Management-Guide.md) | Git 分支与远程协作流程 |
| [Plugin-Roadmap.md](../plugins/Plugin-Roadmap.md) | 插件系统阶段规划 |
| [Editor-Theme-Customization-Design.md](../design/Editor-Theme-Customization-Design.md) | 主题自定义 |

---

## 更新记录

| 日期 | 更新内容 |
|------|----------|
| 2026-05-21 | 同步开源版账号/激活移除口径；后端、管理端、账号/激活/会员源码迁出公开仓库 |
| 2026-04-22 | 同步当前插件、登录与构建现状；移除“微信登录 / QuickJS 未来阶段”等已过时描述 |
| 2026-02-25 | 文档收敛：清理过时设计/排障文档，统一以本文档作为功能总览入口 |
| 2026-01-26 | 新增已完成功能：服务器配置同步系统、Android 15+ 16KB 页面对齐支持、代码格式化增强、PRoot 日志系统 |
| 2026-01-12 | 历史记录：曾新增用户系统（本地认证、激活码）与开发者工具；用户系统现已从开源版移除 |
| 2026-01-11 | **重大更新**：合并 TODO.md、实施进度.md、功能扩展建议.md 到本文档；通过源码审查确认已完成功能 |

更早的历史版本记录已清理，不再在本文维护。
