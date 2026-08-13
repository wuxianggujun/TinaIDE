# TinaIDE ProGuard/R8 混淆规则参考文档

> 最后更新：2026-07-03 | 维护者：TinaIDE Team

## 1. 概述

### 1.1 R8 构建配置

Release 构建启用了 R8 代码压缩与混淆：

```kotlin
// app/build.gradle.kts
release {
    isMinifyEnabled = true      // 代码压缩 + 混淆
    isShrinkResources = true    // 资源压缩
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

R8 对代码执行三项操作：
- **Tree-shaking**：移除未引用的类、方法、字段
- **Obfuscation**：重命名类/方法/字段名（`com.example.Foo` → `a.b`）
- **Optimization**：内联、合并、去虚化等字节码优化

### 1.2 规则分发机制

```
┌─────────────────────┐
│  app/proguard-rules  │ ← 全局基础规则 + 无 consumer-rules 的第三方 SDK
│         .pro         │
└─────────┬───────────┘
          │  合并
          ▼
┌─────────────────────┐
│  各模块 consumer-    │ ← 由 convention plugin 自动注册：
│  rules.pro           │   consumerProguardFiles("consumer-rules.pro")
└─────────┬───────────┘   位于 build-logic/TinaAndroidLibraryPlugin.kt:23
          │
          ▼
