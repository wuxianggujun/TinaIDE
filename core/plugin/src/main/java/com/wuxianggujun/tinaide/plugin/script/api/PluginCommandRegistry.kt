package com.wuxianggujun.tinaide.plugin.script.api

import com.wuxianggujun.tinaide.core.commands.HostCommandInvocation
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.plugin.script.PluginExecutionResult
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

data class RegisteredPluginCommand(
    val pluginId: String,
    val pluginName: String,
    val commandId: String,
    val title: String?,
    val callbackName: String
)

data class PluginCommandRegistrationIssue(
    val pluginId: String,
    val pluginName: String,
    val commandId: String,
    val message: String,
    val conflictingPluginId: String? = null
)

data class PluginCommandExecutionIssue(
    val pluginId: String,
    val pluginName: String,
    val commandId: String,
    val message: String
)

data class PluginCommandDispatchResult(
    val handled: Boolean,
    val errorMessage: String? = null
)

data class PluginCommandAvailability(
    val available: Boolean,
    val errorMessage: String? = null
)

object PluginCommandRegistry {
    private const val TAG = "PluginCommandRegistry"
    private const val COMMANDS_API_NAMESPACE = "commands"
    private const val COMMANDS_EXECUTE_METHOD = "execute"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandsById = ConcurrentHashMap<String, RegisteredPluginCommand>()
    private val registrationIssuesByPluginId =
        ConcurrentHashMap<String, ConcurrentHashMap<String, PluginCommandRegistrationIssue>>()
    private val executionIssuesByPluginId =
        ConcurrentHashMap<String, ConcurrentHashMap<String, PluginCommandExecutionIssue>>()
    private val revisionCounter = AtomicLong(0L)
    private val _stateRevision = MutableStateFlow(0L)
    val stateRevision: StateFlow<Long> = _stateRevision.asStateFlow()

    @Volatile
    private var callbackInvoker: (suspend (String, String, Map<String, Any?>) -> PluginExecutionResult)? = null

    @Volatile
    private var permissionChecker: ((String, PluginPermission) -> PluginCommandAvailability)? = null

    fun setRuntimeAccess(
        callbackInvoker: suspend (String, String, Map<String, Any?>) -> PluginExecutionResult,
        permissionChecker: (String, PluginPermission) -> PluginCommandAvailability,
    ) {
        this.callbackInvoker = callbackInvoker
        this.permissionChecker = permissionChecker
        signalStateChanged()
    }

    fun register(
        pluginId: String,
        pluginName: String,
        commandId: String,
        callbackName: String,
        title: String? = null
    ): Result<RegisteredPluginCommand> {
        val normalizedPluginId = pluginId.trim()
        val normalizedPluginName = pluginName.trim().takeIf { it.isNotBlank() } ?: normalizedPluginId
        val normalizedCommandId = commandId.trim()

        if (normalizedPluginId.isBlank()) {
            return recordRegistrationFailure(
                pluginId = normalizedPluginId,
                pluginName = normalizedPluginName,
                commandId = normalizedCommandId,
                message = "Plugin ID is required",
            )
        }
        if (normalizedCommandId.isBlank()) {
            return recordRegistrationFailure(
                pluginId = normalizedPluginId,
                pluginName = normalizedPluginName,
                commandId = normalizedCommandId,
                message = "Command ID is required",
            )
        }

        val normalizedCallbackName = callbackName.trim()
        if (normalizedCallbackName.isBlank()) {
            return recordRegistrationFailure(
                pluginId = normalizedPluginId,
                pluginName = normalizedPluginName,
                commandId = normalizedCommandId,
                message = "Callback name is required",
            )
        }

        if (HostCommands.isSupported(normalizedCommandId)) {
            return recordRegistrationFailure(
                pluginId = normalizedPluginId,
                pluginName = normalizedPluginName,
                commandId = normalizedCommandId,
                message = "Command ID conflicts with host command: $normalizedCommandId",
            )
        }

        val existing = commandsById[normalizedCommandId]
        if (existing != null && existing.pluginId != normalizedPluginId) {
            return recordRegistrationFailure(
                pluginId = normalizedPluginId,
                pluginName = normalizedPluginName,
                commandId = normalizedCommandId,
                message = "Command ID already registered by plugin ${existing.pluginId}: $normalizedCommandId",
                conflictingPluginId = existing.pluginId,
            )
        }

        val command = RegisteredPluginCommand(
            pluginId = normalizedPluginId,
            pluginName = normalizedPluginName,
            commandId = normalizedCommandId,
            title = title?.trim()?.takeIf { it.isNotBlank() },
            callbackName = normalizedCallbackName
        )

        commandsById[normalizedCommandId] = command
        clearRegistrationIssue(normalizedPluginId, normalizedCommandId)
        clearExecutionIssue(normalizedPluginId, normalizedCommandId)
        signalStateChanged()
        Timber.tag(TAG).d(
            "Registered plugin command: commandId=%s pluginId=%s callback=%s",
            normalizedCommandId,
            normalizedPluginId,
            normalizedCallbackName
        )
        return Result.success(command)
    }

