# 插件系统路线图（Roadmap）

> 文档更新：2026-07-16
> 目标：以 **配置插件优先** 的方式逐步扩展 TinaIDE 插件能力（Play 合规、低风险、可维护）。

---

## 0. 现状（已完成）

当前已落地的能力：

- 插件安装/卸载/启用/禁用：`core/plugin/src/main/java/.../plugin/PluginManager.kt`
- 插件管理 UI（设置 → 插件）：`feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/PluginsSettingsSection.kt`
- 主题、代码片段、文件树菜单、编辑器 Tab 长按菜单、编辑器工具栏动作菜单
- 插件快捷键、依赖声明提示、插件详情页配置 UI
- 项目模板与 APK 导出模板（`contributions.projectTemplates` / `contributions.apkExports`）
- LSP 插件安装链路
- 脚本 / hybrid 插件最小运行时、权限确认与日志
- assets 内置测试插件自动安装：`core/plugin/src/main/java/.../plugin/BundledPluginsInstaller.kt` + `app/src/main/assets/bundled_plugins/`

当前插件状态模型见：

- `docs/plugins/Plugin-State-Model.md`

---

## 1. 总体原则（强约束）

### 1.1 不重复包管理器

插件系统不做“依赖下载/安装/升级”的具体实现；仅支持 **依赖声明 + 宿主提示 + 跳转到现有安装流程**。

理由（DRY + 风险控制）：

- 镜像源、校验、权限、回滚、缓存、冲突策略等是包管理器/工具链安装的核心能力，插件系统重复实现成本高且难以维护。

### 1.2 菜单先绑定宿主命令（配置插件优先）

阶段 1.5 只做 **命令映射**：插件声明菜单项，绑定宿主内置命令；不执行插件代码。

---

## 2. 阶段 1.5：配置插件增强（推荐优先做）

目标：在不引入脚本引擎的前提下，让插件能“扩展 UI + 提供内容”，覆盖 80% 常见需求。

### 2.1 任务总表

| 功能 | 价值 | 难度 | 优先级 | 备注 |
|------|------|------|--------|------|
| 宿主命令注册表（Command Registry） | ⭐⭐⭐⭐⭐ | ⭐⭐ | P0 | ✅ 已完成（宿主内置命令集合：`HostCommands.kt`；插件命令运行时注册） |
| 文件树目录菜单扩展 | ⭐⭐⭐⭐ | ⭐⭐⭐ | P0 | ✅ 已完成（`menus["filetree/context"]` → 宿主内置命令 / 当前插件已注册命令） |
| 编辑器菜单/工具栏扩展 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | P1 | ✅ editor/context（Tab 长按菜单）与 editor/toolbar（标签栏右侧动作菜单）已完成 |
| SnippetManager（代码片段） | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | P1 | ✅ 已完成（`contributions.snippets`） |
| Keybindings（快捷键绑定） | ⭐⭐⭐ | ⭐⭐⭐ | P2 | ✅ JSON 文件声明，MainActivity 硬件快捷键分发已接入 |
| requires（依赖声明提示） | ⭐⭐⭐ | ⭐⭐ | P2 | ✅ 已完成：manifest 解析、详情展示、doctor 提示；不做安装 |
| 插件详情页（权限/依赖/贡献预览） | ⭐⭐⭐ | ⭐⭐⭐ | P2 | ✅ 已完成：状态、故障、依赖、贡献、必需/可选权限与授权操作均已接入 |
| **插件设置页面** | ⭐⭐⭐ | ⭐⭐⭐ | P2 | ✅ 已完成：manifest `configuration` schema、插件详情页自动配置 UI、持久化与 `tina.config.*` API |

---

## 3. 实现方案与示例

### 3.1 宿主命令注册表（P0）

**目标**：把宿主已有动作抽象成 `commandId -> handler`，供插件菜单/快捷键绑定。

当前实现（已落地最小集）：

- 宿主内置命令集合定义在：`core/common/src/main/java/.../core/commands/HostCommands.kt`
- 插件运行时命令通过 `PluginCommandRegistry` 注册和分发。
- 菜单解析只显示宿主内置命令或当前插件已注册命令；未知 `commandId` 会被忽略并记录日志。

