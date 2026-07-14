# LSP 插件开发指南

> 文档更新：2026-02-25
> 作者：Claude Code

本文档介绍如何开发 LSP（Language Server Protocol）类型的插件，为 TinaIDE 添加新语言的代码补全、诊断、跳转定义等功能。

---

## 概述

LSP 插件是一种特殊类型的插件（`type: "lsp"`），它通过声明：

1. **Language Server 配置**：指定 LSP 服务器的启动命令、支持的语言和文件类型
2. **工具链依赖**：指定需要安装的运行时环境和 LSP 服务器

宿主应用会负责：

- 在 PRoot 环境中安装工具链依赖
- 启动 LSP 服务器进程
- 建立 LSP 连接并提供补全、诊断等功能

---

## 快速开始

### 1. 创建插件目录结构

```
tinaide.lsp.python/
├── manifest.json          # 必须：插件元信息
└── README.md              # 可选：插件说明
```

### 2. 编写 manifest.json

以 Python LSP 插件为例：

```json
{
  "id": "tinaide.lsp.python",
  "name": "Python Language Support",
  "version": "1.0.0",
  "type": "lsp",
  "author": {
    "name": "Your Name"
  },
  "description": "Python language support with Python Language Server (pylsp)",
  "contributions": {
    "languageServers": [
      {
        "id": "pylsp",
        "name": "Python Language Server",
        "languages": ["python"],
        "fileExtensions": ["py", "pyw"],
        "runtime": {
          "type": "python",
          "minVersion": "3.8"
        },
        "server": {
          "type": "stdio",
          "command": "pylsp"
        },
        "capabilities": {
          "completion": true,
          "hover": true,
          "signatureHelp": true,
          "definition": true,
          "references": true,
          "documentHighlight": true,
          "documentSymbol": true,
          "codeAction": true,
          "formatting": true,
          "rename": true
        }
      }
    ],
    "toolchains": [
      {
        "id": "python3",
        "name": "Python 3",
        "type": "system",
        "packagesByManager": {
          "apk": ["python3", "py3-pip"],
          "apt": ["python3", "python3-pip", "python3-venv"],
          "pacman": ["python", "python-pip"],
          "dnf": ["python3", "python3-pip"]
        },
        "required": true,
        "verifyCommand": "python3 --version",
        "verifyPattern": "Python 3\\."
      },
      {
        "id": "pylsp",
        "name": "Python Language Server",
        "type": "pip",
        "packages": ["python-lsp-server"],
        "required": true,
        "verifyCommand": "pylsp --version",
        "verifyPattern": "pylsp"
      }
    ]
  },
  "activationEvents": [
    "onLanguage:python"
  ]
}
```

### 3. 打包与安装

```powershell
# PowerShell
Compress-Archive -Path .\* -DestinationPath ..\tinaide.lsp.python.tinaplug
```

```bash
# Linux/macOS
zip -r ../tinaide.lsp.python.tinaplug .
```

在 TinaIDE 中：设置 → 插件 → 从文件安装

---

## manifest.json 详解

### 基础字段

| 字段 | 类型 | 必须 | 说明 |
|------|------|------|------|
| `id` | string | ✓ | 插件唯一 ID（建议：`tinaide.lsp.<language>`） |
| `name` | string | ✓ | 插件显示名称 |
| `version` | string | ✓ | 版本号（语义化版本） |
| `type` | string | ✓ | 必须为 `"lsp"` |
| `description` | string | | 插件描述 |
| `author` | object | | 作者信息 |

### contributions.languageServers

Language Server 配置数组，每个元素定义一个 LSP 服务器。

```json
{
  "id": "pylsp",
  "name": "Python Language Server",
  "languages": ["python"],
  "fileExtensions": ["py", "pyw"],
  "filePatterns": ["Pipfile"],
  "runtime": {
    "type": "python",
    "minVersion": "3.8"
  },
  "server": {
    "type": "stdio",
    "command": "pylsp",
    "args": ["--log-file", "/tmp/pylsp.log"],
    "env": {
      "PYTHONPATH": "${workspaceRoot}"
    }
  },
  "initializationOptions": {},
  "settings": {},
  "capabilities": {
    "completion": true,
    "hover": true
  }
}
```

