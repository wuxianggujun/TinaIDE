package com.wuxianggujun.tinaide.core.packages

/**
 * Result of filtering the Android catalog or the project dependency list.
 *
 * [incompatible] explains an empty search result: the query matched upstream entries that are
 * declared incompatible with the current Android triplet, so the market can say so instead of
 * claiming the library does not exist.
 */
data class VcpkgSearchResult(
    val ports: List<VcpkgPort>,
    val incompatible: List<VcpkgPort> = emptyList(),
) {
    val isBlankSearch: Boolean get() = ports.isEmpty() && incompatible.isEmpty()
}

/** Only real upstream entries may become market candidates; project placeholders stay reachable through [orphanDependencies]. */
class VcpkgAndroidCatalog(availablePorts: List<VcpkgPort>, val androidAbi: String) {
    private val allPorts: List<VcpkgPort> = availablePorts.distinctBy { it.name }.sortedBy { it.name }
    private val supportByName = allPorts.associate { port ->
        port.name to VcpkgAndroidSupport.evaluate(port.supports, androidAbi)
    }

    /** Upstream entries usable with TinaIDE's native, static Android triplet. */
    val ports: List<VcpkgPort> = allPorts.filter { support(it.name) == VcpkgPlatformSupport.SUPPORTED }

    /** Every entry in the snapshot, including incompatible ones. */
    val totalCount: Int get() = allPorts.size

    fun support(name: String): VcpkgPlatformSupport = supportByName[name] ?: VcpkgPlatformSupport.UNKNOWN

    fun search(query: String): VcpkgSearchResult {
        val normalized = query.trim()
        val matched = ports.filter { it.matches(normalized) }
        val incompatible = if (matched.isEmpty() && normalized.isNotEmpty()) {
            allPorts.filter { it.matches(normalized) && support(it.name) != VcpkgPlatformSupport.SUPPORTED }
        } else {
            emptyList()
        }
        return VcpkgSearchResult(matched, incompatible)
    }

    /**
     * 已声明或已安装、但被 Android 兼容性过滤掉的条目（上游已删除或不适配当前 triplet）。
     *
     * 单视图列表里必须保留它们，否则这类依赖会失去移除入口；能正常搜索到的条目不会重复返回。
     */
    fun orphanDependencies(project: VcpkgProjectCatalog?, query: String): List<VcpkgPort> {
        if (project == null) return emptyList()
        val normalized = query.trim()
        val visible = ports.mapTo(HashSet()) { it.name }
        return project.ports.filter { port ->
            (port.name in project.declared || port.name in project.installed) &&
                port.name !in visible &&
                port.matches(normalized)
        }.sortedBy { it.name }
    }

    private fun VcpkgPort.matches(query: String): Boolean =
        query.isEmpty() || name.contains(query, ignoreCase = true) || description.contains(query, ignoreCase = true)
}
