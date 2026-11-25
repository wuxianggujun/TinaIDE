package org.libsdl.app;

import android.util.Log;
import java.util.function.BooleanSupplier;

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
 * TinaIDE 支持 3.4/3.5 两种已核对的 SDL3 JNI 接口。3.2 的初始化返回类型不同，
 * 由 SdlNativeLibraryProbe 在注册前拒绝。做法是：
 * <ol>
 *   <li>Java 侧声明<b>新旧两套签名</b>（旧签名以 native 重载形式保留，见
 *       {@link SDLActivity} 末尾的兼容声明），这样两代原生库都能注册成功；</li>
 *   <li>调用点按本类探测到的能力选择新旧签名，避免调用对方不存在的方法。</li>
 * </ol>
 *
 * 3.5 开发快照的版本号相同但 JNI 能力不同。版本只决定是否尝试无副作用的子系统查询，
 * 查询入口实际存在后才启用新版接口；缺少入口时保留旧接口调用。
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
    private static boolean sModernBridge;
    private static int sCompiledSubsystems = SDL.SDL_INIT_EVERYTHING;

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
            if (sMajorVersion == CAPABILITY_MAJOR && sMinorVersion >= CAPABILITY_MINOR) {
                try {
                    sCompiledSubsystems = SDLActivity.nativeGetCompiledSubsystems();
                    sModernBridge = true;
                } catch (UnsatisfiedLinkError unavailable) {
                    Log.i(TAG, "SDL snapshot has no subsystem query; using compatible JNI callbacks");
                }
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
        resolve();
        return sModernBridge;
    }

    static int compiledSubsystems() {
        resolve();
        return sCompiledSubsystems;
    }

    /** {@code nativeGetCompiledSubsystems()} 是否存在（3.5+）。 */
    static boolean supportsSubsystemQuery() {
        return hasCapabilities();
    }

    /** {@code nativeIsHIDAPIEnabled()} 是否存在（3.5+）。 */
    static boolean supportsHidapiQuery() {
        return hasCapabilities();
    }

    /** A missing optional query does not mean the legacy HID bridge is disabled. */
    static boolean shouldInitializeHidapi(BooleanSupplier enabledQuery) {
        if (!supportsHidapiQuery()) {
            return true;
        }
        try {
            return enabledQuery.getAsBoolean();
        } catch (UnsatisfiedLinkError unavailable) {
            // Development snapshots can add the subsystem query before the HID
            // query. Fall back to acquiring the legacy bridge in that case too.
            return true;
        }
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
