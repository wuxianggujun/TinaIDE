# TinaIDE 项目开发规范

> 最后人工核验：2026-09-09
>
> 本文档供所有开发者和 AI 助手参考。精简版关键约束，详细设计文档见 `docs/` 目录。
> 协作规则的唯一事实源是根目录 `AGENTS.md`；本文与 `AGENTS.md` 或当前构建配置冲突时以后者为准。

---

## 1. 项目结构

```
app/                    ← 应用壳（Activity 启动、DI 装配、ProGuard 全局规则）
build-logic/            ← Gradle Convention Plugins
core/                   ← 基础模块（common, config, i18n, model, network, lsp, git, plugin, ...）
feature/                ← 功能模块（editor, terminal, settings, viewer, workspace, ...）
external/               ← 第三方 submodule / 本地依赖（termux-proot, tina-android-tree-sitter, ...）
server/                 ← 公开占位说明；私有后端不随开源仓库分发
docs/                   ← 设计文档
```

**模块依赖方向**：`feature → core → common`，禁止反向依赖。

---

## 2. 编码约束

### 2.1 语言与编码
- Kotlin 为主，Java 仅限第三方兼容
- 文件编码：UTF-8（无 BOM）
- 默认输出语言：简体中文

### 2.2 Android 国际化（强制）
用户可见文本**必须**走 `values/strings.xml` + `values-en/strings.xml`，禁止硬编码。

```kotlin
// 正确
Strings.some_text.str()
Strings.export_failed.strOr(context, errorMessage)

// 错误
"导出失败：$message"
```

入口文件：`core/i18n/src/main/java/com/wuxianggujun/tinaide/core/i18n/` 下的 `AppStrings.kt`、`ResExt.kt`、`TextResourceAliases.kt`；
app 层 drawable 别名在 `app/src/main/java/com/wuxianggujun/tinaide/core/i18n/AppResourceAliases.kt`。

全局字符串资源位于 `core/i18n/src/main/res/values/strings.xml` 与 `values-en/strings.xml`。
文案变更后运行 `py tools/i18n/check_all.py`；详细规范见 [国际化规范](i18n.md)。

### 2.3 DI 模式
使用 **Koin**（DSL 声明，运行时解析；当前未引入 koin-annotations/KSP 代码生成）：
- Activity/Plain 类：`KoinComponent` + `by inject()`
- Composable：`koinInject()`
- 无 KoinComponent 的普通类：`GlobalContext.getOrNull()?.getOrNull<T>()`

---

## 3. ProGuard/R8 混淆规则（关键）

Release 构建启用 `isMinifyEnabled = true`。**新增第三方库必须评估混淆风险。**

### 3.1 规则存放原则
- **哪个模块引入依赖，就在那个模块的 `consumer-rules.pro` 写规则**
- Convention Plugin 已自动注册 `consumerProguardFiles("consumer-rules.pro")`
- `app/proguard-rules.pro` 仅放全局基础规则 + 无 consumer-rules 的第三方 SDK

### 3.2 判断是否需要规则

| 库的特征 | 需要的规则 | 示例 |
|----------|------------|------|
| Gson/反射序列化 | `-keepclassmembers { <fields>; }` | lsp4j |
| `Class.forName()` 动态加载 | `-keep class` | msgpack |
| JNI native 方法 | 全局规则已覆盖，通常无需 | zstd-jni |
| 动态代理接口 | `-keep interface` | lsp4j services |
| 自带 consumer-rules | 无需额外处理 | OkHttp, Koin, Coil |

### 3.3 常见崩溃模式

| 症状 | 根因 |
|------|------|
| `ClassCastException`（无消息） | 字段名被重命名，Gson 反序列化类型错误 |
| `ClassNotFoundException` | `Class.forName()` 引用的类被 R8 删除或重命名 |
| `NoSuchFieldError` | JNI 硬编码字段名被混淆 |

**详细参考**：`docs/proguard-rules-reference.md`

---

## 4. Git 子模块规则

本项目包含 Git submodule。提交顺序：
1. **先**在子模块仓库内提交并 `git push`
2. **再**回主仓库提交子模块指针变更并 `git push`
3. 打 tag 前校验：`git submodule status --recursive`

---

## 5. 构建验证

验证从改动所属模块开始，只有 `app` 宿主、ABI/flavor、打包或跨模块集成变化时才跑 `:app:*`。
一次性本地命令统一带 `--no-daemon`；其他会话正在构建时不要并发启动 Gradle。

```bash
# 单模块快速验证（优先测试，其次编译）
./gradlew :core:xxx:testDebugUnitTest --no-daemon --console=plain
./gradlew :core:xxx:compileDebugKotlin --no-daemon --console=plain

# App 宿主编译（arm64）
./gradlew :app:compileArm64DebugKotlin --no-daemon --console=plain

# 检查混淆 mapping（本项目包名为 com.wuxianggujun）
grep "^com.wuxianggujun" app/build/outputs/mapping/arm64Release/mapping.txt
```

Release 构建不是普通只读验证：

```bash
./gradlew :app:assembleArm64Release --no-daemon --console=plain
```

该任务可能递增 `version.properties`，并把 `build/outputs/mapping/<flavor>Release/mapping.txt`
归档到 `app/mappings/<versionName>-<timestamp>/`。只在确实需要产出 Release 包时运行，
不要拿它当日常检查。

---

## 6. 核心设计原则

- **KISS**：能用小改动解决就不引入新层级
- **YAGNI**：只实现当前需要的功能
- **DRY**：新增前先搜索是否已有类似实现
- **SOLID**：单一职责、开放封闭、依赖倒置
