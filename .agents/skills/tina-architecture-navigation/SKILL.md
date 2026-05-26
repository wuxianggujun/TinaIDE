---
name: tina-architecture-navigation
description: TinaIDE 项目架构导航。用于理解模块边界、启动入口、多进程初始化、主界面/编辑器数据流，或判断新代码应放在 app、core、feature、external、tools 还是 build-logic 中。
---

# TinaIDE 架构导航

## 先读文件

- `settings.gradle.kts`：模块清单、included build、外部源码替换。
- `docs/架构概览.md`、`docs/模块功能说明.md`：若文件存在，先以项目文档为入口。
- `app/src/main/AndroidManifest.xml`：Application、Activity、provider、权限和进程声明。
- `app/src/main/java/com/wuxianggujun/tinaide/TinaApplication.kt`：主进程、toolchain 进程、crash 进程、用户 native runtime 的初始化分流。
- `app/src/main/java/com/wuxianggujun/tinaide/ui/MainPortalActivity.kt`：启动入口。
- `app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt`：项目编辑器工作区入口。
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainScreen.kt`：首页 Tab 入口。
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityScreenHost.kt`：主编辑器界面装配。

## 模块边界

- `app/` 是启动、导航、DI 装配和跨模块协调层；不要把领域逻辑继续塞进 Activity 或 Host。
- `feature/*` 是用户可见功能切片，例如 AI、设置、工作区、编辑器相关功能。
- `core/*` 是无界面复用能力和运行时基础设施，例如 i18n、designsystem、storage、security、database、compile、lsp、plugin、tree-sitter。
- `external/*` 保持第三方源码或本地 fork 边界，改动前先确认是否为上游同步代码。
- `tools/*` 放模板、校验、构建辅助脚本；不要把运行时代码放到这里。
- `build-logic/` 放 Gradle convention plugin；已有 ABI 聚合、版本、R8、Tree-sitter、toolchain assets 等构建逻辑时优先复用。

## 判断新代码位置

1. 先用 `rg` 或 `ace-tool.search_context` 按意图搜索既有实现。
2. 用户可见页面、ViewModel、功能流程优先放到对应 `feature/*`。
3. 被多个 feature/app 共享且不依赖 UI 的能力放到 `core/*`。
4. 只负责跨模块装配、Activity 入口、全局 DI 或主界面协调时才改 `app/`。
5. 第三方源码、generated 文件、toolchain assets 只在对应维护流程中修改。

## 必须记住

- `MainPortalActivity` 是启动门户，`MainActivity` 是项目编辑器工作区。
- `TinaApplication` 有多进程分流；`:toolchain`、`:crash`、用户 native runtime 进程不能误初始化主进程服务。
- 默认编译/LSP 依赖 native tina-toolchain + Android sysroot；PRoot 是可选 Linux 环境，不是默认路径。
- 主界面没有全局统一 `NavHost`；首页是 `MainScreen` Tab + Activity/回调式导航。
- 新增用户可见文案必须走 `core/i18n` 资源和封装。

## 高风险误区

- 不要把 `feature/*` 的业务逻辑上移到 `app/`。
- 不要在多进程 Application 分支外初始化日志、崩溃、Tree-sitter、Koin 等主进程组件。
- 不要手改生成的 Tree-sitter registry 或把手动同步任务接入常规 build。
- 不要绕过 `ProjectPaths` 自行拼接项目、日志、PRoot、模板等持久化路径。

## 验证

- 结构调整后运行目标模块编译，例如 `./gradlew :app:compileArm64DebugKotlin --console=plain`。
- 涉及模块边界时检查 `settings.gradle.kts` 和对应 `build.gradle.kts` 依赖方向。
- 涉及主界面时优先跑相关 `src/test` 中的 Activity、navigation、settings 或 editor tests。
