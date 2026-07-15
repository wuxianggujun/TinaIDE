# Tools 文档索引

> 更新日期：2026-07-15

本目录存放本地开发、构建辅助、i18n 校验、Linux distro manifest、插件 starter 和项目模板脚本。这里不是运行时源码入口，App 功能实现应优先回到 `app/`、`core/`、`feature/` 和 `build-logic/`。

## 常用脚本

- `build-apk.ps1`：本地 APK 构建入口。
- `analyze-apk.ps1`、`check-apk-contents.ps1`：APK 内容与体积检查。
- `sync-tina-toolchain-assets.ps1`、`verify-tina-toolchain-package.ps1`：tina-toolchain 资产同步与校验；同步脚本同时维护 `assets/android-sysroot/profiles.json`。
- `verify-android-sysroot-assets.ps1`：校验 ABI 专属 Android sysroot profile manifest、sha256、`.version`、API 目录和 `libc++_shared.so`。
- `device-native-smoke.ps1`：设备侧 native smoke 验证。
- `testing/plugin-device-gate.ps1`：插件 isolated runtime、native crash 与 force-stop/relaunch 设备稳定性门禁。
- `check_i18n.py`：旧入口；完整 i18n 校验优先看 [i18n 工具说明](i18n/README.md)。

## 子目录

- [i18n](i18n/README.md)：字符串同步、硬编码 CJK 检查与乱码修复。
- [linux-distro](linux-distro/README.md)：自研 Linux 发行版 manifest 生成与资产校验。
- [plugin-starters](plugin-starters)：插件 starter 模板、打包和校验脚本。
- `testing/`：需要真实 Android 设备编排的稳定性测试入口。
- `project-templates/`：项目模板资产。
- `template-common/`、`template-native-activity/`、`template-sdl3/`、`template-terminal/`：模板工程构建配置。
- `toolchain-patches/`：toolchain 相关补丁与维护资料。

## 状态说明

- 工具脚本文档属于维护者工具文档，不能直接覆盖当前 App 行为说明。
- 修改 `tools/plugin-starters/**` 后，必须重新运行 `tools/plugin-starters/build-bundled-plugin-starters.ps1`，并检查 `tools/plugin-starters/dist/tinaide.plugin.starters/templates/*.zip`。
- 涉及构建事实时，优先校对 `tools/build-apk.ps1`、`app/build.gradle.kts`、`build-logic/convention/**` 和 `docs/documentation-status.md`。
