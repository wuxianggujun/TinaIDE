# TinaIDE 分支管理指南

> 最后人工核验：2026-09-09

本文档只保留长期有效的分支规范，不再维护“历史分支快照/统计清单”。

---

## 1. 分支模型

### 长期分支

- `main`：稳定发布分支，只接收已验证变更。
- `dev`：日常集成分支，功能分支合并入口。

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

- 默认使用 `Squash and Merge`，保持主干历史简洁。
- 禁止直接推送到 `main` 和 `dev`。
- `release/*` 合并到 `main` 后，需要同步回合并到 `dev`。

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

## 8. 发布流程（简版）

1. 从 `dev` 切 `release/<version>`。
2. 在 `release/*` 只做发布阻断问题修复。
3. 验证通过后合并到 `main` 并打 Tag。
4. 将 `main` 的发布修复回合并到 `dev`。

本仓库包含 Git submodule（如 `external/rikkahub`、`external/termux-proot`）。涉及子模块指针变更时，必须先提交并推送子模块，再提交主仓库指针；打 Tag 前用 `git submodule status --recursive` 校验，避免 CI 在 `git submodule update --recursive` 阶段因 `not our ref` 失败。

---

## 9. 文档维护约定

- 本文档只维护“规则”，不维护具体分支名单。
- 具体分支现状请使用 Git 命令实时查看：

```bash
git branch -a
git for-each-ref --sort=-committerdate refs/heads/ --format="%(refname:short) %(committerdate:short)"
```