建议最小接口：

```kotlin
interface HostCommand {
    val id: String
    val title: String
    suspend fun run(context: CommandContext): Result<Unit>
}
```

建议命令分类（示例）：

- `file.new`, `file.newFolder`, `file.rename`, `file.delete`, `file.copyPath`
- `editor.save`, `editor.saveAll`, `editor.format`, `editor.search`
- `terminal.openHere`

落地要点：

- `PluginManifest.contributions.commands` 用于提供标题、分类等声明信息，不再等同于唯一可执行集合。
- 可执行命令来自两处：宿主内置命令，或当前插件运行时已注册的插件命令。
- 插件菜单项引用未知 `command` 时，直接忽略并在日志里提示。

### 3.2 文件树目录菜单扩展（P0）

**目标**：在文件树右键菜单中插入插件菜单项。

当前实现（已落地最小集）：

- 扩展点：`app/src/main/java/.../ui/compose/components/FileTreeContextMenu.kt`
- 解析器：`core/plugin/src/main/java/.../plugin/PluginMenuResolver.kt`
- manifest 键：`contributions.menus["filetree/context"]`

你现有上下文菜单实现：

- `app/src/main/java/.../ui/compose/components/FileTreeContextMenu.kt`
- `app/src/main/java/.../ui/compose/components/FileTreeModels.kt`（`FileContextAction`）

建议改造策略（KISS）：

1. 在宿主侧定义 “菜单扩展点”：
   - `FileTreeContextMenu` 渲染完内置项后，追加 `PluginMenuRegistry` 提供的 `DropdownMenuItem`
2. 插件菜单项支持两类命令：
   - 宿主内置命令，例如 `file.copyPath`
   - 当前插件通过 `tina.commands.register()` 注册的命令
3. 菜单仍由 `manifest.contributions.menus["filetree/context"]` 声明：
   - `command` 指向宿主内置命令或当前插件已注册命令
4. 命令执行时传入上下文（当前文件/目录路径、项目根目录等）

插件 manifest 示例（草案）：

```json
{
  "contributions": {
    "commands": [{ "id": "file.copyPath", "title": "复制路径" }],
    "menus": {
      "filetree/context": [{ "command": "file.copyPath", "group": "9_plugin" }]
    }
  }
}
```

示例插件：

- 直接按上文 manifest 草案声明 `filetree/context` 菜单项即可。

### 3.3 编辑器菜单/工具栏扩展（P1）

**目标**：编辑器 UI 上提供“插件按钮/菜单项”。

当前实现（已落地最小集）：

- `contributions.menus["editor/context"]` 已可用（当前落点：编辑器 Tab 长按上下文菜单）
- 扩展点：`app/src/main/java/.../ui/compose/components/TabContextMenu.kt`
- `contributions.menus["editor/toolbar"]` 已可用（当前落点：编辑器标签栏右侧插件动作菜单）
- 分发入口：`app/src/main/java/.../ui/compose/screens/main/MainActivityCommandProvider.kt`
- `when` 最小支持：`isDirty`

示例插件：

- 直接按上文 manifest 草案声明 `editor/context` 菜单项即可。
- 工具栏动作声明 `editor/toolbar`，由宿主统一映射为标签栏右侧动作菜单。

方案与文件树类似：

- 插件只提供菜单描述，宿主负责渲染与条件判断（`when`）

### 3.4 SnippetManager（P1）

**目标**：加载 `contributions.snippets` 并注册到补全系统。

当前实现（已落地最小集）：

- 只加载 JSON 数据（不执行插件代码）
- 在补全列表中展示 snippet，并使用 Tina 片段控制器进行插入
- 示例可直接复用本文的 snippet JSON 结构；当前仓库不再随 APK 内置
  `sample.snippets.cpp`，插件 starter 中保留了 snippet 配置示例。

建议分两步：

1. 先做到“静态插入片段”：在补全列表中出现 snippet（不做占位符跳转）
2. 再增强占位符（`$1`、`${1:default}`）与 tab 跳转

插件 snippet 示例：

```json
{
  "language": "cpp",
  "snippets": [
    { "prefix": "fori", "name": "for (int i=0;...)", "body": ["for (int i = 0; i < ${1:n}; i++) {", "  $0", "}"] }
  ]
}
```

