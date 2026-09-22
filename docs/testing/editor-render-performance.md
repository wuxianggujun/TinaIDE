# 编辑器渲染性能回归

> 最后人工核验：2026-09-09

被测实现入口：

- 视觉行 Fenwick 索引：`core/editor-view/src/main/java/.../editorview/EditorVisualLineIndex.kt`
- 增量映射与线性重建阈值：`core/editor-view/src/main/java/.../editorview/EditorVisualLineMapper.kt`（`MAX_INCREMENTAL_LINE_UPDATES`）
- 着色计划缓存与上限：`core/editor-view/src/main/java/.../editorview/EditorLineRenderPlanCache.kt`
- 计数入口：`EditorRenderEngine.performanceSnapshot()`，实现位于 `EditorRenderer.kt`

## 本地定向验证

只运行 `core:editor-view` 的测试及其编译依赖，不构建 App。一次性 Gradle 命令使用 `--no-daemon`；其他会话正在构建时不要并发启动 Gradle。后台测试执行时间上限为 60 秒，依赖准备和编译另计。

```bash
./gradlew :core:editor-view:testDebugUnitTest --tests '*EditorVisualLineIndexTest' --tests '*EditorVisualLineMapperTest' --tests '*EditorWordWrapLayoutCacheTest' --tests '*EditorLineRenderPlanCacheTest' --tests '*TextRendererCacheTest' --tests '*TextRenderPlannerTest' --tests '*EditorStateWordWrapTest' --tests '*EditorStateInlayHintsTest' --tests '*EditorMaxLineWidthTrackerTest' --tests '*EditorLineLayoutCacheTest' --tests '*EditorRendererPerformanceSnapshotTest' --tests '*EditorPopupComposeSmokeTest' --tests '*PopupOverlaySharedAnchorIntegrationTest' --tests '*EditorOverlaysIntegrationTest' --tests '*WhitespaceRendererTest' --tests '*WordOccurrenceHighlightRendererTest' --tests '*DiagnosticRendererTest' --no-daemon --console=plain
```

Windows JVM 使用 UTF-8。若本机 Java 的 loopback 初始化依赖临时目录，可在该次进程的 `JAVA_TOOL_OPTIONS` 中附加 `-Djdk.net.unixdomain.tmpdir=C:/gradle-tmp -Dfile.encoding=UTF-8`，目录须预先存在；不要覆盖用户已有的 JVM 参数。

## 工作量门禁

- 10 万行文档首尾两次行内编辑：复用原视觉行索引，只读取 2 个受影响行。
- 普通行内编辑：计数原地失效，Fenwick 累计索引按点更新；不复制全文数组。
- 批量编辑或 Hint 更新超过 1024 个不同脏行（`MAX_INCREMENTAL_LINE_UPDATES`）：丢弃脏行集合并线性重建索引，未失效的段数仍复用。
- 折叠隐藏行：编辑时不测量，展开后再计算。
- 同版本、相同折叠区间的解析回传不重建映射，避免抵消行内编辑的增量更新。
- Inlay Hint：仅重算新增、改变和删除提示的行；增删文档行时不能搬运旧提示宽度。
- 连续绘制 120 帧并改变软换行宽度：同一行只构建一次着色计划。
- 着色计划包含列范围与最终颜色，不保存像素坐标。文本、语法片段、语义修饰符、括号深度和配色决定是否复用；滚动、字体、Tab、Hint 和换行仍由当前布局处理。
- 着色计划缓存上限：512 行、220000 字符、32768 个源片段及结果分段；超大条目不保留，超大临时规划缓冲在使用后释放。
- 空白标记和同词高亮按可见视觉行遍历。折叠 4800 行、视口跨过折叠区域的用例只访问 9 个可见文档行，不逐一检查隐藏行。
- Tab、空格和跨软换行的同词高亮使用当前分段坐标；滚入续行后只处理该视觉窗口中的标记和匹配。实际绘制不创建逐标记或逐匹配矩形列表。
- 诊断为空时不读取文本；诊断稀疏时只读取可见且存在诊断的行。

这些断言证明重复工作被消除，不代表设备帧率或输入延迟已经达标。

## 设备采样

使用包含本次改动的已安装构建。先检查 `adb devices -l`，没有设备时不要记录为通过。测试文档应包含长行、Tab、中文和代理对、嵌套括号、可折叠区域及 Inlay Hint。

1. 固定设备、刷新率、字体、窗口大小和同一文档，分别采集修改前后的滚动、快速反向滑动、首尾输入、折叠展开及缩放。
2. 使用 Android Studio System Trace / Perfetto 采集各场景，分别记录冷缓存和预热后的主线程耗时、FrameTimeline 掉帧、GC 及输入延迟；按设备刷新周期判断，不统一用 16 ms 代替所有帧预算。
3. 在现有 `EditorRenderEngine.performanceSnapshot()` 读取 `totalRenderPlanBuilds`、`totalRenderPlanCacheHits`、`renderPlanCacheEntryCount`、`renderPlanCacheCharCount`、`renderPlanCacheElementCount`。计数为 renderer 生命周期累计值，应比较同一实例在采样前后的差值。
4. 稳定重绘时命中应增加、重建应保持不变；文本、颜色或有效高亮改变后应出现必要的重建。字体或换行改变不应导致着色计划重建，但必须确认实际文字位置正确。

首次建立软换行、改变换行参数、增删文档行或改变折叠结构仍可能重建累计索引。这是后续采样重点，不能把普通行内编辑的 O(log N) 结论扩大到这些场景。
