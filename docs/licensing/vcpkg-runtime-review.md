# vcpkg Android 运行时与包目录

- vcpkg-tool：MIT，源码 https://github.com/microsoft/vcpkg-tool ，版本 `2026-10-29`，提交 `139f2e53438b1529e178f6295bc563f4e0de734f`。
- vcpkg ports 目录：MIT，源码 https://github.com/microsoft/vcpkg ，目录快照提交和 SHA-256 在 `core/packages/src/main/assets/vcpkg/catalog.json`。
- Android CLI 由 `tools/vcpkg/build_android_tool.py` 从上游源码构建；Android shell、进程环境、运行路径与 API 适配均在脚本中可审查。每个 ABI 的 `vcpkg-build.json` 记录源码提交、适配源码哈希和最终 ELF 哈希。
- 两个 ABI 随包保留 `LICENSE.txt` 与完整 `NOTICE.txt`，后者包含 CLI 使用的 fmt、CMakeRC 等第三方声明；应用的开源组件页同步展示许可与源码链接。
- 原生 helper 在维护时通过 HTTPS 从 Termux 官方仓库按 `runtime.json` 固定版本和 SHA-256 获取，以每个 ABI 的 `helpers.tar` 快照随 APK 分发。归档及其中每个 Debian 包分别校验 SHA-256，首次准备无需访问滚动仓库；仅解压数据，不执行 Debian 安装脚本，保留 `share/doc` 许可文件。对应版本、包名、大小和校验值随 APK 的 `runtime.json` 提供，来源及对应构建脚本、上游源码地址和补丁：https://github.com/termux/termux-packages/tree/master/packages 。
- helper 闭包包含 Git/zip/unzip/pkg-config、curl/openssl、glib、Python 及其支持库，采用 GPL/LGPL、MIT/BSD、Apache-2.0、PSF、Info-ZIP 等许可，与 GPL-3.0 组合兼容；没有引入非商业或用户数限制。
- 随包 `helpers-NOTICE.txt` 保留 Debian 包内许可和 GPL-2.0、GPL-3.0、LGPL-2.1、Apache-2.0、MPL-2.0 全文；`helpers-sources.json` 固定 Termux recipe / patches 提交与上游源码定位。应用开源组件页提供同一份许可与来源记录。各 helper 是独立程序，按各自许可分发，不把它们统一改为 TinaIDE 的许可。
- ports 中各个库按自身许可分发。TinaIDE 的目录展示与 vcpkg 的 MIT 许可并不替代具体依赖库的许可。

更新 CLI 时重新构建两种 ABI，运行 `sync_runtime.py`、`verify_runtime.py` 和分发门禁。此记录只说明来源和许可，不代表所有 ports 均能在 Android 构建。
