# 0.18.39 Release 手工验收清单

本轮重点：连续运行/快速连点不复用挂死的图形进程、旧 raylib 清理与拒绝、无限游戏运行，
并回归 0.18.38 的 RikkaHub 资源、模型页与许可修复。**构建成功不等于下面项目已通过。**

## 1. 测试前准备

1. 保存项目与设置备份，采用覆盖安装，不清除 IDE 数据；全新安装场景用独立测试设备/配置。
2. 设置 → 关于核对 **0.18.39**，记录设备型号、Android 版本、ABI 和系统导航方式。
3. 资源包市场刷新并检查更新，安装 raylib **6.0 revision 3**。仅看到版本 `6.0` 不足以确认更新。
4. 现有项目执行“清理构建目录 / 清理并重新配置”，再运行；移除项目手动复制的旧 `libraylib.so`。
   不要删项目源码或清空整个 IDE 数据。另新建一个当前 raylib 模板项目用于对照。
5. 固定使用同一份最终签名 APK；不同 APK/不同资源 revision 的结果不能混记为同一次验收。

## 2. P0：图形生命周期验收步骤

下表是操作与通过标准，**不是全项已通过的声明**；本轮按用户选择使用独立模拟器，
实际已测范围见第 7 节。独立夹具成功不能代替完整 IDE 操作。

| 操作 | 预期结果 |
| --- | --- |
| 新建 raylib 项目，安装依赖，运行 | 动画/FPS 可见，触摸有效，不只看“编译成功”或 EGL 日志 |
| 一次快速连点运行 10～20 下 | 只编译/打开一个窗口，不排队弹出第二个，不重复清空日志/重置进程 |
| 正在保存、编译时再点运行/重新构建/快捷键运行 | 不产生并行操作；可停止后明确重新发起 |
| 系统手势返回，再立即运行，连续至少 20 轮 | 每轮可见画面，无卡住/闪退；旧图形进程退出，新 PID 运行，IDE 主 PID 不变 |
| 三键导航的 Back、浮动返回按钮分别重复 10 轮 | 与手势一致，不依赖样例自行处理 `KEY_BACK` |
| 点运行后在窗口刚出现时立即返回，重复 10 轮 | 下次可正常运行；是否发生在 Surface 创建前须单独取证，不能仅凭“很快返回”推断 |
| 编译完成/启动准备时点停止，再运行 | 旧结果/迟到 Intent 不能把新窗口关闭或覆盖；没有额外迟到窗口 |
| 编译完成时旋转 IDE 或切后台再回来 | 请求最多消费一次，不残留永久忙碌状态、不自动连开多个窗口 |
| raylib → SDL2 → SDL3 → raylib，每种运行并返回 5 轮 | 每种对应独立新进程，画面/触摸有效，不互相污染 native 状态 |
| 存在旧 r1/r2 库或元数据伪装 r3 的旧库 | 安装/运行/导出被清晰拒绝，提示更新资源并重编译；项目源码未修改 |
| raylib 6.0 r2 已安装时检查更新 | 发现同版本的新 revision；更新后下载的是 r3，不复用旧 URL/hash |

不要用“疯狂点击直到一次成功”作为通过标准；任何失败需记录第几轮、当时动作和日志。

## 3. P0：运行时限与返回

| 操作 | 预期结果 |
| --- | --- |
| 游戏持续运行至少 10 分钟 | 不因退出保护/启动超时自动结束；FPS 或画面持续更新 |
| Home 到后台 30 秒，回最近任务 | 不是显式退出，不被 2 秒退出保护杀死；回到同一运行后继续出帧 |
| 锁屏/解锁、横竖屏切换 | 不出现无法恢复的黑屏；注意区分系统主动回收与宿主超时 |
| 正常退出游戏 | 立即或短时间内回 IDE；若引擎退出回调卡住，约 2 秒回收的是图形子进程而非 IDE |
| 返回 IDE 后编辑/保存/再运行 | 编辑器、日志、LSP 不受图形子进程退出影响 |

2 秒仅是**明确退出后的兜底**，800 ms 是 SDL 优雅退出宽限，10 秒仅等启动入口确认；
都不是游戏总运行时间。编译任务原有超时和终端会话仍按各自配置工作。

