# MT Data Files Provider

MT Data Files Provider 是 TinaIDE 为 MT 管理器提供的 DocumentsProvider 入口，用于在无 ROOT 场景下访问 TinaIDE 自己的应用目录。

## 默认状态

- Manifest 中 Provider 和 WakeUp Activity 默认 `android:enabled="true"`。
- `ConfigKeys.MTFileProviderEnabled` 默认值为 `true`。
- 应用启动时由 `MTFileProviderManager.initialize()` 按用户配置启用或禁用组件。

因此，新安装或未显式修改过开关的用户默认可以通过 MT 管理器访问 TinaIDE 自己的目录；用户在设置中关闭后，启动时会按配置禁用组件。

## 开启后暴露的根目录

开启后仅暴露 TinaIDE 自己的四类目录：

- `data`：应用私有数据目录，通常对应 `/data/data/<packageName>`。
- `android_data`：外部存储应用目录，通常对应 `Android/data/<packageName>`。
- `android_obb`：OBB 目录，通常对应 `Android/obb/<packageName>`。
- `user_de_data`：设备加密存储目录，通常对应 `/data/user_de/<userId>/<packageName>`。

不应新增系统目录、其他应用目录或用户任意路径作为根目录。

## 路径安全规则

文档 ID 必须严格为 `<packageName>` 或 `<packageName>/<root>/<relativePath>`。

Provider 必须拒绝：

- 非当前包名前缀的 docId。
- 空路径段、`.`、`..`。
- 绝对路径。
- 反斜杠路径。
- NUL 字符。
- displayName 中的 `/`、`\`、`.`、`..` 或空白名称。
- 指向暴露根目录外的 symlink。

所有文件解析必须经过 canonical root 校验，确保目标仍在当前暴露根目录内。

## 兼容边界

- `data`、`android_data`、`android_obb`、`user_de_data` 的名称是对 MT 管理器显示的稳定入口，不要轻易重命名。
- 目录遍历时，历史遗留的不安全 symlink 会被跳过，而不是让整个列表失败。
- `chmod` 只允许普通 Unix 权限位 `0x1FF`，不能允许外部传入额外模式位。

## 验证

修改该 Provider 或路径规则后至少运行：

```powershell
.\gradlew.bat :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.provider.MTDataFilesProviderPathSafetyTest" --no-daemon --console=plain
.\gradlew.bat :app:compileArm64DebugKotlin --no-daemon --console=plain
```
