# TinaEditor Kit

可独立构建的 Android/Jetpack Compose 代码编辑器。此目录可作为单独仓库维护；TinaIDE
主工程通过 `settings.gradle.kts` 的 `projectDir` 映射消费同一份源码，不复制实现。

## 模块

| 模块 | 职责 |
| --- | --- |
| `:core:editor-api` | 文件类型、主题键、语义 token 与 Signature Help 公共模型 |
| `:core:text-engine` | Rope、行索引、编辑历史、JNI 文本扫描 |
| `:core:tree-sitter` | 语法高亮、语言注册、折叠区域 |
| `:core:editor-view` | Compose 编辑器、IME、选择、滚动、弹窗与渲染 |

TinaIDE 的 `:core:editor-lsp`、`:feature:editor`、项目路径、编译运行、插件和偏好存储
**不属于此库**。宿主通过 `EditorState` 的回调接入补全、诊断、语义 token 等服务；
`EditorRuntimeOptions` 接入字体大小持久化和调试开关；`TinaEditor(hoverContent = ...)`
可替换默认的纯文本 Hover 内容。

## 构建

要求 JDK 17、Android SDK 37、NDK 29.0.14206865 与 CMake 3.22.1；库的
最低 Android API 为 28。请设置 `ANDROID_HOME` 指向 SDK，以便复合构建中的
Tree-sitter 子项目也能定位 SDK。源码与构建脚本均为 UTF-8。
克隆独立仓库时请初始化 Tree-sitter 源码子模块：

```bash
git submodule update --init --recursive
```

```bash
./gradlew :core:text-engine:testDebugUnitTest --no-daemon --console=plain
./gradlew :core:tree-sitter:compileDebugKotlin --no-daemon --console=plain
./gradlew :core:editor-view:compileDebugKotlin --no-daemon --console=plain
```

上述命令在本目录运行；Windows 可用 `gradlew.bat`。

## 在其他 Android 项目中使用

当前**推荐源码复合构建**：将 `editor-kit` 作为 Git 子模块（或同等方式）放到消费
项目中，初始化其 Tree-sitter 子模块，然后在消费项目的 `settings.gradle.kts` 添加：

```kotlin
includeBuild("editor-kit")
```

消费项目的 `build.gradle.kts` 添加依赖：

```kotlin
implementation("io.github.tinaide.editor:editor-view:0.1.0-SNAPSHOT")
```

Gradle 会用 included build 中的同名模块替换该坐标。消费项目仍需配置 `google()`
与 `mavenCentral()`。可运行 `examples/consumer` 验证跨项目源码消费：

```bash
./gradlew -p examples/consumer :consumer:compileDebugKotlin --no-daemon --console=plain
```

当前不提供 Maven/AAR 发布：部分 Tree-sitter grammar（例如 Bash、CMake）的
`4.3.2` 版本不在 Maven Central，直接发布编辑器 AAR 会生成无法解析的传递依赖。
待 grammar 产物、版本与发布仓库统一后，再增加 Maven 发布任务和独立消费者测试。

最小 Compose 用法：

```kotlin
val buffer = remember { RopeTextBuffer("Hello, editor!\n") }
val editorState = remember(buffer) { EditorState(textBuffer = buffer) }
TinaEditor(state = editorState, modifier = Modifier.fillMaxSize())
```

应用需自行提供 Activity、Compose 主题、文件读写和语法/LSP 服务。库的包名暂保留
`com.wuxianggujun.tinaide.core.*`，避免本轮搬迁同时改动 JNI 符号与现有 API；
后续如需重命名，应单独做兼容迁移。

## 许可证

本库源代码按 GPL-3.0-or-later 提供，见 [LICENSE](LICENSE)。Tree-sitter Android
绑定和各语言 grammar 来自独立子模块，保留其上游许可；详见 [NOTICE.md](NOTICE.md)。