## 4. P0：RikkaHub 与 Release 专有回归

1. 编辑器侧栏打开 AI，进入模型/渠道选择页，滚动、搜索、切换提供商图标，多次打开关闭。
   验证此前 Vector XML / R8 模型列表 NPE 不再出现。
2. 用测试渠道发送短消息与长流式消息，停止生成、再次生成、切后台恢复；消息不丢失、不重复。
3. 打开网页预览，关闭后再次打开；检查脚本/外链行为、滚动和返回不影响 IDE。
4. 发送 Markdown 表格、代码块和 Mermaid；显示、复制与导出正常，不缺图标/字体/前端文件。
5. 进入设置、切换中英文及明暗主题，检查模型/渠道配置保留；日志与分享不包含 API Key。
6. 设置 → 关于 → 开源组件与许可证，离线打开 RikkaHub 条目读取 AGPL 全文，联网源码链接可达。
7. 导出当前 raylib/SDL 样例 APK，在独立测试包中安装运行、返回、再次打开；不能用 IDE 内运行代替导出验收。

RikkaHub 仍保留 TinaIDE embedded 适配，不声称整体升级至上游 3.x。
更多现有样例见 [0.18.37 清单](release-0.18.37-manual-checklist.md)；许可依据见
[AGPL 审查记录](../licensing/rikkahub-agpl-review.md)。

## 5. P1：其余 IDE 功能

- C / C++ 终端项目：新建、输入、输出、退出、再次运行，停止长循环。
- CMake configure/build/rebuild 与清理；编译报错定位到本次构建日志。
- 编辑器：中文/emoji 保存，撤销重做，多文件/分屏，补全、跳转、诊断刷新。
- 项目管理：打开旧项目、切换项目、导入/导出；路径与文件未丢失。
- 插件：模板安装、依赖安装、启用/禁用，离线失败与重试。
- 网络失败：资源下载中断后重试，校验失败不发布半安装包。
- 音频、控制器、低内存、厂商系统限制与 16 KB 页设备需单独记录；X11/XFCE 仍不标记为真机已验证。

## 6. 如何记录失败

记录 APK SHA-256/BUILD_ID、raylib revision/库 SHA-256、设备/Android/ABI/导航方式、项目样例、
第几次失败，以及“手势/按钮/停止/Home/锁屏/旋转”的准确顺序。保留屏幕录像与失败前后日志。

```bash
adb -s DEVICE shell pidof com.wuxianggujun.tinaide
adb -s DEVICE shell pidof com.wuxianggujun.tinaide:gui
adb -s DEVICE shell pidof com.wuxianggujun.tinaide:sdl
adb -s DEVICE shell pidof com.wuxianggujun.tinaide:sdl2
adb -s DEVICE logcat -d -v threadtime \
  TinaNativeActivity:I ExternalNativeActivity:I GraphicalRuntimeProcess:I \
  GraphicalRuntimeLaunch:I CompilerViewModel:I raylib:I AndroidRuntime:E '*:S' > graphics-runtime.txt
```

用 Git Bash 或显式 UTF-8 工具保存日志。Release 通常不支持 `adb run-as`，不要为读私有目录
清空数据或更改 APK 的调试属性。`GetWindowHandle()==0` 在该 raylib Android 后端是未实现的公共 API，
不能据此断言没有 Surface、离屏渲染或必须在 UI 线程绘制。

## 7. 本轮自动化与制品记录

### 7.1 自动化与首轮 Release 候选

2026-11-01 首次完成 `:app:assembleReleaseAllAbi`，耗时 45 分 26 秒；版本固定为 `0.18.39`，
基础版本码 `1840`，没有在重试时再次递增版本。R8 和资源压缩均保持开启。
以下是首轮候选的指纹，不能冒充后来修改导出模板后的最终 APK；最终重建记录另行核对。

| ABI | APK versionCode | 字节数 | SHA-256 |
| --- | ---: | ---: | --- |
| arm64-v8a | 18402 | 151747097 | `953decfab67c44cafe4ec2637e869ba822d9b222cbefaa1daa370a96f10c8768` |
| x86_64 | 18401 | 144823481 | `9fc678e9c0d721065b2c3aa0635dbcdfcc8f7060979cba08fa85601f6ee20dfc` |

