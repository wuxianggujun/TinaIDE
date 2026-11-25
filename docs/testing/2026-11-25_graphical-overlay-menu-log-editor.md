# 图形悬浮菜单锚点与日志编辑器视图

本轮日期：2026-11-25（Asia/Shanghai）。继续原仓库的 `dev` 分支与原 worktree，不新建工作区，保留上一轮尚未提交的项目卡片修改。

## 1. 现象与根因

- 点击控制球后，菜单总在屏幕左上角，移动控制球后重开仍不跟随。图形宿主是 Android 原生 `PopupWindow`，但原菜单使用默认 Compose `DropdownMenu` 锚点。Compose 对嵌套 Popup 的识别没有覆盖这个原生宿主，局部窗口原点被当成菜单定位坐标。
- 日志正文仍是 `LazyColumn + Text`，外层水平 8 dp／垂直 6 dp，加上内部 8 dp；每条日志最多显示 3 行并使用省略号，没有行号或换行选项。
- 旧自动滚动仅监听列表大小；达到 500 条之后大小不再改变，后续输出无法继续驱动该滚动效果。

定位时核对了项目使用的 Compose UI 1.11.2 [官方源码包](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.11.2/ui-android-1.11.2-sources.jar)，尤其是 `AndroidPopup.android.kt` 的嵌套识别与 `updateParentBounds()`／`updatePosition()`。源码副本只存放在本轮 `app/build/verification`，不新增或替换依赖。