#### 字段说明

| 字段 | 类型 | 必须 | 说明 |
|------|------|------|------|
| `id` | string | ✓ | 服务器唯一 ID |
| `name` | string | ✓ | 服务器显示名称 |
| `languages` | string[] | ✓ | 支持的语言 ID 列表 |
| `fileExtensions` | string[] | ✓ | 支持的文件扩展名（不含点） |
| `filePatterns` | string[] | | 支持的文件名模式（glob） |
| `runtime` | object | | 运行时依赖信息 |
| `server` | object | ✓ | 服务器连接配置 |
| `initializationOptions` | object | | LSP 初始化选项 |
| `settings` | object | | LSP 工作区设置 |
| `capabilities` | object | | 声明支持的 LSP 能力 |

#### server 配置

| 字段 | 类型 | 必须 | 说明 |
|------|------|------|------|
| `type` | string | ✓ | 连接类型：`stdio`（当前仅支持） |
| `command` | string | ✓ | 启动命令 |
| `args` | string[] | | 命令参数 |
| `env` | object | | 环境变量 |

#### 变量替换

在 `args` 和 `env` 中支持以下变量：

| 变量 | 说明 |
|------|------|
| `${workspaceRoot}` | 项目根目录（PRoot 内路径） |
| `${workspaceFolder}` | 同 `${workspaceRoot}` |
| `${userHome}` | 用户主目录（`/root`） |
| `${HOME}` | 同 `${userHome}` |

### contributions.toolchains

工具链依赖数组，定义需要安装的软件包。

```json
{
  "id": "python3",
  "name": "Python 3",
  "type": "system",
  "packagesByManager": {
    "apk": ["python3", "py3-pip"],
    "apt": ["python3", "python3-pip"],
    "pacman": ["python", "python-pip"],
    "dnf": ["python3", "python3-pip"]
  },
  "required": true,
  "verifyCommand": "python3 --version",
  "verifyPattern": "Python 3\\.",
  "fallbackVersions": ["3.10", "3.9"]
}
```

#### 字段说明

| 字段 | 类型 | 必须 | 说明 |
|------|------|------|------|
| `id` | string | ✓ | 工具链唯一 ID |
| `name` | string | ✓ | 工具链显示名称 |
| `type` | string | ✓ | 安装类型（见下表） |
| `packages` | string[] | * | 包名列表（system/pip/npm 需要；不同发行版优先使用 packagesByManager） |
| `url` | string | * | 下载 URL（download 需要） |
| `sha256` | string | | 文件 SHA256 校验和（download） |
| `extractTo` | string | * | 解压目标路径（download 需要） |
| `required` | boolean | | 是否必需（默认 true） |
| `verifyCommand` | string | | 验证安装的命令 |
| `verifyPattern` | string | | 验证输出的正则表达式 |
| `fallbackVersions` | string[] | | 版本降级列表（适用于支持 `name=version` 的包管理器） |

#### 安装类型

| 类型 | 说明 | 必需字段 |
|------|------|----------|
| `system` | 通过当前 Linux 发行版包管理器安装系统包 | `packages` 或 `packagesByManager` |
| `pip` | 通过 pip3 安装 Python 包 | `packages` |
| `npm` | 通过 npm 安装 Node.js 包 | `packages` |
| `download` | 下载并解压二进制文件 | `url`, `extractTo` |

---

## 安装类型详解

### system 系统包安装

适用于系统级依赖（Python、Node.js、Java 等运行时），实际会使用当前 rootfs 的包管理器。

```json
{
  "id": "python3",
  "name": "Python 3",
  "type": "system",
  "packagesByManager": {
    "apk": ["python3", "py3-pip"],
    "apt": ["python3", "python3-pip", "python3-venv"],
    "pacman": ["python", "python-pip"],
    "dnf": ["python3", "python3-pip"]
  },
  "required": true,
  "verifyCommand": "python3 --version",
  "verifyPattern": "Python 3\\.",
  "fallbackVersions": ["3.10", "3.9"]
}
```

