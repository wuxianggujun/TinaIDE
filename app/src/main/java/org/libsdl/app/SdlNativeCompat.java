package org.libsdl.app;

import android.util.Log;

/**
 * TinaIDE 定制：原生 SDL 能力探测。
 *
 * <h3>为什么需要它</h3>
 * SDL 的 Java 桥接层与 {@code libSDL3.so} 是<b>强耦合</b>的：
 * <ul>
 *   <li>原生库在 {@code JNI_OnLoad} 里用 {@code RegisterNatives} 注册一整张方法表，
 *       Java 类里缺少任何一个 (名字, 签名) 都会让注册失败，进程在 {@code nativeLoad}
 *       阶段直接 abort（日志表现为 {@code No pending exception expected:
 *       NoSuchMethodError: no static or non-static method
 *       "Lorg/libsdl/app/SDLActivity;.nativeGetCompiledSubsystems()I"}）；</li>
 *   <li>反过来，Java 调用原生库里不存在的方法会抛 {@code UnsatisfiedLinkError}。</li>
 * </ul>
 *
 * TinaIDE 需要同时运行不同小版本的 SDL3 原生库（包管理器装的 3.2.x、用户自己编译的
 * 3.4/3.5.x）。做法是：
 * <ol>
 *   <li>Java 侧声明<b>新旧两套签名</b>（旧签名以 native 重载形式保留，见
 *       {@link SDLActivity} 末尾的兼容声明），这样两代原生库都能注册成功；</li>
 *   <li>调用点按本类探测到的能力选择新旧签名，避免调用对方不存在的方法。</li>
 * </ol>
 *
 * 探测基准是 {@link SDLActivity#nativeGetVersion()}——它在所有 SDL3 版本里都存在。
 * 探测失败时一律按"老版本"处理：少用功能好过直接崩溃。
 */
final class SdlNativeCompat {

    private static final String TAG = "SdlNativeCompat";

    /** SDL 3.5 引入了子系统查询、HIDAPI 查询、HDR headroom、传感器回调等 JNI 接口。 */
    private static final int CAPABILITY_MAJOR = 3;
    private static final int CAPABILITY_MINOR = 5;

    private static boolean sResolved;
    private static int sMajorVersion;
    private static int sMinorVersion;
    private static String sRawVersion = "";

    private SdlNativeCompat() {
    }

    private static synchronized void resolve() {
        if (sResolved) {
            return;
        }
        sResolved = true;
        sMajorVersion = -1;
        sMinorVersion = -1;
        try {
            String raw = SDLActivity.nativeGetVersion();
            sRawVersion = raw == null ? "" : raw;
            String[] parts = sRawVersion.split("\\.");
            if (parts.length >= 1) {
                sMajorVersion = Integer.parseInt(parts[0].trim());
            }
            if (parts.length >= 2) {
                sMinorVersion = Integer.parseInt(parts[1].trim());
            }
        } catch (Throwable error) {
            // 原生库没加载成功或版本串异常：按老版本处理。
            Log.w(TAG, "nativeGetVersion() unavailable: " + error);
        }
        Log.i(TAG, "native SDL version=" + sRawVersion + " (parsed " + sMajorVersion + "." + sMinorVersion + ")");
    }

    /** 原生 SDL 版本是否 >= major.minor（未知版本视为不满足）。 */
    static boolean isAtLeast(int major, int minor) {
        resolve();
        if (sMajorVersion < 0) {
            return false;
        }
        if (sMajorVersion != major) {
            return sMajorVersion > major;
        }
        return sMinorVersion >= minor;
    }

    private static boolean hasCapabilities() {
        return isAtLeast(CAPABILITY_MAJOR, CAPABILITY_MINOR);
    }

    /** {@code nativeGetCompiledSubsystems()} 是否存在（3.5+）。 */
    static boolean supportsSubsystemQuery() {
        return hasCapabilities();
    }

    /** {@code nativeIsHIDAPIEnabled()} 是否存在（3.5+）。 */
    static boolean supportsHidapiQuery() {
        return hasCapabilities();
    }

    /** {@code nativeSetScreenHDRHeadroom(float)} 是否存在（3.5+）。 */
    static boolean supportsHdrHeadroom() {
        return hasCapabilities();
    }

    /** {@code onNativePinchStart/Update} 是否接受 span/focus 参数（3.5+）。 */
    static boolean pinchCarriesFocus() {
        return hasCapabilities();
    }

    /** {@code onNativeJoySensor(...)} 是否存在（3.5+，取代 onNativeAccel）。 */
    static boolean supportsJoySensor() {
        return hasCapabilities();
    }

    /** {@code nativeAddJoystick(...)} 是否带加速度计/陀螺仪参数（3.5+）。 */
    static boolean joystickReportsSensors() {
        return hasCapabilities();
    }

    /** 供日志使用的一句话描述。 */
    static String describe() {
        resolve();
        return "sdlNative=" + (sRawVersion.isEmpty() ? "unknown" : sRawVersion)
                + " v35Features=" + hasCapabilities();
    }
}
