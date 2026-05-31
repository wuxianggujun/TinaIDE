# Changelog

> 说明：服务端（后端/部署脚本）相关的变更记录请查看 `server/CHANGELOG.md`；服务器侧的部署文档更新也统一放在 `server/ops/**` 下，避免根 Changelog 混入运维细节。

本文档记录 TinaIDE 项目的版本更新历史，包括新功能、Bug 修复和改进。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## 维护约定（开发者）

### App（TinaIDE）更新记录写在此处

- 本文件 `CHANGELOG.md` 仅记录 TinaIDE App 的版本变更（新增功能 / 修复 / 优化）。
- 如需在提交时记录“文件级别操作”（A/M/D/R），可将 `git diff --cached --name-status` 的输出粘贴到对应版本区块的 `### Changed Files` 小节中（可选）。

### 前端/后端/运维（server）更新写到 `server/CHANGELOG.md`

- `server/` 下的变更（`tina-admin`、`tina-server`、`ops` 等）统一写入 `server/CHANGELOG.md`，不要写到本文件。

### 版本号一致则合并追加，不要滥增版本

- 先从 `version.properties` 读取当前 `versionName`。
- 若本文件已存在该 `versionName` 的版本区块，则把本次变更**合并追加**到该区块中。
- 只有当 `versionName` 确实发生变化（计划发布/升级）时，才创建新的版本区块。

### 新版本可详细，旧版本需精简

- **最新版本**（建议最近 1~3 个版本）允许写得更详细（可包含关键文件名/类名/链接/设计点）。
- **旧版本**应逐步“压缩”为摘要：只保留用户可感知的变化点，避免继续维护大量已删除文件/类的链接与细节。
- 若旧版本内容中引用的文件/类已不存在，可在整理时移除/合并相关条目（保持可读性优先）。

### 不使用“未发布/Unreleased”区块

- 本项目不使用 `Unreleased` / `未发布` 区块。
- 所有变更必须归档到明确的版本号区块（版本号来源：`version.properties` 的 `versionName`）。

## [0.17.6] - 2026-05-31

### Fixed
- 修复 0.17.5 启动即崩溃的 `StackOverflowError`：`OkHttpClientProvider.probe` 在 builder 中绑定 `SmartDns`，而 `SmartDns` 的 lazy initializer 通过 `DohDnsResolver` 又回引 `probe`，同线程递归触发 `SynchronizedLazyImpl` 重入直至 8 MB 主线程栈耗尽。任何走 `probe` 的入口（更新检查、插件市场索引、包索引）都会在启动早期复现。
- `DohDnsResolver` 改用独立的 `OkHttpClient.Builder() + Dns.SYSTEM`，DoH 客户端从此不再依赖 `OkHttpClientProvider`。
- `SmartDns` 由 `val by lazy` 重写为 `object : Dns`，内部 `delegate` 在首次 `lookup()` 时才以双检锁构造；`OkHttpClient` builder 引用 `SmartDns` 不会再触发任何懒加载链。注释中显式声明 `delegate` 构造路径禁止回引 `OkHttpClientProvider.probe`，便于后续维护。

### Changed
- 让本地 `tina.abi` flavor 跟随 Android Studio 注入的目标设备 ABI，避免开发期反复手动切换 flavor。
- 大规模模块化整理与残留清理：移除 `app/src/main/assets/help/*.md` 帮助文档（已迁至各 feature 模块自有资源），同步清理 `MainActivity` / `TinaApplication` / `AndroidManifest` / `lint-baseline` / `app/src/main/cpp` 内的旧引用。
- Linux 环境与 PRoot 一致性继续打磨：发行版安装与 PRoot 启动失败时具备恢复路径；要求复用发行版时元数据完全匹配，避免错位；新增环境健康刷新动作；安装完成提示提供"查看日志/打开终端"快捷入口；记录 Linux 健康检查结果便于排障。

### Verification
- `.\gradlew.bat :core:network:compileDebugKotlin --console=plain`
- `.\gradlew.bat :app:compileArm64DebugKotlin --console=plain`

## [0.17.5] - 2026-05-27

### Added
- 编辑器与"安装帮助"导航优化：在编辑器内可直接跳转到与当前任务相关的安装帮助章节，降低首次配置 Linux 工具链的成本。

### Changed
- 项目级 `.agents/skills/` 知识库新增："tina-ai-tools"、"tina-editor"、"tina-build" 等 skill 文档，便于贡献者快速掌握各模块约定。

### Known Issues
- ⚠️ 启动即 `StackOverflowError`（`SmartDns` ↔ `OkHttpClientProvider.probe` 循环初始化）。已在 0.17.6 修复，受影响的 0.17.5 用户请直接升级 0.17.6。

## [0.17.4] - 2026-05-25

### Changed
- SDL 图形运行配置继续收敛旧 `gui` 内部命名：运行配置字段改为 `sdlOrientation`，新写出的配置不再暴露旧的 `guiOrientation` 字段。
- 运行配置弹窗、SDL Activity 启动参数、编译完成提示和多语言资源 key 统一改为 SDL 命名，降低用户误以为仍存在自研 GUI 绘制接口的可能性。
- 游戏引擎插件文档改名为 `game-engine-plugin-sdl.md`，示例配置同步改为 `sdlOrientation`。

### Fixed
- 旧版 `run_configs.json` 中的 `guiOrientation` 仍可读取，并会在保存时迁移为 `sdlOrientation`，避免用户升级后丢失屏幕方向设置。

### Verification
- `git diff --check`
- `.\gradlew.bat :core:compile:testDebugUnitTest --tests com.wuxianggujun.tinaide.core.compile.RunConfigurationManagerMigrationTest --no-daemon`
- `.\gradlew.bat :app:compileArm64DebugKotlin --no-daemon`
- GitHub Actions `Dev APK Build` 已通过：run `26366034612`。

## [0.17.3] - 2026-05-24

### Changed
- 图形运行时统一迁移到 SDL 输出模式：IDE 内部不再维护自定义 GUI 主机、绘制接口头文件或旧的 `run --gui` 入口，降低用户误用和后续支持成本。
- 示例与文档同步收口到 SDL3 / NativeActivity 模板，避免继续引导用户使用已移除的自定义图形接口。
- Release workflow 延长双 ABI Release APK 构建超时：`x86_64` 构建在 GitHub Actions 上进入后半段后不再被 35 分钟步骤超时提前终止。

### Verification
- GitHub Actions `Dev APK Build` 已在 `dev` 分支通过：run `26361907447`。
- GitHub Actions `Build and Release` 的 `v0.17.2` 失败原因为 `x86_64` Release APK 步骤 35 分钟超时，不是源码编译错误；本版本通过新的 tag 重新触发发布构建。

## [0.17.1] - 2026-05-22

### Changed
- 市场数据源改为 GitHub 仓库注册表：依赖库、插件列表和包信息不再依赖 TinaIDE 自有服务器，降低服务器资源占用，也更适合公开仓库协作。
- 包管理与插件市场增加 GitHub 访问兜底配置：在中国网络环境下可继续通过镜像/代理配置访问注册表，减少直接访问 GitHub 失败导致的市场不可用。
- 项目首页移除公告系统，改为启动时检查 GitHub Release 最新版本，并通过更新对话框提示用户升级。
- Release workflow 边界进一步收口：公开仓库只负责构建 TinaIDE 自身 APK，发布说明直接从本文件对应版本区块生成。
- Release 构建显式复用预编译 PRoot 资源，不再在 GitHub Actions 中重新编译 PRoot 源码，减少远端构建耗时和资源占用。
- Debug/Release 构建步骤增加超时限制，并在 workflow 结束时执行 Gradle 清理，避免构建脚本异常时持续占用后台资源。

### Removed
- 完全移除市场页代码片段功能和 `feature:snippet` 模块，避免继续维护未计划上线的代码片段市场。
- 清理 QQ 登录残留资源与文档入口，公开版不再暴露账号登录、QQ 登录、激活码或许可证激活相关入口。
- 删除项目列表公告横幅、公告弹窗和通知中心相关模型/测试，保留反馈、日志、下载历史、插件系统和包下载能力。

### Verification
- `.\gradlew.bat :app:compileArm64DebugKotlin :core:network:compileDebugKotlin :feature:projectlist:compileDebugKotlin --no-configuration-cache`
- `.\gradlew.bat :app:assembleArm64Debug --no-configuration-cache`
- Debug APK 验证产物：`app/build/outputs/apk/arm64/debug/app-arm64-v8a-debug.apk`
- `v0.17.0` 远端 Release 构建因未显式关闭 PRoot 源码编译而长时间停留在 `Build Release APKs`，已取消以回收 GitHub Actions 资源；本版本作为实际发布版本重新触发。

## [0.16.2] - 2026-05-21

### Changed
- 修正开源仓库 Release workflow：移除 Pro 仓库时代的跨仓库 dispatch 发布链路，公开仓库现在直接构建并发布自身产物。
- Release 构建显式启用双 ABI：固定使用 `-Ptina.allAbi=true`，并强制校验 `arm64-v8a` 与 `x86_64` 两个 APK 都存在。
- APK 与 GitHub Actions artifact 命名统一包含版本号和架构名：`TinaIDE-0.16.2-arm64-v8a.apk`、`TinaIDE-0.16.2-x86_64.apk`。

### Verification
- GitHub Actions `Build and Release` 手动 release 验证通过：run `26229851905`。
- 产物确认：`TinaIDE-0.16.2-arm64-v8a-apk`、`TinaIDE-0.16.2-x86_64-apk`。

## [0.16.0] - 2026-05-21

### Changed
- 发布干净开源基线：公开仓库历史从当前源码重新开始，移除后端、账号登录、QQ 登录、激活码/许可证和会员入口。
- 保留反馈、日志、下载历史、插件系统、包管理和本地 IDE 能力。
- 后端与旧分支/tag 已迁入私有归档仓库，公开仓库仅保留 `main` 与 `dev`。
- 开源自动构建流程改为无签名密钥时也能完成 arm64 Debug APK 验证；签名密钥完整时再继续生成 Release APK。

### Verification
- `.\gradlew.bat :app:assembleArm64Debug --no-daemon`
- `BUILD SUCCESSFUL`

## [0.15.18] - 2026-04-30

### Added
- **AI 工具与聊天链路回归测试闭环**：补齐 `feature:ai` 的 API 流式解析、渠道配置、偏好配置、会话仓储、工具注册/初始化、文件系统、代码分析、诊断、编辑器、执行、项目、重构、搜索和 `AiChatViewModel` 关键路径测试，覆盖取消语义、工具自动执行、摘要续聊、路径边界、网络错误映射和本地 fake client 搜索分支。
- **AI 测试覆盖率报告链路**：为 `feature:ai` 接入并验证 Jacoco 报告，当前 `testDebugUnitTest` 覆盖 `403` 个测试用例，行覆盖率提升到 `96.97%`，分支覆盖率提升到 `80.50%`。
- **项目单元测试覆盖补齐**：为 `app`、`core` 与 `feature` 多模块补齐纯模型、工具函数、仓储、状态机、输出监听、路径校验、CMake 解析和教程/向导等边界测试，当前已注册的核心与功能模块均达到至少 3 个 `src/test` Kotlin 测试文件。

### Changed
- **AI 工具执行稳定性增强**：工具参数解析、工具注册状态、默认回调、项目结构/文件查找/代码行统计、Web/GitHub 搜索、文件系统回调和执行状态查询等路径补齐稳定断言，避免用户停止生成、网络异常或工具失败被误归类为普通成功路径。
- **AI 聊天与 API 错误处理收口**：`chat/listModels`、流式响应、摘要续聊和工具执行继续明确区分 `CancellationException` 与普通错误，保证用户主动停止、协程取消、工具结果提交和后续流式回复状态一致。
- **App 与设置模块质量验证收口**：清理 `app` 与 `feature:settings` 的 ktlint 格式债，收敛常量命名、测试占位类和测试模板字符串，确保相关模块格式检查与单测可稳定通过。
- **Tree-sitter 语言注册生成链路加固**：`app` 的 ktlint 主源码检查显式依赖 `generateTreeSitterLanguageRegistry`，生成模板同步规避空列表与多余空行格式问题，避免 Gradle 隐式依赖校验和 ktlint 检查互相阻塞。
- **跨模块回归验证扩展**：补充 `core:cmake/security/network/ndk/i18n/logging` 与 `feature:help/license/login/membership/output/packages/projectlist/tutorial/viewer/wizard` 等模块的批量单测验证，降低模型默认值、资源回退、文件路径与监听通知的回归风险。

### Fixed
- **Native toolchain 就绪态判断修复**：启动自检和依赖安装页改为使用 `AndroidNativeToolchainManager.isReadyForCurrentAssets()`，同时校验当前激活工具链、安装目录、内置资源版本与哈希，避免旧内置工具链或失效配置被误判为已就绪。
- **内置工具链自动激活策略修复**：安装或复用内置工具链时仅在当前无可用工具链、当前工具链缺失，或当前激活项同为内置工具链时自动切换，避免覆盖用户主动选择的自定义工具链。
- **AI 工具字符串资源转义修复**：转义 AI 工具多语言资源中的单引号示例值，修复 Android 资源合并阶段的 invalid unicode escape 风险。
- **开发者 clangd 测试样例修复**：`compile_commands.json` 与 `.clangd` 样例统一保留工作区占位符 include 路径，确保渲染前后的跨文件样例断言一致。
- **CMake 无引号参数转义修复**：修正 `CMakeLexer` 对无引号参数中反斜杠转义的处理顺序，避免转义字符被提前归一化后影响解析结果。
- **Windows 路径白名单校验修复**：`PathValidator` 统一使用 canonical/absolute 路径兜底比较，修复 Windows 与 Robolectric 环境下合法宿主路径因大小写或路径规范化差异被误拒的问题。

### Verification
- `./gradlew.bat :feature:ai:ktlintCheck :feature:ai:testDebugUnitTest :feature:ai:jacocoTestReport`
- `feature:ai:testDebugUnitTest`：`403 tests`，失败 `0`，错误 `0`，跳过 `0`
- `feature:ai:jacocoTestReport`：行覆盖率 `96.97%`，分支覆盖率 `80.50%`
- `./gradlew.bat :app:mergeArm64DebugResources`
- `./gradlew.bat :app:ktlintCheck :app:testArm64DebugUnitTest :feature:settings:ktlintCheck :feature:settings:testDebugUnitTest :termux-terminal:terminal-emulator:testDebugUnitTest`
- `./gradlew :core:cmake:testDebugUnitTest :core:crash:testDebugUnitTest :core:database:testDebugUnitTest :core:debug:testDebugUnitTest :core:i18n:testDebugUnitTest :core:logging:testDebugUnitTest :core:ndk:testDebugUnitTest :core:network:testDebugUnitTest :core:security:testDebugUnitTest --no-daemon`
- `./gradlew :feature:help:testDebugUnitTest :feature:license:testDebugUnitTest :feature:login:testDebugUnitTest :feature:membership:testDebugUnitTest :feature:output:testDebugUnitTest :feature:packages:testDebugUnitTest :feature:projectlist:testDebugUnitTest :feature:tutorial:testDebugUnitTest :feature:viewer:testDebugUnitTest :feature:wizard:testDebugUnitTest --no-daemon`
- `git diff --check`

## [0.15.17] - 2026-04-30

### Fixed
- **Release APK 打包签名修复**：升级 `core:apk-builder` 的 apksig ASN.1 R8 规则，完整保留运行时注解、解析器/编码器、`@Asn1Class` 模型和 `@Asn1Field` 字段，修复 Release 下 `SubjectPublicKeyInfo is not annotated with Asn1Class` 以及 `Asn1BerParser` 字段排序空指针导致 APK 二次打包失败的问题。
- **Release APK 安装入口兼容性修复**：将文件分享入口收敛到 `core:storage` 的 `TinaFileProvider`，显式绑定 `file_paths` 并手工生成 `content://` URI，避免设备侧 PackageManager 读取不到 `android.support.FILE_PROVIDER_PATHS` meta-data 时安装入口失败。
- **APK 安装器唤起稳定性修复**：安装打包产物时优先匹配系统安装器，向所有可处理安装 Intent 的组件授予 URI 读取权限，并增加 Provider 诊断日志，减少 Release 包点击“安装”后无响应或权限丢失的问题。
- **关于页与日志版本号显示修正**：新增基础版本号读取逻辑，关于页和日志导出显示 `version.properties` 中的基础 `versionCode`，不再显示按 ABI 分包后放大的安装包版本码。

### Changed
- **文件分享链路统一**：`ProjectExporter`、APK 安装/分享和外部打开入口统一复用 `ExternalFileIntents` 生成可分享 URI，Provider 异常会返回国际化错误提示并记录可排查日志。
- **版本号一致性守卫补齐**：Release 构建会校验 `versionName` 与 `versionCode` 的历史规则一致性，并将基础版本码写入 Manifest，减少发版包、日志与崩溃反馈中的版本歧义。
- **R8 规则文档同步**：更新 ProGuard/R8 参考文档，记录 apksig ASN.1 注解反射链、Provider keep 规则和本次 Release 签名/安装失败的排查结论。
- **GitHub Release 校验兼容自定义 Provider**：发版 workflow 的 APK Manifest 校验识别 `core:storage` 中的 `TinaFileProvider` 与标准 AndroidX `FileProvider`，避免 Provider 路径调整后被旧校验脚本拦截发布。

### Verification
- `./gradlew.bat :app:assembleArm64Release --no-daemon --stacktrace`
- `configuration.txt` / `usage.txt` 已确认 apksig ASN.1 解析器、模型字段与 `com.wuxianggujun.tinaide.storage.TinaFileProvider` 未被 R8 删除

## [0.15.4] - 2026-04-29

### Fixed
- **APK 安装入口发布版追踪修复**：将修复版升级到 `0.15.4` / `1505`，避免继续与旧 `0.15.3` 发布包混淆，便于用户、安装包文件名和崩溃反馈明确区分修复前后版本。
- **Release 发版守卫补齐**：GitHub Actions 增加 tag 与 `version.properties` 一致性校验，并在上传前检查最终 APK Manifest 内包含 `androidx.core.content.FileProvider`、`.fileprovider` authority 与 `android.support.FILE_PROVIDER_PATHS` 元数据，防止再次发布缺少安装/分享 Provider 配置的 APK。

### Verification
- `git diff --check`
- `aapt dump badging <release-apk>`
- `aapt dump xmltree <release-apk> AndroidManifest.xml`


## [0.15.3] - 2026-04-29

### Added
- **插件开发体验补齐为“模板 + API + 诊断 + 教程”闭环**：新增内置插件 starter、插件 API 契约与作者教程文档，并补齐插件命令、工作区文件访问、诊断上报、宿主事件分发和插件开发入口，降低从模板创建到调试发布的成本。
- **Linux 发行版自托管运行时进入可验证阶段**：新增 `core:linux-distro` 与 Rootfs bootstrap/health/profile/runtime 支撑代码，补齐 Linux distro 工具脚本、rootfs 健康检查、设置页健康模型和相关测试，为后续自托管工具链安装与恢复打基础。
- **编辑器导航与交互增强**：新增 peek definition 面板、鼠标悬停/快捷键/选择菜单相关测试与 LSP 导航支撑，提升跳转定义、引用查看和硬件键盘场景的稳定性。
- **崩溃日志与插件诊断能力扩展**：新增崩溃日志隐私分类、诊断日志格式化、日志导出策略、插件健康检查与插件加载问题诊断模型，方便定位插件和运行时故障。

### Changed
- **Android 端工具链与打包链路更新到 arm64 v0.2.4 patched**：更新内置 Tina toolchain 元数据、补充多变体目录与 linker 修复补丁，Release arm64 APK 版本递增到 `0.15.3` / `1504`。
- **帮助中心与教程内容同步扩展**：更新快速开始、构建项目、编辑器基础、Git、Linux 存储和设置总览等帮助页，并新增插件快速开始、插件 API 指南、插件模板设计与维护文档。
- **插件运行时和权限边界收口**：强化 manifest 校验、路径校验、网络主机规则、版本比较、LSP 插件安装和脚本 API 模块，减少插件侧重复实现与宿主侧状态不一致。
- **PRoot/rootfs 相关实现重构**：将旧 Alpine/rootfs 方案逐步迁移到通用 Linux distro runtime，移除历史 Docker rootfs 构建方案与旧 profile importer，统一 guest package manager 支撑能力。

### Fixed
- **Release warning 清理**：修复 `NativeCrashHandler` 清理崩溃日志时的恒真条件 warning，保留待上传 tombstone 时不再依赖重复空判断。
- **QQ 登录回调兼容 warning 清理**：对 QQ SDK 必需的旧式 `onActivityResult` 入口增加局部 deprecation 抑制，避免 Release Kotlin 编译噪声。
- **R8 consumer rules 缺失 warning 清理**：为 `core:linux-distro` 补齐 `consumer-rules.pro` 占位文件，保持库模块 Release 混淆配置一致。

### Verification
- `pwsh ./tools/build-apk.ps1 -Variant release -Abi arm64`
- `apksigner verify --verbose --print-certs TinaIDE-0.15.3-arm64-v8a.apk`
- `zipalign -c -p -v 4 TinaIDE-0.15.3-arm64-v8a.apk`


## [0.15.2] - 2026-04-22

### Added
- **帮助中心扩展为“快速开始 + 设置索引 + 排障说明”三层结构**：
  - 新增 [`about-and-logs.md`](app/src/main/assets/help/about-and-logs.md)、[`ai-settings.md`](app/src/main/assets/help/ai-settings.md)、[`appearance-settings.md`](app/src/main/assets/help/appearance-settings.md)、[`compiler-settings.md`](app/src/main/assets/help/compiler-settings.md)、[`developer-options.md`](app/src/main/assets/help/developer-options.md)、[`editor-settings.md`](app/src/main/assets/help/editor-settings.md)、[`feedback-guide.md`](app/src/main/assets/help/feedback-guide.md)、[`git-settings.md`](app/src/main/assets/help/git-settings.md)、[`keyboard-settings.md`](app/src/main/assets/help/keyboard-settings.md)、[`linux-storage.md`](app/src/main/assets/help/linux-storage.md)、[`lsp-settings.md`](app/src/main/assets/help/lsp-settings.md)、[`package-manager.md`](app/src/main/assets/help/package-manager.md)、[`plugins-settings.md`](app/src/main/assets/help/plugins-settings.md)、[`profile-edit.md`](app/src/main/assets/help/profile-edit.md)、[`project-settings.md`](app/src/main/assets/help/project-settings.md)、[`settings-overview.md`](app/src/main/assets/help/settings-overview.md)、[`terminal-settings.md`](app/src/main/assets/help/terminal-settings.md) 与 [`terminal-troubleshooting.md`](app/src/main/assets/help/terminal-troubleshooting.md)，并更新 [`HelpRepository.kt`](feature/help/src/main/java/com/wuxianggujun/tinaide/core/help/HelpRepository.kt) 与多语言字符串：帮助中心现在把设置项、常见运维动作和反馈入口串成完整索引，用户不需要在设置页和历史文档之间来回找入口。
- **文件树补齐插件图标贡献与路径过滤公共层**：
  - 新增 [`FileTreeIconResolver.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FileTreeIconResolver.kt)、[`PluginFileIconResolver.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginFileIconResolver.kt) 与 [`ProjectPathFilters.kt`](core/lang/src/main/java/com/wuxianggujun/tinaide/core/lang/ProjectPathFilters.kt)，并补充对应测试：工程树现在支持插件按文件名/扩展名贡献图标，同时把搜索、监听、同步共用的高噪声目录过滤规则抽成单一来源，避免各模块各写一套排除名单。
- **插件状态与市场安装态补齐公共快照模型**：
  - 新增 [`PluginStateSnapshot.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginStateSnapshot.kt)、[`PluginLogSource.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginLogSource.kt)、[`PluginMarketplaceInstallState.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/marketplace/PluginMarketplaceInstallState.kt)、[`PluginMarketplaceSelectionSupport.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/marketplace/PluginMarketplaceSelectionSupport.kt) 与 [`MarketScreenPluginStateSupport.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreenPluginStateSupport.kt)：插件安装态、启用态、版本映射、可更新状态和宿主日志来源都收口成显式模型，后续设置页、市场页和宿主桥接可以直接复用，不再各自缓存一份半同步状态。

### Changed
- **编译入口继续收口到显式 Operation / LaunchSpec 模型**：
  - 更新 [`CompileProjectUseCase.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCase.kt)、[`CompileActionsHelper.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompileActionsHelper.kt)、[`CompileUiEventObserver.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompileUiEventObserver.kt)、[`DebugViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/DebugViewModel.kt)、[`GuiHostActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/gui/GuiHostActivity.kt) 与 [`ExternalSdlActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/sdl/ExternalSdlActivity.kt)，并新增 [`LaunchEnvironment.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/LaunchEnvironment.kt) 与 [`NativeLaunchEnvironment.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/runtime/NativeLaunchEnvironment.kt)：Run / Debug / GUI / SDL 启动参数现在统一走显式 launch 描述模型，额外环境变量的校验、透传与回滚也收敛到公共入口，减少布尔补丁和分叉逻辑。
- **文件树刷新与监听链路改为按子树增量重建**：
  - 更新 [`FileManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/file/FileManager.kt)、[`IFileWatchService.kt`](core/common/src/main/java/com/wuxianggujun/tinaide/file/IFileWatchService.kt)、[`FileTreeState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FileTreeState.kt)、[`FileTree.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FileTree.kt)、[`FileTreeItem.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FileTreeItem.kt)、[`FileTreeContextMenu.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FileTreeContextMenu.kt)、[`FileTreeModels.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FileTreeModels.kt)、[`ProjectSearchEngine.kt`](core/search/src/main/java/com/wuxianggujun/tinaide/search/ProjectSearchEngine.kt)、[`ProjectSyncManager.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/ProjectSyncManager.kt) 与 [`RsyncSyncProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/RsyncSyncProvider.kt)：工程树现在会跳过 `build`、`.git`、`node_modules` 等高噪声目录，文件监听改为返回可释放注册句柄，目录刷新改为按受影响子树重算，避免大项目下重复节点、全树抖动和无效递归扫描。
- **插件管理、市场页与设置页统一消费单一状态快照**：
  - 更新 [`PluginManager.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginManager.kt)、[`PluginLogManager.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginLogManager.kt)、[`PluginMarketplaceRepository.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/marketplace/PluginMarketplaceRepository.kt)、[`PluginMarketplaceViewModel.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/marketplace/PluginMarketplaceViewModel.kt)、[`MarketScreenViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreenViewModel.kt)、[`PluginsSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/PluginsSettingsSection.kt)、[`PluginMarketplaceSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/PluginMarketplaceSection.kt) 与 [`PluginEditorThemeRegistry.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/theme/PluginEditorThemeRegistry.kt)：安装/启用/版本信息改由 `pluginStateFlow` 派生，市场详情页改用 `pluginId` 追踪选中项，插件开关/卸载/安装增加宿主侧结构化日志，主题、图标与 APK 导出贡献都复用同一份启用态视图。
- **帮助文档与项目文档目录继续收口为“可维护索引 + 活跃说明”**：
  - 更新 [`docs/README.md`](docs/README.md)、[`docs/plugins/README.md`](docs/plugins/README.md)、[`docs/testing/README.md`](docs/testing/README.md)、[`docs/planning/Feature-Roadmap.md`](docs/planning/Feature-Roadmap.md)、[`docs/plugins/Plugin-Roadmap.md`](docs/plugins/Plugin-Roadmap.md)、[`docker/tinaide-pkg/README.md`](docker/tinaide-pkg/README.md) 以及 `app/feature/help` 下多篇帮助文档：保留仍然有效的入口说明，把面向一次性分析、旧方案评审和历史样例的内容从主索引中移出，减少文档目录长期堆积的噪声。

### Fixed
- **修复 launchEnvironment 只在终端路径生效、GUI / SDL / Debug 丢参的问题**：
  - 更新 [`CompileProjectUseCase.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCase.kt)、[`CompileActionsHelper.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompileActionsHelper.kt)、[`CompileUiEventObserver.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompileUiEventObserver.kt)、[`DebugSessionService.kt`](core/debug/src/main/java/com/wuxianggujun/tinaide/core/debug/DebugSessionService.kt)、[`PRootDebugger.kt`](core/debug/src/main/java/com/wuxianggujun/tinaide/core/debug/PRootDebugger.kt)、[`GuiHostActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/gui/GuiHostActivity.kt)、[`ExternalSdlActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/sdl/ExternalSdlActivity.kt) 与对应测试：AI/运行配置传入的环境变量现在会分别透传到终端 shell、PRoot 调试进程以及 GUI/SDL 宿主进程，并在 GUI/SDL 退出时恢复原环境，避免“这次运行设置残留到下次运行”的隐性污染。
- **修复插件详情页与市场安装态容易因本地状态变化而陈旧的问题**：
  - 更新 [`MarketScreenViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreenViewModel.kt)、[`PluginsSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/PluginsSettingsSection.kt)、[`PluginsSettingsSectionSupport.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/PluginsSettingsSectionSupport.kt) 与对应测试：插件详情页现在会在插件被卸载后自动关闭，市场页会监听安装态流实时刷新“已安装/可更新”标记，避免旧对象残留导致的 UI 错判。
- **修复文件树与同步链路对高噪声目录过度响应的问题**：
  - 更新 [`FileManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/file/FileManager.kt)、[`ProjectSearchEngine.kt`](core/search/src/main/java/com/wuxianggujun/tinaide/search/ProjectSearchEngine.kt)、[`ProjectSyncManager.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/ProjectSyncManager.kt) 与 [`RsyncSyncProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/RsyncSyncProvider.kt)：`build/`、`.cxx/`、`node_modules/` 等目录不再把文件树刷新、项目搜索和远程同步一并拖慢，减少了大工作区里的无效监听和同步噪声。

### Removed
- **清理过期设计分析稿与旧插件样例资产**：
  - 删除 `docs/` 下大量历史分析文档、调试记录和旧样例插件清单（包括 `docs/API-Reference.md`、`docs/testing/*`、`docs/plugins/sample-*` 等）：文档主目录只保留当前仍会被维护和引用的说明，减少版本迭代时需要持续跟踪的历史包袱。

### 技术细节
- 主要变更：`core/compile/*`、`core/debug/*`、`core/plugin/*`、`core/search/*`、`core/lsp/*`、`feature/help/*`、`feature/settings/*`、`app/ui/compose/components/FileTree*`
- 文档整理：帮助中心新增 18 篇设置/排障文档，`docs/` 目录集中清理一批过期分析稿与历史示例资源
- 版本对齐：`0.15.2`

## [0.14.98] - 2026-04-21

### Added
- **新增会员中心与爱发电扫码购买入口**：
  - 新增 [`feature/membership/`](feature/membership/) 一组会员模块，并更新 [`ProfileScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/profile/ProfileScreen.kt)、[`TinaServerApi.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/api/TinaServerApi.kt)、[`strings.xml`](core/i18n/src/main/res/values/strings.xml) 与 [`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：已登录用户现在可从“我的”页进入会员中心，查看会员方案、拉起爱发电二维码并轮询支付结果；未登录状态会直接引导登录。
- **新增存储空间清理总览与明细页**：
  - 新增 [`StorageCleanupManager.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/StorageCleanupManager.kt)、[`StorageCleanupSupport.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/StorageCleanupSupport.kt)、[`StorageCleanupViewModel.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/StorageCleanupViewModel.kt)、[`StorageCleanupScreen.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/StorageCleanupScreen.kt)、[`StorageCleanupDetailScreen.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/StorageCleanupDetailScreen.kt) 与对应测试：设置页新增存储清理入口，可按类别查看构建中间产物、下载缓存、PRoot 缓存、导出缓存与日志占用，并支持一键清理或按路径精细清理；删除范围严格受白名单限制，不会触碰 `projects/` 等用户数据。
- **新增 AI 自定义渠道管理 UI 与本地渠道表**：
  - 新增 [`AiChannelDao.kt`](core/common/src/main/java/com/wuxianggujun/tinaide/core/ai/db/AiChannelDao.kt)、[`AiChannelEntity.kt`](core/common/src/main/java/com/wuxianggujun/tinaide/core/ai/db/AiChannelEntity.kt)、[`AiChannelRepository.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/channel/AiChannelRepository.kt)、[`AiChannelManagementUi.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/AiChannelManagementUi.kt)：BYOK 模式改为支持多渠道增删改查、激活与独立 API Key 存储，设置页可以直接管理多个供应商配置。
- **新增终端 APK 导出模板链路与插件模板扩展点**：
  - 新增 [`TerminalApkExportResolver.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/apk/TerminalApkExportResolver.kt)、[`ApkExportTemplateOption.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/apk/ApkExportTemplateOption.kt)、[`TrackedInputCollector.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/artifact/TrackedInputCollector.kt) 与对应测试，并更新 [`ApkBuilder.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkBuilder.kt)、[`ApkTemplateType.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkTemplateType.kt)、[`MainActivityDialogs.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityDialogs.kt)、[`ProjectApkExportSupportResolver.kt`](core/project/src/main/java/com/wuxianggujun/tinaide/project/ProjectApkExportSupportResolver.kt) 与 [`PluginModels.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginModels.kt)：项目 APK 导出现在可以识别终端型可执行项目，支持把 ELF 可执行文件和运行库一起打进终端模板 APK；插件清单也新增 APK 导出模板贡献入口，可按项目类型扩展模板选择。

### Changed
- **AI 模块收口为“官方网关 + 多渠道 BYOK”双路径，并补齐 VIP 守卫**：
  - 更新 [`AiPreferences.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/config/AiPreferences.kt)、[`AiConfig.kt`](core/config/src/main/java/com/wuxianggujun/tinaide/core/config/ai/AiConfig.kt)、[`AiConfigStrategy.kt`](core/config/src/main/java/com/wuxianggujun/tinaide/core/config/ai/AiConfigStrategy.kt)、[`AiSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/AiSettingsSection.kt)、[`AiSettingsBridgeImpl.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/settings/AiSettingsBridgeImpl.kt)、[`DefaultVipGate.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/DefaultVipGate.kt) 与 [`UserContentDatabase.kt`](core/database/src/main/java/com/wuxianggujun/tinaide/database/user/UserContentDatabase.kt)：老数据迁移不再凭遗留 API Key 隐式切到 BYOK，非 VIP 保存 BYOK 配置时会回退官方网关；本地配置、数据库与设置页围绕访问模式和活动渠道重新组织。
- **流式聊天、鉴权与重试链路拆分为可测试组件**：
  - 更新 [`AiApiClient.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/api/AiApiClient.kt) 与 [`AiChatViewModel.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/viewmodel/AiChatViewModel.kt)，并新增 [`AuthStrategy.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/api/AuthStrategy.kt)、[`SseReader.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/api/stream/SseReader.kt)、[`OpenAiCompatibleStreamParser.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/api/stream/OpenAiCompatibleStreamParser.kt)、[`ChatStreamController.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/stream/ChatStreamController.kt)、[`RetryCoordinator.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/retry/RetryCoordinator.kt) 与 [`ToolExecutionCoordinator.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/ToolExecutionCoordinator.kt)：Gateway JWT 与 BYOK Bearer 鉴权改为策略化实现，SSE 解析、流式节流、自动重试与工具调度从超大 ViewModel / Client 中拆出，后续更容易回归测试和继续扩展。
- **原生可执行文件运行改为先 stage-copy，再启动独立终端会话**：
  - 更新 [`NativeExecutableRunner.kt`](core/common/src/main/java/com/wuxianggujun/tinaide/core/util/NativeExecutableRunner.kt)、[`CompileProjectUseCase.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCase.kt)、[`TerminalRunShellCommand.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/TerminalRunShellCommand.kt)、[`TerminalActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/TerminalActivity.kt)、[`TerminalSessionManager.kt`](feature/terminal/src/main/java/com/wuxianggujun/tinaide/terminal/session/TerminalSessionManager.kt) 与 [`FileManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/file/FileManager.kt)：Run/Terminal 现在会在工作目录下 staging 可执行文件、统一拼装 shell 命令并记录会话元数据，降低直接运行源文件路径导致的 shebang、权限位和动态库前缀问题。
- **APK 导出模板与权限配置继续迭代**：
  - 更新 [`ApkExportPermissionProfile.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkExportPermissionProfile.kt)、[`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt)、[`template-checksums.txt`](app/src/main/assets/apk_templates/template-checksums.txt)、[`template-native-activity.apk`](app/src/main/assets/apk_templates/template-native-activity.apk)、[`template-sdl3.apk`](app/src/main/assets/apk_templates/template-sdl3.apk) 与模板图标资源：导出面板、模板校验和与预置模板资产同步更新，配套测试也随之调整。
- **项目设置、插件与文档索引补齐终端 APK 导出能力描述**：
  - 更新 [`ProjectMetadata.kt`](core/model/src/main/java/com/wuxianggujun/tinaide/project/ProjectMetadata.kt)、[`ProjectSettingsSectionSupport.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/ProjectSettingsSectionSupport.kt)、[`PluginManager.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginManager.kt)、[`docs/README.md`](docs/README.md)、[`docs/design/README.md`](docs/design/README.md) 与 [`docs/plugins/README.md`](docs/plugins/README.md)：项目元数据与设置页增加 `TERMINAL` 导出类型，插件管理器可以解析并列出 APK 导出模板贡献，文档目录也同步清理过期审计稿并补齐新模板说明。

### Fixed
- **修复 AI 网关与 BYOK 切换的若干边界问题**：
  - 更新 [`AiConfigTest.kt`](core/config/src/test/java/com/wuxianggujun/tinaide/core/config/ai/AiConfigTest.kt)、[`AiConfigStrategyTest.kt`](core/config/src/test/java/com/wuxianggujun/tinaide/core/config/ai/AiConfigStrategyTest.kt)、[`AiSettingsSectionSupportTest.kt`](feature/settings/src/test/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/AiSettingsSectionSupportTest.kt) 与 [`AiPreferences.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/config/AiPreferences.kt)：避免会员过期后继续沿用自定义渠道、旧配置误判为 BYOK，以及 API Key 清洗、模型选择和访问模式决策不一致的问题。
- **修复终端与编辑器若干交互细节**：
  - 更新 [`SelectionMagnifierController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionMagnifierController.kt)、[`TerminalSession.java`](external/termux-terminal/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java)、[`TerminalSessionInfo.kt`](core/common/src/main/java/com/wuxianggujun/tinaide/core/terminal/TerminalSessionInfo.kt)、[`TinaTerminalSessionClient.kt`](feature/terminal/src/main/java/com/wuxianggujun/tinaide/terminal/session/TinaTerminalSessionClient.kt)、[`SpotlightTooltipChrome.kt`](feature/tutorial/src/main/java/com/wuxianggujun/tinaide/tutorial/spotlight/SpotlightTooltipChrome.kt) 与 [`DevEditorTestHost.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/DevEditorTestHost.kt)：继续收敛终端状态同步、选区放大镜与少量 Compose 调试/引导交互的边界行为，减少运行中 UI 偏移和状态错乱。
- **修复编译缓存把旧产物误判为最新产物的问题**：
  - 更新 [`BuildPlanner.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/pipeline/BuildPlanner.kt)、[`FingerprintCalculator.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/artifact/FingerprintCalculator.kt)、[`BuildFingerprint.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/artifact/BuildFingerprint.kt)、[`ArtifactSpec.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/artifact/ArtifactSpec.kt)、[`MakeStrategy.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/strategy/MakeStrategy.kt)、[`CMakeStrategy.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/strategy/CMakeStrategy.kt) 与对应测试：缓存复用现在会同时校验 tracked inputs、输出路径和产物类型，`Makefile` / `CMakeLists.txt` / 深层源码变化都会正确触发重建，不再把旧构建结果误当成当前产物。
- **修复 APK 打包面板在模板缺失场景下的错误退出路径**：
  - 更新 [`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt)：当模板选项为空时，对话框现在会正确回滚构建状态并显示错误消息，不再因为非法 `return@launch` 造成 Kotlin 编译失败。

### 技术细节
- 主要变更：`feature/membership/*`、`feature/ai/*`、`feature/settings/*`、`core/storage/*`、`core/compile/*`、`core/apk-builder/*`、`core/plugin/*`
- 设计文档：新增 [`AI-Channel-Audit-2026-04-20.md`](docs/design/AI-Channel-Audit-2026-04-20.md) 与 [`Afdian-Payment-Integration-Design.md`](docs/design/Afdian-Payment-Integration-Design.md)
- 版本对齐：`0.14.98`

## [0.14.96] - 2026-04-15

### Added
- **新增编辑器渲染性能快照与开发者底部性能页**：
  - 新增 [`EditorPerformanceContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorPerformanceContent.kt)，并更新 [`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt)、[`BottomPanelTypes.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanelTypes.kt)、[`EditorRenderEngine.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderEngine.kt)、[`TinaEditor.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditor.kt)：编辑器现在可以输出渲染帧性能快照，并在开发者底部面板直接查看缓存命中、扫描耗时和当前渲染负载。
- **新增 text-engine 文本扫描内核抽象与原生实现链路**：
  - 新增 [`TextScanKernel.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/TextScanKernel.kt)、[`NativeTextScanKernel.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/NativeTextScanKernel.kt)、[`NativeLineIndexKernel.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/NativeLineIndexKernel.kt)、[`TextEngineNativeBridge.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/TextEngineNativeBridge.kt) 以及 [`core/text-engine/src/main/cpp/`](core/text-engine/src/main/cpp/) 下的一组原生实现：长文本扫描与行索引链路现在具备 Kotlin / Native 双后端，为后续继续压缩热路径成本打基础。
- **编辑器标签页加载指示器统一到 TabBar 底部**：
  - 更新 [`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt)、[`EditorTab.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorTab.kt)、[`EditorTabBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/EditorTabBar.kt) 与 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：每个 tab 的加载状态上浮到容器统一管理，TabBar 据此为活跃 tab 播放扩散指示器动画；同时移除单个 `EditorTab` 自己的底部选中指示器实现，避免选中/加载两个动画来源在同一位置冲突。
- **APK 导出新增打包权限管理面板**：
  - 更新 [`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt)、[`strings.xml`](core/i18n/src/main/res/values/strings.xml) 与 [`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：APK 导出对话框新增"管理权限"入口，弹出底部 BottomSheet 可按分类勾选内置权限，面板首屏直接显示"已选 N 项（含 M 项高风险）"汇总；模板侧依然不内置固定权限，全部写入仅发生在本次导出的 APK Manifest。

### Changed
- **构建脚本第三轮第 1 步完成，App 版本管理已迁入 build-logic 插件**：
  - 新增 [`TinaAppVersioning.kt`](build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaAppVersioning.kt) 与 [`TinaAndroidAppVersioningPlugin.kt`](build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaAndroidAppVersioningPlugin.kt)，并更新 [`build.gradle.kts`](build-logic/convention/build.gradle.kts) 与 [`build.gradle.kts`](app/build.gradle.kts)：`version.properties` 的默认补全、版本读取、release 前自动递增与任务挂接统一收口到 `tina.android.app.versioning`，`app/build.gradle.kts` 回退为声明式消费版本信息与业务构建配置。
- **构建脚本第三轮第 2 步完成，tina-toolchain 资产校验已迁入 build-logic 插件**：
  - 新增 [`TinaToolchainAssetsVerification.kt`](build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaToolchainAssetsVerification.kt) 与 [`TinaAndroidAppToolchainAssetsPlugin.kt`](build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaAndroidAppToolchainAssetsPlugin.kt)，并更新 [`build.gradle.kts`](build-logic/convention/build.gradle.kts) 与 [`build.gradle.kts`](app/build.gradle.kts)：`verifyTinaToolchainAssets` 任务体以及 `assemble*`/`bundle*`/`install*` 挂接统一收口到 `tina.android.app.toolchain.assets`，`app/build.gradle.kts` 不再内联 `current.properties` 读取、sha256 入口校验、legacy archive 拦截与任务挂接逻辑。
- **构建脚本第三轮第 3 步完成，Tree-sitter 同步/注册生成链路迁入 build-logic 插件，同时修复 registry 生成空 entries 的回归**：
  - 新增 [`TinaTreeSitterSupport.kt`](build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaTreeSitterSupport.kt)、[`TinaTreeSitterQueriesSync.kt`](build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaTreeSitterQueriesSync.kt)、[`TinaGenerateTreeSitterLanguageRegistryTask.kt`](build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaGenerateTreeSitterLanguageRegistryTask.kt) 与 [`TinaAndroidAppTreeSitterPlugin.kt`](build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaAndroidAppTreeSitterPlugin.kt)，并更新 [`build.gradle.kts`](build-logic/convention/build.gradle.kts) 与 [`build.gradle.kts`](app/build.gradle.kts)：`syncTreeSitterQueries`、`generateTreeSitterLanguageRegistry`、主源集 `srcDir` 注入与 `preBuild` 挂接统一收口到 `tina.android.app.treesitter`；`app/build.gradle.kts` 不再内联 Tree-sitter 下载/解压、grammar 绑定解析、Kotlin 注册表拼装逻辑，同时移除相关 `java.net.URI` / `ZipInputStream` / `JFile` 冗余 import。
  - grammar 语言枚举改为直接从 [`core/tree-sitter/build.gradle.kts`](core/tree-sitter/build.gradle.kts) 所在 project 的 `implementation` 配置读取（`:core:tree-sitter` 为单一数据源），并通过 `evaluationDependsOn(":core:tree-sitter")` 确保 configure-on-demand 下也能拿到依赖；顺手修复了 registry 在 grammar 依赖迁到 `:core:tree-sitter` 后一直生成空 `entries` 的回归（现在 `GeneratedTreeSitterLanguageRegistry.entries` 会完整枚举 16 个 grammar）。
- **构建脚本第三轮第 4 步完成，release R8 mapping 备份/上传链路迁入 build-logic 插件（release 默认开启）**：
  - 新增 [`TinaMappingFileBackup.kt`](build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaMappingFileBackup.kt)、[`TinaMappingFileUpload.kt`](build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaMappingFileUpload.kt) 与 [`TinaAndroidAppMappingPlugin.kt`](build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaAndroidAppMappingPlugin.kt)，并更新 [`build.gradle.kts`](build-logic/convention/build.gradle.kts) 与 [`build.gradle.kts`](app/build.gradle.kts)：`backupMappingFiles`、`uploadMappingFiles` 任务以及 `assemble*Release` / `bundle*Release` 的 `finalizedBy` 挂接统一收口到 `tina.android.app.mapping`；`app/build.gradle.kts` 不再内联 gzip 压缩 + multipart 上传实现，同时清理 `DataOutputStream` / `ByteArrayOutputStream` / `GZIPOutputStream` / `HttpURLConnection` / `URL` / `LocalDateTime` / `DateTimeFormatter` 以及 `appVersionCode` / `appVersionName` 的 `by extra` 冗余 import 与本地变量。
  - 新增可配置 gradle 属性：`tina.releaseMapping.enabled`（默认 `true`，即 release 默认开启备份与上传）、`tina.releaseMapping.serverUrl`（默认 `https://tinaide.wuxianggujun.com`）；当 `tina.releaseMapping.enabled=false` 时插件会跳过 `finalizedBy` 挂接并让两个任务 `onlyIf` 直接 no-op，保持与旧行为兼容。
- **MainActivity 继续按“高价值低风险”策略收口 LSP 导航与构建对话框装配，主界面职责进一步变薄**：
  - 新增 [`LspNavigationDelegate.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/LspNavigationDelegate.kt)、[`MainActivityScreenState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityScreenState.kt)、[`MainActivityBuildUiStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityBuildUiStateTest.kt)、[`MainActivityLocationDialogStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityLocationDialogStateTest.kt)、[`LspNavigationDelegateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/LspNavigationDelegateTest.kt) 与 [`MainActivityNavigationHelperTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/MainActivityNavigationHelperTest.kt)，并更新 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`MainActivityDialogs.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityDialogs.kt)、[`MainActivityNavigationHelper.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityNavigationHelper.kt)、[`MainActivityHostCommandExecutor.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/commands/MainActivityHostCommandExecutor.kt)：LSP 跳转结果处理、运行配置/APK 打包状态、部分快捷键和命令执行装配继续从 Activity 主体中拆出，保留 Compose 组合层的前提下减少命令式逻辑噪声。
- **MainActivity 再次收口顶栏/侧滑栏回调与 Compose 状态桥接，继续减少 Activity 装配噪声**：
  - 更新 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`DrawerContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/DrawerContent.kt)、[`MainActivityDialogs.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityDialogs.kt) 与 [`LspNavigationDelegate.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/LspNavigationDelegate.kt)：新增 `MainActivityComposeStateBridge` 统一承接 `BottomPanelDragState`、`FileTreeState` 的 Activity 级桥接；顶栏回调改为 `rememberMainActivityTopBarCallbacks(...)` 集中装配；侧滑栏改用 `DrawerFileCallbacks`、`DrawerGitCallbacks`、`DrawerAiCallbacks` 分组回调；LSP 跳转绑定改为 `lspNavigationDelegate.bind(...)` 一行收口；`PluginEditorBridgeHolder` 注册再拆成独立的 Compose 绑定/解绑 helper，并在 `onDispose` 时自动 `clear()`；同时把底部一整段对话框装配收进 `MainActivityDialogsHost(...)`，并新增 `MainActivityEditorActionBridge` 承接搜索结果跳转与快捷键分发；随后再把 `FileTreeState` 从 `MainActivityComposeStateBridge` 直接持有收窄为 `MainActivityFileTreeActionBridge`，编译产物同步与工程树 reveal 改走动作桥；继续把 `BottomPanelDragState` 也收窄为 `BottomPanelController` + `MainActivityBottomPanelActionBridge`，让编译 UI、AI 执行链、诊断导航和宿主命令执行都不再直接依赖 Compose 运行态对象，并删掉仅剩桥接用途的 `MainActivityComposeStateBridge`，继续按文档要求减少 Activity 对 Compose 运行态对象的直接持有。
- **编译链也继续收口活动文件语义，core 模块不再自己用 tab 列表反推当前编辑文件**：
  - 更新 [`IEditorTabProvider.kt`](core/common/src/main/java/com/wuxianggujun/tinaide/editor/IEditorTabProvider.kt)、[`CompileProjectUseCase.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCase.kt)、[`IEditorTabProviderTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/editor/IEditorTabProviderTest.kt) 与 [`AGENTS.md`](AGENTS.md)：`IEditorTabProvider` 新增默认 `getActiveFile()` 窄语义，编译链直接复用该入口，不再在 `CompileProjectUseCase` 里手动组合 `getActiveTabId() + getOpenTabs()`；同时把历史遗留 `LOCALAPPDATA/TinaIDE/gradle-out` 目录列为可直接删除且禁止再次依赖的构建垃圾目录。
- **`MainScreen` 开始进入第二轮宿主拆分，先收口顶栏装配块**：
  - 更新 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)：`Scaffold` 顶栏区域改为 `MainActivityTopBarHost(...)` 宿主入口，顶栏回调装配与 `MainActivityTopBar(...)` 渲染不再直接堆在 `MainScreen()` 主体里，为后续继续抽 `BottomPanelHost` / `DrawerHost` 保持清晰边界。
  - 更新 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)：`MainScreen()` 内容区改为 `MainActivityBottomPanelHost(...)` 宿主入口，`BoxWithConstraints + EditorContainer + BottomPanel` 底部区域装配不再直接堆在主体里，继续为后续抽 `DrawerHost` 收口边界。
  - 更新 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)：`BackHandler + SwipeableDrawer + DrawerContent + Scaffold` 抽屉壳子改为 `MainActivityDrawerHost(...)` 宿主入口，`MainScreen()` 进一步只保留状态声明与区域编排。
- **项目页补齐公共项目目录权限入口，打开已有项目不再依赖新建流程兜底**：
  - 更新 [`ProjectScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/project/ProjectScreen.kt)、[`strings.xml`](core/i18n/src/main/res/values/strings.xml) 与 [`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：项目页在未授予文件管理权限时显示直接授权入口，已有项目卡片点击与 `OPEN` 动作统一走权限门禁；授权成功后自动刷新列表，公共目录项目可以直接显示并打开。
- **底部面板继续收窄常驻 Prefs 观察面，非活跃状态不再全时订阅调试条与诊断设置**：
  - 更新 [`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt)：`debugToolbarPositionFlow` 仅在调试会话活跃时订阅，`devDiagnosticsSettingsFlow` 仅在开发者选项启用时订阅，保持面板行为不变，同时减少普通编辑态下的无效配置流观察。
- **AI 工具入口与插件桥接继续收窄活动编辑器语义，外层不再自己拼“当前文件 + 当前选区 + 活动 tab 文本编辑”**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`EditorToolCallbacksImpl.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/EditorToolCallbacksImpl.kt)、[`AiToolsIntegrationManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/AiToolsIntegrationManager.kt)、[`TinaPluginEditorBridge.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/TinaPluginEditorBridge.kt)、[`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt) 与 [`TinaPluginEditorBridgeTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/TinaPluginEditorBridgeTest.kt)：状态层新增活动文件/选区组合快照、插件桥接专用活动上下文与活动页文本编辑入口，AI 工具回调和插件桥接不再自己组合 `ActiveEditorHandle + 文本/选区快照 + tabId` 来推导当前编辑器语义。
- **保存当前文件入口也继续收窄到状态层的活动保存目标语义**：
  - 更新 [`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：`saveCurrentFile()` 不再直接拿 `ActiveEditorHandle` 再自己取 `tabId/file`，改为消费状态层的 `ActiveSaveTargetResult`，只收口“当前保存目标判定”语义，不改保存执行链。
- **测试侧也继续移除对旧活动句柄 getter 的反向依赖**：
  - 更新 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)、[`DevEditorTestInfrastructureTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/DevEditorTestInfrastructureTest.kt) 与 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)：活动 tab/file 断言统一改走插件活动上下文或可编辑编辑器快照，`getActiveEditorHandleOrNull()` 收紧为状态层私有实现，未再使用的 `getActiveEditableEditorHandleOrNull()` 与 `getActiveEditorLanguageIdOrNull()` 一并移除。
- **书签与活动会话告警结果也继续去掉整块活动编辑器句柄暴露**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：书签上下文/目标改为直接返回 `file + line`，活动会话告警改为直接返回 `tabId + file + 告警状态`，外层不再经由 `ActiveEditorHandle` 二次拆解。
- **活动可编辑编辑器快照也继续去掉句柄暴露，只保留格式化入口真正需要的数据**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：`ActiveEditableEditorSnapshot` 由 `handle + text` 收窄为 `file + text`，格式化入口不再通过活动句柄间接取文件。
- **保存全部通知目标与内部可编辑绑定结果也继续去句柄化，状态层不再保留通用 `ActiveEditorHandle`**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：保存全部后的通知目标直接复用 `ActiveSaveTarget`，可编辑编辑器内部绑定结果也只保留 `file + callback`，不再通过通用活动句柄中转 `tabId/file`。
- **EditorContainer / BottomPanel 继续收窄活动编辑器观察面，并顺手减少搜索浮层引发的无意义重组**：
  - 更新 [`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt)、[`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：活动工具栏状态现在统一从状态层窄语义入口读取，底部面板不再自己拆 `tabs + activeTabIndex`，搜索浮层也改为独立组合读取搜索状态，避免搜索状态变化把整个编辑器 Pager 内容树一起带进重组。
- **主 Activity 快捷键入口也继续移除活动标签索引拼装逻辑**：
  - 更新 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：关闭当前标签、切到上一个/下一个标签页改为直接走状态层窄语义入口，`MainActivity` 不再自己读取 `activeTabIndex` 和 `tabs` 来推导标签切换行为。
- **保存全部后的 LSP 保存通知也从外层列表拼装收回状态层，并修正部分失败时误通知全部标签的问题**：
  - 更新 [`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：保存全部前会先由状态层快照脏标签通知目标，保存后只回传成功项对应的编辑器句柄并执行保存通知，外层不再直接 `tabs.toList()` 后逐个通知，也避免“只要有部分成功就把所有打开标签都当作已保存”的旧语义错误。
- **未保存关闭对话框的“保存后关闭”也收回状态层处理**：
  - 更新 [`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`EditorTabManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorTabManager.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：保存成功后直接由状态层完成待关闭脏标签收口，`EditorContainer` 不再根据 `tabId` 回查标签索引再发关闭请求。
- **跳转到行与全文件替换的活动编辑器能力判断也收回状态层**：
  - 更新 [`MainActivityDialogs.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityDialogs.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：`Goto Line` / `Replace All` 对话框不再自己串 `hasActiveEditorTab + hasActiveEditableEditor + 执行动作`，改为直接消费状态层返回的窄语义结果。
- **切换行注释与格式化入口也继续移除外层活动编辑器能力拼装**：
  - 更新 [`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：切换注释改为由状态层统一判断当前是否存在可编辑编辑器并执行动作，格式化入口则直接从状态层获取当前可编辑编辑器快照，外层不再自己串 `hasActiveEditorTab / getActiveEditableEditorHandleOrNull / readActiveTabText`。
- **Windows 本地构建恢复使用模块相对路径下的默认 `build/` 目录，移除会持续膨胀的 `LOCALAPPDATA/TinaIDE/gradle-out` 外置产物方案**：
  - 更新 [`build.gradle.kts`](build.gradle.kts)、[`external/tina-android-tree-sitter/build.gradle.kts`](external/tina-android-tree-sitter/build.gradle.kts) 与 [`AGENTS.md`](AGENTS.md)：撤掉 Windows 下对 `layout.buildDirectory` 的外置重定向，构建产物重新回到模块自己的 `build/` 目录；同时在协作文档中明确禁止再次引入 `AppData` 外置产物和时间戳 session 目录，避免后续 AI 修改再次把 C 盘挤满。
- **BottomPanel 的调试状态订阅改成按需收集，避免普通编辑态也持续观察整套调试流**：
  - 更新 [`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt)：`debugStatus / breakpoints / variables / callStack / consoleLines` 仅在调试工具栏或调试内容实际显示时才 `collectAsStateWithLifecycle`；普通模式下改为读取当前值，不再让底部面板因隐藏调试流变化产生额外重组。
  - 同时把书签面板的 `projectRootPath` 读取收窄到 `BOOKMARKS` 标签实际显示时，继续缩小底部面板在普通标签切换下的状态观察面。
- **BottomPanel 的构建日志 / 诊断流也改成按当前标签页按需订阅**：
  - 继续更新 [`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt)：`buildLogs` 仅在 `BUILD_LOG` 标签显示时订阅，`diagnostics` 仅在 `DIAGNOSTICS` 标签显示时订阅；未显示时直接读取当前 `StateFlow.value`，避免后台日志/诊断变更把无关标签页一起带进重组。
- **Symbols 面板按当前搜索模式只计算当前分支结果，避免 LSP 搜索时仍同步跑本地索引查询**：
  - 更新 [`SymbolsContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/SymbolsContent.kt)：`effectiveUseLspSearch` 为真时直接跳过 `queryGlobalsFuzzy/queryGlobals`，并且只在 LSP 分支实际显示时再构建 `groupedLspResults`，继续缩小 Symbols 面板在输入过程中的无效计算面。
- **Outline 面板在搜索态下不再提前计算折叠元数据**：
  - 更新 [`OutlineContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/OutlineContent.kt)：搜索关键字非空时直接跳过 `hasChildrenMap` 的整表构建，仅在折叠功能实际启用时才计算子节点映射；同时“全部折叠”按钮复用这份映射，避免重复扫描整份 symbol 列表。
- **Outline 面板在无打开文件或 LSP 不可用时，直接跳过后续副作用和派生计算**：
  - 继续更新 [`OutlineContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/OutlineContent.kt)：把 `documentSymbolsTarget` 的可见性判断前移到状态 `remember` 之后、副作用和折叠/过滤派生之前，保留既有面板状态的同时，避免无效标签页仍去准备 Outline 的监听和计算。
- **文档符号面板和宿主命令入口继续收窄活动编辑器能力语义，外层不再自己拼可用性判断**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`OutlineContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/OutlineContent.kt)、[`SymbolsContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/SymbolsContent.kt)、[`MainActivityHostCommandExecutor.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/commands/MainActivityHostCommandExecutor.kt)、[`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：文档符号面板与工作区符号面板都改为直接消费状态层返回的符号目标结果，`Goto Line` / `Replace` 的宿主命令入口和顶栏入口也都直接消费状态层的“活动可编辑编辑器命令可用性”，不再由外层组合 `hasActiveEditorTab`、`tabId != null` 或无条件弹框后再等对话框兜底。
- **状态层旧布尔桥接继续收窄，测试也不再反向依赖已淘汰入口**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：删除 `hasActiveEditorTab`、`hasActiveEditableEditor`、`getActiveDocumentSymbolsTabIdOrNull`、`getActiveWorkspaceSymbolsTabIdOrNull` 这组已无生产代码使用的旧桥接，并将 `getActiveLspTabIdOrNull` 收成状态层私有实现；测试统一改为断言 `ActiveDocumentSymbolsTargetResult`、`ActiveWorkspaceSymbolsTargetResult` 与 `ActiveEditorCommandResult`，避免旧语义继续被保活。
- **AI 当前文件/选区上下文快照也收回状态层，主界面与测试不再依赖 AI 扩展桥接**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt) 并删除 [`EditorMessageContextExt.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/EditorMessageContextExt.kt)：`snapshotCurrentFileContext()` 与 `snapshotSelectedCodeContext()` 改为由状态层直接提供，外层不再通过 AI 集成扩展函数拼装活动编辑器上下文。
- **书签入口也继续移除活动文件/光标/marker line 的外层拼装**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`EditorMarkerLineResolver.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorMarkerLineResolver.kt)、[`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：书签切换与跳转前所需的活动文件、光标行和 bookmark target line 统一改由状态层结果对象提供，`MainActivityActionsViewModel` 不再自己组合 `getActiveEditorHandleOrNull() + getCursorPositionInActiveTab() + resolveMarkerLineFromSnapshot()`。
- **编辑器容器的外部修改提示也不再自己拼活动句柄和会话告警流**：
  - 更新 [`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 与 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt)：活动编辑器的外部修改和错误提示改为直接消费状态层提供的 `ActiveEditorSessionAlertState`，UI 不再自己组合 `getActiveEditorHandleOrNull()` 和 `getActiveTabSessionAlertFlow()`。
- **编辑器热路径继续收口到共享缓存与共享帧上下文**：
  - 更新 [`EditorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderer.kt)、[`EditorRenderAssembly.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderAssembly.kt)、[`EditorRenderFrameContext.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderFrameContext.kt)、[`EditorLineLayoutCache.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorLineLayoutCache.kt)、[`EditorLineTextLookup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorLineTextLookup.kt)、[`EditorTextScanCache.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorTextScanCache.kt)、[`EditorBracketSnapshotCache.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorBracketSnapshotCache.kt)、[`BracketDepthCache.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/BracketDepthCache.kt)：行布局、文本扫描、括号深度与 bracket snapshot 进一步围绕统一缓存协作，减少 renderer 之间重复读取和重复扫描。
- **底部面板与一批 Compose 对话框/面板继续收拢到更一致的 UI 结构**：
  - 更新 [`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt)、[`BottomPanelDragHandle.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanelDragHandle.kt)、[`BottomPanelTabRow.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanelTabRow.kt) 以及多处 Compose Dialog / Panel 文件：性能页显示门控、底部面板状态与弹窗外壳继续去重，减少分散判断和重复视觉实现。
- **编辑器容器状态继续把活动态与桥接语义收回内部，减少 UI / AI / 插件层直接拆 tab 细节**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt)、[`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt)、[`OutlineContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/OutlineContent.kt)、[`MainActivityHostCommandExecutor.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/commands/MainActivityHostCommandExecutor.kt) 以及测试基础设施：活动文件、活动 tab、活动 LSP、撤销/重做/脏标记等派生语义继续统一从状态层提供，外层调用不再依赖 `getActiveTab()?.xxx` 这类旧桥接写法。
- **会话状态桥接继续收口成更窄的派生 flow，减少 UI 直接消费 `DocumentSessionState` 大对象**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt)、[`OutlineContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/OutlineContent.kt)、[`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：外层改为只读取工具栏状态、最后编辑时间和活动页告警这些窄语义 flow，不再从容器状态层直接拿整份会话状态再自行拆字段。
- **活动标签切换对 LSP 生命周期的监听也移出组合期，避免所有已打开编辑页随活动页切换一起重组**：
  - 更新 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：活动标签页的 LSP attach/release 改为在 `snapshotFlow` 中监听 `isTabActive + loading + loadError`，不再在组合期直接读取活动态布尔值，从而减少切换标签时无意义的整页重组。
- **编辑器页的 LSP Folding / Semantic Tokens 设置订阅也下沉到副作用流，避免设置切换触发整页编辑器重组**：
  - 更新 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：`lspFoldingRangeEnabled` 与 `semanticTokensEnabled` 改为直接在 `LaunchedEffect` 中组合 `Prefs` flow 监听，继续保留已打开编辑器的热更新能力，但不再通过 `collectAsState()` 把整页组合树一起带进重组。
- **编辑器页继续移除对会话对象的直接持有，改由状态层承接会话写回操作**：
  - 更新 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt) 与 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)：标记快照 clean、光标位置、滚动位置、文本变更通知以及 editor binding attach/detach 都改为通过状态层窄方法转发，Compose 页面主体不再直接保存 `session` 实例。
- **状态层继续把局部查询收成内部窄入口，页面不再自行包装 diagnostics 观察逻辑**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 与 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：`diagnostics` 读取改由状态层直接提供 flow，相关活动态 / 会话态派生方法进一步降为 `internal`，减少 `app` 模块内部之外的 API 暴露面。
- **页面与查看器的搜索 callback 装配继续回收到状态层内部**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt)、[`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：代码页与十六进制查看器不再直接创建 `SearchStateManager` 回调对象，改为只提交搜索/跳转函数，由状态层统一装配并解绑搜索桥接。
- **BottomPanel 继续移除对活动 LSP / 裸 projectRoot 查询的直接依赖**：
  - 更新 [`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt) 与 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)：底部状态栏的编辑器状态改由状态层直接给出，书签面板也改为走更窄的 bookmarks 根路径语义入口；同时删除未再使用的 `canLspActions` 死代码。
- **编辑器页继续移除通用 projectRoot 裸查询，改走更窄的 editor / bookmarks 语义入口**：
  - 更新 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 与测试基础设施：`EditorState` 初始化改用 editor 专用根路径入口，书签高亮链路改用 bookmarks 专用根路径入口，页面侧不再直接读取通用 `projectRoot` 查询方法。
- **Outline / Symbols 页面继续移除对活动 tab 与活动 LSP 组合判断的直接依赖**：
  - 更新 [`OutlineContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/OutlineContent.kt)、[`SymbolsContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/SymbolsContent.kt) 与 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)：大纲与符号页改为直接读取“是否有活动编辑器”“当前文档符号 tab”“当前工作区符号 tab”这些窄语义入口，不再在页面层自行拼接活动 tab / LSP 可用性判断。
- **活动编辑器目标继续从分散的 tabId/file 裸查询收成单个 handle 语义入口**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt)：保存当前文件与外部修改监听改为直接消费 `ActiveEditorHandle`，不再分别读取活动 tabId 和活动 file 再自行拼装。
- **外层继续移除通过活动索引反查当前编辑器的残留写法**：
  - 更新 [`EditorTabManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorTabManager.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt)、[`MainActivityHostCommandExecutor.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/commands/MainActivityHostCommandExecutor.kt) 与测试基础设施：关闭当前/关闭其他标签、工具栏脏标记/撤销重做状态以及活动页告警监听改为直接走状态层活动语义，不再在外层用 `activeTabIndex + tabs[...]` 反推当前编辑器。
- **对话框与工具入口继续移除“有活动文件就等于支持文本编辑动作”的旧判断**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`MainActivityDialogs.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityDialogs.kt)、[`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)：跳转行、全文件替换、注释切换、格式化等入口改为先判断“是否有活动编辑器”与“是否有活动可编辑编辑器”这两个窄能力语义，不再把活动文件存在误当成支持当前操作。
- **文件跳转入口继续从外层轮询桥接收回状态层统一语义**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`MainActivityNavigationHelper.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityNavigationHelper.kt)、[`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`OutlineContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/OutlineContent.kt)、[`SymbolsContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/SymbolsContent.kt)：打开文件并跳到指定位置现在统一走状态层入口，外层不再各自维护 `openFile + retry goToPositionInActiveTab(...)` 的旧轮询拼装，也顺手移除了未实际使用的 `activityContext` 空参数。
- **活动编辑器内跳转与基础编辑动作继续收口成更窄的状态语义**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`MainActivityDialogs.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityDialogs.kt)、[`TinaPluginEditorBridge.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/TinaPluginEditorBridge.kt)、[`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`MainActivityHostCommandExecutor.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/commands/MainActivityHostCommandExecutor.kt)：跳转行对话框与插件桥接不再直接调用活动 tab 底层跳转方法，撤销/重做/全选/复制/剪切这些动作入口也移除了无意义的 `Context` 透传，继续减少外层桥接噪声。
- **按文件定位已打开编辑器的读写/关闭语义也收回状态层**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`FileSystemCallbacksImpl.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/FileSystemCallbacksImpl.kt)：WorkspaceEdit 应用、AI 文件系统读写和文件移动/删除同步不再在外层用 `tabs.indexOfFirst + activeTabIndex` 自己查找并切换目标 tab，改为统一复用状态层的按文件查找、临时选中和关闭语义入口。
- **AI 编辑器工具的格式化目标也不再偷用“当前活动页”语义**：
  - 更新 [`EditorToolCallbacksImpl.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/EditorToolCallbacksImpl.kt) 与 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)：AI 触发 `formatCode(filePath)` 时，已打开文件改为按目标文件读取/写回对应编辑器内容，未打开文件才直接落盘，避免“文件存在但当前活动页并不是它”时误格式化错误对象。
- **AI 工具、插件桥接与主界面动作继续复用统一的活动编辑器语义入口**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`TinaPluginEditorBridge.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/TinaPluginEditorBridge.kt)、[`AiToolsIntegrationManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/AiToolsIntegrationManager.kt)、[`EditorMessageContextExt.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/EditorMessageContextExt.kt)、[`EditorToolCallbacksImpl.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/EditorToolCallbacksImpl.kt)、[`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`MainActivityDialogs.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityDialogs.kt)：AI 当前文件/选中代码快照、插件桥接的活动文件与 tab 查询、语言识别，以及书签/注释/格式化/跳转行/整页替换等动作都改走 `ActiveEditorHandle` 与统一 languageId 语义入口，删除插件侧重复扩展名映射和更多分散的活动文件裸查询。
- **存储权限体系统一：消除散落的 3 套请求入口 + 响应式化 + 修复"空壳项目"残留**：
  - 新增 [`StoragePermissionRequest.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/StoragePermissionRequest.kt) 与 [`StoragePermissionRequester.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/compose/StoragePermissionRequester.kt)，并更新 [`StorageManager.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/StorageManager.kt) 与 [`build.gradle.kts`](core/storage/build.gradle.kts)：`StorageManager` 暴露 `permissionStatus: StateFlow<PermissionStatus>` + `nextPermissionRequest()` + `refreshPermissionStatus()`，Compose 侧统一走 `rememberStoragePermissionRequester { granted -> ... }`，两类 launcher（设置页跳转 / 运行时权限）的回调都在内部自动触发 `refreshPermissionStatus`，UI 无需额外钩子即可自动同步；`core:storage` 为此新增 Compose + koin-compose 依赖。
  - 更新 [`ProjectScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/project/ProjectScreen.kt)、[`NewProjectWizardActivity.kt`](feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardActivity.kt)、[`NewProjectWizardScreen.kt`](feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardScreen.kt)、[`NewProjectWizardViewModel.kt`](feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardViewModel.kt)、[`ToolchainConfigActivity.kt`](feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/ToolchainConfigActivity.kt) 与 [`ProjectCreationService.kt`](core/project/src/main/java/com/wuxianggujun/tinaide/project/ProjectCreationService.kt)：三处各自独立的 launcher + pending 状态机合并为单一 `StoragePermissionRequester`；`ProjectCreationService` 移除 `requiresPublicSourcePermission` 与 `PUBLIC_SOURCE_PERMISSION_REQUIRED` 枚举，权限判断由调用方（Wizard Screen）在创建前统一完成；`ToolchainConfigActivity` 弃用 `XXPermissions` 库并同步删除 [`app/build.gradle.kts`](app/build.gradle.kts) 与 [`feature/workspace/build.gradle.kts`](feature/workspace/build.gradle.kts) 中的 `:xxpermissions-local` 依赖引用（`tools/template-common` 的模板项目侧仍保留）。
  - 更新 [`ProjectManagerViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/ProjectManagerViewModel.kt)、[`FileManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/file/FileManager.kt)、[`ProjectImporter.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectImporter.kt)、[`AppModule.kt`](app/src/main/java/com/wuxianggujun/tinaide/di/AppModule.kt) 与 [`AppViewModelModule.kt`](app/src/main/java/com/wuxianggujun/tinaide/di/AppViewModelModule.kt)：消费者全部改为 Koin 单例注入 `StorageManager`，移除多处散落的 `StorageManager(context)` new 实例；`StorageManager.getDefaultPublicProjectsDir*` 与 `ProjectCreationService` 里硬编码的 `Documents/TinaIDE` 路径统一收敛到 [`ProjectPaths.getPublicProjectsRoot`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectPaths.kt)，成为公有目录唯一事实源。
  - `ProjectManagerViewModel.reloadProjects` 扫描公有目录时新增"空壳项目"过滤：只剩 `.tinaide/` 元数据、无任何其它源码文件的目录不再展示。场景是 Android 11+ 清除应用数据不会撤销 `MANAGE_EXTERNAL_STORAGE` 这种特殊权限，因此 `/sdcard/Documents/TinaIDE/` 下历史遗留的空目录仍会被扫到，过滤后避免点进去看到空文件树。

### Fixed
- **修复 Windows 本地 Gradle 在 included build 配置期和 `bundleLibCompileToJar*` 阶段的双重阻塞，并恢复增量构建命中**：
  - 更新 [`external/tina-android-tree-sitter/build.gradle.kts`](external/tina-android-tree-sitter/build.gradle.kts)、[`build.gradle.kts`](build.gradle.kts)、[`gradle.properties`](gradle.properties)：修正 included build 中错误复用主工程插件别名导致的配置期失败；同时将 Windows 本地构建切换为单次 daemon 进程，并移除会强制预删 `classes.jar` 输出目录、打穿增量缓存的兜底逻辑。`assembleDebug` 连续验证已恢复稳定通过，且后续重复构建的 `up-to-date` 命中率明显回升。
- **修复 bracket fallback 重复扫描与渲染侧重复失效的问题**：
  - 更新 [`EditorBracketSnapshotCache.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorBracketSnapshotCache.kt)、[`BracketPairGuideRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/BracketPairGuideRenderer.kt)、[`MatchingBracketHighlightRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/MatchingBracketHighlightRenderer.kt)：括号高亮与 guide 共享的 fallback 扫描路径由双分支收成单一路径，减少热路径上的重复区间推导与无效 cache 失效入口。
- **修复底部面板性能页切换条件分散导致的行为不一致问题**：
  - 新增 [`BottomPanelTabResolutionTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanelTabResolutionTest.kt)，并更新 [`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt)、[`BottomPanelTypes.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanelTypes.kt)：性能页显隐决策改为纯函数统一管理，避免 UI 组合层和状态层各自做一套判断。
- **修复主界面命令执行层仍调用旧签名、测试基础设施仍依赖活动 tab 公开结构的问题**：
  - 更新 [`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`MainActivityHostCommandExecutor.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/commands/MainActivityHostCommandExecutor.kt)、[`DevEditorTestInfrastructureTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/DevEditorTestInfrastructureTest.kt)：移除 `performPaste` / `toggleLineComment` 的无用 `Context` 透传，并让测试改用 `getActiveFile()` / `getActiveTabId()`，避免公开接口继续泄漏活动 tab 内部结构。

### Technical
- **版本对齐**：`0.14.96 (1497)`
- **删除旧设计残留**：
  - 删除 [`EditorTextLayoutMapper.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorTextLayoutMapper.kt)、[`ImeDeltaUtils.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/ImeDeltaUtils.kt)、[`ImeDeltaMappingTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/ImeDeltaMappingTest.kt)，并移除 bracket renderer 中无实际效果的 no-op `invalidate()` 入口与 hover 请求里的未使用锚点参数，继续收口旧编辑器过渡代码。
- **继续删除编辑器状态层的旧桥接与死代码**：
  - 删除 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 中未再使用的 `currentTabSupportsSearch()`，并将 `getActiveTab()` 收紧回状态层内部；同时把仅供 `app` 内部使用的 `projectRoot / diagnostics / session` 查询入口降为 `internal`，继续缩小旧桥接暴露面。
  - 继续删除 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 的 `getActiveFile()` 旧桥接，并更新 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt) 与 [`DevEditorTestInfrastructureTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/DevEditorTestInfrastructureTest.kt) 改为断言 `ActiveEditorHandle.file`，让活动编辑器语义彻底收口到统一 handle。
  - 继续将 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt) 的 `getActiveTabId()` 收紧为私有实现，并更新 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt) 与 [`DevEditorTestInfrastructureTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/DevEditorTestInfrastructureTest.kt) 改为复用 `ActiveEditorHandle.tabId`，避免测试继续依赖状态层旧 tabId 桥接。
- **继续收紧编辑器状态层里仅供页面装配使用的内部入口**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)：`getLspStatus()`、`getActiveLspStatus()` 以及 code viewer / hex viewer / code editor callback 注册入口统一降为 `internal`，LSP 状态查询与编辑器绑定装配继续留在 `app` 模块内部，不再作为对外桥接接口泄漏。
- **继续补强状态层收口回归**：
  - 更新 [`EditorContainerStateTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerStateTest.kt) 与 [`DevEditorTestInfrastructureTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/DevEditorTestInfrastructureTest.kt)，补上 `TabToolbarState`、`lastEditAt`、活动页告警 flow、底部状态栏编辑器状态、editor / bookmarks 根路径入口、Outline / Symbols 活动态语义入口以及 `ActiveEditorHandle` 映射测试，锁住这轮删除旧会话桥接后的行为。
- **APK 打包功能审计（P0 / P1 / P2）整体收尾**，详见 [`docs/design/APK-Packaging-Audit-2026-04-18.md`](docs/design/APK-Packaging-Audit-2026-04-18.md) 与 [`docs/design/APK-Packaging-Next-Steps.md`](docs/design/APK-Packaging-Next-Steps.md)：
  - **P0.1 / P1.1 / P1.2 — 模板代码 DRY 收口**：新增 [`tools/template-common/`](tools/template-common/) 模块，抽出 [`TemplatePermissionResolver.java`](tools/template-common/src/main/java/com/tinaide/template/common/TemplatePermissionResolver.java)（Manifest 驱动的特殊权限 / 危险权限分桶）与 [`TemplatePermissionFlow.java`](tools/template-common/src/main/java/com/tinaide/template/common/TemplatePermissionFlow.java)（两阶段请求 + 30s 冷却设置跳转 + 首次解释对话框 + 全部授予后清理 state）；同时把 `restricted_settings_*` 中英字符串统一迁到 `template-common/res/`。[`TemplateNativeActivity.java`](tools/template-native-activity/src/main/java/com/tinaide/template/nativeactivity/TemplateNativeActivity.java) 从 182 行瘦到 23 行，[`TemplateSDLActivity.java`](tools/template-sdl3/src/main/java/com/tinaide/template/sdl3/TemplateSDLActivity.java) 从 197 行瘦到 37 行，只保留生命周期钩子 + SDL3 特有的 `setWindowStyle`。两模板 `build.gradle.kts` 删除重复的 `xxpermissions-local` / `fragment` 依赖声明，统一走 `template-common` 的 `api` 传递。SDL3 项目勾选 `POST_NOTIFICATIONS` / `CAMERA` 等权限现在也能真正在运行时被请求。
  - **P0.2 — 新增 `versionCode` UI + 持久化**：[`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt) 加数字输入（仅数字 / 上限 9 位 / `toIntOrNull() > 0` 校验），`ApkBuildConfig.versionCode` 透传；[`ApkExportPermissionProfile.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkExportPermissionProfile.kt) 的 `RememberedApkExportPermissions` 新增 `versionCode` 字段持久化到 `permissions.json`，解决二次打包覆盖安装 `INSTALL_FAILED_VERSION_DOWNGRADE` 的问题。
  - **P0.3 — 包名格式即时校验**：[`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt) 顶层常量 `PACKAGE_NAME_REGEX = ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$`，`OutlinedTextField` 用 `isError` + `supportingText` 即时反馈，`canBuild` 加 `isPackageNameValid` 闸门避免用户拿到"安装解析失败"的不友好错误。中英 i18n `apk_builder_package_invalid` 已补齐。
  - **P0.5 — 自定义权限高风险检测**：[`ApkExportPermissionProfile.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkExportPermissionProfile.kt) 新增 `hasHighRiskPermissionSelected()` 同时扫描内置勾选 + 自定义文本；警告卡片对手敲的 `MANAGE_EXTERNAL_STORAGE` 等高风险权限也生效。
  - **P1.3 — 启动器图标自定义**：新增 [`IconRasterizer.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/IconRasterizer.kt)（按 DPI 解码 + WebP 压缩）与 [`TemplateIconPatcher.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/TemplateIconPatcher.kt)（读 `resources.arsc` 定位 `@mipmap/ic_launcher` 的所有 ZIP 条目，按各 DPI 重写图标字节）；[`ApkBuildConfig.iconFile`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkBuildConfig.kt) + [`ApkBuilder.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkBuilder.kt) 打通链路；Dialog 加文件选择器 + 错误提示 + 持久化路径。用户不选图标时仍走模板自带的 TinaIDE 标识图标，不会再出现默认小机器人。
  - **P1.4 — Keystore SHA-1 / SHA-256 指纹预览**：[`DebugKeyStore.computeFingerprints()`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/DebugKeyStore.kt) + `CertificateFingerprints` 数据类；对话框在签名参数变化时 `LaunchedEffect` 异步重算，CUSTOM 签名模式下用 `SelectionContainer` 包裹 SHA-1 / SHA-256 文本（长按进入选择模式 → 复制）。对接微信 / 高德 / QQ 等第三方 SDK 不再需要跑 `keytool -list`。
  - **P2.1 — 模板 APK 与源码同步校验任务**：根 [`build.gradle.kts`](build.gradle.kts) 扩展 `buildApkTemplates`，拷贝 APK 后把每个模板的**源输入集合**（`tools/template-*/src/` + `build.gradle.kts` + `tools/template-common/` + SDL3 额外依赖的 `app/src/main/java/org/libsdl/app/`）的 SHA-256 写入 [`app/src/main/assets/apk_templates/template-checksums.txt`](app/src/main/assets/apk_templates/template-checksums.txt)；新增 `./gradlew checkApkTemplatesSync` 任务重新计算哈希并与 manifest 对比，不一致即 fail 并给出 `./gradlew buildApkTemplates` 修复命令。manifest 是纯文本、Git diff 友好；不依赖 git diff，本地 + CI 都可调用。
  - **P2.2 — 暴露更多常用权限到 UI**：`apkPermissionOptions` 现含 14 个权限（新增 `REQUEST_INSTALL_PACKAGES` / `SYSTEM_ALERT_WINDOW` / `WRITE_SETTINGS` / `SCHEDULE_EXACT_ALARM` / `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` / `RECORD_AUDIO` / `POST_NOTIFICATIONS`），其中 5 个标记 `isHighRisk = true` 触发警告卡片；不必再走"自定义权限"文本框。
  - **P0.3 / P0.4 原 README 描述证伪**：更新 [`app/src/main/assets/apk_templates/README.md`](app/src/main/assets/apk_templates/README.md)，移除过时的"48/47 字符固定长度占位符"章节，改为 AXML 二进制 `AndroidManifestBlock` patch 说明；`setValueAsString` 支持任意长度 UTF-16 内容，也无需 XML 转义。
- **APK 导出路径收口到 `ProjectDirStructure`，旧版 `apk-signing.properties` 自动迁入 `apk-export/`**：
  - 更新 [`ProjectDirStructure.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectDirStructure.kt)、[`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt)、[`ApkExportPermissionProfile.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkExportPermissionProfile.kt)，并新增 [`ProjectDirStructureApkPathsTest.kt`](core/storage/src/test/java/com/wuxianggujun/tinaide/storage/ProjectDirStructureApkPathsTest.kt)：`.tinaide/apk-export/`（`permissions.json` / `signing.properties` / `icons/`）与 `.tinaide/keystore/` 全部通过 `ProjectDirStructure.getApkExportDir / getApkPermissionsFile / getApkSigningPropertiesFile / getKeystoreDir / getApkExportIconsDir` 获取，App 层 4 处绕过 `ProjectDirStructure` 的硬编码 `.tinaide/**` 路径已清零；首次打开 Dialog 时 `migrateLegacyApkSigningPropertiesIfNeeded()` 以 copy-先于-delete 的安全方式把旧版 `.tinaide/apk-signing.properties` 搬到 `.tinaide/apk-export/signing.properties`，迁移失败则保留旧文件供下次重试。
- **编译运行入口改走一次性新终端会话，运行结束"按 Enter 关闭"后自动回到编辑器**：
  - 更新 [`CompileUiEventObserver.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompileUiEventObserver.kt)、[`TerminalActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/TerminalActivity.kt)、[`CompileProjectUseCase.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCase.kt)、[`strings.xml`](core/i18n/src/main/res/values/strings.xml) 与 [`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：Run/Terminal 动作现在带 `EXTRA_NEW_SESSION`，`TerminalActivity` 在 Run 模式下跳过项目级会话恢复与 `onPause` 快照保存，避免一次性命令污染用户终端状态；运行命令尾部追加 `; __tina_rc=$?; printf "<按 Enter 关闭>" "$__tina_rc"; IFS= read -r __tina_ign || true; exit "$__tina_rc"`，程序退出后先展示"按 Enter 键关闭当前界面"提示，用户确认后 shell 以程序退出码退出，Activity 侦听 `SessionStatus.EXITED` 自动 `finish()` 回到编辑器。
- **终端 pinch-zoom 改为整数 sp 阶梯切换，修复长时间缩放后字符间距逐渐漂移与滚动位置跳回底部**：
  - 更新 [`TerminalViewWrapper.kt`](feature/terminal/src/main/java/com/wuxianggujun/tinaide/terminal/ui/TerminalViewWrapper.kt)：对齐 Termux 官方策略，只在累积 scale 跨过 ±10% 阈值时才让字号上下切一档（`MIN_FONT_SP=8` / `MAX_FONT_SP=32`），同一档字号对应的 Paint 字宽/行高始终一致，彻底消除浮点舍入累积导致的"间距逐渐变大/变小"；同时用 `appliedTextSizePxRef` 挡住 Compose 重组触发的重复 `setTextSize`，缩放/`update` 路径都会在调用 `setTextSize` 前后保存并 `clamp(topRow)`，避免翻阅 scrollback 时因 `updateSize()` 重建 Renderer 把视图跳回底部。

## [0.14.95] - 2026-04-05

### Added
- **新增 Compose 图标安全加载入口与工作区图标适配器**：
  - 新增 [`TinaIconPainter.kt`](core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/icons/TinaIconPainter.kt)、[`TinaTabIcons.kt`](core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/icons/TinaTabIcons.kt)、[`WorkspaceIconPainter.kt`](feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/components/WorkspaceIconPainter.kt)：为抽屉栏、顶部菜单、登录页与 Workspace/Toolchain/InstallLog 等页面提供统一的 `ImageVector` / `BitmapPainter` 加载链路，后续页面不再直接依赖 Compose XML Vector 解析链。
- **新增文件编码检测与编辑器回归测试**：
  - 新增 [`FileCharsetDetector.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/io/FileCharsetDetector.kt)、[`DocumentSessionTest.kt`](feature/editor/src/test/java/com/wuxianggujun/tinaide/editor/session/DocumentSessionTest.kt)、[`RainbowBracketComputerTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/RainbowBracketComputerTest.kt)、[`TextRendererCacheTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/TextRendererCacheTest.kt)：补齐文件字符集检测、文档会话保存/重载、彩虹括号脏区重算和可见区高亮缓存失效等关键回归场景。
- **新增统一编辑器 Popup 外壳与 Signature Help 参数提示链路**：
  - 新增 [`EditorPopupChrome.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorPopupChrome.kt)、[`EditorSignatureHelpPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSignatureHelpPopup.kt)、[`SignatureHelpFormatter.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpFormatter.kt)、[`SignatureHelpPopupLayoutResolver.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpPopupLayoutResolver.kt) 以及 [`CompletionPopupLayoutResolverTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/CompletionPopupLayoutResolverTest.kt)、[`EditorSignatureHelpStateTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorSignatureHelpStateTest.kt)、[`SignatureHelpFormatterTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpFormatterTest.kt)、[`SignatureHelpPopupLayoutResolverTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpPopupLayoutResolverTest.kt)：Compose 编辑器现在具备统一 popup 视觉外壳，并补齐参数提示的状态管理、布局解析与回归测试。
- **新增原生构建产物导出目录、CMake 产物定位器与 SDL3 显式 run config 引导器**：
  - 新增 [`BuildArtifactExporter.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/BuildArtifactExporter.kt)、[`CMakeBuildOutputLocator.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/CMakeBuildOutputLocator.kt)、[`ProjectRunConfigBootstrapper.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/ProjectRunConfigBootstrapper.kt)、[`BuildArtifactExporterTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/BuildArtifactExporterTest.kt)、[`CMakeBuildOutputLocatorTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/cmake/CMakeBuildOutputLocatorTest.kt)、[`ProjectRunConfigBootstrapperTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/ProjectRunConfigBootstrapperTest.kt)：IDE 现在会把私有工作区中的最终二进制导出到项目 `.tinaide/artifacts/`，CMake 构建会按目标类型更准确地定位可执行文件 / `.so` / `.a`，SDL3 模板项目创建完成后也会自动写入显式 GUI 运行配置。
- **新增新建项目来源位置模型与相关设置项**：
  - 新增 [`NewProjectSourceLocation.kt`](core/config/src/main/java/com/wuxianggujun/tinaide/core/config/NewProjectSourceLocation.kt)，并更新 [`ProjectScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/project/ProjectScreen.kt)、[`NewProjectWizardActivity.kt`](feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardActivity.kt)、[`NewProjectWizardScreen.kt`](feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardScreen.kt)、[`SettingsViewModel.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/SettingsViewModel.kt)、[`ProjectSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/ProjectSettingsSection.kt)：新建项目、导入项目和 Git Clone 现在可以显式选择公有目录或私有目录，并支持在设置页记忆默认来源位置。

### Changed
- **编辑器文档链路改为“字符集感知 + 文本快照复用”模型**：
  - 更新 [`DocumentSession.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/session/DocumentSession.kt)、[`FileEncodingDetector.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/FileEncodingDetector.kt)、[`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：文件打开/重载会记录检测到的 charset，保存时继续使用原编码写回；编辑器内部新增版本化文本快照，completion / folding / Tree-sitter / LSP 读取统一复用同一份文本快照，减少频繁 `buffer.toString()` 带来的额外开销和状态漂移。
- **Tree-sitter 高亮与彩虹括号缓存改为增量更新**：
  - 更新 [`IncrementalTreeSitterHighlightState.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/IncrementalTreeSitterHighlightState.kt)、[`TextRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt)、[`RainbowBracketComputer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/RainbowBracketComputer.kt)、[`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`EditorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderer.kt)：高亮状态现在维护当前文本、脏行范围与可见窗口缓存，彩虹括号也改为按脏行增量重算，长文档输入和滚动时的重绘开销进一步收敛。
- **AI 搜索、工具确认、错误提示与总结文案统一走国际化资源**：
  - 更新 [`SearchTools.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/search/SearchTools.kt)、[`AiApiErrorHandler.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/api/AiApiErrorHandler.kt)、[`AiChatViewModel.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/viewmodel/AiChatViewModel.kt)、[`FileSystemTools.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/filesystem/FileSystemTools.kt)、[`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：GitHub/Web 搜索结果、危险操作确认、网络/API 异常、会话总结和验证码相关提示统一移除硬编码文本，便于中英文环境保持一致体验。
- **工具链 / Sysroot / APK / 远程 LSP 诊断文案收敛到统一资源**：
  - 更新 [`AndroidNativeToolchainManager.kt`](core/ndk/src/main/java/com/wuxianggujun/tinaide/core/ndk/AndroidNativeToolchainManager.kt)、[`AndroidSysrootManager.kt`](core/ndk/src/main/java/com/wuxianggujun/tinaide/core/ndk/AndroidSysrootManager.kt)、[`ApkBuilder.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkBuilder.kt)、[`RemoteLspConnectionProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/RemoteLspConnectionProvider.kt)、[`RsyncSyncProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/RsyncSyncProvider.kt)、[`CompilerSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/CompilerSettingsSection.kt)、[`LspSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/LspSettingsSection.kt)：导入、校验、安装、连接失败和构建进度提示不再散落硬编码字符串，设置页与运行时错误展示更一致。
- **编辑器语言服务链路补齐参数提示、Make 支持复用和插件按文件名匹配**：
  - 更新 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)、[`BuiltinLanguageServiceSession.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/BuiltinLanguageServiceSession.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)、[`LspClientSession.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspClientSession.kt)、[`LspPluginManager.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/lsp/LspPluginManager.kt)：编辑器现在可透传 LSP `signatureHelp` 请求，Makefile 补全逻辑复用统一语言支持入口，插件 LSP 也能命中无扩展名文件和 `pom.xml` 这类固定文件名。
- **文档中心与开发文档重写为当前实现口径，并清理旧 TinaEditor 迁移稿**：
  - 更新 [`docs/README.md`](docs/README.md)、[`docs/design/README.md`](docs/design/README.md)、[`docs/guides/LSP-Debug-Guide.md`](docs/guides/LSP-Debug-Guide.md)、[`docs/toolchain-build-guide.md`](docs/toolchain-build-guide.md)、[`docs/开发指南.md`](docs/开发指南.md)、[`docs/快速开始.md`](docs/快速开始.md)、[`docs/架构概览.md`](docs/架构概览.md)，并删除一组已过时的 `docs/design/TinaEditor-*` 迁移文档：文档入口、LSP 排障、工具链同步与架构说明现在与当前代码实现保持一致，不再继续引用历史重构计划。
- **原生构建链路重构为“私有 workspace 构建 + 项目目录导出产物 + UI 观察器解耦”**：
  - 更新 [`CompileProjectUseCase.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCase.kt)、[`CompileActionsHelper.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompileActionsHelper.kt)、[`CompilerViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompilerViewModel.kt)、[`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)，并新增 [`CompileRuntimeObserver.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompileRuntimeObserver.kt)、[`CompileUiEventObserver.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompileUiEventObserver.kt)、[`CompileActionUiText.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompileActionUiText.kt)：真实构建继续留在私有目录，项目目录只保留可见的导出副本；编译完成后的 toast、面板切换、产物定位和运行态处理也从 `MainActivity` 拆分为独立观察器，减少 UI 层继续膨胀。
- **项目存储与工作区模型继续收敛到统一的项目定位注册表**：
  - 更新 [`ProjectLocationManager.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectLocationManager.kt)、[`ProjectPaths.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectPaths.kt)、[`ProjectImporter.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectImporter.kt)、[`StorageDatabase.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/db/StorageDatabase.kt)、[`ProjectLocationDao.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/db/ProjectLocationDao.kt)、[`ProjectDirStructure.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectDirStructure.kt)，并删除 [`WorkspaceManager.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/WorkspaceManager.kt) 与 [`WorkspaceSelectionActivity.kt`](feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/WorkspaceSelectionActivity.kt)：项目注册、导入、构建路径和 `.tinaide` 目录结构继续围绕统一的 project location 模型收口，旧的 workspace 管理入口被移除。
- **顶栏构建菜单新增 CMake 工具子菜单与产物目录入口**：
  - 更新 [`MainActivityTopBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityTopBar.kt)、[`CompileActionsHelper.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompileActionsHelper.kt)：CMake 项目现在可以直接从顶栏访问“打开构建产物目录 / 重新配置 / 清空并重新配置 / 清空构建目录”等操作，不再只能靠重新编译间接处理缓存。

### Fixed
- **修复 Release 包在 Android 15/16 上解析部分 Compose XML Vector 资源可能崩溃的问题**：
  - 更新 [`MainActivityTopBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityTopBar.kt)、[`LoginActivity.kt`](feature/login/src/main/java/com/wuxianggujun/tinaide/ui/activity/LoginActivity.kt)、[`WorkspaceSelectionActivity.kt`](feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/WorkspaceSelectionActivity.kt)、[`ToolchainConfigActivity.kt`](feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/ToolchainConfigActivity.kt)、[`InstallLogActivity.kt`](feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/InstallLogActivity.kt)、[`InstallContentComponents.kt`](feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/components/InstallContentComponents.kt)、[`MirrorComponents.kt`](feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/components/MirrorComponents.kt)、[`ProgressComponents.kt`](feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/components/ProgressComponents.kt)、[`SetupComponents.kt`](feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/components/SetupComponents.kt)：相关入口改为优先使用代码 `ImageVector` 或统一 Drawable -> Bitmap painter，规避部分机型/系统在 Release 环境下解析 XML Vector 的崩溃风险。
- **修复 GBK / UTF-16 等非 UTF-8 文件在重新加载或保存后编码被重置的问题**：
  - 更新 [`DocumentSession.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/session/DocumentSession.kt)、[`FileCharsetDetector.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/io/FileCharsetDetector.kt)、[`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：文档会话现在会保存检测到的字符集并在写回时沿用，避免中文文件保存后出现乱码或编码漂移。
- **修复验证码错误自动刷新依赖中文关键字匹配的问题**：
  - 更新 [`LoginViewModel.kt`](feature/login/src/main/java/com/wuxianggujun/tinaide/auth/LoginViewModel.kt)、[`LoginActivity.kt`](feature/login/src/main/java/com/wuxianggujun/tinaide/ui/activity/LoginActivity.kt)、[`CaptchaDialog.kt`](core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/CaptchaDialog.kt)、[`CaptchaInput.kt`](core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/CaptchaInput.kt)：验证码刷新改为识别 `CAPTCHA` 类错误码/英文提示，不再依赖 `result.message.contains("验证码")` 这样的本地化耦合判断。
- **修复编辑器高亮可见区缓存与彩虹括号全量重算导致的状态抖动与额外开销**：
  - 更新 [`TextRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt)、[`RainbowBracketComputer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/RainbowBracketComputer.kt)、[`IncrementalTreeSitterHighlightState.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/IncrementalTreeSitterHighlightState.kt)：文本变更后会精准失效相关缓存并按脏区重新计算，减少长文件编辑时的重复扫描。
- **修复补全框定位飘移、窄屏遮挡和右键菜单样式割裂问题**：
  - 更新 [`CompletionPopupLayoutResolver.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CompletionPopupLayoutResolver.kt)、[`EditorCompletionPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionPopup.kt)、[`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)、[`EditorSelectionContextMenu.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSelectionContextMenu.kt)、[`TinaEditorScaffold.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorScaffold.kt)：补全框改为基于窗口绝对坐标与可见画布约束定位，小屏下会自动切换更稳定的宽面板模式，选择菜单与补全/参数提示也统一到同一套 popup 视觉。
- **补强补全框/参数提示的共享光标锚点回归，避免滚动后再次出现错位**：
  - 新增 [`CursorPopupAnchorResolver.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CursorPopupAnchorResolver.kt)、[`CursorPopupAnchorResolverTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/CursorPopupAnchorResolverTest.kt)，并更新 [`CursorHandleLayout.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CursorHandleLayout.kt)、[`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)：补全框、参数提示和光标句柄现在共用同一套光标锚点解析逻辑，补上横向滚动、纵向滚动和换行分段场景的回归测试，持续对齐 Sora 的重定位行为。
- **继续补强 popup 滚动重定位回归，锁住共享锚点到布局解析的整条链路**：
  - 新增 [`PopupOverlaySharedAnchorIntegrationTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/PopupOverlaySharedAnchorIntegrationTest.kt)：补全框与参数提示现在有更高一层的集成回归，覆盖横向滚动、纵向滚动以及二者共享同一水平锚点位移的场景，避免后续修改只修 resolver 却漏掉 overlay 组合逻辑。
- **继续补强 popup 重定位回归，锁定 IME / 窗口变化与 Compose 组合层重组稳定性**：
  - 更新 [`PopupOverlaySharedAnchorIntegrationTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/PopupOverlaySharedAnchorIntegrationTest.kt)、[`EditorPopupComposeSmokeTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorPopupComposeSmokeTest.kt)：新增 IME inset、窗口高度收缩以及 layout state 更新后的 Compose 交互回归，继续把“layout 解析正确”与“弹窗在重组后仍保持可交互”这两层一起锁住。
- **继续补强补全框设备侧验收链路，新增 Compose instrumentation smoke test**：
  - 更新 [`build.gradle.kts`](core/editor-view/build.gradle.kts) 并新增 [`EditorCompletionPopupInstrumentationTest.kt`](core/editor-view/src/androidTest/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionPopupInstrumentationTest.kt)：`core:editor-view` 现在支持补全框的 Android 设备侧 Compose 验收，覆盖 popup 根节点、row / kind icon 稳定标签，以及 offset 变化后的点击交互。
- **继续补强 popup 状态切换回归，锁定 hover / completion / signature 的互斥与共存规则**：
  - 更新 [`EditorInteractionControllerTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionControllerTest.kt)：新增 hover 抢占时会收起 completion / signature，以及手动触发 completion 或 signature help 时仅收起 hover、允许另外一个 popup 保持可见的回归，避免后续改交互控制器时把三者的层级关系改乱。
- **继续补强失焦收敛回归，锁定 completion 与上下文菜单在焦点切换下的收口规则**：
  - 新增 [`EditorFocusCoordinatorTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorFocusCoordinatorTest.kt)：补充“编辑器完全失焦时收起 completion 并隐藏上下文菜单”“宿主 View 仍持有焦点时不误收起 completion”以及“重新获焦时恢复光标闪烁”的回归，避免后续焦点桥接调整把 overlay 生命周期弄乱。
- **继续补强运行时失焦回归，锁定 hover / signature 在真实生命周期中的自动收口**：
  - 新增 [`EditorRuntimeEffectsTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorRuntimeEffectsTest.kt)：补充“编辑器失焦时 `EditorRuntimeEffects` 会自动收起 hover 与 signature help、关闭光标闪烁，但不越权处理 completion”的回归，避免后续运行时 effect 调整时把 overlay 分层职责改乱。
- **继续补强宿主焦点桥接回归，锁定窗口切换与 detach 场景下的 completion 生命周期**：
  - 更新 [`EditorFocusCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorFocusCoordinator.kt)、[`EditorInputConnection.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInputConnection.kt)、[`EditorInputHostLayer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInputHostLayer.kt)、[`EditorInteractionController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionController.kt)、[`TinaEditorScaffold.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorScaffold.kt)，并新增 [`EditorInputHostLayerTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorInputHostLayerTest.kt)：宿主输入层现在会把 `View` 焦点、窗口焦点和 detach 生命周期统一回送到焦点协调器，补上“窗口失焦时收起 completion”和“宿主重建但 Compose 仍持焦时不误收起 completion”的回归，避免 Activity 切换或宿主重建时 popup 状态漂移。
- **继续补强 popup 重组回归，锁定主题切换与密度变化下的交互稳定性**：
  - 更新 [`EditorPopupComposeSmokeTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorPopupComposeSmokeTest.kt)：新增“补全框在颜色方案切换后仍可点击”和“参数提示在密度变化重组后仍可交互”的 Compose smoke 回归，避免后续主题/配置切换把 popup 组件层的 remember 状态或点击链路弄丢。
- **继续修复 popup 横向锚点在异常字体度量下被压缩的问题，并补强字号变化回归**：
  - 更新 [`CursorPopupAnchorResolver.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CursorPopupAnchorResolver.kt)、[`EditorLineLayoutCache.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorLineLayoutCache.kt)、[`CursorPopupAnchorResolverTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/CursorPopupAnchorResolverTest.kt)、[`PopupOverlaySharedAnchorIntegrationTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/PopupOverlaySharedAnchorIntegrationTest.kt)：当运行时给出明显失真的列宽量测时，popup 锚点会回退到当前 `charWidthPx` 估算，避免补全框/参数提示横向塌回行首；同时新增字体度量增大后的集成回归，锁住 completion 与 signature help 会随字号变化重新定位。
- **继续对齐补全框与 Sora 的视觉密度，收敛补全项图标与外壳阴影样式**：
  - 更新 [`EditorCompletionPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionPopup.kt)、[`EditorPopupChrome.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorPopupChrome.kt)、[`CompletionKindStyleTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/CompletionKindStyleTest.kt)：补全项左侧从字母 badge 改为按 kind 分组的 24dp 图标，文本间距和上下内边距收敛到更接近 Sora 的密度，popup 外壳阴影也从 `10dp` 收回到 `8dp`，减少补全框与参考实现之间的视觉割裂。
- **继续补强补全框样式回归，锁定图标尺寸、列表密度与 popup 外壳参数**：
  - 更新 [`EditorCompletionPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionPopup.kt)、[`EditorPopupChrome.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorPopupChrome.kt)、[`EditorPopupChromeTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorPopupChromeTest.kt)，并新增 [`EditorCompletionPopupMetricsTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionPopupMetricsTest.kt)：把补全项行高、内边距、文本间距、图标尺寸以及 popup 圆角/描边/阴影提炼成共享常量并补上纯单测，后续如果样式再次偏离 Sora 目标会被回归直接拦住。
- **继续补强补全框验收抓手，新增 popup / row / kind icon 的稳定测试标签**：
  - 更新 [`EditorCompletionPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionPopup.kt)、[`EditorPopupComposeSmokeTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorPopupComposeSmokeTest.kt)：补全框根节点、每一行候选项和左侧 kind 图标现在都会暴露稳定 `testTag`，后续做 Compose 自动化或真机截图验收时可以直接精确命中，不必只靠文本节点做脆弱选择。
- **修复参数提示缺失、遮挡补全框以及若干 UI 路由/文案遗漏问题**：
  - 更新 [`EditorInteractionController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionController.kt)、[`EditorKeyboardShortcuts.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorKeyboardShortcuts.kt)、[`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`MainPortalActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainPortalActivity.kt)、[`TerminalActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/TerminalActivity.kt)、[`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt)、[`AiApiClient.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/api/AiApiClient.kt)、[`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：输入 `(` / `,` 或按 `Ctrl+Shift+Space` 时可触发参数提示，提示优先展示在光标上方避免压住补全框，同时统一收敛设置页跳转入口、APK 构建错误格式化与编译诊断/AI 对话测试页的国际化文案。
- **修复参数提示不会随光标位置变化而更新的问题**：
  - 更新 [`EditorInteractionController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionController.kt)、[`EditorRuntimeEffects.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRuntimeEffects.kt)，并新增 [`SignatureHelpContextResolver.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpContextResolver.kt)、[`SignatureHelpContextResolverTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpContextResolverTest.kt)：参数提示现在会在参数列表内随光标移动自动刷新，离开调用上下文、产生非空选区或失焦时自动收起，减少提示内容与当前位置脱节的问题。
- **修复多重载参数提示只能查看当前单条签名的问题**：
  - 更新 [`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`EditorKeyboardShortcuts.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorKeyboardShortcuts.kt)、[`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)、[`EditorSignatureHelpPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSignatureHelpPopup.kt)、[`TinaEditorScaffold.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorScaffold.kt)、[`EditorSignatureHelpStateTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorSignatureHelpStateTest.kt)：参数提示弹窗现在会列出更多重载，支持点击切换查看，也可用 `Alt+↑ / Alt+↓` 在多签名之间轮换，避免服务端返回多条签名时只能看到当前一条。
- **修复参数提示上下文解析会被注释、Kotlin 原始字符串和尾随 lambda 场景干扰的问题**：
  - 更新 [`SignatureHelpContextResolver.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpContextResolver.kt)、[`SignatureHelpContextResolverTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpContextResolverTest.kt)、[`EditorInteractionControllerTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionControllerTest.kt)：参数提示上下文扫描现在会跳过行注释、块注释、转义字符串和 `"""` 原始字符串中的括号内容，并把 Kotlin 尾随 lambda 视为调用上下文的一部分，同时继续排除 `if/while/when` 这类控制流 block，减少光标移动时的误收起与误刷新。
- **优化多重载参数提示过长时的弹窗可读性问题**：
  - 更新 [`EditorSignatureHelpPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSignatureHelpPopup.kt)，并新增 [`SignatureHelpVisibleSliceResolver.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpVisibleSliceResolver.kt)、[`SignatureHelpVisibleSliceResolverTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpVisibleSliceResolverTest.kt)：参数提示弹窗现在会围绕当前选中项裁剪展示可见重载窗口，顶部/底部用紧凑计数提示仍有隐藏项，选中项切换时会始终保持在可见区域内，避免长列表把弹窗内容挤得过密。
- **优化参数提示中“当前选中项”和“服务端 active 项”的视觉区分问题**：
  - 更新 [`EditorSignatureHelpPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSignatureHelpPopup.kt)，并新增 [`SignatureHelpRowPresentation.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpRowPresentation.kt)、[`SignatureHelpRowPresentationTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpRowPresentationTest.kt)：参数提示列表现在会用不同的描边和标记形态区分“用户当前选中项”“服务端当前 active 项”以及两者重合场景，减少切换多重载时的识别成本。
- **优化参数提示中当前参数的扫读效率问题**：
  - 更新 [`EditorSignatureHelpPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSignatureHelpPopup.kt)、[`SignatureHelpFormatter.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpFormatter.kt)、[`SignatureHelpFormatterTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/SignatureHelpFormatterTest.kt)：当前选中的重载行现在会额外显示一个紧凑参数预览胶囊，直接提炼出当前参数子串，切换 overload 时不必先通读整条签名也能快速确认正在填写的参数。
- **补强编辑器 Popup 共享布局回归与设备侧验收抓手**：
  - 更新 [`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)、[`EditorSelectionContextMenu.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSelectionContextMenu.kt)、[`EditorSignatureHelpPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSignatureHelpPopup.kt)、[`EditorPopupComposeSmokeTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorPopupComposeSmokeTest.kt)、[`docs/testing/README.md`](docs/testing/README.md)，并新增 [`EditorOverlaysIntegrationTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlaysIntegrationTest.kt)、[`EditorSharedPopupInstrumentationTest.kt`](core/editor-view/src/androidTest/java/com/wuxianggujun/tinaide/core/editorview/EditorSharedPopupInstrumentationTest.kt)：completion / signature help / selection menu 现在共享可注入的窗口视口度量与布局探针，补齐稳定 `testTag`、滚动与 IME 回归场景，以及真机侧 popup 交互验收入口。
- **简化编辑器标签栏的分隔线绘制**：
  - 更新 [`EditorTabBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/EditorTabBar.kt)：移除 Tab 之间额外的竖向分隔线，减少顶部栏视觉噪声，让标签选中态本身承担主层级提示。
- **修复 CMake 重新配置时 `unused-cli` 警告刷屏的问题**：
  - 更新 [`NativeCMakeBuildExecutor.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutor.kt)、[`NativeCMakeBuildExecutorConfigTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutorConfigTest.kt)：CMake configure 现在默认追加 `--no-warn-unused-cli`，避免在纯 CXX 项目或注入内部缓存项时反复出现 “Manually-specified variables were not used by the project” 噪声告警。
- **修复 SDL3 模板项目首次运行仍需手动切换 GUI 模式的问题**：
  - 更新 [`RunConfiguration.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/RunConfiguration.kt)、[`ProjectManagerViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/ProjectManagerViewModel.kt)、[`NewProjectWizardViewModel.kt`](feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardViewModel.kt)、[`RunConfigurationManagerMigrationTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/RunConfigurationManagerMigrationTest.kt)：当项目元数据标记为 `SDL3` 时，默认 run config 会直接落成 GUI 模式；模板项目创建成功后也会立即写入显式的 `.tinaide/run_configs.json`，不再要求用户先手动打开运行配置对话框切换模式。
- **修复构建成功但项目目录看不到产物、运行入口误判“无输出”的问题**：
  - 更新 [`CompileProjectUseCase.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCase.kt)、[`CompileActionsHelper.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/CompileActionsHelper.kt)、[`FileTree.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FileTree.kt)：构建报告现在会区分可执行文件、共享库和静态库，并把导出的产物路径回传给 UI；文件树也会把 `.tinaide/artifacts` 作为“构建产物”入口展示，减少 CMake 静态库 / SDL 共享库项目“明明构建成功却像什么都没有”的错觉。

### Technical
- 版本号：0.14.95 (versionCode: 1496)
- 本次聚焦：Compose 图标解析稳定性、编辑器字符集与增量高亮链路、编辑器 popup / signature help 体验、AI/工具链/LSP 文案国际化，以及若干遗留重复实现与旧文档清理
- 本次继续聚焦：原生构建产物导出与 CMake 缓存操作、SDL3 默认 GUI 运行配置，以及项目来源/工作区模型收敛
- 架构清理：删除旧的 [`BuildCache.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/BuildCache.kt)、[`BuildMetrics.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/BuildMetrics.kt)、[`ProgramRunner.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/ProgramRunner.kt)、[`WorkspaceManager.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/WorkspaceManager.kt) 等遗留实现，避免编译链路继续同时维护新旧两套职责边界
- 测试补强：为参数提示补充状态收敛、快捷键分流与布局边界单测，锁定 `Alt+↑ / Alt+↓` 多重载轮换、IME 可见区约束与上下方回退逻辑，减少后续继续调整 popup 时的回归风险
- 测试补强：继续为参数提示自动刷新链路补充 `EditorInteractionController` 单测，覆盖光标移动自动刷新、非空选区/失焦自动收起，以及文本编辑后首个同版本光标事件的抑制逻辑
- 测试补强：继续补齐参数提示上下文在注释、Kotlin 原始字符串与控制器事件链上的回归，锁定“注释中的括号不会误触发/误关闭参数提示”的场景
- 测试补强：继续补齐 Kotlin 尾随 lambda 与控制流 block 的参数提示上下文回归，锁定“尾随 lambda 内保持参数提示、离开 lambda 后及时收起”的行为
- 测试补强：继续为多重载参数提示补充可见窗口裁剪规则测试，锁定“选中项居中展示、边界时向两端贴齐”的弹窗列表行为
- 测试补强：继续为参数提示行样式状态补充纯逻辑测试，锁定“selected / active / selected+active”三种视觉语义的分流规则
- 测试补强：继续为当前参数预览补充展示规则测试，锁定“只在选中项显示预览、selected+active 时使用强调样式”的行为
- 测试补强：继续为 completion / signature help / selection menu 增加共享锚点、滚动重定位、IME 缩窗与设备侧稳定 tag 回归，避免后续调整 popup 布局时再次出现“位置变了但点击链路失效”的问题
- 测试补强：继续为 overload 切换补充参数预览联动回归，锁定“切换 displayed signature 后，当前参数预览同步切到对应签名”的行为
- 测试补强：继续为 `Loading(previousResult)` 状态补充参数预览联动回归，锁定“请求刷新尚未返回时，用户切换 overload 仍能基于旧结果看到正确预览”的行为
- 测试补强：继续为参数提示刷新结果收缩场景补充预览 clamp 回归，锁定“旧选中项越界后，索引与当前参数预览一起回落到新结果范围内”的行为
- 测试补强：继续为 `Alt+↑` 反向轮换补充 loading 状态下的回绕回归，锁定“从首项反向切换到末项时，当前参数预览同步回绕到对应签名”的行为
- 测试补强：继续为 `Alt+↑ / Alt+↓` 快捷键补充 loading 状态下的参数预览联动回归，锁定“键盘切换 overload 时，当前参数预览与选中项保持同步”的行为
- 测试补强：继续为 completion / signature popup 的位置启发式补充分支回归，锁定“下方空间虽不足最小高度但与上方接近时补全框仍贴近光标显示”和“参数提示在上下都拥挤时优先选择更大的可用侧”的行为
- 测试补强：继续为 popup chrome 与补全项高亮补充样式语义回归，锁定“亮暗主题下容器/边框/强调色角色稳定”和“补全查询字符仅高亮命中的有序字符”的行为
- 测试补强：继续为 completion kind 徽标补充样式分组回归，锁定“callable / value-like / file-like 类型沿用各自稳定的字母与色板分组”的行为
- 测试补强：继续为 completion / signature popup 补充 Compose smoke 回归，锁定“弹窗在真实组合层能渲染关键文案并正确派发点击/轮换回调”的行为
- 稳定性修复：`EditorState` 的慢操作日志节流改为 JVM 兼容的单调时钟，避免 `editor-view` 纯单测因 Android `SystemClock` stub 触发偶发失败
- 测试补强：继续为 popup 组合层补充滚动与折叠窗口交互回归，锁定“补全选中项自动滚入可见区”和“参数提示上下隐藏指示器点击后跳到正确 overload slice”的行为
- 测试补强：继续为 popup 定位解析器补充 Sora 风格布局回归，锁定“窄编辑区下 completion / signature 都保持居中宽面板”以及“参数提示在跟随光标模式下靠右时仍被裁剪进可见区”的行为

## [0.14.91] - 2026-04-02

### Added
- **本地项目导入支持压缩包入口与统一导入器**：
  - 新增 [`ProjectImporter.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectImporter.kt)，并更新 [`ProjectManagerViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/ProjectManagerViewModel.kt)、[`ProjectScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/project/ProjectScreen.kt)、[`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：本地导入现在支持“选择文件夹 / 选择压缩包”两种入口，压缩包会先解压再注册到项目列表，导入链路也统一收口到独立导入器，便于后续继续扩展来源类型。

### Changed
- **外部项目导入模型改为“保留原目录，只登记来源”**：
  - 更新 [`ProjectManagerViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/ProjectManagerViewModel.kt)、[`ProjectScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/project/ProjectScreen.kt)、[`ProjectListItem.kt`](core/model/src/main/java/com/wuxianggujun/tinaide/project/ProjectListItem.kt)、[`ProjectDialogs.kt`](feature/projectlist/src/main/java/com/wuxianggujun/tinaide/ui/projectlist/ProjectDialogs.kt)、[`ProjectListModels.kt`](feature/projectlist/src/main/java/com/wuxianggujun/tinaide/ui/projectlist/ProjectListModels.kt)、[`ProjectListComponents.kt`](feature/projectlist/src/main/java/com/wuxianggujun/tinaide/ui/projectlist/ProjectListComponents.kt)：从本地选择的文件夹不再复制进 `Documents/TinaIDE`，而是直接把当前目录注册为项目目录；项目列表新增外部来源标记，删除这类项目时也改为仅从列表移除，不再误删用户原始目录。

### Fixed
- **修复 linker64 shim 模式下 CMake/Ninja 可能把 `llvm-ar` 错误改写为 `llvm-ranlib` 的问题**：
  - 更新 [`NativeCMakeBuildExecutor.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutor.kt)、[`NativeCMakeBuildExecutorConfigTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutorConfigTest.kt)：Ninja 二进制命令映射不再让共享真实二进制的 canonical path 互相覆盖，避免静态库归档阶段生成 `llvm-ranlib qc libxxx.a` 这类错误命令，修复部分 CMake 项目在链接静态库时直接构建失败的问题。

### Technical
- 版本号：0.14.91 (versionCode: 1492)
- 本次聚焦：本地项目导入来源模型收敛、压缩包导入能力补齐、外部项目安全删除，以及 linker64 模式下 Native CMake 归档命令稳定性修复

## [0.14.90] - 2026-04-01

### Added
- **APK 导出支持按需配置 Manifest 权限并记忆上次选择**：
  - 新增 [`ApkExportPermissionProfile.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkExportPermissionProfile.kt)、[`ApkExportPermissionProfileTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/components/ApkExportPermissionProfileTest.kt)，并更新 [`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt)、[`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：APK 打包弹窗现在支持勾选常见权限、补充自定义权限，并把导出配置记到项目 `.tinaide/apk-export/permissions.json`；默认不再给导出 APK 注入固定权限集。

### Changed
- **clangd 会话收敛为“活动标签页独占 + 共享 C/C++ 会话”模型**：
  - 更新 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)、[`LspClientSession.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspClientSession.kt)：clangd 连接现在随活动标签页切换及时释放非当前页绑定，C/C++ 标签页之间复用共享会话，并为 compile setup、文档切换与请求代次做缓存和隔离，降低同一项目并发拉起多个 clangd、旧请求串页和切换标签页后状态错乱的概率。
- **LSP 导航、代码操作与重命名链路改为统一的超时感知挂起调用**：
  - 更新 [`LspCodeActionService.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspCodeActionService.kt)、[`LspNavigationService.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspNavigationService.kt)、[`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)：definition / references / implementation / workspace symbol / code action / rename 不再直接在各处阻塞等待 `CompletableFuture`，而是统一走带超时的 suspend 请求包装，减少 LSP 服务器慢响应时的卡顿与过期结果污染。
- **工具链名称与版本展示统一走国际化标签**：
  - 新增 [`ToolchainInfoDisplay.kt`](core/ndk/src/main/java/com/wuxianggujun/tinaide/core/ndk/ToolchainInfoDisplay.kt)，并更新 [`RunConfigDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/RunConfigDialog.kt)、[`CompilerSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/CompilerSettingsSection.kt)、[`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：运行配置和编译设置中的内置/自定义工具链显示改为统一的本地化名称与 `vX.Y.Z` 版本标签，减少硬编码英文和 `unknown` 占位带来的歧义。
- **APK Manifest 补丁改为结构化 AXML 重写**：
  - 更新 [`ApkBuildConfig.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkBuildConfig.kt)、[`ApkBuilder.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkBuilder.kt)、[`ManifestPatcher.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ManifestPatcher.kt)、[`build.gradle.kts`](core/apk-builder/build.gradle.kts)、[`libs.versions.toml`](gradle/libs.versions.toml)、[`ManifestPatcherTest.kt`](core/apk-builder/src/test/java/com/wuxianggujun/tinaide/core/apkbuilder/ManifestPatcherTest.kt)：Manifest 处理不再依赖固定长度占位符字符串替换，导出时可以同时写入包名、应用名、版本号和权限列表，并为 `WRITE_EXTERNAL_STORAGE` 自动补 `maxSdkVersion=29`。

### Fixed
- **修复 SDL 外部运行页退出时的 Android Surface 生命周期竞态崩溃**：
  - 更新 [`ExternalSdlActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/sdl/ExternalSdlActivity.kt)：退出 SDL 页面时改为先向原生线程发送 quit 请求，等待 SDL 线程自行回收 renderer/window 后再返回 `MainActivity`，并增加超时强制 finish 兜底，缓解 `surfaceDestroyed -> SDL_DestroyRenderer` 导致的 Android 渲染后端崩溃。
- **修复导出 APK 权限模型僵化，导致相机/存储等能力无法按项目需求准确声明的问题**：
  - 更新 [`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt)、[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)、[`AndroidManifest.xml`](tools/template-sdl3/src/main/AndroidManifest.xml)、[`AndroidManifest.xml`](tools/template-native-activity/src/main/AndroidManifest.xml)、[`TemplateNativeActivity.java`](tools/template-native-activity/src/main/java/com/tinaide/template/nativeactivity/TemplateNativeActivity.java)：导出模板 Manifest 不再写死相机/媒体/存储权限，最终 APK 权限集合改为由导出配置生成；NativeActivity 壳会按最终 Manifest 动态拆分特殊权限与危险权限并逐步申请，宿主 App 也补充了可选 `CAMERA` 声明，减少权限声明和运行时申请不一致的问题。
- **修复导出 APK 从外部输出目录直接安装或分享时可能失败的问题**：
  - 更新 [`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt)、[`ExternalFileIntents.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ExternalFileIntents.kt)：安装/分享前会先确保 APK 位于 `FileProvider` 可安全暴露的路径，避免自定义输出目录下的产物因 Uri 不可访问而导致安装或分享失败。
- **修复 linker64 模式下 Native CMake 归档工具路径解析不正确的问题**：
  - 更新 [`NativeCMakeBuildExecutor.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutor.kt)、[`NativeCMakeBuildExecutorConfigTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutorConfigTest.kt)：CMake configure/build 现在会显式传递 `CMAKE_AR`、`CMAKE_RANLIB`，并在 linker64 模式下为 `AR`/`RANLIB` 使用真实 shim 路径，减少静态库归档、try_compile 或配置阶段因工具命令解析错误导致的失败。

### Technical
- 版本号：0.14.90 (versionCode: 1491)
- 本次聚焦：clangd 会话隔离与请求稳定性、SDL Android 退出链路稳定性、APK 导出权限可配置化与安装分享链路修复、linker64 下 Native CMake 归档工具稳定性

## [0.14.89] - 2026-03-30

### Added
- **内置 CMake 语言服务与隔离验证开关正式落地**：
  - 新增 [`CMakeLanguageSupport.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/CMakeLanguageSupport.kt)、[`CMakeLanguageServiceSession.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/CMakeLanguageServiceSession.kt)，并更新 [`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)、[`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)、[`Prefs.kt`](core/config/src/main/java/com/wuxianggujun/tinaide/core/config/Prefs.kt)、[`DeveloperOptionsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/DeveloperOptionsSection.kt)、[`TreeSitterTestScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/TreeSitterTestScreen.kt)：为 `CMakeLists.txt` / `.cmake` 提供内置 completion、semantic tokens、diagnostics、document symbols、definition / references / hover，并新增“内置 CMake LSP”开发者开关，方便与纯 Tree-sitter 高亮分离验证。
  - 新增 [`CMakeLanguageServiceSessionTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/CMakeLanguageServiceSessionTest.kt)、[`TinaCodeEditorSemanticTokenMappingTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorSemanticTokenMappingTest.kt)：补齐 CMake 内置语言服务与语义 token 映射回归测试。
- **编辑器 Hover 交互与 Markdown 浮层能力**：
  - 新增 [`strings.xml`](core/editor-view/src/main/res/values/strings.xml)、[`strings.xml`](core/editor-view/src/main/res/values-en/strings.xml)、[`EditorHoverStateTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorHoverStateTest.kt)，并更新 [`EditorSelectionContextMenu.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSelectionContextMenu.kt)、[`EditorSelectionContextMenuCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSelectionContextMenuCoordinator.kt)、[`EditorInteractionController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionController.kt)、[`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)、[`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`TinaEditorScaffold.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorScaffold.kt)：选中文本后的上下文菜单现在可以直接触发 Hover，请求过程中显示加载态，返回内容通过浮层展示并支持关闭。
- **Tree-sitter 增量高亮基础设施与状态机测试**：
  - 新增 [`SafeTsTree.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/SafeTsTree.kt)、[`TreeSitterIncrementalSupport.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterIncrementalSupport.kt)、[`IncrementalTreeSitterHighlightState.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/IncrementalTreeSitterHighlightState.kt)、[`IncrementalTreeSitterHighlightStateTest.kt`](core/tree-sitter/src/test/java/com/wuxianggujun/tinaide/core/treesitter/IncrementalTreeSitterHighlightStateTest.kt)、[`TreeSitterIncrementalSupportTest.kt`](core/tree-sitter/src/test/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterIncrementalSupportTest.kt)：高亮器现在具备 `openDocument`、`applyTextChange`、按行读取 segments 与前后台树快照切换能力，为编辑器实时高亮稳定化打基础。
- **GUI 运行时共享库 staging 与回归测试**：
  - 新增 [`GuiRuntimeLibraryStager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/runtime/GuiRuntimeLibraryStager.kt)、[`GuiRuntimeLibraryStagerTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/runtime/GuiRuntimeLibraryStagerTest.kt)，并更新 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`GuiHostActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/gui/GuiHostActivity.kt)：GUI / SDL 启动前会把项目构建目录中的主库与同目录依赖 staging 到 app 私有目录，installed-packages 中已位于私有目录的运行时库保持原路径，规避 Android 高版本直接从公有目录加载 `.so` 的 namespace / 权限限制。
- **Make 产物定位器与第三方 Makefile 兼容兜底**：
  - 新增 [`MakeBuildOutputLocator.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/MakeBuildOutputLocator.kt)：对没有执行位的公有目录 ELF 也会按 ELF 头识别，优先使用项目 `build` 目录中的产物，同时继续兼容第三方 / 开源 Makefile 将可执行文件直接输出到项目根目录的情况。
- **Android 原生构建 smoke 排障文档与对照诊断入口**：
  - 新增 [`android-native-cmake-smoke-issues-2026-03-31.md`](docs/troubleshooting/android-native-cmake-smoke-issues-2026-03-31.md)、[`NativeCMakeBuildExecutorConfigTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutorConfigTest.kt)、[`EffectiveBuildConfigResolverTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/EffectiveBuildConfigResolverTest.kt)、[`NativeMakeBuildStrategyTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/NativeMakeBuildStrategyTest.kt)、[`CompileCommandsNormalizerTest.kt`](core/lsp/src/test/java/com/wuxianggujun/tinaide/core/lsp/CompileCommandsNormalizerTest.kt)、[`DefaultCompletionProviderTest.kt`](core/editor-lsp/src/test/java/com/wuxianggujun/tinaide/core/editorlsp/DefaultCompletionProviderTest.kt)，并更新 [`CompilerDiagnosticsTestScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/CompilerDiagnosticsTestScreen.kt)、[`CompilerProjectSmokeTests.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/CompilerProjectSmokeTests.kt)：开发者测试页新增 launcher chain 对照诊断与更清晰的 smoke 输出，方便区分 configure / clangd / UI 聚合失败这几类问题。

### Changed
- **编辑器高亮主链路重构为“高亮器持久状态驱动渲染”**：
  - 更新 [`TextRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt)、[`EditorTextBufferRendererSyncEffect.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorTextBufferRendererSyncEffect.kt)、[`TreeSitterHighlighter.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterHighlighter.kt)、[`EditorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderer.kt)、[`EditorRenderEngine.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderEngine.kt)、[`EditorRuntimeEffects.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRuntimeEffects.kt)：渲染层不再维护旧的可见区异步高亮缓存，而是直接读取高亮器按行产出的结果，输入和滚动时的高亮稳定性、缓存复用与职责边界进一步接近 Sora Editor。
  - 删除 [`VisibleHighlightCacheUpdaterTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/VisibleHighlightCacheUpdaterTest.kt)，原有缓存平移与回归场景统一迁移到新的 Tree-sitter 增量测试体系。
- **编辑器状态、菜单与滚动联动继续收口**：
  - 更新 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)、[`EditorScrollDeltaCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScrollDeltaCoordinator.kt)、[`EditorSessionGestureRuntime.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSessionGestureRuntime.kt)：上下文菜单、浮层、滚动与手势状态之间的同步逻辑更集中，便于后续继续扩展编辑器交互。
- **MarkdownViewer 升级为可复用的悬停内容渲染器**：
  - 更新 [`MarkdownViewer.kt`](core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/MarkdownViewer.kt)：Markdown AST 解析改为后台线程 `collectLatest`，并补齐链接打开与代码复制回调，Hover 浮层和其他 Markdown 展示场景可以共享同一套渲染能力。
- **设计文档与验证记录补齐**：
  - 新增 [`editor-sora-highlight-refactor.md`](docs/editor-sora-highlight-refactor.md)、[`editor-hover-analysis.md`](docs/editor-hover-analysis.md)、[`editor-highlight-flicker-analysis.md`](docs/editor-highlight-flicker-analysis.md)：沉淀高亮重构目标、Hover 交互分析与闪烁问题根因，方便后续继续迭代。
- **原生构建目录模型重新收敛为“项目目录构建 + 私有目录运行”**：
  - 更新 [`ProjectPaths.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectPaths.kt)、[`ProjectLocationManager.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectLocationManager.kt)、[`CompileProjectUseCase.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCase.kt)、[`CompileDatabaseProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/CompileDatabaseProvider.kt)、[`build-project.md`](app/src/main/assets/help/build-project.md)、[`cmake-guide.md`](app/src/main/assets/help/cmake-guide.md)、[`cmake-guide.md`](feature/help/src/main/assets/help/cmake-guide.md)：项目 `build`、`compile_commands.json` 与 APK 输出重新回到项目目录，IDE 与帮助文档同步按项目目录定位；真正需要执行的 GUI / SDL 产物仍走私有 staging，不再把整个构建目录整体塞回私有 build-workspace。
- **Make / 单文件运行配置补齐显式 Build Type，并统一透传到模板与构建命令**：
  - 更新 [`RunConfiguration.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/RunConfiguration.kt)、[`RunConfigDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/RunConfigDialog.kt)、[`NativeMakeBuildStrategy.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/NativeMakeBuildStrategy.kt)、[`PRootMakeBuildStrategy.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/PRootMakeBuildStrategy.kt)、[`make_executable.zip`](app/src/main/assets/templates/make_executable.zip)、[`MakeTemplateRegressionTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/MakeTemplateRegressionTest.kt)、[`RunConfigurationManagerMigrationTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/RunConfigurationManagerMigrationTest.kt)：Make / Single File 现在可直接选择 Debug / Release，模板会透传 `BUILD_TYPE`，默认输出规范到 `build/$(BUILD_TYPE)`，但仍兼容第三方 / 开源 Makefile 的根目录产物布局。
- **CMake 原生 configure/build/clean 链路重新收敛到真实编译器路径与受控 launcher**：
  - 更新 [`NativeCMakeBuildExecutor.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutor.kt)、[`CMakeBuildStrategy.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/CMakeBuildStrategy.kt)、[`NinjaCmakePathPatcher.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NinjaCmakePathPatcher.kt)、[`ToolchainLinker64ShimManager.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/toolchain/ToolchainLinker64ShimManager.kt)、[`TinaExecRuntime.kt`](external/tina-exec/integration/src/main/java/com/wuxianggujun/tinaide/exec/TinaExecRuntime.kt)、[`CMakeLists.txt`](external/tina-exec/runtime/src/main/cpp/CMakeLists.txt)：CMake 缓存校验改为对比真实 clang / clang++ 路径，configure / build / clean 显式禁用 tina-exec preload 污染，Ninja 补丁会覆盖嵌套工具命令；`tina-exec` 继续保留给通用 native 执行链，但不再充当 CMake try_compile 的编译器入口。
- **clangd / compile_commands / 编辑器补全链路继续按真实项目布局归一化**：
  - 更新 [`CompileCommandsNormalizer.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/CompileCommandsNormalizer.kt)、[`CompileDatabaseProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/CompileDatabaseProvider.kt)、[`ProjectSyncManager.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/ProjectSyncManager.kt)、[`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)、[`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)、[`CMakeLanguageSupport.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/CMakeLanguageSupport.kt)、[`CompletionProvider.kt`](core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/CompletionProvider.kt)、[`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)：compile database 直接在项目构建目录内归一化，linker64 / `sh` / shim 命令会回写成 clang / clang++ 供 clangd 消费，补全候选去重、大小写匹配与本地 / LSP 混排策略也继续优化。
- **编辑器交互与文本/高亮状态继续打磨**：
  - 更新 [`EditorGestureHandler.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureHandler.kt)、[`EditorGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureCoordinator.kt)、[`EditorSessionCoreRuntime.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSessionCoreRuntime.kt)、[`EditorConfig.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorConfig.kt)、[`RopeTextBuffer.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/RopeTextBuffer.kt)、[`IncrementalTreeSitterHighlightState.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/IncrementalTreeSitterHighlightState.kt)、[`EditorGestureHandlerTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureHandlerTest.kt)、[`RopeTextBufferTest.kt`](core/text-engine/src/test/java/com/wuxianggujun/tinaide/core/textengine/RopeTextBufferTest.kt)、[`TreeSitterHighlighterTest.kt`](core/tree-sitter/src/test/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterHighlighterTest.kt)：双击识别、手势抑制、Rope 替换终点列、changed 节点高亮查询容错进一步稳定。

### Fixed
- **修复编辑器输入时高亮闪烁、当前行掉色与旧缓存回填抖动问题**：
  - 更新 [`TextRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt)、[`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`TreeSitterHighlighter.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterHighlighter.kt)、[`IncrementalTreeSitterHighlightState.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/IncrementalTreeSitterHighlightState.kt)：文本变化后不再先把当前行刷回默认色再等待后台结果，减少输入过程中的“白一下再恢复”现象。
- **修复 Hover 请求时序、异常回收与展示可读性问题**：
  - 更新 [`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`EditorInteractionController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionController.kt)、[`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)、[`MarkdownViewer.kt`](core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/MarkdownViewer.kt)：Hover 请求失败时会正确收起浮层，加载态与 Markdown 内容展示也更加清晰。
- **修复 Tree-sitter capture 归类与 CMake 注释着色不准确的问题**：
  - 更新 [`HighlightModels.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/HighlightModels.kt)、[`TreeSitterHighlighter.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterHighlighter.kt)、[`EditorColorScheme.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorColorScheme.kt)、[`TreeSitterHighlighterTest.kt`](core/tree-sitter/src/test/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterHighlighterTest.kt)：`property / field / member`、`constant / builtin` 与 `@spell / @none` 这类 capture 的处理更准确，CMake `# comment` 不会再被默认文本色覆盖，注释、常量和属性颜色表现更稳定。
- **修复 Make / 单文件运行配置在 Debug 场景下仍被错误解析为 Release 的问题**：
  - 更新 [`EffectiveBuildConfigResolver.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/EffectiveBuildConfigResolver.kt)、[`RunConfiguration.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/RunConfiguration.kt)、[`RunConfigDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/RunConfigDialog.kt)、[`EffectiveBuildConfigResolverTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/EffectiveBuildConfigResolverTest.kt)、[`RunConfigurationManagerMigrationTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/RunConfigurationManagerMigrationTest.kt)：非 CMake 场景不再默认强制回落到 Release，开发者选项里的 Make / 单文件项目可按真实选择进入 Debug 构建。
- **修复高版本 Android 上 GUI / SDL 直接加载项目公有目录 `.so` 失败的问题**：
  - 更新 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`GuiHostActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/gui/GuiHostActivity.kt)、[`GuiRuntimeLibraryStager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/runtime/GuiRuntimeLibraryStager.kt)、[`GuiRuntimeLibraryStagerTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/runtime/GuiRuntimeLibraryStagerTest.kt)：GUI 主库及同目录依赖会先 staging 到私有目录再启动，避免 namespace 拒绝与执行权限问题。
- **修复 Android 原生 CMake smoke test 中 configure/try_compile、Ninja 自动重配与 clangd 误报串在一起的假失败**：
  - 更新 [`NativeCMakeBuildExecutor.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutor.kt)、[`NinjaCmakePathPatcher.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NinjaCmakePathPatcher.kt)、[`CompileCommandsNormalizer.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/CompileCommandsNormalizer.kt)、[`CompileDatabaseProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/CompileDatabaseProvider.kt)、[`ProjectSyncManager.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/ProjectSyncManager.kt)、[`CompilerDiagnosticsTestScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/CompilerDiagnosticsTestScreen.kt)、[`CompilerProjectSmokeTests.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/CompilerProjectSmokeTests.kt)：CMake configure 失败信息更可读，try_compile 假阴性与 compile_commands 启动链污染得到隔离，开发者测试页也能更明确区分“构建已成功但 clangd / UI 仍失败”的场景。
- **修复编辑器补全结果质量、手势时序和底层文本/高亮边界问题**：
  - 更新 [`CompletionProvider.kt`](core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/CompletionProvider.kt)、[`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`EditorGestureHandler.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureHandler.kt)、[`RopeTextBuffer.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/RopeTextBuffer.kt)、[`IncrementalTreeSitterHighlightState.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/IncrementalTreeSitterHighlightState.kt)、[`DefaultCompletionProviderTest.kt`](core/editor-lsp/src/test/java/com/wuxianggujun/tinaide/core/editorlsp/DefaultCompletionProviderTest.kt)、[`EditorCompletionStateTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionStateTest.kt)、[`EditorGestureHandlerTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureHandlerTest.kt)、[`RopeTextBufferTest.kt`](core/text-engine/src/test/java/com/wuxianggujun/tinaide/core/textengine/RopeTextBufferTest.kt)：补全去重与排序更稳定，双击 / 抬手抑制更准确，多行替换的结束列与 changed 节点高亮查询也不再产生异常或错位。

### Technical
- 版本号：0.14.89 (versionCode: 1490)
- 本次聚焦：内置 CMake 语言服务与编辑器高亮稳定化，外加原生 CMake / Make 构建链、compile_commands / clangd、GUI / SDL 私有 staging 与开发者 smoke test 排障闭环的收敛

## [0.14.87] - 2026-03-27

### Added
- **项目页通知中心与公告已读状态正式落地**：
  - 新增 [`NotificationCenterDialog.kt`](feature/projectlist/src/main/java/com/wuxianggujun/tinaide/ui/projectlist/NotificationCenterDialog.kt)：项目首页铃铛入口升级为通知中心，支持全部/未读筛选、批量标记已读，以及从列表进入单条公告详情。
  - 更新 [`ProjectManagerViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/ProjectManagerViewModel.kt)、[`ProjectScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/project/ProjectScreen.kt)、[`ProjectListModels.kt`](feature/projectlist/src/main/java/com/wuxianggujun/tinaide/ui/projectlist/ProjectListModels.kt)、[`TinaServerApi.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/api/TinaServerApi.kt)：公告状态改为本地持久化的通知流，补齐已读时间、过期时间、首次弹窗记录和服务端同步。
- **已安装包元数据读取与版本来源展示能力**：
  - 新增 [`InstalledPackageMetadataReader.kt`](core/packages/src/main/java/com/wuxianggujun/tinaide/core/packages/InstalledPackageMetadataReader.kt)：从安装目录中的 `package.json` 读取包版本、上游版本、ABI 与主页等元数据。
  - 更新 [`PackageManagerViewModel.kt`](feature/packages/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/packages/PackageManagerViewModel.kt)、[`PackageManagerScreen.kt`](feature/packages/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/packages/PackageManagerScreen.kt)、[`PackageDetailScreen.kt`](feature/packages/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/packages/PackageDetailScreen.kt)：包卡片和详情页可直接展示安装包版本、上游库版本与修订号。
- **Android 包维护脚本补齐**：
  - 新增 [`build-imgui-android-package.ps1`](scripts/build-imgui-android-package.ps1)、[`repair-sdl3-bundled-package.ps1`](scripts/repair-sdl3-bundled-package.ps1)、[`CMakeLists.txt`](scripts/cmake/imgui-android-gl3/CMakeLists.txt)：补齐 ImGui Android 共享库打包与 SDL3 内置包元数据修复脚本，方便维护 bundled package 资产。
- **真实项目编译 / clangd 闭环诊断与对照测试**：
  - 新增 [`CompilerProjectSmokeTests.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/CompilerProjectSmokeTests.kt)、[`PRootRuntimeSmokeTest.kt`](app/src/androidTest/java/com/wuxianggujun/tinaide/core/proot/PRootRuntimeSmokeTest.kt)，并更新 [`CompilerDiagnosticsTestScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/CompilerDiagnosticsTestScreen.kt)、[`AndroidNativeToolchainSmokeTest.kt`](app/src/androidTest/java/com/wuxianggujun/tinaide/core/ndk/AndroidNativeToolchainSmokeTest.kt)：开发者工具现在可以直接跑 single-file / CMake / SDL3 真实项目闭环、PRoot 运行时校验与 Android 工具链 smoke test，对比构建耗时、diagnostics 和补全命中结果。
- **本地 `tina-exec` runtime / integration 模块纳入工程**：
  - 新增 [`external/tina-exec/runtime/`](external/tina-exec/runtime/)、[`external/tina-exec/integration/`](external/tina-exec/integration/)，并更新 [`settings.gradle.kts`](settings.gradle.kts)、[`build.gradle.kts`](build.gradle.kts)、[`core/common/build.gradle.kts`](core/common/build.gradle.kts)、[`core/proot/build.gradle.kts`](core/proot/build.gradle.kts)：将 vendored `tina-exec` 模块正式编入构建，后续原生执行、PRoot 和 rsync 可统一复用同一套 preload/runtime 能力。

### Changed
- **公告详情改为 Markdown 友好的通知阅读体验**：
  - 更新 [`AnnouncementDialog.kt`](feature/projectlist/src/main/java/com/wuxianggujun/tinaide/ui/projectlist/AnnouncementDialog.kt)、[`MarkdownViewer.kt`](core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/MarkdownViewer.kt)、[`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：公告详情页切换到 Markdown 渲染、统一 Material 3 视觉和更完整的国际化文案。
- **包管理下载 / 内置安装链路补齐 tar 系列归档与元数据回填**：
  - 更新 [`DownloadPackageBackend.kt`](core/packages/src/main/java/com/wuxianggujun/tinaide/core/packages/backend/DownloadPackageBackend.kt)、[`BundledPackagesInstaller.kt`](core/packages/src/main/java/com/wuxianggujun/tinaide/core/packages/BundledPackagesInstaller.kt)：下载包现在支持 `zip / tar / tar.gz / tgz / tar.xz / txz / tar.zst`，同时会校验归档路径安全、自动识别归档格式，并在内置包缺少 `package.json` 时触发重装修复。
- **编辑器补全、高亮与语义 token 链路收口到“可退化、可缓存”的状态模型**：
  - 更新 [`CompletionProvider.kt`](core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/CompletionProvider.kt)、[`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)、[`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)、[`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`TextRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt)：LSP completion 改为显式区分成功/瞬时失败/超时的结果模型，首屏可预热，加载中会保留上一批候选；可见区高亮缓存与语义 token 也支持增量复用，减少补全闪烁与高亮重复计算。
  - 更新 [`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)、[`EditorKeyboardShortcuts.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorKeyboardShortcuts.kt)、[`Completion-Performance-Analysis.md`](docs/design/Completion-Performance-Analysis.md)、[`TinaEditor-Highlight-Pipeline-Review.md`](docs/design/TinaEditor-Highlight-Pipeline-Review.md)：补全弹层、快捷键行为和设计文档统一对齐新的状态机与性能分析结论。
- **原生执行 / 构建 / 格式化 / rsync / PRoot 链路统一注入 `tina-exec`**：
  - 更新 [`NativeExecutableRunner.kt`](core/common/src/main/java/com/wuxianggujun/tinaide/core/util/NativeExecutableRunner.kt)、[`AndroidElfExecutor.kt`](core/common/src/main/java/com/wuxianggujun/tinaide/core/util/AndroidElfExecutor.kt)、[`ProgramRunner.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/ProgramRunner.kt)、[`NativeMakeBuildStrategy.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/NativeMakeBuildStrategy.kt)、[`SingleFileBuildStrategy.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/SingleFileBuildStrategy.kt)、[`NativeCMakeBuildExecutor.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutor.kt)、[`NativeCodeFormatter.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/format/NativeCodeFormatter.kt)、[`RsyncSyncProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/RsyncSyncProvider.kt)：native 进程启动时会按 direct/linker 模式自动注入合适的 preload，编译、格式化和 rsync 的派生子进程行为也跟主执行链统一。
- **APK 导出模板升级为带权限引导的可执行壳**：
  - 更新 [`tools/template-native-activity/build.gradle.kts`](tools/template-native-activity/build.gradle.kts)、[`tools/template-native-activity/src/main/AndroidManifest.xml`](tools/template-native-activity/src/main/AndroidManifest.xml)、[`tools/template-native-activity/src/main/java/com/tinaide/template/nativeactivity/TemplateNativeActivity.java`](tools/template-native-activity/src/main/java/com/tinaide/template/nativeactivity/TemplateNativeActivity.java)、[`tools/template-sdl3/build.gradle.kts`](tools/template-sdl3/build.gradle.kts)、[`tools/template-sdl3/src/main/AndroidManifest.xml`](tools/template-sdl3/src/main/AndroidManifest.xml)、[`tools/template-sdl3/src/main/java/com/tinaide/template/sdl3/TemplateSDLActivity.java`](tools/template-sdl3/src/main/java/com/tinaide/template/sdl3/TemplateSDLActivity.java)、[`app/src/main/assets/apk_templates/README.md`](app/src/main/assets/apk_templates/README.md)：NativeActivity / SDL3 模板补齐 `XXPermissions` 权限引导、专用 Activity 和新版 manifest，导出的 APK 首次启动即可完成存储权限自引导。

### Fixed
- **修复编辑器高亮优先级与 C/C++ 语义着色不准确的问题**：
  - 更新 [`EditorColorScheme.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorColorScheme.kt)、[`TextRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt)、[`TreeSitterHighlighter.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterHighlighter.kt)、[`highlights.scm`](core/tree-sitter/src/main/assets/tree-sitter-queries/cpp/highlights.scm)：枚举成员、布尔字面量、控制流关键字和常量的颜色归类更准确，重叠高亮的覆盖优先级也得到修正。
  - 新增 [`EditorColorSchemeTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorColorSchemeTest.kt)、[`TreeSitterHighlighterTest.kt`](core/tree-sitter/src/test/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterHighlighterTest.kt)，并更新 [`TextRenderPlannerTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/TextRenderPlannerTest.kt)：为语义着色和覆盖优先级补齐回归测试。
- **修复 SDL3 内置包和 APK 签名链路的兼容性与报错可读性问题**：
  - 更新 [`sdl3.tar.xz`](app/src/main/assets/bundled_packages/sdl3.tar.xz)、[`ApkSigner.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkSigner.kt)、[`ApkSignerTest.kt`](core/apk-builder/src/test/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkSignerTest.kt)、[`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt)：SDL3 bundled package 补齐元数据，APK 签名链路收敛到 v2/v3，并在界面中区分签名失败与普通构建失败的错误提示。
- **修复 LSP 导航服务读取旧版 `SymbolInformation` 时的 Kotlin 废弃告警**：
  - 更新 [`LspNavigationService.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspNavigationService.kt)：将旧版 `SymbolInformation` 转换逻辑收敛到独立兼容 helper，精确约束废弃抑制范围。
- **修复编辑器 IME 选区同步、Shift 扩选与 UTF-16 列定位问题**：
  - 更新 [`EditorInputConnection.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInputConnection.kt)、[`EditorInteractionController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionController.kt)、[`EditorInputConnectionUtils.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInputConnectionUtils.kt)、[`LspSemanticTokenDecoderTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspSemanticTokenDecoderTest.kt)：方向键扩选、HOME/END 选区同步、代理对字符周围的 IME 选区映射和 LSP UTF-16 列偏移处理统一修正，减少中文/Emoji 场景下的光标错位。
  - 新增 [`EditorInputConnectionImeSelectionTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorInputConnectionImeSelectionTest.kt)、[`EditorStateSemanticTokensTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorStateSemanticTokensTest.kt)、[`VisibleHighlightCacheUpdaterTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/VisibleHighlightCacheUpdaterTest.kt)：补齐 IME 选区、语义 token 增量合并和可见高亮缓存更新的回归覆盖。
- **修复 `compile_commands` 复用判断不足导致的 clangd 误报与冷启动排障困难问题**：
  - 更新 [`CompileDatabaseProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/CompileDatabaseProvider.kt)、[`NativeClangdConnectionProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/NativeClangdConnectionProvider.kt)、[`PRootClangdConnectionProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/PRootClangdConnectionProvider.kt)：single-file 项目会按 C++ 标准和依赖指纹判断是否必须重建 `compile_commands.json`，clangd 启动前也会输出数据库摘要，减少 SDL3 / CMake 项目“明明能编过、编辑器却误报”的排查成本。
- **修复项目内 `compile_commands.json` 与 clangd 消费副本不同步，导致 Android NDK / PRoot 解析不稳定的问题**：
  - 新增 [`CompileCommandsNormalizer.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/CompileCommandsNormalizer.kt)，并更新 [`CompileCommandsGenerator.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/CompileCommandsGenerator.kt)、[`CompileDatabaseProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/CompileDatabaseProvider.kt)、[`NativeClangdConnectionProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/NativeClangdConnectionProvider.kt)、[`PRootClangdConnectionProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/PRootClangdConnectionProvider.kt)：项目内的 `compile_commands.json` 保持为用户可见源文件，clangd 统一改用私有构建目录里的规范化副本；同步阶段会剥离 `linker64` / `toolchain-shims` 包装，并补齐 `-resource-dir`，降低 `stdarg.h` 等系统头文件误报概率。
  - 更新 [`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)、[`CompilerProjectSmokeTests.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/CompilerProjectSmokeTests.kt)：保存 `CMakeLists.txt`、`.cmake` 或项目内 `compile_commands.json` 后，会刷新 compile database 并重启 clangd，真实项目 smoke test 也同步切到新的 `compileCommandsDir` 参数。
- **修复属性/成员捕获被错误降级为普通变量，且 `PROPERTY` 渲染分支遗漏的问题**：
  - 更新 [`HighlightModels.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/HighlightModels.kt)、[`TreeSitterHighlighter.kt`](core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterHighlighter.kt)、[`TreeSitterHighlighterTest.kt`](core/tree-sitter/src/test/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterHighlighterTest.kt)、[`EditorColorScheme.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorColorScheme.kt)、[`TextRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt)：Tree-sitter 现在会把 `property / field / member` 单独归类为 `PROPERTY`，泛化 `identifier` 回退为 `DEFAULT`，属性颜色映射与覆盖优先级也同步补齐，避免成员名被整体刷成变量色或触发 `when` 非穷尽编译错误。

### Technical
- 版本号：0.14.87 (versionCode: 1488)
- 本次聚焦：通知中心与公告已读模型、包管理归档/元数据增强、编辑器/LSP 闭环诊断与高亮修复、`tina-exec` 原生执行链路、SDL3/APK 模板权限与打包链路整理

## [0.14.85] - 2026-03-27

### Added
- **项目 APK 导出能力自动识别与导出依赖补齐**：
  - 新增 [`ProjectApkExportSupportResolver.kt`](core/project/src/main/java/com/wuxianggujun/tinaide/project/ProjectApkExportSupportResolver.kt)、[`ApkExportRuntimeLibrariesResolver.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/apk/ApkExportRuntimeLibrariesResolver.kt)、[`ApkSigningConfig.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkSigningConfig.kt)、[`ApkKeyStoreManager.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkKeyStoreManager.kt)：项目列表和打包对话框可以自动判断项目是否适合 `NativeActivity` / `SDL3` 导出，导出时会递归补齐依赖 `.so`，并统一支持调试签名与自定义签名。
- **Rootfs 配置档与访客包管理能力**：
  - 新增 `RootfsProfile*`、`GuestSystemPackageManager`、`ApkGuestPackageManager` 及对应测试，Linux 环境配置不再只依赖单一路径探测，后续可基于配置档做导入、检查、修复与开发包安装。

### Changed
- **项目存储升级为“公有源码 + 私有构建”并兼容双目录项目列表**：
  - 更新 [`ProjectLocationManager.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectLocationManager.kt)、[`ProjectPaths.kt`](core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectPaths.kt)、[`ProjectMetadata.kt`](core/model/src/main/java/com/wuxianggujun/tinaide/project/ProjectMetadata.kt)、[`ProjectManagerViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/ProjectManagerViewModel.kt)、[`ProjectScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/project/ProjectScreen.kt)：新建项目、项目元数据和项目列表统一收口到位置模型，支持同时识别公有目录项目与旧私有目录项目，默认把源码与构建产物拆到更合理的位置。
- **终端 / 工作区配置页继续收口到可修复、可引导的安装流程**：
  - 更新 [`TerminalSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/TerminalSettingsSection.kt)、[`StorageSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/StorageSettingsSection.kt)、[`ProjectSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/ProjectSettingsSection.kt)、[`LocaleInstaller.kt`](feature/terminal/src/main/java/com/wuxianggujun/tinaide/terminal/locale/LocaleInstaller.kt)、[`ZshInstaller.kt`](feature/terminal/src/main/java/com/wuxianggujun/tinaide/terminal/shell/ZshInstaller.kt)：设置页补齐工作区权限、默认存储位置、LSP 工具链与终端环境安装入口，用户不必再分散到多个页面处理环境问题。
- **本地构建链路在 Android / Windows 双端继续增强稳定性**：
  - 更新 [`build.gradle.kts`](build.gradle.kts)、[`external/tina-android-tree-sitter/build.gradle.kts`](external/tina-android-tree-sitter/build.gradle.kts)、[`NativeCMakeBuildExecutor.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutor.kt)、[`CompileProjectUseCase.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCase.kt)：Windows 主机上的 Android Library 构建输出改为按会话隔离，降低 `bundleLibCompileToJarDebug` 的 `classes.jar` 文件锁失败概率；CMake / Make / GUI 模式的路径、目标与诊断链路也继续收口。

### Fixed
- **修复 APK 导出链路只打主程序 `.so`、安装无反馈、签名不稳的问题**：
  - 更新 [`ApkBuilder.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkBuilder.kt)、[`ApkSigner.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkSigner.kt)、[`ManifestPatcher.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ManifestPatcher.kt)、[`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt)：导出 APK 现在会打入依赖 `.so`、显式申请安装未知来源权限、修复二进制 Manifest 替换后的签名链路，并在安装流程中给出明确反馈。
- **修复 SDL3 导出模板仍会显示顶栏 / toolbar 的问题**：
  - 更新 [`tools/template-sdl3/src/main/AndroidManifest.xml`](tools/template-sdl3/src/main/AndroidManifest.xml)、[`TemplateSDLActivity.java`](tools/template-sdl3/src/main/java/com/tinaide/template/sdl3/TemplateSDLActivity.java)、[`tools/template-native-activity/src/main/AndroidManifest.xml`](tools/template-native-activity/src/main/AndroidManifest.xml)、[`ApkTemplate.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkTemplate.kt)：模板清单统一切到无标题全屏主题，SDL3 模板新增专用 Activity 在生命周期内强制恢复沉浸式全屏，并在升级后优先从 `assets/apk_templates/` 刷新模板缓存，避免用户继续命中旧模板。

### Technical
- 版本号：0.14.85 (versionCode: 1486)
- 本次聚焦：项目公有 / 私有目录兼容、APK 导出能力自动识别与签名安装链路、SDL3 模板全屏修复、Rootfs / 终端环境安装收口，以及 Windows 本地构建文件锁稳定性

## [0.14.84] - 2026-03-25

### Added
- **公告弹窗支持领取 AI 福利额度**：
  - 更新 [`TinaServerApi.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/api/TinaServerApi.kt)、[`ProjectListModels.kt`](feature/projectlist/src/main/java/com/wuxianggujun/tinaide/ui/projectlist/ProjectListModels.kt)：公告接口模型新增奖励额度、领取状态、奖励过期时间与领取响应定义，App 端可以识别“可领取公告福利”。
  - 更新 [`ProjectManagerViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/ProjectManagerViewModel.kt)、[`ProjectScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/project/ProjectScreen.kt)：公告弹窗接入领取动作、成功 Toast、奖励摘要和过期时间展示，用户可直接在 App 内领取赠送额度。

### Changed
- **项目列表公告展示改为奖励感知模型**：
  - 更新 [`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：补充公告奖励可领取、已领取、有效期和领取成功等国际化文案。
  - 更新 [`ProjectManagerViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/ProjectManagerViewModel.kt)：公告内容拼装从“只展示正文”调整为“正文 + 奖励摘要”，并将公告操作统一升级为异步结果返回，避免领取奖励时 UI 只能做静态跳转。

### Fixed
- **修复书签跳转在文件行数变化后可能触发编辑器崩溃的问题**：
  - 更新 [`BookmarksContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BookmarksContent.kt)、[`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)、[`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)：当书签、外部导航或选区请求携带的行列位置已超过当前文档边界时，统一夹紧到有效范围，避免旧书签指向已缩短文件时把非法行号继续传入文本引擎。
  - 更新 [`EditorStateEventTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorStateEventTest.kt)：新增越界跳转回归测试，覆盖超大行号和列号会被收敛到最后有效位置的场景。

### Technical
- 版本号：0.14.84 (versionCode: 1485)
- 本次聚焦：修复书签/外部导航越界位置导致的编辑器崩溃，补齐公告福利领取的 App 侧交互链路与国际化文案

## [0.14.83] - 2026-03-24

### Added
- **统一的 Tina Server 鉴权会话管线**：
  - 新增 `TinaServerHttpClientFactory`、`TinaServerAuthAuthenticator`、`TinaServerSessionCoordinator` 与 `TinaServerSessionProvider`，把带登录态的 OkHttp 客户端、401 自动刷新与单次重试逻辑集中到 `core:auth`，避免各业务模块各自拼接鉴权能力。
  - 新增 `core/auth/src/test/.../TinaServerAuthPipelineTest.kt`：覆盖请求自动附带 Bearer Token、401 后刷新并重放一次、刷新接口与重复重试保护等关键行为。
- **可安装的项目模板插件能力**：
  - 新增 `contributions.projectTemplates` 插件贡献点，允许插件携带 zip 模板资源，并在安装后把模板动态注册到新建项目流程。
  - 新增 `ProjectTemplateSpec`、`ProjectTemplateOption` 与 `BuiltInProjectTemplates`，统一描述内置模板与插件模板，避免界面层各自维护模板定义。
- **SDL3 + CMake 模板插件与回归测试**：
  - 新增 `test-plugins/tinaide.template.sdl3/` 与对应 `.tinaplug` 安装包，提供可在线安装的 SDL3 GUI 项目模板插件。
  - 新增 `ExternalZipTemplateInstallTest.kt` 与 `Sdl3TemplateRegressionTest.kt`，覆盖外部 zip 模板安装与 SDL3 模板产物约束，防止模板回退为不可运行的目标类型。
- **插件市场评分 / 评论 / 举报交互落地**：
  - 更新 [`PluginMarketplaceModels.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/marketplace/PluginMarketplaceModels.kt)、[`PluginMarketplaceApi.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/marketplace/PluginMarketplaceApi.kt)、[`PluginMarketplaceRepository.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/marketplace/PluginMarketplaceRepository.kt)：补充插件详情评分、评论、举报评论与禁评状态字段，统一市场接口数据模型。
  - 更新 [`MarketScreenViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreenViewModel.kt)、[`MarketScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreen.kt)：插件详情页新增评分、评论输入、评论举报弹窗与禁评提示，插件页从“只下载”扩展为完整互动详情页。

### Changed
- **Git 操作失败处理更稳健**：
  - 更新 [`GitViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/GitViewModel.kt)：为状态刷新、分支加载、diff 获取、fetch/pull/push、冲突处理与继续/跳过操作统一补上异常兜底与取消语义保留；当底层 Git 服务抛出未预期异常时，界面会回落到可展示的错误消息，而不是卡在加载状态。
  - 更新 [`ProjectScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/project/ProjectScreen.kt)：Git 克隆流程现在会把非取消类异常转换为标准失败结果，避免创建项目时因底层异常直接打断页面流程。
- **Windows 本地构建清理策略增强**：
  - 更新 `external/tina-android-tree-sitter/build.gradle.kts`：Windows 主机下将 tree-sitter 子项目构建输出迁移到 `LOCALAPPDATA/TinaIDE/gradle-out/...`，并在 `bundleLibCompileToJar*` 前预删旧输出目录、遇到文件锁时重试清理，降低 `classes.jar` 被索引器/杀毒/并行 Gradle 占用导致的构建失败概率。
- **日志体系继续收口到 Timber，并清理噪音调试输出**：
  - 更新 [`HybridUserContentRepository.kt`](core/database/src/main/java/com/wuxianggujun/tinaide/database/impl/HybridUserContentRepository.kt)、[`FavoriteSyncWorker.kt`](core/database/src/main/java/com/wuxianggujun/tinaide/database/worker/FavoriteSyncWorker.kt)、[`AiApiClient.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/api/AiApiClient.kt)、[`AiChatViewModel.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/viewmodel/AiChatViewModel.kt) 等文件：移除 `android.util.Log` 与高噪音调试日志，统一改为带 tag 的 `Timber` 输出，保留真正有诊断价值的异常和告警。
  - 更新 [`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt)、[`EditorToolBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorToolBar.kt)：移除编辑器工具栏与撤销/重做按钮的重复调试日志，减少开发期 logcat 噪音。
- **Tina Server 业务调用统一改走自动鉴权客户端**：
  - 更新 [`TinaServerApi.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/api/TinaServerApi.kt)、[`TinaServerConfig.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/api/TinaServerConfig.kt)、[`AuthRepositoryImpl.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/AuthRepositoryImpl.kt)：用户资料、头像上传、登出、License 领取、反馈提交、AI 模型/额度/兑换码等接口不再在业务层手工传 `accessToken`，统一由鉴权客户端负责附带 Token、401 后刷新并重放。
  - 更新 [`AiChatViewModel.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/viewmodel/AiChatViewModel.kt)、[`AiSettingsBridgeImpl.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/settings/AiSettingsBridgeImpl.kt)、[`MyPublishViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MyPublishViewModel.kt)、[`SnippetMarketplaceApi.kt`](feature/snippet/src/main/java/com/wuxianggujun/tinaide/snippet/api/SnippetMarketplaceApi.kt)、[`FeedbackRepository.kt`](feature/help/src/main/java/com/wuxianggujun/tinaide/data/repository/FeedbackRepository.kt)、[`LicenseRepository.kt`](feature/license/src/main/java/com/wuxianggujun/tinaide/license/LicenseRepository.kt)：AI 对话长连接、模型加载、插件发布、代码片段市场、意见反馈、License 激活/领取等链路全部复用同一套鉴权会话能力，减少重复刷新 token 的分叉实现。
- **个人中心 AI 额度展示改为优先保留最近一次成功快照**：
  - 更新 [`ProfileViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/profile/ProfileViewModel.kt)、[`ProfileScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/profile/ProfileScreen.kt)：额度刷新中、网络失败或登录过期时会继续展示上次成功数据，并补充“刷新失败但保留缓存”与“登录过期但保留缓存”的状态文案，减少资料页数据闪空。
- **新建项目入口改为统一聚合内置模板与插件模板**：
  - 更新 `PluginManager.kt`、`ProjectCreationService.kt`、`ProjectDialogs.kt`、`NewProjectWizardViewModel.kt` 等文件：项目列表和新建向导不再硬编码 SDL3 模板，而是统一读取“内置模板 + 已安装插件模板”集合。
  - 更新 `docs/plugins/README.md`：补充 `contributions.projectTemplates` 说明，明确插件可贡献项目模板资源。
- **SDL3 GUI 模板改为输出宿主可直接加载的共享库产物**：
  - 更新 `tools/project-templates/sdl3_cmake/` 与测试插件模板：GUI 目标由可执行文件改为 `SHARED`，并固定 `OUTPUT_NAME "main"`，构建产物统一为 `libmain.so`。
- **插件市场状态提示与交互反馈统一收口**：
  - 更新 [`MarketScreenViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreenViewModel.kt)：将 `installMessage` 重构为通用 `message` 状态，统一承载下载、评论、举报等反馈消息，减少插件市场多条提示通道分叉。
  - 更新 [`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：补充评论禁用、评论提交成功、举报原因与举报成功等国际化文案。

### Fixed
- **修复 release 混淆阶段因 Jsoup 可选 `re2j` 依赖触发的 R8 构建失败**：
  - 更新 [`app/proguard-rules.pro`](app/proguard-rules.pro)：新增 `-dontwarn com.google.re2j.**`，解决 `:app:minifyArm64ReleaseWithR8` 因 `Missing class com.google.re2j.Matcher/Pattern` 中断的问题。
  - 更新 [`docs/proguard-rules-reference.md`](docs/proguard-rules-reference.md)：补充 Jsoup 可选依赖与对应 R8 规则说明，避免后续重复踩坑。
- **修复 JGit 在 release 包中的 NLS 初始化崩溃风险**：
  - 更新 [`core/git/consumer-rules.pro`](core/git/consumer-rules.pro)：从仅保留类名改为显式保留 `TranslationBundle` 子类的默认构造器和字段，避免 `JGitText` / `TranslationBundle` 在 R8 后初始化失败。
- **修复 Git 同步/冲突处理链路在底层抛异常时容易遗留“正在加载”状态的问题**：
  - 更新 [`GitViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/GitViewModel.kt)：为 diff、远程同步、SSH 解锁、冲突接受/标记/继续/跳过/中止等流程补齐 `finally` 清理，确保加载与冲突处理状态可以正确复位。
- **修复访问令牌过期后多个 Tina Server 功能容易同步失效的问题**：
  - 更新 [`AuthRepositoryImpl.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/AuthRepositoryImpl.kt)：`isLoggedIn()` 在 access token 过期但 refresh token 仍有效时改为尝试静默刷新，仅在缺少 refresh token 或后端明确拒绝时清理本地登录态，避免用户明明还能续期却被提前踢下线。
  - 更新 [`TinaServerAuthInterceptor.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/api/TinaServerAuthInterceptor.kt) 与 [`TinaServerAuthAuthenticator.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/api/TinaServerAuthAuthenticator.kt)：受保护请求会自动补齐 Bearer Token，并在 401 后刷新一次会话再重放请求，修复 AI 模型加载、AI 额度查询、插件发布、Snippet 市场、反馈提交、License 领取等接口在 token 过期后需要手工重登或各处重复刷新 token 的问题。
- **修复个人中心 AI 额度在异常场景下信息不透明的问题**：
  - 更新 [`ProfileViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/profile/ProfileViewModel.kt)、[`ProfileScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/profile/ProfileScreen.kt)、[`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：区分普通刷新失败与登录过期两种状态，并在界面中明确提示当前显示的是最近一次成功数据。
- **修复 GUI 模式下 CMake 构建成功却找不到正确共享库产物的问题**：
  - 更新 `BuildStrategy.kt`、`CMakeBuildStrategy.kt` 与 `CompileProjectUseCase.kt`：通过 CMake 目标元数据解析 `OUTPUT_NAME` / `LIBRARY_OUTPUT_NAME` / `RUNTIME_OUTPUT_NAME`，不再按 target 名盲猜产物文件名。
  - 修复 SDL3 GUI 项目把依赖库 `libSDL3.so` 误判为主程序产物的问题，宿主现在会正确定位并加载 `libmain.so`。
- **修复项目模板插件错误提示文案的中文乱码问题**：
  - 更新 [`strings.xml`](core/i18n/src/main/res/values/strings.xml)：修正 `plugin_error_project_template_*` 三条中文提示，避免模板安装失败时出现异常乱码。
- **修复插件市场“下载后无社区互动、状态不可见”的体验断层**：
  - 更新 [`MarketScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreen.kt) 与 [`MarketScreenViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreenViewModel.kt)：安装插件后重新打开详情页时可直接看到评分、评论和禁评状态，不再只停留在基础元数据视图。

### Technical
- 版本号：0.14.83 (versionCode: 1484)
- 本次聚焦：release 构建稳定性、Git 操作容错、Windows tree-sitter 本地构建可靠性、Tina Server 鉴权链路统一、插件化项目模板能力、SDL3 GUI 运行链路修复，以及插件市场评分 / 评论 / 举报交互闭环

## [0.14.81] - 2026-03-24

### Changed
- **头像显示改回基于稳定 URL 的正常缓存语义**：
  - 更新 [`UserAvatar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/UserAvatar.kt)：移除每次重组都附加时间戳的强制绕缓存逻辑，改为直接使用服务端返回的头像 URL；当后端头像 key 变化时，界面会自然刷新到新头像，避免无意义的重复拉图请求。

### Fixed
- **修复空头像地址被当作有效 URL 加载的问题**：
  - 更新 [`UserPreferences.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/UserPreferences.kt)：读取用户资料时把空字符串头像地址和邮箱还原为 `null`，并在保存时统一做 trim/空值归一，避免 UI 尝试加载空头像地址或展示空白邮箱行。
- **修复插件市场“已安装”仍可重复安装的问题**：
  - 更新 [`MarketScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreen.kt)：插件列表卡片与详情页改为按版本感知安装状态；同版本已安装时按钮真正禁用，仅在远端存在更高版本时显示“更新”。
  - 更新 [`MarketScreenViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreenViewModel.kt)、[`PluginMarketplaceRepository.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/marketplace/PluginMarketplaceRepository.kt)：补充 `updatablePlugins` 状态与同版本幂等保护，避免 UI 漏拦截时再次下载并重复安装同一版本插件。

### Technical
- 版本号：0.14.81 (versionCode: 1482)
- 本次聚焦：头像刷新链路收口，消除前端无效绕缓存与空字符串资料状态，并修正插件市场安装状态与重复安装链路

## [0.14.80] - 2026-03-13

### Added
- **统一的项目创建服务**：
  - 新增 `ProjectCreationService` 与失败结果模型，项目列表和新建向导共用同一套目录创建、模板安装与失败清理逻辑。
- **AI 设置桥接层**：
  - 新增 `AiSettingsBridge` / `AiSettingsBridgeImpl`，将 AI 模型加载与工具开关管理从设置页中抽离，解除 `feature:settings` 对 `feature:ai` 内部实现的直接依赖。
- **x86_64 内置工具链资源**：
  - 新增 `tinaide-toolchain-x86_64-v0.2.3` 及校验文件，并更新 `android-sysroot-x86_64-all.tar.xz`，补齐对应架构的内置工具链分发资源。
- **架构整改计划文档**：
  - 新增 `docs/架构整改计划.md`，沉淀当前架构问题、整改目标与阶段计划。

### Changed
- **文档与架构说明对齐当前实现**：
  - 更新 `README.md`、`docs/架构概览.md`、`docs/开发指南.md` 与 `docs/README.md`，统一说明 Koin 依赖注入、`app + core/* + feature/*` 模块结构，以及当前使用的 `dev` 分支。
- **文件与项目上下文接口拆分**：
  - `FileManager` 改为分别实现 `IFileOperations`、`IRecentFilesProvider`、`IFileWatchService`、`IProjectContext`、`IProjectSession`，减少调用方对胖接口的依赖。
- **构建链与本地开发体验优化**：
  - 升级 Kotlin / KSP 到 `2.2.20`，增加阿里云镜像与 Foojay resolver。
  - 新增 `tina.devAbi`、`tina.allAbi`、`tina.autoIncrementReleaseVersion`、`tina.checkNoAndroidUtilLogOnBuild` 等属性，本地默认单 ABI 构建，版本号仅在 release 构建前自动递增。
  - `external/termux-terminal`、`external/xcrash` 与 `external/tina-android-tree-sitter` 的本地构建也同步支持单 ABI 策略。
- **AI 会话与设置体验收敛**：
  - AI API 结果统一复用 `core:network.ApiResult`，会话列表改为按聚合查询消息数，设置页模型加载与工具开关统一走桥接层。
- **构建日志与 Markdown 渲染优化**：
  - `OutputManager` 现在缓存 BUILD 通道输出，底部面板可以恢复历史构建日志。
  - Markdown 解析改为后台线程 `collectLatest`，减少频繁更新时的主线程阻塞。
- **主题与终端相关实现清理**：
  - 主题初始化与夜间模式切换统一收敛到 `Prefs`。
  - 终端标签栏适配器重命名为 `TerminalSessionInfoTabBar`，命名与职责更清晰。
- **NDK / 工具链配置整理**：
  - `ToolchainConfig` 替换为 `InstalledToolchainConfig`。
  - C++ 标准与 Android API 级别显示逻辑迁移到公共显示扩展，便于国际化复用。

### Fixed
- **修复项目创建路径被忽略的问题**：
  - `ProjectManagerViewModel.createNewProject()` 现在会正确使用传入的 `projectPath`。
- **修复模板安装误伤二进制文件的问题**：
  - 模板解压时会区分文本与二进制内容，避免 zip 中的二进制资源被按文本替换后损坏。
- **修复用户内容数据库的破坏性迁移问题**：
  - 为 AI 对话相关表补充显式迁移与缺失列/表校验，移除 destructive migration。
- **修复搜索/替换失败静默吞错的问题**：
  - 全局搜索与批量替换改为保留取消语义并记录错误日志，便于排查问题。
- **修复 Rootfs / 主题等设置读取不一致的问题**：
  - 设置页的 Rootfs 路径和主题夜间模式切换统一收敛到 `Prefs`。

## [0.14.69] - 2026-03-10

### Added
- **AI 代码搜索工具集**：
  - 新增 [`SearchTools.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/search/SearchTools.kt)：实现代码搜索、符号查找、引用分析等工具，支持 AI 助手进行代码库探索与分析。

### Changed
- **编辑器文本句柄与拖拽体验向 Sora 对齐**：
  - 更新 [`EditorCanvasLayer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasLayer.kt)、[`CursorHandleDragCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CursorHandleDragCoordinator.kt)、[`CursorHandleLayout.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CursorHandleLayout.kt)、[`CursorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CursorRenderer.kt)、[`SelectionMagnifierController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionMagnifierController.kt)：新增单光标底部拖拽句柄、拖拽放大镜、边缘自动滚动，并细化句柄圆点大小、下坠长度与跟手偏移，整体观感更贴近 Sora。
  - 更新 [`EditorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderer.kt)、[`EditorRenderAssembly.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderAssembly.kt)、[`EditorConfig.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorConfig.kt)：正文起始偏移改为按 Sora 风格的 divider margin 公式计算，选区句柄默认尺寸、命中区与交互范围同步放大。
- **AI 工具集成与交互优化**：
  - 更新 [`AiToolsIntegrationManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/AiToolsIntegrationManager.kt)、[`EditorToolCallbacksImpl.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/EditorToolCallbacksImpl.kt)、[`ExecutionCallbacksImpl.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/ExecutionCallbacksImpl.kt)、[`FileSystemCallbacksImpl.kt`](app/src/main/java/com/wuxianggujun/tinaide/ai/integration/FileSystemCallbacksImpl.kt)：增强 AI 工具回调实现，优化编辑器操作、执行反馈与文件系统交互，提升 AI 助手的代码操作能力。
  - 更新 [`ExecutionTools.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/execution/ExecutionTools.kt)、[`ProjectTools.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/project/ProjectTools.kt)、[`RefactorTools.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/refactor/RefactorTools.kt)：重构工具实现，改进参数解析、错误处理与日志记录，提高工具执行的稳定性与可调试性。
  - 更新 [`ToolInitializer.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/ToolInitializer.kt)、[`ToolParameterParser.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/ToolParameterParser.kt)：优化工具初始化流程与参数解析逻辑，支持更灵活的工具配置。
- **AI 聊天界面改进**：
  - 更新 [`DrawerAiPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/DrawerAiPanel.kt)：重构 AI 面板布局，工具按类别分组展示，新增停止生成按钮，优化访问令牌管理与模型加载状态显示。
  - 更新 [`ChatMessageBubble.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ChatMessageBubble.kt)：改进消息气泡样式，用户消息与 AI 消息视觉区分更明显。
  - 更新 [`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt)、[`BottomPanelTypes.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanelTypes.kt)：底部面板支持 AI 聊天相关操作。
  - 更新 [`AiChatViewModel.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/viewmodel/AiChatViewModel.kt)：实现停止生成功能，允许用户中断 AI 响应。
- **AI 设置界面增强**：
  - 更新 [`AiSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/AiSettingsSection.kt)：新增访问令牌管理、模型选择与加载状态显示，工具选项按类别分组，提升配置体验。
- **AI API 客户端优化**：
  - 更新 [`AiApiClient.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/api/AiApiClient.kt)：API 密钥在使用前清理换行符和空白字符，避免因格式问题导致的认证失败。
- **国际化资源更新**：
  - 更新 [`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：新增 AI 工具相关的多语言字符串资源。

### Fixed
- **长按菜单与连续扩选手势回归修复**：
  - 更新 [`EditorCanvasLayer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasLayer.kt)、[`EditorCanvasGesturePipeline.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasGesturePipeline.kt)、[`EditorGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureCoordinator.kt)、[`EditorGestureHandler.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureHandler.kt)、[`EditorRuntimeEffects.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRuntimeEffects.kt)、[`EditorSessionGestureRuntime.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSessionGestureRuntime.kt)、[`TinaEditorScaffold.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorScaffold.kt)、[`TinaEditorSession.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorSession.kt)、[`TinaEditorUiState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorUiState.kt)：恢复长按选词/长按空白弹菜单，已有选区仍可长按拖动，并补上长按后直接拖动扩选的连续手势链路。

### Technical
- 版本号：0.14.69 (versionCode: 1470)
- 本次聚焦：Sora 风格文本句柄、长按菜单恢复、光标/选区拖拽体验细化

## [0.14.66] - 2026-03-10

### Changed
- **插件市场安装失败提示与错误可观测性增强**：
  - 更新 [`PluginMarketplaceApi.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/marketplace/PluginMarketplaceApi.kt)、[`PluginMarketplaceRepository.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/marketplace/PluginMarketplaceRepository.kt)、[`PluginMarketplaceViewModel.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/marketplace/PluginMarketplaceViewModel.kt)、[`MarketScreenViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreenViewModel.kt)：下载失败时补充 HTTP 状态、服务端 API envelope 错误消息与日志记录，安装失败提示改为优先透出具体原因；同时保留协程取消异常，避免取消下载时被误判为普通失败。

### Fixed
- **开发者选项关闭后的返回行为修正**：
  - 更新 [`SettingsScreen.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/SettingsScreen.kt) 与 [`DeveloperOptionsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/DeveloperOptionsSection.kt)：关闭开发者选项后改为直接返回上一页，不再强制跳转到设置首页。

### Technical
- 版本号：0.14.66 (versionCode: 1467)
- 本次聚焦：插件市场错误透出、安装链路日志补强、开发者设置返回逻辑修复

---

## [0.14.52] - 2026-03-10

### Added
- **收藏与下载历史混合仓库落地**：
  - 新增 [`HybridUserContentRepository.kt`](core/database/src/main/java/com/wuxianggujun/tinaide/database/impl/HybridUserContentRepository.kt)：将插件收藏改为“本地优先 + 远程同步”模型，支持分页读取、未同步状态记录、服务端全量回拉与标签序列化处理。
  - 新增 [`UserContentApiClient.kt`](core/network/src/main/java/com/wuxianggujun/tinaide/core/network/api/UserContentApiClient.kt)：补充用户收藏相关接口客户端，统一处理收藏增删、分页查询与通用响应解析。
  - 新增 [`FavoriteSyncWorker.kt`](core/database/src/main/java/com/wuxianggujun/tinaide/database/worker/FavoriteSyncWorker.kt)：通过 WorkManager 定时补偿未同步收藏，应用启动后自动注册后台同步任务。
- **构建环境抽象进一步收敛**：
  - 新增 [`MakeBuildEnvironment.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/MakeBuildEnvironment.kt) 与 [`CMakeLinkPolicy.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/CMakeLinkPolicy.kt)：统一 Make/CMake 构建阶段的环境变量拼装与标准库链接策略。
  - 新增 [`MakeBuildEnvironmentTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/MakeBuildEnvironmentTest.kt)、[`MakeTemplateRegressionTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/MakeTemplateRegressionTest.kt)、[`CMakeLinkPolicyTest.kt`](core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/cmake/CMakeLinkPolicyTest.kt)：补齐构建模板与链接策略的回归测试覆盖。

### Changed
- **插件市场与收藏页体验增强**：
  - 更新 [`MarketScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreen.kt) 与 [`MarketScreenViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/market/MarketScreenViewModel.kt)：市场卡片新增下载进度展示、收藏状态联动与详情点击入口；未登录用户收藏时增加明确提示。
  - 更新 [`FavoritesScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/profile/FavoritesScreen.kt) 与 [`FavoritesViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/profile/FavoritesViewModel.kt)：收藏页新增顶部栏、手动刷新、同步状态提示、取消收藏确认和跳转市场入口，并支持主动从服务端同步收藏数据。
  - 更新 [`strings.xml`](core/i18n/src/main/res/values/strings.xml) 与 [`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：补充收藏同步、下载进度、未登录收藏等中英文文案。
- **依赖注入与应用启动流程调整**：
  - 更新 [`TinaApplication.kt`](app/src/main/java/com/wuxianggujun/tinaide/TinaApplication.kt)、[`AppViewModelModule.kt`](app/src/main/java/com/wuxianggujun/tinaide/di/AppViewModelModule.kt)、[`AuthModule.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/di/AuthModule.kt)、[`DatabaseModule.kt`](core/database/src/main/java/com/wuxianggujun/tinaide/database/di/DatabaseModule.kt)、[`build.gradle.kts`](core/database/build.gradle.kts)、[`build.gradle.kts`](core/network/build.gradle.kts)：接入新的混合仓库、网络客户端与后台 Worker 依赖，市场页 ViewModel 同步改为注入用户内容仓库。
- **构建链路清理**：
  - 更新 [`NativeMakeBuildStrategy.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/NativeMakeBuildStrategy.kt)、[`PRootMakeBuildStrategy.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/PRootMakeBuildStrategy.kt)、[`CMakeBuildExecutor.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/CMakeBuildExecutor.kt)、[`NativeCMakeBuildExecutor.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutor.kt)：复用统一构建环境与链接策略，减少重复命令覆盖逻辑。
  - 更新 [`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt) 与 [`make_executable.zip`](app/src/main/assets/templates/make_executable.zip)：同步调整保存后构建与模板执行细节。

### Fixed
- **本地数据接口补齐**：
  - 更新 [`UserContentRepository.kt`](core/common/src/main/java/com/wuxianggujun/tinaide/core/user/UserContentRepository.kt)、[`LocalUserContentRepository.kt`](core/database/src/main/java/com/wuxianggujun/tinaide/database/impl/LocalUserContentRepository.kt)、[`DownloadHistoryDao.kt`](core/database/src/main/java/com/wuxianggujun/tinaide/database/user/DownloadHistoryDao.kt)：补充收藏同步接口与按 ID 删除下载记录能力，完善本地 / 混合仓库兼容性。

### Technical
- 版本号：0.14.52 (versionCode: 1453)
- 本次聚焦：收藏云端同步、市场/收藏页联动、Make/CMake 构建环境收敛、应用启动后台同步补偿

---

## [0.14.51] - 2026-03-08

### Added
- **个人中心接入 AI 额度与兑换入口**：
  - 更新 [`ProfileScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/profile/ProfileScreen.kt) 与 [`ProfileViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/profile/ProfileViewModel.kt)：新增 AI 额度摘要、额度刷新、AI 充值码弹窗与兑换成功反馈，个人中心可直接查看并操作 AI 余额。
  - 更新 [`TinaServerApi.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/api/TinaServerApi.kt) 与 [`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：补充 AI 额度查询 / 兑换接口及中英文文案。
- **编辑器 snippet 补全会话能力落地**：
  - 新增 [`SnippetParser.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SnippetParser.kt) 与 [`SnippetSession.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SnippetSession.kt)：支持补全项 snippet 解析、占位符会话与跳转。
  - 新增 [`Editor-Completion-System-Design.md`](docs/design/Editor-Completion-System-Design.md)：沉淀编辑器补全系统设计与 snippet 占位符处理方案。
- **工程约定与混淆文档补充**：
  - 新增 [`proguard-rules-reference.md`](docs/proguard-rules-reference.md)、[`project-conventions.md`](docs/project-conventions.md)、[`tina-server-ai-system-issues-analysis.md`](docs/tina-server-ai-system-issues-analysis.md)：补充混淆规则说明、项目约定与 AI 系统问题分析文档。

### Changed
- **主界面编辑与悬浮运行交互继续打磨**：
  - 更新 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`MainActivityTopBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityTopBar.kt)、[`EditorToolBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorToolBar.kt)、[`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt)：将保存全部与书签切换/跳转能力前移到主界面动作区，并同步精简底部工具栏职责。
  - 更新 [`FloatingOverlay.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FloatingOverlay.kt)、[`ExternalSdlActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/sdl/ExternalSdlActivity.kt)：悬浮球在关闭日志面板时可直接触发退出确认，logcat 捕获改为按当前进程与多标签过滤，提升 SDL / 标准输出日志采集完整性。
- **编辑器补全、快捷键与 LSP 协同继续增强**：
  - 更新 [`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`EditorStateEditOperations.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorStateEditOperations.kt)、[`EditorCompletionUtils.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionUtils.kt)、[`EditorCompletionPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionPopup.kt)、[`EditorInteractionController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionController.kt)、[`EditorKeyboardShortcuts.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorKeyboardShortcuts.kt)、[`CompletionProvider.kt`](core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/CompletionProvider.kt)：接入 snippet 补全应用链路、占位符跳转、补全触发细节与键盘交互优化。
  - 更新 [`LspClientSession.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspClientSession.kt)、[`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：增强 LSP 连接日志、编辑器会话联动与页面侧补全行为。
  - 删除 [`TinaEditorAdapter.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorAdapter.kt)：移除旧兼容适配层，收敛到当前编辑器状态模型。
- **PRoot 运行时边界与设置联动继续收缩**：
  - 更新 [`PRootEnvironment.kt`](core/proot/src/main/java/com/wuxianggujun/tinaide/core/proot/PRootEnvironment.kt)、[`ToolchainPathResolver.kt`](core/proot/src/main/java/com/wuxianggujun/tinaide/core/proot/ToolchainPathResolver.kt)、[`CompileEnvironmentChecker.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileEnvironmentChecker.kt)、[`PRootClangdConnectionProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/PRootClangdConnectionProvider.kt)、[`DebugSessionService.kt`](core/debug/src/main/java/com/wuxianggujun/tinaide/core/debug/DebugSessionService.kt)：移除对 manifest / symlink / 旧编译兼容层的依赖，统一改为基于 rootfs 实际二进制探测可用性。
  - 更新 [`SettingsViewModel.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/SettingsViewModel.kt)、[`CompilerSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/CompilerSettingsSection.kt)、[`LspSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/LspSettingsSection.kt)、[`StorageSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/StorageSettingsSection.kt)、[`TerminalSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/TerminalSettingsSection.kt)、[`AboutSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/AboutSettingsSection.kt)：当 Linux 环境插件关闭时隐藏 PRoot 相关入口与选项，并自动回退不再可用的终端后端设置。
  - 更新 [`gradle.properties`](gradle.properties)：关闭 Gradle 并行执行并限制单 worker，降低当前重构阶段的资源争抢与构建不稳定性。
- **工程稳定性与 shrink 规则整理**：
  - 更新 [`TinaApplication.kt`](app/src/main/java/com/wuxianggujun/tinaide/TinaApplication.kt)、[`app/proguard-rules.pro`](app/proguard-rules.pro)、[`consumer-rules.pro`](core/git/consumer-rules.pro)、[`consumer-rules.pro`](core/lsp/consumer-rules.pro)、[`consumer-rules.pro`](core/network/consumer-rules.pro)、[`consumer-rules.pro`](core/plugin/consumer-rules.pro)：补充模块 consumer rules 与应用混淆保留规则，降低发布构建下功能被裁剪的风险。
  - 更新 [`ProjectLanguage.kt`](core/model/src/main/java/com/wuxianggujun/tinaide/project/ProjectLanguage.kt)、[`ProjectMetadata.kt`](core/model/src/main/java/com/wuxianggujun/tinaide/project/ProjectMetadata.kt) 以及 [`external/tina-android-tree-sitter`](external/tina-android-tree-sitter)：同步工程语言/元数据与 Tree-sitter 依赖状态。
- **配套设计文档同步整理**：
  - 更新 [`backend-license-system.md`](docs/backend-license-system.md)、[`Build-Config-Consolidation-Design.md`](docs/design/Build-Config-Consolidation-Design.md)、[`LSP-Snippet-Placeholder-Handling.md`](docs/design/LSP-Snippet-Placeholder-Handling.md)、[`PRoot-Feature-Analysis.md`](docs/design/PRoot-Feature-Analysis.md)、[`PRoot-Runtime-Refactor.md`](docs/design/PRoot-Runtime-Refactor.md)、[`TinaEditor-Migration-Strategy.md`](docs/design/TinaEditor-Migration-Strategy.md)、[`TinaEditor-Module-Setup.md`](docs/design/TinaEditor-Module-Setup.md)、[`TinaEditor-README.md`](docs/design/TinaEditor-README.md)、[`TinaEditor-Rewrite-Design.md`](docs/design/TinaEditor-Rewrite-Design.md)、[`TinaEditor-Missing-Features.md`](docs/design/TinaEditor-Missing-Features.md)：同步补全系统、编辑器迁移和 PRoot 收缩方案文档。

### Fixed
- **输入法兼容性全面补强**（[`EditorInputConnection.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInputConnection.kt)）：
  - **回车键在第三方输入法上无反应**：搜狗、百度、讯飞、微信等第三方输入法按回车走 `performEditorAction()` 路径，原来直接返回 `false`。现在处理 `IME_ACTION_DONE/GO/SEND/NEXT/SEARCH/UNSPECIFIED/NULL` 全部 action，统一插入换行符，所有输入法均可正常回车。
  - **输入法工具栏方向键无效**：`BaseInputConnection` 默认不处理方向键，现在 `sendKeyEvent()` 显式处理 `KEYCODE_DPAD_LEFT/RIGHT/UP/DOWN`（调用 `EditorState.moveLeft/Right/Up/Down()`）以及 `KEYCODE_MOVE_HOME/END`，每次移动后通过 `InputMethodManager.updateSelection()` 同步光标位置给输入法。
- **长按空白区域不弹菜单**（[`EditorGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureCoordinator.kt)）：原来 `onLongPress()` 仅在 `selectWord()` 选中单词时才显示菜单，空文件/空白区域/行尾长按无菜单。现在长按始终显示菜单，空白区域至少粘贴和全选可用。
- **输入法切换中英文时长按菜单消失**（[`EditorSelectionContextMenu.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSelectionContextMenu.kt)）：菜单 `Popup` 设置 `PopupProperties(focusable = false)`，弹出时不抢夺编辑器焦点，用户点击 IME 语言切换按钮等 IME 区域也不触发 `onDismissRequest`，从根本上消除菜单被误关闭的问题。

### Technical
- 版本号：0.14.51 (versionCode: 1452)
- 本次聚焦：AI 额度个人中心接入、snippet 补全会话、主界面编辑动作前移、PRoot 运行时收缩与设置联动；输入法兼容性修复（回车、方向键、长按菜单）

---

## [0.14.51] - 2026-03-09（追加）

### Added
- **图片验证码体系接入（登录/注册/重置密码全流程）**：
  - 新增 [`CaptchaDialog.kt`](core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/CaptchaDialog.kt)、[`CaptchaInput.kt`](core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/CaptchaInput.kt)：通用验证码对话框与输入组件，支持 Base64 图片展示、一键刷新、加载态。
  - 更新 [`AuthModels.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/model/AuthModels.kt)：新增 `CaptchaData` 数据类，`LoginMethod`/`UserProfile`/`MembershipInfo`/`BoundAccount`/`AuthToken` 全部补充 `@Serializable` 注解。
  - 更新 [`AuthRepository.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/AuthRepository.kt)、[`AuthRepositoryImpl.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/AuthRepositoryImpl.kt)、[`TinaServerApi.kt`](core/auth/src/main/java/com/wuxianggujun/tinaide/auth/api/TinaServerApi.kt)：新增 `getCaptcha()` 接口；`loginWithEmail`、`sendRegisterCode`、`sendPasswordResetCode` 全部增加 `captchaId`/`captchaCode` 参数，接口签名同步更新。
  - 更新 [`LoginViewModel.kt`](feature/login/src/main/java/com/wuxianggujun/tinaide/auth/LoginViewModel.kt)、[`LoginActivity.kt`](feature/login/src/main/java/com/wuxianggujun/tinaide/ui/activity/LoginActivity.kt)：UI 层接入验证码弹窗流程（获取→展示→校验→提交），登录/注册/找回密码三个入口均支持。
  - 更新 [`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：补充验证码相关中英文文案。
- **PRoot Guest 工具链安装步骤正式引入**：
  - 新增 [`PRootGuestToolchainInstaller.kt`](core/proot/src/main/java/com/wuxianggujun/tinaide/core/proot/PRootGuestToolchainInstaller.kt)：负责在 PRoot rootfs 内安装 Guest 工具链（独立于 Android sysroot 的 Linux 侧工具链）。
  - 更新 [`DependencyInstallViewModel.kt`](feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/DependencyInstallViewModel.kt)：在安装流程中插入 `proot-guest-toolchain` 安装步骤，重新分配各阶段进度权重（rootfs 0.45 / guest-toolchain 0.20 / sysroot 0.175 / native-toolchain 0.175）。

### Changed
- **LSP 工作区文件监听（workspace/didChangeWatchedFiles）落地**：
  - 更新 [`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)：新增 `WorkspaceFileWatcher`（基于 `FileObserver`，API 29+ 递归模式），在 CXX LSP 会话建立后自动启动；实现 `onCapabilityRegistered`/`onCapabilityUnregistered` 以响应 clangd 动态注册的文件监听范围；新增 `onFileSaved()` 方法，CMakeLists.txt / .cmake 文件保存时自动重建 compile_commands 并重启 clangd。
  - 更新 [`LspClientSession.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspClientSession.kt)：补充 `registrationConsumer`/`unregistrationConsumer` 回调，将 LSP `client/registerCapability`、`client/unregisterCapability` 事件桥接到上层管理器。
  - 更新 [`LspNavigationService.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspNavigationService.kt)：小幅调整。
- **主界面编辑动作与状态联动调整**：
  - 更新 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)、[`MainActivityHostCommandExecutor.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/commands/MainActivityHostCommandExecutor.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)：编辑器容器状态与主界面命令执行层小幅联动优化。
- **设置模块多处细节修复**：
  - 更新 [`ToolchainImportDialog.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/ToolchainImportDialog.kt)、[`AiSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/AiSettingsSection.kt)、[`GitSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/GitSettingsSection.kt)、[`MirrorSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/MirrorSettingsSection.kt)、[`TerminalSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/TerminalSettingsSection.kt)：设置页各分区小幅交互与文案调整。
- **其他模块小幅更新**：
  - 更新 [`ToolI18n.kt`](feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/tools/ToolI18n.kt)、[`BookmarkService.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/bookmark/BookmarkService.kt)、[`BookmarkDatabase.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/bookmark/db/BookmarkDatabase.kt)、[`TerminalDatabase.kt`](feature/terminal/src/main/java/com/wuxianggujun/tinaide/terminal/persistence/db/TerminalDatabase.kt)：各模块小幅调整。

### Removed
- **PRoot 旧编译抽象层清理**：
  - 删除 `ICompilerEnvironment.kt`、`InstallProgressEvent.kt`、`PRootCompiler.kt`、`PRootLinuxEnvironment.kt`、`ToolchainInstallResult.kt`、`ToolchainManifest.kt`、`ToolchainManifestStore.kt`（`core/proot/`）以及 `toolchain/LlvmVersions.kt`、`SymlinkConfig.kt`、`SymlinkConfigStore.kt`、`SymlinkManager.kt`、`SymlinkResult.kt`：彻底移除旧版 PRoot 编译运行时抽象，统一由 `PRootEnvironment` + `PRootGuestToolchainInstaller` 替代。

### Technical
- 版本号：0.14.51 (versionCode: 1452)
- 本次追加聚焦：图片验证码全流程接入、PRoot Guest 工具链安装步骤、LSP 工作区文件监听与 CMake 热重载、旧 PRoot 抽象层清理

---

## [0.14.47] - 2026-03-06

### Changed
- **GUI 悬浮日志捕获协程异常修复**：
  - 更新 [`FloatingOverlay.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FloatingOverlay.kt)：将 `captureLogcat()` 包装到 [`coroutineScope`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FloatingOverlay.kt:63) 中，并统一使用 [`isActive`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FloatingOverlay.kt:65) 检查协程状态，修复悬浮日志面板在日志读取生命周期中的异常退出问题。

### Technical
- 版本号：0.14.47 (versionCode: 1448)
- 本次聚焦：悬浮日志 logcat 捕获协程稳定性修复

---

## [0.14.46] - 2026-03-06

### Added
- **APK 模板打包链路与图形运行时入口补齐**：
  - 新增 [`core/apk-builder/`](core/apk-builder/) 模块及其中的 [`ApkBuilder.kt`](core/apk-builder/src/main/java/com/wuxianggujun/tinaide/core/apkbuilder/ApkBuilder.kt)：支持将用户编译产物注入模板 APK、执行 manifest 修补、zipalign 与签名，形成应用内 APK 打包能力。
  - 新增 [`ApkPackageDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/ApkPackageDialog.kt) 与 [`app/src/main/assets/apk_templates/`](app/src/main/assets/apk_templates/)：在界面中提供 APK 打包配置、构建进度、安装与分享入口，并接入 native / SDL 模板资产。
  - 新增 [`FloatingOverlay.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FloatingOverlay.kt)：为 GUI / SDL 运行时提供悬浮退出球与可选日志面板。
- **编辑器可视化辅助能力扩展**：
  - 新增 [`BracketPairGuideRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/BracketPairGuideRenderer.kt)、[`WhitespaceRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/WhitespaceRenderer.kt)、[`WordOccurrenceHighlightRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/WordOccurrenceHighlightRenderer.kt)、[`MatchingBracketHighlightRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/MatchingBracketHighlightRenderer.kt)：补齐括号对引导线、空白字符可视化、光标词高亮和匹配括号提示。
- **脚本插件 API 模块化重组**：
  - 新增 [`PluginApiModule.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/PluginApiModule.kt) 与 [`CommandsApiModule.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/CommandsApiModule.kt)、[`EditorApiModule.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/EditorApiModule.kt)、[`LogApiModule.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/LogApiModule.kt)、[`StorageApiModule.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/StorageApiModule.kt)、[`UiApiModule.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/UiApiModule.kt)、[`PluginApiHelpers.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/PluginApiHelpers.kt)：将原本集中在单文件内的脚本 API 拆分为可注册的独立模块。

### Changed
- **运行配置、GUI 启动与模板工程继续增强**：
  - 更新 [`RunConfiguration.kt`](core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/RunConfiguration.kt)、[`RunConfigDialog.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/RunConfigDialog.kt)、[`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)、[`MainActivityTopBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityTopBar.kt)：新增 GUI 屏幕方向与悬浮日志开关，并把 APK 打包入口整合进主界面运行流程。
  - 更新 [`GuiHostActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/gui/GuiHostActivity.kt)、[`GuiRuntimeBridge.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/gui/GuiRuntimeBridge.kt)、[`ExternalSdlActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/sdl/ExternalSdlActivity.kt)、[`gui_runtime_jni.cpp`](app/src/main/cpp/gui/gui_runtime_jni.cpp)：补充触摸/按键事件桥接、强制方向控制、SDL 返回键接管与悬浮层叠加逻辑，提升图形程序宿主可用性。
  - 更新 [`build.gradle.kts`](build.gradle.kts)、[`settings.gradle.kts`](settings.gradle.kts)、[`app/build.gradle.kts`](app/build.gradle.kts)：纳入 [`core/apk-builder/`](core/apk-builder/) 与模板工程模块，新增 [`buildApkTemplates`](build.gradle.kts) 任务用于生成并拷贝模板 APK 资源。
- **编辑器设置、补全与输入体验补强**：
  - 更新 [`Prefs.kt`](core/config/src/main/java/com/wuxianggujun/tinaide/core/config/Prefs.kt)、[`EditorConfig.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorConfig.kt)、[`EditorSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/EditorSettingsSection.kt)、[`SettingsViewModel.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/SettingsViewModel.kt)：新增空白字符可视化模式与 Tab 转空格设置，并把相关选项接入设置页。
  - 更新 [`EditorColorScheme.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorColorScheme.kt)、[`EditorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderer.kt)、[`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`EditorStateEditOperations.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorStateEditOperations.kt)、[`EditorSmartReplacement.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSmartReplacement.kt)、[`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：接入括号引导线、空白字符渲染、词高亮、自动补全排序与成对符号删除等行为优化。
  - 更新 [`EditorCompletionPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionPopup.kt)、[`EditorCompletionUtils.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionUtils.kt)、[`EditorInteractionController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionController.kt)、[`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)：继续强化补全触发、候选排序、UI 信息密度与查询高亮体验。
- **脚本插件运行时与 Lua 引擎升级**：
  - 更新 [`PluginRuntime.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/PluginRuntime.kt)、[`ScriptPluginManager.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/ScriptPluginManager.kt)、[`ClipboardApiModule.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/ClipboardApiModule.kt)、[`DatabaseApiModule.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/DatabaseApiModule.kt)、[`EventsApiModule.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/EventsApiModule.kt)、[`FileApiModule.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/FileApiModule.kt)、[`NetworkApiModule.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/NetworkApiModule.kt)：将脚本运行时从 `LuaJ` 切换到 Lua 5.4 JNI 实现，并重构 API 注册方式、日志模块与安全沙箱细节。
  - 删除 [`PluginApi.kt`](core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/script/api/PluginApi.kt)：原始聚合式 API 定义被拆分模块化实现替代。
- **模板资源与示例插件整理**：
  - 删除 [`app/src/main/assets/bundled_plugins/`](app/src/main/assets/bundled_plugins/) 下的示例脚本、主题与 snippets 插件资源，收敛默认包内资产。

### Technical
- 版本号：0.14.46 (versionCode: 1447)
- 本次聚焦：APK 模板打包、GUI/SDL 宿主增强、编辑器可视化辅助、脚本插件 API 模块化与 Lua 5.4 升级

---

## [0.14.41] - 2026-03-06

### Added
- **编辑器彩虹括号与 Tree-sitter 括号/折叠查询资源落地**：
  - 新增 [`RainbowBracketComputer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/RainbowBracketComputer.kt)：按行缓存括号嵌套深度，在大文件场景下依据配置阈值自动停用彩虹括号计算。
  - 新增 [`brackets.scm`](app/src/main/assets/tree-sitter-queries/java/brackets.scm)、[`brackets.scm`](app/src/main/assets/tree-sitter-queries/kotlin/brackets.scm)、[`blocks.scm`](app/src/main/assets/tree-sitter-queries/java/blocks.scm)、[`blocks.scm`](app/src/main/assets/tree-sitter-queries/kotlin/blocks.scm)：为 Java / Kotlin 补齐括号配对与代码块查询资源，给折叠和括号高亮提供语言侧规则基础。

### Changed
- **编辑器折叠、括号高亮与渲染链路继续增强**：
  - 更新 [`EditorConfig.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorConfig.kt) 与 [`EditorColorScheme.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorColorScheme.kt)：新增彩虹括号开关、最大扫描行数、折叠占位色与彩虹括号调色板，并支持从主题配置解析对应颜色。
  - 更新 [`TextRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt)、[`EditorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderEngine.kt)、[`CursorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CursorRenderer.kt)：在文本绘制阶段接入彩虹括号着色、折叠占位符徽标、可见区域异步高亮缓存，以及折叠尾行的光标定位与命中检测能力。
  - 更新 [`EditorGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureCoordinator.kt)、[`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`EditorStateEditOperations.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorStateEditOperations.kt)：点击折叠徽标即可切换折叠状态，并让光标移动、删除与文本变更在隐藏区间和折叠尾行场景下自动跳过或展开折叠区域。
  - 更新 [`TreeSitterFoldingProvider.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TreeSitterFoldingProvider.kt)：补充折叠区间调试日志，便于校验查询命中结果与折叠边界。
  - 更新 [`DiagnosticRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/DiagnosticRenderer.kt)、[`DiagnosticWavePathBuilder.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/DiagnosticWavePathBuilder.kt)、[`GutterRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/GutterRenderer.kt)、[`LineNumberRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/LineNumberRenderer.kt)、[`EditorCanvasLayer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasLayer.kt)、[`EditorTextBufferRendererSyncEffect.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorTextBufferRendererSyncEffect.kt)：同步整理诊断波浪线、 gutter 折叠图标模板、当前行号绘制和文本缓冲同步时机，提升编辑器绘制一致性。
- **补全触发与候选展示体验补强**：
  - 更新 [`EditorCompletionUtils.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionUtils.kt) 与 [`EditorInteractionController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionController.kt)：补全自动触发从单字符扩展到 `->`、`::` 等组合触发符，并在 IME 插入后读取前导字符参与判断。
  - 更新 [`EditorCompletionPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionPopup.kt) 与 [`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)：将当前补全查询串传入弹窗，对 fuzzy 匹配命中的字符做高亮强调，提升候选列表可读性与选择效率。

### Technical
- 版本号：0.14.41 (versionCode: 1442)
- 本次聚焦：彩虹括号、折叠占位绘制、Tree-sitter 查询资源补齐、可见区域高亮缓存优化

---

## [0.14.31] - 2026-03-06

### Added
- **编辑器稳定性测试与许可证说明补充**：
  - 新增 [`EditorStateSurrogatePairTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorStateSurrogatePairTest.kt)、[`EditorStateWordWrapTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorStateWordWrapTest.kt)、[`TextRenderPlannerTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/TextRenderPlannerTest.kt)：补齐 surrogate pair、自动换行和文本渲染规划的回归测试覆盖。
  - 更新 [`OpenSourceLicensesActivity.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/settings/OpenSourceLicensesActivity.kt) 与 [`strings.xml`](core/i18n/src/main/res/values/strings.xml)、[`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：开源许可证页面新增说明卡片，并补充随应用分发组件的展示信息。

### Changed
- **编辑器渲染链路与文本缓冲性能继续优化**：
  - 更新 [`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)：缓存 `cursorPosition` 派生结果，收敛诊断行排序与 visual line map 的失效判断，并统一 word wrap 分段来源。
  - 更新 [`TextRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt)、[`EditorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderer.kt)、[`EditorRenderEngine.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderEngine.kt)、[`EditorCanvasLayer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasLayer.kt)：重构文本绘制规划、覆盖层拼装和渲染调用顺序，减少重复计算并提升复杂文本场景的绘制稳定性。
  - 更新 [`DiagnosticRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/DiagnosticRenderer.kt)、[`DiagnosticWavePathBuilder.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/DiagnosticWavePathBuilder.kt)、[`GutterRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/GutterRenderer.kt)、[`LineNumberRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/LineNumberRenderer.kt)、[`SelectionRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionRenderer.kt)、[`CursorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CursorRenderer.kt)、[`EditorScrollbarRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScrollbarRenderer.kt)、[`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)、[`EditorRuntimeEffects.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRuntimeEffects.kt)、[`TinaEditorScaffold.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorScaffold.kt)：同步清理诊断、光标、选区、行号和滚动条相关绘制逻辑，降低层间状态重复拼装。
  - 更新 [`RopeTextBuffer.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/RopeTextBuffer.kt) 与 [`TextBuffer.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/TextBuffer.kt)：补充原生 `replace()` 路径与统一 `TextChange` 生成，减少替换操作的中间开销。
- **帮助文档与工程排除配置整理**：
  - 更新 `feature/help/src/main/assets/help/*.md`：整体压缩和重写内置帮助文档内容，统一表述风格并收敛冗长说明。
  - 更新 [`.idea/modules/TinaIDE.iml`](.idea/modules/TinaIDE.iml)：将 [`.cursor/`](.cursor/) 加入 IDE 排除目录，减少本地工具元数据对工程索引的干扰。

### Technical
- 版本号：0.14.31 (versionCode: 1432)
- 代码统计：36 个文件修改，+1983 行，-3514 行
- 本次聚焦：渲染链路精简、文本缓冲 replace 能力、许可证展示补全、帮助文档收敛

---

## [0.14.28] - 2026-03-06

### Added
- **编辑器统一偏移量选区模型文档**：
  - 新增 [`OffsetRange.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/OffsetRange.kt)：以 `charOffset`（UTF-16 code unit）表达选区锚点与活动端，统一内部选区坐标模型。
  - 新增 [`unified-layout-snapshot.md`](docs/design/unified-layout-snapshot.md)：沉淀“charOffset 统一布局模型”设计说明，明确 `Position(line, column)` 仅保留在 LSP 边界层做转换。

### Changed
- **编辑器状态与手势链路切换到 offset-first 选区语义**：
  - 更新 [`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`EditorStateContracts.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorStateContracts.kt)、[`EditorEvent.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorEvent.kt)：将核心选区状态从 `Selection` 切换为 `selectionRange` / `OffsetRange`，并同步事件与接口定义。
  - 更新 [`EditorStateEditOperations.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorStateEditOperations.kt)、[`EditorInputConnection.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInputConnection.kt)、[`EditorInteractionController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionController.kt)、[`EditorKeyboardShortcuts.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorKeyboardShortcuts.kt)：插入、删除、替换、撤销重做、IME 同步与快捷键路径统一改为按 offset 驱动。
  - 更新 [`EditorGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureCoordinator.kt)、[`EditorGestureHandler.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionHandleDragCoordinator.kt)、[`SelectionHandleLayout.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionHandleLayout.kt)、[`SelectionRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionRenderer.kt)、[`EditorCanvasLayer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasLayer.kt)、[`EditorRuntimeEffects.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRuntimeEffects.kt)、[`EditorScaleTransformCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScaleTransformCoordinator.kt)：手势命中、选区拖拽、句柄翻转、渲染与缩放联动统一按偏移量计算，减少 `Position ↔ offset` 来回转换。
  - 更新 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt) 与 [`TinaEditorAdapter.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorAdapter.kt)：页面侧选择、搜索跳转、光标恢复与外部适配接口统一改为 offset-first 调用，仅在需要对接外部坐标时再转换。
- **旧选区类型清理与测试同步**：
  - 删除 [`Selection.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/Selection.kt)，避免内部继续混用基于行列的旧选区模型。
  - 更新 [`EditorCompletionStateTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionStateTest.kt) 与 [`EditorStateEventTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorStateEventTest.kt)，校验 offset 选区事件、补全状态与状态变更行为。

### Technical
- 版本号：0.14.28 (versionCode: 1429)
- 代码统计：21 个文件修改，+392 行，-469 行
- 本次聚焦：选区坐标统一、编辑器内部 offset-first 重构、LSP 边界转换收敛

---

## [0.14.26] - 2026-03-06

### Changed
- **缩放手势坐标系与渲染时序进一步稳定**：
  - 更新 [`EditorCanvasLayer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasLayer.kt)：
    - 调整 `pointerInput` 与 `transformable` 的处理顺序，确保缩放焦点在同帧先更新再参与视觉缩放。
    - 明确选区拖拽与滚动手势的竞争边界，降低选区存在时滚动被抢占的概率。
  - 更新 [`EditorScaleTransformCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScaleTransformCoordinator.kt)：
    - 优化双指缩放流程，继续采用“手势期仅视觉缩放、结束后一次性落地字体与滚动”的路径。
    - 强化缩放结束时锚点与滚动对齐逻辑，减少缩放后文本跳变与偏移漂移。
  - 更新 [`TinaEditorUiState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorUiState.kt)：
    - 补充缩放焦点与视觉缩放状态字段注释，明确其在渲染重绘与诊断日志中的职责边界。

### Technical
- 版本号：0.14.26 (versionCode: 1427)
- 本次聚焦：双指缩放稳定性、焦点锚定一致性、手势链路可维护性

---

## [0.14.24] - 2026-03-06

### Added
- **编辑器折叠与查询编译能力增强**：
  - 新增 [`TreeSitterFoldingProvider.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TreeSitterFoldingProvider.kt)：提供基于 Tree-sitter 的折叠范围计算。
  - 新增 [`TreeSitterQueryCompiler.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TreeSitterQueryCompiler.kt)：统一查询编译与缓存逻辑。
  - 新增 [`EditorFoldingModels.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorFoldingModels.kt)：抽离折叠领域模型。
- **编辑器渲染与运行时能力补齐**：
  - 新增 [`EditorColorScheme.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorColorScheme.kt)：统一编辑器色板语义。
  - 新增 [`EditorWordWrapLayoutCache.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorWordWrapLayoutCache.kt)：多行换行布局缓存。
  - 新增 [`EditorSessionCoreRuntime.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSessionCoreRuntime.kt) 与 [`EditorSessionGestureRuntime.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSessionGestureRuntime.kt)：拆分会话核心运行时与手势运行时。
  - 新增 [`EditorSmartReplacement.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSmartReplacement.kt)：补全插入/替换策略增强。
  - 新增 [`EditorScaleTransformCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScaleTransformCoordinator.kt) 与 [`EditorTransformFocusTracker.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorTransformFocusTracker.kt)：双指缩放焦点追踪与变换协调。
- **测试与文档补充**：
  - 新增 [`TransformGestureFocusResolverTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/TransformGestureFocusResolverTest.kt)：覆盖手势焦点解析边界场景。
  - 新增调试文档：[`text-layout-technologies.md`](docs/debug/text-layout-technologies.md)、[`zoom-offset-issue-analysis.md`](docs/debug/zoom-offset-issue-analysis.md)、[`zoom-offset-root-cause-analysis.md`](docs/debug/zoom-offset-root-cause-analysis.md)。
  - 新增设计文档：[`smart-wrap-implementation.md`](docs/design/smart-wrap-implementation.md)。

### Changed
- **编辑器核心链路重构与性能优化**：
  - 大幅重构 [`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)、[`TinaEditorSession.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorSession.kt)、[`TextRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt)，提升状态一致性与渲染稳定性。
  - 优化手势与滚动链路：[`EditorCanvasGesturePipeline.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasGesturePipeline.kt)、[`EditorGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureCoordinator.kt)、[`EditorScrollGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScrollGestureCoordinator.kt)、[`EditorScrollDeltaCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScrollDeltaCoordinator.kt)。
  - 优化绘制层与可视化：[`EditorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderer.kt)、[`EditorCanvasLayer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasLayer.kt)、[`GutterRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/GutterRenderer.kt)、[`LineNumberRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/LineNumberRenderer.kt)、[`SelectionRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionRenderer.kt)、[`CursorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CursorRenderer.kt)、[`DiagnosticRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/DiagnosticRenderer.kt)。
- **编辑器设置与诊断能力增强**：
  - 扩展 [`Prefs.kt`](core/config/src/main/java/com/wuxianggujun/tinaide/core/config/Prefs.kt) 开发者诊断配置流，新增缩放/焦点/滚动/fling 级别日志开关。
  - 更新 [`DeveloperOptionsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/DeveloperOptionsSection.kt) 与 [`SettingsViewModel.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/SettingsViewModel.kt)，支持诊断选项实时联动。
  - 更新 [`EditorSettingsSection.kt`](feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/EditorSettingsSection.kt)，新增编辑器主题选择对话框。
- **编辑器页面与 LSP 集成优化**：
  - 更新 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)、[`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)、[`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)，增强折叠驱动切换、语义 token 请求边界处理与会话联动。
- **配套资源与文档更新**：
  - 更新 [`strings.xml`](core/i18n/src/main/res/values/strings.xml) 与 [`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：补充新设置项文案。
  - 更新 [`README.md`](docs/plugins/README.md) 与 [`dracula.json`](docs/plugins/sample-theme-plugin/themes/dracula.json)：完善插件主题示例。

### Technical
- 版本号：0.14.24 (versionCode: 1425)
- 代码统计：43 个已跟踪文件修改，+2439 行，-632 行
- 主要方向：编辑器架构拆分、折叠与缩放稳定性、诊断可观测性、设置体验完善

---

## [0.14.12] - 2026-03-04

### Changed
- **编辑器核心持续优化**（23 个文件修改，+854 行，-391 行）：
  - 优化 [`CompletionPopupLayoutResolver.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CompletionPopupLayoutResolver.kt)：
    - 改进补全弹窗布局解析算法
    - 优化弹窗位置计算
  - 优化 [`DiagnosticRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/DiagnosticRenderer.kt)：
    - 改进诊断信息渲染性能
    - 优化波浪线绘制逻辑
  - 优化 [`EditorCanvasLayer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasLayer.kt)：
    - 改进画布层渲染逻辑
    - 优化层级管理
  - 优化 [`EditorConfig.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorConfig.kt)：
    - 扩展配置选项
    - 改进配置验证
  - 优化 [`EditorFontScaleCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorFontScaleCoordinator.kt)：
    - 改进字体缩放逻辑
    - 优化缩放动画
  - 优化 [`EditorGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureCoordinator.kt)：
    - 改进手势协调逻辑
    - 优化手势冲突处理
  - 优化 [`EditorGestureExclusionCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureExclusionCoordinator.kt)：
    - 改进手势排除逻辑
    - 优化排除区域计算
  - 优化 [`EditorGestureHandler.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureHandler.kt)：
    - 改进手势处理逻辑
    - 优化事件分发
  - 优化 [`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)：
    - 改进覆盖层管理
    - 优化层级渲染
  - 优化 [`EditorRenderAssembly.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderAssembly.kt)：
    - 改进渲染装配逻辑
    - 优化渲染流程
  - 优化 [`EditorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderer.kt)：
    - 改进渲染器性能
    - 优化渲染算法
  - 优化 [`EditorRuntimeEffects.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRuntimeEffects.kt)：
    - 改进运行时特效
    - 优化特效性能
  - 优化 [`EditorScrollGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScrollGestureCoordinator.kt)：
    - 改进滚动手势协调
    - 优化滚动响应
  - 优化 [`EditorScrollbarRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScrollbarRenderer.kt)：
    - 改进滚动条渲染
    - 优化滚动条交互
  - 优化 [`EditorSelectionContextMenuCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSelectionContextMenuCoordinator.kt)：
    - 改进上下文菜单协调
    - 优化菜单显示逻辑
  - 优化 [`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)：
    - 改进状态管理
    - 优化状态同步
  - 优化 [`GutterRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/GutterRenderer.kt)：
    - 改进行号槽渲染
    - 优化渲染性能
  - 优化 [`LineNumberRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/LineNumberRenderer.kt)：
    - 改进行号渲染
    - 优化行号显示
  - 优化 [`ScrollbarDragCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/ScrollbarDragCoordinator.kt)：
    - 改进滚动条拖拽
    - 优化拖拽响应
  - 优化 [`SelectionHandleDragCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionHandleDragCoordinator.kt)：
    - 改进选择手柄拖拽
    - 优化拖拽精度
  - 优化 [`SelectionMagnifierController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionMagnifierController.kt)：
    - 改进放大镜控制
    - 优化放大镜显示
  - 优化 [`TinaEditorSession.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorSession.kt)：
    - 改进会话管理
    - 优化会话生命周期

### Technical
- 版本号：0.14.12 (versionCode: 1413)
- 代码统计：+854 行，-391 行
- 净增长：+463 行
- 主要改进：性能优化、代码质量提升、用户体验改进

---

## [0.14.6] - 2026-03-04

### Added
- **编辑器架构重大重构**：
  - 新增 30+ 个核心协调器和渲染组件
  - 新增 [`TinaEditorScaffold.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorScaffold.kt)：编辑器脚手架
  - 新增 [`TinaEditorSession.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorSession.kt)：编辑器会话管理
  - 新增 [`TinaEditorUiState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditorUiState.kt)：UI 状态管理

- **手势处理系统**：
  - 新增 [`EditorCanvasGesturePipeline.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasGesturePipeline.kt)：手势管道
  - 新增 [`EditorScrollGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScrollGestureCoordinator.kt)：滚动手势协调器
  - 新增 [`EditorScrollDeltaCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScrollDeltaCoordinator.kt)：滚动增量协调器
  - 新增 [`EditorGestureExclusionCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureExclusionCoordinator.kt)：手势排除协调器
  - 新增 [`PointerInputExtensions.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/PointerInputExtensions.kt)：指针输入扩展

- **选择和拖拽系统**：
  - 新增 [`SelectionHandleDragCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionHandleDragCoordinator.kt)：选择手柄拖拽协调器
  - 新增 [`SelectionHandleLayout.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionHandleLayout.kt)：选择手柄布局
  - 新增 [`SelectionMagnifierController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionMagnifierController.kt)：选择放大镜控制器
  - 新增 [`ScrollbarDragCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/ScrollbarDragCoordinator.kt)：滚动条拖拽协调器
  - 新增 [`ScrollbarVisibilityCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/ScrollbarVisibilityCoordinator.kt)：滚动条可见性协调器

- **上下文菜单系统**：
  - 新增 [`EditorSelectionContextMenuCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSelectionContextMenuCoordinator.kt)：选择上下文菜单协调器
  - 新增 [`ContextMenuLayoutResolver.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/ContextMenuLayoutResolver.kt)：上下文菜单布局解析器
  - 新增 [`ContextMenuPopupPositionProvider.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/ContextMenuPopupPositionProvider.kt)：上下文菜单弹窗位置提供器
  - 新增 [`CompletionPopupLayoutResolver.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CompletionPopupLayoutResolver.kt)：补全弹窗布局解析器

- **渲染和布局系统**：
  - 新增 [`EditorCanvasLayer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCanvasLayer.kt)：编辑器画布层
  - 新增 [`EditorInputHostLayer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInputHostLayer.kt)：输入宿主层
  - 新增 [`EditorOverlays.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverlays.kt)：编辑器覆盖层
  - 新增 [`EditorRenderAssembly.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderAssembly.kt)：渲染装配器
  - 新增 [`EditorLineLayoutCache.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorLineLayoutCache.kt)：行布局缓存

- **剪贴板和焦点管理**：
  - 新增 [`EditorClipboardBridge.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorClipboardBridge.kt)：剪贴板桥接
  - 新增 [`EditorClipboardCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorClipboardCoordinator.kt)：剪贴板协调器
  - 新增 [`EditorFocusCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorFocusCoordinator.kt)：焦点协调器
  - 新增 [`EditorFontScaleCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorFontScaleCoordinator.kt)：字体缩放协调器

- **特效和同步**：
  - 新增 [`EditorRuntimeEffects.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRuntimeEffects.kt)：运行时特效
  - 新增 [`EditorTextBufferRendererSyncEffect.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorTextBufferRendererSyncEffect.kt)：文本缓冲区渲染同步特效

- **调试和测试**：
  - 新增 [`EditorTouchDiagnostics.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorTouchDiagnostics.kt)：触摸诊断
  - 新增 [`ContextMenuLayoutResolverTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/ContextMenuLayoutResolverTest.kt)：上下文菜单布局解析器测试
  - 新增 [`EditorScrollAxisDeciderTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorScrollAxisDeciderTest.kt)：滚动轴决策器测试

### Changed
- **编辑器核心重构**（17 个文件修改，+765 行，-1,231 行）：
  - 重构 [`TinaEditor.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditor.kt)：
    - 从单体架构重构为模块化协调器架构
    - 分离手势处理、渲染、输入管理等职责
    - 改进性能和可维护性
  - 重构 [`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)：
    - 优化状态管理逻辑
    - 改进状态同步机制
  - 重构 [`EditorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderer.kt)：
    - 分离渲染逻辑到专门的渲染器
    - 优化渲染性能
  - 重构 [`TextRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt)：
    - 改进文本渲染算法
    - 优化大文件渲染性能
  - 重构 [`SelectionRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionRenderer.kt)：
    - 改进选择区域渲染
    - 优化选择手柄显示
  - 重构 [`CursorRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/CursorRenderer.kt)：
    - 优化光标渲染和闪烁动画
  - 重构 [`EditorScrollbarRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScrollbarRenderer.kt)：
    - 改进滚动条渲染逻辑
    - 优化滚动条交互

- **输入处理优化**：
  - 重构 [`EditorInputConnection.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInputConnection.kt)：
    - 改进 IME 输入处理
    - 优化输入法兼容性
  - 重构 [`EditorInputConnectionUtils.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInputConnectionUtils.kt)：
    - 提取输入连接工具函数
  - 重构 [`EditorInteractionController.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInteractionController.kt)：
    - 改进交互控制逻辑

- **手势处理优化**：
  - 重构 [`EditorGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureCoordinator.kt)：
    - 改进手势协调逻辑
    - 优化多点触控处理

- **上下文菜单优化**：
  - 重构 [`EditorSelectionContextMenu.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorSelectionContextMenu.kt)：
    - 改进上下文菜单显示逻辑
    - 优化菜单项布局

- **配置扩展**：
  - 优化 [`EditorConfig.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorConfig.kt)：
    - 新增更多配置选项
    - 改进配置管理

- **测试增强**：
  - 优化 [`EditorInputConnectionUtilsTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorInputConnectionUtilsTest.kt)：
    - 补充测试用例
    - 改进测试覆盖率

- **国际化完善**：
  - 更新 [`strings.xml`](core/i18n/src/main/res/values/strings.xml)：
    - 新增编辑器相关字符串资源
  - 更新 [`strings.xml`](core/i18n/src/main/res/values-en/strings.xml)：
    - 同步英文翻译

### Technical
- 版本号：0.14.6 (versionCode: 1407)
- 架构重构：从单体编辑器重构为模块化协调器架构
- 新增文件：30+ 个（协调器、渲染器、测试）
- 修改文件：17 个
- 代码统计：+765 行，-1,231 行
- 净减少：-466 行（通过重构和优化）
- 主要改进：架构模块化、性能优化、可维护性提升

---

## [0.13.90] - 2026-03-03

### Added
- **编辑器滚动条渲染器**：
  - 新增 [`EditorScrollbarRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorScrollbarRenderer.kt)：自定义滚动条渲染
  - 支持垂直和水平滚动条显示
  - 滚动条自动隐藏和淡入淡出动画

- **编辑器服务系统**：
  - 新增 `app/src/main/java/com/wuxianggujun/tinaide/service/` 目录：编辑器后台服务

- **LSP 代码片段占位符处理文档**：
  - 新增 [`LSP-Snippet-Placeholder-Handling.md`](docs/design/LSP-Snippet-Placeholder-Handling.md)：LSP 代码片段占位符处理设计

### Changed
- **编辑器核心功能增强**：
  - 优化 [`TinaEditor.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditor.kt)：
    - 新增滚动条渲染支持
    - 优化触摸事件处理
    - 改进滚动性能
    - 新增 +453 行核心功能代码
  - 优化 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：
    - 改进编辑器页面布局
    - 优化状态管理
    - 新增 +136 行功能代码

- **诊断渲染增强**：
  - 优化 [`DiagnosticRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/DiagnosticRenderer.kt)：
    - 改进诊断信息渲染逻辑
    - 优化波浪线绘制性能
    - 新增 +133 行渲染代码
  - 优化 [`DiagnosticWavePathBuilder.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/DiagnosticWavePathBuilder.kt)：
    - 改进波浪线路径构建算法

- **编辑器配置扩展**：
  - 优化 [`EditorConfig.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorConfig.kt)：
    - 新增滚动条配置选项
    - 扩展编辑器配置参数

- **手势处理优化**：
  - 优化 [`EditorGestureCoordinator.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureCoordinator.kt)：
    - 改进手势协调逻辑
  - 优化 [`EditorOverScrollerFlingBehavior.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorOverScrollerFlingBehavior.kt)：
    - 改进 fling 行为处理
    - 优化过度滚动效果

- **LSP 管理器优化**：
  - 优化 [`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)：
    - 改进 LSP 编辑器管理逻辑
    - 优化状态同步

- **编辑器管理器增强**：
  - 优化 [`EditorManager.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/EditorManager.kt)：
    - 改进编辑器实例管理
  - 优化 [`DocumentSession.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/session/DocumentSession.kt)：
    - 改进文档会话管理
    - 优化保存逻辑

- **主界面优化**：
  - 优化 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)：
    - 改进主界面逻辑
  - 更新 [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)：
    - 新增服务声明

### Technical
- 版本号：0.13.90 (versionCode: 1391)
- 新增代码：+1,416 行
- 删除代码：-136 行
- 净增长：+1,280 行
- 主要改进：滚动条渲染、诊断显示、手势处理、性能优化

---

## [0.13.75] - 2026-03-03

### Added
- **TinaEditor 核心模块**：
  - 新增 [`core:text-engine`](core/text-engine)：基于 Rope 数据结构的高性能文本引擎
    - [`Rope.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/Rope.kt)：不可变 Rope 实现，支持高效的文本插入/删除/切片操作
    - [`RopeTextBuffer.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/RopeTextBuffer.kt)：可变文本缓冲区，支持撤销/重做
    - [`LineIndex.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/LineIndex.kt)：行索引管理，支持快速行号与偏移量转换
    - [`EditHistory.kt`](core/text-engine/src/main/java/com/wuxianggujun/tinaide/core/textengine/EditHistory.kt)：编辑历史管理
  - 新增 [`core:editor-view`](core/editor-view)：自定义 View 编辑器实现
    - [`TinaEditor.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaEditor.kt)：核心编辑器 View，基于 Canvas 渲染
    - [`EditorState.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)：编辑器状态管理（光标、选区、滚动位置等）
    - [`EditorRenderEngine.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderEngine.kt)：渲染引擎
    - [`EditorGestureHandler.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorGestureHandler.kt)：手势处理（点击、长按、滑动、fling）
    - [`EditorInputConnection.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorInputConnection.kt)：IME 输入处理
    - [`TreeSitterHighlighter.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TreeSitterHighlighter.kt)：Tree-sitter 语法高亮
    - [`DiagnosticRenderer.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/DiagnosticRenderer.kt)：诊断信息渲染（波浪线）
    - [`EditorCompletionPopup.kt`](core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionPopup.kt)：代码补全弹窗
  - 新增 [`core:editor-lsp`](core/editor-lsp)：LSP 集成层
    - [`CompletionProvider.kt`](core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/CompletionProvider.kt)：LSP 补全提供器
    - [`DiagnosticsManager.kt`](core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/DiagnosticsManager.kt)：诊断信息管理
    - [`NavigationService.kt`](core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/NavigationService.kt)：导航服务（跳转定义、查找引用等）
    - [`SemanticServices.kt`](core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/SemanticServices.kt)：语义服务（语义高亮、符号查询等）

- **TinaEditor UI 集成**：
  - 新增 [`TinaCodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt)：Compose 编辑器页面组件
  - 新增 [`TinaPluginEditorBridge.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/TinaPluginEditorBridge.kt)：插件系统桥接层
  - 新增 [`TinaTextContentProvider.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/TinaTextContentProvider.kt)：文本内容提供器
  - 新增 [`LspSemanticTokenDecoder.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspSemanticTokenDecoder.kt)：LSP 语义 Token 解码器

- **LSP 系统增强**：
  - 新增 [`LspClientSession.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspClientSession.kt)：LSP 客户端会话管理
  - 新增 [`LspSession.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspSession.kt)：LSP 会话抽象
  - 新增 [`LspConnectionProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspConnectionProvider.kt)：LSP 连接提供器接口

- **搜索系统增强**：
  - 新增 [`SearchRange.kt`](core/search/src/main/java/com/wuxianggujun/tinaide/search/SearchRange.kt)：搜索范围定义
  - 新增 [`CodeSearchEngineTest.kt`](core/search/src/test/java/com/wuxianggujun/tinaide/search/CodeSearchEngineTest.kt)：搜索引擎单元测试

- **测试覆盖**：
  - 新增 [`LspSemanticTokenDecoderTest.kt`](app/src/test/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspSemanticTokenDecoderTest.kt)
  - 新增 [`EditorCompletionStateTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorCompletionStateTest.kt)
  - 新增 [`EditorGutterInteractionTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorGutterInteractionTest.kt)
  - 新增 [`EditorInputConnectionUtilsTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorInputConnectionUtilsTest.kt)
  - 新增 [`EditorStateEventTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/EditorStateEventTest.kt)
  - 新增 [`ImeDeltaMappingTest.kt`](core/editor-view/src/test/java/com/wuxianggujun/tinaide/core/editorview/ImeDeltaMappingTest.kt)
  - 新增 [`LineIndexTest.kt`](core/text-engine/src/test/java/com/wuxianggujun/tinaide/core/textengine/LineIndexTest.kt)
  - 新增 [`RopeTest.kt`](core/text-engine/src/test/java/com/wuxianggujun/tinaide/core/textengine/RopeTest.kt)
  - 新增 [`RopeTextBufferTest.kt`](core/text-engine/src/test/java/com/wuxianggujun/tinaide/core/textengine/RopeTextBufferTest.kt)

- **文档完善**：
  - 新增 [`TinaEditor-问题修复提示词.md`](docs/TinaEditor-问题修复提示词.md)：问题修复指南
  - 新增 [`TinaEditor性能优化指南.md`](docs/TinaEditor性能优化指南.md)：性能优化指南
  - 新增 [`TinaEditor性能优化指南-高级技术.md`](docs/TinaEditor性能优化指南-高级技术.md)：高级性能优化技术
  - 新增 [`floating-log-window-design.md`](docs/floating-log-window-design.md)：浮动日志窗口设计

### Changed
- **编辑器架构重构**：
  - 移除 tina-sora-editor 子模块依赖，改用自研 TinaEditor
  - 重构 [`EditorContainerState.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt)：适配 TinaEditor
  - 重构 [`LspEditorManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt)：LSP 管理器重构
  - 重构 [`EditorTabManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorTabManager.kt)：标签页管理优化
  - 重构 [`SearchStateManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/SearchStateManager.kt)：搜索状态管理

- **编辑器功能模块重构**：
  - 重构 [`EditorManager.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/EditorManager.kt)：编辑器管理器
  - 重构 [`DocumentSession.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/session/DocumentSession.kt)：文档会话管理
  - 重构 [`BookmarkService.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/bookmark/BookmarkService.kt)：书签服务
  - 重构 [`ProjectSymbolIndexService.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/symbol/ProjectSymbolIndexService.kt)：符号索引服务
  - 重构 [`PluginEditorThemeRegistry.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/theme/PluginEditorThemeRegistry.kt)：主题注册表

- **LSP 系统重构**：
  - 重构 [`LspDiagnosticsBridge.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspDiagnosticsBridge.kt)：诊断桥接层
  - 重构 [`LspCodeActionService.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspCodeActionService.kt)：代码操作服务
  - 重构 [`LspNavigationService.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspNavigationService.kt)：导航服务
  - 优化 [`NativeClangdConnectionProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/NativeClangdConnectionProvider.kt)
  - 优化 [`PRootClangdConnectionProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/PRootClangdConnectionProvider.kt)
  - 优化 [`RemoteLspConnectionProvider.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/RemoteLspConnectionProvider.kt)

- **搜索系统优化**：
  - 优化 [`CodeSearchEngine.kt`](core/search/src/main/java/com/wuxianggujun/tinaide/search/CodeSearchEngine.kt)：搜索引擎性能优化
  - 优化 [`SearchOptions.kt`](core/search/src/main/java/com/wuxianggujun/tinaide/search/SearchOptions.kt)：搜索选项扩展
  - 优化 [`SearchResult.kt`](core/search/src/main/java/com/wuxianggujun/tinaide/search/SearchResult.kt)：搜索结果模型

- **UI 组件优化**：
  - 优化 [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)：主界面适配 TinaEditor
  - 优化 [`MainActivityActionsViewModel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityActionsViewModel.kt)：动作视图模型
  - 优化 [`MainActivityNavigationHelper.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityNavigationHelper.kt)：导航辅助
  - 优化 [`EditorContainer.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorContainer.kt)：编辑器容器
  - 优化 [`CodeBlock.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/CodeBlock.kt)：代码块组件
  - 优化 [`OutlineContent.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/OutlineContent.kt)：大纲内容
  - 优化 [`SearchBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/SearchBar.kt)：搜索栏
  - 优化 [`FloatingSearchBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FloatingSearchBar.kt)：浮动搜索栏

- **测试界面简化**：
  - 简化 [`CppScrollStressTestScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/CppScrollStressTestScreen.kt)
  - 简化 [`EditorScrollTestScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/EditorScrollTestScreen.kt)
  - 简化 [`ThemePreviewTestScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/ThemePreviewTestScreen.kt)
  - 简化 [`TreeSitterTestScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/TreeSitterTestScreen.kt)
  - 新增 [`LegacyEditorScreenPlaceholder.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/testing/LegacyEditorScreenPlaceholder.kt)：旧编辑器占位符

- **构建系统优化**：
  - 更新 [`settings.gradle.kts`](settings.gradle.kts)：移除 sora-editor 模块，新增 TinaEditor 模块
  - 更新 [`build.gradle.kts`](build.gradle.kts)：依赖管理优化
  - 更新 [`app/build.gradle.kts`](app/build.gradle.kts)：应用模块配置
  - 更新 [`app/proguard-rules.pro`](app/proguard-rules.pro)：混淆规则更新

- **文档更新**：
  - 更新 [`TinaEditor-Migration-Strategy.md`](docs/design/TinaEditor-Migration-Strategy.md)：迁移策略
  - 更新 [`TinaEditor-Missing-Features.md`](docs/design/TinaEditor-Missing-Features.md)：缺失功能清单
  - 更新 [`TinaEditor-Module-Setup.md`](docs/design/TinaEditor-Module-Setup.md)：模块设置指南
  - 更新 [`TinaEditor-Rewrite-Design.md`](docs/design/TinaEditor-Rewrite-Design.md)：重写设计文档
  - 更新 [`代码统计指南.md`](docs/guides/代码统计指南.md)：代码统计指南

### Removed
- **移除 tina-sora-editor 依赖**：
  - 移除 `external/tina-sora-editor` 子模块引用
  - 移除 `sora-editor-build-logic` 构建逻辑
  - 移除 `:sora-editor:editor`、`:sora-editor:editor-lsp`、`:sora-editor:language-textmate`、`:sora-editor:language-treesitter`、`:sora-editor:oniguruma-native` 模块

- **移除旧编辑器代码**（feature/editor）：
  - 删除 [`BreakpointAwareCodeEditor.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/BreakpointAwareCodeEditor.kt)
  - 删除 [`BreakpointManager.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/BreakpointManager.kt)
  - 删除 [`TinaDarkColorScheme.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/TinaDarkColorScheme.kt)
  - 删除 [`TinaGrayColorScheme.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/TinaGrayColorScheme.kt)
  - 删除 [`TinaLightColorScheme.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/TinaLightColorScheme.kt)
  - 删除 `editor/action/` 目录（EditMenuGroup、NavigationMenuGroup、TextMenuGroup）
  - 删除 [`BookmarkManager.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/bookmark/BookmarkManager.kt)
  - 删除 `editor/completion/` 目录（CompletionItemComparators、CxxCompletionEngine、CxxPreprocessorCompletion）
  - 删除 [`EditorTouchDebug.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/debug/EditorTouchDebug.kt)
  - 删除 [`MarkerLineResolver.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/gutter/MarkerLineResolver.kt)
  - 删除 `editor/language/` 目录（C/C++/CMake/Make 语言提供器）
  - 删除 `editor/navigation/` 目录（HeaderNavigationManager、HeaderNavigationTextAction）
  - 删除 `editor/performance/` 目录（性能优化相关代码）
  - 删除 [`SoraEditorBridge.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/plugin/SoraEditorBridge.kt)
  - 删除 [`SoraEditorTextContentProvider.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/search/SoraEditorTextContentProvider.kt)
  - 删除 [`CodeEditorBinding.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/session/CodeEditorBinding.kt)
  - 删除 [`SnippetCompletionItems.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/snippet/SnippetCompletionItems.kt)
  - 删除 [`ConfigEditorColorScheme.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/theme/ConfigEditorColorScheme.kt)
  - 删除 [`EditorTouchInterceptGuard.kt`](feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/touch/EditorTouchInterceptGuard.kt)

- **移除旧 UI 组件**（app）：
  - 删除 [`CodeEditorPage.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/CodeEditorPage.kt)
  - 删除 [`FilePathBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/FilePathBar.kt)
  - 删除 [`CodeEditorTextContentProvider.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/CodeEditorTextContentProvider.kt)
  - 删除 [`EditorAppearanceManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorAppearanceManager.kt)
  - 删除 [`EditorCacheManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorCacheManager.kt)
  - 删除 [`EditorFeatureManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorFeatureManager.kt)
  - 删除 [`EditorInstanceManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorInstanceManager.kt)
  - 删除 [`SemanticTokensStyleProviders.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/SemanticTokensStyleProviders.kt)
  - 删除 `editor/features/` 目录（EditorBookmarkFeature、EditorBreakpointFeature、EditorHeaderNavigationFeature、EditorLanguageLspFeature）
  - 删除 [`JsonViewerScreen.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/viewer/JsonViewerScreen.kt)

### Fixed
- **诊断系统修复**：
  - 修复 [`Diagnostic.kt`](core/common/src/main/java/com/wuxianggujun/tinaide/core/lsp/Diagnostic.kt)：诊断数据模型优化
  - 修复 [`LspDiagnosticsBridge.kt`](core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspDiagnosticsBridge.kt)：诊断桥接层稳定性

- **C++ 文件支持修复**：
  - 修复 [`CxxFileSupport.kt`](core/common/src/main/java/com/wuxianggujun/tinaide/core/lang/CxxFileSupport.kt)：C++ 文件类型识别
  - 修复 [`CxxFileSupportTest.kt`](core/common/src/test/java/com/wuxianggujun/tinaide/core/lang/CxxFileSupportTest.kt)：单元测试

- **Tree-sitter 查询文件修复**：
  - 修复 [`cmake/blocks.scm`](app/src/main/assets/tree-sitter-queries/cmake/blocks.scm)
  - 修复 [`rust/blocks.scm`](app/src/main/assets/tree-sitter-queries/rust/blocks.scm)
  - 修复 [`rust/locals.scm`](app/src/main/assets/tree-sitter-queries/rust/locals.scm)

### Technical
- 版本号：0.13.75 (versionCode: 1376)
- 新增模块：3 个（text-engine、editor-view、editor-lsp）
- 删除文件：100+ 个（旧编辑器相关代码）
- 新增文件：80+ 个（TinaEditor 实现）
- 代码统计：新增约 13,929 行，删除约 11,461 行
- 单元测试：新增 13 个测试类

---

## [0.13.52] - 2026-03-01

### Added
- **新增 SDL3 库构建系统**：
  - 添加 SDL3 库的 CMake 构建配置和文档
  - 支持在 Android 平台上构建和使用 SDL3
- **新增内置包自动安装器**：
  - 实现 `BundledPackagesInstaller` 自动安装机制
  - 支持首次启动时自动安装必需的工具链包
- **新增开发者选项功能**：
  - 设置页面添加"重新安装内置包"按钮
  - 方便开发者重置和调试包管理系统

### Changed
- **包管理系统增强**：
  - 支持多种压缩格式（tar.xz、tar.zst、tar.gz、zip）
  - 重构压缩解压工具，提升模块化程度
  - 优化 `InstalledPackagePathResolver` 路径解析逻辑
  - 改进 `PackageManagerImpl` 安装流程
- **编译系统优化**：
  - 增强 `BuildStrategy` 接口设计
  - 优化 `CompileProjectUseCase` 编译流程
  - 改进 Make/CMake 构建策略实现
  - 完善 `RunConfiguration` 运行配置管理
  - 优化 `ProgramRunner` 程序执行逻辑
- **LSP 系统改进**：
  - 优化 `CompileCommandsGenerator` 生成逻辑
  - 改进 `CompileDatabaseProvider` 数据库提供机制
  - 完善 LSP 测试类型定义
- **编辑器功能增强**（子模块 tina-sora-editor）：
  - LSP 与 Tree-sitter 分析器稳定性改进
  - 新增 LSP Semantic Tokens 支持
  - 增强样式补丁功能
  - 编辑器文本动作窗口支持分组和折叠
  - 改进 tree-sitter 行范围和作用域变量处理
  - 优化 Hover 窗口和 Markdown 渲染
- **UI/UX 改进**：
  - 优化主界面布局和交互
  - 改进运行配置对话框
  - 完善编辑器容器状态管理
  - 优化下拉刷新组件
  - 改进各类 UI 组件样式
- **项目配置优化**：
  - 更新 C++ 标准配置选项
  - 改进项目元数据管理
  - 优化配置持久化机制
- **国际化改进**：
  - 新增和更新多个字符串资源
  - 完善中英文翻译
  - 优化内容描述文本

### Fixed
- **修复编译系统问题**：
  - 修复 Make 命令覆盖逻辑
  - 修复 Native/PRoot Make 构建策略问题
  - 修复 CMake 构建执行器路径处理
  - 修复 Ninja CMake 路径补丁问题
- **修复包管理问题**：
  - 修复包依赖事件处理
  - 修复安装状态存储问题
- **修复 LSP 相关问题**：
  - 修复 LSP 编辑器 UI 委托问题
  - 修复 LSP 语言服务器包装器问题
  - 改进 Hover 窗口显示逻辑

### Technical
- 版本号：0.13.52 (versionCode: 1353)
- 子模块 tina-sora-editor 更新至最新提交
- 新增多个单元测试：
  - `MakeCommandOverridesTest`
  - `NinjaCmakePathPatcherTest`
  - `RunConfigurationManagerMigrationTest`
  - `NativeCMakeBuildExecutorPatchGateTest`
- 新增设计文档：
  - `Build-Config-Consolidation-Design.md`
  - `TinaEditor-API-Design.md`
  - `TinaEditor-Compose-Implementation.md`
  - `TinaEditor-Implementation-Details.md`
  - `TinaEditor-Missing-Features.md`
  - `TinaEditor-Rewrite-Design.md`

---

## [0.13.40] - 2026-02-26

### Fixed
- **修复 CMake + Ninja 构建静态库时的权限失败**：
  - 修复 `cmake -E rm -f lib*.a` 在 Android 私有目录下触发 `Permission denied` 的问题。
  - 修复静态库归档阶段 `llvm-ar` / `llvm-ranlib` shim 脚本被直接执行导致 `Permission denied` 的问题。
- **修复路径别名导致的“只修复一半”问题**：
  - 补齐 `/data/user/0/...` 与 `/data/data/...` 两种路径别名替换，避免 Ninja 规则中遗漏某一类路径。

### Changed
- **增强 Native CMake Ninja 规则补丁逻辑**（`NativeCMakeBuildExecutor`）：
  - 将 Ninja 中所有 raw `cmake` 路径统一替换为 `shim(linker64)` 或 `linker64` 包装执行。
  - 自动扫描并补丁 shim 工具路径（`cmake`、`llvm-ar`、`llvm-ranlib` 等），统一改写为 `/system/bin/sh <shim>`。
  - 替换流程改为幂等处理，避免重复包裹导致命令污染。

### Added
- **新增 Ninja 路径补丁器**：`NinjaCmakePathPatcher`
- **新增回归测试**：`NinjaCmakePathPatcherTest`
  - 覆盖 `cmake` 路径别名替换
  - 覆盖 linker64 模式幂等
  - 覆盖 shim 脚本 `/system/bin/sh` 包裹逻辑

### Technical
- 版本号：0.13.40 (versionCode: 1341)
- 构建相关回归测试：`:core:compile:testDebugUnitTest` 通过

---

## [0.13.16] - 2026-02-25

### Changed
- **工具链升级至 v0.2.2**：
  - 更新 `current.properties` 指向 tinaide-toolchain-aarch64-v0.2.2
  - 新增 v0.2.0、v0.2.1、v0.2.2 工具链包及 sha256 校验文件
- **工具链构建脚本大幅增强**（`build-and-package-android-toolchain.sh`）：
  - 新增 LLVM 源码补丁机制（`APPLY_LLVM_ANDROID_EXEC_PATCH`），支持自动应用 Android linker64/argv0 修复补丁
  - 新增 `NINJA_TARGETS` 参数：支持自定义 ninja 构建目标，增量编译更灵活
  - 新增 `SKIP_STAGE_AND_PACKAGE` 参数：支持仅构建不打包，便于调试
  - 新增 host toolchain 版本一致性校验：自动检测 llvm-tblgen 主版本号，版本不匹配时自动重建
  - 新增 clang runtime 兼容目录生成：为所有 sysroot API level 创建 `<triple><api>` 格式的 runtime 目录，解决 clang driver 查找 builtins 路径不一致问题
  - 新增 runtime 库完整性校验（builtins、libunwind）
  - 打包阶段自动补全缺失的 ninja targets
- **包管理安装路径统一**：
  - `DownloadPackageBackend` 安装目录从 `ndk-sysroot` 改为 `installed-packages`，统一以 `packageId` 作为安装子目录
  - 移除 `extractPath` 参数依赖，`uninstall()`、`isInstalled()`、`getInstallPath()` 接口简化
  - `PackageModels` 中 `extractPath` 字段标记为 `@Deprecated`
  - 管理后台 `detail.vue` 中解压路径字段标记为已弃用并禁用
- **MessagePackCodec 解码优化**：
  - `decodeOkEnvelope()` 从泛型 `MsgpackApiEnvelope<T>` 改为手动解析 JsonObject，避免嵌套泛型反序列化问题
  - 移除 `MsgpackApiEnvelope` 内部数据类
  - `TinaServerApi` 和 `ServerConfigManager` 调用方式改为 reified 泛型 `decode<T>()`
- **编译器诊断测试页面重构**（`CompilerDiagnosticsTestScreen`）：
  - 新增 `ClangRuntimeLayout` 数据类，统一管理 linux 路径和 triple 路径两种 runtime 布局
  - 新增 `resolveClangRuntimeLayout()` 方法，支持 `<arch>-unknown-linux-android<api>` triple 目录解析
  - 诊断输出增加 triple 目录检测、优先 builtins/libunwind 路径显示
  - 链接测试使用多 runtime 搜索目录（`-L`），兼容新旧两种 runtime 布局
  - 提取 `DIAGNOSTIC_TARGET_API_LEVEL` 常量，消除魔法数字

### Added
- **新增工具链补丁文件**：`tools/toolchain-patches/llvm-android-linker-exec-modified.patch`
- **新增工具链重打包脚本**：`scripts/repack-toolchain-from-existing-package.sh`
- **新增故障排查文档**：
  - `docs/troubleshooting/llvm-clang-incremental-build.md`
  - `docs/troubleshooting/llvm-clang-linker64-review-bundle.md`

### Technical
- 版本号：0.13.16 (versionCode: 1317)
- 工具链版本：v0.2.2（从 v0.2.0 升级）
- android-sysroot 包更新
- 包管理安装路径统一为 `installed-packages/<packageId>`

---

## [0.13.11] - 2026-02-24

### Changed
- **工具链系统重大升级**：
  - 升级 tina-toolchain 至 v0.2.0 版本
  - 更新 `current.properties` 配置，指向最新工具链
  - 新增 `ToolchainConfig.kt`：工具链配置管理
  - 优化 `AndroidNativeToolchainManager`：工具链管理逻辑改进
- **资源文件整合优化**：
  - 删除 80+ 个重复的 drawable 图标资源文件（app/feature 模块间去重）
  - 统一图标资源到 `core/designsystem/src/main/res/` 目录
  - 优化资源引用路径，减少 APK 体积
- **核心模块重构**：
  - **启动流程优化**：
    - 重构 `MainActivity.kt`、`TinaApplication.kt`
    - 优化 `CoreServiceRegistrar`、`ProjectMetadataInitializer`、`ThemeInitializer`
  - **UI 管理改进**：
    - 重构 `IUIManager`、`MainActivityActionsViewModel`、`MainPortalActivity`
    - 优化 `ProjectManagerViewModel`、`EditorContainerState`
  - **配置管理迁移**：
    - 迁移 `LicensePreferences`、`MTFileProviderManager`、`ThemeManager` 到 `core/config` 包
    - 统一配置管理入口
  - **存储模块重构**：
    - 新增 `ProjectDirStructure.kt`、`ProjectPaths.kt`
    - 迁移 `ExternalFileIntents`、`ProjectExporter` 到 `core/storage` 包
    - 优化 `ProjectLocationManager`、`StorageManager`、`WorkspaceManager`
- **编译系统改进**：
  - 优化 `BuildCache`、`BuildMetrics`、`BuildStrategy`
  - 改进 `CompileProjectUseCase`、`ProgramRunner`、`RunConfiguration`
  - 优化 `CMakeBuildStrategy`、`NativeCMakeBuildExecutor`
  - 改进 `SingleFileBuildStrategy` 单文件编译流程
- **网络与序列化**：
  - 新增 `core/common/src/main/java/com/wuxianggujun/tinaide/core/serialization/` 目录
  - 优化 `MessagePackCodec`、`ApiEnvelopeParser`
- **认证系统优化**：
  - 改进 `AuthRepositoryImpl`、`JwtUtils`、`UserPreferences`
  - 优化 `TinaServerApi` API 客户端
- **UI 组件改进**：
  - 优化 `MembershipBadge`、`RunConfigDialog`
  - 改进 `ProfileScreen`、`ProfileViewModel`、`ProjectScreen`
  - 优化 `CompilerDiagnosticsTestScreen`、`JsonViewerScreen`
  - 新增 `ToolchainImportDialog`、`ToolchainImportState`：工具链导入功能
- **设置界面优化**：
  - 改进 `SettingsViewModel`、`OpenSourceLicensesActivity`
  - 优化 `CompilerSettingsSection`、`EditorSettingsSection`
  - 改进 `PluginInstallHelper`、`TerminalSettingsSection`
  - 优化资源别名：`HelpResAliases`、`LoginResAliases`、`SettingsResAliases`、`WorkspaceResAliases`
- **包管理与插件系统**：
  - 优化 `PackageApiClient`、`PackageCacheManager`、`PackageModels`
  - 改进 `InstallHistoryStore`、`LocalInstallStateStore`
  - 优化 `PluginManager`、`PluginModels`、`PluginMarketplaceApi`
  - 改进 `SnippetMarketplaceApi`、`SnippetModels`
- **PRoot 与工具链管理**：
  - 优化 `PRootManager`、`PRootSessionLogger`、`InstallLogManager`
  - 改进 `AlpineRootfsManager`、`ToolchainManifestStore`、`SymlinkConfigStore`
- **项目管理优化**：
  - 优化 `ProjectMetadata`（core/model 和 core/project）
  - 改进 `ProjectDialogs`、`ProjectListComponents`
  - 优化 `DependencyInstallViewModel`、`InstallContentComponents`
  - 改进 `PRootLogActivity`
- **许可证系统改进**：
  - 优化 `LicenseRepository`、`LicenseViewModel`、`LicenseStatusCard`
- **其他模块优化**：
  - 改进 `NativeCrashHandler`、`LogExportUtils`
  - 优化 `DeviceInfo`、`FeedbackModels`
  - 改进 `PathValidator` 及其测试
  - 优化 `HeaderPathResolver`、`TutorialProgressStore`
  - 改进 `AiApiClient`、`ChatModels`
  - 优化 `DatabaseModule`、`LocalUserContentRepository`
  - 改进 `TinaIDETheme` 主题系统
- **构建系统优化**：
  - 更新所有模块的 `build.gradle.kts`（30+ 个模块）
  - 优化依赖版本管理（`gradle/libs.versions.toml`）
  - 改进根项目 `build.gradle.kts`
- **工具链构建脚本**：
  - 优化 `build-and-package-android-toolchain.sh`
  - 改进 `build-android-tools.sh`
  - 新增 `monitor-toolchain-progress.ps1`：工具链进度监控
  - 更新 `sync-tina-toolchain-assets.ps1`
  - 删除 `llvm-android-linker-exec.patch`（已集成到工具链）
- **Docker 构建优化**：
  - 更新 `docker/toolchain-builder/Dockerfile`
- **国际化完善**：
  - 更新 `strings.xml` 和 `values-en/strings.xml`

### Added
- **新增工具链配置**：
  - `core/ndk/src/main/java/com/wuxianggujun/tinaide/core/ndk/ToolchainConfig.kt`
- **新增序列化模块**：
  - `core/common/src/main/java/com/wuxianggujun/tinaide/core/serialization/` 目录
- **新增存储管理类**：
  - `core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectDirStructure.kt`
  - `core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectPaths.kt`
  - `core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ExternalFileIntents.kt`
  - `core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectExporter.kt`
- **新增配置管理类**：
  - `core/config/src/main/java/com/wuxianggujun/tinaide/core/config/LicensePreferences.kt`
  - `core/config/src/main/java/com/wuxianggujun/tinaide/core/config/MTFileProviderManager.kt`
  - `core/config/src/main/java/com/wuxianggujun/tinaide/core/config/ThemeManager.kt`
- **新增设置界面组件**：
  - `feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/ToolchainImportDialog.kt`
  - `feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/ToolchainImportState.kt`
- **新增资源目录**：
  - `core/designsystem/src/main/res/`：统一的设计系统资源
- **新增工具脚本**：
  - `tools/monitor-toolchain-progress.ps1`

### Removed
- **删除重复图标资源**（80+ 个文件）：
  - `app/src/main/res/drawable/ic_*.xml`（40+ 个）
  - `feature/help/src/main/res/drawable/ic_help_*.xml`（10 个）
  - `feature/login/src/main/res/drawable/ic_*.xml`（4 个）
  - `feature/projectlist/src/main/res/drawable/ic_*.xml`（1 个）
  - `feature/settings/src/main/res/drawable/ic_*.xml`（14 个）
  - `feature/workspace/src/main/res/drawable/ic_*.xml`（26 个）
- **删除旧位置的类文件**：
  - `core/config/src/main/java/com/wuxianggujun/tinaide/license/LicensePreferences.kt`
  - `core/config/src/main/java/com/wuxianggujun/tinaide/provider/MTFileProviderManager.kt`
  - `core/config/src/main/java/com/wuxianggujun/tinaide/ui/theme/ThemeManager.kt`
  - `core/storage/src/main/java/com/wuxianggujun/tinaide/core/project/ProjectPaths.kt`
  - `core/storage/src/main/java/com/wuxianggujun/tinaide/project/ProjectPaths.kt`
  - `core/storage/src/main/java/com/wuxianggujun/tinaide/utils/ExternalFileIntents.kt`
  - `core/storage/src/main/java/com/wuxianggujun/tinaide/utils/ProjectExporter.kt`
- **删除旧工具链资源**：
  - `app/src/arm64/assets/tina-toolchain/tinaide-toolchain-aarch64-v0.1.9.*`（3 个文件）
- **删除已集成的补丁**：
  - `tools/toolchain-patches/llvm-android-linker-exec.patch`

### Technical
- 版本号：0.13.11 (versionCode: 1312)
- 工具链版本：v0.2.0（从 v0.1.9 升级）
- 删除重复资源文件：80+ 个
- 模块包结构优化：配置、存储、主题管理类迁移到合理位置
- 构建配置更新：30+ 个模块的 build.gradle.kts

---

## [0.12.88] - 2026-02-23

### Changed
- **工具链系统优化**：
  - 更新 tina-toolchain assets：新增 v0.1.9 版本工具链包（base + tools）
  - 更新 `current.properties` 配置文件，指向最新工具链版本
  - 优化 `AndroidNativeToolchainManager` 和 `AndroidSysrootManager` 工具链管理逻辑
- **编译系统改进**：
  - `SingleFileBuildStrategy` 优化单文件编译流程
  - `NativeExecutableRunner` 改进原生程序启动逻辑
- **测试功能增强**：
  - 优化 `AiChatTestScreen`：AI 聊天测试界面改进
  - 优化 `CompilerDiagnosticsTestScreen`：编译器诊断测试界面改进
  - 新增 `AndroidNativeToolchainSmokeTest`：工具链冒烟测试
- **UI 组件优化**：
  - 优化 `CodeBlock` 组件：代码块渲染改进
- **设置界面改进**：
  - 优化 `AiSettingsSection`：AI 设置界面改进
  - 优化 `DeveloperOptionsSection`：开发者选项界面改进
  - 优化 `SettingsItems` 组件：设置项组件改进
- **工作空间功能优化**：
  - 优化 `DependencyInstallViewModel`：依赖安装视图模型改进
  - 优化 `InstallContentComponents`：安装内容组件改进
- **构建系统优化**：
  - 优化 `TinaAndroidLibraryPlugin`：Android 库插件改进
  - 更新 `build.gradle.kts`：构建配置优化
- **国际化完善**：
  - 更新 `strings.xml` 和 `values-en/strings.xml`：补充和优化字符串资源
- **备份规则更新**：
  - 更新 `backup_rules.xml` 和 `data_extraction_rules.xml`：备份规则优化
- **工具链构建脚本优化**：
  - 优化 `build-android-tools.sh`：Android 工具构建脚本改进
  - 更新 `llvm-android-linker-exec.patch`：LLVM Android linker 补丁更新
- **依赖版本更新**：
  - 更新 `gradle/libs.versions.toml`：依赖版本升级
- **版本号更新**：
  - 版本号：0.12.88 (versionCode: 1289)

### Added
- **新增测试模块**：
  - 新增 `core/common/src/test/`：核心通用模块测试
  - 新增 `core/compile/src/test/`：编译模块测试
  - 新增 `core/config/src/test/`：配置模块测试
  - 新增 `core/search/src/test/`：搜索模块测试
  - 新增 `core/security/src/test/`：安全模块测试
- **新增 CI/CD 配置**：
  - 新增 `.github/workflows/pr-check.yml`：PR 检查工作流
- **新增编辑器配置**：
  - 新增 `.editorconfig`：统一编辑器配置
- **新增计划文档目录**：
  - 新增 `plans/` 目录：项目计划文档

### Technical
- 版本号：0.12.88 (versionCode: 1289)
- 工具链版本：v0.1.9
- 新增测试模块：5 个（common、compile、config、search、security）
- 新增 CI/CD 工作流：PR 检查

---

## [0.12.78] - 2026-02-22

### Changed
- **模块化架构重构**：
  - 移除 `UserContentModule`：用户内容管理功能暂时下线，为后续重构做准备
  - 移除用户内容数据库相关代码（`DownloadHistoryDao`、`DownloadHistoryEntity`、`FavoriteDao`、`FavoriteEntity`、`UserContentDatabase`）
  - 新增 `core:database` 模块：统一数据库基础设施管理
  - 新增核心接口抽象模块：`core:common/ai/`、`core:common/editor/`、`core:common/symbol/`、`core:common/terminal/`
  - 编辑器、终端、AI 等功能的核心接口从具体实现模块中抽离到 `core:common`，降低模块间耦合
- **工具链系统重构**：
  - 移除 JNI 工具链服务（`ITinaToolchainService.aidl`、`TinaToolchainService`、`TinaToolchainServiceClient`、`NativeToolchainBundled`、`NativeToolchainWrapper`）
  - 统一使用原生 ELF 可执行文件工具链（clang/clang++/lld）
  - 更新 tina-toolchain assets：移除 v0.1.7 版本文件，更新 `current.properties` 和 sysroot 包
  - 优化 `AndroidNativeToolchainManager` 和 `AndroidSysrootManager`：简化工具链管理逻辑
- **编译系统优化**：
  - `SingleFileBuildStrategy`、`NativeMakeBuildStrategy`、`NativeCMakeBuildExecutor` 适配新的工具链架构
  - `ProgramRunner` 优化原生程序启动流程
  - `NativeCodeFormatter` 改进代码格式化逻辑
- **UI 组件改进**：
  - 新增 `ExternalModificationDialog`：文件外部修改提示对话框
  - 新增 `ScaffoldPaddingSanitizer`：Scaffold padding 统一处理工具
  - 新增 `CodeEditorTextContentProvider`：编辑器文本内容提供器接口
  - 优化 `MarkdownViewer`、`TinaTopBar` 等通用组件
- **测试功能扩展**：
  - 新增 `AiChatTestScreen`：AI 聊天功能测试界面
  - 新增 `CompilerDiagnosticsTestScreen`：编译器诊断测试界面
  - 优化 `ClangdTestScreen` 和 `DevTestRegistry`
- **编辑器功能增强**：
  - 新增编辑器插件系统基础架构（`feature/editor/plugin/`）
  - 新增编辑器搜索功能模块（`feature/editor/search/`）
  - 新增 `BookmarkRepositoryAdapter`：书签仓库适配器
  - 优化 `EditorCacheManager`、`EditorFeatureManager`、`EditorInstanceManager`
- **终端功能增强**：
  - 新增终端 DI 模块（`feature/terminal/di/`）
  - 新增 `TerminalSessionManagerAdapter`、`ShellResolverAdapter`、`TerminalThemeProvider`
  - 优化 `LocaleInstaller`、`ZshInstaller`、`TerminalPreferences`
- **AI 功能改进**：
  - 优化 `AiApiClient`、`ChatModels`、`ConversationRepository`、`AiChatViewModel`
  - 新增 `EntityExtensions`：实体扩展函数
  - 改进 AI 配置管理（`AiConfig`、`AiSettingsSection`）
- **设置界面优化**：
  - 优化 `SettingsScreen`、`SettingsRootSection`
  - 改进 `CompilerSettingsSection`、`TerminalSettingsSection`
  - 新增 `TerminalSettingsHelper`：终端设置辅助工具
- **文档更新**：
  - 新增 `docs/compiler-diagnostics-test-guide.md`：编译器诊断测试指南
  - 新增 `docs/gradle-build-optimization.md`：Gradle 构建优化指南
  - 新增模块化重构文档：`Modularization-Audit-Report.md`、`Modularization-Optimization-Plan.md`、`Modularization-Refactoring-Summary.md`
  - 更新 `docs/clang-android-exec-lessons.md`、`docs/proot-removal-plan.md`、`docs/toolchain-build-guide.md`

### Removed
- 删除用户内容管理相关代码（暂时下线，为后续重构做准备）
- 删除 JNI 工具链服务相关代码（统一使用原生 ELF 工具链）
- 删除备份文件：`UserContentApi.kt.bak`、`UserContentModels.kt.bak`、`UserContentRepository.kt.bak`、`TerminalSettingsSection.kt.bak`
- 删除旧版本文件：`DownloadHistoryScreen.kt.old`、`FavoritesScreen.kt.old`

### Technical
- 版本号：0.12.78 (versionCode: 1279)
- 模块化重构：核心接口抽离到 `core:common`，降低模块间耦合
- 工具链架构：统一使用原生 ELF 可执行文件，移除 JNI 服务层
- 数据库架构：新增 `core:database` 模块，统一数据库基础设施

---

## [0.11.96] - 2026-02-19

### Added
- **用户内容管理系统**：
  - 新增 `UserContentActivity`：用户收藏与下载历史统一入口
  - 新增 `FavoritesScreen` 与 `FavoritesViewModel`：代码片段收藏管理
  - 新增 `DownloadHistoryScreen` 与 `DownloadHistoryViewModel`：下载历史记录查看
  - 新增 `UserContentModule`：Koin DI 模块，统一管理用户内容相关依赖
- **AI 功能基础模块**：
  - 新增 `core:common/ai/` 目录：AI 相关核心数据模型与接口
  - 为后续 AI 聊天、代码补全等功能提供基础架构
- **Linux 环境管理**：
  - 新增 `core:common/linux/` 目录：Linux 环境抽象与管理
  - 新增 `PRootLinuxEnvironment`：PRoot 环境实现
  - 新增 `PluginLinuxEnvironmentProvider`：插件 Linux 环境提供器
  - 支持插件通过统一接口访问 Linux 环境（如 bundled_plugins/tinaide.linux-environment）
- **用户数据管理**：
  - 新增 `core:common/user/` 目录：用户相关数据模型
  - 新增 `core:storage/db/` 目录：Room 数据库基础设施（为后续本地数据持久化做准备）
- **原生可执行文件运行器**：
  - 新增 `NativeExecutableRunner`：统一管理原生 ELF 可执行文件的启动与环境配置
- **UI 组件扩展**：
  - 新增 `DetailScreenComponents`：详情页面通用组件（标题栏、操作按钮、内容区域等）
  - 新增 `TinaListCard`：列表卡片组件
  - 新增 `TinaPullToRefresh`：下拉刷新组件
  - 新增 `TinaSkeletons`：骨架屏加载组件
- **插件能力扩展**：
  - 新增 `PluginCapabilities`：插件能力声明与检测系统

### Changed
- **编辑器会话管理重构**：
  - `ProjectSessionStorage` 迁移到 Room 数据库（`editor/session/db/`）
  - 会话数据持久化从 JSON 文件改为 SQLite 数据库，提升查询与更新性能
- **书签系统重构**：
  - `BookmarkStateStorage` 迁移到 Room 数据库（`editor/bookmark/db/`）
  - 书签数据持久化从 JSON 文件改为 SQLite 数据库
- **终端状态持久化重构**：
  - `TerminalStateStorage` 迁移到 Room 数据库（`terminal/persistence/db/`）
  - 终端会话状态持久化从 JSON 文件改为 SQLite 数据库
- **存储模块依赖更新**：
  - `core:storage` 新增 Room 依赖（`androidx.room:room-runtime`、`room-ktx`、`room-compiler`）
  - `core:common` 新增 Room 依赖，提供数据库基础设施
- **编辑器模块依赖更新**：
  - `feature:editor` 新增 Room 依赖，支持会话与书签数据库迁移
- **终端模块依赖更新**：
  - `feature:terminal` 新增 Room 依赖，支持终端状态数据库迁移
- **项目路径管理增强**：
  - `ProjectPaths` 新增用户内容相关路径管理方法
  - `ProjectLocationManager` 优化路径解析逻辑
- **文档更新**：
  - 更新 `docs/API-Reference.md`：补充新增 API 文档
  - 更新 `docs/README.md`：更新项目文档索引
  - 更新 `docs/LSP-服务接入与Clangd启动（小白向）.md`：补充 LSP 服务接入说明
  - 更新 `docs/clangd-completion-fast-path.md`：优化 clangd 补全快速路径说明
  - 更新 `docs/planning/Next-Steps-2026-02.md`：更新下一步计划
  - 新增 `docs/clang-android-exec-fix.md`：clang Android exec 修复说明
  - 新增 `docs/clang-android-exec-lessons.md`：clang Android exec 经验总结
- **子模块更新**：
  - `external/tina-sora-editor` 更新到最新版本（LSP Semantic Tokens 支持、样式补丁增强）

### Technical
- 版本号：0.11.96 (versionCode: 1197)
- 数据持久化从 JSON 文件全面迁移到 Room 数据库（编辑器会话、书签、终端状态）
- 新增用户内容管理、AI 基础模块、Linux 环境管理等核心架构

---

## [0.11.32] - 2026-02-16

### Changed
- **移除 JNI 共享库编译链路**：
  - 删除 `SingleFileBuildStrategy.buildWithJni()` 及全部 JNI 分步编译/链接逻辑（~640 行），单文件编译统一走 ELF 可执行工具链路径
  - `BuildOptions` 移除 `preferNativeToolchain` 字段
  - `CompileProjectUseCase` 移除 `shouldPreferNativeToolchain()` 方法及相关分支，编译流程不再区分 JNI/PRoot 路径
  - `NativeToolchainBundled.requiredLibraries` 从 5 个共享库精简为仅 `libtinaide_toolchain.so`（不再要求 APK 打包 libLLVM.so / libclang-cpp.so / libtinaide_clang.so / libtinaide_lld.so）
- **`NativeClangdConnectionProvider` 启动策略重构**：
  - 移除 `LD_LIBRARY_PATH` 注入（clangd 不再依赖 APK 内的 libLLVM.so / libclang-cpp.so）
  - 新增 `startAndProbe()` 方法：启动后等待 120ms 探测进程是否存活，避免"进程可启动但瞬间退出"导致误判成功
  - 启动失败时收集 stderr 输出，提供更详细的错误信息
  - 重构为 `LaunchAttempt` 数据类 + 循环尝试模式，替代原有的 try-catch 嵌套
- **tina-toolchain assets 更新**：base/tools 包及 sha256 校验文件同步更新；android-sysroot 包更新
- **构建脚本与文档清理**：
  - 删除 `scripts/build-and-package-android-shared-libs.sh`（共享库打包脚本，已无用）
  - 删除 `tools/toolchain-patches/`（LLVM 补丁目录，已无用）
  - 删除 `docker/proot-build/test-progress.ps1`、`watch-build.ps1`
  - 精简 `scripts/build-and-package-android-toolchain.sh`、`tools/run-toolchain-builder.ps1`、`tools/sync-tina-toolchain-assets.ps1`
  - 更新 `docs/toolchain-build-guide.md`、`docs/proot-removal-plan.md`、`docs/design/Native-Toolchain-Integration-Design.md`

### Technical
- 版本号：0.11.32 (versionCode: 1133)
- 编译系统从 JNI 共享库 + ELF 可执行双路径简化为仅 ELF 可执行路径
- clangd 从依赖 APK 内 LLVM 共享库改为独立 ELF 二进制（自包含所有依赖）

---

## [0.11.31] - 2026-02-15

### Added
- **底部面板大纲视图（Outline）**：
  - 新增 `OutlineContent` 组件：展示当前文件的文档符号结构（类/函数/变量等），支持搜索、展开/折叠、点击跳转
  - 新增 `SymbolKindIcon`：为不同符号类型（类、函数、变量、枚举等）提供统一图标映射
  - 底部面板新增 `OUTLINE` Tab
- **多语言符号提取架构**：
  - 新增 `LanguageSymbolProvider` 抽象接口，支持可插拔的语言符号提取
  - 新增 `CxxSymbolProvider`：C/C++ 符号提取（命名空间、枚举、类、结构体、函数、变量）
  - 新增 `JavaSymbolProvider`：Java 符号提取（类、接口、枚举、方法、字段）
  - 新增 `KotlinSymbolProvider`：Kotlin 符号提取（类、接口、函数、属性）
  - 新增 `PythonSymbolProvider`：Python 符号提取（类、函数、变量）
  - 新增 `RustSymbolProvider`：Rust 符号提取（struct、enum、trait、函数、模块、常量）
  - 新增 `SymbolTypes.kt`：全局符号数据模型（`GlobalSymbol`、`SymbolKind`、`SymbolLocation`）
- **C/C++ 预处理器补全**：
  - 新增 `CxxPreprocessorCompletion`：预处理器指令补全（`#include`、`#define`、`#pragma` 等）
  - 支持本地头文件路径补全，解析 `compile_commands.json` 获取 include 搜索路径
  - LRU 缓存目录列表与 include 搜索根路径
- **LSP 语义高亮（Semantic Tokens）**：
  - 新增 `SemanticTokensStyleProviders`：语言级语义 Token 样式定制（C/C++ 宏/指令按关键字着色）
  - LSP 设置新增"语义高亮"开关（实验性）
- **LocationListDialog 搜索过滤**：LSP 导航结果列表支持搜索过滤与结果计数

### Changed
- **Git 模块重写为纯 JGit 实现**：
  - `GitService` 全部 Git 操作改用 JGit API，移除外部 CLI 依赖
  - `GitSshManager` 使用 Apache MINA SSHD（JGit 内置），不再依赖 PRoot/ssh-agent
  - `GitSshStore` 持久化 SSH 密钥与主机绑定
  - `GitSettingsSection` 重构为 HTTPS/SSH 分 Tab 的凭据管理界面
  - 移除 OpenSSH 安装检测、ssh-agent 相关 UI 与字符串资源
- **补全引擎重构**：
  - `CxxCompletionEngine` 集成 `CxxPreprocessorCompletion`，预处理器指令与 include 路径补全走独立模块
  - `CxxLanguage` 集成 snippet manager 与预处理器上下文检测
- **符号索引架构升级**：
  - `ProjectSymbolIndexService` 适配新的 `LanguageSymbolProvider` 接口
  - `SymbolIndexCache` 更新缓存策略
  - `EditorContainerState` / `EditorFeatureManager` 集成文档符号查询
- **LSP 设置扩展**：
  - `LspAssistSettings` 新增 data class 统一管理 LSP 行为配置（签名帮助、行内提示、语义高亮）
  - `LspSettingsSection` 扩展 Clangd 配置、行为设置、远程 LSP 支持
- **sora-editor fork 增强**：
  - `LspLanguage` / `LspEditor` 支持 Semantic Tokens 全量/增量请求
  - `SparseStylePatches` 增强样式补丁系统（+267 行）
  - 新增 `SemanticTokensEvent` / `SemanticTokensStyleProvider` 事件与接口
  - `RequestManager` 新增 `semanticTokensFull` / `semanticTokensFullDelta` 方法
- **tina-android-tree-sitter**：`TSQueryCursor` 增强
- **JNI 工具链 .so 更新**：`libtinaide_clang.so` / `libtinaide_lld.so`（arm64-v8a + x86_64）

### Removed
- 删除 `CxxSymbolExtractor`、`CxxSymbolIndex`（已被 `LanguageSymbolProvider` 架构替代）
- 删除 `CxxSignatureHelpProvider`、`LocalSignatureHelpWindow`（签名帮助改由 LSP 处理）
- 移除 Git SSH 中 OpenSSH 安装检测、ssh-agent 相关字符串资源与 UI

### Technical
- 版本号：0.11.31 (versionCode: 1132)
- 子模块更新：`external/tina-sora-editor`（Semantic Tokens 支持）、`external/tina-android-tree-sitter`
- 符号提取从 C++ 专用改为多语言可插拔架构（5 种语言）
- Git 模块从 CLI + PRoot 依赖改为纯 JGit 实现

---

## [0.11.24] - 2026-02-13

### Added
- **Clangd LSP 核心导航功能**：
  - Go to Definition、Find References、Go to Type Definition、Go to Implementation
  - Switch Header/Source（clangd 扩展协议 `textDocument/switchSourceHeader`）
  - 新增 `LspNavigationService`（`core/lsp`）统一处理导航请求
  - 新增 `LocationListDialog` 组件：多结果时弹出选择列表，单结果直接跳转，无结果显示提示
  - `NavigationMenuGroup` 替换为实际 LSP 导航菜单项（LSP 连接时显示）
- **LSP Workspace Symbols**：`SymbolsContent` 新增 LSP 搜索模式，LSP 可用时优先使用语义搜索，不可用时回退本地索引
- **LSP Folding Range**（可选开关）：设置 → LSP → LSP 折叠范围，请求 `textDocument/foldingRange` 驱动编辑器折叠
- **Alpine Linux rootfs 支持**：新增 `AlpineRootfsManager`、`AlpineMirrorManager`，替代 Ubuntu rootfs

### Changed
- **架构审查迭代一（P0）**：
  - `MainActivity` 拆分：1380→1140 行（-17%），提取 `GitDialogState`、`EditorActionsState`、`MainActivityTopBar`，净减少 17 个状态变量
  - `DebugSessionStore` 从 `object` 迁移为 `class` + Koin `single{}`，`DebugViewModel` 构造函数注入
  - `ProfileViewModel` DI 修复：不再绕过 Koin 调用 `getInstance()`，改为构造函数注入接口
- **架构审查迭代二（P1 + P2）**：
  - `PackageDetailScreen` 链接点击失败增加 Toast 提示（原先静默吞异常）
  - `feature:settings` 依赖收敛：feature 依赖从 3 个降到 1 个
    - AI 配置类（`AiAccessMode`/`AiConfig`/`AiProvider`）迁移到 `core:config/ai/`，新增 `AiConfigProvider` 接口
    - 新增 `EditorThemeIndex` 接口在 `core:plugin`，settings 不再依赖 `feature:editor`
  - `CompileProjectUseCase` 移除 3 个未使用构造参数（8→5）
- **PRoot Alpine 迁移**：`PRootBootstrap` 从 1253 行精简到 346 行，删除全部 Ubuntu/apt 相关代码（`AptBootstrapper`、`AptCommand`、`AptConfigWriter`、`AptOutputParser`、`UbuntuRootfsManager`、`UbuntuMirrorManager`、`UbuntuToolchainInstaller`、`UbuntuDebuggerInstaller`、`AptInstallSupport`）
- **`PRootClangdConnectionProvider`** / **`CompileDatabaseProvider`**：适配 Alpine 路径
- **`DependencyInstallViewModel`**：适配新的包管理后端

### Removed
- 删除 Ubuntu rootfs 静态资源（`rootfs.tar_gz` arm64 + x86_64）
- 删除 `PRootBootstrapProgressParser`、`AlpineMirrorManager`（feature:terminal 侧，已迁移到 core:proot）
- 删除 `feature:ai` 中的 `AiAccessMode.kt`、`AiConfig.kt`、`AiProvider.kt`（已迁移到 `core:config/ai/`）

### Technical
- 版本号：0.11.24 (versionCode: 1125)
- sora-editor fork：`LanguageServerWrapper.getServer()` 增强 KDoc，用于 clangd 扩展请求
- `feature:settings` 新增 `koin-compose` 依赖（用于 `koinInject<AiConfigProvider>()`）
- `feature:ai` 新增 `core:config` 依赖

---

## [0.11.22] - 2026-02-12

### Added
- **终端双后端支持（HOST + PRoot 共存）**：
  - 终端会话新增 `backend` 属性（per-session），支持 HOST（Android 原生）和 PRoot（Linux 环境）两种后端共存
  - 默认创建 HOST 会话；用户可通过长按标签栏 "+" 按钮选择 PRoot 后端
  - 标签栏每个标签显示后端标识徽章：绿色 "H"（HOST）/ 橙色 "P"（PRoot）
  - 新建标签后端选择弹窗（`NewTabBackendDialog`）：展示 HOST 和 PRoot 选项及说明
  - 标签列表弹窗（`TerminalTabListDialog`）显示后端类型和会话状态
  - 会话持久化支持 backend 字段，关闭再打开终端可正确恢复会话后端类型
  - 编译运行始终使用 HOST 终端，避免原生 ELF 被注入 PRoot 环境产生 linker warning
- **i18n 新增终端后端相关字符串**：`terminal_select_backend_title`、`terminal_backend_host_label`、`terminal_backend_host_desc`、`terminal_backend_proot_label`、`terminal_backend_proot_desc`（中英文）

### Changed
- **`TerminalSessionState`**：新增 `backend: TerminalBackend = TerminalBackend.HOST` 字段，`create()` 工厂方法支持指定后端
- **`TerminalSessionSnapshot`**：新增 `backend: String = "host"` 字段（字符串序列化，向后兼容旧 JSON）
- **`TerminalShellResolver`**：
  - 新增 `resolveForSession(backend, workDir, rows, cols)` 重载，接受显式后端参数
  - 原有无 backend 参数的重载改为委托调用，保持向后兼容
  - 修复 `resolveBackend()` AUTO 模式：不再因 PRoot 已安装就默认使用 PRoot，改为始终返回 HOST
- **`TerminalSessionManager`**：`createSession()` / `startTerminalSession()` / `createSnapshot()` / `restoreSession()` 全链路贯穿 backend 参数
- **`MultiTerminalViewModel`**：`createSession()` 透传 backend 参数
- **`TerminalActivity`**：新增 `EXTRA_BACKEND` Intent extra，支持外部指定终端后端
- **`TerminalTabBar`**：新增 `onNewTabLongClick` 回调，"+" 按钮支持长按选择后端
- **`CompileActionsHelper`**：`UiEvent.OpenTerminal` 携带 `backend = TerminalBackend.HOST`
- **`MainActivity`**：`OpenTerminal` 事件处理传递 `EXTRA_BACKEND` 到 TerminalActivity

### Fixed
- 修复编译运行的原生 ELF 二进制被注入 PRoot 终端执行，导致 `WARNING: linker: Warning: failed to find generated linker configuration from "/linkerconfig/ld.config.txt"` 的问题

### Technical
- 版本号：0.11.22 (versionCode: 1123)
- 终端后端从全局设置改为 per-session 属性，HOST 和 PRoot 会话可同时存在

---

## [0.11.12] - 2026-02-11

### Added
- **`AndroidSystemLinker` 工具类**：统一 Android 系统动态链接器路径解析，支持 APEX 路径探测（`/apex/com.android.runtime/bin/linker64`），替代各模块中分散的 `resolveLinkerPath()` 实现
- **`verifyTinaToolchainAssets` Gradle 任务**：构建时校验 tina-toolchain assets 完整性（current.properties 引用的 base/tools/sha256 文件是否存在、版本号是否一致）
- **`sync-tina-toolchain-assets.ps1` 脚本**：新增 PowerShell 工具脚本，用于同步 tina-toolchain 构建产物到 app assets 目录
- **Clangd 初始化流程文档**：新增 `docs/clangd-initialization.md`，描述 TinaIDE 中 Clangd LSP 服务器的完整初始化链路
- **`BuildOptions.buildForRun` 选项**：区分「构建」与「运行」场景，Android 15+ 下运行场景可选择更兼容的链接方式

### Changed
- **tina-toolchain 升级至 v0.1.6**：base/tools 包及 sha256 校验文件同步更新
- **移除静态链接支持**：`LinkType.STATIC` 已删除，默认改为 `DYNAMIC`；Android 15+ 禁止直接 exec() 应用私有目录二进制文件，linker64 无法稳定启动 static-pie（TLS 初始化崩溃），因此统一使用动态链接
- **`SingleFileBuildStrategy` 重构**：
  - JNI 构建路径自动检测并安装 tina-toolchain（无需手动解压）
  - 移除 `linkType` 参数，统一动态链接 + PIE
  - 新增 toolchain service 崩溃自动重试（`DeadObjectException`/`RemoteException` 最多重试 2 次）
  - 链接参数重构：动态链接 CRT 启动文件（`crt1.o` → `Scrt1.o`）、`-pie` 标志、`-lc++_shared`
- **`ProgramRunner` 重构**：
  - 统一通过系统 linker64 启动原生程序（不再先尝试直接 exec 再 fallback）
  - 新增 `stageExecutableForNativeRun()`：将可执行文件复制到 app 私有目录再启动，规避外部存储 exec 限制
  - `buildNativeEnv()` 修复 `LD_LIBRARY_PATH`：仅包含真实运行时库（libc++_shared.so），排除 NDK stub 库（避免 stub libc.so 覆盖系统真实 libc.so 导致 SIGSEGV）
- **`AndroidSysrootManager.getCompilerFlags()`**：移除 `linkType` 参数和 `-static` 标志
- **新建项目向导**：移除链接方式选择 UI（`NewProjectWizardScreen` 中的 `LinkType` RadioButton 组）
- **i18n 资源清理**：移除 `ndk_link_type_label`、`ndk_link_static`、`ndk_link_static_desc` 字符串（中/英）

### Technical
- 版本号：0.11.12 (versionCode: 1113)
- tina-toolchain: v0.1.6
- 链接方式：仅动态链接（DYNAMIC），旧配置 STATIC 自动迁移为 DYNAMIC

---

## [0.11.8] - 2026-02-09

### Added
- **AI 聊天功能增强**：
  - 新增 AI Provider 配置（通义千问、智谱 AI、Ollama 本地、自定义）
  - 新增对话历史持久化与多会话管理
  - 新增工具调用（Tool Call）显示与执行支持
  - 新增思考过程（Reasoning）折叠显示
  - 新增图片消息发送与多模态支持
  - 新增上下文感知（当前文件、选中代码、错误信息）
- **Diff 查看器增强**：DiffEngine 支持更多差异算法与 UI 改进
- **全局搜索增强**：批量替换支持（成功/失败/部分替换状态反馈）
- **Market 市场功能**：新增分类筛选、下载计数、评分显示、代码片段复制计数
- **教程系统 UI 改进**：SpotlightOverlay 交互优化

### Changed
- **模块化架构**：
  - `language-cmake` → `core:cmake`：CMake 解析器迁移为标准 core 模块，使用 convention plugin（compileSdk 34→36, minSdk 21→28）
  - Koin DI 迁移完成：ServiceLocator 已删除（277 行），全部切换到 Koin 依赖注入
  - ProGuard 多模块拆分完成：18 个 consumer-rules.pro 文件
- **strings.xml 重复资源修复**：
  - 15 个重复字符串资源去重（Alpine/Ubuntu 镜像键冲突、plugin_log 格式占位符 bug）
  - Alpine 镜像键重命名为 `alpine_mirror_xxx`，与 Ubuntu `mirror_xxx` 分离
- **工具链构建系统**：build-sysroot.sh、打包脚本、APK 分析工具更新
- **CMake 解析器改进**：Lexer/Parser/Analyzer 增强

### Fixed
- **`plugin_log_level`/`plugin_log_message` UI 显示 bug**：移除带 `%1$s` 占位符的重复定义，修复 PluginLogScreen 显示原始占位符文本的问题
- **`AiChatViewModel.sendMessage` 参数遮蔽 bug**：`context: MessageContext?` 参数遮蔽了类属性 `context: Context`，导致 `getString` 调用失败
- **`feature:ai` 缺少 `core:i18n` 依赖**：添加显式依赖声明

### Technical
- 版本号：0.11.8 (versionCode: 1109)
- 模块总数：37（22 core + 15 feature），app 模块 150 个 Kotlin 文件
- DI 框架：Koin BOM 4.1.1，ServiceLocator 已完全移除

---

## [0.11.7] - 2026-02-07

### Fixed
- **JNI 原生工具链单文件编译失败修复**：
  - 修复 `clang++: error: invalid linker name in argument '-fuse-ld=lld'` 错误
  - 根因：`clang_main()` 在一步编译+链接时，内部通过 `fork+exec` 调用 `ld.lld` 可执行文件，但 Android `:toolchain` 进程的 PATH 中没有该可执行文件
  - 修复方案：将一步编译+链接改为分步执行：
    1. `clang_main()` 只编译（`-c`），生成 `.o` 文件（不涉及链接器）
    2. `lld_main()` 直接链接 `.o` 文件，生成最终可执行文件
  - 两步都走 JNI 共享库调用，完全不需要任何可执行文件

### Changed
- **`SingleFileBuildStrategy.kt` 重构**：
  - `buildWithJni()` 从一步调用改为分步：先 `invokeClangMain()` 编译，再 `invokeLldMain()` 链接
  - 新增 `invokeClangMain()` / `invokeLldMain()` / `invokeToolchainService()` 方法
  - 新增 `resolveCompilerRtBuiltins()` 方法：查找 compiler-rt builtins 静态库
  - 链接参数手动构造：CRT 启动文件、sysroot 库路径、标准库、PIE 标志、TLS 对齐等

### Technical
- 版本号：0.11.7 (versionCode: 1108)

---

## [0.10.91] - 2026-02-05

### Added
- **自定义工具链系统（Custom Toolchain）**：
  - 新增 `AndroidNativeToolchainManager.kt`：Android 原生工具链管理器，支持工具链安装、版本检测、路径管理
  - 新增 `AndroidElfExecutor.kt`：Android ELF 可执行文件运行器，支持直接执行和 linker 回退模式
  - 新增 Docker 构建系统 `docker/toolchain-builder/Dockerfile`：用于构建 Android 原生 clang/clangd/lld/llvm 工具链
  - 新增构建脚本：
    - `scripts/build-and-package-android-toolchain.sh`：工具链打包脚本
    - `scripts/build-android-tools.sh`：Android 工具构建脚本
    - `tools/run-toolchain-builder.ps1`：Windows 下运行工具链构建器的 PowerShell 脚本
- **自定义工具链文档**：
  - 新增 `docs/design/Custom-Toolchain-Build-Guide.md`：工具链构建指南
  - 新增 `docs/planning/Custom-Toolchain-Roadmap.md`：工具链开发路线图
  - 新增 `docs/planning/Custom-Toolchain-Workspace-Inventory.md`：工具链工作空间清单
- **工具链补丁**：新增 `tools/toolchain-patches/` 目录，包含工具链构建所需的补丁文件
- **Android 测试模块**：新增 `app/src/androidTest/` 目录

### Changed
- **编译系统增强**：
  - `BuildStrategy.kt`：构建策略接口优化
  - `CompileProjectUseCase.kt`：编译用例增强，支持自定义工具链
  - `SingleFileBuildStrategy.kt`：单文件构建策略优化
- **PRoot 构建脚本**：更新 `docker/proot-build/build-sysroot.sh`

### Technical
- 版本号：0.10.91 (versionCode: 1092)
- 自定义工具链里程碑完成：M1（可编译/可链接）、M2（cmake/ninja/make 可用）、M3（分层交付）

---

## [0.10.88] - 2026-02-03

### Added
- **AI 聊天功能基础模块**：
  - 新增 `ai/` 模块，包含 API 客户端、配置、仓库和 ViewModel
  - 新增 `DrawerAiPanel.kt`：侧滑栏 AI 聊天面板
  - 新增 `ChatMessageBubble.kt`：聊天消息气泡组件
  - 新增 `CodeBlock.kt`：代码块渲染组件
  - 新增 `AiSettingsSection.kt`：AI 设置界面
  - 新增 AI Tab 图标资源 `ic_tab_ai.xml`
- **代码片段系统**：
  - 新增 `editor/snippet/SnippetCompletionItems.kt`：代码片段补全项
  - 新增 `editor/completion/CompletionItemComparators.kt`：补全项排序比较器
  - 新增设计文档 `docs/design/Snippet-Support-Design.md`
- **CMake 构建增强**：
  - 新增 CMake 工具栏图标：`ic_cmake_clean.xml`、`ic_cmake_rebuild.xml`、`ic_cmake_reload.xml`、`ic_cmake_target.xml`
  - `CompileProjectUseCase` 支持 CMake 目标选择、清理、重新构建
  - `DrawerContent` 新增 CMake 子菜单（选择目标、重新加载、清理、重新构建）
- **编辑器动作系统**：新增 `editor/action/` 模块，支持编辑器动作扩展
- **服务端 AI Gateway 模块**（tina-server）：
  - 新增 `ai_gateway/` handlers 模块，支持 Chat、Embeddings、Images、Rerank 等 API
  - 新增 AI 相关数据模型：`ai_channel.rs`、`ai_consume_log.rs`、`ai_model_pricing.rs`、`ai_redeem_code.rs`、`ai_token.rs`、`ai_user_quota.rs`
  - 新增 AI 相关仓库层
  - 新增 `ai_auth.rs`、`ai_gateway_auth.rs` 中间件
- **管理后台 AI 管理**（tina-admin）：
  - 新增 AI 管理视图 `views/ai/`
  - 新增定价管理视图 `views/pricing/`
  - 新增 AI API 接口 `api/ai.ts` 和类型定义 `types/ai.ts`
- **文档**：
  - 新增 `docs/ai-gateway-integration-plan.md`：AI Gateway 集成计划
  - 新增 `docs/design/TinaIDE-Package-Builder.md`：包构建器设计文档
  - 新增 `docs/newapi-rust-rewrite-analysis.md`：NewAPI Rust 重写分析
- **Docker 包构建器**：新增 `docker/tinaide-pkg/` 目录

### Changed
- **侧滑栏重构**：
  - `DrawerContent` 新增 AI 面板 Tab 和 CMake 子菜单支持
  - `SwipeableDrawer` 优化手势处理
- **终端设置增强**：
  - `TerminalPreferences` 新增更多配置项
  - `TerminalSettingsSection` 界面优化
  - `TerminalShellResolver` 增强 Shell 解析逻辑
- **编辑器功能增强**：
  - `EditorHeaderNavigationFeature` 改进头文件导航
  - `HeaderNavigationTextAction` 优化导航动作
  - `EditorContainerState` 增强状态管理
- **编译系统改进**：
  - `CompileProjectUseCase` 重构，支持 CMake 多目标构建
  - `CompilerViewModel` 新增编译状态管理
- **CMake/Make 语言支持**：
  - `CMakeAutoComplete` 和 `CMakeLanguage` 优化
  - `MakeAutoComplete` 和 `MakeLanguage` 优化
- **UI 组件优化**：
  - `TinaBottomBar` 优化样式
  - `GlobalSearchScreen` 改进搜索体验
  - `ProfileEditSection` 简化代码
- **服务端改进**（tina-server）：
  - `config/mod.rs` 配置模块重构
  - `errors/mod.rs` 错误处理增强
  - `crypto.rs` 加密工具增强
  - `runtime_settings.rs` 运行时设置模块新增
  - `settings.rs` 设置模块新增
  - 部署脚本和 Docker 配置优化

### Removed
- 删除旧的数据库补丁脚本（已合并到 `recreate_schema.sql`）：
  - `schema_patches/20260131_add_code_snippets.sql`
  - `schema_patches/20260131_add_code_snippets_no_extensions.sql`
  - `schema_patches/20260201_add_activation_code_user_bindings.sql`
  - `schema_patches/20260201_backfill_activation_code_user_bindings.sql`
  - `schema_patches/20260201_cleanup_admin_assigned_placeholder_devices.sql`
- 删除旧的加密密钥文件 `secrets/activation_code_enc_keys.b64.enc`

### Technical
- 版本号：0.10.88 (versionCode: 1089)
- 子模块更新：`external/tina-sora-editor`
- 新增模块：AI 聊天（客户端）、AI Gateway（服务端）、代码片段系统
- 国际化：新增约 100+ 条字符串资源（中英文）

---

## [0.10.78] - 2026-02-02

### Added
- **触摸事件拦截保护**：新增 `EditorTouchInterceptGuard.kt`，解决 Compose + AndroidView 混合架构下编辑器滑动被父容器打断导致卡顿的问题。
- **存储权限对话框**：新增 `StoragePermissionDialog.kt` 组件，用于引导用户授予存储权限。
- **首次启动权限申请**：`ToolchainConfigActivity` 首次启动时检查并申请存储权限。

### Changed
- **编辑器保存并发安全增强**：
  - `DocumentSession.save()` 使用 sora-editor `Content.documentVersion`（AtomicLong）解决"保存过程中继续编辑导致 dirty 误判"的竞态问题。
  - `CodeEditorBinding.currentDocumentVersion()` 改用 `Content.documentVersion`，保证跨线程读取安全。
  - `cleanText`、`cleanTextLength`、`cleanVersion` 字段添加 `@Volatile` 注解。
  - 文件写入使用 `Files.move(..., ATOMIC_MOVE)` 替代 copy + delete，提升原子性。
- **触摸诊断模块重构**：`EditorTouchDebug` 从触摸事件处理器重构为纯诊断日志记录器，拦截保护逻辑移至 `EditorTouchInterceptGuard`。
- **头像上传容错**：`ProfileEditSection` 在系统裁剪不可用时直接使用原图上传，避免崩溃。
- **登出时清除许可证**：`AuthRepositoryImpl` 登出时调用 `LicensePreferences.clearLicense(keepTrial = true)`，清除当前账号的许可证缓存。

### Fixed
- **编辑器滑动卡顿**：通过 `EditorTouchInterceptGuard` 始终调用 `requestDisallowInterceptTouchEvent(true)`，防止 Compose 父容器打断编辑器的触摸序列。
- **保存中继续编辑 dirty 误判**：基于版本号快照判断保存完成后是否有新修改，修复保存后立即显示"已保存"但实际内容已变化的问题。
- **后端激活码归属校验**：修复 `SELECT 1` 在 Postgres 返回 INT4 导致 sqlx 解码为 i64 时类型不匹配的问题，改用 `SELECT EXISTS(...)`。
- **许可证验证账号校验**：`/license/validate` 新增 token 用户与设备绑定账号一致性校验，避免"先未登录激活/换号登录"导致旧 token 仍被视为有效会员。

### Technical
- 版本号：0.10.78 (versionCode: 1079)
- 新增文件：`EditorTouchInterceptGuard.kt`、`StoragePermissionDialog.kt`
- 修改文件：`DocumentSession.kt`、`CodeEditorBinding.kt`、`EditorTouchDebug.kt`、`ProfileEditSection.kt`、`AuthRepositoryImpl.kt`、`ToolchainConfigActivity.kt`、`CodeEditorPage.kt`、`JsonViewerScreen.kt`、`EditorScrollTestScreen.kt`

---

## [0.10.76] - 2026-02-01

### Added
- **会员徽章组件**：新增 `MembershipBadge.kt` 组件，用于在用户界面显示会员等级标签。
- **教程系统基础模块**：新增 `tutorial/` 目录，为后续教程功能做准备。
- **C++ 滚动压力测试**：新增 `CppScrollStressTestScreen.kt`，用于测试编辑器大文件滚动性能。
- **社交登录图标资源**：新增 `ic_gitee.xml`、`ic_github.xml`、`ic_google.xml` 图标资源。
- **后端许可证系统文档**：新增 `docs/backend-license-system.md`，详细记录多设备管理、激活码系统、会员体系的完整架构设计。
- **激活码用户绑定表**：新增 `activation_code_user_bindings` 表，实现会员与账号的独立绑定关系（与设备绑定分离）。
- **数据库补丁脚本**：新增 `20260201_add_activation_code_user_bindings.sql`、`20260201_backfill_activation_code_user_bindings.sql`、`20260201_cleanup_admin_assigned_placeholder_devices.sql`。

### Changed
- **ProfileScreen 重构**：整合会员信息、账号绑定、激活码兑换功能到"我的"页面，删除 `AccountSettingsSection.kt`，简化设置页面结构。
- **编辑器大文件保存优化**：`DocumentSession.save()` 方法将 `readText()` 移到 IO 线程执行，修复 5 万行以上大文件保存时主线程阻塞导致 UI 无响应的问题。
- **PRoot apt 退出码处理**：`AptInstallSupport.kt` 增加输出内容检测，不再仅依赖退出码判断安装计划是否有效，修复 PRoot 环境下 apt-get -s 返回非 0 退出码但输出正常的问题。
- **微信登录功能禁用**：由于微信开放平台审核未通过，暂时禁用微信登录入口、微信账号绑定、微信登录方式显示（保留后端逻辑以便后续恢复）。
- **后端激活服务增强**：`ActivationService` 新增账号维度的会员归属管理，支持"先激活后登录"场景的数据迁移。
- **后端用户服务增强**：`UserService::get_user_response()` 从 `activation_code_user_bindings` 查询最优会员信息。
- **管理后台用户详情**：新增设备列表查看和强制解绑功能。

### Fixed
- **编辑器保存阻塞**：修复大文件（5 万行以上）保存时 UI 卡死的问题。
- **PRoot 工具链安装**：修复 apt-get 模拟安装返回非 0 退出码导致安装失败的问题。
- **会员状态显示**：修复 `/auth/me` 返回的 `membership` 可能为空导致 App 显示"普通用户"的问题。

### Technical
- 版本号：0.10.76 (versionCode: 1077)
- 子模块更新：`external/tina-sora-editor`

---

## [0.10.75] - 2026-01-31

### Added
- 高级搜索替换：支持全词匹配、范围过滤、搜索历史、批量替换预览与执行。
- 代码片段市场（客户端基础模块）：新增 `snippet` 相关 API / Model / Repository 与配套多语言文案。
- Diff 查看能力：新增 `diff` 模块与差异查看界面/状态管理（用于文件差异浏览）。

### Changed
- 市场模块：新增“我的发布”入口与页面。
- 主导航体验：
  - 底部导航栏取消点击阴影/水波纹，避免与图标着色叠加导致观感变差。
  - 切换底部 Tab 时保留页面 `rememberSaveable` 状态，减少返回项目页时的重复初始化/刷新。
- 构建与工具链：若干 NDK/Proot/工具链安装与解压流程调整，提升稳定性与错误处理一致性。

### Fixed
- 项目页重复刷新：切换回项目视图不再重复触发首次加载刷新。
- Kotlin 编译稳定性：降低 Kotlin compile daemon 异常导致的构建失败概率（默认 in-process 编译策略）。

### Technical
- 版本号：0.10.75 (versionCode: 1076)

---

## [0.10.61] - 2026-01-30

### Added
- **多语言项目标签支持**：项目列表卡片现在可以正确显示项目的主要编程语言标签
  - 新增 `ProjectLanguage` 枚举：支持 C、C++、Java、Kotlin、Python、Rust、Go、JavaScript、TypeScript、Shell 等语言
  - 新增 `LanguageDetector` 语言检测器：通过扫描项目源文件自动识别主要语言，结果缓存到元数据中
  - 扩展 `ProjectTag` 枚举：添加 Java、Kotlin、Python、Rust、Go、JavaScript、TypeScript、Shell 标签
  - 为每种语言标签配置独特的颜色方案（背景色 + 文字色）

### Changed
- **项目标签显示逻辑优化**：
  - 移除无意义的"本地"标签，改为显示实际的语言类型
  - 构建系统标签（CMake/Makefile）与语言标签分离显示
  - 单文件项目和未知构建系统的项目现在显示检测到的语言标签（而非固定的 C/C++）
- **项目元数据扩展**：`ProjectMetadata` 新增 `primaryLanguage` 字段，用于持久化存储检测到的语言类型
- **项目模板安装器更新**：`ProjectTemplateInstaller` 创建项目时自动写入 `primaryLanguage`

### Technical
- 版本号：0.10.61 (versionCode: 1062)
- 新增文件：`ProjectLanguage.kt`、`LanguageDetector.kt`
- 修改文件：`ProjectListModels.kt`、`ProjectListCard.kt`、`ProjectListComponents.kt`、`ProjectDialogs.kt`、`ProjectManagerViewModel.kt`、`ProjectMetadata.kt`、`ProjectTemplateInstaller.kt`、`strings.xml`（中英文）

---

## [0.10.30] - 2026-01-29

### Fixed
- **登录状态保持修复**：修复 App 启动时错误清除登录状态的问题。现在只有在 Refresh Token 过期时才会退出登录，实现真正的 90 天登录保持。
  - 修改 `TinaApplication.cleanupExpiredAuthOnStartup()`：区分 Access Token 过期和 Refresh Token 过期
  - Access Token 过期时保持登录状态，等待下次 API 调用时自动刷新
  - 只有 Refresh Token 也过期时才清除登录状态

### Changed
- **认证模块重构**：大幅优化认证相关代码质量，遵循 SOLID、DRY、KISS 原则
  - **新增 `OAuthLoginHandler`**：提取 QQ 和微信登录的重复代码（~100 行），统一处理 OAuth 登录同步逻辑
  - **修复 `ProjectSymbolIndexService` 资源泄漏风险**：添加 TSParser 超时机制（5 秒），防止恶意 C++ 代码导致解析挂起
  - **增强 `CrashLogUploader` 错误日志**：添加详细的日志记录（锁获取失败、文件状态、上传结果、429 限流、5xx 错误等）
  - **优化 `AuthRepositoryImpl` 内存使用**：将 `authStateFlow` 的 `SharingStarted` 策略从 `Eagerly` 改为 `Lazily`，减少不必要的资源占用
  - **改进 `FeedbackRepository` 错误处理**：用密封类 `FeedbackResult` 替换泛型异常，提供类型安全的错误处理
  - **提取魔法数字为常量**：为 `ProjectSymbolIndexService`、`CrashLogUploader`、`LicenseRepository` 添加带注释的常量定义
  - **标准化日志级别**：统一 ERROR/WARN/INFO/DEBUG 的使用规范

### Technical
- 版本号：0.10.30 (versionCode: 1031)
- 新增文件：`OAuthLoginHandler.kt`
- 修改文件：`AuthRepositoryImpl.kt`、`TinaApplication.kt`、`ProjectSymbolIndexService.kt`、`CrashLogUploader.kt`、`FeedbackRepository.kt`、`FeedbackViewModel.kt`、`AuthRepositoryImpl.kt`
- 代码质量提升：删除重复代码 ~100 行，新增常量 6 个，修复 P0 问题 5 个

---

## [0.10.27] - 2026-01-29

### Added
- **Makefile 语言支持增强**：新增 `MakeLanguage`、`MakeAutoComplete`、`MakeDatabaseParser`、`MakeTreeSitterLanguageProvider`，提供 Makefile 的语法高亮、自动补全和解析功能。
- **设计文档归档系统**：新增 `docs/design/archived/` 目录，用于归档已完成或过时的设计文档。

### Changed
- **编辑器 LSP 功能优化**：`EditorLanguageLspFeature.kt` 改进 LSP 语言特性集成。
- **文档整理**：将 15 个已完成的设计文档移至 `archived/` 目录，保持主文档目录清晰。
- **设计文档索引更新**：更新 `docs/design/README.md`，反映文档归档变化。

### Removed
- **过时设计文档清理**：移除主目录中的已完成设计文档（代码折叠、邮箱验证、图标迁移、登录配置、MessagePack、设置重构、zsh 支持等）。

### Technical
- 版本号：0.10.27 (versionCode: 1028)
- 文档归档：15 个设计文档移至 archived 目录

---

## [0.10.26] - 2026-01-28

### Added
- **License 许可证管理系统**：新增完整的许可证管理功能，支持许可证激活、验证、查询和撤销。
- **服务端 License API**：新增 `/api/v1/license` 端点，提供许可证的完整生命周期管理。
- **License 数据模型**：新增 `License`、`LicenseActivationRequest`、`LicenseActivationResponse` 等数据模型。
- **License 数据库表**：新增 `licenses` 表，支持许可证信息的持久化存储。

### Changed
- **用户资料编辑优化**：`ProfileEditSection` 界面改进，支持头像上传和用户信息编辑。
- **服务端架构增强**：License 模块集成到主服务端，统一管理用户许可证。

### Technical
- 版本号：0.10.26 (versionCode: 1027)
- 服务端新增 License 管理模块

---

## [0.10.24] - 2026-01-28

### Added
- **Make 构建系统支持**：新增 `MakeBuildStrategy` 构建策略，支持基于 Makefile 的项目编译；新增 `make_executable` 项目模板。
- **头像功能**：新增 `AvatarHelper` 头像上传与裁剪功能；服务端新增 `avatar.rs` 头像处理模块。
- **RootfsTargetDetector 工具**：新增 `RootfsTargetDetector`，用于检测 rootfs 目标架构。
- **编辑器工具栏增强**：工具栏新增快捷操作按钮。
- **对话框组件扩展**：`TinaDialogs` 新增更多通用对话框类型。

### Changed
- **用户资料编辑优化**：`ProfileEditSection` 和 `AccountSettingsSection` 大幅改进用户资料编辑界面交互与布局。
- **诊断内容显示改进**：`DiagnosticsContent` 优化诊断信息展示方式。
- **公告管理页面改进**：`announcements/index.vue` 优化公告管理交互。
- **构建系统检测增强**：`BuildSystemDetector` 新增 Make 项目类型检测；`CompileCommandsGenerator` 增强编译命令生成。
- **服务端增强**：`TinaServerApi` 新增头像相关 API；`AuthRepositoryImpl` 增强认证逻辑；用户仓库新增查询能力。
- **新建项目向导增强**：`NewProjectWizardScreen` 支持更多项目类型选择。
- **国际化资源更新**：`strings.xml` 和 `values-en/strings.xml` 新增多条字符串资源。

### Technical
- 版本号：0.10.24 (versionCode: 1025)
- 子模块更新：`external/tina-sora-editor`（CodeEditor 和 FoldingManager 稳定性改进）

---

## [0.9.89] - 2026-01-27

### Added
- **QQ 登录域名配置系统**：新增 QQ 分享域名配置功能，支持从服务端动态下发域名配置（`QQAuthProvider.kt`）。
- **权限管理文档**：新增权限管理设计文档（`docs/design/Permission-Management.md`）。
- **QQ 登录故障排查文档**：新增完整的 QQ 登录问题排查指南（`docs/troubleshooting/QQ-Login-FAQ.md`、`QQ-Login-Fix-Summary.md`、`QQ-Login-Unauthorized-Fix.md`、`Social-Login-Complete-Guide.md`）。
- **服务器诊断工具**：新增本地和 Nginx 诊断脚本（`server/ops/diagnose-local.ps1`、`diagnose-nginx.sh`）。
- **QQ 配置检查工具**：新增 QQ 配置检查和 MD5 签名获取工具（`tools/check-qq-config.ps1`、`get-md5-signature.ps1`）。

### Changed
- **登录流程优化**：登录页面根据服务端配置动态调整第三方登录按钮显示（`LoginActivity.kt`、`LoginViewModel.kt`）。
- **QQ 授权流程增强**：改进 QQ 授权提供器，支持动态域名配置和更完善的错误处理（`QQAuthProvider.kt`）。
- **管理后台优化**：
  - 新增首页仪表板（`server/tina-admin/src/views/home/`）
  - 优化日志详情页面显示（`server/tina-admin/src/views/logs/detail.vue`）
  - 改进状态监控页面（`server/tina-admin/src/views/status/index.vue`）
  - 更新侧边栏导航（`server/tina-admin/src/components/Layout/AppSidebar.vue`）
- **Nginx 配置整理**：删除旧的 Nginx 配置文件，统一使用 `tinaide-final.conf`。
- **插件日志界面优化**：改进插件日志查看界面的交互体验（`PluginLogScreen.kt`）。
- **删除 tina-publisher**：移除未使用的插件发布者前端项目。

### Fixed
- **QQ 登录 401 错误**：修复 QQ 登录时因域名配置不匹配导致的 401 Unauthorized 错误。
- **AndroidManifest 配置**：修复 QQ 登录相关的 Activity 配置问题。

### Technical
- 版本号：0.9.89 (versionCode: 990)
- 子模块更新：`external/tina-android-tree-sitter`、`external/tina-sora-editor`

---

## [0.9.79] - 2026-01-27

### Added
- **设置项增强**：新增 LSP 设置区块与插件日志入口（`LspSettingsSection.kt`、`PluginLogManager.kt`、`PluginLogScreen.kt`）。
- **对话框/输入组件**：新增并统一一批可复用的对话框与输入组件实现（`TinaDialogs.kt`、`TinaTextFields.kt`）。
- **设置帮助资源**：新增一组设置帮助图标资源并补全相关文案（`app/src/main/res/drawable/ic_help_*.xml`、`app/src/main/res/values*/strings.xml`）。

### Changed
- **设置页面重构**：设置页面结构与区块实现大幅梳理，减少耦合与重复（如 `EditorSettingsSection.kt` 大幅瘦身，多个 Section 拆分/对齐）。
- **登录/认证流程**：登录页根据服务端配置动态调整（邮箱验证码/第三方登录开关等），并完善相关 API/模型同步（`LoginViewModel.kt`、`LoginActivity.kt`、`TinaServerApi.kt`）。
- **文档补充**：新增/整理多份设计文档（邮箱验证、设置重构、对话框系统等，位于 `docs/`、`docs/design/`）。

### Fixed
- **代码折叠交互**：折叠为 `{...}` 后，点击/拖动到 `...}` 右侧可将光标稳定放到 `}` 后（不再误展开）；删除/输入会自动定位到真实闭合处并正确更新折叠状态。
- **彩虹括号稳定性**：修复在快速编辑/Undo/Redo 等瞬态文本状态下可能触发的越界崩溃，避免括号着色丢失。

### Technical
- 版本号：0.9.79 (versionCode: 980)

## [0.9.57] - 2026-01-26

### Added
- **服务器配置下发（MessagePack）安全校验**：新增 HMAC 校验支持（`BuildConfig.SERVER_CONFIG_HMAC_SECRET`）。

### Changed
- **xCrash 本地模块化**：改为引用 `:xcrash` 本地模块，支持 Android 15+ 16KB page alignment 的本地编译版本。

### Fixed
- **代码折叠稳定性**：折叠状态下删除相邻空白行不再破坏折叠；折叠后可在闭合 `}` 后放置光标；Undo/Redo 后彩虹括号颜色不再丢失。

### Technical
- 版本号：0.9.57 (versionCode: 958)

## [0.9.36] - 2026-01-26

### Added
- **Android 15+ 16KB 页面对齐支持**
  - 新增 `docker/rsync-build/` Docker 构建系统，用于编译支持 16KB 页面对齐的 rsync
  - Fork 并集成 android-rsync 作为子模块（`external/android-rsync`）
  - 构建 ARM64 和 x86_64 架构的 librsync.so，支持 Android 15+ 设备
  - 新增详细文档：
    - `docs/design/16KB-Rsync-Integration-Complete.md`：完整集成报告
    - `external/android-rsync/README-16KB-ALIGNMENT.md`：技术文档
    - `external/android-rsync/BUILD-GUIDE.md`：中文构建指南
    - `docker/rsync-build/README.md`：Docker 构建文档

- **服务器配置同步系统**
  - 新增 `ServerConfigManager.kt`：客户端配置管理器，支持从服务器同步配置
  - 新增 `ServerConfigSyncWorker.kt`：WorkManager 后台定时同步任务
  - 新增服务端 `/api/v1/config` 端点，下发客户端配置
  - 新增 Admin 管理界面"客户端配置"标签页，支持预览和管理下发配置

- **服务端运行时配置项**
  - `auth.registration_enabled`：是否允许新用户注册
  - `auth.email_verification_required`：注册时是否需要邮箱验证码
  - `auth.qq_login_enabled` / `auth.wechat_login_enabled`：第三方登录开关
  - `feature.feedback_enabled` / `feature.plugin_market_enabled` / `feature.package_manager_enabled`：功能开关
  - `feature.developer_options_enabled`：开发者选项开关
  - `client.min_version` / `client.recommended_version` / `client.force_update`：客户端版本控制
  - `config.refresh_interval_secs`：配置刷新间隔

- **工具链安装增强**
  - 工具链安装现在自动包含 `clang-format-<version>` 包
  - 修复用户无法使用代码格式化功能的问题

### Changed
- **16KB 页面对齐修复**
  - 移除 `com.nerdoftheherd:android-rsync:3.4.1` Maven 依赖
  - 使用本地构建的 16KB 对齐版本（位于 `app/src/main/jniLibs/`）
  - 更新 `docs/design/16KB-Page-Alignment-Fix.md`，标记 librsync.so 为已完成
  - 所有 native 库现已支持 Android 15+ 的 16KB 页面大小要求

- **代码格式化模块重构**
  - `CodeFormatter.kt`：
    - 修复路径处理问题，正确区分 host/guest 路径
    - 移除复杂的 YAML 转换逻辑，改用 clang-format 内置风格名称
    - 新增 `AvailabilityResult` 密封类，提供详细的可用性检查结果
    - 将 `formatFile()` 重命名为 `formatGuestFile()`，明确 API 语义
    - 新增 `extractGuestParentDir()` 方法处理 guest 路径
  - `ClangFormatConfigManager.kt`：
    - 添加 `ConcurrentHashMap` 配置缓存，避免重复 IO
    - 配置列表改为懒加载
    - 新增 `clearCache()` 方法

- **登录流程增强**
  - `LoginViewModel.kt`：支持从服务器同步邮箱验证码配置
  - `LoginActivity.kt`：根据服务端配置动态显示/隐藏验证码输入框
  - 支持在邮件服务不可用时跳过验证码验证

- **设置界面优化**
  - `DeveloperOptionsSection.kt`：新增开发者选项入口（受服务端配置控制）
  - `SettingsRootSection.kt`：根据服务端配置动态显示功能入口

### Fixed
- 修复 clang-format 未安装导致代码格式化功能不可用的问题
- 修复 `formatFile()` 方法中 host/guest 路径混淆的问题
- 修复工作目录解析在 Windows 主机上可能失败的问题

### Technical
- 版本号：0.9.36 (versionCode: 937)
- 服务端新增 12 个运行时配置项
- Admin 后台新增配置预览功能

---

## [0.9.35] - 2026-01-25

### Changed
- **国际化（i18n）增强**
  - 多个枚举类和数据类添加 `getDisplayName(context: Context)` 方法，支持运行时国际化
  - 涉及类：`BuildLogLevel`、`BuildVariables`、`CompilerType`、`DebugToolbarPosition`、`KeyboardShortcuts`、`HelpDocument`、`Feedback`、`UbuntuMirrorManager`、`TerminalPreferences`、`LspTestTypes`、`DevTestRegistry`、`BottomPanelTypes`
  - 旧的 `displayName` 属性标记为 `@Deprecated`，推荐使用新方法

- **异常处理优化**
  - `ExceptionMessages.kt`：工具链相关消息方法支持 Context 参数
  - `TinaIDEException.kt`：`ToolchainNotInstalledException` 工厂方法支持 Context 参数

- **代码质量改进**
  - `ProjectManagerViewModel.kt`：简化公告处理逻辑，移除冗余的空值检查
  - `CompileProjectUseCase.kt`、`SingleFileBuildStrategy.kt`、`CMakeBuildExecutor.kt`、`PRootCompiler.kt`、`PRootEnvironment.kt`、`LspTestManager.kt`、`RunConfigDialog.kt`：适配新的国际化 API

- **子模块更新**
  - `tina-android-tree-sitter`：构建脚本优化，多个 Task 类添加 `@get:Internal` 注解避免 Gradle 输入/输出警告
  - `tina-sora-editor`：`LineStyles.kt` 小修复

### Technical
- 版本号：0.9.35 (versionCode: 936)

---

## [0.9.30] - 2026-01-24

### Changed
- **网络层重构与优化**
  - 统一 OkHttp 客户端管理：引入 `OkHttpClientProvider` 单例，提供全局共享的 OkHttpClient 实例
  - 优化连接池配置：最大空闲连接数 5，连接保活时间 5 分钟
  - 统一超时配置：连接超时 30 秒，读写超时 60 秒
  - 重构所有网络 API 客户端使用统一的 OkHttpClient（AuthRepositoryImpl、CrashLogUploader、FeedbackRepository、LicenseRepository、PluginMarketplaceApi 等）
  - 新增 SmartDns 支持：集成 SmartDNS 解析能力，提升域名解析速度和可靠性

- **UI/UX 改进**
  - 重构 `InstallContentComponents.kt`：优化 PRoot 环境安装界面布局和交互
  - 改进 `ProgressComponents.kt`：增强进度显示组件的视觉效果和动画
  - 优化 `MirrorComponents.kt`：改进镜像源选择界面
  - 新增 `TinaAnimation.kt` 和 `TinaTopBar.kt`：统一动画效果和顶部栏组件
  - 优化多个测试界面的布局和交互（ActivationTestScreen、ClangdTestScreen、LogUploadTestScreen）
  - 改进包管理器相关界面（PackageManagerScreen、PackageDetailScreen、InstallHistoryScreen）

- **代码质量提升**
  - 重构 `OutputActivity.kt`：优化代码结构和可读性
  - 改进 `TerminalActivity.kt`：优化终端界面逻辑
  - 优化 `OpenSourceLicensesActivity.kt`：改进开源许可证展示
  - 统一网络请求错误处理和日志记录

- **文档整理**
  - 清理过时的设计文档（删除 20+ 个已完成或过时的设计文档）
  - 新增 `TinaIDE-Design-System.md`：统一设计系统文档
  - 新增 `database-migration-merge.md`：数据库迁移合并说明
  - 更新后端功能路线图和设计文档索引

- **数据库优化**
  - 合并所有数据库迁移脚本为单一 `recreate_schema.sql`
  - 简化数据库初始化流程
  - 移除分散的迁移文件，统一管理数据库架构

- **部署改进**
  - 更新 Docker 部署配置和文档
  - 优化部署脚本 `deploy-all.ps1`
  - 改进多环境部署支持（dev、1panel、flexible）

- **后端健康检查增强**（server/tina-server）
  - 分离 PostgreSQL 和 Redis 健康检查方法
  - 改进健康检查响应格式，提供更详细的依赖状态信息
  - 优化前端状态页面，支持显示各依赖的响应时间和状态
  - 增强错误处理，允许在部分服务降级时仍返回状态信息

- **编辑器调试能力增强**（tina-sora-editor 子模块）
  - 新增 `touchDebugLogEnabled` 配置项，用于触摸事件调试
  - 在 `EditorTouchEventHandler` 中添加详细的触摸事件日志
  - 记录 ACTION_DOWN、ACTION_UP、滑动、长按、单击等事件
  - 便于定位和解决触摸相关问题

### Fixed
- 修复网络请求中的连接泄漏问题
- 修复部分界面的布局问题
- 修复反馈系统的网络请求错误处理

### Technical
- 版本号：0.9.30 (versionCode: 931)
- 子模块 proot-builder 更新

## [0.9.27] - 2026-01-23

### Added
- **Lua 脚本插件系统**
  - 新增 `PluginPermission.kt`：权限枚举和 L0-L3 分级定义
  - 新增 `PluginPermissionManager.kt`：权限授予/撤销/持久化管理
  - 新增 `PluginSandboxConfig.kt`：沙箱配置和速率限制器
  - 新增 `PluginRuntime.kt`：脚本运行时接口和实现
  - 新增 `ScriptPluginManager.kt`：脚本插件生命周期管理
  - 集成 LuaJ 引擎（`party.iroiro.luajava:luaj:4.1.0`，纯 Java 实现）

- **脚本插件 API 模块**
  - 新增 `api/PluginApi.kt`：核心 API 模块（editor/ui/storage/commands）
  - 新增 `api/PluginEditorBridge.kt`：编辑器桥接层（连接 CodeEditor）
  - 新增 `api/EventsApiModule.kt`：事件系统（插件间通信）
  - 新增 `api/FileApiModule.kt`：文件 API（`tina.fs`，项目目录内读写）
  - 新增 `api/ClipboardApiModule.kt`：剪贴板 API（`tina.clipboard`）
  - 新增 `api/NetworkApiModule.kt`：网络 API（`tina.network`，域名白名单限制）

- **插件市场系统**
  - 新增 `PluginMarketplaceModels.kt`：市场数据模型
  - 新增 `PluginMarketplaceApi.kt`：API 客户端（支持断点续传下载）
  - 新增 `PluginMarketplaceRepository.kt`：存储库
  - 新增 `PluginMarketplaceViewModel.kt`：ViewModel
  - 新增 `PluginMarketplaceSection.kt`：市场 UI 界面（列表/搜索/分类/安装）

- **插件权限 UI**
  - 新增 `PluginPermissionDialog.kt`：权限确认对话框组件（支持国际化，高风险权限警告）
  - 新增 `PluginInstallHelper.kt`：插件安装辅助函数（权限预览、manifest 解析）

- **示例脚本插件**
  - 新增 `bundled_plugins/sample.script.hello/`：Hello World Lua 脚本插件示例

- **反馈系统基础能力**
  - 新增 `Feedback.kt`：反馈数据模型（支持 bug/feature/improvement/question/other 分类）
  - 新增 `FeedbackRepository.kt`：反馈数据仓库
  - 新增 `FeedbackScreen.kt`：反馈提交界面（Compose）
  - 新增 `FeedbackViewModel.kt`：反馈表单验证和提交逻辑
  - 支持标题（5-100字符）和内容（10-5000字符）字数限制
  - 自动收集设备信息辅助问题诊断

- **书签系统持久化**
  - 新增 `BookmarkStateStorage.kt`：书签状态持久化管理
  - 书签数据保存在项目目录 `.tinaide/state/bookmarks.json`
  - 使用原子文件写入，防止数据损坏
  - 支持项目相对路径，确保工程可搬移

### Changed
- **PluginManager 增强**
  - 新增单例模式 `getInstance(context)`
  - 新增 `listInstalledPlugins()` / `getInstalledPlugin()` 便捷方法
  - 新增 `install(zipFile)` 挂起函数

- **PluginsSettingsSection 重构**
  - 集成权限授权 UI：安装脚本插件时显示权限对话框
  - 新增插件市场入口按钮
  - 支持 script/hybrid 类型插件的权限预览和确认流程

- **设置页面**
  - 新增插件市场导航入口

### Fixed
- **代码质量优化（国际化完善）**
  - 修复 `PackageManagerScreen.kt` 所有硬编码字符串（安装/卸载/进度提示等）
  - 修复 `FeedbackViewModel.kt` 硬编码中文错误消息
  - 修复 `PackageApiClient.kt` 网络错误消息硬编码
  - 修复 `PluginApi.kt` "[Error]" 前缀硬编码
  - 修复 `RunConfigDialog.kt`、`ProjectDialogs.kt`、`LoginActivity.kt` placeholder 硬编码
  - 新增 39 个字符串资源（中英文双语）

- **代码重构（消除重复）**
  - 新增统一的 `ApiResult` 类，替代 `TinaServerApi` 和 `PackageApiClient` 中的重复定义
  - 新增 `OkHttpClientProvider` 单例，统一管理 HTTP 客户端
  - 消除 9 处重复的 OkHttpClient 创建，减少 56% 资源占用
  - 统一网络配置管理（超时、重试、连接池等）

- **书签系统交互优化**
  - 修复书签编辑对话框状态同步问题
  - 使用 `LaunchedEffect` 自动同步 `editingText`
  - 确保对话框始终显示正确的书签备注内容

### Removed
- 删除 `docs/design/QuickJS-Plugin-System-Design.md`（改用 Lua 方案）

---

## [0.9.23] - 2026-01-23

### Added
- **包管理器系统**
  - 新增 `PackageManager` 接口与 `PackageManagerImpl` 实现：统一管理 Linux/Android 双平台软件包
  - 新增 `PackageApiClient`：与后端 API 交互，获取包列表、版本信息、下载链接
  - 新增 `AptPackageBackend`：Linux 平台通过 PRoot + apt 安装包
  - 新增 `DownloadPackageBackend`：Android 平台直接下载 zip 并解压安装
  - 新增 `ResumableDownloader`：支持断点续传的下载器
  - 新增 `PackageCacheManager`：包信息本地缓存（5 分钟过期）
  - 新增 `LocalInstallStateStore` / `InstallHistoryStore`：安装状态与历史记录持久化

- **包管理器 UI**
  - 新增 `PackageManagerScreen`：包列表页面，支持分类筛选、搜索、批量安装
  - 新增 `PackageDetailScreen`：包详情页面，显示版本、依赖、安装状态
  - 新增 `InstallHistoryScreen`：安装历史记录页面
  - 新增 `PackageManagerViewModel`：管理 UI 状态与安装流程

### Fixed
- 修复 `InstallError.Cancelled` 编译错误：`data class` 改为 `object`（data class 必须有参数）
- 修复 `PackageManagerViewModel` 中 `TinaServerConfig.getBaseUrl()` 方法不存在的问题

---

## [0.9.21] - 2026-01-23

### Changed
- **Tree-sitter 上游高亮规则自动同步**
  - 构建阶段自动执行 `:app:syncTreeSitterQueries`（挂到 `merge*Assets`），无需手动运行任务。
  - `highlights.scm` 不再写入 `app/src/main/assets`，改为生成到 `app/build/generated/...` 并作为 assets 打包，避免工作区被构建弄脏。

---

## [0.9.20] - 2026-01-23

### Changed
- **上游高亮规则（highlights.scm）进一步统一**
  - `:app:syncTreeSitterQueries` 覆盖范围扩展到 C/C++/CMake 等语言，并从上游仓库同步 `highlights.scm` 到 assets（以提升跨平台一致性）。
  - C/C++ Provider 复用通用 query loader 与通用 theme 映射，减少维护与捕获名不一致带来的妥协。

---

## [0.9.19] - 2026-01-23

### Added
- **Tree-sitter Queries 同步任务**
  - 新增 `:app:syncTreeSitterQueries` Gradle task：从 `external/tina-android-tree-sitter/grammars/{lang}/queries/` 同步 `highlights.scm` 到 `app/src/main/assets/tree-sitter-queries/{lang}/`。

- **用户反馈（Feedback）基础能力**
  - 新增反馈相关数据模型、仓库与 Compose 界面（FeedbackScreen + ViewModel）。

### Changed
- **Tree-sitter 高亮架构重构**
  - 新增通用 Query 加载与缓存：`TreeSitterQueryLoader`。
  - 新增通用高亮 Provider：`GenericTreeSitterLanguageProvider` + 通用 theme 映射：`GenericTreeSitterTheme`。
  - 新增表驱动分发：`TreeSitterLanguageRegistry`，纯高亮语言（JSON/YAML/TOML/Make/Bash/Rust）统一走通用实现，减少重复 Provider。

- **构建稳定性**
  - 修复 Tree-sitter Rust/TOML 语法生成的兼容性问题（在构建期提供必要的兼容/依赖 shim），确保 `:app:assembleDebug` 可通过。

---

## [0.9.9] - 2026-01-22

### Added
- **Rust 语言 Tree-sitter 支持**
  - 新增 `tree-sitter-rust` 依赖
  - 新增 `RustTreeSitterLanguageProvider.kt` 语言提供器
  - 新增 Rust 语法高亮查询文件（highlights.scm、blocks.scm、brackets.scm、locals.scm）
  - 支持 `.rs` 文件的语法高亮

- **TOML 语言 Tree-sitter 支持**
  - 新增 `tree-sitter-toml` 依赖
  - 新增 `TomlTreeSitterLanguageProvider.kt` 语言提供器
  - 新增 TOML 语法高亮查询文件（highlights.scm、blocks.scm、brackets.scm）
  - 支持 `.toml` 文件（如 Cargo.toml、pyproject.toml）的语法高亮

### Changed
- **新建文件模板简化**
  - C/C++ 源文件模板移除默认的 main 函数，改为空模板
  - 仅保留基础的 include 语句，让用户自行编写代码

- **CI 构建流程优化**
  - 为 tree-sitter-toml 添加 regexp-util npm 依赖安装步骤

---

## [0.9.4] - 2026-01-22

### Added
- **C 语言独立 Tree-sitter 支持**
  - 新增 `tree-sitter-c` 依赖，C 文件使用独立的 TSLanguageC 解析器
  - 修复 C 语言语法高亮使用 C++ 解析器导致的兼容性问题
  - 优化 C 语言 highlights.scm 查询文件，移除重复的类型定义

- **编辑器硬件加速设置**
  - 新增"硬件加速"开关（设置 → 编辑器 → 性能）
  - 默认开启 GPU 渲染以提升滚动性能
  - 某些设备（如一加）可能出现 Surface 缓冲区错误，可关闭此选项
  - 修改后需重新打开文件生效

### Improved
- **进程管理器（ProcessManager）重构**
  - `stopCurrentProcess()` 增加幂等性保护，避免重复触发停止逻辑
  - 新增 `stopJob` 确保停止协程唯一性
  - 停止时立即取消运行任务（`currentRunJob`），避免继续推进
  - 简化停止流程：优雅中断（SIGINT）后立即销毁，不再等待 5 秒超时
  - `stopAndPrepareForNewRun()` 改为静默停止，不阻塞线程

- **输出界面（OutputActivity）优化**
  - 移除广播机制（`ACTION_STOP_RUNNING_PROGRAM`），改用直接调用 `forceStopRunningProgram()`
  - 新增 `userRequestedStop` 标志，点击停止后立即停止 UI 输出追加
  - 避免"点了停止还在刷屏"的观感问题
  - 新进程启动时自动恢复输出显示

- **PTY 进程终止增强**
  - `PtyProcess.destroy()` 支持进程组终止（`Os.kill(-pid, SIGKILL)`）
  - 子进程链存在时可一并终止，提升终止可靠性

### Changed
- **MainActivity 简化**
  - 移除 `stopRunningReceiver` 广播接收器及相关注册/注销逻辑
  - 进程停止改由 OutputActivity 直接调用 ProcessManager

- **PRootLogActivity 返回逻辑优化**
  - 参数重命名：`onBack` → `onFinish`，语义更清晰
  - 详情页返回列表，列表页返回设置页面

- **关于页面 Toast 移除**
  - 移除点击版本号时的默认 Toast 提示

---

## [0.8.94] - 2026-01-21

### Added
- **国际化（i18n）统一基础设施**
  - 新增全局字符串入口 [`AppStrings.kt`](app/src/main/java/com/wuxianggujun/tinaide/core/i18n/AppStrings.kt)（避免到处传 `Context`）。
  - 新增资源别名 [`ResAliases.kt`](app/src/main/java/com/wuxianggujun/tinaide/core/i18n/ResAliases.kt)（`typealias Strings = R.string` 等），减少 `import R` 噪声。
  - 新增资源扩展 [`ResExt.kt`](app/src/main/java/com/wuxianggujun/tinaide/core/i18n/ResExt.kt)（`Strings.xxx.str()` / `strOr(context)` / `stringArray()`）。
  - 新增异常消息统一入口 [`ExceptionMessages.kt`](app/src/main/java/com/wuxianggujun/tinaide/core/exception/ExceptionMessages.kt)。
  - 新增规范文档 [`docs/i18n.md`](docs/i18n.md) 及配套设计文档（`docs/design/I18n-*`）。
  - 新增维护脚本 `tools/i18n/**` 与 `tools/check_i18n.py`（values-en 同步、硬编码中文扫描、资源属性硬编码扫描等）。

- **协议/隐私文本资源化**
  - 新增 `res/raw/privacy_policy.md`、`res/raw/user_agreement.md` 及英文版本 `res/raw-en/*`，用于长文本多语言维护。

- **安装过程 UI/体验**
  - 新增依赖安装的分组列表组件 [`GroupedPackageList.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/workspace/components/GroupedPackageList.kt)（安装中/等待/已完成可折叠/失败分组）。
  - 新增图标资源 `ic_arrow_down`、`ic_clock`。

### Changed
- **全 App 字符串调用风格统一**
  - Compose：统一用 `stringResource(Strings.xxx, ...)`。
  - 非 Compose：统一用 `Strings.xxx.strOr(context, ...)` / `Strings.xxx.str(...)`。
  - 覆盖模块：登录/授权、编译系统、PRoot/工具链、终端、Git/SSH、编辑器、设置与工作区等。

- **PRoot 安装流程稳健性**
  - [`PRootBootstrap.kt`](app/src/main/java/com/wuxianggujun/tinaide/core/proot/PRootBootstrap.kt) 安装进度/阶段不再依赖解析本地化文本（避免因语言差异导致状态误判）。
  - 包清单生成改为基于 `Context` 的动态生成（便于后续多语言展示/配置扩展）。

- **混淆规则完善**
  - [`app/proguard-rules.pro`](app/proguard-rules.pro) 补齐 Tree-sitter JNI 相关字段/内部类 keep 规则，降低 release 混淆引发的运行时崩溃风险。

### Fixed
- **资源编译**
  - 修复 `strings.xml`/`values-en/strings.xml` 中导致 aapt2 报错的无效转义字符（`Invalid unicode escape sequence`）。

- **运行时架构识别**
  - 新增 [`RuntimeAbiDetector.kt`](app/src/main/java/com/wuxianggujun/tinaide/core/device/RuntimeAbiDetector.kt)，在 x86_64/arm64 兼容场景下避免仅依赖 `Build.SUPPORTED_ABIS` 造成误判。

## [0.8.84] - 2026-01-21

### Added
- **PRoot 日志系统**
  - 新增 [`PRootSessionLogger.kt`](app/src/main/java/com/wuxianggujun/tinaide/core/proot/PRootSessionLogger.kt)：PRoot 会话日志记录器
  - 新增 [`PRootLogActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/workspace/PRootLogActivity.kt)：PRoot 日志查看界面
  - 日志文件存储在内部私有目录 `files/logs/proot/`，包含会话日志和错误日志
  - 支持记录命令、环境变量、stdout/stderr 输出、退出码等完整信息
  - 在设置 → 关于页面新增"查看 PRoot 日志"入口，便于排查安装与运行问题
  - 日志导出功能集成 PRoot 日志（最近7天，单文件最大2MB）

- **PRoot 自动重试机制**
  - 检测到 PRoot 运行时崩溃（SIGSEGV/signal 11）时自动开启兼容模式重试
  - 检测到 seccomp/syscall 兼容性问题（fork ENOSYS）时自动开启兼容模式重试
  - 自动注入 `PROOT_NO_SECCOMP=1` 和调整 `KERNEL_RELEASE`，提升跨设备兼容性
  - 避免用户手动配置，减少"怎么配都不生效"的困扰

### Improved
- **PRoot 错误分类器增强**
  - [`PRootErrorClassifier.kt`](app/src/main/java/com/wuxianggujun/tinaide/core/proot/PRootErrorClassifier.kt)：新增 Native 崩溃检测
  - 识别 "terminated with signal 11"、"SIGSEGV" 等典型崩溃日志
  - 改进 seccomp 相关错误识别（fork ENOSYS、syscall 兼容性）

- **PRoot 日志记录完整性**
  - [`PRootManager.kt`](app/src/main/java/com/wuxianggujun/tinaide/core/proot/PRootManager.kt)：
    - `execute()` 和 `executeWithOutput()` 方法集成会话日志记录
    - 记录完整的命令、环境变量、输出、退出码、超时状态
    - 自动重试时记录重试原因和新配置
  - [`init-proot.sh`](app/src/main/assets/proot/init-proot.sh)：
    - 新增日志函数：`log_info()`、`log_warn()`、`log_error()`、`log_cmd()`、`log_debug()`
    - 支持通过 `PROOT_SESSION_LOG_FILE` 和 `PROOT_ERROR_LOG_FILE` 环境变量指定日志文件
    - 记录脚本执行的关键步骤和错误信息

- **编辑器工具栏简化**
  - [`EditorToolBar.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorToolBar.kt)：
    - 移除"查找"、"跳转到行"按钮（功能已移至菜单）
    - 移除"Code Actions"、"Rename"按钮（LSP 功能暂未完善）
    - 保留核心编辑操作：撤销、重做、保存、书签功能
  - [`BottomPanel.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanel.kt)：同步移除相关回调参数
  - [`MainActivity.kt`](app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt)：简化事件处理逻辑

- **底部面板 UI 优化**
  - [`BottomPanelTabRow.kt`](app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/BottomPanelTabRow.kt)：
    - 使用 `ScrollableTabRow` 替代 `PrimaryTabRow`，支持横向滚动
    - 移除默认分隔线，视觉更简洁
    - 优化按钮尺寸和间距（32dp 可点击区域，20dp 图标）
    - 改进图标颜色（使用 `onSurfaceVariant`）

- **项目路径管理**
  - [`ProjectPaths.kt`](app/src/main/java/com/wuxianggujun/tinaide/project/ProjectPaths.kt)：
    - 新增 `getInternalLogsRoot()`：内部日志目录（私有）
    - 新增 `getPRootLogsRoot()`：PRoot 日志目录（内部）

- **日志导出增强**
  - [`LogExportUtils.kt`](app/src/main/java/com/wuxianggujun/tinaide/core/logging/LogExportUtils.kt)：
    - 新增 `addPRootLogsToZip()`：导出 PRoot 日志（最近7天）
    - 改进 `clearLogs()`：递归删除目录，支持清理 PRoot 日志

### Changed
- **菜单项调整**
  - [`strings.xml`](app/src/main/res/values/strings.xml) / [`strings-en.xml`](app/src/main/res/values-en/strings.xml)：
    - "源代码管理" → "书签"（`menu_source_control` → `menu_bookmarks`）
    - 新增 `menu_goto_line`："跳转到行"
    - 新增 PRoot 日志相关字符串资源

### Removed
- **文档清理**
  - 删除 [`docs/design/PRoot-Init-Proot-Script-Guide.md`](docs/design/PRoot-Init-Proot-Script-Guide.md)（内容已迁移到 `docs/init-proot-script.md`）
  - 更新 [`docs/design/PRoot-Execution-Guide.md`](docs/design/PRoot-Execution-Guide.md) 和 [`docs/design/README.md`](docs/design/README.md) 中的文档引用路径

---

## [0.8.83] - 2026-01-20

### Fixed
- **编辑器滚动 / 惯性滚动**
  - 修复 CodeEditor 在 Compose + AndroidView 场景下偶发“滑几行就停/突然卡住”的问题（触摸序列被父容器打断）
  - 侧滑栏手势改为仅左侧窄边缘区域响应，减少对编辑器滚动的干扰（`SwipeableDrawer`）
  - 关闭 CodeEditor 的 NestedScrolling，降低被父层嵌套滚动链路打断的概率（ACTION_CANCEL）
  - 优化 sora-editor fling 参数：允许同时存在水平/垂直速度并开启 overscroll，提升快速滑动的惯性一致性

### Added
- **开发者选项 - 诊断日志开关**
  - 新增“诊断日志”总开关 + 分项开关（编辑器触摸诊断 / 手势链路追踪 / sora-editor 内部日志）
  - 新增 `GestureTrace`（tag=`GestureTrace`），可定位 Drawer/底部面板等 Compose 手势是否在消费/拦截触摸事件

### Changed
- **日志策略**
  - 编辑器触摸诊断默认关闭；仅在“开发者选项”开启对应开关后输出，避免影响性能与日志量

---

## [0.8.79] - 2026-01-20

### Fixed
- **书签系统**
  - 修复书签编辑对话框状态同步问题
    - 使用 `LaunchedEffect` 在 `editingTarget` 改变时自动同步 `editingText`
    - 修复对话框关闭时状态未清空的问题
    - 确保编辑对话框始终显示正确的书签备注内容
  - 改进对话框交互体验
    - 取消和确认时都会清空 `editingText` 状态
    - 避免下次打开对话框时显示旧内容

- **日志上传**
  - 修复设备信息获取不完整的问题
    - 创建统一的 [`DeviceInfoProvider.kt`](app/src/main/java/com/wuxianggujun/tinaide/core/device/DeviceInfoProvider.kt) 工具类
    - 所有设备信息字段添加空值保护，确保百分百有值
    - 使用 `takeIf { it.isNotBlank() } ?: "Unknown"` 模式处理所有字段
    - 统一管理设备信息获取逻辑，避免重复代码
  - 更新日志上传接口
    - 在 [`TinaServerConfig.kt`](app/src/main/java/com/wuxianggujun/tinaide/auth/api/TinaServerConfig.kt) 中添加解析好的设备字段
    - 新增 `deviceModel`、`deviceBrand`、`androidVersion` 字段
    - 后端可直接使用这些字段，无需解析 JSON
    - 保留完整的 `deviceInfo` JSON 以保持完整信息
  - 更新日志导出工具
    - 在 [`LogExportUtils.kt`](app/src/main/java/com/wuxianggujun/tinaide/utils/LogExportUtils.kt) 中使用新的 `DeviceInfoProvider`
    - 确保本地导出和服务器上传使用相同的设备信息获取逻辑

- **管理后台日志查看**
  - 修复非崩溃日志详情页面显示无用内容的问题
    - 只在崩溃日志（log_type === 'crash'）时显示设备信息、内容和元数据
    - 其他类型日志（运行、调试、性能）不显示这些section
  - 修复复制功能失败的问题
    - 增强复制功能的错误处理
    - 添加空内容检查，避免复制空值
    - 改进错误提示信息，提示用户检查浏览器权限
  - 创建日志文件说明文档 [`Log-Files-Explanation.md`](docs/design/Log-Files-Explanation.md)
    - 说明各个日志文件的内容和用途
    - 解释日志系统的架构和设计
    - 提供诊断问题时的查看顺序建议

### Removed
- **底部日志面板系统**
  - 删除 BottomLogBuffer 及相关组件
    - 删除 `BottomLogBuffer.kt`、`BottomLogTree.kt`、`BottomLogEntry.kt`、`BottomLogLevel.kt`
    - 删除 `LogcatMonitor.kt`（仅用于转发日志到 BottomLogBuffer）
    - 删除 `ProjectLogSession.kt`（已无使用）
    - 删除未使用的 `LogPanelView.kt` UI 组件
  - 简化日志系统架构
    - 移除 FileManager 的 projectLogSession 参数
    - 移除 TinaTimber 中的 BottomLogTree 注册
    - 移除日志导出中的 bottom_panel.txt
  - 原因：IDE 没有底部日志面板 UI，该系统完全未使用
  - 详见：[`BottomLog-Removal-Summary.md`](docs/design/BottomLog-Removal-Summary.md)

### Added
- **编辑器调试工具**
  - 新增 [`EditorTouchDebug.kt`](app/src/main/java/com/wuxianggujun/tinaide/editor/debug/EditorTouchDebug.kt) 触摸事件调试工具
    - 记录触摸事件的详细信息（ACTION_DOWN、ACTION_MOVE、ACTION_UP 等）
    - 计算和显示滑动速度
    - 帮助调试编辑器触摸交互问题

---

## [0.8.78] - 2026-01-20

### Summary
- **开发者选项**

## [0.8.77] - 2026-01-19

### Summary
- **CI / Release**
- **Quality**

## [0.8.57] - 2026-01-15

### Summary
- **编辑器性能优化系统**
- **编辑器滑动性能大幅提升**
- 新增 `app/src/main/java/com/wuxianggujun/tinaide/editor/performance/README.md`：性能优化文档

## [0.8.56] - 2026-01-14

### Summary
- **软链接管理器（SymlinkManager）**
- **工具链安装自动配置软链接**
- **工具链路径解析增强**
- 更新 Symlink-Manager-Design.md：

## [0.8.55] - 2026-01-14

### Summary
- **UI 设计规范常量统一**
- **日志系统重构**
- **工具链路径解析**
- **硬编码颜色统一替换**
- **组件库文档更新**
- **应用初始化优化**
- **设置页面优化**
- **编辑器状态管理优化**

## [0.8.19] - 2026-01-12

### Summary
- **Android 16 上 Ubuntu/工具链下载失败（PRoot/apt）**
- **PRoot：避免在新内核上误注入 `PROOT_NO_SECCOMP`**
- **安装包清单体验（全包名 + 动画一致 + 排序更清晰）**
- **移除 talloc assets 运行时依赖**

## [0.8.18] - 2026-01-11

### Summary
- **本地函数参数提示功能**
- **开发者选项页面**
- **LSP 测试框架**
- **SharedPreferences 统一管理**
- **C/C++ 补全引擎增强**
- **项目符号索引服务增强**
- **编辑器功能管理器增强**
- **远程 LSP URI 映射优化**

## [0.8.16] - 2026-01-11

### Summary
- **远程 Clangd LSP 初始化/同步时序**
- **远程 Clangd LSP URI 映射稳定性**
- **PRoot linker 启动兼容性**
- **Git AskPass 脚本外置与权限修复**
- **MTDataFilesProvider 稳定性**
- **安装页面小修复**
- **Windows 模拟器远程 LSP 测试指南**：补充“`textDocument.uri` 必须包含文件名+扩展名、同步模式与 `remoteWorkspaceRootUri` 配置”的排查要点

## [0.8.15] - 2026-01-10

### Summary
- **终端 Shell 支持增强**
- **移除 Ubuntu 兼容代码**

## [0.8.14] - 2026-01-10

### Summary
- **APK 体积优化（减少约 75%）**
- **安装成功页面简化**
- **构建脚本优化**

## [0.8.9] - 2026-01-09

### Summary
- **Ubuntu apt keyring 预置**
- **安装日志导出**
- **PRoot/Ubuntu 环境安装流程**
- **Docker rootfs 构建**

## [0.7.79] - 2026-01-08

### Summary
- **终端状态持久化功能**
- **终端会话管理增强**
- **ProGuard 规则更新**
- 更新 `终端状态持久化设计文档.md`：
- 更新 `docs/planning/实施进度.md`、`docs/planning/Feature-Roadmap.md`、`docs/README.md`、`docs/架构概览.md`

## [0.7.78] - 2026-01-06

### Summary
- **构建系统基础设施增强**（预留功能）
- **工具类**
- **领域特定异常类系统**
- **文件路径安全验证系统**
- **编译器环境接口抽象**
- **可配置的编译超时管理**
- **路径验证功能完全集成**
- **核心模块中文注释补充**

## [0.7.62] - 2026-01-06

### Summary
- **依赖安装界面 UI 美化**
- **帮助系统**
- **日志导出功能**
- **PC LSP 代理设置指南**
- **帮助入口优化**
- **代码补全增强**
- **字符串资源完善**
- **已知问题文档更新**

## [0.7.61] - 2026-01-05

### Summary
- **Rust 编译警告修复**
- **构建脚本 APK 查找问题**
- **多架构分离实现说明**

## [0.7.44] - 2026-01-05

### Summary
- **实施进度文档重大更新**
- **核心功能完整性确认**

## [0.6.91] - 2026-01-01

### Summary
- **Android Sysroot 交叉编译支持**
- **ABI 分包构建**
- **GitHub Actions 多架构发布**
- **工具链修复功能**
- **动态包安装列表 UI**
- **Git 远程操作功能**
- **Git 功能增强**
- **PRoot 环境优化**

## [0.6.88] - 2026-01-01

### Summary
- **终端会话管理优化**
- **PRoot 环境更新**
- **UI 主题优化**
- **启动图标**

## [0.6.82] - 2026-01-01

### Summary
- **TinaIDE 统一组件库**
- **UI 组件统一重构**
- **终端功能增强**
- **编辑器改进**
- **PRoot 环境优化**
- **其他改进**
- **子模块更新**

## [0.6.74] - 2025-12-31

### Summary
- **目录结构整理与统一**
- 更新 `ProjectPaths.kt` 目录架构说明注释

## [0.6.73] - 2025-12-31

### Summary
- **构建变量系统**
- **单文件编译源文件选择模式**
- **变量补全功能**
- **变量帮助对话框**
- **运行配置对话框增强**
- **编译流程优化**
- 更新 `docs/design/运行配置功能设计.md`：

## [0.6.72] - 2025-12-30

### Summary
- **终端状态持久化功能**
- **统一存储路径管理**
- **终端会话管理器增强**
- **存储路径统一重构**

## [0.6.71] - 2025-12-30

### Summary
- **C/C++ 头文件导航功能**
- **C++ 标准库头文件识别**
- **文件路径栏**
- **编辑器文本操作窗口扩展机制**（tina-sora-editor）
- **编辑器管理器架构重构**
- **单文件项目 clangd 补全/诊断优化**
- **编辑器设置增强**
- **子模块更新**

## [0.6.50] - 2025-12-30

### Summary
- **代码折叠设置项**
- **编辑器设置热更新**

## [0.6.49] - 2025-12-30

### Summary
- **CI/CD 工作流优化**
- **跨仓库发布增强**
- **开源仓库 workflow 修复**

## [0.6.48] - 2025-12-30

### Summary
- **统一 Linux 安装界面**
- **Ubuntu rootfs 下载优化**
- **Ubuntu apt 包安装优化**
- **PRootBootstrap 重构**
- **DependencyInstallActivity 增强**
- **PRootEnvironment 优化**
- 删除 `UbuntuInstallActivity.kt`
- 从 `AndroidManifest.xml` 移除 `UbuntuInstallActivity` 注册

## [0.6.47] - 2025-12-30

### Summary
- **智能补全与高亮增强设计文档**
- **编译系统检测优化**
- **PRoot 引导流程优化**
- **UI 组件增强**
- **终端视图改进**
- **工作空间流程优化**
- **国际化完善**
- **子模块更新**

## [0.6.41] - 2025-12-29

### Summary
- **跨仓库 Release 工作流**
- **多 Linux 发行版支持**
- **子模块管理**
- **代码清理**
- 添加跨仓库 Release 工作流设计文档
- 添加开源仓库 workflow 模板 (`opensource-receive-release.yml`)

## [0.6.1] - 2025-12-29

### Summary
- **代码格式化功能**
- **clang-format 配置文件**
- **统一构建脚本 `build-apk.ps1`**
- **Git 远程操作功能设计文档**
- **BuildLog 和 InstallLog 组件重构**
- **UI 组件优化**
- **文档清理**
- 删除旧的构建脚本：`build-and-install-all-abi.ps1`、`build-release.ps1`

## [0.6.0] - 2025-12-28

### Summary
- **CMake 语法高亮修复**
- **CMake Tree-sitter 谓词支持**
- **BuildLogView 测量问题**
- 更新 `CMake-Highlight-Fix-Guide.md`，详细记录 CMake 语法高亮问题的根本原因和解决方案

## [0.5.89] - 2025-12-28

### Summary
- **断点显示功能重构**
- **BreakpointManager 重构**
- **EditorCacheManager 改进**

## [0.5.37] - 2025-12-27

### Summary
- **国际化支持 (i18n)**
- **外部依赖重构**
- **字体管理功能**
- **项目导出功能**
- **UI 组件**
- **ProjectManagerActivity 组件化重构**
- 修复侧滑栏文件页顶部"新建文件"加号图标在部分主题/配色下不可见的问题
- 修复项目主页/编辑器文件标签页选中态仅文字高亮、底部指示器不显示的问题

## [0.5.3] - 2025-12-26

### Summary
- **高性能输出视图系统**
- **进程管理器 (`ProcessManager`)**
- **独立输出界面 (`OutputActivity`)**
- **编译运行流程优化**
- **编辑器搜索功能优化**
- **UI 图标优化**
- 新增字符串资源：`action_stop`、`action_clear`、`action_scroll_to_bottom`、`action_scroll_to_top`、`action_copy_all`、`action_select_all`
- 新增布局文件：`activity_output.xml`

## [0.4.32] - 2025-12-24

### Summary
- **项目模板系统重构**
- **CMake 构建系统支持**
- ProjectTemplateInstaller 重写，从 zip 解压模板并替换占位符
- ProjectManagerViewModel.createNewProject() 支持选择模板类型
- CompileProjectUseCase 重构为策略模式，支持多种构建系统
- 删除旧的 `cpp_cmake` 模板目录
- 移除 ProjectTemplateInstaller 中的硬编码模板生成代码

## [0.4.31] - 2025-12-24

### Summary
- **终端中文语言包安装**
- **Alpine 软件源换源功能**
- 补充 CHANGELOG 缺失的版本记录 (0.3.25 ~ 0.4.30)
- 终端设置界面优化

## [0.4.30] - 2025-12-24

### Summary
- **终端界面体验优化**
- **项目清理**

## [0.3.97] - 2025-12-22

### Summary
- **Git 集成功能**
- **全局搜索功能**
- **LSP 代码操作服务**
- **运行配置功能**
- **Markdown 查看器**
- 改进底部面板和调试工具栏
- 更新 proot rootfs 构建脚本
- 更新 tree-sitter 语法查询文件

## [0.3.48] - 2025-12-18

### Summary
- **终端功能基础实现**
- **底部面板调整**
- **重构计划推进（阶段 3-7）**
- 修复 tree-sitter 编译问题

## [0.3.40] - 2025-12-17

### Summary
- **架构重构**
- 移除 treeview 模块（已用 Compose FileTree 替代）

## [0.3.25] - 2025-12-16

### Summary
- **PRoot 工具链可用**

## [0.3.0] - 2025-12-17

### Summary
- **JSON 智能编辑器**
- **PRoot 工具链迁移完成**
- **UI 交互优化**
- **JSON 文件保存问题**
- **保存按钮状态不一致**
- **底部面板拖拽体验**
- 删除旧的 Native 编译/链接/调试基础设施
- 删除"生成 compile_commands.json"菜单项（已集成到构建流程）

## [0.2.90] - 2025-12-14

### Summary
- **Jetpack Compose UI 全面迁移**
- **编辑器会话管理系统**
- **断点管理器 (`BreakpointManager.kt`)**
- **Native 崩溃捕获 (`NativeCrashHandler.kt`)**
- **键盘快捷键系统 (`KeyboardShortcuts.kt`)**
- **主题管理器 (`ThemeManager.kt`)**
- **调试面板 UI**
- **架构重构**

## [0.2.0] - 2025-12-11

### Summary
- **应用主题灰色选项**
- **编辑器配色方案**
- **调试功能基础架构（预览）**
- **社交登录**
- **主题系统统一**
- **架构重构**
- **编辑器会话与自动保存**
- 编辑器独立主题设置入口（现跟随应用主题）

## [0.1.94] - 2025-12-11

### Summary
- **UI 布局全面优化**
- **Drawable 资源**
- **字符串资源**
- 删除未使用的重复布局文件 `activity_project_manager.xml` 和 `item_project.xml`

## [0.1.88] - 2025-12-11

### Summary
- **工具栏运行按钮灰色边框问题**
- **工具栏侧滑栏按钮灰色背景问题**
- **全面替换系统图标为自定义 Material Design 矢量图标**
- **空编辑器界面优化**

## [0.0.156] - 2025-12-10

### Summary
- **开源许可页面**
- 开源许可数据自动化：无需手动维护，Gradle 构建时自动扫描依赖生成

## [0.0.155] - 2025-12-10

### Summary
- **诊断 JSON 解析重构：使用 nlohmann/json 库替代手写解析器**

## [0.0.154] - 2025-12-10

### Summary
- **Clangd 诊断功能完整实现**
- `LspService.addDiagnosticsListener()` 注册时立即发送缓存的诊断数据
- 文件关闭时自动清除对应的诊断缓存