**执行流程**：

1. 解析当前 Linux 发行版包管理器
2. 执行对应的软件源索引更新命令
3. 执行对应的系统包安装命令
4. 如果失败且有 `fallbackVersions`，尝试降级版本
5. 执行 `verifyCommand` 验证安装

### pip 安装

适用于 Python 包（LSP 服务器、工具等）。

```json
{
  "id": "pylsp",
  "name": "Python Language Server",
  "type": "pip",
  "packages": ["python-lsp-server", "python-lsp-black"],
  "required": true,
  "verifyCommand": "pylsp --version",
  "verifyPattern": "pylsp"
}
```

**执行流程**：

1. `pip3 install --user --break-system-packages <packages>`
2. 如果失败，尝试不带 `--break-system-packages`（旧版 pip）
3. 执行 `verifyCommand` 验证安装

**注意**：pip 安装的包位于 `/root/.local/bin`，已自动添加到 PATH。

### npm 安装

适用于 Node.js 包（TypeScript LSP、ESLint 等）。

```json
{
  "id": "typescript-language-server",
  "name": "TypeScript Language Server",
  "type": "npm",
  "packages": ["typescript", "typescript-language-server"],
  "required": true,
  "verifyCommand": "typescript-language-server --version",
  "verifyPattern": "\\d+\\.\\d+"
}
```

**执行流程**：

1. `npm install -g <packages>`
2. 执行 `verifyCommand` 验证安装

### download 安装

适用于预编译的二进制文件。

```json
{
  "id": "rust-analyzer",
  "name": "Rust Analyzer",
  "type": "download",
  "url": "https://example.com/rust-analyzer-aarch64-linux.tar.gz",
  "sha256": "abc123...",
  "extractTo": "/usr/local/bin",
  "required": true,
  "verifyCommand": "rust-analyzer --version",
  "verifyPattern": "rust-analyzer"
}
```

**执行流程**：

1. 下载文件到临时目录
2. 验证 SHA256（如果提供）
3. 解压 tar.gz 到 `extractTo` 目录
4. 执行 `verifyCommand` 验证安装

---

## 示例插件

### Python LSP

```json
{
  "id": "tinaide.lsp.python",
  "name": "Python Language Support",
  "version": "1.0.0",
  "type": "lsp",
  "contributions": {
    "languageServers": [{
      "id": "pylsp",
      "name": "Python Language Server",
      "languages": ["python"],
      "fileExtensions": ["py", "pyw"],
      "server": { "type": "stdio", "command": "pylsp" }
    }],
    "toolchains": [
      {
        "id": "python3",
        "type": "system",
        "packagesByManager": {
          "apk": ["python3", "py3-pip"],
          "apt": ["python3", "python3-pip"],
          "pacman": ["python", "python-pip"],
          "dnf": ["python3", "python3-pip"]
        },
        "verifyCommand": "python3 --version"
      },
      { "id": "pylsp", "type": "pip", "packages": ["python-lsp-server"], "verifyCommand": "pylsp --version" }
    ]
  }
}
```

### TypeScript LSP

```json
{
  "id": "tinaide.lsp.typescript",
  "name": "TypeScript Language Support",
  "version": "1.0.0",
  "type": "lsp",
  "contributions": {
    "languageServers": [{
      "id": "tsserver",
      "name": "TypeScript Language Server",
      "languages": ["typescript", "javascript"],
      "fileExtensions": ["ts", "tsx", "js", "jsx"],
      "server": { "type": "stdio", "command": "typescript-language-server", "args": ["--stdio"] }
    }],
    "toolchains": [
      { "id": "nodejs", "type": "system", "packages": ["nodejs", "npm"], "verifyCommand": "node --version" },
      { "id": "tsserver", "type": "npm", "packages": ["typescript", "typescript-language-server"], "verifyCommand": "typescript-language-server --version" }
    ]
  }
}
```

