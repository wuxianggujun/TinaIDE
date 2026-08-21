# 插件状态模型

> 文档更新：2026-07-16
> 目标：统一 TinaIDE 插件系统中的安装态、启用态、运行态与页面态，避免状态漂移。

---

## 1. 为什么需要这份文档

插件页面、插件市场、脚本运行时、LSP、Snippet、菜单与 APK 导出都依赖插件状态。

如果每个模块都自己推导一次状态，就很容易出现这些问题：

- 已卸载插件的详情页还停留在屏幕上
- 市场页仍显示“已安装 / 可更新”
- 禁用插件后，进入项目仍继续生效
- 某些模块读取“已安装列表”，另一些模块读取“已启用列表”，行为不一致

这份文档定义当前版本的**单一状态来源**和**消费规则**。

---

## 2. 状态分层

### 2.1 安装态（Installed State）

定义：插件目录和 `manifest.json` 是否存在于本地。

来源：

- `PluginManager.refreshInstalledPlugins()`
- 本地目录：`filesDir/plugins/<pluginId>/`

数据表现：

- `PluginStateSnapshot.installedPlugins`
- `PluginStateSnapshot.installedPluginIds`
- `PluginStateSnapshot.installedVersions`

### 2.2 启用态（Enabled State）

定义：插件虽然已安装，但当前是否允许向宿主贡献能力。

来源：

- `SharedPreferences` 中的 `desired_enabled_<pluginId>`（用户期望）
- `enabled_<pluginId>` 作为降级兼容字段；隔离时强制写为 `false`
- `PluginManager.resolvePluginEnabled()`

数据表现：

- `PluginStateSnapshot.enabledPlugins`
- `PluginStateSnapshot.enabledPluginIds`
- `PluginStateSnapshot.enabledCapabilities`
- `PluginManager.enabledPluginsFlow`

### 2.3 有效状态（Effective Status）

定义：综合用户期望、权限、隔离记录和 runtime 可用性后，插件当前真实状态。

脚本插件状态包括：

- `DISABLED`
- `WAITING_PERMISSION`
- `LOADING`
- `ACTIVE`
- `QUARANTINED`
- `RUNTIME_UNAVAILABLE`

`desiredEnabled=true` 不代表插件一定可运行；存在故障隔离时，有效启用态仍为 false。
有效状态由 `PluginFaultStore.effectiveStatuses` 持久化；故障记录与 `QUARANTINED` 状态在同一次同步提交中落盘。
`RUNTIME_UNAVAILABLE` 表示宿主插件运行基础设施当前无法安全执行，例如 isolated runtime 服务或执行 journal 持久化不可用；
它不是可归因到插件的 fault，不得生成隔离记录，也不得清除用户的 `desiredEnabled`。只有用户期望启用、插件未被隔离、
运行基础设施可用且必需权限满足时，脚本插件才进入 `ACTIVE`。

### 2.4 故障与执行 journal

来源：`PluginFaultStore`，使用附加 SharedPreferences 字段持久化：

- `PluginFaultRecord`：插件版本、阶段、故障类型、脱敏信息、时间和 execution ID
- `PluginInFlightRecord`：进入插件代码前同步落盘，正常返回后清除
- `PluginInstallTransactionRecord`：目录替换前落盘；记录旧版本 backup、启用状态与旧故障状态

启动时先恢复安装事务，再审计执行 journal，之后才启动状态同步。`pluginStateFlow` 与 `grantsFlow` 必须通过
单个组合流同步：首次 replay 只允许加载一次，后续权限变化仍必须触发重算。残留执行 journal 会归因到对应插件版本并进入隔离；
损坏的安装 journal 会让插件子系统 fail-closed，并保留 staging/backup 供恢复，避免加载半安装目录。

### 2.5 运行态（Runtime State）

定义：对需要运行时的插件，当前是否真的加载并在内存中工作。

当前主要包含：

- `script`
- `hybrid`
- `lsp`（更准确地说是“可服务态 / 工具链就绪态”）

来源：

- `ScriptPluginManager`（宿主协调器；Lua/JNI 只存在于 `:plugin_runtime`）
- `LspPluginManager`

关键约束：

