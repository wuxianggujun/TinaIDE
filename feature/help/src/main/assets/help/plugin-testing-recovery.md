# 插件测试、自愈与发布前检查

插件“能安装”不等于“稳定”。这节把开发闭环补成可重复的验收流程，并说明宿主在插件故障后会如何隔离和恢复。

## 分层验证

### 1. 静态预检

先验证：

- `manifest.json` 可解析且字段合法
- `id`、`version`、`type` 和资源路径正确
- `apiVersion` 与 `minAppVersion` 兼容
- 必需权限与可选权限没有重复
- main、theme、snippet、LSP 配置等引用文件都在包内
- `.tinaplug` 根目录直接包含 `manifest.json`

### 2. 热安装闭环

1. 点击运行
2. 确认校验与打包成功
3. 确认热安装完成
4. 到插件详情检查版本、类型和权限
5. 主动启用插件
6. 验证贡献或运行时行为

新安装插件默认禁用。不要把“安装后没有立刻执行”误判为安装失败。

### 3. 从文件安装

再用生成的 `.tinaplug` 走一次系统文件选择器。这样可以覆盖包大小、条目数、解压大小、单项大小、压缩比、manifest 身份和权限确认等门禁。

### 4. 升级与回滚

准备一个已启用的健康旧版本，然后安装严格更高的新版本：

- 成功时应原子切换到新版本
- 安装或激活失败时，健康旧版本应恢复
- 升级应保留插件数据和原有启用意图
- 卸载才会撤销授权并清理插件本地数据

## 插件日志怎么用

入口：`设置 → 插件 → 更多 → 插件日志`。

建议每个关键阶段写一条简短日志：

- 插件加载
- 命令注册结果
- 事件回调开始与失败
- 外部请求的状态，不记录 token 或正文
- LSP 启动、initialize、退出和 owner-stop

日志页可以按级别、插件和关键词筛选，也支持复制与导出。不要把用户源码、密钥、token 或完整网络响应写入日志。

## 自动隔离意味着什么

这些可归因故障可能让插件进入 quarantine：

- 启动或回调异常
- Lua watchdog 超时
- 内存或结果大小越限
- isolated runtime crash
- 无效运行时贡献
- LSP server 成功启动后异常退出

进入隔离后，该版本不会继续自动启动，避免 crash loop。插件详情会展示脱敏后的故障阶段和时间；重新启用需要用户明确确认风险。

权限拒绝、普通网络失败和依赖未准备好属于可恢复业务结果，不应该触发自动隔离。

## 自愈验收

至少验证以下场景：

1. 健康插件能启动并执行命令
2. 死循环会被 watchdog 终止，宿主仍可操作
3. runtime 被杀后进程 generation 会替换
4. 故障插件进入隔离，健康插件仍可恢复运行
5. 重新启用后若再次失败，会立即重新隔离
6. 禁用、卸载、隔离和 runtime 异常会清理命令、面板与事件 owner
7. LSP 插件异常退出不会停止其他插件的 server
8. force-stop / relaunch 后隔离状态仍保持一致

## 兼容更新验收

为 Registry 保留至少两种宿主视角：

- 旧 IDE：只看到满足 `apiVersion` 与 `minAppVersion` 的最高版本
- 新 IDE：看到并安装新的最高兼容版本

下载后的包仍必须再次校验 `id`、`version` 和兼容字段。不要因为 Registry 已过滤就跳过本地校验。

## 发布前最终清单

1. config、script 或 lsp 的真实核心功能已验证
2. 未授权 optional permission 时能安全降级
3. 插件日志无敏感信息
4. 禁用和卸载后不残留命令、面板、事件或 LSP owner
5. 故障不会拖垮主 Activity
6. 旧版本升级失败可以回滚
7. 旧 IDE 不显示不兼容更新
8. manifest、Registry 元数据和包内身份一致
9. 中英文名称、说明和教程与当前行为一致

## 继续学习

- [插件开发快速开始](plugin-quick-start.md)
- [插件 Manifest 与版本兼容](plugin-manifest-compatibility.md)
- [Script API 与最小权限](plugin-script-api.md)
- [插件面板与事件联动](plugin-panels-events.md)
- [LSP 插件开发与排错](plugin-lsp-troubleshooting.md)
- [插件设置说明](plugins-settings.md)
