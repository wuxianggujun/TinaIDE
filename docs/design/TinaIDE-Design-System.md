# TinaIDE 设计系统规范

> 版本：1.4
> 更新日期：2026-09-09
> 最后人工核验：2026-09-09
> 状态：Active

## 目录

- [概述](#概述)
- [设计原则](#设计原则)
- [色彩系统](#色彩系统)
- [间距系统](#间距系统)
- [圆角系统](#圆角系统)
- [字体系统](#字体系统)
- [动画系统](#动画系统)
- [组件规范](#组件规范)
- [布局规范](#布局规范)
- [实施指南](#实施指南)

---

## 概述

TinaIDE 采用 **Material Design 3** 作为基础设计语言，在此基础上建立了统一的设计系统，确保整个应用的视觉一致性和用户体验连贯性。

### 设计目标

- **一致性**：所有页面使用统一的设计语言
- **可维护性**：通过设计 Token 集中管理样式
- **可扩展性**：易于添加新组件和页面
- **可访问性**：符合无障碍设计标准

### 组件文件结构

所有设计系统组件位于 `core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/` 目录：

| 文件 | 说明 |
|------|------|
| `TinaSpacing.kt` | 间距常量 |
| `TinaShapes.kt` | 圆角常量 |
| `TinaSemanticColors.kt` | 语义化颜色 |
| `TinaTopBar.kt` | 顶部栏组件 |
| `TinaButtons.kt` | 按钮组件 |
| `TinaCards.kt` | 卡片组件 |
| `TinaDialogs.kt` | 对话框组件 |
| `TinaTextFields.kt` | 输入框组件 |
| `TinaBadges.kt` | 徽章组件 |
| `TinaDividers.kt` | 分隔线组件 |
| `TinaMenus.kt` | 菜单组件 |
| `TinaSkeletons.kt` | 骨架屏占位组件 |
| `TinaPullToRefresh.kt` | 下拉刷新容器 |
| `TinaBackHandlers.kt` | 返回手势/返回键处理 |
| `MarkdownViewer.kt` | Markdown 渲染（帮助、说明类页面复用） |
| `ProjectIcon.kt` | 项目图标 |
| `DetailScreenComponents.kt` | 详情页通用区块 |

主题与图标在同模块的相邻目录：`ui/theme/TinaIDETheme.kt`、`ui/theme/RikkaHubInspiredColorSchemes.kt`、`ui/compose/icons/`。

---

## 设计原则

### 1. 简洁优先
- 避免过度装饰
- 突出核心功能
- 减少视觉噪音

### 2. 层次分明
- 使用阴影和间距建立层次
- 重要信息优先显示
- 合理使用色彩引导注意力

### 3. 响应式设计
- 适配不同屏幕尺寸
- 考虑横竖屏切换
- 优化触摸交互

### 4. 性能优先
- 避免过度动画
- 优化列表渲染
- 减少不必要的重组

---

## 色彩系统

### Material Design 3 主题色

使用 Material Design 3 的动态色彩系统：

```kotlin
// 主色调
MaterialTheme.colorScheme.primary          // 主要品牌色
MaterialTheme.colorScheme.onPrimary        // 主色上的文字
MaterialTheme.colorScheme.primaryContainer // 主色容器

// 次要色调
MaterialTheme.colorScheme.secondary
MaterialTheme.colorScheme.onSecondary
MaterialTheme.colorScheme.secondaryContainer

// 背景色
MaterialTheme.colorScheme.background       // 页面背景
MaterialTheme.colorScheme.surface          // 卡片/组件背景
MaterialTheme.colorScheme.surfaceVariant   // 次要表面

// 文字色
MaterialTheme.colorScheme.onSurface        // 主要文字
MaterialTheme.colorScheme.onSurfaceVariant // 次要文字

// 状态色
MaterialTheme.colorScheme.error            // 错误/危险
MaterialTheme.colorScheme.errorContainer   // 错误容器
```

### 语义化颜色 (TinaSemanticColors)

用于状态指示、日志级别、Git 状态等场景：

```kotlin
// 通用状态颜色
TinaSemanticColors.success        // 成功状态 - 绿色 #4CAF50
TinaSemanticColors.error          // 错误状态 - 红色 #F44336
TinaSemanticColors.warning        // 警告状态 - 橙色 #FF9800
TinaSemanticColors.info           // 信息状态 - 蓝色 #2196F3
TinaSemanticColors.neutral        // 中性状态 - 灰色 #9E9E9E

// 日志级别颜色
TinaSemanticColors.Log.verbose    // VERBOSE - 灰色 #9E9E9E
TinaSemanticColors.Log.debug      // DEBUG - 蓝色 #2196F3
TinaSemanticColors.Log.info       // INFO - 绿色 #4CAF50
TinaSemanticColors.Log.warn       // WARN - 橙色 #FF9800
TinaSemanticColors.Log.error      // ERROR - 红色 #F44336
TinaSemanticColors.Log.success    // SUCCESS - 亮绿色 #00E676
TinaSemanticColors.Log.fail       // FAIL - 亮红色 #FF1744

// Git 状态颜色
TinaSemanticColors.Git.modified   // 已修改 - 黄色 #E2A832
TinaSemanticColors.Git.added      // 已添加 - 绿色 #4CAF50
TinaSemanticColors.Git.deleted    // 已删除 - 红色 #F44336
TinaSemanticColors.Git.renamed    // 已重命名 - 蓝色 #2196F3
TinaSemanticColors.Git.copied     // 已复制 - 紫色 #9C27B0
TinaSemanticColors.Git.untracked  // 未跟踪 - 灰色 #9E9E9E
TinaSemanticColors.Git.ignored    // 已忽略 - 深灰色 #757575

// 编辑器状态颜色
TinaSemanticColors.Editor.ready      // 就绪 - 绿色 #4CAF50
TinaSemanticColors.Editor.connecting // 连接中 - 蓝色 #2196F3
TinaSemanticColors.Editor.busy       // 忙碌 - 黄色 #FFC107
TinaSemanticColors.Editor.noLsp      // 无 LSP - 灰色 #9E9E9E
TinaSemanticColors.Editor.error      // 错误 - 红色 #F44336

// 调试状态颜色
TinaSemanticColors.Debug.paused              // 暂停 - 绿色 #4CAF50
TinaSemanticColors.Debug.running             // 运行中 - 橙色 #FF9800
TinaSemanticColors.Debug.breakpoint          // 断点 - 红色 #E53935
TinaSemanticColors.Debug.breakpointUnverified // 断点未验证 - 半透明红色

// 诊断严重性颜色
TinaSemanticColors.Diagnostic.error()   // 错误 - 使用 MaterialTheme.colorScheme.error
TinaSemanticColors.Diagnostic.warning   // 警告 - 橙色 #FF9800
TinaSemanticColors.Diagnostic.info()    // 信息 - 使用 MaterialTheme.colorScheme.primary
TinaSemanticColors.Diagnostic.hint()    // 提示 - 使用 MaterialTheme.colorScheme.onSurfaceVariant
```

`TinaSemanticColors` 还包含上表未列出的 `successBright` / `errorBright` / `warningYellow` / `neutralDark`
以及 `Project`、`Language` 两个子对象；新增语义色前先读 `TinaSemanticColors.kt`，避免重复定义。

### 使用规范

#### 正确使用

```kotlin
// 页面背景
Modifier.background(MaterialTheme.colorScheme.background)

// 卡片背景
Card(
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
    )
)

// 主要按钮
Button(
    colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary
    )
)
```

#### 避免使用

```kotlin
// 不要硬编码颜色
Modifier.background(Color(0xFF6200EE))
```

### 统一规范

| 场景 | 使用颜色 |
|------|---------|
| 页面背景 | `background` |
| 卡片背景 | `surface` |
| 顶部栏背景 | `surface` |
| 主要按钮 | `primary` |
| 次要按钮 | `secondary` |
| 危险操作 | `error` |
| 主要文字 | `onSurface` |
| 次要文字 | `onSurfaceVariant` |

---

## 间距系统

### TinaSpacing 定义

基于 Material Design 8dp 网格系统设计：

```kotlin
object TinaSpacing {
    // 基础间距
    val xxs = 2.dp    // 极小间距 - 用于紧凑元素内部
    val xs = 4.dp     // 小间距 - 用于相关元素之间
    val sm = 6.dp     // 较小间距 - 用于图标与文字之间
    val md = 8.dp     // 中等间距 - 最常用的基础间距
    val mdLg = 10.dp  // 中大间距 - 用于中等密度布局
    val lg = 12.dp    // 较大间距 - 用于分组内元素
    val xl = 16.dp    // 大间距 - 用于卡片内边距、分组之间
    val xxl = 20.dp   // 较大间距 - 用于区域分隔
    val xxxl = 24.dp  // 超大间距 - 用于页面边距
    val huge = 32.dp  // 巨大间距 - 用于主要区域分隔

    // 语义化间距别名
    val iconText = sm           // 图标与文字间距 (6dp)
    val listItemVertical = xxs  // 列表项垂直间距 (2dp)
    val listItemHorizontal = xs // 列表项水平内边距 (4dp)
    val cardPadding = lg        // 卡片内边距 (12dp)
    val cardGap = md            // 卡片之间间距 (8dp)
    val dialogPadding = xxxl    // 对话框内边距 (24dp)
    val toolbarPadding = md     // 工具栏内边距 (8dp)
    val statusBarPadding = lg   // 状态栏内边距 (12dp)
    val buttonGap = md          // 按钮之间间距 (8dp)
    val inputLabelGap = xs      // 输入框与标签间距 (4dp)
    val sectionGap = xl         // 分组标题与内容间距 (16dp)
    val pageHorizontal = xl     // 页面水平边距 (16dp)
    val pageVertical = md       // 页面垂直边距 (8dp)
}
```

### 使用场景

| 间距 | 值 | 使用场景 |
|------|---|---------|
| `xxs` | 2dp | 紧凑元素内部、列表项垂直间距 |
| `xs` | 4dp | 相关元素之间、图标间距 |
| `sm` | 6dp | 图标与文字之间 |
| `md` | 8dp | 基础间距、按钮间距、工具栏内边距 |
| `mdLg` | 10dp | 中等密度布局 |
| `lg` | 12dp | 卡片内边距、状态栏内边距 |
| `xl` | 16dp | 页面水平边距、分组间距 |
| `xxl` | 20dp | 区域分隔 |
| `xxxl` | 24dp | 对话框内边距、页面边距 |
| `huge` | 32dp | 主要区域分隔 |

### 示例

```kotlin
// 正确使用
Column(
    modifier = Modifier.padding(TinaSpacing.xl),
    verticalArrangement = Arrangement.spacedBy(TinaSpacing.lg)
)

// 使用语义化别名
Card(
    modifier = Modifier.padding(TinaSpacing.cardPadding)
)

// 避免硬编码
Column(
    modifier = Modifier.padding(16.dp),  // 应使用 TinaSpacing.xl
    verticalArrangement = Arrangement.spacedBy(12.dp)  // 应使用 TinaSpacing.lg
)
```

---

## 圆角系统

### TinaShapes 定义

```kotlin
object TinaShapes {
    val ExtraSmallCorner = 4.dp   // 超小圆角 - 进度条、徽章
    val SmallCorner = 8.dp        // 小圆角 - 标签、小图标背景
    val ButtonCorner = 12.dp      // 按钮圆角
    val TextFieldCorner = 12.dp   // 输入框圆角
    val CardCorner = 16.dp        // 卡片圆角
    val DialogCorner = 24.dp      // 对话框圆角
}
```

### 使用场景

| 圆角 | 值 | 使用场景 |
|------|---|---------|
| `ExtraSmallCorner` | 4dp | 进度条、徽章、分割线端点 |
| `SmallCorner` | 8dp | 标签、小图标背景、列表选中项 |
| `ButtonCorner` | 12dp | 按钮、搜索框 |
| `TextFieldCorner` | 12dp | 输入框 |
| `CardCorner` | 16dp | 标准卡片、列表项 |
| `DialogCorner` | 24dp | 对话框、底部表单 |

### 示例

```kotlin
// 正确使用
Card(
    shape = RoundedCornerShape(TinaShapes.CardCorner)
)

Button(
    shape = RoundedCornerShape(TinaShapes.ButtonCorner)
)

// 避免硬编码
Card(
    shape = RoundedCornerShape(16.dp)  // 应使用 TinaShapes.CardCorner
)
```

---

## 字体系统

### Material Design 3 Typography

使用 Material Design 3 的字体系统：

```kotlin
// 标题
MaterialTheme.typography.displayLarge      // 57sp
MaterialTheme.typography.displayMedium     // 45sp
MaterialTheme.typography.displaySmall      // 36sp

MaterialTheme.typography.headlineLarge     // 32sp
MaterialTheme.typography.headlineMedium    // 28sp
MaterialTheme.typography.headlineSmall     // 24sp

MaterialTheme.typography.titleLarge        // 22sp
MaterialTheme.typography.titleMedium       // 16sp (Medium)
MaterialTheme.typography.titleSmall        // 14sp (Medium)

// 正文
MaterialTheme.typography.bodyLarge         // 16sp
MaterialTheme.typography.bodyMedium        // 14sp
MaterialTheme.typography.bodySmall         // 12sp

// 标签
MaterialTheme.typography.labelLarge        // 14sp (Medium)
MaterialTheme.typography.labelMedium       // 12sp (Medium)
MaterialTheme.typography.labelSmall        // 11sp (Medium)
```

### 使用规范

| 场景 | 字体样式 | 字重 |
|------|---------|------|
| 页面标题 | titleLarge | SemiBold |
| 卡片标题 | titleMedium | SemiBold |
| 列表项标题 | bodyLarge | Medium |
| 正文内容 | bodyMedium | Normal |
| 辅助说明 | bodySmall | Normal |
| 按钮文字 | labelLarge | Medium |
| 标签/徽章文字 | labelSmall | Medium |

### 字重定义

```kotlin
FontWeight.Normal      // 400
FontWeight.Medium      // 500
FontWeight.SemiBold    // 600
FontWeight.Bold        // 700
```

---

## 组件规范

### 1. 顶部栏组件 (TinaTopBar.kt)

#### 顶部栏类型

| 组件 | 用途 | 样式 |
|------|------|------|
| `TinaTopBar` | 标准顶部栏 | 左对齐标题 |

#### 使用示例

```kotlin
// 标准顶部栏
TinaTopBar(
    title = "页面标题",
    onNavigateBack = { navController.popBackStack() },
    actions = {
        IconButton(onClick = { /* ... */ }) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null
            )
        }
    }
)

// 带自定义标题的顶部栏
TinaTopBar(
    title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("标题")
            TinaRecommendedBadge()
        }
    },
    onNavigateBack = onBack
)
```

#### 顶部栏规范

- **高度**：56dp（系统默认）
- **背景色**：`surface`
- **标题字体**：`titleLarge` + `SemiBold`
- **返回按钮**：左侧，使用 `ArrowBack` 图标
- **操作按钮**：右侧，最多 3 个

### 2. 按钮组件 (TinaButtons.kt)

#### 按钮类型

| 组件 | 用途 | 样式 |
|------|------|------|
| `TinaPrimaryButton` | 主要操作（确认、保存） | 填充样式，primary 色 |
| `TinaPrimaryButtonLarge` | 页面底部主操作 | 填充样式，56dp 高度，全宽 |
| `TinaSecondaryButton` | 次要操作（编辑、查看） | 色调样式 |
| `TinaOutlinedButton` | 中等强调（取消、返回） | 轮廓样式 |
| `TinaTextButton` | 低优先级（跳过、稍后） | 文本样式 |
| `TinaDangerButton` | 危险操作（删除） | 填充样式，error 色 |
| `TinaDangerOutlinedButton` | 危险轮廓按钮 | 轮廓样式，error 色 |

#### 使用示例

```kotlin
// 主要按钮
TinaPrimaryButton(
    text = "确认",
    onClick = { /* ... */ },
    icon = painterResource(R.drawable.ic_check)  // 可选图标
)

// 大号主要按钮（页面底部）
TinaPrimaryButtonLarge(
    text = "下一步",
    onClick = { /* ... */ }
)

// 危险操作按钮
TinaDangerButton(
    text = "删除",
    onClick = { /* ... */ }
)
```

#### 按钮规范

- **圆角**：12dp (`TinaShapes.ButtonCorner`)
- **大号按钮高度**：56dp
- **图标尺寸**：18dp
- **图标与文字间距**：8dp

### 3. 卡片组件 (TinaCards.kt)

#### 卡片类型

| 组件 | 用途 | 样式 |
|------|------|------|
| `TinaCard` | 基础卡片 | 填充样式，1dp 阴影 |

#### 使用示例

```kotlin
// 基础卡片
TinaCard(
    onClick = { /* 可选点击事件 */ }
) {
    // 卡片内容
}

```

#### 卡片规范

- **圆角**：16dp (`TinaShapes.CardCorner`)
- **默认阴影**：1dp
- **高亮阴影**：4dp
- **背景色**：`surface`

### 4. 对话框组件 (TinaDialogs.kt)

#### 对话框类型

| 组件 | 用途 |
|------|------|
| `TinaAlertDialog` | 基础对话框 |
| `TinaConfirmDialog` | 确认对话框（确认/取消） |
| `TinaThreeActionDialog` | 三按钮对话框 |
| `TinaInfoDialog` | 信息对话框（仅关闭按钮） |
| `TinaErrorDialog` | 错误对话框 |
| `TinaInputDialog` | 输入对话框 |
| `TinaValidatedInputDialog` | 带校验的输入对话框 |
| `TinaLoadingDialog` | 加载对话框 |
| `TinaSingleChoiceDialog` | 单选对话框 |
| `TinaActionChoiceDialog` | 操作选择对话框 |
| `TinaSliderDialog` | 滑块对话框 |
| `TinaCustomDialog` / `TinaCustomDialogScaffold` | 自定义内容对话框骨架 |
#### 使用示例

```kotlin
// 确认对话框
TinaConfirmDialog(
    title = "删除文件",
    message = "确定要删除此文件吗？",
    onConfirm = { /* ... */ },
    onDismiss = { /* ... */ },
    isDanger = true  // 危险操作样式
)

// 输入对话框
TinaInputDialog(
    title = "重命名",
    value = fileName,
    onValueChange = { fileName = it },
    onConfirm = { /* ... */ },
    onDismiss = { /* ... */ },
    label = "文件名"
)

// 加载对话框
TinaLoadingDialog(
    message = "正在处理..."
)
```

#### 对话框规范

- **圆角**：24dp (`TinaShapes.DialogCorner`)
- **背景色**：`surface`

### 5. 输入框组件 (TinaTextFields.kt)

#### 输入框类型

| 组件 | 用途 |
|------|------|
| `TinaTextField` | 基础输入框 |
| `TinaSearchField` | 搜索输入框 |

#### 使用示例

```kotlin
// 基础输入框
TinaTextField(
    value = text,
    onValueChange = { text = it },
    label = "用户名",
    placeholder = "请输入用户名",
    isError = hasError,
    errorText = "用户名不能为空"
)

// 搜索输入框
TinaSearchField(
    value = searchQuery,
    onValueChange = { searchQuery = it },
    placeholder = "搜索..."
)
```

#### 输入框规范

- **圆角**：12dp (`TinaShapes.TextFieldCorner`)
- **搜索框圆角**：12dp (`TinaShapes.ButtonCorner`)
- **默认宽度**：`fillMaxWidth()`

### 6. 徽章组件 (TinaBadges.kt)

#### 徽章类型

| 组件 | 用途 |
|------|------|
| `TinaRecommendedBadge` | 推荐徽章 |
| `TinaStatusBadge` | 状态徽章 |

#### 使用示例

```kotlin
// 推荐徽章
TinaRecommendedBadge()

// 状态徽章
TinaStatusBadge(
    text = "成功",
    status = BadgeStatus.SUCCESS
)
```

#### 徽章规范

- **圆角**：4dp (`TinaShapes.ExtraSmallCorner`)
- **内边距**：水平 6dp，垂直 2dp
- **字体**：`labelSmall` + `Medium`

### 7. 分隔线组件 (TinaDividers.kt)

```kotlin
// 水平分隔线
TinaDivider()
```

#### 分隔线规范

- **默认厚度**：1dp
- **默认颜色**：`outlineVariant`

---

## 布局规范

### 1. 页面布局

#### 标准页面结构

```kotlin
Scaffold(
    topBar = {
        TinaTopBar(
            title = "页面标题",
            onNavigateBack = onBack
        )
    }
) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TinaSpacing.pageHorizontal)
    ) {
        // 页面内容
    }
}
```

#### 使用规范

- **页面背景**：`background`
- **水平边距**：16dp (`TinaSpacing.pageHorizontal`)
- **垂直边距**：8dp (`TinaSpacing.pageVertical`)
- **组件间距**：16dp (`TinaSpacing.xl`)

### 2. 列表布局

```kotlin
LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(TinaSpacing.xl),
    verticalArrangement = Arrangement.spacedBy(TinaSpacing.lg)
) {
    items(list) { item ->
        // 列表项
    }
}
```

#### 使用规范

- **内边距**：16dp (`TinaSpacing.xl`)
- **项间距**：12dp (`TinaSpacing.lg`)

### 3. 网格布局

```kotlin
LazyVerticalGrid(
    columns = GridCells.Fixed(2),
    contentPadding = PaddingValues(TinaSpacing.xl),
    horizontalArrangement = Arrangement.spacedBy(TinaSpacing.lg),
    verticalArrangement = Arrangement.spacedBy(TinaSpacing.lg)
) {
    items(list) { item ->
        // 网格项
    }
}
```

---

## 实施指南

### 1. 导入方式

```kotlin
// 统一导入所有组件
import com.wuxianggujun.tinaide.ui.compose.components.*
```

### 2. 代码审查清单

- [ ] 是否使用 `TinaSpacing` 而非硬编码间距？
- [ ] 是否使用 `TinaShapes` 而非硬编码圆角？
- [ ] 是否使用 `MaterialTheme.colorScheme` 而非硬编码颜色？
- [ ] 是否使用统一的 `Tina*` 组件？
- [ ] 是否遵循字体使用规范？

### 3. 常见问题

#### Q: 什么时候可以不遵循规范？

A: 在以下情况可以例外：
- 特殊的视觉效果需求
- 第三方库的限制
- 性能优化需要

但需要在代码中注释说明原因。

#### Q: 如何处理遗留代码？

A: 采用渐进式迁移：
1. 新功能严格遵循规范
2. 修改旧功能时顺便重构
3. 定期进行设计系统审计

---

## 附录

### A. 设计 Token 速查表

| Token | 值 | 使用场景 |
|-------|---|---------|
| `TinaSpacing.xxs` | 2dp | 紧凑元素内部 |
| `TinaSpacing.xs` | 4dp | 小间距 |
| `TinaSpacing.sm` | 6dp | 图标与文字 |
| `TinaSpacing.md` | 8dp | 基础间距 |
| `TinaSpacing.lg` | 12dp | 卡片内边距 |
| `TinaSpacing.xl` | 16dp | 页面边距 |
| `TinaSpacing.xxl` | 20dp | 区域分隔 |
| `TinaSpacing.xxxl` | 24dp | 对话框内边距 |
| `TinaSpacing.huge` | 32dp | 主要区域分隔 |
| `TinaShapes.ExtraSmallCorner` | 4dp | 徽章 |
| `TinaShapes.SmallCorner` | 8dp | 标签 |
| `TinaShapes.ButtonCorner` | 12dp | 按钮 |
| `TinaShapes.TextFieldCorner` | 12dp | 输入框 |
| `TinaShapes.CardCorner` | 16dp | 卡片 |
| `TinaShapes.DialogCorner` | 24dp | 对话框 |

### B. 参考资源

- [Material Design 3 Guidelines](https://m3.material.io/)
- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
- [Material Theme Builder](https://material-foundation.github.io/material-theme-builder/)

---

**文档维护者**：TinaIDE 团队
**最后更新**：2026-02-25
