# PC 端远程 LSP 代理配置指南

> 更新日期：2026-07-13
> 适用版本：TinaIDE 0.18.11 及后续当前分支

本文只描述当前仓库能验证的远程 LSP 代理部署方式。历史文档中的
`tools/tina-lsp-proxy.py` 和 `tools/tina-lsp-proxy-kt` 当前不在仓库中，
不能作为可执行命令使用。

## 当前推荐

如果只是验证远程 clangd 能否工作，使用标准 WebSocket ↔ stdio LSP 代理即可：

```bash
cargo install lsp-ws-proxy
lsp-ws-proxy --listen 0.0.0.0:6789 -- clangd
```

TinaIDE 侧配置：

1. 设置 → 语言服务器 → 远程 LSP 服务器。
2. 开启远程 LSP。
3. 服务器地址填写 PC 局域网 IP。
4. 端口填写 `6789`。
5. 同步模式优先选择轻量模式，或使用手动同步。

## 架构

```text
TinaIDE Android client
  RemoteLspConnectionProvider
          |
          | WebSocket ws://<host>:<port>
          v
PC LSP proxy
  WebSocket <-> stdio
          |
          v
clangd / rust-analyzer / gopls / other LSP server
```

## 能力边界

| 能力 | 当前状态 |
|------|----------|
| 标准 LSP 转发 | 可用，依赖外部 WebSocket LSP 代理 |
| TinaIDE 扩展消息 | 需要自定义代理实现 |
| `tina/syncProject` | Android 端可能发送，标准代理通常不支持 |
| `tina/fileChanged` | Android 端可能发送，标准代理通常不支持 |
| 仓库内置 Python 代理 | 当前无 |
| 仓库内置 Kotlin 代理 | 当前无 |

## 自定义代理要求

如果要恢复项目模式或内置同步，需要单独实现 PC 代理，至少满足：

- 监听 WebSocket，默认端口建议 `6789`。
- 为每个连接启动或复用一个 LSP 进程。
- 将 LSP JSON-RPC 在 WebSocket 与 stdio 之间双向转发。
- 处理 TinaIDE 扩展消息：`tina/syncProject`、`tina/fileChanged`。
- 为同步后的项目生成稳定工作目录，并让 clangd 能读取对应源码和
  `compile_commands.json`。
- 输出可诊断日志，但不能记录源码、密钥、token 等敏感内容。

## 验证

PC 端：

```bash
lsp-ws-proxy --listen 0.0.0.0:6789 -- clangd
```

Android 端：

1. 在设置页点击测试连接。
2. 打开 C/C++ 文件。
3. 状态栏应显示远程 LSP 连接状态。
4. 检查补全、诊断和跳转定义是否来自远程 clangd。

## 排错

### 无法连接

- 代理必须监听 `0.0.0.0` 或 PC 局域网 IP。
- PC 防火墙需要允许入站端口。
- 手机和 PC 需要在同一局域网，或配置了可达路由。

### 连接成功但补全异常

- 标准代理不理解 TinaIDE 扩展同步消息，先改用轻量模式。
- CMake 项目需要在 PC 端准备 `compile_commands.json`。
- clangd 参数、sysroot、include 路径需要和项目一致。

### 项目模式不可用

项目模式依赖 TinaIDE 扩展代理。当前仓库没有内置实现；要么补回代理工程，
要么使用手动同步/rsync 让 PC 端项目目录保持一致。

## 相关文档

- [远程 LSP 功能使用指南](Remote-LSP-Guide.md)
- [LSP 调试指南](LSP-Debug-Guide.md)