## 2. 落地边界

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/com/wuxianggujun/tinaide/ui/runtime/GraphicalRuntimeMenuPositionProvider.kt` | 使用显式屏幕锚点定位菜单，RTL 对齐、下方／上方选择与安全区限幅 |
| `app/src/main/java/com/wuxianggujun/tinaide/ui/runtime/GraphicalOverlayWindowState.kt` | 复用原窗口安全区，向菜单提供安全边界，不改变保存快照格式 |
| `app/src/main/java/com/wuxianggujun/tinaide/ui/runtime/GraphicalRuntimeLogDocument.kt` | 单个 Rope 文档与 EditorState，增量追加／裁剪、滚动与换行配置、关闭释放 |
| `app/src/main/java/com/wuxianggujun/tinaide/ui/runtime/GraphicalRuntimeLogcat.kt` | 当前 PID 的 UTF-8 捕获、有限通道与批次、主线程交付、取消时解阻塞并销毁自建 logcat 进程 |
| `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/GraphicalRuntimeOverlay.kt` | 按钮锚点、菜单、日志只读编辑器与 UI 状态 |

菜单数据流为：`窗口屏幕位置 + 按钮在窗口内的 bounds → PositionProvider → Compose Popup / WindowManager`。控制球使用自身矩形；日志更多菜单使用实际按钮的布局矩形，不把控制球坐标硬套给另一个按钮。

日志数据流为：`PID 限定 logcat → UTF-8 reader → 有界 Channel → 主线程批次 → RopeTextBuffer → TinaCodeViewer`。

### 用户行为

- 菜单在对应按钮旁打开，靠近下边缘时优先翻到上方，横向和纵向均限制在既有安全区。
- 日志正文直接使用 `TinaCodeViewer`，即 TinaEditor 的同一渲染、样式和行缓存；没有另一套日志文本渲染器，也没有编辑输入宿主、LSP 或 Debug 条。
- 显示绝对行号，横向滚动时行号固定；默认不自动换行。更多菜单复用中英文 `settings_word_wrap` 资源提供换行开关，不修改主编辑器偏好。
- 内容留白统一为 2 dp，去掉内部额外 8 dp；标题按钮仍保持 48 dp 触摸目标。长行不再被限制为 3 行省略。
- 收起重开保留文档、位置、大小与本次运行的换行选择。查看旧日志时不强制跳回末尾；滚回末尾后继续跟随输出。
- 保留最多 500 个逻辑行，总文本最多 512 × 1024 个 UTF-16 单元，单条消息最多 8192 个单元；裁剪最旧的完整行，并避免切断 surrogate pair。不保留撤销历史。
- 捕获最多每 50 ms 交付一批、每批最多 64 条，避免逐条切主线程和滚动动画。清空只清正文，不停止捕获。
- 保留原拖动跟手、右下角缩放、最大化／还原、退出确认与窗口内外输入边界。没有改动 SDL 渲染 Surface、X11 或编辑器子模块。

## 3. 实际验证

本地命令均使用 JDK 17、`--no-daemon`，Test task 通过现有 `tools/testing/single-worker-tests.init.gradle` 限制为 60 秒。构建准备与测试执行分别计时，没有停止共享 daemon 或删除缓存。

| 验证 | 实际结果 |
| --- | --- |
| `:app:compileArm64DebugKotlin` | 成功，首轮 4m 7s |
| 几何、文档、捕获、宿主与退出定向测试 | 53 项通过，0 失败／错误／跳过；包含准备共 3m 28s |
| Compose 交互与原生 PopupWindow 锚点测试 | 16 项通过，0 失败／错误／跳过；包含准备共 4m 19s |
| 本轮图形 JVM / Robolectric 合计 | 69 项，10 个测试类，全部通过 |
| Python 设备证据解析回归 | 44 项通过；没有调用设备 |
| 国际化与维护检查 | `tools/i18n/check_all.py`、`tools/checks/check_all.py` 通过 |

新增原生锚点测试不是把 Overlay 放在普通 Activity 的原点预览：它真的创建一个 Android `PopupWindow`、设置 ViewTree owners、在其中挂 Compose，再读取菜单窗口的 `WindowManager.LayoutParams`。覆盖移动中更新菜单，以及日志更多按钮自己的锚点；几何测试另覆盖 RTL、底部翻转、视口改变和异常大内容。

日志测试覆盖默认配置、同一个编辑器／缓冲对象、不保留历史、超过 500 行继续更新、总字符上限、历史滚动位置、清空、换行切换、中文／emoji、UTF-8 捕获、读取失败，以及退出或进程创建期间取消。UI 测试验证只读语义、横向滚动、完整长消息、换行开关、收起重开与实际 2 dp 布局。

首轮测试准备发现新夹具遗漏 `FrameLayout.LayoutParams` 限定，修正后重跑；未执行的首轮不计为通过。初次 diff 检查还修掉移除旧捕获代码后留下的文件末尾多余空行。

本轮原始证据位于 `app/build/verification/graphical-overlay-20261125/`：

- `app-compile.log`、`document-menu-capture-tests-retry.log`、`overlay-ui-popup-tests.log`。
- `core-junit/` 与 `ui-junit/` 分开保存原始报告，避免后一次过滤测试覆盖前一次的 XML。
- `core-test-summary.json`、`ui-test-summary.json`、`graphical-test-summary.json`：逐类核对测试个数、失败、错误和跳过。
- `python-regression-tests.log`、`i18n-check.log`、`maintenance-check.log`。

新增四个测试类已加入 `.github/workflows/dev-static-check.yml` 的宿主定向回归；本地通过不等于尚未推送的 CI 已执行。

## 4. 真机验收与发布边界

修复与 JVM 验证阶段没有构建或安装新 APK，没有启动／关闭任何模拟器，也没有修改 `editor-kit`；当时 `version.properties` 保持前一轮 Release 留下的 `0.19.7 / 1908`。随后用户明确要求构建 Release 并安装到真机，结果见第 5 节；没有打 Tag 或发布。

包含本轮修复的候选安装后（本次安装记录见第 5 节），应在真机执行：

1. 将控制球移动到四角与屏幕中部，分别重开菜单；拖动日志窗口后重开更多菜单，检查对应按钮锚点与安全区。
2. 输出长行、中文／emoji 和超过 500 行的连续日志；检查固定行号、横向滚动、不显示 Debug 条及不断更新。
3. 反复切换自动换行，缩放／最大化／还原／收起重开，确认选择与文档保留。
4. 查看历史时继续输出，然后滚回末尾；清空后继续输出，检查跟随与捕获不停止。
5. 检查原程序触摸、返回确认的取消／确认、退出后的捕获回收，以及重新运行。

以上 JVM 框架窗口验证不是 ARM64 真机、SDL2／SDL3／raylib 或 Release/R8 的实际设备验收。

## 5. 用户续接：签名 Release 与真机覆盖安装

按用户明确请求，继续原 `dev` 工作区构建并安装；没有新建 worktree、提交推送、打 Tag 或创建 GitHub Release。包中包含本轮悬浮菜单／日志编辑器和上一轮项目卡片修改。构建期间核对 25 个既有修改／新增文件的 SHA-256 未变，只有 `version.properties` 按现有机制递增；`editor-kit` 保持干净。

| 项目 | 实际结果 |
| --- | --- |
| 源码基线 | 主仓库 HEAD `0a4772104b614f2b4340a6af0fb423b62f097ff4`，包含原工作区尚未提交的修改；逐文件指纹见 `preflight.json` |
| 构建 | `:app:assembleArm64Release` 成功，24m 17s；R8 压缩／混淆与资源裁剪完成，Release Lint 通过 |
| 版本 | `version.properties` 为 `0.19.8 / 1909`；ARM64 APK 的实际 manifest 为 `0.19.8 / 19092` |
| 手机 | PMS110，ARM64，API 37，Android user 0；无线目标 `192.168.2.185:44495`，已排除模拟器 |
| 安装时间 | 2026-11-25 12:38:01；安装后核验完成于 12:39:51，Asia/Shanghai |
| 安装前／后 | `0.19.7 / 19082` → `0.19.8 / 19092`，两者均为非 debuggable Release |
| 安装方式 | 仅一次 `adb -s <真机 serial> install -r <新 APK>` 返回 `Success`；没有卸载、清空数据或修改模拟器状态 |
| 签名 | 新 APK 签名校验通过，签名证书与手机安装前 APK 对应的本地 Release 一致 |
| 包体 | 177,303,957 bytes（169.09 MiB）；ZIP alignment、ARM64 原生运行库及 ELF 架构检查通过 |
| 许可资源 | `check_distribution.py --apk` 通过，实际 APK 含已审查的许可／notice 资源 |
| 安装一致性 | 手机 `base.apk` 的 SHA-256 与本地已签名、已验证的新 APK 完全一致 |
| 安装历史 | 当前 user 0 的首次安装时间为 `2026-11-01 13:10:17`；没有把缺失的安装前 UID 记录写成已完成数值比较 |
| R8 mapping | 生成文件与本地归档的 SHA-256 一致；未上传或提交 mapping |
| 真机功能回归 | 没有主动打开应用或执行 SDL／悬浮窗交互；第 4 节手测仍待执行，安装成功不等于功能验收全部通过 |

本次 Windows PowerShell 命令复用既有 ARM64 PRoot 预编译库，不修改构建脚本或标准模块输出目录：

```powershell
./gradlew.bat :app:assembleArm64Release '-Ptina.devAbi=arm64' '-Ptina.buildProotFromSource=false' --no-daemon --console=plain
```

APK：`app/build/outputs/apk/arm64/release/TinaIDE-0.19.8-arm64-v8a-release.apk`。本地与手机 APK 的 SHA-256：

```text
e885004f26ff64039867b9122fd57f4cf30c0317beeeba8d8d3aa433c177a31b
```

mapping 归档为 `app/mappings/0.19.8-nogit-20261125121126/arm64Release/mapping.txt`。本次插件使用 `nogit` 时间戳降级 buildId；实际源码 HEAD 与未提交修改由独立的构建前指纹记录保存。

安装后的首次校验脚本使用旧 `userId` 字段，而该手机的 `dumpsys package` 返回 `appId`，因此在安装已返回 `Success` 后出现校验断言失败。核对实际版本后改用 `appId` 和当前 Android user 的精确包 UID，再读取 `base.apk` 哈希完成校验；没有重复安装。初始失败及恢复方法保留在 `installation.json.initialPostcheck`，不把它改写成安装失败或删除该记录。

本次证据目录：`app/build/verification/release-physical-install-20261125-overlay/`。

- `preflight.json`、`build-status.json`、`release-build.log`：原工作区、逐文件指纹、构建时间及真实退出码。
- `previous-apk-identity.json`、`previous-apk-signature.log`、`release-apk-signature.log`：安装前 APK 一致性与签名证书核对。
- `release-apk-badging.log`、`release-apk-alignment.log`、`release-apk-native-libs.log`、`release-apk-distribution.log`、`release-verification.json`：实际包版本、Release 标记、包体门禁、SHA-256 与 mapping 归档。
- `install.log`、`installed-apk-sha256.log`、`installation.json`：唯一一次覆盖安装、安装后版本和哈希，以及校验脚本字段差异的恢复记录。

没有重跑上一阶段已通过的 69 项 JVM／Robolectric 用例，也没有把这些用例计作本次真机交互测试。新增验证记录与版本配置保持 UTF-8 无 BOM，并做补丁、维护和文档引用检查。
