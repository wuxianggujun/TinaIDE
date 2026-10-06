package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 过滤 Android linker 在旧系统上输出的已知无害噪音。
 *
 * <p>旧版 Android linker 不认识新版 arm64 运行时库/可执行文件里的若干处理器相关动态标记：
 * BTI（{@code DT_AARCH64_BTI_PLT = 0x70000001}）与 Auth RELR
 * （{@code 0x70000011 / 0x70000012 / 0x70000013}）。linker 会打印 "unused DT entry ...
 * (ignoring)" 后继续忽略，不影响进程行为。这里仅在终端显示层丢弃精确命中的噪音行，
 * 不改变进程行为和退出码，也不重定向 stderr —— 子进程 fd 2 仍是真实 PTY，
 * {@code isatty(2)} 保持为真，依赖它做颜色探测的程序（如 ffmpeg）不受影响。</p>
 *
 * <p>全局总闸 {@link #setFilteringEnabled(boolean)}：默认开启过滤。用户可在设置里关闭它，
 * 让所有会话（交互终端 + Run）原样显示 linker 输出。作为安全阀，避免过滤逻辑将来误吞
 * 真实告警时用户无从查看。</p>
 */
final class KnownLinkerWarningFilter {

    private static final String LINKER_WARNING_PREFIX = "WARNING: linker: Warning: ";

    /**
     * 全局过滤总闸。{@code true}（默认）时丢弃已知噪音行；{@code false} 时原样透传。
     * 由 App 设置层通过 {@link TerminalSession#setKnownLinkerWarningFilterEnabled(boolean)}
     * 写入，跨所有会话共享，故为 static volatile（写在设置线程，读在终端主线程）。
     */
    private static volatile boolean sFilteringEnabled = true;

    static void setFilteringEnabled(boolean enabled) {
        sFilteringEnabled = enabled;
    }

    static boolean isFilteringEnabled() {
        return sFilteringEnabled;
    }

    /**
     * 已知无害的处理器相关动态标记。命中其一即视为可丢弃的兼容告警。
     * 不再绑定具体库名（如 libc++_shared.so）：Auth RELR 标记也会出现在可执行文件自身
     * 或其它库上。
     */
    private static final String[] KNOWN_NOISY_DYNAMIC_TAGS = {
        "0x70000001", // DT_AARCH64_BTI_PLT
        "0x70000011", // DT_AARCH64_AUTH_RELR 及相关变体
        "0x70000012",
        "0x70000013",
    };

    private boolean mAtLineStart = true;
    private boolean mCollectingPotentialWarningLine = false;
    private final ByteArrayOutputStream mPotentialWarningBuffer = new ByteArrayOutputStream(256);

    byte[] filter(byte[] input, int count) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(count);

        // 总闸关闭：原样透传。若切换发生在收集途中，先把缓存吐回，避免误吞半行。
        if (!sFilteringEnabled) {
            if (mCollectingPotentialWarningLine) {
                flushPotentialWarningBufferAsNormalLine(output);
            }
            for (int i = 0; i < count; i++) {
                writeNormalByte(output, input[i]);
            }
            return output.toByteArray();
        }

        for (int i = 0; i < count; i++) {
            byte b = input[i];

            if (!mCollectingPotentialWarningLine && mAtLineStart && isPotentialWarningStartByte(b)) {
                mCollectingPotentialWarningLine = true;
                mPotentialWarningBuffer.reset();
            }

            if (!mCollectingPotentialWarningLine) {
                writeNormalByte(output, b);
                continue;
            }

            mPotentialWarningBuffer.write(b);

            if (b == '\n') {
                finalizePotentialWarningLine(output);
            } else if (!couldStillBeKnownLinkerWarning()) {
                flushPotentialWarningBufferAsNormalLine(output);
            }
        }
        return output.toByteArray();
    }

    void flushForProcessExit(ByteArrayOutputStream output) {
        if (!mCollectingPotentialWarningLine) return;

        byte[] lineBytes = mPotentialWarningBuffer.toByteArray();
        String line = new String(lineBytes, StandardCharsets.UTF_8);
        // 总闸关闭时无条件吐回缓存；开启时仅丢弃精确命中的噪音行。
        if (!sFilteringEnabled || !isKnownNoisyLinkerWarningLine(line)) {
            output.write(lineBytes, 0, lineBytes.length);
        }

        mCollectingPotentialWarningLine = false;
        mPotentialWarningBuffer.reset();
    }

    private void writeNormalByte(ByteArrayOutputStream output, byte b) {
        output.write(b);
        if (b == '\n') {
            mAtLineStart = true;
        } else if (b != '\r') {
            mAtLineStart = false;
        }
    }

    private void flushPotentialWarningBufferAsNormalLine(ByteArrayOutputStream output) {
        byte[] bytes = mPotentialWarningBuffer.toByteArray();
        output.write(bytes, 0, bytes.length);
        mCollectingPotentialWarningLine = false;
        mPotentialWarningBuffer.reset();

        if (bytes.length == 0) return;
        byte last = bytes[bytes.length - 1];
        if (last == '\n') {
            mAtLineStart = true;
        } else if (last != '\r') {
            mAtLineStart = false;
        }
    }

    private void finalizePotentialWarningLine(ByteArrayOutputStream output) {
        byte[] lineBytes = mPotentialWarningBuffer.toByteArray();
        String line = new String(lineBytes, StandardCharsets.UTF_8);
        if (!isKnownNoisyLinkerWarningLine(line)) {
            output.write(lineBytes, 0, lineBytes.length);
        }

        mCollectingPotentialWarningLine = false;
        mPotentialWarningBuffer.reset();
        mAtLineStart = true;
    }

    private boolean couldStillBeKnownLinkerWarning() {
        String line = new String(mPotentialWarningBuffer.toByteArray(), StandardCharsets.UTF_8);
        String visiblePrefix = stripLeadingTerminalControls(line);
        return visiblePrefix.isEmpty()
            || LINKER_WARNING_PREFIX.startsWith(visiblePrefix)
            || visiblePrefix.startsWith(LINKER_WARNING_PREFIX);
    }

    private static boolean isKnownNoisyLinkerWarningLine(String line) {
        if (!line.contains(LINKER_WARNING_PREFIX) || !line.contains("unused DT entry")) {
            return false;
        }
        for (String tag : KNOWN_NOISY_DYNAMIC_TAGS) {
            if (line.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPotentialWarningStartByte(byte b) {
        return b == 'W'
            || b == '\r'
            || b == '\t'
            || b == ' '
            || b == 0x1b
            || (b >= 0 && b < ' ');
    }

    private static String stripLeadingTerminalControls(String line) {
        int index = 0;
        while (index < line.length()) {
            char ch = line.charAt(index);
            if (ch == '\u001b') {
                int next = index + 1;
                if (next >= line.length()) return "";

                char type = line.charAt(next);
                if (type == '[') {
                    int end = next + 1;
                    while (end < line.length()) {
                        char endChar = line.charAt(end);
                        if (endChar >= 0x40 && endChar <= 0x7e) {
                            index = end + 1;
                            break;
                        }
                        end++;
                    }
                    if (end >= line.length()) return "";
                    continue;
                }

                if (type == ']') {
                    int end = next + 1;
                    while (end < line.length()) {
                        char endChar = line.charAt(end);
                        if (endChar == '\u0007') {
                            index = end + 1;
                            break;
                        }
                        if (endChar == '\u001b' && end + 1 < line.length() && line.charAt(end + 1) == '\\') {
                            index = end + 2;
                            break;
                        }
                        end++;
                    }
                    if (end >= line.length()) return "";
                    continue;
                }

                index = next + 1;
                continue;
            }

            if (ch == '\r' || ch == '\t' || ch == ' ' || (ch < ' ' && ch != '\n') || ch == 0x7f) {
                index++;
                continue;
            }

            break;
        }
        return line.substring(index);
    }
}