### Rust LSP

```json
{
  "id": "tinaide.lsp.rust",
  "name": "Rust Language Support",
  "version": "1.0.0",
  "type": "lsp",
  "contributions": {
    "languageServers": [{
      "id": "rust-analyzer",
      "name": "Rust Analyzer",
      "languages": ["rust"],
      "fileExtensions": ["rs"],
      "server": { "type": "stdio", "command": "rust-analyzer" }
    }],
    "toolchains": [
      {
        "id": "rust-analyzer",
        "type": "download",
        "url": "https://github.com/rust-lang/rust-analyzer/releases/download/2024-01-01/rust-analyzer-aarch64-unknown-linux-gnu.gz",
        "extractTo": "/usr/local/bin",
        "verifyCommand": "rust-analyzer --version"
      }
    ]
  }
}
```

### Java LSP (jdtls)

```json
{
  "id": "tinaide.lsp.java",
  "name": "Java Language Support",
  "version": "1.0.0",
  "type": "lsp",
  "contributions": {
    "languageServers": [{
      "id": "jdtls",
      "name": "Eclipse JDT Language Server",
      "languages": ["java"],
      "fileExtensions": ["java"],
      "runtime": { "type": "java", "minVersion": "17" },
      "server": {
        "type": "stdio",
        "command": "jdtls",
        "args": ["-data", "${workspaceRoot}/.jdtls-workspace"]
      }
    }],
    "toolchains": [
      {
        "id": "openjdk",
        "type": "system",
        "packagesByManager": {
          "apk": ["openjdk17"],
          "apt": ["openjdk-17-jdk"],
          "pacman": ["jdk17-openjdk"],
          "dnf": ["java-17-openjdk-devel"]
        },
        "verifyCommand": "java --version",
        "verifyPattern": "openjdk 17"
      },
      {
        "id": "jdtls",
        "type": "download",
        "url": "https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz",
        "extractTo": "/opt/jdtls",
        "verifyCommand": "/opt/jdtls/bin/jdtls --version"
      }
    ]
  }
}
```

---

## 工作流程

### 插件安装流程

```
用户安装 LSP 插件（.tinaplug）
    ↓
PluginManager 解析 manifest.json
    ↓
LspPluginManager 检测到 type="lsp"
    ↓
插件出现在插件列表中
    ↓
用户触发依赖安装（入口以当前宿主 UI 为准）
    ↓
LspToolchainInstaller 按顺序安装工具链
    ↓
验证安装成功
    ↓
LSP 插件就绪
```

新安装 LSP 插件默认禁用。依赖安装完成只代表 readiness 满足，用户仍需明确启用插件。

### LSP 连接流程

```
用户打开 .py 文件
    ↓
EditorFeatureManager.setupLanguageAndLsp()
    ↓
TreeSitterLanguageRegistry 提供语法高亮
    ↓
LspPluginManager.getServerConfigForExtension("py")
    ↓
找到 pylsp 配置
    ↓
检查插件是否就绪（工具链已安装）
    ↓
创建 PluginLspConnectionProvider
    ↓
LspEditorManager.attachPluginLsp()
    ↓
启动 pylsp 进程，建立 LSP 连接
    ↓
补全、诊断、跳转等功能可用
```

每个 LSP session 都归属于一个 `ownerPluginId`。禁用、自动隔离、升级或卸载插件时，宿主会立即关闭该插件拥有的进程和编辑器连接。依赖未安装、Linux/toolchain 未就绪属于 readiness 错误，不会自动隔离；服务器成功启动后异常退出或协议崩溃会隔离对应插件。

`server.command`、参数和环境变量都有数量与长度限制，并拒绝 NUL、换行和非法环境变量名。不要把用户输入拼成 shell 命令；每个参数应独立写入 `args`，由宿主统一转义。

---

## 调试与排错

### 查看日志

LSP 相关日志使用以下 TAG：

