# TinaIDE 分支管理指南

> 最后核验：2026-11-25

本文档只保留长期有效的分支规范，不再维护“历史分支快照/统计清单”。

---

## 1. 分支模型

### 长期分支

- `main`：正式发布分支，**只在发布新版本时更新**，用于承载已经验证的完整发布内容。
- `dev`：日常集成分支，功能分支合并入口。

日常功能、普通修复、文档维护和本地测试包继续在 `dev` 或短期分支推进。即使本地构建了签名 Release APK 并安装真机，也不因此更新 `main`。正式发版必须先将候选内容合并到远端 `main`，再创建版本 Tag 和 GitHub Release。

### 短期分支

- `feature/<scope>-<name>`：新功能开发。
- `fix/<scope>-<name>`：普通问题修复（进入下一个版本）。
- `hotfix/<scope>-<name>`：线上紧急修复（可直接从 `main` 切出）。
- `release/<version>`：发布准备分支（仅允许修复和版本调整）。
- `chore/<scope>-<name>`：工程维护类改动（文档、脚本、重构等）。

---

## 2. 命名规范

统一使用小写和短横线，避免中文和空格：

- `feature/editor-split-view`
- `fix/git-remote-timeout`
- `hotfix/proot-launch-recovery`
- `chore/docs-cleanup`

命名建议：

- `scope` 优先使用模块名（如 `editor`、`plugin`、`workspace`）。
- `name` 只描述一个核心意图，长度尽量控制在 3~5 个词。

---

## 3. 日常开发流程

1. 基于 `dev` 拉取最新代码。
2. 创建短期分支并完成开发。
3. 小步提交，保证每次提交可读且可回滚。
4. 本地通过必要检查（编译、测试、静态检查）。
5. 发起 PR 合并到 `dev`，至少 1 人审查通过。
6. 合并后删除远端分支，避免积压。

命令示例：

```bash
git checkout dev
git pull --ff-only origin dev
git checkout -b feature/editor-split-view
git push -u origin feature/editor-split-view
```

---

## 4. 合并策略

- 功能／修复分支合入 `dev` 时，默认使用 `Squash and Merge`。
- 发布候选合入 `main`、以及 `main` 同步回 `dev` 时，保留提交历史，使用普通合并；不要 squash／rebase 已共享的发布历史，以便核验候选提交与 Tag 的祖先关系。
- 禁止直接推送到 `main` 和 `dev`。
- `release/*` 或冻结的 `dev` 通过发布 PR 合并到 `main` 后，需要将发布变更同步回 `dev`。

---

## 5. 提交规范

推荐格式：

```text
<type>(<scope>): <summary>
```

常用 `type`：

- `feat`：新增功能
- `fix`：缺陷修复
- `refactor`：重构
- `docs`：文档调整
- `test`：测试相关
- `chore`：构建/脚本/依赖维护

---

## 6. 冲突处理

- 优先 `rebase` 到最新 `dev` 后再发起合并，减少主干冲突。
- 冲突解决后必须重新跑相关测试。
- 对高风险冲突（构建链路、远程 LSP 鉴权与密钥存储、插件隔离、文件写入）必须二次审查。

常用命令：

```bash
git fetch origin
git rebase origin/dev
# 解决冲突后
git rebase --continue
```

---

## 7. 分支清理策略

- 已合并分支：合并后 7 天内删除远端分支。
- 长期未更新分支：超过 30 天无提交，默认进入清理名单。
- 实验分支：结束后要么转正式 `feature/*`，要么归档说明后删除。

---

## 8. 正式发布流程（必须先合并 main）

**固定顺序：准备并验证候选提交 → 合并并推送到远端 `main` → 从 `main` 的发布提交打 Tag → 发布成功并核验产物 → 同步回 `dev`。** 用户要求“发布新版本”时，合入 `main` 就是任务的一部分，不能只推送 `dev` 和 Tag 后宣布完成。