    fun unregister(pluginId: String, commandId: String): Boolean {
        val normalizedPluginId = pluginId.trim()
        val normalizedCommandId = commandId.trim()
        if (normalizedCommandId.isBlank()) return false

        var changed = clearRegistrationIssue(normalizedPluginId, normalizedCommandId)
        changed = clearExecutionIssue(normalizedPluginId, normalizedCommandId) || changed
        val existing = commandsById[normalizedCommandId]
        if (existing == null || existing.pluginId != normalizedPluginId) {
            if (changed) {
                signalStateChanged()
            }
            return false
        }

        return commandsById.remove(normalizedCommandId, existing).also { removed ->
            if (removed) {
                changed = true
                changed = clearRegistrationIssuesBlockedBy(
                    conflictingPluginId = normalizedPluginId,
                    commandIds = setOf(normalizedCommandId),
                ) ||
                    changed
                Timber.tag(TAG).d(
                    "Unregistered plugin command: commandId=%s pluginId=%s",
                    normalizedCommandId,
                    normalizedPluginId
                )
            }
            if (changed) {
                signalStateChanged()
            }
        }
    }

    fun unregisterAll(pluginId: String) {
        val normalizedPluginId = pluginId.trim()
        if (normalizedPluginId.isBlank()) return
        val removedCommandIds = mutableSetOf<String>()
        commandsById.entries.removeIf { (_, command) ->
            (command.pluginId == normalizedPluginId).also { remove ->
                if (remove) {
                    removedCommandIds += command.commandId
                }
            }
        }
        var changed = removedCommandIds.isNotEmpty()
        changed = (registrationIssuesByPluginId.remove(normalizedPluginId) != null) || changed
        changed = (executionIssuesByPluginId.remove(normalizedPluginId) != null) || changed
        changed = clearRegistrationIssuesBlockedBy(conflictingPluginId = normalizedPluginId) || changed
        if (changed) {
            signalStateChanged()
        }
        Timber.tag(TAG).d("Unregistered all commands for plugin: %s", normalizedPluginId)
    }

    fun clear() {
        val changed = commandsById.isNotEmpty() ||
            registrationIssuesByPluginId.isNotEmpty() ||
            executionIssuesByPluginId.isNotEmpty()
        commandsById.clear()
        registrationIssuesByPluginId.clear()
        executionIssuesByPluginId.clear()
        callbackInvoker = null
        permissionChecker = null
        if (changed) {
            signalStateChanged()
        }
    }

    fun isRegistered(commandId: String, pluginId: String? = null): Boolean {
        val command = commandsById[commandId.trim()] ?: return false
        return pluginId == null || command.pluginId == pluginId
    }

    fun registrationIssue(commandId: String, pluginId: String): PluginCommandRegistrationIssue? {
        val normalizedPluginId = pluginId.trim()
        val normalizedCommandId = commandId.trim()
        if (normalizedPluginId.isBlank() || normalizedCommandId.isBlank()) return null
        return registrationIssuesByPluginId[normalizedPluginId]?.get(normalizedCommandId)
    }

