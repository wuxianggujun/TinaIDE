# 2026-11-24 项目卡片紧凑布局

## 根因与设计

- 旧布局先测量整条图标／标题行，再显示标签。即使名称只有一行，也要等待 48 dp 的图标行结束，造成名称下方多余空白。
- `ProjectListCard` 改为图标、名称／标签列、菜单三个区域。名称与标签保持 4 dp 间距及相同起始缩进，整组与图标垂直居中；标签仍复用 `ProjectTagChip` 和 `FlowRow`，不会挤占名称宽度。
- 常见名称自然使用一或两行；极长名称及大字体不强行截断。使用标题换行策略，不改名称字符串、项目目录或重命名规则。
- 路径继续单行省略，菜单显式保留 48 dp，打开、长按及所有菜单回调不变。
- 原“最近更新”对所有非 Git 项目固定显示，且 Git 分支行是写死的 `main`。移除两条占位状态；Git 身份仍保留在标签中。
- 近期状态复用首页已有的“最近打开”前三项分组，由 `ProjectScreen` 传入 `isRecent`，其他项目默认不显示。斜角标是裁剪的非交互覆盖层，不增加卡片高度；大字体切换为符号，完整无障碍描述仍为“最近打开”。

没有新增依赖、通用组件框架、文件修改时间扫描或新的持久化状态。复用 `ProjectIcon`、现有主题和 `core:i18n` 中英文资源，文件使用 UTF-8 无 BOM。

## 本轮验证

| 范围 | 结果 |
| --- | --- |
| `feature:projectlist` 单元测试 | 8 项通过，0 失败、错误、跳过；其中 3 项布局测试覆盖下述矩阵与交互 |
| 名称与标签 | 中英文各 4 组宽度／字体组合，280–420 dp、1–2 倍字体；完整名称无省略，标签缩进相同且紧贴下方，菜单独立 |
| 短名／换行 | 一行短名与两行课程名，图标和名称／标签列垂直居中 |
| 近期角标 | 普通项目无角标，近期 Git 项目有角标；高度不变、不覆盖标题、没有独立点击动作；1.5／2 倍字体保持完整状态描述 |
| 菜单 | 菜单操作不触发卡片打开；卡片打开回调仍有效 |
| 国际化及差异 | `tools/i18n/check_all.py` 与 `git diff --check` 通过 |
| ARM64 Debug 宿主编译 | 通过，耗时 12 分 57 秒；见 `app-debug-compile.log` |

验证产物位于 `feature/projectlist/build/verification/card-layout-20261124/`，不提交 Git。最终模块日志为 `module-tests-final.log`，四张 `project-card-*.png` 为真实 Compose 视图树的本地渲染，路径是测试夹具，不是真机截图。

Robolectric 使用 API 34 和 native graphics。窗口截图接口依赖真实 vsync，最初触发 `forceRedraw` 超时；预览改为在 UI 线程将同一视图树绘制到 CPU Canvas，再裁剪卡片。保留失败日志，没有捕获后忽略失败或删掉布局断言。

复现命令：

```powershell
.\gradlew.bat -I tools/testing/single-worker-tests.init.gradle :feature:projectlist:testDebugUnitTest :feature:projectlist:compileDebugKotlin --no-daemon --console=plain --configure-on-demand --max-workers=1
.\gradlew.bat :app:compileArm64DebugKotlin --no-daemon --console=plain --configure-on-demand --max-workers=1
py -X utf8 tools/i18n/check_all.py
git diff --check
```

测试任务通过既有 init script 限制为 60 秒，构建准备独立计时。

## 交付与后续手测

本轮没有构建或安装 APK，没有启动模拟器、操作真机或递增版本；此前 Release 自动递增的 `version.properties` 修改保留。当前改动留在原 `dev` 工作区，没有新建 worktree。

下一次 APK 安装后，检查单行／两行名称、私有／公有／Git 标签、窄窗口、大字体、最近打开前三项及菜单操作。以上 JVM／宿主编译结果不替代真机触摸、Release/R8 或安装验收；回滚只需撤回本轮 UI、文案和测试修改，不涉及项目数据迁移。
