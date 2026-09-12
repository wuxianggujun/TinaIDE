# LSP 调试指南

> 最后人工核验：2026-09-09
> 适用范围：`LspEditorManager` 管理下的 C/C++、CMake、Make、插件 LSP 路径
>
> 编排入口位于 `core/editor-lsp/`（`LspEditorManager`、`CMakeLanguageServiceSession`、`MakeLanguageServiceSession`）；
> clangd 会话与连接提供者位于 `core/lsp/`；插件 LSP 位于 `core/plugin/`。

## 目标

当补全 / 悬停 / 诊断 / 跳转不工作时，用最短路径定位问题发生在：

1. 文件类型分流是否正确
2. 后端会话是否真正 attach 成功
3. 对应运行时是否就绪
4. 诊断 / 补全是否已经同步到 UI

## 先分清当前走的是哪条路径

### C / C++

由 `LspEditorManager` 走 `LspClientSession`，后端可能是：

- `NativeClangdConnectionProvider`
- `PRootClangdConnectionProvider`
- `RemoteLspConnectionProvider`

### CMake

不走 clangd，直接走：

- `CMakeLanguageServiceSession`

### Make / `.mk`

不走 clangd，直接走：

- `MakeLanguageServiceSession`

### 其他语言

如果插件匹配成功，会走：

- `LspPluginManager`
- `PluginLspConnectionProvider`

## 快速检查清单

1. **总开关是否开启**
   检查 `Prefs.devEditorLspEnabled`
2. **文件类型是否被正确识别**
   C/C++、CMake、Make、插件语言的进入条件不同
3. **对应运行时是否就绪**
   native toolchain、PRoot、remote 配置、插件工具链不要混着看
4. **会话是否 attach 成功**
   `LspEditorManager` 会记录 attach 失败、超时和 tab 切换丢弃
5. **是否真的触发了 didOpen / didChange / 请求**
   打开文件后没有 attach，后面的补全 / 诊断请求都不会成立

## 推荐日志标签

```bash
adb logcat -s LspEditorManager LspCompileSetupCache NativeClangd PRootClangd RemoteLsp PluginLspConnection LspPluginManager
```

如果你只想先粗看：

```bash
adb logcat | grep -i "lsp\|clangd\|plugin"
```

## 各路径怎么排

### 1. C / C++ 路径

优先检查：

1. 工具链是否已安装
2. `compile_commands.json` 是否存在
3. 当前到底是 native、PRoot 还是 remote
4. `LspClientSession.connect()` 是否成功

重点日志：

- `NativeClangd`
- `PRootClangd`
- `RemoteLsp`
- `LspEditorManager`

常见现象：

- `clangd exited immediately`
  多半是 ELF 依赖、工作目录或 compile commands 路径问题
- 大量 include 报错
  多半是 `compile_commands.json` 中的 `-I` / `--target` / sysroot 不对
- 状态栏一直 Connecting
  优先看 `RemoteLsp` 或具体 provider 的连接日志

### 2. CMake 路径

当前 CMake 是内建语言服务，不依赖 clangd 进程。

优先检查：

1. 文件名是不是 `CMakeLists.txt` 或 `.cmake`
2. `LspEditorManager` 是否把它分流到 `CMakeLanguageServiceSession`
3. 解析内容是否过大或语法本身有问题

如果这里出问题，不要去盯 `NativeClangd` / `PRootClangd` 日志。

### 3. Make 路径

当前 Make 也是内建语言服务。

优先检查：

1. 文件名是不是 `Makefile`、`makefile`、`GNUmakefile` 或 `.mk`
2. `LspEditorManager` 是否分流到 `MakeLanguageServiceSession`
3. 当前大小写敏感、变量引用、目标解析是否符合文档结构

Make 路径没有 clangd 进程日志，主要看 `LspEditorManager` 的 attach 和请求日志。

### 4. 插件 LSP 路径

优先检查：

1. 插件 manifest 是否声明了匹配的语言 / 扩展名
2. 插件是否启用
3. 必需工具链是否已安装
4. Linux 环境是否可用

重点日志：

- `LspPluginManager`
- `PluginLspConnection`
- `PluginManager`

常见现象：

- `Linux environment is unavailable`
  插件服务器依赖 Linux 环境，但当前环境未安装或不可用
- 插件列表能看到，但文件没有 LSP
  多半是扩展名 / 文件名匹配失败

## 常见问题

### 1. 没有任何诊断 / 补全

排查顺序：

1. 先确认文件走的是哪条路径
2. 确认会话 attach 成功
3. 确认对应后端运行时已就绪
4. 再看请求有没有超时或被 tab 切换丢弃

### 2. 有状态变化，但底部诊断列表为空

排查顺序：

1. 确认 `LspDiagnosticsBridge` 是否收到了当前文件诊断
2. 确认 UI 订阅的是当前 tab 对应的数据源
3. 确认不是 built-in 会话缓存还没刷新

### 3. C/C++ clangd 补全慢或不稳定

优先检查：

- `compile_commands.json`
- 当前是否在 remote 模式下同步项目
- 是否频繁重建会话
- `LspEditorManager` 是否记录了 timeout

### 4. 只有 CMake / Make 出问题

优先检查：

- 文件是否被识别成对应语言
- 当前问题是否来自解析 / 分词，而不是 clangd
- 是否误把“没有 clangd 日志”当成 attach 失败

### 5. 装了依赖包，但 clangd 仍报“找不到头文件”（编译却能过）

典型场景：先创建项目（如 SDL3 模板）→ 打开源码看到头文件报错 → 安装依赖包 → clangd 仍报红，但点编译运行正常。

判定与排查顺序：

1. 先确认这是 **Tina 兜底生成的 compile_commands.json**，而不是 CMake 导出的：看
   `build/debug/compile_commands.tina.meta.properties` 里的 `generatedBy` 字段，
   `tina-fallback` 表示兜底库（消费已安装包的 include），`external` 表示 CMake / 用户提供。
2. 兜底库才会随装包刷新；CMake 导出库（`build/compile_commands.json`）的权威来源是
   CMake 配置，装包后需要重新 configure，clangd 报红属正常预期。
3. 若确为兜底库仍未刷新，确认装包事件是否触发了缓存失效。当前日志关键字为：
   - `EditorContainerState`：`Dependency revision=<n>, refreshed <k> C/C++ tab(s)`，
     或没有活跃 C/C++ 标签时的 `Dependency revision=<n>, no active C/C++ tab; invalidated compile setup cache only`。
   - `LspCompileSetupCache`：`compile setup cache stale for <file> (<projectHint>): compile inputs changed, recomputing`。
4. 仍未恢复时，对照 [compile_commands 与依赖包同步设计](../design/CompileCommands-Package-Sync-Design.md)
   核对包指纹（`packageFingerprint`）是否随安装状态变化。

## 相关文档

- [架构概览](../架构概览.md)
- [compile_commands 与依赖包同步设计](../design/CompileCommands-Package-Sync-Design.md)
- [远程 LSP 指南](Remote-LSP-Guide.md)
- [PC LSP 代理配置](PC-LSP-Proxy-Setup-Guide.md)
