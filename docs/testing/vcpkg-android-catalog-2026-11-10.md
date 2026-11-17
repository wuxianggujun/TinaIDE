# vcpkg Android 市场与内置目录回归（2026-11-10）

本记录对应 `0.19.2` 发布后的 Unreleased 修改，不代表已发布 APK 包含本次修复。

## 行为范围

- 设置页移除目录输入。普通项目仅有关闭/内置管理选项；旧预编译项目可保留原配置，明确切换后才清除配置中的路径，原文件保留。
- 市场默认展示 Android 候选库。上游 `supports` 按当前应用 ABI、Android、静态库、静态 CRT、相同 host/target triplet 计算；空声明视为无限制，未知或非法表达式不列入候选。
- 包数量、搜索和添加依赖使用同一筛选依据。项目声明/实际安装的包独立展示，已下架和不兼容的依赖仍可移除；移除不自动启用已关闭的集成。
- 复用既有 `VcpkgCatalogStore` 更新机制、`VcpkgRepository` 清单写入及 `VcpkgManagedPaths` 私有目录，没有新增下载来源或路径存储。

## 已执行的定向测试

JDK 17；Robolectric 使用 API 34；单 worker，Test task 超时 60 秒。全部 38 项通过，失败、错误、跳过均为 0。

| 测试类 | 用例数 | 覆盖 |
| --- | ---: | --- |
| `VcpkgAndroidSupportTest` | 9 | Android/桌面系统、两种 ABI、arm/arm32 语义、静态链接、native、括号/逗号/关键字、未知与非法条件、解析大小/深度上限 |
| `VcpkgAndroidCatalogTest` | 4 | 平台列表及数量、项目依赖隔离、目录更新重算、完整随包索引 |
| `VcpkgRepositoryTest` | 19 | 添加前校验、移除不启用、自动管理、旧预编译迁移、UTF-8、清单字段保留、并发写与安装状态 |
| `VcpkgCatalogStoreTest` | 6 | 离线索引、目录更新、归档校验、取消及原目录保留 |

随包索引 revision：`bdadb1e1a5feceeaf1040dbdd5180dce2ecad61d`，共 2,912 项。全部表达式可识别；ARM64 筛选后 2,487 项，x86_64 筛选后 2,530 项。数字是此快照的声明匹配结果，不是成功编译库数量；在线更新后会变化。

```bash
./gradlew :core:packages:testDebugUnitTest \
  --tests '*VcpkgAndroidSupportTest' --tests '*VcpkgAndroidCatalogTest' \
  --tests '*VcpkgRepositoryTest' --tests '*VcpkgCatalogStoreTest' \
  -I tools/testing/single-worker-tests.init.gradle \
  --no-daemon --no-parallel --max-workers=1 --console=plain
```

`py tools/i18n/check_all.py` 已通过；用户文案保持中英同步，新增文本使用 UTF-8。`git diff --check` 已通过。

以下编译任务均通过（`BUILD SUCCESSFUL`，36 分 5 秒，单 worker）：

```bash
./gradlew :feature:packages:compileDebugKotlin :feature:settings:compileDebugKotlin \
  :app:compileArm64DebugKotlin :app:compileArm64DebugAndroidTestKotlin \
  --no-daemon --no-parallel --max-workers=1 --console=plain
```

未运行 assemble/Release，未改版本号，未生成或安装本轮 APK。结构化结果见 [验证证据](evidence/vcpkg-android-catalog-2026-11-10/results.json)。

## 设备验证范围

`VcpkgMarketInstrumentationTest` 已补充无项目目录/数量/搜索、排除 `directx-headers`、已下架依赖可移除且不启用集成的交互场景。本轮尚未执行新 APK 的模拟器或真机测试；历史 fmt 源码构建验收见 [内置 vcpkg 验收记录](vcpkg-managed-2026-11-10.md)，不能替代本次 UI 验收。

上游未声明限制不等于 TinaIDE 实测通过，平台表达式也不覆盖所有 features、传递依赖和额外构建工具；实际结果仍由构建日志确认。

## 后续接入体验验证（2026-11-11）

在上述市场筛选基础上增加 CMake 新建项目的可选 vcpkg 勾选项，以及中英文离线教程。创建项目时只生成清单和配置，不下载库；已有模板清单原样保留。教程、帮助和市场共用 `help/vcpkg-guide.md` 正文，避免发布重复的工具插件或维护多套教程。

定向 JVM/Robolectric 验证共 55 项通过，无失败、错误或跳过：

| 测试类 | 用例数 | 覆盖 |
| --- | ---: | --- |
| `ProjectCreationServiceTest` | 4 | 原有创建、路径校验及回滚 |
| `ProjectCreationVcpkgTest` | 8 | 可选启用、空清单、模板清单 UTF-8/字段保留、路径清理、非 CMake 拒绝、失败回滚 |
| `NewProjectWizardActivityIntentTest` | 2 | 原有向导入口参数 |
| `NewProjectWizardCppVisibilityTest` | 1 | 原有语言配置显示 |
| `NewProjectWizardSupportTest` | 15 | 模板筛选与选择逻辑 |
| `NewProjectWizardViewModelTest` | 8 | vcpkg 选项默认值、模板切换、真实项目创建传参、模板必需包检查 |
| `HelpRepositoryCatalogTest` | 4 | 文档注册、两种语言的真实资产、搜索和入口解析 |
| `HelpRepositoryTest` | 11 | 帮助语言回落与内部链接 |
| `TutorialRepositoryTest` | 2 | 教程目录及文章链接 |

```bash
./gradlew :core:project:testDebugUnitTest --tests '*ProjectCreation*' \
  :feature:wizard:testDebugUnitTest --tests '*NewProjectWizard*' \
  -I tools/testing/single-worker-tests.init.gradle --no-daemon --no-parallel --max-workers=1 --console=plain
./gradlew :feature:help:testDebugUnitTest --tests '*HelpRepository*Test' \
  :feature:tutorial:testDebugUnitTest --tests '*TutorialRepositoryTest' \
  -I tools/testing/single-worker-tests.init.gradle --no-daemon --no-parallel --max-workers=1 --console=plain
```

文档和国际化检查通过，覆盖 35 份中文、35 份英文帮助资产。教程中的 CMake/C++ 代码与 `tools/vcpkg/example` 完全一致，JSON 示例可解析；本地链接存在。清理内容包括旧路径配置说明、英文待验收文案、文档索引的旧许可阻塞、易过期的版本/模块数量副本和英文首页的旧底栏条目；历史验收记录保留并注明后续实现入口。

市场、向导、帮助和教程模块编译通过；宿主 `:app:compileArm64DebugKotlin` 与 `:app:compileArm64DebugAndroidTestKotlin` 通过。创建/向导验证构建用时 10 分 7 秒，帮助/教程测试与宿主集成编译用时 16 分 40 秒。

结构化结果见 [接入体验验证证据](evidence/vcpkg-android-catalog-2026-11-10/onboarding-results.json)。结果与 SHA-256 已保存；清理两份临时日志的操作被自动审批以 `blocked by policy` 拒绝，因此保留 `.tmp/vcpkg-onboarding` 中共 135,754 字节日志，未改用其他方式删除。标准模块 `build/` 留供增量编译。本节不代表已在新 APK 中完成设备交互测试；本轮未运行 assemble 或安装设备。
