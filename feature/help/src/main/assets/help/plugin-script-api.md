# Script API 与最小权限

这节从一个“能点击、能修改编辑器、能留下日志”的命令插件开始。目标不是一次用完所有 API，而是建立最小权限和可验证闭环。

## 先选模板

第一次写 Lua 插件，优先选择 `Tina Script Command Plugin`。它已经包含命令声明、菜单绑定、编辑器读写和日志示例。

脚本运行在独立的 isolated process 中，并受执行时间、内存、结果大小和危险 API 沙箱限制。不要依赖 `io`、`debug`、`loadfile`、`dofile`、native `loadlib` 或 Java 反射。多文件脚本使用受限 `require("module.name")` 加载插件目录内模块。

## 最小 manifest

```json
{
  "id": "com.example.insert-header",
  "name": "Insert Header",
  "version": "0.1.0",
  "apiVersion": 1,
  "type": "script",
  "description": "Insert a comment header into the active editor.",
  "author": {
    "name": "Your Name"
  },
  "main": "main.lua",
  "contributions": {
    "commands": [
      {
        "id": "com.example.insert-header.run",
        "title": "Insert Header"
      }
    ],
    "menus": {
      "editor/context": [
        {
          "command": "com.example.insert-header.run",
          "group": "5_editor"
        }
      ]
    }
  },
  "permissions": [
    "editor.read",
    "editor.write",
    "command.execute"
  ]
}
```

`contributions.commands` 只提供命令元数据；真正的回调还要在 Lua 中注册。注册插件命令和执行宿主命令都需要 `command.execute`。

## 最小 main.lua

```lua
local command_id = "com.example.insert-header.run"

function insert_header()
  local editor = tina.editor.getActiveEditor()
  if editor == nil then
    tina.log.warn("No active editor")
    return
  end

  local file_name = editor.fileName or "unknown"
  local header = "// Generated for " .. file_name .. "\n"
  local ok = tina.editor.insertText(header, 0, 0)
  if ok then
    tina.log.info("Inserted header into " .. file_name)
  else
    tina.log.warn("insertText returned false")
  end
end

local ok, err = tina.commands.register(
  command_id,
  "insert_header",
  "Insert Header"
)

if not ok then
  tina.log.warn("Register failed: " .. tostring(err))
end
```

## API 按用途选择

### 用户与日志

- `tina.ui`：显示有界用户消息，需要 `ui.notification`
- `tina.log`：写入插件日志，排错时优先使用

### 编辑器

- `tina.editor.getActiveEditor()`：读取活动编辑器，需要 `editor.read`
- `tina.editor.getSelection()`：读取选择区，需要 `editor.selection`
- `tina.editor.insertText(...)`：插入文本，需要 `editor.write`
- `tina.editor.replaceSelection(...)`：替换选择区，需要 `editor.write`

### 命令

- `tina.commands.register(...)`：注册插件回调
- `tina.commands.execute(...)`：执行白名单宿主命令或当前插件已注册命令

两者都需要 `command.execute`。manifest 命令 ID、菜单 command 和 Lua 注册 ID 必须完全一致。

### 工作区与诊断

- `tina.workspace`：在项目根目录白名单内查找和读写文件
- `tina.diagnostics`：读取当前诊断快照
- `tina.fs`：历史兼容命名空间，新插件优先使用 `tina.workspace`

先从只读权限开始。只有真正要写文件时才申请 `workspace.write`。

### 网络与存储

- `tina.network`：受权限、host allowlist、超时和大小限制
- `tina.storage` / `tina.db`：保存插件自己的有界数据

网络访问应同时声明 `network.fetch` 和所需 host 白名单。不要把 token、密码或用户源码写进日志。

## 可选权限的正确用法

非核心能力放进 `optionalPermissions`。调用前准备好失败分支：

```lua
local files, err = tina.workspace.findFiles("*.lua", 5)
if files == nil then
  tina.log.warn("Workspace scan unavailable: " .. tostring(err))
  return
end
```

`findFiles` 返回按 `/` 相对路径升序排列的结果，再应用数量上限。未触及扫描上限的相同目录树，即使文件创建顺序或宿主文件系统不同，前 N 项也保持一致。超大工作区触及 50,000 项扫描上限时，结果只代表已扫描集合，不能当作完整索引。

权限拒绝是正常业务结果，不应被当作 runtime crash，也不会触发自动隔离。

## 验证闭环

1. 点击运行完成热安装
2. 到 `设置 → 插件` 打开详情并启用
3. 打开代码文件，在编辑器菜单执行命令
4. 确认文件顶部出现注释
5. 打开插件日志，确认注册和执行日志
6. 撤销可选权限，确认基础命令仍能使用
7. 制造一个可恢复错误，确认错误被记录且宿主仍可操作

## 继续学习

- [插件 Manifest 与版本兼容](plugin-manifest-compatibility.md)
- [插件面板与事件联动](plugin-panels-events.md)
- [插件测试、自愈与发布前检查](plugin-testing-recovery.md)
- [插件设置说明](plugins-settings.md)
