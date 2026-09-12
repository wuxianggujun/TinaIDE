package com.wuxianggujun.tinaide.core.linuxdesktop;

/**
 * 主进程 → :x11 进程的 X server 控制通道。
 *
 * 不复用 lorie 的 ICmdEntryInterface：那个接口是给 termux 侧命令行工具取
 * X 连接 fd 用的，语义是"把连接交出去"，而这里需要的是"启动/停止/查询状态"。
 */
interface IX11ServerController {
    /**
     * 启动 X server。已在运行且 display 号相同时视为成功。
     *
     * @param displayNumber X display 号
     * @param argv CmdEntryPoint.start() 的参数（不含 argv[0]）
     * @param hostTmpDir X server 的 $TMPDIR，socket 建在 $TMPDIR/.X11-unix/ 下
     * @param xkbConfigRoot $XKB_CONFIG_ROOT，指向 rootfs 内的 xkb 数据
     * @return 启动失败时的错误描述，成功时为 null
     */
    String startServer(int displayNumber, in String[] argv, String hostTmpDir, String xkbConfigRoot);

    /** X server 是否已启动。 */
    boolean isServerRunning();

    /** 当前 display 号，未启动时为 -1。 */
    int getDisplayNumber();

    /**
     * 在本进程 spawn guest 桌面会话（proot + XFCE），并交给看护器重启。
     *
     * 会话必须由 `:x11` 进程 spawn 而不是主进程：init-proot.sh 带 `--kill-on-exit`，
     * proot 树的存亡跟着 spawn 它的进程。放在主进程等于"关掉 IDE 就杀掉桌面"，
     * 与"server 常驻、窗口可开可关"的拓扑直接矛盾。
     *
     * 已有存活会话时视为成功（幂等），不会开出第二个桌面。
     *
     * @param argv host 侧完整命令行（主进程用 PRootManager 组装好，本进程只负责 exec）
     * @param environment `KEY=VALUE` 形式的完整环境，会整体替换而非追加
     * @param workingDirectory host 侧工作目录
     * @param maxRestarts 意外退出后的重启预算
     * @param restartDelayMs 每次重启前的等待
     * @return 启动失败时的错误描述，成功时为 null
     */
    String startGuestSession(in String[] argv, in String[] environment, String workingDirectory,
            int maxRestarts, long restartDelayMs);

    /** guest 会话当前是否存活。 */
    boolean isGuestSessionRunning();

    /** 看护器阶段名（[LinuxDesktopSupervisorPhase] 的 name），供 UI 区分"运行中"与"已崩溃"。 */
    String guestSessionPhase();

    /** 终止 guest 会话；不影响 X server。 */
    void stopGuestSession();
}
