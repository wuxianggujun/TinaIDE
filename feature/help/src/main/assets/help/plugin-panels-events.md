# 插件面板与事件联动

这节把 Script 插件从“点一下执行”推进到“响应 IDE 状态并持续展示结果”。面板只承载有界纯文本，不承载 HTML、WebView 或任意 Compose UI。

## 声明一个面板

先在 manifest 的 `contributions.panels` 中声明面板：

```json
{
  "contributions": {
    "panels": [
      {
        "id": "status",
        "title": "Plugin Status"
      }
    ]
  }
}
```

插件启用后，编辑器底部会出现“插件”页签。每个插件最多声明 16 个面板；面板 ID 必须在当前插件内唯一。

## 三个面板 API

```lua
tina.panels.setContent("status", "Plugin loaded")
tina.panels.appendContent("status", "\nBuild started")
tina.panels.clear("status")
```

- `setContent` 替换当前内容
- `appendContent` 追加内容
- `clear` 清空指定面板

宿主会限制面板文本大小。面板适合状态、摘要和短日志，不适合持续倾倒完整编译输出。

## 监听宿主事件

```lua
function on_project_opened(data)
  local root = data and data.rootPath or "unknown"
  tina.panels.setContent("status", "Project: " .. root)
  tina.log.info("Project opened: " .. root)
end

function on_diagnostics_changed(data)
  local errors = data and data.errorCount or 0
  local warnings = data and data.warningCount or 0
  local summary = "Errors=" .. errors .. ", warnings=" .. warnings
  tina.panels.setContent("status", summary)
end

tina.events.on("project.opened", "on_project_opened")
tina.events.on("diagnostics.changed", "on_diagnostics_changed")
```

推荐从这些稳定事件开始：

- `project.opened` / `project.closed`
- `build.started` / `build.finished`
- `editor.opened` / `editor.closed`
- `editor.activeChanged`
- `editor.selectionChanged`
- `editor.dirtyChanged` / `editor.saved`
- `file.created` / `file.deleted` / `file.renamed`
- `diagnostics.changed`

事件回调应保持短小。需要聚合时，只保留必要状态并刷新摘要，不要在每次光标或选择区变化时做大范围文件扫描。

## 自定义事件

同一插件运行时可以发送并接收自定义事件：

```lua
function on_refresh(data)
  local reason = data and data.reason or "manual"
  tina.panels.appendContent("status", "\nRefresh: " .. reason)
end

tina.events.on("custom", "on_refresh")
tina.events.emit("custom", { reason = "startup" })
```

自定义事件数据同样受序列化深度、条目数和结果大小限制。不要把大型文件内容塞进事件 payload。

## 生命周期与清理

以下情况会自动清理插件面板内容和运行时所有权：

- 插件被禁用或卸载
- 插件进入自动隔离
- isolated runtime 异常退出
- 宿主结束对应 owner 生命周期

插件不要假设旧面板内容会跨重启恢复。需要持久状态时使用 `tina.storage` 或 `tina.db`，启动后重新生成面板摘要。

## 一个稳妥的状态面板模式

1. 加载时 `setContent` 写入初始状态
2. 监听少量高价值事件
3. 每次只更新摘要
4. 详细信息写插件日志
5. 持久数据写插件存储
6. 回调失败时记录错误并尽快返回

## 验证步骤

1. 热安装并启用插件
2. 打开编辑器，确认底部出现“插件”页签和 `status` 面板
3. 打开项目、保存文件、触发诊断，确认摘要变化
4. 禁用插件，确认面板内容被清理
5. 重新启用，确认面板从初始状态重新建立
6. 查看插件日志，确认没有高频重复错误

## 继续学习

- [Script API 与最小权限](plugin-script-api.md)
- [LSP 插件开发与排错](plugin-lsp-troubleshooting.md)
- [插件测试、自愈与发布前检查](plugin-testing-recovery.md)
- [插件设置说明](plugins-settings.md)