### 8.1 准备和验证候选提交

1. 从 `dev` 切 `release/<version>`；如果候选范围已经冻结，也可以直接以 `dev` 发起发布 PR。准备期间只接收发布阻断修复、版本调整与必要文档。
2. 涉及子模块指针变更时，先提交并推送子模块（包括 `editor-kit`、`external/rikkahub` 等），确认引用的提交在远端可达，再提交主仓库 gitlink，避免 CI 报 `not our ref`。
3. 提交源码、最终 `version.properties`、对应版本的 `CHANGELOG.md` 和验证记录。非 Tag 的 Release 构建可能递增版本号，必须在构建后重新核对并提交最终值。
4. 按影响范围完成检查、CI 和必要的 Release／设备验证，记录已验证的候选 SHA。安装成功、单元测试通过与真机功能验收必须分别记录。

### 8.2 合并 main 后才能打 Tag

1. 发起目标为 `main` 的发布 PR，检查完整差异、子模块指针与检查结果，以保留提交历史的方式合并。
2. 确认远端 `main` 已更新；PR 仅创建、等待合并或只在本地合并，都不满足这个前置条件。
3. 获取远端并核对最终发布提交。若合并解决了冲突或引入额外源码修改，必须重新验证；不能沿用旧候选的验证结论。

```bash
git fetch origin main --tags
git status --short
git submodule status --recursive
git log -1 --oneline origin/main
git show origin/main:version.properties
```

确认工作区及子模块干净、引用提交已推送、`origin/main` 包含已验证候选，且 CHANGELOG 与版本一致。记录 `origin/main` 的最终 SHA，再创建 Tag。以下 `X.Y.Z` 必须替换为该提交的 `versionName`：

```bash
git tag -a vX.Y.Z origin/main -m "Release vX.Y.Z"
git push origin refs/tags/vX.Y.Z
```

禁止从尚未合入远端 `main` 的 `dev`、`release/*` 或其他分支直接创建正式发布 Tag。先推送 `main`、确认成功后再推送 Tag，不能用一次同时推送分支和 Tag 来替代这一步。

### 8.3 核验发布并同步 dev

`.github/workflows/release.yml` 在推送 `v*` Tag 后运行发布流程。它的触发条件本身不代表该提交已进入 `main`，所以发布者必须完成上述前置检查。

- 等待同一 Tag 的 CI 成功，核验 GitHub Release 的版本、CHANGELOG、两个 ABI APK、签名／哈希与对应源码。
- 将 `main` 的发布变更通过 PR 同步回 `dev`，保持双方共享同一发布历史；已经包含的提交不重复处理。
- 在交付记录中分别写明：远端 `main` 的合并提交、Tag 指向、Release 链接与状态、产物及设备验证范围。

发布后可核验 Tag 已在 `main` 历史内。将示例中的版本替换为本次实际版本；`merge-base` 返回 0 才表示祖先关系成立：

```bash
git fetch origin main --tags
git rev-parse origin/main
git rev-parse 'vX.Y.Z^{commit}'
git merge-base --is-ancestor 'vX.Y.Z^{commit}' origin/main
```

### 8.4 已发布版本遗漏 main 的补救

保留原公开 Tag、Release 和资产，将该版本的原始提交历史通过 PR 合入 `main`，核验 Tag 提交已成为 `main` 的祖先，并记录遗漏与补齐结果。这属于该次发布的收尾，不用于把日常未发布改动提前带入 `main`。禁止移动旧 Tag、覆盖资产或为了掩盖遗漏重新发布同一个版本。

---

## 9. 文档维护约定

- 本文档只维护“规则”，不维护具体分支名单。
- 具体分支现状请使用 Git 命令实时查看：

```bash
git branch -a
git for-each-ref --sort=-committerdate refs/heads/ --format="%(refname:short) %(committerdate:short)"
```
