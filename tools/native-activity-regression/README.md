# NativeActivity 独立回归夹具

只安装/操作 `com.wuxianggujun.tinaide.nativerepeat`，不覆盖 TinaIDE，不清 IDE 数据。
构建使用生产 NativeActivity C bridge 与指定 Registry raylib 包；**故意不启用 IDE 的退出 watchdog**，
以区分 raylib 自身的销毁修复和宿主的最终进程回收能力。

## 构建与执行

先设置 `ANDROID_HOME` / `JAVA_HOME`，或显式传路径。Windows 使用 `py -3 -X utf8`：

```bash
python tools/native-activity-regression/build_fixture.py --sdk "$ANDROID_HOME" --java-home "$JAVA_HOME"
python tools/native-activity-regression/run_fixture.py --adb "$ANDROID_HOME/platform-tools/adb" --serial DEVICE --suite gesture --install
python tools/native-activity-regression/run_fixture.py --adb "$ANDROID_HOME/platform-tools/adb" --serial DEVICE --suite key
python tools/native-activity-regression/run_fixture.py --adb "$ANDROID_HOME/platform-tools/adb" --serial DEVICE --suite home
python tools/native-activity-regression/run_fixture.py --adb "$ANDROID_HOME/platform-tools/adb" --serial DEVICE --suite early-cancel
python tools/native-activity-regression/run_fixture.py --adb "$ANDROID_HOME/platform-tools/adb" --serial DEVICE --suite rejected
```

- 每个 suite 总时限 60 秒，命令显式指定设备；手势套件需要设备使用手势导航、屏幕解锁。
- `gesture` / `key` 默认连续 5 次，要求每次有帧、`main-return`、`destroy-return`，图形 PID 换新而 launcher PID 不变。
- `home` 要求长于退出宽限期仍运行，Home 后不被杀，返回同一 Activity 后帧数继续增长。
- `early-cancel` 在 NativeActivity 的 `super.onCreate` 返回后、窗口创建前立即 finish。
- `rejected` 在 `super.onCreate` 前 finish；生产 bridge 必须完成空生命周期而不加载用户引擎。
- 产物与 UTF-8 日志在 `app/build/reports/native-activity-regression/`；必须人工查看截图，不能只看帧计数。
- 夹具密钥由本地生成，只用于此测试包。若旧夹具签名不同，只重装测试包，不卸载 IDE。

模拟器使用对应 ABI，例如构建时加 `--abi x86_64`，执行时指定 `--serial emulator-5554`。
用 `--report-dir app/build/reports/native-activity-regression/emulator-host/gesture` 分开保存
不同设备、GPU 后端和套件的证据，避免覆盖先前的真机结果；`--apk` 可指定要安装的夹具。

这不能替代最终 Release 的“设备内新建项目 → 安装资源 → 编译 → 运行”验收。
统一宿主的取消票据、进程边界与卡死退出另由 `GraphicalRuntime*Test`、`CancelledSdlActivityTest` 覆盖。