- `LspPluginManager`：插件管理
- `LspToolchainInstaller`：工具链安装
- `PluginLspConnection`：LSP 连接

使用 Logcat 过滤：

```bash
adb logcat -s LspPluginManager:* LspToolchainInstaller:* PluginLspConnection:*
```

### 常见问题

#### 1. 工具链安装失败

**症状**：安装进度卡住或报错

**排查**：
- 检查网络连接
- 查看 `LspToolchainInstaller` 日志
- 确认 `verifyCommand` 和 `verifyPattern` 正确

**解决**：
- 添加 `fallbackVersions` 尝试其他版本
- 检查包名是否正确

#### 2. LSP 服务器启动失败

**症状**：打开文件后状态栏显示 Error

**排查**：
- 查看 `PluginLspConnection` 日志中的 stderr 输出
- 确认 `command` 路径正确
- 检查环境变量配置

**解决**：
- 确保工具链已正确安装
- 检查 PATH 是否包含 `/root/.local/bin`

#### 3. 补全不工作

**症状**：LSP 连接成功但无补全

**排查**：
- 确认 `fileExtensions` 包含当前文件扩展名
- 检查 LSP 服务器是否支持该语言

---

## API 参考

### LspServerConfig

```kotlin
data class LspServerConfig(
    val id: String,                              // 服务器 ID
    val name: String,                            // 显示名称
    val languages: List<String>,                 // 语言 ID 列表
    val fileExtensions: List<String>,            // 文件扩展名
    val filePatterns: List<String>? = null,      // 文件名模式
    val runtime: LspRuntimeConfig? = null,       // 运行时配置
    val server: LspServerConnectionConfig,       // 连接配置
    val initializationOptions: Map<String, Any>? = null,
    val settings: Map<String, Any>? = null,
    val capabilities: LspCapabilitiesConfig? = null
)
```

### LspToolchainConfig

```kotlin
data class LspToolchainConfig(
    val id: String,                              // 工具链 ID
    val name: String,                            // 显示名称
    val type: String,                            // 安装类型
    val packages: List<String>? = null,          // 包名列表
    val url: String? = null,                     // 下载 URL
    val sha256: String? = null,                  // SHA256 校验
    val extractTo: String? = null,               // 解压路径
    val required: Boolean = true,                // 是否必需
    val verifyCommand: String? = null,           // 验证命令
    val verifyPattern: String? = null,           // 验证模式
    val fallbackVersions: List<String>? = null   // 降级版本
)
```

---

## 相关文件

| 文件 | 说明 |
|------|------|
| [LspPluginModels.kt](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/lsp/LspPluginModels.kt) | LSP 插件数据模型 |
| [LspPluginManager.kt](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/lsp/LspPluginManager.kt) | LSP 插件管理器 |
| [LspToolchainInstaller.kt](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/lsp/LspToolchainInstaller.kt) | 工具链安装器 |
| [PluginLspConnectionProvider.kt](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/lsp/PluginLspConnectionProvider.kt) | LSP 连接提供者 |
| [tinaide.lsp.python/manifest.json](https://github.com/wuxianggujun/TinaIDE-Registry/tree/main/sources/plugins/tinaide.lsp.python) | Python LSP 示例插件发布源 |

---

## 当前能力与限制（截至 2026-07）

当前已经接入：

1. 插件详情页内的一键“安装依赖 / 修复依赖”入口
2. 安装前确认对话框与安装进度对话框
3. 运行时诊断中的“修复 LSP 依赖”动作，可跳转回插件详情页处理
4. LSP session 按插件归属，禁用、隔离、升级和卸载会关闭对应会话
5. 成功启动后的服务器异常退出会持久化故障并自动隔离插件

以下能力在当前版本仍未开放或仅部分支持：

1. 自动检测“插件未就绪”并弹出安装提示尚未接入
2. 连接类型当前以 `stdio` 为主，`socket/websocket` 暂未开放

---

## 维护说明

- 本文档优先描述当前可用能力与落地配置。
- 规划类内容统一维护在 `docs/plugins/Plugin-Roadmap.md`。
