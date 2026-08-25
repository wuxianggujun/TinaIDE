package com.wuxianggujun.tinaide.plugin.script

sealed class PluginExecutionResult {
    data class Success(val value: Any?) : PluginExecutionResult()
    data class Error(val message: String, val stack: String? = null) : PluginExecutionResult()
    data object Timeout : PluginExecutionResult()
    data object PermissionDenied : PluginExecutionResult()
}
