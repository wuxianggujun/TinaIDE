# {{PROJECT_NAME}} Language Support

这是一个 TinaIDE `lsp` 插件模板。

## 你需要替换的关键字段

1. `languages`
2. `fileExtensions`
3. `server.command`
4. `toolchains[].packages`
5. `verifyCommand`
6. `activationEvents`

## 推荐验证顺序

1. 先让工具链安装成功
2. 再确认 `verifyCommand` 能通过
3. 在 TinaIDE 中点击 **运行**，让 IDE 校验、打包并热安装
4. 需要离线分发时，再执行 `pack.ps1` 或 `pack.sh`
5. 用“设置 → 插件 → 从文件安装”验证生成的 `.tinaplug`
6. 最后打开目标语言文件验证补全和诊断

首次安装后插件默认禁用。依赖安装完成只表示插件已就绪，仍需在详情页明确启用。每个 LSP session 归属于插件；禁用、隔离、升级或卸载会关闭对应进程。依赖未准备好不会隔离插件，但服务器成功启动后异常退出会自动隔离。

不要把用户输入拼入 `server.command`。固定可执行文件写在 `command`，每个参数独立写入 `args`，环境变量名和值保持最小且可验证。

## 打包说明

- 在 TinaIDE 中点击 **运行**：校验当前目录、打包 `.tinaplug`，并热安装到当前 IDE
- 在 TinaIDE 中点击 **打包**，或执行 `pack.ps1` / `pack.sh`：只生成插件包，不执行热安装
- 输出路径固定为 `dist/<manifest.id>-<manifest.version>.tinaplug`
- `pack.ps1` / `pack.sh` 会先自动执行校验
- 最终 `.tinaplug` 会排除 README、打包脚本和校验辅助文件
- 离线分发前，建议再用“设置 → 插件 → 从文件安装”选择生成的 `.tinaplug` 做一次预检
