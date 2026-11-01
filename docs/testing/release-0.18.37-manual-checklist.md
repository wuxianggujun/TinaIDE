# 0.18.37 Release 真机手测清单

编写日期：2026-11-01。**以下是待执行的真机验收项，不是已通过记录。**

建议先做 P0，再按实际使用范围做 P1。本次重点是 RikkaHub 资源更新、网页预览、WebView 释放，
同时覆盖完整 Release 的 R8/JNI、旧数据升级和 IDE 核心功能。
本次没有将 RikkaHub 整体升级至 3.x，也没有解除现有分发门禁。

此处保留 `0.18.37` 构建时的历史状态。后续许可同步与防回退检查见
[RikkaHub 许可审查](../licensing/rikkahub-agpl-review.md)，不要据此把本页旧 APK 当成包含新修复的产物。

## 1. 准备正确的安装包和设备

| 项目 | 本次本地构建 |
| --- | --- |
| APK | `app/build/outputs/apk/arm64/release/TinaIDE-0.18.37-arm64-v8a-release.apk` |
| 版本 | `0.18.37`，APK versionCode `18382`，基础版本码 `1838` |
| ABI | `arm64-v8a`，不要在 x86_64 模拟器上验收此包 |
| 体积 | 151,441,701 字节，144.43 MiB |
| SHA-256 | `fa5ba4814be27e1eb7b22106d62d29939fc5bd4e4774d49a48f29e438ed83e82` |
| Build ID | `29e7f83487-20261031235611` |
| mapping | `app/mappings/0.18.37-29e7f83487-20261031235611/arm64Release/mapping.txt` |

已完成的自动/构建验证：embedded Debug 编译与 AAR、ARM64 Release 构建、Release Lint、
R8/资源压缩、APK v2 签名和 zipalign；资源契约 10 项及桌面 Chrome 预览回归通过。
这些结果不能替代 Android System WebView、设备权限、Activity 生命周期和 JNI 的真机验证。

1. 先在有 `0.18.36` 测试数据的 ARM64 设备上**覆盖安装**，不要先卸载或清数据。
   准备一个可修改的测试项目、一条历史聊天和已配置的测试渠道，记录原有语言/主题。
2. 再用另一台设备或独立 Android 用户做全新安装，验证首次启动；不要破坏第一组升级测试数据。
3. 至少记录 Android 版本与 Android System WebView/Chrome 提供程序版本。
   条件允许时覆盖一台旧系统设备及一台 Android 14–16 设备；Android 9 是最低支持版本。
4. 网页预览会访问 CDN，首次在线验收需要网络可访问 `esm.sh` 和 `cdn.jsdelivr.net`。
   普通原生 Markdown 与网页预览是两条路径，必须分别测试。
5. 只用测试数据。渠道密钥不需要粘贴进样例、日志或问题报告。

电脑安装的 PowerShell 示例（在 TinaIDE 仓库根目录运行，SDK 路径按本机配置；
多设备时在 `adb` 子命令前加 `-s` 和实际序列号）：

```powershell
$adb = 'D:\Programs\Android\Sdk\platform-tools\adb.exe'
$apk = Join-Path (Get-Location).Path 'app\build\outputs\apk\arm64\release\TinaIDE-0.18.37-arm64-v8a-release.apk'
& $adb devices
& $adb install -r $apk
```

预期为 `Success`。若签名不匹配，核对是否装过 Debug/其他签名的包，不要通过卸载正式数据来掩盖升级失败。
也可以直接在设备文件管理器打开此 APK 覆盖安装。

## 2. P0：必须先通过

