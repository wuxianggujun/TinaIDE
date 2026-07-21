package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorTabState

internal class EditorSaveAllNotificationTracker {
    private var pendingTargets: List<ActiveSaveTarget> = emptyList()

    fun rememberDirtyTabs(tabs: List<EditorTabState>) {
        pendingTargets = tabs
            .asSequence()
            .filter { it.isDirty }
            .map { tab ->
                ActiveSaveTarget(
                    tabId = tab.id,
                    file = tab.file
                )
            }
            .toList()
    }

    fun resolveSuccessfulTargets(
        results: List<SaveResult>
    ): List<ActiveSaveTarget> {
        val resultTargets = results.mapNotNull { result ->
            val target = (result as? SaveResult.Success)?.target ?: return@mapNotNull null
            ActiveSaveTarget(
                tabId = target.tabId,
                file = target.file
            )
        }
        if (resultTargets.isNotEmpty()) {
            pendingTargets = emptyList()
            return resultTargets
        }

        val targets = pendingTargets
        pendingTargets = emptyList()
        if (targets.isEmpty() || results.isEmpty()) return emptyList()
        return targets.zip(results).mapNotNull { (target, result) ->
            target.takeIf { result is SaveResult.Success }
        }
    }
}