- 本地文件为 `app/build/outputs/apk/<flavor>/release/TinaIDE-0.18.39-<abi>-release.apk`。
- Build ID：`1805f0f6e6-20261101155641`。构建使用该 HEAD **加上本轮未提交修复的工作树**，
  不能将旧 `1805f0f6e6` 单独检出后当作完整修复源码；最终承载源码的提交在完成提交后补记。
- 两个 ABI 的 APK v2 签名与历史正式包证书相同；zipalign、minSdk 28、targetSdk 36、
  ABI、版本和最终 APK 的许可全文/分发门禁均通过。
- 两个最终 DEX/mapping 均不含 6 个 ARSCLib Android/XML 平台桩类；向量资源解析保留
  `Resources.getXml → XmlPullParser.next/getName`，未关闭 R8 或全包 keep 掩盖问题。
- 新 WebP、实际使用的图标、JetBrains Mono、emoji 和 HTML 均按哈希进入 APK；
  Google Sans、banner、simple_dict 未回流。mapping 与本地归档哈希一致。
- Kotlin/JVM **126 项通过**（app 107、core:packages 19），最终编译后的类由有界 JUnitCore 运行通过；
  证据为 `junit-app-final.log` / `junit-packages-r3.log`，不是后来被筛选任务覆盖的 XML 合计。
  SDL3 模板回归另 **3 项通过**（Kotlin 2.3.21 CLI 编译后 JUnitCore，先失败再修复）；
  导出模板退出状态另 **6 项通过**（JDK 17 编译后 JUnitCore），覆盖重复请求、退出竞态和无总运行时限。
  相同 6 项随后通过 `:tools:template-common:testDebugUnitTest`（46 秒），不重复计数。
  合计 **135 项定向 JVM 测试**，不声称全仓库 Gradle 测试全部通过。
  Python checks **47 项通过**；i18n、Bash syntax、分发及静态检查通过。
- 两 ABI 的制品检查证据在 `app/build/reports/native-repeat-20261101/verification/`，
  Release 日志为同目录下的 `release-all-abi-v2.log`。

### 7.2 首轮候选完整 IDE：Android 16 / API 36 / x86_64

新建专用 `tina_release_r3_api36` AVD，GPU `host`（Intel Iris Xe / Android Emulator OpenGL ES
Translator），2 GB RAM，720×1600，手势导航。只使用 `emulator-5554`，未清除旧 AVD 或用户设备数据。
首次安装上述首轮签名 x86_64 Release，经 App UI 完成工具链部署、文件权限、市场模板与
raylib r3 安装，新建公共目录项目 `RaylibR3_36`，在设备内通过 native Clang/CMake/Ninja 构建。

| 已执行场景 | 结果 |
| --- | --- |
| 全新安装 → 市场依赖 → 创建 → 设备内编译 → 首次运行 | 成功；实际截图有圆形动画与 FPS，日志确认 `TinaIDE lifecycle r3` |
| 手势返回、立即再运行 | 25 轮通过 |
| `KEYCODE_BACK` 返回再运行 | 10 轮通过；仍使用手势导航，**不冒充三键导航 UI 已测** |
| 浮动按钮 → 确认 Return → 再运行 | 10 轮通过 |
| 快速点 Run 20 下 | 2 组通过，每组只启动一个新图形进程 |
| 窗口出现即返回 | 10 轮通过；不据此宣称一定发生在 Surface 创建前 |
| 可控慢 CMake configure 中 Stop | 3 轮通过，Stop 恢复为 idle，无迟到图形进程 |
| 上述取消后再运行/返回 | 5 轮通过 |
| Home 后至少 30 秒，从最近任务恢复 | 同一图形 PID 恢复动画，未触发退出保护 |
| 点击输入、锁屏/解锁 | 测试样例触摸计数和背景变化可见；解锁后同一 PID 继续绘制 |
| 横竖屏切换 | 同一 PID 继续绘制、没有黑屏；横屏存在拉伸，不能标为比例/布局适配完全通过 |
| 连续前台游戏运行 | 解锁后约 12 分 30 秒持续采样，累计超过 51000 帧，画面持续变化；明确返回后图形进程正常退出 |