| 编号 | 怎么操作 | 通过标准 |
| --- | --- | --- |
| P0-01 升级与启动 | 覆盖安装后冷启动；打开旧项目、旧聊天、渠道设置；保存测试文件后结束 App 并重新打开 | 显示 0.18.37；项目、聊天和渠道可用；语言/主题保留；无启动崩溃或数据丢失 |
| P0-02 全新安装 | 在独立测试环境安装，完成首次启动和权限流程，新建一个 C/C++ 项目 | 首页/设置正常，默认 native toolchain 可准备并使用，不要求先安装 Ubuntu/PRoot |
| P0-03 AI 基础聊天 | 从编辑器侧边栏打开 AI；发送一句中文，再请求较长回复并中途停止；随后继续发消息，切换现有模型/历史会话 | 流式更新、停止和继续发送可用；旧会话能加载；返回编辑器后原文件仍在；没有闪退或明显串会话 |
| P0-04 原生 Markdown | 用下方样例替换一条测试助手消息，先在聊天气泡查看，不打开网页视图；复制代码 | 中文/emoji、表格、代码内容可读；代码字体正常；复制不丢字符；没有缺资源异常 |
| P0-05 网页预览 | 消息下方 **⋮ → 网页视图渲染**；检查下方样例的标题、表格、复选框、代码、公式、化学式、流程图 | 内容完整；`E = mc²`、H₂O 和流程图正常；不显示 `{{...}}` 未替换变量；联网情况下没有持续白屏 |
| P0-06 两个 Mermaid 入口 | 在聊天里的 Mermaid 代码块切换到图表预览，再点图表预览图标打开独立页面；返回后点下载图标 | 两条路径都能显示中文节点和连线；导出的 PNG 实际存在、非空、能用相册打开，不只根据“导出成功”提示判定 |
| P0-07 WebView 生命周期 | A/B 两条不同测试消息交替打开网页预览并返回，各做 10 次；滚动使内联图表移出/重新进入屏幕；期间切后台再回来，重新导出图片 | 无崩溃、ANR、已销毁 WebView 报错、旧内容串到新消息；重开和导出仍可用。设备支持时再测横竖屏/分屏 |
| P0-08 安全与失败输入 | 用下方安全边界样例做“网页视图渲染”；再单独预览一条错误 Mermaid 样例 | 页面标题仍可见，不出现脚本插入的标记或 iframe 内容；错误图表可显示失败提示，但 App 仍可返回/继续聊天 |
| P0-09 IDE 编译运行 | 新建临时 C++ 控制台项目，用下方 `main.cpp` 编译运行；故意删掉分号制造错误，再修复重跑 | 默认 native toolchain 编译成功；输出中文且退出码为 0；错误诊断可定位源文件；修复后恢复，不依赖 PRoot |
| P0-10 编辑与保存 | 编辑中文/emoji，多行选取、撤销/重做、保存；同一文件打开两个分屏，关闭一个后继续编辑；保存并关闭再打开 | 文本和磁盘内容一致；另一个分屏不被误销毁；无空白编辑器、保存丢失或高亮不更新 |
| P0-11 LSP | 在编译成功的 C++ 项目等待 clangd 就绪，验证补全、悬停、跳转定义/返回、语法诊断 | 结果与当前文件对应；弹窗位置合理；编辑并修正错误后诊断更新，没有一直停留的旧状态 |

P0 任一失败先记录复现，不把“重装后能用”或“桌面浏览器通过”当作通过。
没有设备或没有配置对应能力时，记录“未测”，而不是勾选通过。

## 3. 可以直接粘贴的预览样例

先在专用测试会话取得一条助手回复，点该消息下方 **⋮ → 编辑**，用以下内容替换并保存。
不要把整个样例再包进一个 Markdown 代码块；也不需要让模型重新生成一份，以免模型修改样例。
先验收原生显示，再从同一条消息的菜单选择“网页视图渲染”。

````markdown
# 中文预览回归 😀

**粗体**、*斜体*、中文与 emoji 🌍。

| 功能 | 预期 |
| --- | --- |
| 中文 | 不乱码 |
| 表格 | 两列对齐 |

- [x] 已完成
- [ ] 待完成

```cpp
#include <iostream>
int main() {
    std::cout << "你好，TinaIDE 😀" << std::endl;
    return 0;
}
```

行内公式：$E = mc^2$。化学式：$\ce{H2O}$。

$$
\int_0^1 x^2\,dx = \frac{1}{3}
$$

<details><summary>安全 HTML 折叠区域</summary>展开后应看到这段中文。</details>

```mermaid
flowchart LR
    A[编辑代码] --> B[编译]
    B --> C[运行成功]
```
````

安全边界样例单独作为一条测试消息；页面应该保留“安全边界回归”标题，
不应新插入 `INJECTION_EXECUTED` 标题或显示 `IFRAME_RENDERED` 内容。
页面不应因样例中的 CSS 隐藏。脚本 URL 应被移除/不可执行，不能据此启动脚本。
这只是定点手测，不是完整的漏洞扫描。

```markdown
# 安全边界回归

这行普通文字必须仍然可见。

<img src=x onerror="document.body.insertAdjacentHTML('afterbegin','<h1>INJECTION_EXECUTED</h1>')">
<style>body { display: none; }</style>
<iframe srcdoc="<h1>IFRAME_RENDERED</h1>"></iframe>
<a href="javascript:document.body.innerHTML='INJECTION_EXECUTED'">不可信脚本链接</a>
```

另建一条消息粘贴以下错误 Mermaid 内容后做网页预览，预期显示可理解的渲染失败提示并可正常返回：

````markdown
```mermaid
this is not a valid mermaid diagram
```
````

IDE 原生编译的 `main.cpp` 样例（源文件保存为 UTF-8）：

```cpp
#include <iostream>

int main() {
    std::cout << "你好，TinaIDE 0.18.37" << std::endl;
    return 0;
}
```

## 4. P1：完整版本回归