### 3.5 Keybindings（P2）

你现有快捷键系统：

- `core/config/src/main/java/.../core/config/KeyboardShortcuts.kt`

已落地：

- 插件 keybindings 只能绑定宿主命令注册表（同菜单）
- 支持 `isDirty` 与 `editorFocus` 最小 `when` 条件
- 用户自定义/内置快捷键优先，插件快捷键作为兜底分发

### 3.6 requires（依赖声明提示）（P2，已完成基础提示）

**目标**：插件声明需要哪些工具链组件/包，宿主展示并引导用户确认环境（不代替包管理器）。

已支持 `manifest.json` 字段：

```json
{
  "requires": {
    "toolchain": { "recommended": ["clangd", "cmake"], "optional": ["lldb"] },
    "packages": { "proot": ["python3"] }
  }
}
```

已落地行为：

- 插件详情页显示依赖清单
- Plugin Doctor 生成 INFO 级提示
- 不检测真实安装状态，也不自动安装工具链或系统包

### 3.7 插件设置页面（P2，阶段 2 前置）

**目标**：让插件可以声明可配置项，宿主自动生成设置 UI。

**当前状态**：
- 已实现 manifest `configuration` 解析与校验
- 已在插件详情页按 schema 自动生成配置 UI
- 已提供按插件 ID 隔离的配置持久化
- 已提供脚本 / hybrid 插件 `tina.config.get/set/reset`
- 已提供定向 `config.changed` 事件，脚本可监听自身配置变化

**当前边界**：
- 支持 `boolean`、`string`、`number`
- `string` + `enum` 会生成单选配置
- `config.changed` 只派发给配置所属插件，不广播其他插件配置

**manifest.json 示例**：

```json
{
  "configuration": {
    "title": "My Plugin 设置",
    "properties": {
      "myPlugin.enableFeatureX": {
        "type": "boolean",
        "default": true,
        "description": "启用功能 X"
      },
      "myPlugin.outputFormat": {
        "type": "string",
        "default": "json",
        "enum": ["json", "xml", "yaml"],
        "description": "输出格式"
      }
    }
  }
}
```

**实现要点**：

1. **数据模型扩展**
   - 在 `PluginManifest` 中添加 `configuration: PluginConfiguration?` 字段
   - 定义 `ConfigProperty` 数据类（type、default、description、enum 等）

2. **UI 自动生成**
   - 根据属性类型生成对应控件：
     - `boolean` → Switch
     - `number` → TextField（数字输入）或 Slider
     - `string` → TextField
     - `string` + `enum` → DropdownMenu
   - 在插件详情页添加"设置"按钮入口

3. **配置存储**
   - 使用 SharedPreferences
   - 键格式：`<pluginId>:<propertyKey>`
   - 插件卸载时清理该插件 ID 下的配置

4. **配置读取 API**（脚本插件使用）
   ```javascript
   const enabled = tina.config.get("myPlugin.enableFeatureX", true);
   tina.config.set("myPlugin.outputFormat", "json");
   tina.config.reset("myPlugin.outputFormat");
   ```

**参考实现**：VS Code 的 `contributes.configuration`

---

## 4. 阶段 2：插件宿主隔离与自愈

阶段 2 的代码重构和 Android 设备验收已经完成。公开 `apiVersion 1` 不变，不引入另一套脚本引擎；
本阶段把 Lua/JNI、Host API、状态机、安装事务和 LSP 生命周期收敛到可隔离、可归因、可恢复的边界。

状态说明：

- ✅：代码和 JVM/静态验证已完成。
- 待决：属于发布策略，不在本轮技术重构中擅自决定。

### 4.1 实施状态

