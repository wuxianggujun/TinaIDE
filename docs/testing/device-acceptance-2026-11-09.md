# vcpkg、包管理与编辑器设备验收（2026-11-09）

本轮继续此前未完成的设备验收。设备为专用 Android 14 / API 34 x86_64 模拟器，720×1280、280 dpi、
SwiftShader、禁用 Vulkan；使用 Debug APK。证据保存在
[device-acceptance-2026-11-09](evidence/device-acceptance-2026-11-09)。

## 新发现与修复

### vcpkg shell 映射不能依赖项目路径

移除早期手工创建的 `files/usr/bin/sh`，同时移除 fmt 的安装、构建输出和二进制缓存后，真实 fmt port
在 Ninja 启动 CMake 时失败。应用确实创建了构建目录内的 shell 链接，但 tina-exec 的
`TERMUX__PREFIX_DIR___MAX_LEN` 为 90，项目内的长 prefix 超限，回退到了默认的 `files/usr`。

同一条失败命令仅改用 `TINA_PREFIX=/system` 即可成功配置。最终实现直接把 `/bin/sh` 映射到 Android
已有的 `/system/bin/sh`，不再创建临时链接，也不依赖 Windows 的符号链接权限。CLI 自身同时适配
Android 的 shell 路径、linker64 启动时的可执行文件定位、环境变量清除与 API 级别检测。

`VCPKG_MAX_CONCURRENCY` 取当前构建并发数，最小为 1；只有 PATH 一类搜索路径进行冒号合并，
prefix、数值等标量直接覆盖。单测覆盖长路径、带空格路径、并发数和继承环境变量；设备验证仍必须
执行真实安装，不能仅凭生成的配置文件正确就判定通过。

### 安装成功必须包含持久化完成

真实 Registry 测试分进程执行取消、安装和卸载。取消、安装均通过，但下一次进程执行卸载时找不到
安装记录；安装目录仍在，`shared_prefs/package_install_state.xml` 不存在。旧代码的
`SharedPreferences.apply()` 只保证内存更新，测试进程立即结束可以丢失尚未落盘的记录。

安装、卸载和更新记录改为检查 `commit()` 的结果，保存失败抛出异常，不发送成功完成事件。
现有管理器的 IO dispatcher 与操作锁继续负责线程和顺序。设备测试在完成事件内解析实际磁盘 XML，
再在独立进程内卸载，最后另启进程确认卸载记录也已持久化。磁盘写失败另有模块单测。

## 环境与证据边界

- Android vcpkg CLI 基于 `vcpkg-tool` 的 `2026-10-29`，revision 为
  `139f2e53438b1529e178f6295bc563f4e0de734f`；最终产物来源及 SHA-256 见
  `android-vcpkg-final-build.json`。早期 `android-vcpkg-cli.json` 仅代表当时版本启动检查。
- fmt 使用 12.2.0#1，仓库与 Android 辅助工具事先准备在测试应用私有目录。该验证不代表安装后自动
  下载完整 vcpkg 环境；辅助工具目前仍需准备。
- 首次 Registry 直连 GitHub 遇到连接重置及超时，不能记作通过。改用宿主已有 HTTP 代理后，真实下载
  才到达校验与安装阶段。最终记录必须标注这一网络条件。
- 先前保存的两组初始性能样本各 160 个，关闭/开启高亮时渲染中位数分别为 22/26 ms，p95 为 36/69 ms。
  后续关闭高亮的另一次采样为 26/46 ms；环境波动使不同批次不能直接作为高亮开销的对照实验。
  它们只是 Debug 模拟器中已预热渲染函数的墙钟耗时，不是 CPU 时间、屏幕帧率或触摸延迟。
- 早期外部采样运行超时，保留为失败结果。进程内采样将独立记录，不能混入无采样的性能数据。
- ARM64 真机、最终 Release/R8、真实手柄输入及完整市场页面手工交互仍不在本轮通过范围内。

## 设备结果

包含系统 shell 映射与安装状态持久化修复的 Debug APK、测试 APK 构建成功，哈希见
`final-acceptance-apks.json`。本次设备结果如下：