    fun executionIssue(commandId: String, pluginId: String): PluginCommandExecutionIssue? {
        val normalizedPluginId = pluginId.trim()
        val normalizedCommandId = commandId.trim()
        if (normalizedPluginId.isBlank() || normalizedCommandId.isBlank()) return null
        return executionIssuesByPluginId[normalizedPluginId]?.get(normalizedCommandId)
    }

    fun titleFor(commandId: String, pluginId: String? = null): String? {
        val command = commandsById[commandId.trim()] ?: return null
        if (pluginId != null && command.pluginId != pluginId) return null
        return command.title
    }

    fun availability(commandId: String, pluginId: String? = null): PluginCommandAvailability {
        val command = commandsById[commandId.trim()] ?: return PluginCommandAvailability(available = false)
        if (pluginId != null && command.pluginId != pluginId) {
            return PluginCommandAvailability(available = false)
        }

        return permissionChecker?.invoke(command.pluginId, PluginPermission.COMMAND_EXECUTE)
            ?: PluginCommandAvailability(available = false)
    }

    fun dispatch(
        commandId: String,
        invocation: HostCommandInvocation = HostCommandInvocation()
    ): Boolean = dispatchWithResult(commandId, invocation).handled

    fun dispatchWithResult(
        commandId: String,
        invocation: HostCommandInvocation = HostCommandInvocation()
    ): PluginCommandDispatchResult {
        val command = commandsById[commandId.trim()] ?: return PluginCommandDispatchResult(handled = false)
        val permission = permissionChecker?.invoke(command.pluginId, PluginPermission.COMMAND_EXECUTE)
            ?: return PluginCommandDispatchResult(handled = false)
        if (!permission.available) {
            val errorMessage = permission.errorMessage ?: "Plugin command permission denied"
            Timber.tag(TAG).w(
                "Plugin command permission denied before dispatch: commandId=%s pluginId=%s",
                command.commandId,
                command.pluginId
            )
            return PluginCommandDispatchResult(
                handled = false,
                errorMessage = errorMessage
            )
        }

        val payload = buildInvocationPayload(command.commandId, invocation)

        scope.launch {
            val result = runCatching {
                callbackInvoker?.invoke(command.pluginId, command.callbackName, payload)
                    ?: PluginExecutionResult.Error("Plugin runtime unavailable")
            }.getOrElse { throwable ->
                val message = throwable.message
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: throwable::class.java.simpleName
                if (recordExecutionIssue(command, "Unhandled exception: $message")) {
                    signalStateChanged()
                }
                Timber.tag(TAG).e(
                    throwable,
                    "Plugin command callback crashed: commandId=%s pluginId=%s",
                    command.commandId,
                    command.pluginId
                )
                return@launch
            }
            when (result) {
                is PluginExecutionResult.Success -> {
                    if (clearExecutionIssue(command.pluginId, command.commandId)) {
                        signalStateChanged()
                    }
                    Timber.tag(TAG).d(
                        "Plugin command dispatched: commandId=%s pluginId=%s",
                        command.commandId,
                        command.pluginId
                    )
                }
                is PluginExecutionResult.Error -> {
                    Timber.tag(TAG).e(
                        "Plugin command failed: commandId=%s pluginId=%s message=%s",
                        command.commandId,
                        command.pluginId,
                        result.message
                    )
                    if (recordExecutionIssue(command, result.message)) {
                        signalStateChanged()
                    }
                }
                PluginExecutionResult.Timeout -> {
                    Timber.tag(TAG).w(
                        "Plugin command timed out: commandId=%s pluginId=%s",
                        command.commandId,
                        command.pluginId
                    )
                    if (recordExecutionIssue(command, "Command execution timed out")) {
                        signalStateChanged()
                    }
                }
                PluginExecutionResult.PermissionDenied -> {
                    Timber.tag(TAG).w(
                        "Plugin command permission denied: commandId=%s pluginId=%s",
                        command.commandId,
                        command.pluginId
                    )
                    if (
                        recordExecutionIssue(
                            command,
                            "Command execution was denied by runtime permission check",
                        )
                    ) {
                        signalStateChanged()
                    }
                }
            }
        }
        return PluginCommandDispatchResult(handled = true)
    }