| 能力 | 状态 | 当前结果 |
|------|------|----------|
| Lua/JNI 进程隔离 | ✅ | 非导出的 `:plugin_runtime` 使用 `isolatedProcess=true`；宿主通过类型化 Binder 调用，已删除宿主进程 Lua fallback。`PluginRuntimeIsolationInstrumentedTest` 和真实 App 验收均已验证 runtime 主动终止后的 Binder death、自愈和 PID 替换；watchdog 额外使用 500ms Binder death fallback，慢设备冷启动连接超时放宽为 10 秒。 |
| Lua 沙箱与 API v1 兼容 | ✅ | 禁用危险库、Java/LuaJava 反射和 native module；保留插件目录内只读纯 Lua `require()`。同一设备测试已覆盖死循环 watchdog、受限 `require()`、危险库和 stale generation/超大 Binder 返回值。 |
| Host capability gateway | ✅ | 文件、编辑器、Command、Clipboard、Network、Database、UI 等能力统一回到宿主；每次调用重验 generation、有效启用态、manifest 声明和运行时授权。 |
| 故障状态机与自愈 | ✅ | 已落地 `desiredEnabled`、有效状态、故障记录和 in-flight journal；禁用/卸载递增 generation，故障插件隔离，空闲 runtime 死亡后恢复健康插件。`PluginQuarantinePersistenceInstrumentedTest` 已验证两次宿主对象图重建后的持久化；真实 App 验收进一步确认 watchdog 后故障插件进入 `QUARANTINED / EXECUTION_TIMEOUT`、健康 survivor 在新 runtime 恢复 `ACTIVE`，且 `force-stop/relaunch` 后 quarantine 不丢失。 |
| 安装事务与资源限制 | ✅ | 新装默认禁用；升级使用 staging/backup/atomic rename 和恢复 journal；Zip、Lua、日志、Binder、Network 与 PSS 均设置上限。市场下载与本地 URI 导入流量超过 64 MiB 会立即中止并删除残留；每次市场请求使用独立临时包，安装前后绑定请求 ID/版本。权限授权、manifest 身份/声明复验、文件安装和失败回滚统一进入同一互斥且不可取消的事务；旧授权快照也写入安装 journal，进程中断可恢复，成功替换会裁剪新 manifest 已不再声明的 grant。设备测试已验证约 96 MiB Lua 保留内存触发 `RESOURCE_LIMIT` 后 runtime 可恢复。 |
| LSP 生命周期归属 | ✅ | session 绑定 `ownerPluginId`，禁用、隔离、升级、卸载会关闭会话；command/args/env 已校验。owner 代际令牌会拒绝禁用期间尚未完成的延迟注册，`activationEvents` 同时约束 language、扩展名和文件模式路由。JVM 测试覆盖 owner 定向清理和成功启动后的异常退出回调；真实 App 验收确认 readiness 失败不隔离、server 异常退出进入 `QUARANTINED / LSP_CRASH`、重新启用可建立新会话，UI 禁用会关闭 PRoot/server，并通过 owner-stop 回调释放编辑器 session，将状态从 `LSP Ready` 更新为 `No LSP`。 |
| 设置 UI 与文档 | ✅ | 已增加等待授权、自动隔离、runtime 不可用和风险确认状态，并同步中英文资源、App 内帮助、API 合同与 starter README。 |

### 4.2 设备验收结果（2026-07-14）

1. Lua watchdog 触发后 isolated runtime PID 被替换，宿主 PID 和 Activity 保持；编辑器、文件抽屉仍可操作，健康 survivor 插件在新 runtime 恢复 `ACTIVE`。
2. 真实 `force-stop/relaunch` 后 `QUARANTINED / EXECUTION_TIMEOUT` 持久化；用户风险确认和重新启用流程通过。
3. 真实 PRoot LSP 已完成 initialize 并进入 fully connected；readiness 失败不会隔离插件，成功启动后的 server 异常退出会进入 `QUARANTINED / LSP_CRASH`，重新启用后可创建新 PRoot/server 进程。
4. 从插件 UI 禁用 owner 后，PRoot 与 server PID 均退出，日志出现 `Closing LSP connection` 和 `Plugin LSP owner stopped; releasing session`，返回原编辑器后状态显示 `No LSP`。
5. PRoot 回归补齐真实子进程探针：宿主工作目录固定到 App 私有目录，Android x86_64 seccomp 拒绝的 `fork/vfork` 会在 PRoot 内等价转换为 `clone`；同一设备连续 3 次 guest `ls/cat` 子进程链路及 App 全量 instrumentation 均通过。验收同时修复了发行版注册表并发刷新共用临时文件导致的启动崩溃。
6. 阶段 2 的技术重构与设备验收至此完成；发布渠道策略已暂缓，不属于当前稳定性工作的验收项。

