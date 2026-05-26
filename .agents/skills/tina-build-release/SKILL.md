---
name: tina-build-release
description: TinaIDE 构建、Gradle、ABI、CI、发布、签名和 R8 排障指南。用于处理 assemble/compile 失败、APK 构建、版本号、GitHub Actions、toolchain assets 校验或 Release 混淆问题。
---

# TinaIDE 构建与发布

## 先读文件

- `gradle/wrapper/gradle-wrapper.properties`：Gradle Wrapper 版本，当前指向 Gradle 8.13。
- `settings.gradle.kts`：`includeBuild("build-logic")`、模块和外部 tree-sitter 替换。
- `build.gradle.kts`：根插件、ktlint 配置。
- `gradle/libs.versions.toml`：AGP、Kotlin、KSP、Compose BOM 和依赖版本。
- `app/build.gradle.kts`：ABI flavor、Release、签名、R8 配置。
- `build-logic/convention/src/main/kotlin/**`：ABI 聚合、版本、mapping、Tree-sitter、toolchain assets 校验等约定插件。
- `.github/workflows/**`：dev、PR、release 构建矩阵。
- `version.properties`、`keystore.properties.example`、`docs/proguard-rules-reference.md`。

## 常用命令

```powershell
./gradlew :app:compileArm64DebugKotlin --console=plain
./gradlew :app:assembleArm64Debug --console=plain
./gradlew -Ptina.devAbi=x86_64 :app:assembleX86_64Debug --console=plain
./gradlew :app:assembleDebugAllAbi --console=plain
./gradlew :app:assembleArm64Release --console=plain
./gradlew ktlintCheck --console=plain
```

- Windows 辅助脚本存在：`tools/build-apk.ps1`。
- `tools/build-apk.ps1 -Universal` 会临时写 `app/build.gradle.kts`，执行前必须确认影响。

## 项目事实

- 依赖版本集中在 `gradle/libs.versions.toml`；不要在模块中散落硬编码版本。
- `app` flavor 是 `arm64` 与 `x86_64`，本地默认 `tina.devAbi=arm64`。
- 全 ABI 用 `:app:assembleDebugAllAbi`、`:app:assembleReleaseAllAbi` 或 `-Ptina.allAbi=true`。
- Release 默认启用 `isMinifyEnabled = true` 与 `isShrinkResources = true`。
- Release 签名读取 `keystore.properties`；真实 keystore 和密码不能提交。
- 非 tag 的 release assemble/bundle/install 可能自动递增 `version.properties`。
- tag 发布要求 `v*` 去掉 `v` 后等于 `versionName`。
- Release 构建会备份 R8 mapping；mapping 上传默认关闭，只有显式启用 `tina.releaseMapping.uploadEnabled=true` 才会上传。
- CI 使用 JDK 17、CMake 3.22.1、tree-sitter-cli 0.22.1，并 recursive checkout submodules。

## 复用入口

- ABI 聚合：`TinaAndroidAppAbiAggregationPlugin`。
- 版本递增：`TinaAndroidAppVersioningPlugin`。
- mapping 备份/上传：`TinaAndroidAppMappingPlugin`。
- Tree-sitter registry 和 ktlint 任务衔接：`TinaAndroidAppTreeSitterPlugin`。
- toolchain assets 完整性：`verifyTinaToolchainAssets` 相关 build-logic。
- 库模块 `consumer-rules.pro` 自动注册：`TinaAndroidLibraryPlugin`。

## 禁止事项

- 不要重复注册 `assembleDebugAllAbi` 或重新实现版本递增。
- 不要把 `syncTreeSitterQueries` 挂到常规 build；它是手动联网任务。
- 不要把 Gradle build 产物重定向到 `LOCALAPPDATA/TinaIDE/gradle-out/**`。
- 不要读取、打印或提交真实 `keystore.properties`、keystore、CI secrets。
- 新增第三方库必须评估 R8 keep 规则，优先写在引入依赖模块的 `consumer-rules.pro`。
- 不要恢复或复制 `docs/workflows/receive-release.yml` 到 `.github/workflows/`；该私有仓库 `repository_dispatch` 发布链路已废弃。

## 验证

- 文档或脚本变更至少检查 `git diff -- AGENTS.md .agents tools build-logic app/build.gradle.kts`。
- 构建逻辑变更先跑最小目标：`:app:compileArm64DebugKotlin`。
- ABI 或 packaging 变更跑对应 assemble。
- R8/Release 变更需要用户明确接受版本/mapping 副作用后再跑目标 release assemble，并检查 keep 规则是否在正确模块。
