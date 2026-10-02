package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Test;

public class KnownLinkerWarningFilterTest {

    private static final String NOISY_WARNING = "WARNING: linker: Warning: \"/data/data/com.wuxianggujun.tinaide/files/android-sysroot/usr/lib/aarch64-linux-android/libc++_shared.so\" unused DT entry: unknown processor-specific (type 0x70000001 arg 0x0) (ignoring)\n";

    @After
    public void restoreGlobalFilterState() {
        // sFilteringEnabled 是 static，测试改动后必须复位，避免污染其它用例。
        KnownLinkerWarningFilter.setFilteringEnabled(true);
    }

    @Test
    public void filterDropsKnownLibcxxBtiWarning() {
        KnownLinkerWarningFilter filter = new KnownLinkerWarningFilter();

        String output = apply(filter, NOISY_WARNING + "Hello, 1!\n");

        assertEquals("Hello, 1!\n", output);
    }

    @Test
    public void filterDropsWarningAfterLeadingTerminalControls() {
        KnownLinkerWarningFilter filter = new KnownLinkerWarningFilter();

        String output = apply(filter, "\u001b[?2004l\r" + NOISY_WARNING + "Hello, 1!\n");

        assertEquals("Hello, 1!\n", output);
    }

    @Test
    public void filterPreservesNormalWarningOutput() {
        KnownLinkerWarningFilter filter = new KnownLinkerWarningFilter();

        String input = "WARNING: user warning\nHello, 1!\n";
        String output = apply(filter, input);

        assertEquals(input, output);
    }

    @Test
    public void filterHandlesWarningSplitAcrossReads() {
        KnownLinkerWarningFilter filter = new KnownLinkerWarningFilter();
        int split = NOISY_WARNING.indexOf("unused DT entry");

        String first = apply(filter, NOISY_WARNING.substring(0, split));
        String second = apply(filter, NOISY_WARNING.substring(split) + "Hello, 1!\n");

        assertEquals("", first);
        assertEquals("Hello, 1!\n", second);
    }

    @Test
    public void filterPreservesIncompleteNormalWarningLine() {
        KnownLinkerWarningFilter filter = new KnownLinkerWarningFilter();
        ByteArrayOutputStream tail = new ByteArrayOutputStream();

        String first = apply(filter, "WARNING: user warning without newline");
        filter.flushForProcessExit(tail);

        assertEquals("WARNING: user warning without newline", first);
        assertEquals("", new String(tail.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void filterDropsAuthRelrWarningFromExecutable() {
        KnownLinkerWarningFilter filter = new KnownLinkerWarningFilter();

        // Auth RELR 告警可能来自可执行文件自身，而非某个库；匹配不再依赖 libc++_shared.so。
        String authRelr = "WARNING: linker: Warning: \"/data/data/com.wuxianggujun.tinaide/files/run-bin/main\" "
            + "unused DT entry: unknown processor-specific (type 0x70000012 arg 0x0) (ignoring)\n";
        String output = apply(filter, authRelr + "Hello, 1!\n");

        assertEquals("Hello, 1!\n", output);
    }

    @Test
    public void filterPreservesLinkerWarningWithoutUnusedDtEntry() {
        KnownLinkerWarningFilter filter = new KnownLinkerWarningFilter();

        // 命中 linker 前缀但不是"unused DT entry"噪音的告警必须保留（避免过度过滤）。
        String input = "WARNING: linker: Warning: something went wrong 0x70000012\n";
        String output = apply(filter, input);

        assertEquals(input, output);
    }

    @Test
    public void globalSwitchOffPassesNoisyWarningThrough() {
        // 总闸关闭：即便命中已知噪音，也必须原样透传（安全阀）。
        KnownLinkerWarningFilter.setFilteringEnabled(false);
        KnownLinkerWarningFilter filter = new KnownLinkerWarningFilter();

        String output = apply(filter, NOISY_WARNING + "Hello, 1!\n");

        assertEquals(NOISY_WARNING + "Hello, 1!\n", output);
    }

    @Test
    public void globalSwitchToggledOffMidCollectionFlushesBuffer() {
        // 收集途中切换到关闭：已缓存的半行噪音不能被吞掉，必须吐回。
        KnownLinkerWarningFilter filter = new KnownLinkerWarningFilter();
        int split = NOISY_WARNING.indexOf("unused DT entry");

        String first = apply(filter, NOISY_WARNING.substring(0, split));
        assertEquals("", first);

        KnownLinkerWarningFilter.setFilteringEnabled(false);
        String second = apply(filter, NOISY_WARNING.substring(split) + "Hello, 1!\n");

        // 关闭后：先前缓存的前半段 + 剩余输入都原样出现。
        assertEquals(NOISY_WARNING + "Hello, 1!\n", second);
    }

    @Test
    public void globalSwitchOffFlushForExitKeepsBufferedNoise() {
        // 关闭状态下进程退出：缓存里的噪音行也要吐回，不做丢弃判定。
        KnownLinkerWarningFilter filter = new KnownLinkerWarningFilter();
        int split = NOISY_WARNING.indexOf("unused DT entry");

        apply(filter, NOISY_WARNING.substring(0, split));
        KnownLinkerWarningFilter.setFilteringEnabled(false);

        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        filter.flushForProcessExit(tail);

        assertEquals(NOISY_WARNING.substring(0, split), new String(tail.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void defaultFilterStateIsEnabled() {
        assertTrue(KnownLinkerWarningFilter.isFilteringEnabled());
    }

    private static String apply(KnownLinkerWarningFilter filter, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return new String(filter.filter(bytes, bytes.length), StandardCharsets.UTF_8);
    }
}