| 范围 | 操作 | 通过标准/边界 |
| --- | --- | --- |
| 浅色、深色、语言 | 中英文各查看一次 AI 聊天/设置和网页预览；切换明暗主题再重开预览 | 字体可读，背景/文字有对比；新的失败提示不乱码；主题切换不导致已销毁 WebView 被继续调用 |
| 占位图、图标 | 查看新会话/图片占位状态；在现有模型列表查看 Gemma、Kimi、Qwen 图标；查看代码字体 | 无丢图、黑块或资源异常；未配置的模型记录未测，不必为测图标购买 API 调用 |
| 网络异常 | 在线完成一次预览，再断网重开并返回；恢复网络后重新打开。再用全新安装环境观察未缓存依赖的断网首开 | CDN 未缓存时允许网页内容暂时无法加载，这是现有限制，不算离线支持；无论加载与否都应可返回、恢复网络后可重试；联网后持续白屏需报错 |
| 图片和分享 | 使用非敏感测试图片打开/关闭预览；导出 Mermaid PNG 并通过系统分享界面交给图片查看器；首次权限拒绝后再授权重试 | 实际文件有效；权限拒绝可恢复，无 FileProvider、JNI、R8 反射相关崩溃；不要只看成功 Toast |
| 项目与文件 | 在临时项目中新建、重命名、搜索、保存文件，测试长文件名/中文路径；打开已有项目 | 文件内容不丢失，文件树与编辑器同步；只删除临时测试文件 |
| 返回键与分屏 | 展开菜单/抽屉/底部面板后按返回；两分屏编辑同文件；有未保存修改时关闭工作区 | 优先关闭临时 UI；不会意外退出项目；未保存内容有正确保护。详见现有编辑器生命周期文档 |
| 插件 | 在测试项目安装/启用可信测试插件，调用一次，再禁用、重开 App | 权限流程、命令入口、禁用状态正常；无序列化或 Lua/JNI 加载崩溃；不用未知来源插件做验收 |
| Git/SSH | 用独立测试仓库验证读取状态、diff、拉取；已有 SSH 配置在重启后仍可用 | 不丢密钥或元数据；失败有可定位信息；不拿生产分支做破坏性测试 |
| 帮助、教程、设置 | 查看帮助文章、教程列表，搜索一项内容；修改一个编辑器偏好并重启 | 页面加载与搜索正常，设置持久化，无资源缺失 |
| 可选原生图形 | 使用现有 SDL2/SDL3/raylib 测试项目运行，测试返回退出与再次运行 | 按 [Native 图形运行回归](native-graphics-runtime.md) 验证帧循环及宿主隔离；运行与导出 APK 分别记录 |
| 可选 Ubuntu/X11 | 仅在已准备 Ubuntu 24.04 环境的设备上，按现有文档尝试启动和停止图形环境 | 单独记录结果；不能因普通 C++/AI 测试通过就宣称 X11/完整 XFCE 已真机验证；不测试已移除的 Alpine |

相关编辑器细项：[保存/关闭生命周期](editor-save-close-lifecycle.md)、
[设备基线](editor-device-baseline.md)、[渲染性能](editor-render-performance.md)。

## 5. 记录问题，别只写“闪退了”

每项使用 `通过 / 失败 / 未测 / 不适用` 之一，并记录：

- 用例编号，升级安装还是全新安装。
- 手机型号、Android 版本、WebView 提供程序与版本、语言/主题、网络条件。
- App 版本、上述 Build ID；最短复现步骤、预期与实际现象、可复现次数。
- 非敏感测试内容、截图/录屏；若崩溃，保留崩溃报告或对应时段的进程日志。
- 日志分享前移除密钥、Authorization、用户聊天/项目内容；不要附带渠道配置导出。

可选 ADB 日志采集：在仓库根目录打开 PowerShell，先打开 App 并记录 PID，然后复现问题，
最后只导出这个 PID 的日志。
代码使用 UTF-8 无 BOM；`-d` 不会清空设备日志。

```powershell
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $OutputEncoding
$adb = 'D:\Programs\Android\Sdk\platform-tools\adb.exe'
$reportDir = Join-Path (Get-Location).Path 'app\build\reports'
$appProcessId = (& $adb shell pidof -s com.wuxianggujun.tinaide | Out-String).Trim()
if ($appProcessId -notmatch '^\d+$') { throw '请先打开 TinaIDE，再记录进程 PID。' }

# 保留当前终端，完成问题复现后再运行下面几行。
$logLines = & $adb logcat -d --pid=$appProcessId -v threadtime
[System.IO.Directory]::CreateDirectory($reportDir) | Out-Null
$logPath = Join-Path $reportDir 'manual-release-0.18.37-logcat.txt'
[System.IO.File]::WriteAllLines($logPath, [string[]]$logLines, [System.Text.UTF8Encoding]::new($false))
```

App 重启后 PID 会变化，需要重新记录；主进程日志不覆盖所有 WebView/原生子进程故障，
此类问题同时保存 App 的崩溃报告。本包在源码提交前构建，反混淆使用上面的 Build ID 与对应 mapping，
不要用随后生成的 Git 提交哈希猜测 mapping 目录。

P0 全部通过只是本次功能验收的一部分，不自动解除 RikkaHub 授权/分发门禁。