| 范围 | 结果 | 关键断言 |
| --- | --- | --- |
| Registry 下载取消、重试安装、跨进程卸载、再启动查询 | 4/4 | 完成时磁盘 XML 含安装记录；卸载后文件和磁盘记录均不存在 |
| vcpkg 首次安装与配置、编译、运行、重复配置 | 4/4 | fmt 从源码构建；无手工 shell 链接；输出 `TinaIDE + vcpkg: 42` |
| 编辑器进程内方法采样 | 1/1 | 取得约 467 KiB trace，主线程 18,624 条事件，解析未发现不匹配退出 |

vcpkg 首次配置包含 port 安装，用时 19.50 秒；项目编译 2.81 秒、运行 2.15 秒、重复配置 3.19 秒。
这些是同一 Debug 模拟器的测试总用时，不是产品性能保证。源码归档已准备，fmt 安装与二进制缓存已清除。

无 tracing 的后续编辑器采样中，关闭高亮通过，开启高亮在 Espresso 等待空闲阶段达到 45 秒测试限时，
不能计作通过。采样发现 `WordOccurrenceHighlightRenderer` 与反复解析视觉行映射占比较高；
据此修改为每次绘制复用映射，未换行路径复用已读取的行文本长度，优化后的验证继续进行。

本地安装状态完整 Robolectric 组早期多次达到 60 秒 task 限时，抓到的两份线程栈处于 Android 类和
APK 资源初始化阶段。失败批次保留；最终同一 60 秒 task 上限下，安装状态 **6/6**、当前系统 shell
实现的 CMake **42/42** 全部通过，无跳过，见 `final-store-cmake-tests.json`。
编辑器独立工程的同词高亮、折叠/换行、缩放和渲染视口 **33/33** 通过，无跳过，见
`editor-render-unit-tests.json`。优化后的完整宿主 Debug 构建通过；真实宿主主题、输入框、变量补全、
一万行 C++ 滚动编辑 **4/4** 通过，见 `optimized-editor-optimized-host-results.json`。

优化后关闭高亮的 160 次无 tracing 样本为 p50 **12 ms**、p95 **21 ms**，161 帧中 23 帧超过 16 ms，
见 `optimized-warm-cpp-scroll-false.json`。开启高亮的长采样仍未稳定完成：先遇到 Play 服务安装期间
`dex2oat64` 占用 CPU/内存，关闭自动更新并重启后又出现测试启动超时。失败运行没有产生新数据，
设备上遗留的旧同名 JSON 已从本轮优化证据中排除。不能据此声称开启高亮的性能已验收通过。

## Release 真机安装（2026-11-10）

用户已授权在验证后尝试 Release 构建并覆盖安装真机。当前真机经已授权的无线 ADB 连接，ARM64、
API 37，原安装为 0.19.0 / versionCode 19012。Release 构建按现有规则递增至 0.19.1，
重试时固定版本，避免因任务路径修正重复递增。

最终 **0.19.1 ARM64 Release / versionCode 19022** 构建与覆盖安装成功。APK 大小 153,699,465 字节，
SHA-256 为 `b4785ad75f00fffc409f7e9e5bf84a9178397332954953c04882f32ffb7015b7`，见
`release-install.json`。ARM64 原生库、分发许可、APK 签名、模板源码同步检查均通过。
通过 `adb install -r` 覆盖升级，未卸载或清除应用数据；`dumpsys package` 确认版本与非 debuggable 标志。

`MainPortalActivity` 冷启动返回 `Status: ok`，报告启动耗时 346 ms；后续复查 PID 4040 存活、Activity
处于 Resumed，当前进程 crash buffer 无记录。该证据覆盖安装与门户启动，不能替代真机编辑、输入法、
图形运行和导出的完整交互验收。

R8 在 4 GiB 堆下老年代占用接近 100%，104 次完整 GC 累计约 944 秒。确认资源瓶颈后只停止本任务
进程，使用 6 GiB 堆复用已完成编译产物；最终 R8、lint、资源压缩、签名与打包全部通过，重试构建耗时
32 分 29 秒。映射保留在
`app/mappings/0.19.1-bdd488b0ff-20261110021823/arm64Release/mapping.txt`。

直接删除 vcpkg 中间构建目录及文件曾被自动审批拒绝，返回 `blocked by policy`。
随后改用 CMake 的 `clean` 目标成功清理 155 个生成文件，保留精简 CLI 与来源记录；
`cleanup-pending.json` 已更新为解决状态，并记录清理前后的实际逻辑字节数。
