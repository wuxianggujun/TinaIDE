# 编辑器设备性能与兼容性基线

状态：已提供可执行采样和回归比较入口，尚无本次改动的真机性能结论。单元测试通过不代表 native LLDB、X11 或设备帧率已经验证。

使用 `tools/testing/editor_device_baseline.py`，仅依赖 Python 3 和 ADB。它采集前台 TinaIDE 的 `gfxinfo framestats` 与 `meminfo`，输出 UTF-8 原始记录和 `result.json`，不读取源码正文、API Key 或日志。统计采用 Android 的 IntendedVsync → FrameCompleted 完成时间；这不是触摸到显示的完整输入延迟，也不能替代 Perfetto 的输入链路分析。

## 固定场景

在同一设备、系统、分辨率、字体、主题、折行设置和刷新率下比较同类构建，保持电量与温度条件接近。先固定刷新率，关闭后台下载，预热工程和 LSP。每种场景测三次，保留全部结果并用中间一次作为人工审核过的基线；不要将首次采样自动当成质量通过。

1. `scroll-10k-cpp`：打开固定 10,000 行 C++ 文件，连续上下滑动 30 秒。
2. `typing-10k-cpp`：同一文件中持续输入、删除中文与英文，包含撤销和重做。
3. `split-10k-cpp`：分屏镜像同一文件，切换焦点并交替编辑和滚动。

示例（`SERIAL`、提交标识和路径需替换）：

```bash
python tools/testing/editor_device_baseline.py --serial SERIAL --scenario scroll-10k-cpp --refresh-hz 60 --build-type debug --revision COMMIT --output artifacts/device/fixture --write-fixture artifacts/device/benchmark.cpp
adb -s SERIAL push artifacts/device/benchmark.cpp /sdcard/Download/benchmark.cpp
# 在 TinaIDE 中打开上述文件，完成预热，再运行采样并执行对应操作。
python tools/testing/editor_device_baseline.py --serial SERIAL --scenario scroll-10k-cpp --refresh-hz 60 --build-type debug --revision COMMIT --output artifacts/device/current
# 有人工审核的同设备基线后：
python tools/testing/editor_device_baseline.py --serial SERIAL --scenario scroll-10k-cpp --refresh-hz 60 --build-type debug --revision COMMIT --output artifacts/device/current-compare --baseline artifacts/device/reference/result.json
```

退出码 0 仅代表可比且通过；2 表示缺少基线、采样失败、设备不匹配或指标回退。至少需要 60 个有效帧；未完成帧、Flags 非零和重复采样帧不参与统计。初始回退容差：P95 增加超过 20% 且超过 3ms、卡顿比例增加超过 5 个百分点、峰值 PSS 增加超过 15% 且超过 16MiB。容差是回归门槛，不是已达成的性能目标。设备确认后再根据噪声调整，禁止为一次失败直接放宽阈值。

## 功能兼容性验收

建议覆盖 Android 10 与 Android 14+ ARM64 真机，以及 x86_64 模拟器。每条记录设备/ABI/系统、提交、工具链版本和实际结果，不用“跳过”代替通过：

| 场景 | 预期结果 |
| --- | --- |
| 关闭自动保存，编辑后等待至少 2 秒，终止进程再打开 | 正文恢复为未保存；磁盘原文件不变 |
| 有草稿时外部修改磁盘文件，再打开 | 恢复草稿并显示冲突，手动选择覆盖或重载 |
| 保存/明确丢弃后再打开 | 不恢复旧草稿 |
| native C++ Debug 构建，主函数断点、单步、变量 | 使用该构建选择的工具链；缺少 LLDB 组件时明确报错 |
| 调试输出中文与 emoji、长输出、退出、连续重启 | 无分块乱码、无前次会话状态污染，退出后释放进程 |
| 分屏镜像、关闭当前标签、关闭分屏、重新打开项目 | 焦点和活动标签一致，未保存内容保留 |
| X11/XFCE | 单独验证；当前仍标记为尚未真机验证 |

本入口不自动操作 UI；帧数据必须配合操作记录判断场景是否正确。native LLDB 还需在设备验证 ptrace、lldb-server 启动与目标执行限制；当前不宣称完整调试器已验证。
