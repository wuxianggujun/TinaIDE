# 插件开发快速开始

这篇教程只做一件事：带你做出第一个能看见效果的 TinaIDE 插件。
先从 `config` 插件开始，先改主题和代码片段，再去碰脚本和 LSP。

## 你会完成什么

- 创建一个插件项目
- 修改 `manifest.json`
- 改一个主题文件
- 改一个 snippet 文件
- 点击运行热安装
- 在设置里切换主题并验证 snippet
- 打包 `.tinaplug`
- 再用从文件安装验证一次

## 0. 先准备

- 安装并启用 `TinaIDE Plugin Starters`
- 如果模板列表里没有插件模板，先到 `设置 → 插件` 检查 starter 是否已安装并启用
- 下面的步骤默认以 `Tina Config Plugin` 为例

## 1. 创建插件项目

1. 打开 TinaIDE
2. 进入本教程，点击顶部快捷操作 `创建插件项目`
3. 确认向导标题是 `新建插件项目`
4. 选择 `Tina Config Plugin`
5. 输入项目名并创建

如果你是从 `项目` 页右下角 `+` 进去，打开的还是通用新建项目向导。
那条路径只作为兜底，请主动选择带“插件”标识的模板。

如果这里没有插件模板，先安装并启用 `TinaIDE Plugin Starters`，然后回到本教程再试。

## 2. 先改 `manifest.json`

先把模板里的插件信息改掉。最少改这几项：

- `id`
- `name`
- `version`
- `apiVersion`（当前稳定值为 `1`）
- `minAppVersion`（仅在确实依赖新宿主能力时填写）
- `type`
- `description`
- `author.name`
- `contributions.themes`
- `contributions.snippets`

一个最小可用示例：

```json
{
  "id": "com.example.my-first-plugin",
  "name": "My First Plugin",
  "version": "0.1.0",
  "apiVersion": 1,
  "type": "config",
  "description": "My first TinaIDE plugin.",
  "author": {
    "name": "Your Name"
  },
  "contributions": {
    "themes": [
      "themes/my-theme.json"
    ],
    "snippets": [
      "snippets/my-snippets.json"
    ]
  }
}
```

`id` 只能包含字母、数字、`.`、`_`、`-`，不能是路径，也不能带 `..`。

示例省略了 `minAppVersion`，这样没有新宿主依赖的插件仍兼容旧 IDE。只有使用了新版本
TinaIDE 才提供的能力时才填写或提高它；旧 IDE 不会显示不兼容的插件更新。

先别急着加 `commands`、`permissions` 或 `lsp`。第一版先把主题和片段跑通。

## 3. 先做一个能看见变化的主题

主题最容易验证。把 `themes/my-theme.json` 写成这样：

```json
{
  "name": "My First Theme",
  "type": "dark",
  "colors": {
    "WHOLE_BACKGROUND": "#1E1E1E",
    "TEXT_NORMAL": "#D4D4D4",
    "KEYWORD": "#C586C0",
    "STRING": "#CE9178",
    "LINE_NUMBER": "#6B7280"
  }
}
```

验证步骤：

1. 点击顶部 `运行`
2. 打开 `设置 → 插件`
3. 进入你的插件详情
4. 找到 `插件主题` 并切换到刚才这个主题
5. 打开一个代码文件确认颜色已经变化

如果安装成功但外观没变化，通常不是安装失败，而是你还没切到这个插件主题。

## 4. 再加一个 snippet

把 `snippets/my-snippets.json` 写成这样：

```json
{
  "language": "cpp",
  "snippets": [
    {
      "prefix": "fori",
      "name": "for (int i = 0; i < n; i++)",
      "description": "最小 for 循环模板",
      "body": [
        "for (int i = 0; i < ${1:n}; i++) {",
        "  $0",
        "}"
      ]
    }
  ]
}
```

验证步骤：

1. 打开一个 `cpp` 文件
2. 输入 `fori`
3. 从补全里插入这个片段
4. 确认代码块已经展开

如果没有出现补全，先检查 `language`、`prefix` 和片段文件路径是否写对。

## 5. 点击运行，做热安装

打开插件项目后，直接点顶部 `运行`。

对于插件项目，运行不是启动普通程序，而是：

1. 校验当前插件目录
2. 打包 `.tinaplug`
3. 热安装到当前 TinaIDE
4. 刷新已安装插件状态

正常情况下不需要重启 IDE。新安装插件默认禁用；安装完成后，去 `设置 → 插件` 打开详情页，确认权限后再主动启用。

## 6. 打包 `.tinaplug`

如果你只想生成安装包，点顶部 `打包`。

输出通常是：

```text
dist/<manifest.id>-<manifest.version>.tinaplug
```

这个文件就是你要分发的插件包。

## 7. 再用文件安装验证一次

到 `设置 → 插件 → 从文件安装插件` 选择刚刚生成的 `.tinaplug`。

安装前会先预检：

- `error` 会阻止安装
- `warning` 允许确认后继续
- `script` / `hybrid` 插件才会额外走权限确认

安装采用 staging + 原子替换；升级失败时健康的旧版本会回滚。插件包还受大小、条目数、单项大小和压缩比限制，超限会直接给出错误，不会等到 OOM。

如果这里报错，先回头改 `manifest.json` 和资源路径，不要先怀疑安装入口。

## 8. 常见问题

### 点击“创建插件项目”后还是普通新建项目？

优先确认你点的是本教程顶部的 `创建插件项目`。

如果你是从 `项目` 页右下角 `+` 进入，那里本来就是通用新建项目向导，需要手动选插件模板。

### 没有插件模板？

先去 `设置 → 插件` 检查 `TinaIDE Plugin Starters` 是否已安装并启用。没装就先装，装了就回到本教程重开一次。

### 运行后没有热安装？

先检查项目根目录有没有合法 `manifest.json`。插件项目至少要有：

- `id`
- `name`
- `version`
- `type`

### 资源找不到？

确认 `manifest.json` 里的路径是相对路径，并且文件确实会被打进最终 `.tinaplug`。

## 继续学习

进阶 script / hybrid 插件可以声明 `optionalPermissions`，由用户在插件详情中按需授权；也可以声明
`contributions.panels`，再通过 `tina.panels.setContent/appendContent/clear` 向编辑器底部“插件”面板发布纯文本。

- [插件设置说明](plugins-settings.md)
- [插件 Manifest 与版本兼容](plugin-manifest-compatibility.md)
- [Script API 与最小权限](plugin-script-api.md)
- [插件面板与事件联动](plugin-panels-events.md)
- [LSP 插件开发与排错](plugin-lsp-troubleshooting.md)
- [插件测试、自愈与发布前检查](plugin-testing-recovery.md)
- [创建项目](create-project.md)
- [编译项目](build-project.md)
- [已知问题](known-issues.md)
