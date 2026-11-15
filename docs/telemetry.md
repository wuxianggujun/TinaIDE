## TinaIDE 遥测与自托管上报（AegisHub）

> 最后人工核验：2026-11-15

TinaIDE 的日志、崩溃、意见反馈和启动次数**只**上报到 AegisHub。
没有 AegisHub 账号也能完整使用 IDE：不配置即视为关闭上报，客户端写一条明确的
错误日志后跳过，不会退化到任何其他后端。

### 1) 上报的内容

| 类型 | 触发方式 | 服务器类型键 |
|------|----------|--------------|
| 启动计数 | 主进程每次启动 | `session`（内部遥测，不进 issue 列表） |
| 崩溃 | 主进程崩溃 | `crash` |
| 日志包上传 | 关于页「上传日志」 | `diagnostic` |
| 日志压缩包上传 | 关于页「上传日志压缩包」 | `diagnostic` |
| 意见反馈 | 关于页「反馈」 | `feedback` |

崩溃上报会附带两个附件：墓碑文本（`text/plain`）和完整日志包
（`application/octet-stream`，zip）。日志包里的 `export_manifest.txt` 会记录
本次采集策略，例如 `profile=CRASH_UPLOAD`、`text_sanitized_on_upload=true`。

上传前的统一约束：

- 单个附件不超过 10 MB，单条报告最多 5 个附件。
- 文本附件必须是严格 UTF-8，且不含 `\0`。
- 压缩包按不透明字节上传，服务端不解压、不执行。
- 用户 SDL / Native / NDK 程序的 tombstone 和 PRoot 运行日志默认**不**进入自动上报，
  只在本机导出。

### 2) 配置

三项都在 `keystore.properties`（不要提交）或同名环境变量里：

```properties
AEGISHUB_BASE_URL=https://your-aegishub.example.com
AEGISHUB_APP_ID=<控制台创建应用后得到的 UUID>
AEGISHUB_INGEST_KEY=<控制台创建的 ingest key>
```

`app/build.gradle.kts` 把它们注入成 `BuildConfig` 字段，再由
`app/.../startup/CoreServiceRegistrar.kt` 交给
`core/network/.../aegis/AegisHubEnvironment.initialize(...)`。

留空任意一项，`AegisHubEnvironment.config` 就是 `null`，所有上报跳过。
GitHub Actions 的 release 构建从同名 repository secrets 读取，缺失时同样按关闭处理。

启动事件先写入本机有界队列，网络失败时留待下次启动重试，最多保留最近 20 次。
安装标识和待发队列排除 Android 云备份与设备迁移，避免不同设备复用同一身份。
从旧版本升级时，主进程会一次性取消旧配置/收藏周期任务并删除旧服务配置缓存，
原 Room 收藏和下载历史继续保留。

### 3) 自建 AegisHub

1. 按 AegisHub 仓库的 `deploy/init.sh` + `deploy/deploy.sh up` 起一套实例，
   或本地 `scripts/dev-db.ps1` + `node scripts/platform.mjs dev` 开发模式。
2. 注册平台账号 → 创建应用 → 记下 app id。
3. 在应用里创建 ingest key（写权限，不能用来读报表）。
4. 在应用的报告类型里确认 `crash`、`diagnostic`、`feedback` 可用；
   `session` 需要标记为 internal，才不会进 issue 列表和占用配额。
5. 把三项配置填进 `keystore.properties`，重新构建。

### 4) 排查

- 上报是否被关闭：`AegisHubEnvironment.config == null`。
- 网络与回执：过滤日志标签 `AegisHubClient`、`CrashLogUploader`、`AegisSessionReporter`。
- 崩溃包内容：下载附件解压后看 `export_manifest.txt` 的 `included_sources`
  和 `excluded_sources`。
- 附件被拒：确认 MIME 是 `text/plain` 或 `application/octet-stream`，
  且没有 `application/zip` / `application/gzip` 这类值。

### 5) 相关文档

- [设置总览](../feature/help/src/main/assets/help/settings-overview.md)
- [关于与日志](../feature/help/src/main/assets/help/about-and-logs.md)
- [数据安全与存储](../.agents/skills/tina-data-security-storage/SKILL.md)
