# ============================================================================
# TinaIDE ProGuard / R8 Rules
# ============================================================================
# 目标：最小化 DEX 体积，只 keep 反射 / JNI / 序列化必须保留的部分
# 原则：
#   - 绝不使用 -keep class xxx.** { *; }（阻止 tree-shake）
#   - 各子模块的 consumer-rules.pro 会自动合并，此处不重复
#   - JSON 序列化统一使用 Kotlin Serialization
#   - View 只保留 XML 构造函数

# ============================================================================
# 1. 基础配置
# ============================================================================

# @Keep 注解：精确标注不允许混淆的类/成员
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# 崩溃日志分析：保留行号
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 注解 & 泛型签名：很多库依赖注解反射、泛型类型擦除恢复
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ============================================================================
# 2. Android 组件 & JNI
# ============================================================================

# Application 入口
-keep class com.wuxianggujun.tinaide.TinaApplication { *; }

# 四大组件 + Fragment（AndroidManifest 反射实例化）
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.fragment.app.Fragment

# JNI native 方法（所有模块通用）
-keepclasseswithmembernames class * {
    native <methods>;
}

# View XML 构造函数（布局反射实例化）
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ============================================================================
# 3. ViewBinding
# ============================================================================

# inflate / bind 方法通过反射调用
-keep class * implements androidx.viewbinding.ViewBinding {
    public static ** inflate(android.view.LayoutInflater);
    public static ** inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
    public static ** bind(android.view.View);
}

# ============================================================================
# 4. Kotlin / Coroutines / Serialization
# ============================================================================

# kotlin.Metadata：Kotlin 反射需要
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlin.reflect.**

# Coroutines：ServiceLoader 反射加载 MainDispatcherFactory
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
# AtomicFU 生成的 volatile 字段
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# suspend 函数入口（R8 需要识别 Continuation）
-keep class kotlin.coroutines.Continuation
-keepclassmembers class * {
    *** invokeSuspend(...);
}

# Kotlin Serialization：编译器生成的 Companion 和 $$serializer
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}

# ============================================================================
# 5. 应用模型
# ============================================================================
# JSON 模型使用 Kotlin Serialization（见第 4 节规则），
# 不再保留历史 Gson/Jackson 反射规则。

# ============================================================================
# 6. Jetpack Compose（仅 dontwarn，不需要手动 keep）
# ============================================================================
# Compose 编译器插件 + R8 已内置完整支持。
# 手动 keep 会阻止 tree-shake 未使用的 Material Icons（2000+ 类，~20MB）。

-dontwarn androidx.compose.**
-dontwarn androidx.lifecycle.compose.**

# ============================================================================
# 7. 编辑器与 Tree-sitter
# ============================================================================

# Tree-sitter 规则已下沉到 core:tree-sitter/consumer-rules.pro：
# - android-tree-sitter 运行时/JNI keep 由其上游 consumer rules 提供
# - grammar bindings 的反射 keep 由 core:tree-sitter 自己提供

-dontwarn org.eclipse.tm4e.**

# ============================================================================
# 8. SDL Java 层（JNI 从 native 侧通过硬编码类名反射调用）
# ============================================================================

-keep class org.libsdl.app.** { *; }
-keep class org.libsdl2.app.** { *; }

# ============================================================================
# 10. Termux Terminal（→ feature/terminal/consumer-rules.pro）
# ============================================================================

# ============================================================================
# 11. 第三方库精简规则
# ============================================================================

# MessagePack：核心 buffer 规则已下沉到 core:network/consumer-rules.pro
# （MessageBuffer.<clinit> 通过 Class.forName 加载 MessageBufferU，需 -keep class）
-dontwarn org.msgpack.**

# （SnakeYAML Engine / Joni / Jcodings — 已无模块引入，R8 完全 tree-shake，无需规则）

# ============================================================================
# 12. 警告抑制
# ============================================================================

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn androidx.datastore.**
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.eclipse.lsp4j.**




# ============================================================================
# 14. Tree-sitter（安全网 + 项目适配层）
# ============================================================================
# android-tree-sitter 的完整 JNI 规则由 external/tina-android-tree-sitter/android-tree-sitter/consumer-rules.pro 提供
# （需在该模块 build.gradle.kts 中声明 consumerProguardFiles）。
# grammar 绑定的 Class.forName 规则由 core:tree-sitter/consumer-rules.pro 提供。
#
# 安全网：composite build (includeBuild) 的 consumer-rules 合并机制不如普通模块可靠，
# 以下规则确保即使 consumer-rules 未生效，关键类也不会被 R8 删除/重命名。
# 历史教训：R8 曾将 TreeSitter 类（含 System.loadLibrary 调用）标记为 REMOVED，
# 导致 native library 未加载 → UnsatisfiedLinkError。

# TreeSitter.loadLibrary() — 必须保留，否则 System.loadLibrary("android-tree-sitter") 被消除
-keep class com.itsaky.androidide.treesitter.TreeSitter { *; }

# TSParser / TSNode 等核心类 — JNI native 方法入口
-keep class com.itsaky.androidide.treesitter.** { *; }

# 项目自有的 tree-sitter 适配层
-keep class com.wuxianggujun.tinaide.core.treesitter.** { *; }

# ============================================================================
# 15. JLatexMath（LaTeX 数学公式渲染）
# ============================================================================
# jlatexmath 通过反射加载字体资源和符号映射配置文件
-keep class ru.noties.jlatexmath.** { *; }

# ============================================================================
# 16. Jsoup（HTML 解析）
# ============================================================================
# Jsoup 自带 consumer-rules，通常无需额外规则
# 安全网：保留解析器核心类防止 tree-shake
-keep class org.jsoup.** { *; }
# Jsoup 1.22.x 会引用可选的 re2j 正则实现；Android 产物未引入该依赖时，
# R8 会在 release 阶段报 Missing class com.google.re2j.*。
# 这里按 AGP 自动生成的 missing_rules.txt 抑制该可选依赖告警。
-dontwarn com.google.re2j.**

# ============================================================================
# 17. TinaFileProvider（显式 file_paths 资源）
# ============================================================================
# AndroidManifest.xml 使用 core:storage 中的 TinaFileProvider。
# 该 Provider 显式绑定 R.xml.file_paths，并在生成 content:// URI 时不再依赖
# PackageManager 返回 android.support.FILE_PROVIDER_PATHS meta-data。
# keep 规则位于 core/storage/consumer-rules.pro，app 侧不重复维护。

# ============================================================================
# 18. Android 资源名安全网（动态资源/反射访问）
# ============================================================================
# Release 同时启用 R8 与 resource shrinker。部分入口可能通过 manifest meta-data、
# getIdentifier/openRawResource、资源别名或第三方库按 R.* 字段/资源名定位资源。
# 保留 R 内部类与字段名，避免 release 混淆后资源反射链路失效导致启动或运行崩溃。
-keep class **.R$* { *; }
-keepclassmembers class **.R$* {
    public static <fields>;
}
