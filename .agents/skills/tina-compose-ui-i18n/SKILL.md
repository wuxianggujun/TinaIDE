---
name: tina-compose-ui-i18n
description: TinaIDE Compose UI、主题、导航和国际化开发指南。用于新增或修改用户界面、Activity setContent、设置页路由、主界面 Tab、底栏/抽屉/弹窗、用户可见文案和多语言资源。
---

# TinaIDE Compose UI 与国际化

## 先读文件

- `core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/theme/TinaIDETheme.kt`。
- `core/i18n/src/main/java/com/wuxianggujun/tinaide/core/i18n/AppStrings.kt`。
- `core/i18n/src/main/java/com/wuxianggujun/tinaide/core/i18n/ResExt.kt`。
- `core/i18n/src/main/java/com/wuxianggujun/tinaide/core/i18n/TextResourceAliases.kt`。
- `core/i18n/src/main/res/values/strings.xml` 与 `core/i18n/src/main/res/values-en/strings.xml`。
- App 内帮助内容不直接读取 `docs/`；面向用户的帮助文档需检查 `feature/help/src/main/assets/help/*.md`。
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainScreen.kt`。
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityScreenHost.kt`。
- `feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/SettingsScreen.kt`。
- `app/src/main/java/com/wuxianggujun/tinaide/settings/SettingsActivity.kt`。

## UI 约定

- Activity `setContent` 应包 `TinaIDETheme`。
- 优先复用 `core/designsystem` 和已有 `Tina*` 组件，不要重复造按钮、弹窗、底栏样式。
- `StateFlow` 在 Compose 中优先用 `collectAsStateWithLifecycle`。
- 主入口是 `MainScreen` 的 Tab，不是全局统一 `NavHost`。
- 设置页入口优先使用 `SettingsActivity.start(context, SettingsRoute.X)`。
- 主编辑器 UI 拆在 Host、Section、State、component 中，不要把新逻辑塞回单个 Activity 或超大 Host。

## 工作区底部区域约定

- 普通模式默认只固定展示 `DIAGNOSTICS` 与 `BUILD_LOG`；`OUTLINE`、`SYMBOLS`、`BOOKMARKS`、`GIT` 等次级页签从 overflow 进入，选中后临时并入可见页签。
- `RUN_OUTPUT` 只有存在真实 writer/数据源时才能展示。当前运行输出进入 Terminal 或 SDL，因此不要恢复一个没有生产 writer 的空页签。
- 编译失败后保持 `BUILD_LOG`，不要强制切到可能为空或滞后的 LSP `DIAGNOSTICS`。
- `EditorSymbolBar` 是移动端代码字符快捷输入条；底部面板的 `SYMBOLS` 是项目/LSP 符号浏览，两者职责不同，不能互相替代。
- `EditorSymbolBar` 的字符输入必须走编辑器 `insertUserInput()`，以复用 IME/硬键盘一致的括号补全、选区替换和撤销语义。
- 抽屉的打开状态与 `selectedTab` 使用 `rememberSaveable` + saver 保存；同一状态对象存续期间，宽度变化由 `updateDrawerWidth()` 按当前进度换算偏移。不要声称中间宽度进度会跨重建持久化。

## 国际化规则

- 任何可能展示给用户的 UI、Toast、Snackbar、Dialog、Notification、错误提示、状态文案，都必须走资源。
- 推荐入口：`Strings.some_text.str()` 或 `Strings.some_text.strOr(context)`。
- 带参数文本用资源占位符，不要字符串拼接。
- 新增字符串至少同步维护 `values/strings.xml` 与 `values-en/strings.xml`。
- 工具、设置、插件等 feature 可能有自己的 `*ResAliases.kt`，新增前先检索既有别名；RikkaHub 内部页面的资源与别名留在 `external/rikkahub` 子模块。

## 修改流程

1. 先搜索相近页面、组件和字符串资源。
2. 找到页面所属 feature 或 app 协调层，保持现有 state hoisting 和 callback 风格。
3. 添加 UI 文案资源与英文资源。
4. 若新增设置入口，同时更新 `SettingsRoute`、入口跳转、测试或 support 映射。
5. 跑改动所属模块的最小编译和相关 UI/support 测试；只有 `app` 宿主/集成变化才跑 `app` task。

## 高风险误区

- 不要在 Kotlin 代码中硬编码中文、英文或其他用户可见文案。
- 不要绕过 `TinaIDETheme`。
- 不要新增只在一个页面使用的重复设计组件，先复用已有 `Tina*` 组件。
- 不要把首页 Tab 当成 Navigation Compose route 处理。
- 不要修改用户当前未提交 UI 文件时回退其改动；先读 diff 再叠加。

## 验证

- 文案变更运行 `py tools/i18n/check_all.py` 或至少检查 `values`/`values-en` 同步。
- Android library UI 变更运行目标模块 `compileDebugKotlin` 或 `testDebugUnitTest`；`app` 宿主 UI 变更才运行 `:app:compileArm64DebugKotlin`。
- 一次性本地 Gradle 命令带 `--no-daemon`；其他会话正在构建时只做静态检查，不启动或停止 Gradle，不执行 `gradlew --stop`。
- 设置页路由变更优先跑 `SettingsActivitySupportTest` 和 `SettingsScreenSupportTest`。
