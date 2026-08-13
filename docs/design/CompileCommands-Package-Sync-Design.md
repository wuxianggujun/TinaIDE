# compile_commands 与依赖包同步机制

> 创建日期：2026-06-12
> 最后复核：2026-08-13
> 适用范围：`CompileDatabaseProvider`、`LspEditorManager` 的 C/C++ compile_commands 生成与复用链路

## 概述

本文档说明 TinaIDE 如何为 clangd 准备 `compile_commands.json`，以及如何保证「安装/卸载依赖包后，clangd 能及时看到新的头文件路径」，避免出现「包已安装但 clangd 仍报找不到头文件」的假错。

## 背景：一个真实的 Bug

### 复现路径

1. 先安装 SDL3 模板插件，**再**创建 SDL3 项目（此时 SDL3 包尚未安装）。
2. 打开 `main.cpp`，clangd 报「找不到 SDL3 头文件」——此时合理，因为包确实没装。
3. 退出项目 → 安装 SDL3 包 → 重新打开项目。
4. **clangd 仍然报找不到 SDL3 头文件（假错），但编译运行却正常。**

而「先装 SDL3 包，再创建项目」则一切正常。

### 三层根因

这个 Bug 由三个层面叠加导致：

**第一层：`prepare()` 对 CMake 项目无条件复用旧数据库。**

`CompileDatabaseProvider.prepare()` 的旧逻辑里：

```kotlin
val shouldReuseExisting = when {
    !hasUsableCompileCommands -> false
    isCmakeProject -> true          // ← BUG：CMake 项目无条件复用任何已存在的 DB
    else -> compileCommandsUpToDate(...)  // 只有非 CMake 才校验包指纹
}
```

SDL3 项目带 `CMakeLists.txt`，所以 `isCmakeProject = true`。问题在于这里没有区分两种 compile_commands：

- **CMake 真正导出的权威 DB**（`build/compile_commands.json`，由 `CMAKE_EXPORT_COMPILE_COMMANDS=ON` 生成）；
- **Tina 在缺少权威 DB 时自动生成的兜底 DB**（`build/debug/compile_commands.json`）。

兜底 DB 会把「当前所有已安装包」的 include 路径塞进编译参数，因此它会随装包而过时；但旧逻辑把它也当成权威 DB 无条件复用，于是装包后从不刷新。

**第二层：装包事件在「无活跃 C/C++ tab」时什么都不做。**

`EditorContainerState.refreshOpenCxxEditorsForDependencyChange()` 的旧逻辑在「当前没有打开的 C/C++ 编辑器」时直接 `return`，不清任何缓存。于是「项目开着 + 装包 + 当时没有活跃 C/C++ 文件」时，内存缓存原封不动。

**第三层：`compileSetupCache` 内存缓存绕过指纹判断。**

`LspEditorManager.resolveCompileSetup()` 第一步就查内存级 `compileSetupCache`，命中即返回旧的 compile_commands 目录——**这发生在 `prepare()` 指纹判断之前**。只要这个缓存残留，第一层的修复也没机会生效。

## 解决方案

### 1. 用 `generatedBy` 标记区分 DB 来源

在 compile_commands 的 meta 文件（`compile_commands.tina.meta.properties`）里增加 `generatedBy` 字段：

| 来源 | `generatedBy` | 复用策略 | 装包后行为 |
|------|---------------|----------|------------|
| Tina 兜底生成 | `tina-fallback` | **走包指纹/工具链/C++ 标准校验** | 自动失效重建（带上新包 include） |
| CMake 导出 | 无 meta → 视为 `external` | 直接复用 | 尊重 CMake 配置（需重新 configure） |
| 用户提供 | `external` | 直接复用 | 尊重外部配置 |

`prepare()` 的判断改为：

```kotlin
val shouldReuseExisting = when {
    !hasUsableCompileCommands -> false
    // CMake 等外部工具导出的 DB 是权威数据库，直接复用；
    // 但 Tina 兜底生成的 DB 可能因装包/工具链变化而过时，仍需走指纹校验。
    isCmakeProject && !isTinaGeneratedCompileCommands(compileCommandsDir) -> true
    else -> compileCommandsUpToDate(...)
}
```