- disable / uninstall / upgrade 必须通过可等待的串行生命周期操作停止 runtime，再完成状态切换
- 运行态绝不能绕过启用态独立存在
- 所有回调携带 generation；旧 generation 不能重新注册贡献或激活插件
- LSP session 必须记录 `ownerPluginId`，插件失效时立即关闭对应进程和编辑器连接
- 状态与权限初值不得由两个独立 collector 分别触发加载，避免第二次 `LOADING` 覆盖已经稳定的有效状态
- 必需权限补齐后必须重新计算有效状态；满足权限和 runtime 条件时从 `WAITING_PERMISSION` 进入 `ACTIVE`

### 2.6 页面态（UI State）

定义：页面当前选中了哪个插件、是否在详情页、是否在管理模式。

规则：

- 页面层**只保存稳定 ID**，不要保存整块插件对象快照
- 详情展示时，始终根据 `pluginId` 从最新列表回查

当前约束：

- 设置页插件详情：保存 `selectedPluginIdForDetail`
- 设置里的插件市场：保存 `selectedPluginId`
- 主市场页：保存 `selectedPluginId`

---

## 3. 单一状态来源

当前插件系统的中心状态源是：

- `PluginManager.pluginStateFlow`

其快照类型为：

- `PluginStateSnapshot`

它负责一次性产出：

- 安装列表
- 启用列表
- 已安装版本映射
- 已启用 capability 集合

### 3.1 消费规则

不同模块必须按下面规则取状态：

- 插件管理页、卸载页、批量管理页：读取**安装态**
- 代码片段、菜单、文件图标、APK 导出模板、项目模板、LSP 注册：读取**启用态**
- 脚本运行时、事件总线绑定：读取**启用态**，并在禁用时卸载运行态
- 市场页“已安装 / 可更新”：读取**安装态 + 版本映射**
- 页面详情选中：保存 `pluginId`，展示时从最新列表回查

---

## 4. 当前实现映射

### 4.1 中心状态

- `core/plugin/.../PluginManager.kt`
- `core/plugin/.../PluginStateSnapshot.kt`

### 4.2 安装态派生

- `PluginMarketplaceInstallStateResolver`

用途：

- 市场页统一计算“已安装 / 可更新”

### 4.3 运行态

- `ScriptPluginManager`
- `LspPluginManager`

### 4.4 页面态

- `SettingsScreen`
- `PluginsSettingsSection`
- `PluginMarketplaceViewModel`
- `MarketScreenViewModel`

---

## 5. 开发约束

后续新增插件相关代码时，必须遵守下面规则：

1. 不要在页面或 ViewModel 中缓存 `InstalledPlugin` / `PluginSummary` 作为长期选中态。
2. 不要让模块自己重新维护一份“已安装 / 已启用 / 可更新”集合，优先复用中心快照或仓库解析器。
3. 任何“会影响宿主行为”的模块都只能消费“启用态”，不能直接消费“安装态”。
4. 对脚本 / hybrid 这类有运行时的插件，禁用时必须同步卸载运行时和事件订阅。
5. 新安装的纯 `config` 插件默认启用；`script`、`hybrid`、`lsp`、`system` 等插件默认禁用。刷新安装列表不得改变用户启用意图或隐式启动可执行插件代码。
6. 插件故障与宿主/环境故障必须分类，权限拒绝、网络失败和依赖未就绪不得误隔离。
7. 如果新增插件能力，先明确它属于“安装态”“期望启用态”“有效状态”“运行态”还是“页面态”，再决定挂在哪层。
8. 卸载必须撤销运行时授权并清理插件 KV/SQLite 数据；升级只替换代码与 manifest，不得误删持久化数据。
9. 状态快照和权限授权必须由同一串行同步入口消费；不得为两个 `StateFlow` 分别建立会 replay 初值的加载 collector。

---

## 6. 反模式

以下写法禁止继续新增：

- `var selectedPlugin by mutableStateOf<InstalledPlugin?>(...)`
- `var selectedPlugin by mutableStateOf<PluginSummary?>(...)`
- 在多个 ViewModel 中重复拷贝版本比较逻辑
- 菜单、Snippet、LSP、APK 导出直接遍历“所有已安装插件”再临时过滤
- 禁用插件时只更新 UI，不处理运行时或事件订阅
- 用两个独立 `StateFlow` collector 分别同步同一批 script runtime，导致首次回放重复加载

---

## 7. 推荐新增能力方式

如果后续还要扩展插件系统，推荐优先按下面顺序落地：

1. 先补 `PluginStateSnapshot` 的字段
2. 再在 `PluginManager.refreshInstalledPlugins()` 中统一生成
3. 再让消费方订阅中心状态流
4. 最后再补页面展示和测试

不要反过来先在页面里拼状态，最后再补宿主层。
