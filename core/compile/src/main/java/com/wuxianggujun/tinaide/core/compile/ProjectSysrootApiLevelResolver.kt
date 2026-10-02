package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 解析原生构建使用的 sysroot API level。
 *
 * 优先级：
 * 1. 运行配置显式配置（合法范围 21..35）
 * 2. 项目 metadata（.tinaide/project.json）的 nativeApiLevel
 * 3. 默认值（API 28）
 */
internal object ProjectSysrootApiLevelResolver {

    enum class Source {
        RUN_CONFIG,
        METADATA,
        DEFAULT
    }

    data class Resolution(
        val apiLevel: Int,
        val source: Source,
        /** 非空表示运行配置传入了非法值（会被回退） */
        val invalidRunConfigApiLevel: Int? = null
    )

    suspend fun resolve(projectRoot: File, runConfigApiLevel: Int?): Resolution = withContext(Dispatchers.IO) {
        resolveOnIo(projectRoot, runConfigApiLevel)
    }

    private suspend fun resolveOnIo(projectRoot: File, runConfigApiLevel: Int?): Resolution {
        if (runConfigApiLevel != null && MakeCommandOverrides.isValidSysrootApiLevel(runConfigApiLevel)) {
            return Resolution(
                apiLevel = runConfigApiLevel,
                source = Source.RUN_CONFIG
            )
        }

        val metadataApiLevel = try {
            ProjectMetadataStore.read(projectRoot)?.getNativeApiLevelOrNull()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

        if (metadataApiLevel != null && MakeCommandOverrides.isValidSysrootApiLevel(metadataApiLevel)) {
            return Resolution(
                apiLevel = metadataApiLevel,
                source = Source.METADATA,
                invalidRunConfigApiLevel = runConfigApiLevel
            )
        }

        return Resolution(
            apiLevel = MakeCommandOverrides.DEFAULT_SYSROOT_API_LEVEL,
            source = Source.DEFAULT,
            invalidRunConfigApiLevel = runConfigApiLevel
        )
    }
}