**设计取舍**：CMake DB 的权威来源是 CMake 配置本身（`find_package` 等），不应被指纹强制覆盖；而兜底 DB 把所有已安装包的 include 都塞进去，所以装包必须刷新。两者语义不同，必须区分对待。

**兼容旧数据**：历史 meta 文件没有 `generatedBy` 字段时，保守视为 `external`（维持原有 CMake 直接复用行为），等下次生成时补齐标记。

### 2. 装包事件无条件失效编译缓存

`refreshOpenCxxEditorsForDependencyChange()` 在早期 return **之前**先调用 `lspEditorManager.invalidateCompileSetupCache()`：

```kotlin
fun refreshOpenCxxEditorsForDependencyChange(revision: Long) {
    if (revision <= lastHandledDependencyRevision) return
    lastHandledDependencyRevision = revision

    // 即使当前没有活跃的 C/C++ 编辑器，也要先清内存中的 compile setup 缓存，
    // 否则下次打开 C/C++ 文件时会绕过包指纹校验，导致头文件假错。
    lspEditorManager.invalidateCompileSetupCache()

    val refreshCandidates = /* 统计活跃 C/C++ tab */
    if (refreshCandidates <= 0) return  // 仅清缓存，不重连
    lspEditorManager.refreshLspConnection(context)
}
```

### 3. 缓存命中时的指纹自愈（兜底防线）

`resolveCompileSetup()` 命中 `compileSetupCache` 时，再校验一次包指纹——指纹变了就丢弃旧缓存重算。即使将来某条新路径漏掉了显式失效调用，只要已安装包发生变化，attach 时也能自动纠正：

```kotlin
compileSetupCache[key]?.let { cached ->
    if (isCompileSetupStillFresh(context, cached)) return cached
    // 指纹已变，移除旧缓存走正常重算
    compileSetupCache.remove(key)
}
```

指纹计算（`CompileDatabaseProvider.computePackageFingerprint()`）会扫描 `installed-packages` 目录并做 SHA-256，因此放在 `Dispatchers.IO` 执行；计算失败时保守视为「仍有效」，避免偶发 IO 错误反复重建拖慢 attach。

## 包指纹的构成

`resolvePackageFingerprint()` 把以下信息排序后做 SHA-256：

- 已安装包贡献的 include / lib / prefix 目录（来自 `InstalledPackagePathResolver`）；
- 本地安装状态里每个包的 `packageId | platform | version | installType`（来自 `LocalInstallStateStore`）。

任何一项变化都会改变指纹，从而触发兜底 DB 重建。

## 三条触发路径的覆盖关系

| 场景 | 由哪层修复覆盖 |
|------|----------------|
| 关项目 → 装包 → 重开 | 新 Activity（新 `LspEditorManager`）+ `prepare()` 指纹判断 |
| 项目开着装包（无活跃 C/C++ tab） | 依赖事件无条件失效缓存 |
| 任何路径漏掉显式失效 | 缓存命中时指纹自愈 |

## 相关源码

| 文件 | 职责 |
|------|------|
| [`CompileDatabaseProvider.kt`](../../core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/CompileDatabaseProvider.kt) | compile_commands 的生成、复用判断、指纹与来源标记 |
| [`CompileCommandsGenerator.kt`](../../core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/CompileCommandsGenerator.kt) | 兜底 DB 的实际生成（拼接 clang 编译参数） |
| [`InstalledPackagePathResolver.kt`](../../core/packages/src/main/java/com/wuxianggujun/tinaide/core/packages/InstalledPackagePathResolver.kt) | 解析已安装包的 include/lib/prefix 路径 |
| [`PackageDependencyEvents.kt`](../../core/packages/src/main/java/com/wuxianggujun/tinaide/core/packages/PackageDependencyEvents.kt) | 包安装/卸载事件广播 |
| [`LspEditorManager.kt`](../../core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/LspEditorManager.kt) | `compileSetupCache` 管理、缓存失效与指纹自愈 |
| [`EditorContainerState.kt`](../../app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) | 订阅包变更事件并触发刷新 |