以上普通运行/退出通过数为 **62 轮**，另有 3 轮编译取消，不将取消、失败驱动或历史夹具混入成功轮数。
IDE 主 PID 始终为 `4138`；每次重新运行使用新图形 PID，退出后无 `:gui/:sdl/:sdl2` 残留。
后半程脚本同时对照 ActivityManager 的进程启动记录，防止漏掉“先额外启动再退出”的重复窗口。
可见动画不仅依据日志判断：每轮保留截图，比较非黑像素和动态像素，并人工检查代表截图。

原模板只画移动圆，没有触摸反馈。触摸/长运行使用在**本轮新建项目**中增加计数的样例，
保留原文件并在测试结束后恢复；没有修改用户既有项目或产品代码来通过测试。
取消测试同样只在该项目临时插入 `/system/bin/sleep 8`，确认 Stop 可见后再取消，最后恢复 CMake 文件。

证据根目录：`app/build/reports/native-repeat-20261101/emulator-ide/`。
成功分组：`api36-host/gesture-01`、`gesture-probe`、`gesture-03..05`、`key-01..02`、
`overlay-v2-01..02`、`rapid-01`、`early-01..02`、`after-cancel-01`、`cancel-02` 和 `soak-01`。
长运行图形 PID `14743`，使用每次不超过 60 秒的独立探测；不把宿主休眠期间增加的墙钟时间
算作前台游戏时长。`after-unlock` 到 `final` 的持续运行、截图变化与递增帧日志分别留证。

### 7.3 跨引擎追加回归

同一 API 36 AVD、同一首轮签名候选、同一 IDE PID `4138`：

| 场景 | 结果与边界 |
| --- | --- |
| SDL2 市场模板创建与设备内编译 | 成功；手势 5 轮、浮动退出确认 3 轮均有可见动画，新图形 PID，退出后无残留 |
| SDL3 旧市场模板启动 | 生命周期 5 轮可退出，但 2 轮启动截图半屏，**不能按全屏画面通过计数** |
| SDL3 原地 A/B：创建窗口时传 `SDL_WINDOW_RESIZABLE` | 手势 5 轮、浮动退出确认 3 轮通过；非黑阈值提高到 0.95，每轮实测 1.0；方向请求不再往返 |
| 切回 raylib 后重复运行 | 追加 5 轮通过，IDE 未重启，未残留 SDL/NativeActivity 图形进程 |

因此首轮候选完整 IDE 的有效运行/退出回归共 **83 轮**（raylib 67、SDL2 8、修正后的 SDL3 8），
不包括 SDL3 半屏组、编译取消、RikkaHub 页面或独立导出 APK。SDL 模板未增加触摸反馈，
本表不声称 SDL2/SDL3 触摸已验证。

SDL3 **1.0.4** 已在 Registry 的 `ebc15f30176deeaa4e8597ead663233b8e2cdc88` 发布，
发布模板替换项目名后与 A/B 的 `main.cpp` 逐字节一致。主仓库模板、ZIP、`.tinaplug` 和回归测试同步；
Registry build / validate 均通过，旧制品没有覆盖。A/B 不冒充“市场重新安装 1.0.4 后新建”的验收。

证据：`api36-host/sdl2-gesture-01`、`sdl2-overlay-01`、`sdl3-gesture-01`、
`sdl3-resizable-ab-01`、`sdl3-resizable-overlay-ab-01`、`raylib-after-sdl-01`。

### 7.4 RikkaHub 模拟器回归

使用仅面向测试 AVD 的本机 OpenAI 兼容 mock 与虚拟凭据，不连接真实模型供应商：

- 模型页反复打开/关闭 **10 轮**，能力 Vector 图标正常，未出现附件中的 Release 模型列表 NPE。
- 3 次流式请求：2 次完成、1 次停止；中文、emoji、表格和代码显示正常；停止后保留部分回复，
  mock 记录 SSE 连接取消，再次发送可成功。Home/最近任务恢复后回复完整，IDE PID 不变。