    private fun buildInvocationPayload(
        commandId: String,
        invocation: HostCommandInvocation
    ): Map<String, Any?> {
        val file = invocation.file
        return mapOf(
            "commandId" to commandId,
            "filePath" to file?.absolutePath,
            "fileName" to file?.name,
            "isDirectory" to (invocation.isDirectory ?: file?.isDirectory ?: false),
            "isDirty" to (invocation.isDirty ?: false)
        )
    }

    private fun recordRegistrationFailure(
        pluginId: String,
        pluginName: String,
        commandId: String,
        message: String,
        conflictingPluginId: String? = null,
    ): Result<RegisteredPluginCommand> {
        if (recordRegistrationIssue(pluginId, pluginName, commandId, message, conflictingPluginId)) {
            signalStateChanged()
        }
        return Result.failure(IllegalArgumentException(message))
    }

    private fun recordRegistrationIssue(
        pluginId: String,
        pluginName: String,
        commandId: String,
        message: String,
        conflictingPluginId: String?,
    ): Boolean {
        if (pluginId.isBlank() || commandId.isBlank()) return false
        val issue = PluginCommandRegistrationIssue(
            pluginId = pluginId,
            pluginName = pluginName,
            commandId = commandId,
            message = message,
            conflictingPluginId = conflictingPluginId,
        )
        val previous = registrationIssuesByPluginId.computeIfAbsent(pluginId) {
            ConcurrentHashMap()
        }.put(commandId, issue)
        Timber.tag(TAG).w(
            "Plugin command registration failed: commandId=%s pluginId=%s message=%s",
            commandId,
            pluginId,
            message
        )
        return previous != issue
    }

    private fun recordExecutionIssue(
        command: RegisteredPluginCommand,
        message: String,
    ): Boolean {
        val normalizedMessage = message.trim().takeIf { it.isNotBlank() }
            ?: "Unknown command execution failure"
        val issue = PluginCommandExecutionIssue(
            pluginId = command.pluginId,
            pluginName = command.pluginName,
            commandId = command.commandId,
            message = normalizedMessage,
        )
        val previous = executionIssuesByPluginId.computeIfAbsent(command.pluginId) {
            ConcurrentHashMap()
        }.put(command.commandId, issue)
        return previous != issue
    }

    private fun clearRegistrationIssue(pluginId: String, commandId: String): Boolean {
        if (pluginId.isBlank() || commandId.isBlank()) return false
        val issues = registrationIssuesByPluginId[pluginId] ?: return false
        val removed = issues.remove(commandId) != null
        if (issues.isEmpty()) {
            registrationIssuesByPluginId.remove(pluginId, issues)
        }
        return removed
    }

    private fun clearExecutionIssue(pluginId: String, commandId: String): Boolean {
        if (pluginId.isBlank() || commandId.isBlank()) return false
        val issues = executionIssuesByPluginId[pluginId] ?: return false
        val removed = issues.remove(commandId) != null
        if (issues.isEmpty()) {
            executionIssuesByPluginId.remove(pluginId, issues)
        }
        return removed
    }

    private fun clearRegistrationIssuesBlockedBy(
        conflictingPluginId: String,
        commandIds: Set<String>? = null,
    ): Boolean {
        if (conflictingPluginId.isBlank()) return false
        var changed = false
        registrationIssuesByPluginId.forEach { (pluginId, issues) ->
            val removed = issues.entries.removeIf { (_, issue) ->
                issue.conflictingPluginId == conflictingPluginId &&
                    (commandIds == null || issue.commandId in commandIds)
            }
            if (removed) {
                changed = true
                if (issues.isEmpty()) {
                    registrationIssuesByPluginId.remove(pluginId, issues)
                }
            }
        }
        return changed
    }

    private fun signalStateChanged() {
        _stateRevision.value = revisionCounter.incrementAndGet()
    }
}
