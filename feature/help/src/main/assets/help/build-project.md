# 构建与运行速查

需要从零实践请先读[学习路线](getting-started.md)；本页只查操作与错误。

## 流程与日志

保存源码 → 选择运行配置 → 构建 → 核对是否成功 → 运行。原生 CMake 由应用提供 Android 工具链与 sysroot，首次使用先完成环境安装。单文件适合短练习，CMake 适合多文件，Make 用于已有 Makefile。

构建失败后看 **构建日志** 中第一个相关错误。**诊断** 来自语言服务，可能滞后，不能替代编译输出。旧终端内容不代表本次运行成功。新代码没生效时核对保存状态、运行 target 和构建结果。

## 运行配置

- CMake 支持 Debug、Release、RelWithDebInfo、MinSizeRel；按项目选中对应目标。
- 控制台可执行程序使用终端输出，参数、环境变量和工作目录在运行配置中设置。
- SDL2 / SDL3 共享库使用对应 SDL 宿主和入口；SDL3 callbacks 需在一个实现文件中保留 `SDL_main.h`。
- raylib 使用普通 `main` 与 NativeActivity。任意 `.so` 不能仅切换输出模式就运行。
- 普通 NDK 共享库可由测试可执行文件调用；不要把它当 SDL 程序。

## vcpkg

**市场 → vcpkg** 可先离线搜索。选择项目并添加依赖只改清单，仍需在 CMake 中 `find_package` 和链接 target。**安装到项目** 完成依赖安装与配置，用户程序仍需构建运行。工具随 APK 校验解压，缺少库源码或 Git 对象时需联网。项目与共享库模式都由应用管理路径，不再读取旧手动目录。

下载失败可在市场右上角改 GitHub / HTTP 代理，然后取消并重试；Git 代理必须支持 Git smart HTTP。不要通过卸载应用或把电脑 Linux 库复制进项目处理。

## 图形与资源

在运行配置 **图形选项 → 悬浮日志** 中开启日志，确认用户程序的退出码与错误。资源路径相对于配置的工作目录或资源入口，不使用电脑绝对路径。图形程序退出后应能回到 IDE；运行时崩溃应与 IDE 本身分开定位。

## 错误定位

| 错误 | 优先检查 |
| --- | --- |
| 语法错误 | 文件行号、保存状态、实际编译的源文件 |
| undefined reference | 实现是否加入目标，库是否链接，声明与定义是否一致 |
| 找不到包配置 | 依赖是否安装到当前项目 / ABI，CMake 包名是否正确 |
| 图形启动退出 | 模板与入口契约、宿主类型、运行日志 |
| 读写文件失败 | 工作目录、路径、权限和错误返回值 |

## 继续学习

- [项目到运行](../tutorial-course/ide-workflow.md)
- [CMake 实践](../tutorial-course/cmake-practice.md)
- [vcpkg 实践](vcpkg-guide.md)
- [SDL3 实践](../tutorial-course/graphics-sdl3.md)
