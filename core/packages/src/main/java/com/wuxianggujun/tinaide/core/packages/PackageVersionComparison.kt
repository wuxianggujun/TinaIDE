package com.wuxianggujun.tinaide.core.packages

/** Upstream version first, then the Registry's repack/security-fix revision. */
internal fun isNewerPackageVersion(
    remote: String,
    local: String,
    remoteRevision: Int? = null,
    localRevision: Int? = null,
): Boolean {
    val remoteParts = remote.split('.').mapNotNull(String::toIntOrNull)
    val localParts = local.split('.').mapNotNull(String::toIntOrNull)
    for (index in 0 until maxOf(remoteParts.size, localParts.size)) {
        val comparison = remoteParts.getOrElse(index) { 0 }.compareTo(localParts.getOrElse(index) { 0 })
        if (comparison != 0) return comparison > 0
    }
    return (remoteRevision ?: 0) > (localRevision ?: 0)
}
