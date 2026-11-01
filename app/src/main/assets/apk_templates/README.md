# APK Templates

TinaIDE 将用户编译的 `.so` 文件注入这些模板 APK，生成独立可安装的 APK 文件。

## 文件列表

| 文件 | 说明 |
|------|------|
| `template-native-activity.apk` | NativeActivity 壳，加载 `libmain.so` |
| `template-sdl3.apk` | SDLActivity 壳 + SDL3 Java 桥接，加载 `libmain.so` |
| `template-checksums.txt` | 模板源码 SHA-256 快照，`buildApkTemplates` 生成，用于 `checkApkTemplatesSync` 校验 |
| `debug.keystore` | 调试签名密钥库（类型: `PKCS12`，别名: `tinaide-debug`，密码: `tinaide`） |

## 构建模板

模板项目位于 `tools/template-native-activity/` 和 `tools/template-sdl3/`；公共权限解析逻辑在 `tools/template-common/`。

终端可执行文件导出已迁移为独立插件，发布事实源位于：

```text
https://github.com/wuxianggujun/TinaIDE-Registry/tree/main/sources/plugins/tinaide.apk-export.terminal
```

主仓库内的 `plugins/tinaide.apk-export.terminal/` 仅作为历史本地副本和 Gradle
兼容打包入口；当前客户端市场发布包从 Registry 的 `plugins/index.v3.json` 和 `plugin.v3.json` 分发，
不再回退读取 `plugins/index.json`。如确实需要服务旧客户端，应在 Registry 仓库显式生成 v1 兼容索引。

一键构建并复制到此目录：

```bash
./gradlew buildApkTemplates
```

或分别构建：

```bash
./gradlew :tools:template-native-activity:assembleRelease
./gradlew :tools:template-sdl3:assembleRelease
```

## 同步校验（防止 APK 与源码脱节）

`buildApkTemplates` 会在拷贝 APK 之后，把每个模板的**源输入集合**（`tools/template-*/src/`、
`build.gradle.kts`、`tools/template-common/` 以及 SDL3 额外依赖的 `app/src/main/java/org/libsdl/app/`）
的 SHA-256 写入 `template-checksums.txt`。

上述文本输入由根目录 `.gitattributes` 固定为 LF，避免 Windows 的 CRLF 与 Linux CI 的 LF
造成相同源码的哈希不同；二进制资源保持原字节。新增模板输入类型时也应检查该约束。

```bash
./gradlew checkApkTemplatesSync
```

该任务重新计算实际源码的哈希并与 `template-checksums.txt` 比对，不一致时失败并提示运行
`./gradlew buildApkTemplates` 重新打包。开发者改完模板源码后提交时，**同时提交**新的 APK 与
`template-checksums.txt`；review 时看这份纯文本 diff 就能判断模板是否已刷新。

同一次调用可以执行 `buildApkTemplates checkApkTemplatesSync`；校验任务在模板拷贝后运行，
单独执行校验仍不会悄悄重建或覆盖资产。模板的 Java 源码显式使用 UTF-8 编译。

## 模板权限能力

从 v0.14 起，内置模板都通过 `com.tinaide.template.common.TemplatePermissionResolver` 从
`PackageInfo.requestedPermissions` 动态读取权限清单，按 SpecialPermission（受限权限）与普通危险权限分桶依次请求。
用户在 `ApkPackageDialog` 勾选什么、自定义框里写什么，运行时就请求什么。

拒绝后引导到系统设置前会弹出一次性 `DeviceDefault` 主题的解释对话框，文案在各模板的
`src/main/res/values{,-en}/strings.xml` 中维护。

## 图形运行与退出

NativeActivity 与 SDL3 模板都是单个图形应用的独立壳，不复用 IDE 的进程或 UI 依赖。

- 入口使用 `singleTask`，重复启动 Intent 不得在同一进程里创建两个引擎实例。
- 系统 Back/手势明确结束图形 Activity，不依赖 Android 12+ 根 Activity 默认的“退到后台”行为。
- 两个壳共用 `TemplateRuntimeExitGuard`：在 `finish()` / native 销毁回调前启动独立线程的
  2 秒退出保护；正常销毁完成立即回收本 APK 的进程。下一次打开从干净的 native 状态启动。
- Home、普通暂停/后台不启动保护，没有游戏总运行时限。权限设置跳转也不作为退出。
- 这不是任意多 Activity / 长驻 Service 应用的通用退出策略；若扩展模板为这类应用，
  应先将图形运行隔离到专用进程，不能直接复用“回收本进程”的单图形应用约定。

纯 Java 退出状态测试位于 `tools/template-common/src/test/`。重新构建后仍要分别验证
导出的 APK：手势返回、桌面图标重开、重复启动 Intent、Home 恢复；IDE 内运行通过不代替导出验收。
SDL3 模板直接依赖版本目录中的 AndroidX Core，保证共享 SDL 桥接的 API 33 receiver flags 可用。

## Manifest 补丁机制

`core/apk-builder/ManifestPatcher` 使用 `reandroid` 的 AXML 二进制 API
(`AndroidManifestBlock.setPackageName` / `setVersionCode` / `setValueAsString`) 原地改写
模板 APK 中的 `AndroidManifest.xml`：

- 包名：来自对话框输入，无长度/字符限制（AXML 字符串池支持任意 UTF-16 内容）。
- 应用名：写入 `application.label`，无需 XML 转义（二进制存储）。
- `versionCode` / `versionName`：对话框输入透传。
- 权限：`patchUsesPermissions` 先清空模板自带的 `uses-permission` 节点，再按用户配置重新注入；
  `WRITE_EXTERNAL_STORAGE` 会自动追加 `android:maxSdkVersion="29"`。

因此模板自身的 `AndroidManifest.xml` 与 `applicationId` 只作为 placeholder 存在，
用于让模板自身能打包通过；打包出的用户 APK 与这些占位值无关。

## 重新生成 debug.keystore

```bash
keytool -genkeypair -v -keystore debug.keystore \
  -alias tinaide-debug \
  -keyalg RSA -keysize 2048 \
  -validity 36500 \
  -storetype PKCS12 \
  -storepass tinaide -keypass tinaide \
  -dname "CN=TinaIDE Debug, OU=TinaIDE, O=TinaIDE, L=Unknown, ST=Unknown, C=CN"
```

