# RikkaHub 纯 AGPL-3.0 许可同步与分发检查

核验日期：2026-11-01。**已解除“非商业 / ≤10 用户附加限制”这一已知分发阻塞**：
同步的是作者公开发布的许可变更，不是自行删除条款或关闭发布门禁。
本记录只覆盖该阻塞的解决依据；Release 真机验收与对应源码交付仍须完成。

## 1. 可复核的上游依据

| 证据 | 结果 |
| --- | --- |
| 官方上游 | [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) |
| 许可变更 | [`65a63e4ec9c649bd5404ebcbbedb55aad5741d84`](https://github.com/rikkahub/rikkahub/commit/65a63e4ec9c649bd5404ebcbbedb55aad5741d84) |
| 作者与日期 | re-ovo，2026-07-18；提交标题 `docs(license): 改为纯 AGPL-3.0 许可证` |
| 变更范围 | `LICENSE` 与三份 README；以 GNU AGPL 第 3 版全文替换分段双重许可 |
| 上游可达性 | 该提交是核验时 `upstream/master`（`a5db9c9b770f316c76136970aa01a9d790e10e51`）的祖先，两者 LICENSE 相同 |
| LICENSE Git blob | `be3f7b28e564e7dd05eaf59d64adba1a4065ac0e` |
| LICENSE SHA-256 | `0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0`（UTF-8，换行规范为 LF） |
| 本地回移 | 子模块 `f10246eb`，保留原作者和 `cherry-pick -x` 上游标记 |
| 本次可获取源码 | [TinaIDE fork `7e9862c3279509a8965b8dcb1f6b6f458be96d32`](https://github.com/wuxianggujun/rikkahub/tree/7e9862c3279509a8965b8dcb1f6b6f458be96d32)，已推送 `tinaide-build-compatibility` |

结论：当前 fork 不再包含原来的非商业和用户数限制。保留 TinaIDE 嵌入适配、
2026-10-31 的资源与网页预览修复；**没有将 RikkaHub 整体升级至 3.x**。
完整回移记录见 [子模块维护记录](../../external/rikkahub/embedded/RESOURCE_MAINTENANCE.md)。

在子模块目录可再次核验（需要配置官方 `upstream` remote）：

```bash
git fetch upstream --no-tags
git show --stat 65a63e4ec9c649bd5404ebcbbedb55aad5741d84
git merge-base --is-ancestor 65a63e4ec9c649bd5404ebcbbedb55aad5741d84 upstream/master
git diff 65a63e4ec9c649bd5404ebcbbedb55aad5741d84 -- LICENSE
```

最后一条预期无差异；主仓库的哈希检查不依赖联网。

## 2. 组合分发的许可边界

- TinaIDE 自有代码仍按 [GPL-3.0-or-later](../../LICENSE) 许可；未将 termux-x11 等第三方代码改成 AGPL。
- RikkaHub covered 部分按 [AGPL-3.0](../../external/rikkahub/LICENSE) 许可；保守使用 SPDX `AGPL-3.0-only`，
  不把未明示的 `or-later` 权利当成事实，也不把它改成 GPL 或 MIT。
- [GPLv3 第 13 条](https://www.gnu.org/licenses/gpl-3.0.html#section13) 和
  [AGPLv3 第 13 条](https://www.gnu.org/licenses/agpl-3.0.html#section13) 明文允许这两类作品链接/组合。
  GPL 部分继续适用 GPL，AGPL 部分继续适用 AGPL；若提供远程网络交互，须履行适用的 AGPL 源码提供义务。
- 分发二进制仍须提供匹配该产物的完整 Corresponding Source、构建脚本、修改说明、版权及许可文本，
  不能将“门禁通过”解释为可闭源分发或免除其他组件义务。

## 3. 已落地的防回退与应用内展示

复用既有 `OpenSourceLicensesActivity` 及其 `res/raw/aboutlibraries.json`，不新增许可页面或依赖。
**设置 → 关于 → 开源组件与许可证 → RikkaHub (TinaIDE embedded)** 可离线阅读 AGPL 全文，
并打开包含 TinaIDE 适配的 fork 源码；TinaIDE 条目同时包含宿主 GPL 全文。
许可原文不翻译，JSON 和文档均保存为 UTF-8；既有中英文 UI 资源继续负责界面文案。

[分发策略](../../tools/checks/distribution-policy.json) 从 `blocked` 改为 `reviewed`，并要求：

1. 仓库内存在本审查记录；路径不得逃逸仓库。
2. 检出的 `external/rikkahub/LICENSE` 与上述 SHA-256 一致。
3. 应用内条目唯一，许可标识与源码 URL 正确，显示的全文与源码 LICENSE 哈希相同。
4. 缺失子模块、文本回退、漏带全文、错误链接、损坏配置或未知状态均失败，不能静默放行。
5. CI 在上传 Debug / Release APK 前再次使用 `--apk` 检查最终 ZIP 中的 raw 资源内容，
   即使 AAPT 缩短资源文件名也按字节哈希核对；不能仅凭源码文件存在放行旧 APK。

CI 在门禁 job 中检出 RikkaHub 子模块，PR/core checks 同时执行真实门禁和反例测试。
本地开发构建仍可运行；对外上传/发布必须运行以下检查：

```bash
python -m unittest discover -s tools/checks -p 'test_distribution.py' -v
python tools/checks/check_distribution.py
```

检查完成的 `0.18.38` ARM64 Release 时，额外执行：

```bash
python tools/checks/check_distribution.py --apk app/build/outputs/apk/arm64/release/TinaIDE-0.18.38-arm64-v8a-release.apk
```

## 4. 随 APK 提供精确对应源码

公开 Release 工作流会在 APK 旁边附上**该次提交 SHA**的源码链接和递归检出命令。
GitHub 自动生成的 source ZIP 不含子模块，不能把它当成完整源码交付。
接收者须按对应 Release 页面给出的精确 SHA 检出，并执行：

```bash
git submodule update --init --recursive
```

手动分发 APK 时，同样应附精确主仓库提交、子模块指针、`COPYRIGHT.md` / `NOTICE.md`
和 [构建指南](../开发指南.md)，确保所有指向的提交保持公开可获取。
提交顺序保持“先提交/推送子模块，再提交/推送主仓库”。

## 5. 历史与回滚边界

`0.18.37` 是旧许可证/旧门禁下构建的历史产物，本记录不追溯宣称该旧 APK 已修复；
使用同步许可证和 ARSCLib/R8 修复后重新构建的产物进行验收。
若后续上游许可证正文改变，必须重新审查并同步 notice 与 policy，不能只改哈希来消除报错。
如回退到旧受限 LICENSE，门禁应重新阻断；不要回退整个子模块而丢失宿主适配或安全补丁。