- 原生 Markdown 显示正常。Android WebView `133.0.6943.137` 的真实 IDE 网页预览在暖缓存下
  连续开关 **5 轮**，中文、表格与 Mermaid 可见。
- 首次在线网页预览曾长时间白页/显示进度，随后成功；没有足够证据归因为新的生命周期缺陷，
  **未将其标为已修复或冷加载通过**。独立 WebView 探针不代替真实 IDE 验收。
- 系统 night mode 切换未使 RikkaHub 实际进入深色，故深色、语言切换、预览 PNG 导出和
  Android WebView 注入负例仍不能记为通过。

证据：`rikka-api36/models-01`、`models-03`、`web-01` 与 `mock-stats.json`。
`models-02` 出现空模型列表断言失败，没有计入通过次数；保留原报告，不将其冒充成功页面回归。

### 7.5 导出 APK 与第二系统对照

- 首轮候选经完整 IDE UI 导出 `com.tinaide.test.raylibr3`，系统安装页应用名正确，安装并首次打开有动画。
- API 36 连续打开测试的首组仅完成 1 轮即遇 ADB 连接重置；第二组在恢复连接后出现启动超时，
  实际停留启动页，日志含 `Activity destroy timeout` 和 raylib shader 初始化失败。这两组均不计通过。
- API 34 / SwiftShader 的旧导出壳对照：桌面 launcher flags 5 轮、普通显式 Intent 5 轮均有动画，
  进程为同一 `5650`；当时 IDE 尚未启动，不能说验证了 IDE 主 PID 不变，也不能以这个系统通过覆盖 API 36 的失败。
- 对应修复为单实例入口、明确 Back 退出，以及两个图形导出壳共享的独立线程退出保护；
  必须重建随包模板，再单独验收本地 A/B 与完整 IDE 新导出产物，不能把旧 APK 当成修复后 APK。
- 新模板本地 A/B 在 API 36 / host GPU 下通过 launcher 5 轮、普通显式 Intent 5 轮；
  每轮画面非黑比例 1.0 且动画变化，Back 后 PID 消失，IDE PID `3218` 保持不变。
  同一次运行连续投递 6 次 launcher Intent 只保留 1 个 Activity / 同一 PID；Home 后 30 秒恢复继续动画，
  明确 Back 后退出。证据为 `api36-export/fixed-ab-launcher-01`、`fixed-ab-intent-01`、
  `fixed-ab-home-single-task-02`。此 APK 只替换模板壳，保留原导出 APK 的逐文件同哈希 `.so`，
  使用公开模板调试证书；它仍不冒充最终 Release 经 IDE UI 重新导出的验收。

第二 AVD 为 `tina_release_r3_api34`，Android 14 / API 34 / x86_64，720×1600、density 280，
SwiftShader，模拟器实际提升内存到 2560 MB。C 盘无法创建 Google Play 镜像所需 userdata，
因此只将该新建测试 AVD 的可写镜像放在 `D:/Programs/Android/Sdk/avd-test-data/tina_release_r3_api34/`；
没有移动项目构建输出、删除其他 AVD 或清除 IDE 数据。首轮候选全新安装、工具链部署已到 Ready 页面。

### 7.6 独立夹具与未覆盖边界

- r3 x86_64 独立夹具：手势 5、Back 5、早退 5、拒绝过期票据 5、Home 恢复 1；
  ARM64 历史独立夹具手势 5。它们不替代上述完整 IDE 测试，也不算本轮真机验证。
- 真实供应商网络、音频/控制器、低内存、厂商系统、ARM64 GPU 和 16 KB 页设备未因此验证。
  用户选择本轮只用模拟器，不要求真机配合；X11/XFCE 也不扩大为已验证功能。
- 旧签名 Debug AVD 保留原数据，未为了安装 Release 而卸载；本轮全新 Release 安装不代表
  旧正式版本覆盖升级、历史聊天/项目迁移已经完整验收。
- 不创建 tag 或 GitHub Release；源码推送与公开发布 APK 是不同操作。

### 7.7 保留的测试驱动与环境异常

不删除失败证据或将其混为产品修复：

