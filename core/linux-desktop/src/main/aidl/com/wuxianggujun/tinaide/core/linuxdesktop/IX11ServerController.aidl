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
}
