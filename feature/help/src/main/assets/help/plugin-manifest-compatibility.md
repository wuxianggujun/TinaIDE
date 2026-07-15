# 插件 Manifest 与版本兼容

这节解决两个最容易混淆的问题：插件包应该声明哪些版本，以及旧版 TinaIDE 为什么不应该看到不兼容更新。

## 先记住四个字段

- `id`：插件的稳定身份。升级时不能随意更换。
- `version`：插件自身版本，例如 `0.2.0`。
- `apiVersion`：插件 API 契约版本。当前稳定值是 `1`，省略时也按 `1` 处理。
- `minAppVersion`：插件真正依赖的最低 TinaIDE 版本；不需要新宿主能力时可以省略。

一个兼容性清晰的最小 manifest：

```json
{
  "id": "com.example.safe-plugin",
  "name": "Safe Plugin",
  "version": "0.1.0",
  "apiVersion": 1,
  "minAppVersion": "1.4.0",
  "type": "config",
  "description": "A compatibility-aware TinaIDE plugin.",
  "author": {
    "name": "Your Name"
  }
}
```

不要照抄示例里的 `minAppVersion`。只有插件确实使用了该版本新增的宿主能力时才填写或提升它。

## 正确的更新选择逻辑

TinaIDE 的 Registry 会结合当前宿主版本和插件 manifest 选择可用版本：

1. 先过滤 `apiVersion` 不受支持的版本
2. 再过滤 `minAppVersion` 高于当前 IDE 的版本
3. 在剩余版本中选择最高插件版本
4. 下载后，安装、启用和运行前再次校验包内 manifest

因此，一个旧 IDE 可以继续使用旧插件版本，但不会看到只支持新 IDE 的更新。IDE 升级后，市场才会展示新的最高兼容版本。

这不是“同一个插件做两套包”。Registry 中可以保留同一插件的历史版本，每个版本只有一个真实 manifest 和一个插件包，宿主按兼容条件选择。

## 什么时候提升 `minAppVersion`

应该提升：

- 使用了新宿主才提供的 Script API
- 使用了新加入的 manifest contribution
- 依赖新版本才存在的权限或生命周期保证
- 旧宿主即使安装成功也无法正确运行

不应该提升：

- 只改 README、文案或示例
- 只修正插件内部逻辑
- 仍然只使用旧宿主已有能力
- 只是想让版本号“看起来更新”

## `permissions` 与 `optionalPermissions`

- `permissions` 是正常工作必需的权限
- `optionalPermissions` 是增强能力，需要用户在插件详情中逐项授予

不要把同一权限同时放进两个列表。可选权限没有授权时，相关 API 会被拒绝，但插件的基础功能应该仍然可用。

```json
{
  "permissions": [
    "editor.read",
    "command.execute"
  ],
  "optionalPermissions": [
    "workspace.write",
    "network.fetch"
  ]
}
```

## 升级前的检查清单

1. `id` 是否保持不变
2. `version` 是否严格高于已发布版本
3. `apiVersion` 是否仍为宿主支持的稳定值 `1`
4. `minAppVersion` 是否只在真实需要时提高
5. 必需权限是否最小化
6. 可选能力在未授权时是否能正常降级
7. Registry 元数据是否与包内 `id`、`version` 和兼容字段一致
8. 是否在旧 IDE 验证“看不到不兼容更新”，在新 IDE 验证“能看到并安装”

## 常见错误

### 新版本在旧 IDE 里仍显示

检查 Registry 的版本条目是否带有正确的 `apiVersion` 和 `minAppVersion`，以及包内 manifest 是否一致。

### 下载后才提示不兼容

说明市场元数据可能不完整或过旧。安装前再次拒绝是最后一道安全门禁，不应该删除；应修正 Registry 元数据。

### 为旧 IDE 单独复制一个插件 ID

不要这样做。复制 ID 会把更新链和用户配置拆成两个插件。保留同一 `id` 的历史兼容版本即可。

## 继续学习

- [插件开发快速开始](plugin-quick-start.md)
- [Script API 与最小权限](plugin-script-api.md)
- [插件测试、自愈与发布前检查](plugin-testing-recovery.md)
- [插件设置说明](plugins-settings.md)
