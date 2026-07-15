# LSP 插件开发与排错

LSP 插件负责告诉 TinaIDE 两件事：哪些文件属于这门语言，以及语言服务器如何准备和启动。

## 最小结构

```text
my-lsp-plugin/
├── manifest.json
├── README.md
├── pack.ps1
└── pack.sh
```

LSP 插件通常不需要 Lua `main`。核心配置在 `contributions.languageServers` 和 `contributions.toolchains`。

## 最小 manifest

```json
{
  "id": "tinaide.lsp.mylang",
  "name": "MyLang Language Support",
  "version": "0.1.0",
  "apiVersion": 1,
  "type": "lsp",
  "description": "Language support for MyLang.",
  "author": {
    "name": "Your Name"
  },
  "contributions": {
    "languageServers": [
      {
        "id": "mylang-server",
        "name": "MyLang Server",
        "languages": ["mylang"],
        "fileExtensions": ["mlg"],
        "server": {
          "type": "stdio",
          "command": "mylang-lsp"
        },
        "capabilities": {
          "completion": true,
          "hover": true,
          "definition": true,
          "references": true,
          "documentSymbol": true
        }
      }
    ],
    "toolchains": [
      {
        "id": "mylang-lsp",
        "name": "MyLang LSP",
        "type": "npm",
        "packages": ["mylang-lsp"],
        "required": true,
        "verifyCommand": "mylang-lsp --version",
        "verifyPattern": ".+"
      }
    ]
  },
  "activationEvents": ["onLanguage:mylang"]
}
```

## 五个必须一致的地方

1. `languages` 必须匹配 TinaIDE 使用的 language ID
2. `fileExtensions` 不带点，并覆盖真实文件后缀
3. `server.command` 必须是运行环境里最终可执行的命令
4. `verifyCommand` 必须能在相同环境成功执行
5. `activationEvents` 的语言必须和 `languages` 对齐

## 正确的验证顺序

### 1. 先看依赖状态

安装插件后进入详情页。如果显示 LSP 依赖未就绪，先完成工具链安装。不要在依赖缺失时反复开关插件。

### 2. 独立验证命令

在对应运行环境中执行：

```text
mylang-lsp --version
```

命令找不到时，先修 `toolchains` 和 PATH，不要先改 LSP 协议字段。

### 3. 打开真实文件

打开后缀匹配的文件，确认插件 owner 被创建并尝试启动 server。空白文件也可以触发语言识别，但最好用一个语法有效的最小样例。

### 4. 验证 initialize

至少确认：

- server 进程成功启动
- initialize 请求有响应
- completion、hover 或 diagnostics 至少一项生效
- 关闭最后一个 owner 后 server 能正常结束

## 故障归因

### 依赖未安装或命令不存在

这是准备状态问题，不应误判为插件 crash。修复依赖并重新验证。

### initialize 之前退出

检查命令、参数、工作目录、运行环境和 server 自身日志。

### initialize 成功后异常退出

这属于可归因的 LSP runtime 故障。宿主会停止该 owner，记录故障并阻止 crash loop；插件可能进入自动隔离。

### 文件关闭后进程仍不退出

检查 owner 生命周期和 server 的 shutdown/exit 行为。一个插件的异常退出不应该停止其他插件的语言服务器。

## 排错清单

1. manifest 是否能通过预检
2. 插件是否已启用
3. 必需 toolchain 是否显示就绪
4. `verifyCommand` 是否真实成功
5. 文件后缀与 language ID 是否匹配
6. 插件日志是否出现 initialize、退出码或 owner-stop 信息
7. 重新打开文件后是否只启动一个对应 server
8. server 崩溃后宿主 Activity 是否仍可操作

## 继续学习

- [插件 Manifest 与版本兼容](plugin-manifest-compatibility.md)
- [插件面板与事件联动](plugin-panels-events.md)
- [插件测试、自愈与发布前检查](plugin-testing-recovery.md)
- [LSP 概述](lsp-overview.md)
- [插件设置说明](plugins-settings.md)
