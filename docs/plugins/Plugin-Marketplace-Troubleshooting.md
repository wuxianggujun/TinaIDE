# 插件市场排障

> 当前状态：公开仓库只保留 Android 客户端侧插件能力；插件市场索引从 GitHub Registry 读取。

公开 Registry 仓库地址：

```text
https://github.com/wuxianggujun/TinaIDE-Registry
```

后端容器、数据库、管理后台、部署脚本和生产运维排障资料已经迁入私有仓库，
不再随开源 Android 项目分发。

客户端侧排查时优先确认：

1. 网络请求是否能访问 `wuxianggujun/TinaIDE-Registry` 的 `plugins/index.v3.json`。
   `0.18.11+` 主干只读取插件 v3 完整索引；v3 不存在、请求失败或解析失败时会直接报错，
   不回退 v2/v1。索引入口先走 GitHub Raw，再回退 jsDelivr CDN。旧 IDE 继续读取
   `plugins/index.v2.json` 兼容视图，只能看到 `0.17.11 + Plugin API v1` 可用版本。
   如果索引中有插件但当前 IDE 没有任何兼容版本，该插件不会进入列表或更新结果。
2. 插件包的 `download_url` 是否能通过当前 Registry 入口或绝对下载地址访问。
   国内网络建议把大文件放到可信 CDN、对象存储或自建代理，再在索引中填写绝对 URL。
3. Registry 仓库是否已运行 `scripts/build-registry.ps1` 生成 v3 完整视图、v2 兼容视图、
   单项详情、不可变 `.tinaplug` 和依赖包校验值；同版本包内容变化会直接构建失败。
4. 如果 GitHub Raw 已经有内容但 jsDelivr 仍为空，先 purge：
   `https://purge.jsdelivr.net/gh/wuxianggujun/TinaIDE-Registry@main/plugins/index.v3.json`。
5. 本地插件缓存、下载历史和安装目录是否可读写。
6. 开源版账号登录、第三方登录、激活码、会员和官方 AI 额度入口均为移除状态，
   不应再按旧商业版链路排查。

依赖包索引同样只读取 GitHub Registry 的 `packages/index.v2.json`，不会回退旧
`packages/index.json`。Registry 结构见
[`docs/registry/GitHub-Registry.md`](../registry/GitHub-Registry.md)。

如果问题需要查看服务端日志、数据库、管理后台或部署配置，请在私有后端仓库
中处理。
