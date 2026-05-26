---
name: tina-native-lsp-runtime
description: TinaIDE native 编译运行、Android sysroot、tina-toolchain assets、clangd/LSP、Tree-sitter 与 PRoot 排障指南。用于处理 C/C++ 编译、clang/lld/clangd、语言服务、toolchain 包、queries/registry 或 PRoot 相关改动。
---

# TinaIDE Native / LSP / Runtime

## 先读文件

- `core/compile/**`：编译、工具链、sysroot 相关能力。
- `core/lsp/**`：语言服务 provider、remote/plugin/native 入口。
- `core/ndk/**`、`core/proot/**`、`core/tree-sitter/**`。
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt`。
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt`。
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt`。
- `app/src/arm64/assets/tina-toolchain/current.properties`。
- `app/src/x86_64/assets/tina-toolchain/current.properties`。
- `tools/verify-tina-toolchain-package.ps1`、`tools/sync-tina-toolchain-assets.ps1`。

## 项目事实

- 默认编译/LSP 依赖 native tina-toolchain + Android sysroot，不默认依赖 PRoot rootfs。
- `AndroidSysrootManager` 与 `AndroidNativeToolchainManager` 是独立管理职责，不能合并。
- toolchain assets 由 `app/src/<abi>/assets/tina-toolchain/current.properties`、tar.xz 和 sha256 约束。
- arm64 当前记录为 `version=0.2.4`、`arch=aarch64`、`full=tinaide-toolchain-aarch64-v0.2.4-patched.tar.xz`。
- x86_64 当前记录为 `version=0.2.3`、`arch=x86_64`、`full=tinaide-toolchain-x86_64-v0.2.3.tar.xz`。
- C/C++ LSP provider 包括 native clangd、PRoot clangd、remote LSP、plugin LSP。
- CMake / Make 使用内建语言服务，不默认走 clangd。

## 修改流程

1. 先确认问题属于编译、LSP、Tree-sitter、toolchain assets 还是 PRoot。
2. 搜索现有 manager/provider，不要新增平行实现。
3. toolchain 包变更先更新对应 ABI assets、sha256 和 `current.properties`。
4. LSP 状态或 UI 变更同时检查 `LspEditorManager`、`EditorContainerState`、`TinaCodeEditorPage`。
5. Tree-sitter queries 变更按手动同步/生成流程处理。

## 禁止事项

- 不要把 PRoot 当默认 C/C++ 编译路径。
- 不要合并 sysroot manager 与 native toolchain manager 的职责。
- 不要手改生成的 language registry。
- 不要把 `syncTreeSitterQueries` 接入普通 Gradle build。
- 不要绕过 toolchain assets 校验。

## 验证

```powershell
./gradlew :app:compileArm64DebugKotlin --console=plain
./gradlew :app:assembleArm64Debug --console=plain
pwsh ./tools/verify-tina-toolchain-package.ps1
```

- LSP UI 或状态变更跑相关 app/editor tests。
- Tree-sitter 改动检查 registry 生成任务和 ktlint 排除规则。
