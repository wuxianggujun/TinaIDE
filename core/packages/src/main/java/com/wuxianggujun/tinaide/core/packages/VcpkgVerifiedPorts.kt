package com.wuxianggujun.tinaide.core.packages

/**
 * Ports that TinaIDE itself exercised end to end (download, build, CMake link, run) on a device.
 *
 * Everything else in the market is only declared compatible by upstream `supports` metadata.
 * Keep this list honest: add a port only after a recorded device acceptance run.
 */
object VcpkgVerifiedPorts {
    val names: Set<String> = setOf("fmt")

    fun isVerified(name: String): Boolean = name in names
}
