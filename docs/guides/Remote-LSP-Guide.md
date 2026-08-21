# 远程 LSP 功能使用指南

> 更新日期：2026-08-20

本文说明 TinaIDE 当前远程 LSP 客户端能力。当前仓库保留 Android 端
`RemoteLspConnectionProvider`、远程 LSP 设置项、状态栏和同步配置，但不再内置
`tools/tina-lsp-proxy.py` 或 `tools/tina-lsp-proxy-kt` PC 代理实现。

## 当前边界

- Android 端通过 WebSocket 连接远程 LSP 服务器，默认端口为 `6789`，默认使用 `wss://`。
- `ws://` 只允许本机回环地址；`::1` 与 `[::1]` 会先规范化为 `localhost`，局域网 IP 与公网主机必须使用 `wss://`。
- 每次连接都必须配置 Bearer Token。Token 使用 Android Keystore 支撑的加密存储，不写入普通配置、日志或备份。
- 本地默认路径仍是 native clangd；远程 LSP 是可选 provider。
- 轻量模式适合使用标准 WebSocket ↔ stdio LSP 代理。
- 项目模式和内置同步会发送 TinaIDE 扩展消息，例如 `tina/syncProject`、`tina/fileChanged`；PC 代理必须显式支持这些扩展。
- 当前仓库没有可直接启动的 TinaIDE 专用 PC 代理脚本或 Kotlin 代理工程。

## 快速验证

PC 端可以用第三方 `lsp-ws-proxy` 验证标准 LSP 转发：

```bash
cargo install lsp-ws-proxy
lsp-ws-proxy --listen 0.0.0.0:6789 -- clangd
```

该命令本身只提供明文 WebSocket 和标准 LSP 转发，不能直接作为局域网生产入口。
应在前面增加校验证书与 Bearer Token 的 TLS 反向代理，使 TinaIDE 实际连接 `wss://`；
只有通过 ADB reverse 等方式映射到 Android 本机回环地址时才允许使用 `ws://`。
标准代理不理解 TinaIDE 扩展同步消息，使用时选择轻量模式或手动同步。

## TinaIDE 配置

1. 打开 **设置** → **语言服务器**。
2. 找到 **远程 LSP 服务器**。
3. 开启 **启用远程 LSP**。
4. 输入 PC 的 IP 地址，例如 `192.168.1.100`。
5. 端口保持 `6789`，或填写代理实际监听端口。
6. 保持 **安全传输** 开启；仅回环调试可关闭。
7. 填写由代理端校验的 Bearer Token。
8. 点击 **测试连接** 验证 WebSocket 是否可达。

打开 C/C++ 文件后，状态栏会显示远程 LSP 连接状态。连接成功后，补全、诊断、
跳转定义和引用查找由远程 clangd 提供。

## 同步模式

| 模式 | 适用场景 | 当前建议 |
|------|----------|----------|
| 自动 | 不确定项目规模时 | 可用，但要确认代理支持对应同步能力 |
| 轻量模式 | 单文件、小项目、标准代理 | 推荐用于 `lsp-ws-proxy` |
| 项目模式 | CMake/大型项目 | 需要 TinaIDE 扩展代理或手动同步 |

## 同步方案

| 方案 | 说明 |
|------|------|
| 内置同步 | 需要 PC 代理支持 TinaIDE 扩展消息 |
| rsync 增量 | 需要自行配置 PC 端 rsync daemon 和远程工作目录 |
| 手动同步 | 由开发者自己保证 PC 端项目路径、`compile_commands.json` 和源码一致 |

### 内置同步协议要求

- 非分块同步通过 `tina/syncProject` JSON-RPC 请求发送，代理必须用相同 `id` 返回 result 或 error。
- 分块同步先发送 `tina/syncProjectStart`，再逐个发送 `tina/syncProjectChunk`；开始请求和每个分块都必须分别用相同 `id` 返回 ACK。
- 任一请求超时、返回 error 或连接中断，本次同步不会标记为 `SYNCED`。
- 单文件最多 2 MiB、单次同步最多 64 MiB 和 10,000 个文件。
- 相对路径最多 1024 UTF-8 bytes、目录深度最多 64 层；路径逃逸、重复路径、符号链接和敏感文件不会上传。

## 常见问题

### 连接失败

1. 确认手机和 PC 在同一网络，或路由已放通。
2. 确认 PC 防火墙开放代理端口。
3. 确认代理监听 `0.0.0.0` 或 PC 局域网 IP，而不是只监听 `127.0.0.1`。
4. 确认 WSS 证书链有效，且代理接受设置页保存的 Bearer Token。
5. 在 TinaIDE 设置页使用测试连接查看是否能建立 WebSocket。

### 连接成功但没有补全

1. 确认 PC 端 clangd 已启动且代理把 stdio 正确转成 WebSocket。
2. 轻量模式下确认当前文件内容已通过 LSP `didOpen` 发送。
3. 项目模式下确认代理支持 `tina/syncProject`，否则改用手动同步。
4. CMake 项目需要 PC 端存在可被 clangd 读取的 `compile_commands.json`。

### 补全很慢

1. 优先检查局域网延迟和 PC 端 clangd 日志。
2. 大项目关闭 clangd 后台索引或降低结果数量，再观察响应。
3. 对频繁改动的大项目，优先手动同步或 rsync，而不是重复全量同步。

## 技术规格

| 特性 | 当前状态 |
|------|----------|
| 协议 | WSS + LSP JSON-RPC；WS 仅回环地址 |
| 默认端口 | `6789` |
| 认证 | 必需 Bearer Token，加密保存 |
| 自动重连 | 支持 |
| 心跳/延迟状态 | 支持 |
| TinaIDE 扩展同步 | Android 端发送带 `id` 的请求，PC 代理必须逐请求 ACK |
| 仓库内置 PC 代理 | 当前无 |

## 相关文档

- [PC LSP 代理配置](PC-LSP-Proxy-Setup-Guide.md)
- [LSP 调试指南](LSP-Debug-Guide.md)