- `gesture-02` 的旧脚本曾报告 PID 不一致；当时实际 PID `6973` 仍在绘制，日志没有相应崩溃。
  旧脚本未保存起始 PID，不能编造具体原因。增加 PID 采样轨迹、稳定双采样与启动记录对照后重测通过。
- `overlay-01` 只点浮球、未确认正常的 Return 对话框，驱动超时；补上确认后重测通过。
- `cancel-01` 使用的 CMake 自调用没有产生预期延迟，取 UI 时游戏已启动；
  改为有结果检查的 Android `sleep` 后，`cancel-02` 三轮通过。
- 外部替换测试源码触发了正常的 File Conflict 对话框，明确选择 Reload External Version 后继续，
  未用覆盖外部修改来掩盖文件冲突。
- 模板/测试准备曾因 Kotlin daemon 连接失败、宿主内存压力以及构建任务超时中断；这些不记为编译通过。
  导出模板重建还暴露了 SDL Java 桥接缺少新版 AndroidX Core 的实际编译错误，已补同版本依赖；
  `checkApkTemplatesSync` 增加与 `buildApkTemplates` 同次执行的先后约束，避免在拷贝前误判。
- 模板源码曾混合 CRLF/LF，而 checksum 按字节计算。针对模板源码与 SDL 桥接的 `.gitattributes`
  固定 LF，重建模板与校验清单，避免 Windows 构建后 Linux CI 误判不同步。
- `fixed-ab-home-single-task-01` 的驱动只匹配 `Hist #`，API 36 实际输出为 `Hist  #`；
  原始 Activity dump 证实只有 1 个实例。改为接受可变空白后在新报告目录重跑通过，未修改产品来绕过断言。

新增文档、样例和 JSON 均使用 UTF-8 无 BOM；APK、日志、截图、测试用虚拟渠道与本地辅助脚本不提交。

### 7.8 最终复测拦截的多 ABI 漏库候选

2026-11-02，源码 `7b52f6cd5dba6b8befce614204d04f59fe84c2a2` 的双 ABI 重建完成
（`release-all-abi-final-v2.log`，50 分 20 秒）。但 Build ID
`7b52f6cd5d-20261102000235` 的 x86_64 包在 API 36 覆盖安装后无法打开已有项目，
日志明确报告 `libandroid-tree-sitter.so` 缺失，**因此拒绝作为最终交付包**。

- 该失败 APK 的 SHA-256 为 `e603e502c027c5db324bd9deeb90501218fad5540e2ed2b8777e854b5054455c`。
- 签名、许可、R8 Vector 路径、资源与导出模板哈希检查曾通过，但原检查没有覆盖 JNI 库完整性，
  不能据此判断 APK 可运行。`verification-final/` 保留的是这一轮旧检查，不覆盖本节的失败结论。
- APK 实际还漏了其余 16 个 Tree-sitter grammar 库、RikkaHub `libworkspace.so` 和 `libXlorie.so`，
  合计 19 个必需库；不是用户项目损坏，也不是 raylib 的第二次运行黑屏。
- 根因是 included build 看不到宿主 `AllAbi` 任务，或只识别显式属性/CI，回退为 ARM64。
  三处原生构建入口现在识别宿主请求链，显式 `tina.allAbi=false` 优先；单 ABI 选择与
  Android Studio 的 `android.injected.build.abi` 保持一致。
- 新增 `check_apk_native_libs.py` 校验 26 个必需 runtime/grammar 库、重复路径、ABI 和 ELF 机器类型，
  并接入 Release 上传前检查。8 个新增检查测试通过，全套 Python checks 现为 **55 项通过**。
- 证据：`api36-final-ide-logcat.txt`、`native-gate-before-x86_64.txt`、
  `python-checks-native-gate.log`；既有项目、聊天和 AVD 数据均保留，未用清空数据绕过问题。

ABI 探针最初的整 App dry-run 超过 60 秒，后改为配置实际 native 模块并完成构建准备；
超时不计为通过。准备工作中有一次 library Release 配置触发自动递增，已精确恢复本轮
`0.18.39 / 1840`，随后所有构建固定 `-Ptina.autoIncrementReleaseVersion=false`，不发布临时的 0.18.40。
