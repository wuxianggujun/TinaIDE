package com.wuxianggujun.tinaide.startup

import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import timber.log.Timber

/**
 * 项目元数据与文件提供器初始化
 *
 * - 设置 ProjectMetadataStore 的 IDE 版本信息
 */
class ProjectMetadataInitializer(private val ideVersion: String) {

    companion object {
        private const val TAG = "ProjectMetadataInitializer"
    }

    fun execute() {
        ProjectMetadataStore.currentIdeVersion = ideVersion.ifBlank { "unknown" }
        Timber.tag(TAG).i(
            "ProjectMetadataStore initialized with IDE version: %s",
            ProjectMetadataStore.currentIdeVersion,
        )
    }
}
