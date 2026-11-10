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

## IDE 内 SDL / raylib 运行回归

`build_runtime_probe.py` 从本地 Registry 归档读取 SDL2、SDL3、raylib，使用 NDK 编译小型测试主库。
不重编译这些依赖库。产物只写入 `app/build/reports/runtime-regression/native/`。

```bash
python tools/native-activity-regression/build_runtime_probe.py --sdk "$ANDROID_HOME" --abi x86_64
adb -s DEVICE shell mkdir -p /data/local/tmp/tina-runtime-probe
adb -s DEVICE push app/build/reports/runtime-regression/native/sdl2 app/build/reports/runtime-regression/native/sdl3 app/build/reports/runtime-regression/native/raylib /data/local/tmp/tina-runtime-probe/
adb -s DEVICE shell run-as com.wuxianggujun.tinaide mkdir -p files/runtime-probe
adb -s DEVICE shell run-as com.wuxianggujun.tinaide cp -r /data/local/tmp/tina-runtime-probe/. files/runtime-probe/
python tools/testing/run_editor_runtime_regression.py --adb "$ANDROID_HOME/platform-tools/adb" --serial DEVICE --suite runtime
```

先安装当前 Debug APK 及对应 androidTest APK。`DEVICE` 必须显式选择测试设备；脚本串行运行，每项最多 60 秒。
仪器测试使用生产 staging 和 Activity，验证默认/自定义目录、中文与 emoji 资源、悬浮日志可见性，
以及正常返回、`exit`、`abort` 后图形进程退出而 IDE PID 不变。缺少 fixture 是失败，不记为跳过。

编辑器组件与宿主回归分别使用 `--suite editor`、`--suite host`；编辑器可加 `--orientation landscape`，
由测试 Activity 设置并校验方向。宿主大文件用例加载真实 C++ Tree-sitter，记录打开与首次高亮耗时，
数据仅作为 Debug 模拟器观察值，不能代替同机 Release 帧率基线。

结果默认存入 `app/build/reports/editor-runtime-regression/`，可用 `--output` 分开保存各轮结果。
清理前保留结果 JSON、必要日志和少量截图；测试专用 AVD 用 `avdmanager delete avd --name NAME` 删除。

SDL 更新时还应对照 `docker/tinaide-pkg/libs/build-sdl3.sh` 锁定提交的
`src/core/android/SDL_android.c` 和 `src/hidapi/android/hid.cpp`，检查双向 JNI 契约：

```bash
python tools/native-activity-regression/check_sdl_jni_contract.py --android-source /path/SDL_android.c --hid-source /path/hid.cpp --classes app/build/intermediates/javac/x86_64Debug/compileX86_64DebugJavaWithJavac/classes --output app/build/reports/runtime-regression/jni-contract.json
```

该检查比较真实编译的 Java 描述符；接口全部存在仍需要实际加载并运行 SDL 的设备测试。