┌─────────────────────┐
│  第三方 AAR 自带     │ ← OkHttp、Koin、Coil、AndroidX 等自带 consumer-rules
│  consumer-rules      │
└─────────────────────┘
```

**Convention Plugin** (`TinaAndroidLibraryPlugin`) 在每个 library 模块自动声明 `consumerProguardFiles("consumer-rules.pro")`，因此只需在模块根目录放置 `consumer-rules.pro` 即可自动合并到最终规则。

**原则**：哪个模块引入依赖，就在那个模块的 `consumer-rules.pro` 写对应规则。

> **Composite Build 注意事项**：通过 `includeBuild()` 引入的外部项目（如 `external/tina-android-tree-sitter`）不受主项目 Convention Plugin 管理。其 `consumer-rules.pro` **必须在自身的 `build.gradle.kts` 中显式声明** `consumerProguardFiles("consumer-rules.pro")`，否则规则文件虽存在但不会被 AGP 合并到最终 R8 配置中。建议在 `app/proguard-rules.pro` 为这类模块添加安全网规则。

---

## 2. 规则分布总览

### 2.1 App 层规则

| 文件 | 职责 |
|------|------|
| `app/proguard-rules.pro` | 全局基础规则（@Keep、Android 组件、JNI、Kotlin/Coroutines/Serialization）+ 无 consumer-rules 的第三方 SDK（SDL、宿主侧 JLatexMath、Jsoup 安全网） |

### 2.2 Core 模块 consumer-rules.pro

| 模块 | 有效规则 | 保护对象 |
|------|----------|----------|
| `core:lsp` | lsp4j 字段名 + 服务接口 + jsonrpc 框架 | Gson 反射序列化 + 动态代理 |
| `core:network` | msgpack buffer 类 | `Class.forName()` 动态加载 |
| `core:git` | JGit NLS + ServiceLoader + SSHD 警告抑制 | 反射加载 ResourceBundle + Transport |
| `core:tree-sitter` | grammar TSLanguage 子类 | `Class.forName()` 加载语法绑定 |
| `core:plugin` | LuaJava JNI + 回调接口 | JNI native 方法 + Lua↔Java 互调 |
| `core:apk-builder` | BouncyCastle JCA provider + apksig ASN.1 注解反射 + ARSCLib 告警抑制 | JcaContentSignerBuilder 内部 SPI/反射算法查找；apksig 签名时通过运行时注解和反射解析 X.509/PKCS#7 模型 |
| 其他 core 模块 | 空（无需额外规则） | — |

### 2.3 Feature 模块 consumer-rules.pro

| 模块 | 有效规则 | 保护对象 |
|------|----------|----------|
| `feature:terminal` | Termux JNI + View 构造函数 | JNI native 方法 + XML 反射实例化 |
| 其他 feature 模块 | 空（无需额外规则） | — |

### 2.4 External 模块

| 模块 | 有效规则 | 保护对象 |
|------|----------|----------|
| `external/tina-android-tree-sitter` | 完整的 JNI 字段/方法/工厂类保留 | TSNode/TSParser/TSTree 等 JNI 字段名 |
| `external/xcrash` | `NativeHandler` native 方法 + 回调 | JNI 崩溃回调 |
| `external/termux-terminal` | 空（上游模板） | — |
| `external/rikkahub/embedded` | Kotlin Serialization、RikkaHub 内部 JLatexMath、Jackson/Auth0 JWT、JVM-only API 告警抑制 | embedded RikkaHub 聊天/设置页运行时、JWT/Jackson 反射链、Ktor/JVM 可选 API |
| `external/rikkahub/document` | MuPDF 与文档解析器 keep | RikkaHub 文档解析能力 |

---

## 3. 第三方库混淆矩阵

### 3.1 需要 keep 规则的库（反射/JNI/动态加载）

| 库 | 版本 | 反射机制 | 规则位置 | 规则类型 |
|----|------|----------|----------|----------|
| **lsp4j** | 0.24.0 | Gson 反射序列化 + 动态代理 + `@JsonRequest` 注解路由 | `core:lsp/consumer-rules.pro` | `-keepclassmembers` 字段 + `-keep interface` 服务接口 + `-keep class` jsonrpc/launch 框架 |
| **msgpack-core** | 0.9.8 | `Class.forName("MessageBufferU")` 动态加载 buffer 实现 | `core:network/consumer-rules.pro` | `-keep class` buffer 包所有类 |
| **android-tree-sitter** | 4.3.2 | JNI 硬编码字段名（`TSNode.context0` 等）+ 工厂反射 | `external/.../consumer-rules.pro` | `-keep class` 全部 + `-keepclassmembers` JNI 字段 |
| **JGit** | 7.1.0 | NLS 反射 (`TranslationBundle.lookupBundle(Class)`) + ServiceLoader transport | `core:git/consumer-rules.pro` | `-keep class` bundle 子类字段/构造器 + `-keep class` transport |
| **JGit SSH Apache** | 7.1.0 | SSHD 引用桌面 JDK API（`javax.management` 等） | `core:git/consumer-rules.pro` | `-dontwarn` |
| **LuaJava** | 4.1.0 | JNI native 方法 + Lua↔Java 反射回调 | `core:plugin/consumer-rules.pro` | `-keep class` + `-keepclasseswithmembernames` native |
| **zstd-jni** | 1.5.5-11 | JNI native 加载（`Zstd` 类名已由 R8 保留） | 无需额外（JNI 全局规则覆盖） | — |
| **xcrash** | — | JNI 崩溃回调 (`crashCallback`/`traceCallback`) | `external/xcrash/proguard-rules.pro` | `-keep class` NativeHandler |
| **SDL2 / SDL3** | — | JNI 从 native 侧通过硬编码类名调用 Java 层 | `app/proguard-rules.pro` | 保留 `org.libsdl2.app.**` 与 `org.libsdl.app.**` |
| **Termux Terminal** | — | JNI + View XML 构造函数 | `feature:terminal/consumer-rules.pro` | `-keep class` JNI + View 构造 |
| **JLatexMath** | 1.3 | 反射加载字体资源和符号映射配置文件 | `app/proguard-rules.pro` | `-keep class ru.noties.jlatexmath.** { *; }` |
| **Jsoup** | 1.22.1 | 安全网（自带 consumer-rules，但保险起见保留核心类；其可选 `re2j` 依赖在 Android 未引入时需抑制 R8 告警） | `app/proguard-rules.pro` | `-keep class org.jsoup.** { *; }` + `-dontwarn com.google.re2j.**` |
| **BouncyCastle** (prov / pkix) | 1.78.1 | JCA/JCE Provider 内部按算法字符串（`SHA256withRSA` 等）SPI/反射查找实现类 | `core:apk-builder/consumer-rules.pro` | `-keep class org.bouncycastle.{jcajce,jce,cert,operator}.**` + `-dontwarn` |
| **apksig** | 8.11.1 | `Asn1BerParser` / `Asn1DerEncoder` 通过 `@Asn1Class`、`@Asn1Field`、`getDeclaredFields()`、`getDeclaredAnnotation()` 解析 X.509 / PKCS#7 ASN.1 模型 | `core:apk-builder/consumer-rules.pro` | `-keepattributes` 运行时注解属性 + `-keep class com.android.apksig.internal.asn1.** { *; }` + `-keep @Asn1Class ... { *; }` + `@Asn1Field` 字段兜底 |
| **RikkaHub embedded** | external/rikkahub 版本 | Kotlin Serialization、Jackson `TypeReference`、Auth0 JWT、RikkaHub 内部 JLatexMath、Ktor JVM-only 检测类引用 | `external/rikkahub/embedded/consumer-rules.pro` | `-keep @Serializable`、`-keep class com.fasterxml.jackson.**`、`-keep class com.auth0.jwt.**`、`-dontwarn java.lang.management.*` / `java.beans.*` |
| **MuPDF / RikkaHub document** | external/rikkahub 版本 | JNI 与反射解析入口 | `external/rikkahub/document/consumer-rules.pro` | `-keep class com.artifex.mupdf.**` + 文档 parser keep |

### 3.2 无需额外规则的库（安全可混淆）

| 库 | 原因 |
|----|------|
| **Kotlin Stdlib** | R8 内置支持 |
| **Kotlin Coroutines** | R8 内置支持 + `app/proguard-rules.pro` 通用规则 |
| **Kotlin Serialization** | 编译器生成序列化器，不依赖反射字段名 |
| **Jetpack Compose** | Compose 编译器插件 + R8 内置完整支持 |
| **AndroidX (core/lifecycle/activity/room/work/datastore)** | 自带 consumer-rules.pro |
| **Room** | AGP `legacy-kapt` 编译时运行 Room compiler 生成 `Dao_Impl`，不依赖运行时反射 |
| **Koin** | 编译时类型解析（`by inject<T>()`），无运行时反射发现 |
| **OkHttp** | 自带 consumer-rules.pro |
| **Coil 3** | 自带 consumer-rules.pro |
| **Material Design** | 自带 consumer-rules.pro |
| **commons-compress** | 纯 Java IO，不依赖反射 |
| **tukaani-xz** | 纯 Java IO，不依赖反射 |
| **XXPermissions** | View 构造函数由全局规则覆盖 |
| **ImmersionBar** | View 构造函数由全局规则覆盖 |
| **JetBrains Markdown** | AST 解析器，纯 Kotlin，无反射 |
| **Timber** | 纯日志库，无反射 |
| **ARSCLib** | 纯 Java IO 解析 resources.arsc / AndroidManifest（仅抑制告警，保留 `-dontwarn com.reandroid.**` 作为安全网） |
| **Parcelize** | Kotlin 编译器插件在编译时生成 CREATOR，无运行时反射 |

---

## 4. 常见 R8 问题模式

### 4.1 ClassCastException（无消息）

**症状**：`java.lang.ClassCastException`（无 "Cannot cast X to Y" 描述）

**原因**：R8 类型优化插入 `checkcast` 指令。当反射/反序列化返回的实际类型与 R8 静态推断的类型不符时触发。

**典型场景**：
- Gson/Jackson 反射反序列化模型类（字段名被重命名，JSON key 不匹配）
- 动态代理返回类型不匹配
- `Either<A, B>` 等泛型联合类型的类型适配器

**解法**：`-keepclassmembers` 保留字段名

**案例**：lsp4j Gson 序列化 — `InitializeParams.capabilities` 被重命名为 `f`，clangd 返回 `"capabilities": {...}` 匹配失败

### 4.2 ClassNotFoundException / NoClassDefFoundError

**症状**：`java.lang.ClassNotFoundException: com.example.SomeClass`

**原因**：被 `Class.forName()` 或 ServiceLoader 硬编码字符串引用的类被 R8 重命名或删除。

**典型场景**：
- `Class.forName("fully.qualified.ClassName")` — 字符串引用不被 R8 追踪
- `ServiceLoader.load(SomeInterface.class)` — `META-INF/services` 文件中的类名可能被重命名
- 静态初始化器 `<clinit>` 中的反射加载

**解法**：`-keep class` 保留完整类名

**案例**：msgpack `MessageBufferU` — `Class.forName("org.msgpack.core.buffer.MessageBufferU")` 但该类被 R8 删除

### 4.3 NoSuchFieldError / NoSuchMethodError

**症状**：`java.lang.NoSuchFieldError: fieldName`

**原因**：JNI native 代码通过硬编码字段名/方法名访问 Java 对象，R8 重命名后 JNI 找不到。

**典型场景**：
- Tree-sitter `TSNode.context0` 等 JNI 字段
- xcrash `NativeHandler.crashCallback()`
- SDL native→Java 回调

**解法**：`-keepclassmembers` 保留 JNI 访问的字段名和方法名

### 4.4 NLS / ResourceBundle 加载失败

**症状**：异常信息包含 `MissingResourceException`、空字符串，或 release 包出现 `NoSuchMethodException: org.eclipse.jgit.internal.JGitText.<init>[]`

**原因**：JGit NLS 系统通过 `TranslationBundle.lookupBundle(SomeText.class)` 反射创建 bundle 子类，并按字段名注入 ResourceBundle。仅保留类名时，R8 仍可能删除默认构造器或改写字段名，导致初始化失败。

**解法**：`-keep class * extends TranslationBundle { <init>(); <fields>; }`，同时保留 bundle 子类、默认构造器和字段名

### 4.5 运行时注解反射链被部分优化

**症状**：release 包运行时报 `NullPointerException`，栈里出现 `TimSort` / `Collections.sort` / 合成 `Comparator`，反混淆后指向类似 `Asn1BerParser.lambda$parseSequence$0`、`Asn1DerEncoder.lambda$toSequence$0`。

**原因**：库先用 `getDeclaredFields()` 枚举字段，再用 `getDeclaredAnnotation()` 读取字段注解并排序/编解码。R8 可能保留了模型类本身，却删除字段、字段注解或优化解析器/编码器逻辑，导致排序时读取到空注解。

**典型场景**：apksig ASN.1 模型（`@Asn1Class` / `@Asn1Field`）解析 X.509 / PKCS#7 证书与签名块。

**解法**：同时保留运行时注解属性、注解类型、解析器/编码器、被 `@Asn1Class` 标记的模型类全部成员，以及被 `@Asn1Field` 标记的字段。不要只写 `-keep class ... { <init>(); <fields>; }`，因为这不足以阻止 R8 对注解反射链做局部优化。

---

## 5. 新增库 Checklist

引入新的第三方库时，按以下步骤判断是否需要 keep 规则：

### Step 1: 检查库是否自带 consumer-rules

```bash
# 解压 AAR/JAR 查看
unzip -l ~/.gradle/caches/.../library.aar | grep proguard
```

自带规则的库（OkHttp、Koin、Coil、AndroidX 等）通常无需额外处理。

### Step 2: 识别反射机制

检查库是否使用以下机制：

| 机制 | 检查方式 | 需要的规则 |
|------|----------|------------|
| **Gson / Jackson 反射序列化** | `import com.google.gson` / `@SerializedName` | `-keepclassmembers` 保留字段名 |
| **Class.forName()** | 搜索源码 `Class.forName` | `-keep class` 保留类名 |
| **ServiceLoader** | 搜索 `META-INF/services` | `-keep class` 保留实现类 |
| **JNI native 方法** | `native` 关键字 + `.so` 文件 | `-keepclasseswithmembernames` native |
| **动态代理** | `Proxy.newProxyInstance` / `Launcher` | `-keep interface` 保留接口 |
| **NLS / ResourceBundle** | `ResourceBundle.getBundle(className)` / 反射创建 bundle | `-keep class` 保留 bundle 类、默认构造器和字段 |
| **注解处理器** | `@JsonRequest` / `@JsonNotification` | 注解保留（全局 `-keepattributes *Annotation*` 已覆盖） |
| **运行时注解 + 字段反射** | 搜索 `getDeclaredFields()` / `getDeclaredAnnotation()` / 字段注解（如 apksig `@Asn1Field`） | 同时保留注解属性、解析器/编码器类、被注解模型类及被注解字段；不要只保留类名或构造函数 |

### Step 3: 编写规则

1. 在**引入依赖的模块**的 `consumer-rules.pro` 编写规则
2. 添加注释说明反射机制和原因
3. 优先使用精确规则（`-keepclassmembers` > `-keep class`），避免 `-keep class xxx.** { *; }` 阻止 tree-shake

### Step 4: 验证

```bash
# 构建 release
./gradlew :app:assembleArm64Release

