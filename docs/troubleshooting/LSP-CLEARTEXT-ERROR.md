# LSP 明文连接错误排查

> 更新日期：2026-08-20

## 现象

```text
连接失败：CLEARTEXT communication to <host> not permitted by network security policy
```

## 当前策略

TinaIDE 默认禁止全局明文网络通信。远程 LSP 默认使用 `wss://`，只有 Android 本机
`localhost` 和 `127.0.0.1` 允许 `ws://`；`::1` 与 `[::1]` 会规范化为 `localhost`。局域网 IP、公网 IP 和域名不能通过修改地址
绕过此限制。

当前配置位于：

- `app/src/main/res/xml/network_security_config.xml`
- `app/src/main/AndroidManifest.xml`
- `core/lsp/.../RemoteLspConfigManager.kt`

`AndroidManifest.xml` 保持 `android:usesCleartextTraffic="false"`，网络安全配置只为两个
回环主机开放 cleartext。不要恢复历史上的全局 `base-config cleartextTrafficPermitted="true"`。

## 修复步骤

### 连接 PC 或远程服务器

1. 在 PC 代理前配置 TLS，提供系统可信或正确安装的 CA 证书。
2. TinaIDE 设置中开启 **安全传输**，连接地址将使用 `wss://`。
3. 在代理端配置 Bearer Token 校验，并在 TinaIDE 设置中保存相同 Token。
4. 检查证书主机名、端口、防火墙和代理监听地址。

### 仅调试本机回环服务

只有服务实际映射到 Android 本机 `localhost` 或 `127.0.0.1` 时，才可关闭安全传输并
使用 `ws://`。例如通过 ADB reverse 映射端口后测试。Bearer Token 仍然必需。

## 验证结果

- 非回环地址配置 `ws://` 时，设置校验和连接层都会拒绝。
- `wss://` 证书或 Token 错误时，连接测试失败且不会记录 Token。
- `ws://localhost:<port>` 与 `ws://127.0.0.1:<port>` 可用于受控的本机调试。

## 相关文档

- [远程 LSP 功能使用指南](../guides/Remote-LSP-Guide.md)
- [PC 端远程 LSP 代理配置指南](../guides/PC-LSP-Proxy-Setup-Guide.md)
- [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)
