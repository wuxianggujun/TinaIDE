# SmartDns 循环依赖导致 StackOverflowError 崩溃报告

## 崩溃信息

- **应用版本**: 0.17.5
- **设备**: Redmi K80 Pro (Android 16, API 36)
- **异常类型**: `java.lang.StackOverflowError`
- **栈大小**: 8188KB
- **崩溃时间**: 2026-05-31T17:25:33.503+0800

## 问题根因

`SmartDns` 和 `OkHttpClientProvider.probe` 两个 `lazy` 属性之间存在**循环初始化依赖**，导致无限递归直至栈溢出。

### 调用链路（循环）

```
SmartDns (lazy 初始化)
  └→ 创建 DohDnsResolver() 实例
       └→ DohDnsResolver.<init> 中访问 OkHttpClientProvider.probe
            └→ OkHttpClientProvider.probe (lazy 初始化)
                 └→ 构建 OkHttpClient 时引用 SmartDns
                      └→ SmartDns 仍在初始化中，再次触发 DohDnsResolver() 创建
                           └→ 再次访问 OkHttpClientProvider.probe
                                └→ ... 无限循环 ...
                                     └→ StackOverflowError 💥
```

### 涉及的关键代码

**文件 1: `core/network/src/main/java/com/wuxianggujun/tinaide/core/network/SmartDns.kt`**

```kotlin
// 第 62 行 — DohDnsResolver 的构造
private val client: OkHttpClient = OkHttpClientProvider.custom(OkHttpClientProvider.probe) {
    dns(Dns.SYSTEM)
}

// 第 216-220 行 — SmartDns 全局 lazy 实例
internal val SmartDns: Dns by lazy {
    val dohResolver = DohDnsResolver()       // ← 触发 DohDnsResolver 初始化
    val smartResolver = SmartDnsResolver(dohResolver)
    OkHttpSmartDns(smartResolver)
}
```

**文件 2: `core/network/src/main/java/com/wuxianggujun/tinaide/core/network/OkHttpClientProvider.kt`**

```kotlin
// 第 30-38 行 — probe 客户端引用了 SmartDns
val probe: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(SmartDns)          // ← 反向引用 SmartDns，形成循环
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
}
```

### 为什么会发生

Kotlin 的 `by lazy` 默认使用 `LazyThreadSafetyMode.SYNCHRONIZED`，但这只保证**线程安全**，并不防止**同一线程内的重入**。当 `SmartDns` 的 lazy lambda 正在执行时，内部又触发了对自身的访问，lazy 机制无法识别这是同一次初始化中的递归调用，于是再次执行 lambda，形成无限递归。

## 修复方案

### 修改文件

`core/network/src/main/java/com/wuxianggujun/tinaide/core/network/SmartDns.kt` — 第 62 行

### 修改前

```kotlin
private val client: OkHttpClient = OkHttpClientProvider.custom(OkHttpClientProvider.probe) {
    dns(Dns.SYSTEM)
}
```

### 修改后

```kotlin
// 使用独立的 OkHttpClient，避免通过 OkHttpClientProvider.probe 间接引用 SmartDns 导致循环初始化
private val client: OkHttpClient = OkHttpClient.Builder()
    .dns(Dns.SYSTEM)
    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()
```

### 修复原理

`DohDnsResolver` 的职责是通过 DoH（DNS over HTTPS）协议查询域名，它本身只需要一个能发出 HTTPS 请求的普通 HTTP 客户端，并且必须使用系统 DNS（`Dns.SYSTEM`）来解析 DoH 服务器地址。

修复前的代码通过 `OkHttpClientProvider.probe` 获取基础客户端再覆盖 DNS 配置，但 `probe` 客户端本身就依赖 `SmartDns`，这在语义上也是不合理的——**DoH 解析器不应该依赖 SmartDns 来创建自己的 HTTP 客户端**。

修复后直接创建一个独立的 `OkHttpClient`，使用与原配置相同的超时和重定向参数，完全绕开 `OkHttpClientProvider`，从而打破循环依赖。

### 修复后的依赖关系

```
SmartDns (lazy)
  └→ 创建 DohDnsResolver() — 使用独立的 OkHttpClient（Dns.SYSTEM）✅
       └→ 不再依赖 OkHttpClientProvider.probe

OkHttpClientProvider.probe (lazy)
  └→ 引用 SmartDns — 正常工作，因为 SmartDns 不再反向依赖 probe
```

循环被打破，初始化顺序变为线性的单向依赖。