# 检查最终 R8 配置确认 consumer-rules 已合并
rg -n "com.example.library|关键 keep 规则" app/build/outputs/mapping/arm64Release/configuration.txt

# 检查 usage，确认反射入口、注解模型和关键字段没有被删除
rg -n "com.example.library|关键反射模型" app/build/outputs/mapping/arm64Release/usage.txt
```

> 对 apksig 这类“运行时注解 + 字段反射”库，必须额外确认 `usage.txt` 中没有删除 `com.android.apksig.internal.asn1.**`、`@Asn1Class` 模型类或 `@Asn1Field` 字段；仅看到 `mapping.txt` 中类还存在并不代表字段注解链完整。

> 对本项目文件分享入口，使用 `core:storage` 的 `TinaFileProvider` 统一生成 `content://` URI。它显式绑定 `R.xml.file_paths`，并在生成 URI 时不依赖 PackageManager 返回 `android.support.FILE_PROVIDER_PATHS` meta-data；新增/调整 Provider 时必须同步检查 `consumer-rules.pro` 与 Release APK Manifest。

---

## 6. 历史问题记录

| 日期 | 问题 | 根因 | 修复 |
|------|------|------|------|
| 2026-03-07 | `ClassCastException` in LspClientSession.connect | lsp4j 响应模型类（`InitializeResult`、`ServerCapabilities` 等）被 R8 删除。`-keepclassmembers` 只保留字段名不阻止类删除；仅通过 Gson 反射实例化的类被 R8 判定为死代码 | `core:lsp/consumer-rules.pro` 从 `-keepclassmembers` 改为 `-keep class org.eclipse.lsp4j.** { <fields>; }` 阻止类删除 |
| 2026-03-07 | `ClassNotFoundException: MessageBufferU` | msgpack `MessageBuffer.<clinit>` 通过 `Class.forName()` 加载 `MessageBufferU`，R8 将其当死代码删除 | `core:network/consumer-rules.pro` 添加 `-keep class org.msgpack.core.buffer.** { *; }` |
| 2026-03-07 | Tree-sitter `TSParser$Native.newParser()` JNI 解析失败 | Tree-sitter JNI 字段名被混淆 | 添加 `external/tina-android-tree-sitter/android-tree-sitter/consumer-rules.pro`（完整 JNI 规则）|
| 2026-03-24 | `R8: Missing class com.google.re2j.Matcher/Pattern` | Jsoup 1.22.1 引用了可选的 `re2j` 正则实现；Android app 未打入该依赖，release 混淆阶段触发缺失类检查 | `app/proguard-rules.pro` 添加 `-dontwarn com.google.re2j.**` |
| 2026-03-07 | `UnsatisfiedLinkError: TSParser$Native.newParser()` native library 未加载 | external 模块 `build.gradle.kts` 缺少 `consumerProguardFiles` 声明，consumer-rules.pro 从未被 AGP 合并；R8 将 `TreeSitter` 类（含 `System.loadLibrary` 调用）标记为 `REMOVED`，native library 永远不会被加载 | 1) 在 `external/.../build.gradle.kts` 添加 `consumerProguardFiles("consumer-rules.pro")`；2) 在 `app/proguard-rules.pro` 添加安全网 `-keep class com.itsaky.androidide.treesitter.** { *; }` |
| 2026-04-19 | 预防性补齐：`core:apk-builder` 引入 BouncyCastle 后 `consumer-rules.pro` 仍为空 | 新模块 `core:apk-builder`（2026-04 commit `8837f2d9b` 起活跃）通过 `JcaX509v3CertificateBuilder` / `JcaContentSignerBuilder("SHA256withRSA")` 生成/签名 p12 keystore。BC 在 `build(privateKey)` 内部按算法字符串 SPI/反射加载实现类，release 下易出 `NoSuchAlgorithmException` | 在 `core:apk-builder/consumer-rules.pro` 精确 keep BC 的 `jcajce/jce/cert/operator` 子包 + `-dontwarn javax.naming.**`；ARSCLib 仅保留 `-dontwarn` 安全网 |
| 2026-04-30 | Release 打包 APK 签名失败：`SubjectPublicKeyInfo is not annotated with Asn1Class` | `apksig` 的 `Asn1BerParser` 通过运行时注解和反射解析 `SubjectPublicKeyInfo` / `RSAPublicKey` 等内部模型；R8 移除类级 `@Asn1Class` 注解后，签名阶段无法识别公钥模型 | 第一版规则保留了运行时注解属性、`@Asn1Class` 模型类无参构造和字段，但后续发现仍不足以保护完整 ASN.1 字段注解链；见下一条 |
| 2026-04-30 | Release 包内再次打包 APK 报 `NullPointerException`：反混淆后为 `Asn1BerParser.lambda$parseSequence$0` / `Collections.sort` | 第一版 apksig 规则范围太窄，只保留模型类构造和字段，没有强保留 `com.android.apksig.internal.asn1.**` 解析器/编码器和 `@Asn1Field` 字段注解反射链；R8 局部优化后排序读取到空注解 | 将 `core:apk-builder/consumer-rules.pro` 升级为完整 ASN.1 规则：保留 `RuntimeVisibleAnnotations` / `AnnotationDefault` / `Signature` / 内部类属性，强保留 `com.android.apksig.internal.asn1.** { *; }`、`@Asn1Class` 模型全部成员，并用 `@Asn1Field <fields>` 兜底 |
| 2026-04-30 | APK Builder 打包成功后点击“安装”失败：Release 日志显示 `Missing android.support.FILE_PROVIDER_PATHS meta-data`，即使最终 APK Manifest 中存在该 meta-data，设备侧 PackageManager 仍可能返回不可加载状态 | 单纯依赖 AndroidX `FileProvider.getUriForFile()` 会在生成 URI 阶段强依赖 PackageManager 的 `FILE_PROVIDER_PATHS` meta-data；一旦设备侧读取失败，安装入口直接失败 | 在 `core:storage` 引入 `TinaFileProvider`：Manifest 指向该 Provider，Provider 构造显式绑定 `R.xml.file_paths`；`ExternalFileIntents` 手工按同一份路径规则生成 `content://` URI，不再依赖 PackageManager meta-data；`core/storage/consumer-rules.pro` 保留 Provider 类和构造路径 |

---

## 7. `-keep` 规则速查

| 规则 | 保留类名 | 保留成员名 | 阻止删除 |
|------|----------|------------|----------|
| `-keep class X { *; }` | YES | YES | YES |
| `-keepclassmembers class X { ... }` | NO | YES | 仅成员 |
| `-keepnames class X` | YES | NO | NO |
| `-keepclassmembernames class X { ... }` | NO | YES | NO |
| `-keepclasseswithmembernames class X { native <methods>; }` | YES | YES (native) | YES |

**原则**：
- **Gson 字段** → `-keepclassmembers`（保留字段名，允许类名混淆）
- **Class.forName** → `-keep class`（必须保留类名）
- **JNI** → `-keepclasseswithmembernames` + native（保留 native 方法签名）
- **动态代理接口** → `-keep interface`（保留方法签名）
- **ResourceBundle** → `-keepnames class`（保留类名，允许优化成员）
