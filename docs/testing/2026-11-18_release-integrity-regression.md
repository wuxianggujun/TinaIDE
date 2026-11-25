# 发布完整性修复与回归

本轮只处理已定位的八项缺口，不恢复旧上传后端、不修改子模块、不新增第三方依赖，也不执行可能递增版本号的 Release 构建。
按用户要求先完成实现与回归用例，再统一运行验证。编码与资源使用 UTF-8（无 BOM），状态与请求体测试包含中文及 emoji。

## 修复范围与验收标准

| 问题 | 实现与验收 |
| --- | --- |
| 不附日志仍发送设备信息 | 反馈默认 None；None 与自选附件的请求体不包含 device，只有 Automatic 生成设备字段与诊断包 |
| 重建后附件丢失并改传自动日志 | 使用保存的缓存 key 和展示名恢复选择；缓存缺失本地报错，绝不改传自动日志；失败保留、取消或成功清理 |
| 共享库发布存在读目录空窗 | 完整库树与索引写入同一代，再原子切换 ABI 指针；构建作用域固定读取一代，回收等待所有活跃读者退出 |
| 旧墓碑被标成新版本 | release/build_id 仅取墓碑自身标签；旧纯版本保留 release，无有效版本或 build ID 时不猜当前 mapping |
| 共享库没有安装索引 | 复用同一 status parser 合并本 ABI 的 installed 基础包，并将索引与库一起发布 |
| 源码和归档无限积累 | 更新与源码准备都保留当前修订及最近一个旧修订；延后回收时重新读取当前引用，不删除无关目录 |
| 横屏列表高度检测可跳过 | 必须定位包含 fmt 的专用 viewport tag；缺失、越屏、无效密度及横屏不足 120 dp 都失败 |
| 下一课测试可停留第一课 | Done 与 Learn 不足以通过；点击下一课及重建后均必须找到 cpp_basics 阅读器 tag |

额外边界：同名用户附件各自拥有唯一缓存文件，上传保持原始安全展示名；导入取消不遗留完成文件或半成品。
自动诊断导出同样使用独立 ZIP，避免同一秒内手动反馈与自动崩溃上报覆盖彼此的隐私 profile；导出失败或取消会清理未交付文件。
导入与导出目录使用支持并发创建的 `Files.createDirectories`，避免首次并发操作将 `mkdirs()` 的竞争返回值误判为失败；同名附件用例包含十轮新目录并发导入。
旧共享事务在没有已发布目录时恢复合法 backup，不读取 incomplete staging；迁移后按 ABI、活跃读者和上一代保留边界回收。
临时导入缓存按七天清理，当前恢复的选择不因打开反馈被删除。

## 最小验证命令

确认没有其他会话正在构建后执行；一次性 Gradle 使用 `--no-daemon`，后台检查每次最多 60 秒，只回收本任务创建的进程。

```powershell
./gradlew :core:network:testDebugUnitTest :core:crash:testDebugUnitTest :core:packages:testDebugUnitTest --no-daemon --console=plain
./gradlew :core:logging:testDebugUnitTest --no-daemon --console=plain
./gradlew :feature:settings:testDebugUnitTest --no-daemon --console=plain
./gradlew :core:compile:compileDebugKotlin :feature:packages:compileDebugKotlin --no-daemon --console=plain
./gradlew :app:compileArm64DebugKotlin :app:compileArm64DebugAndroidTestKotlin --no-daemon --console=plain
py -3 -X utf8 -m unittest discover -s tools/testing/tests
py -3 -X utf8 -m unittest discover -s tools/checks
py -3 -X utf8 tools/i18n/check_all.py
py -3 -X utf8 tools/checks/check_documentation.py
git diff --check
```

共享状态回归覆盖：双 ABI、并发导出、固定构建代、取消释放、旧 backup 恢复、非法指针与 UTF-8/大小边界。
反馈回归覆盖：真实 HTTP 不带 device、Saver 保存、同名附件、失败重试、取消清理、实际大小硬限制与过期回收。

## 统一验证记录

2026-11-19 在独立验证工作区执行本轮代码与回归用例：

| 验证 | 实际结果 |
| --- | --- |
| `core:network` 单元测试 | 40 通过 |
| `core:crash` 单元测试 | 24 通过 |
| `core:packages` 单元测试 | 171 通过 |
| `core:logging` 单元测试 | 36 通过 |
| `feature:settings` 单元测试 | 152 通过 |
| Kotlin 单元测试合计 | 423 通过，0 失败、0 跳过 |
| `tools/testing/tests` | 12 通过 |
| `tools/checks` | 60 通过 |
| `core:compile`、`feature:packages` Kotlin 编译 | 通过 |
| App Arm64 Debug 与 AndroidTest Kotlin 编译 | 通过；未运行设备测试 |
| 国际化与文档检查 | 通过；14 当前文档、43 默认帮助、43 英文帮助 |
| `git diff --check` | 通过 |

统一验证期间实际修复了源码回收集合的 Kotlin 数组类型错误，以及首次并发创建附件缓存目录的竞态。保留失败复现与成功断言，没有放宽测试。
初始后台测试曾达到 60 秒上限，冷编译也曾达到前台命令上限；详细日志显示仍在依赖编译或退出阶段。最终完整模块回归以前台模式执行，Debug 编译单独完成，不将超时算作测试通过。
Windows 的 Python 检查须优先使用 Git Bash，而不是 `C:\Windows\System32\bash.exe` 的 WSL 启动器；本轮仅调整检查进程的 PATH，没有改系统配置：

```powershell
$gitRoot = Split-Path (Split-Path (Get-Command git.exe).Source)
$env:PATH = (Join-Path $gitRoot 'bin') + ';' + $env:PATH
py -3 -X utf8 -m unittest discover -s tools/checks
```

新的 Release tag 在旧候选包里不存在，不能用旧 APK 的黑盒结果冒充这轮验收。
真机横竖屏、真实文件选择器、反馈重建，以及长时间运行图形程序期间更新共享库，仍需在包含本轮代码的 APK 上单独记录设备证据。