### 4.3 发布渠道策略（暂缓，不在本轮范围）

当前代码仍保留用户从文件安装 `.tinaplug` 的统一入口。Play/Release 是否允许安装 `script` / `hybrid`
插件属于产品与合规策略，需要结合实际分发渠道单独决定；本轮不新增未经确认的 `BuildConfig` 分叉。

可选策略仍是：Play 渠道只运行随 APK 发布的受控脚本插件，开发/非 Play 渠道允许用户明确选择文件安装。
无论采用哪种策略，都不得绕过 isolated process、双层权限、Host API 白名单、资源限制和审计边界。

### 4.4 P2 稳定契约收口（2026-07-15）

| 能力 | 状态 | 当前结果 |
|------|------|----------|
| 文本面板 | ✅ | `contributions.panels` 仅允许 script/hybrid 声明；`tina.panels.setContent/appendContent/clear` 只能写本插件已声明面板，单面板上限 256 KiB UTF-8。底部“插件”面板仅在存在启用贡献时显示，禁用、卸载、隔离和 runtime death 会清理内容。 |
| 自定义事件 | ✅ | 已实现 `tina.events.emit("custom", payload)` 定向异步派发；未知事件、非 object payload 和宿主事件伪造会被拒绝，重复订阅去重。 |
| 可选权限 | ✅ | 插件详情页支持单项授予/撤销；`optionalPermissions` 包含的 L0 权限也必须显式授权，撤销后 capability gateway 立即拒绝调用。UI 操作和核心状态变更均串行化，并在核心层复验“已安装且属于 optional 声明”，避免快速点击、卸载和升级交错覆盖授权。 |
| LSP activationEvents | ✅ | apiVersion 1 仅接受 LSP `onLanguage:<languageId>`，并校验目标语言已由 `languageServers` 声明；声明列表会实际限制 language ID、文件扩展名和文件模式路由。 |
| Host gateway 降耦 | ✅ | 持久化 storage、network、database handler、SQLite 封装和 Binder 大载荷存储已从主路由拆出；网络重定向逐跳复验白名单，SQLite 使用完整 ID 哈希隔离；卸载先提交目录移除，再以持久化 journal 幂等清理授权、配置、KV/DB，清理中断会在启动或同 ID 重装前恢复，主路由保留 generation/启用态/权限统一校验。 |
| 事件与 Lua 资源边界 | ✅ | 选区和诊断事件采用显式上限；宿主侧超大请求返回输入错误而不隔离插件；卸载按插件 ID 清理全部 Lua module generation 计数，避免代际错位残留。 |
| LSP owner-stop 回归 | ✅ | owner-stop 的 attach token 校验、请求代际失效、session 移除和 `No LSP` 状态切换合并为一次原子转移；attach 成功/失败也必须在同一 token 校验下提交，避免延迟回调把替换会话或 `No LSP` 覆盖成旧 `Ready/Error`。初始化失败或协程取消会在不可取消的 IO 清理段关闭尚未登记的临时 session，避免遗留孤儿 server。覆盖“当前 attach 释放并转 No LSP”和“旧 callback 不影响替换会话”。 |
| 文档与 starter 路径 | ✅ | API 契约、开发指南、App 内帮助与 starter README 已同步；starter zip 的事实路径统一为 `tools/plugin-starters/dist/tinaide.plugin.starters/templates/`。 |

### 4.5 设备稳定性门禁（2026-07-15）

