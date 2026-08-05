package com.wuxianggujun.tinaide.core.packages

import com.wuxianggujun.tinaide.core.packages.model.DownloadSource

object PackageDownloadSourceSelector {

    fun select(sources: List<DownloadSource>, targetAbi: String): List<DownloadSource> {
        val usableSources = sources.filter { it.url.isNotBlank() }
        val normalizedTargetAbi = normalizeAbi(targetAbi)
        val matchingSources = usableSources.filter { source ->
            normalizeAbi(source.abi) == normalizedTargetAbi
        }

        val selectedSources = matchingSources.ifEmpty {
            usableSources.filter { source -> normalizeAbi(source.abi).isEmpty() }
        }
        return selectedSources.sortedByDescending(DownloadSource::priority)
    }

    private fun normalizeAbi(abi: String?): String = abi.orEmpty().trim().lowercase()
}