| 门禁 | 状态 | 当前结果 |
|------|------|----------|
| isolated runtime native crash | ✅ 代码完成 | `PluginRuntimeIsolationInstrumentedTest` 通过内部 debuggable-only Binder 测试入口向 `:plugin_runtime` 发送真实 `SIGSEGV`，断言宿主 PID 保持、故障插件进入 `QUARANTINED / RUNTIME_CRASH`、健康插件在替换后的 runtime PID 恢复。测试入口不属于公开 Plugin API，非 debuggable 构建拒绝执行。 |
| force-stop/relaunch quarantine | ✅ 代码完成 | `PluginQuarantinePersistenceInstrumentedTest` 提供 prepare/verify 两阶段，`tools/testing/plugin-device-gate.ps1` 在阶段间执行真实 `adb force-stop`，验证新进程仍读取相同故障、有效状态和 desired enabled 状态。 |
| 统一设备入口 | ✅ | 同一脚本运行两套关键 instrumentation suite，校验单设备、force-stop 后 PID 和每个阶段的精确测试数；`0 tests`、ignored/assumption skip、runner failure、数量不匹配均明确失败。每次运行保留 instrumentation 原始输出、设备信息、logcat、汇总和 verdict 诊断制品，并在结束时停止测试进程、卸载测试 APK 和回收 Gradle daemon。 |
| JVM 长期 CI | ✅ | `dev-static-check.yml` 固定执行 `:core:plugin:testDebugUnitTest`、`:feature:help:testDebugUnitTest`、`:feature:tutorial:testDebugUnitTest` 和 App 教程文章状态测试；教程门禁任务同时完成 App Kotlin 编译，失败时上传并保留 14 天的插件/教程 XML/HTML 报告。提交 `76173ebe4` 对应的 run `29691804039` 已完整通过两个 Gradle 步骤和进程清理；插件核心快照仍为 176 项 JVM 测试，教程测试单独统计，避免混淆基线。 |
| 设备长期 CI | 🟡 设备基础设施待接入 | `.github/workflows/plugin-device-gate.yml` 已准备严格结果判定和诊断制品上传，但仍需在默认分支完成 workflow 注册，并配置带 `android-device` 标签的 self-hosted Android 设备 runner。完成前不能把 workflow 文件存在或排队状态视为真机门禁已运行。 |
| 真实 PRoot LSP | 保留人工/条件式验收 | 仍依赖 ABI、发行版和 toolchain 资产，不并入普通插件 instrumentation；`assumeTrue` 跳过不等于通过。 |

门禁口径：JVM CI 已可长期执行；设备 workflow 的脚本和判定逻辑已就绪，但默认分支注册与 self-hosted
设备 runner 尚未完成，因此本节不新增任何真机验收结论。

### 4.6 JVM 稳定性与确定性收口（2026-07-15）

| 能力 | 状态 | 当前结果 |
|------|------|----------|
| 教程加载与全文搜索 | ✅ | 教程目录和正文分别建模 Loading/Empty/Error/Content；正文失败提供重试。帮助搜索统一使用 `language:documentId` 缓存键，避免正文关键词漏搜和中英文缓存串用；`feature:help`、`feature:tutorial` 与 App 文章状态回归已纳入 Dev Static Checks。 |
| workspace 搜索确定性 | ✅ | `tina.workspace.findFiles()` 在最多扫描 50,000 项的资源边界内收集匹配结果，统一按 `/` 相对路径排序后再应用 `maxResults`；未触及扫描上限的相同目录树在 Windows/Linux 和不同创建顺序下返回相同前 N 项。 |
| script runtime 首次同步 | ✅ | 插件状态与权限流使用单一 `combine` 同步入口，首次只加载一次；后续权限授予/撤销仍触发同步。runtime service 不可用保持 `RUNTIME_UNAVAILABLE`，不会因第二次加载短暂回退到 `LOADING`，也不会误隔离插件。 |
| 测试进程隔离 | ✅ | 故障存储并发回归采用整组 20 秒 deadline，并在结束时等待 executor 退出；协程取消不再被普通 `Throwable` 分支吞掉，避免伪错误和跨测试线程污染。 |
| CI 失败可诊断性 | ✅ | 插件 JVM 步骤失败时自动上传 `core/plugin` 的 XML 与 HTML 测试报告；成功运行不生成无意义制品。 |

以上收口不修改 manifest schema、公开 `apiVersion 1`、Registry v2/v3 选择协议或插件持久化结构，
旧 IDE 与旧插件兼容边界保持不变。

### 4.7 本阶段不做

- 不引入动态 DEX、远程插件代码或新的 Marketplace 协议。
- 不为每个插件创建独立常驻进程。
- 不把 LSP server 移入宿主 JVM；它继续作为有明确 owner 的外部工具进程运行。
- 不新增 `apiVersion 2`，也不恢复 `io`、`os.execute`、native `loadlib` 或 Java 反射能力。
