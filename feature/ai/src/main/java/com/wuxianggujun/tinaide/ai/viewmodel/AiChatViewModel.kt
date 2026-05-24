package com.wuxianggujun.tinaide.ai.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.ai.api.*
import com.wuxianggujun.tinaide.ai.channel.AiChannelRepository
import com.wuxianggujun.tinaide.ai.config.AiPreferences
import com.wuxianggujun.tinaide.ai.model.*
import com.wuxianggujun.tinaide.ai.repository.ConversationMeta
import com.wuxianggujun.tinaide.ai.repository.ConversationRepository
import com.wuxianggujun.tinaide.ai.retry.RetryCoordinator
import com.wuxianggujun.tinaide.ai.retry.RetryPolicy
import com.wuxianggujun.tinaide.ai.stream.ChatStreamController
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionCoordinator
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.executor.ContextDataProvider
import com.wuxianggujun.tinaide.ai.tools.executor.DiagnosticsCallbacksProvider
import com.wuxianggujun.tinaide.ai.tools.executor.ExecutionCallbacksProvider
import com.wuxianggujun.tinaide.ai.tools.executor.ProjectInfoProvider
import com.wuxianggujun.tinaide.ai.tools.executor.ToolCallbacksFactory
import com.wuxianggujun.tinaide.ai.tools.executor.diagnostics.DiagnosticsCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.editor.EditorToolCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.execution.ExecutionCallbacks
import com.wuxianggujun.tinaide.core.config.ai.AiAccessMode
import com.wuxianggujun.tinaide.core.config.ai.AiConfig
import com.wuxianggujun.tinaide.core.i18n.R
import com.wuxianggujun.tinaide.core.network.ApiResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * AI 聊天 ViewModel
 *
 * 支持对话历史持久化和工具调用
 */
class AiChatViewModel(
    private val context: Context,
    private val aiPreferences: AiPreferences,
    private val channelRepository: AiChannelRepository,
    private val conversationRepository: ConversationRepository? = null
) : ViewModel() {

    companion object {
        private const val TAG = "AiChatViewModel"
    }

    // 停止生成标志:使用 AtomicBoolean 避免 UI/协程/重试延迟 Job 间的并发可见性问题。
    private val stopGeneration = java.util.concurrent.atomic.AtomicBoolean(false)

    // ===== 工具执行上下文注册表 =====
    private val toolContextRegistry = ToolCallbacksFactory.createRegistry()
    private var toolExecutionContext: ToolExecutionContext? = null
    private val toolExecutionCoordinator = ToolExecutionCoordinator()

    // 项目信息提供者（延迟初始化）
    private var projectInfoProvider: ProjectInfoProvider? = null

    // ===== 重试相关 =====
    private val retryCoordinator by lazy { RetryCoordinator(viewModelScope) }

    // ===== UI 状态 =====
    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    private val _config = MutableStateFlow<AiConfig?>(null)
    val config: StateFlow<AiConfig?> = _config.asStateFlow()

    // AI面板配置
    private val _panelConfig = MutableStateFlow(AiPanelConfig())
    val panelConfig: StateFlow<AiPanelConfig> = _panelConfig.asStateFlow()

    // 流式状态控制器:累积/节流/事件分派都托管在这里,ViewModel 只消费结果。
    private val streamController by lazy { ChatStreamController(viewModelScope) }
    val streamingContent: StateFlow<String> get() = streamController.content
    val streamingReasoningContent: StateFlow<String> get() = streamController.reasoning
    val streamingToolCalls: StateFlow<List<ToolCall>> get() = streamController.toolCalls
    val streamingUsage: StateFlow<ChatUsage?> get() = streamController.usage

    // 对话列表
    val conversationList: Flow<List<ConversationMeta>> =
        conversationRepository?.conversationList ?: flowOf(emptyList())

    // 是否显示对话列表
    private val _showConversationList = MutableStateFlow(false)
    val showConversationList: StateFlow<Boolean> = _showConversationList.asStateFlow()

    private var apiClient: AiApiClient? = null
    private var currentJob: Job? = null

    // 标记 apiClient 是否已初始化完成
    private val _apiClientReady = MutableStateFlow(false)
    private val apiClientReady: StateFlow<Boolean> = _apiClientReady.asStateFlow()

    /**
     * 初始化项目上下文
     * @param projectRoot 项目根目录
     * @param getCurrentFile 获取当前文件的 lambda（懒加载）
     * @param getCurrentFileContent 获取当前文件内容的 lambda（懒加载）
     */
    fun initializeProjectContext(
        projectRoot: String,
        getCurrentFile: () -> String? = { null },
        getCurrentFileContent: () -> String? = { null }
    ) {
        // 注册项目信息提供者
        projectInfoProvider = ProjectInfoProvider(
            projectRoot = projectRoot,
            getCurrentFile = getCurrentFile,
            getCurrentFileContent = getCurrentFileContent
        )
        toolContextRegistry.registerProvider(projectInfoProvider!!)

        // 重新构建上下文
        rebuildToolContext()

        Timber.tag(TAG).i("Project context initialized: %s", projectRoot)
    }

    /**
     * 设置编辑器回调（简化版，不需要文件同步回调）
     * @param editorCallbacks 编辑器工具回调
     */
    fun setEditorCallbacks(editorCallbacks: EditorToolCallbacks) {
        val provider = object : ContextDataProvider {
            override fun provideData(): Map<String, Any> = buildMap {
                put("editorCallbacks", editorCallbacks)
            }
            override val priority: Int = 10
        }
        toolContextRegistry.registerProvider(provider)
        rebuildToolContext()
    }

    /**
     * 设置文件系统回调
     * @param fileSystemCallbacks 文件系统回调
     */
    fun setFileSystemCallbacks(fileSystemCallbacks: com.wuxianggujun.tinaide.ai.tools.executor.filesystem.FileSystemCallbacks) {
        val provider = object : ContextDataProvider {
            override fun provideData(): Map<String, Any> = buildMap {
                put("fileSystemCallbacks", fileSystemCallbacks)
            }
            override val priority: Int = 15
        }
        toolContextRegistry.registerProvider(provider)
        rebuildToolContext()
    }

    /**
     * 设置代码分析回调
     * @param codeAnalysisCallbacks 代码分析回调
     */
    fun setCodeAnalysisCallbacks(codeAnalysisCallbacks: com.wuxianggujun.tinaide.ai.tools.executor.code.CodeAnalysisCallbacks) {
        val provider = object : ContextDataProvider {
            override fun provideData(): Map<String, Any> = buildMap {
                put("codeAnalysisCallbacks", codeAnalysisCallbacks)
            }
            override val priority: Int = 20
        }
        toolContextRegistry.registerProvider(provider)
        rebuildToolContext()
    }

    /**
     * 重新构建工具执行上下文
     */
    private fun rebuildToolContext() {
        toolExecutionContext = toolContextRegistry.build()
    }

    /**
     * 设置诊断回调
     * @param diagnosticsCallbacks 诊断回调
     */
    fun setDiagnosticsCallbacks(diagnosticsCallbacks: DiagnosticsCallbacks) {
        // 注册诊断回调提供者
        val provider = DiagnosticsCallbacksProvider(
            getDiagnosticsCallbacks = { diagnosticsCallbacks }
        )
        toolContextRegistry.registerProvider(provider)

        // 重新构建上下文
        rebuildToolContext()
    }

    /**
     * 设置执行回调
     * @param executionCallbacks 执行回调
     */
    fun setExecutionCallbacks(executionCallbacks: ExecutionCallbacks) {
        // 注册执行回调提供者
        val provider = ExecutionCallbacksProvider(
            getExecutionCallbacks = { executionCallbacks }
        )
        toolContextRegistry.registerProvider(provider)

        // 重新构建上下文
        rebuildToolContext()
    }

    // 更新面板配置
    fun updatePanelConfig(config: AiPanelConfig) {
        _panelConfig.value = config
    }

    // 切换工具执行模式
    fun toggleToolExecutionMode() {
        viewModelScope.launch {
            val newMode = when (_panelConfig.value.toolExecutionMode) {
                ToolExecutionMode.AUTO -> ToolExecutionMode.MANUAL
                ToolExecutionMode.MANUAL -> ToolExecutionMode.AUTO
            }
            _panelConfig.update { it.copy(toolExecutionMode = newMode) }

            // 保存到数据库
            conversationRepository?.updateToolExecutionMode(newMode)
        }
    }

    // 清除待自动执行工具的标志
    fun clearPendingAutoToolExecution() {
        _uiState.update { it.copy(pendingAutoToolExecution = false) }
    }

    // 执行工具
    fun executeTool(toolCall: ToolCall) {
        viewModelScope.launch {
            // 先标记为执行中（等待状态更新完成）
            toolCall.id?.let { id ->
                markToolCallExecuting(id)
            }

            // 执行工具
            val toolResult = executeToolCallInternal(toolCall)

            // 提交结果
            toolCall.id?.let { id ->
                submitToolResult(id, toolResult)
            }
        }
    }

    // 标记工具调用为执行中
    private suspend fun markToolCallExecuting(toolCallId: String) {
        if (toolCallId.isBlank()) return

        // 查找包含该工具调用的 assistant 消息
        val assistantMessage = _uiState.value.messages.findLast { msg ->
            msg.role == ChatRole.ASSISTANT && msg.toolCalls?.any { it.id == toolCallId } == true
        }
        if (assistantMessage != null) {
            val updatedToolCalls = assistantMessage.toolCalls?.map { toolCall ->
                if (toolCall.id == toolCallId) {
                    toolCall.copy(executionStatus = ToolExecutionStatus.EXECUTING)
                } else {
                    toolCall
                }
            }
            val updatedMessage = assistantMessage.copy(toolCalls = updatedToolCalls)

            // 先手动更新 UI 状态（立即生效）
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg.id == updatedMessage.id) updatedMessage else msg
                    }
                )
            }

            // 再更新数据库（异步持久化）
            conversationRepository?.updateMessage(updatedMessage)
        }
    }

    fun cancelTool(toolCallId: String, reason: String) {
        val toolResult = ToolExecutionResult.Cancelled(reason)
        submitToolResult(toolCallId = toolCallId, toolResult = toolResult)
    }

    /**
     * 提交工具执行结果
     *
     * @param toolCallId 工具调用ID
     * @param toolResult 工具执行结果（统一数据结构，包含状态和结果）
     */
    private fun submitToolResult(toolCallId: String, toolResult: ToolExecutionResult) {
        viewModelScope.launch {
            // 等待 apiClient 初始化完成
            apiClientReady.first { it }

            val client = apiClient ?: run {
                _uiState.update { it.copy(error = context.getString(R.string.ai_error_configure_settings)) }
                return@launch
            }
            if (toolCallId.isBlank()) {
                _uiState.update { it.copy(error = context.getString(R.string.ai_error_tool_call_id_empty)) }
                return@launch
            }

            val status = when (toolResult) {
                is ToolExecutionResult.Cancelled -> ToolExecutionStatus.CANCELLED
                is ToolExecutionResult.Error -> ToolExecutionStatus.FAILED
                is ToolExecutionResult.Success -> ToolExecutionStatus.SUCCESS
            }

            val executionError = when (toolResult) {
                is ToolExecutionResult.Cancelled -> toolResult.reason
                is ToolExecutionResult.Error -> toolResult.message
                is ToolExecutionResult.Success -> "ok"
            }

            // 查找包含该工具调用的 assistant 消息
            val assistantMessage = _uiState.value.messages.findLast { msg ->
                msg.role == ChatRole.ASSISTANT && msg.toolCalls?.any { it.id == toolCallId } == true
            }

            if (assistantMessage != null) {
                val currentIndex = assistantMessage.toolCalls?.indexOfFirst { it.id == toolCallId } ?: -1

                // 更新工具调用的执行状态
                val updatedToolCalls = assistantMessage.toolCalls?.mapIndexed { index, toolCall ->
                    if (toolCall.id == toolCallId) {
                        // 更新当前工具的状态
                        toolCall.copy(
                            executionStatus = status,
                            executionResult = toolResult.toJsonString(),
                            executionError = executionError
                        )
                    } else if (status != ToolExecutionStatus.SUCCESS &&
                        toolCall.executionStatus == ToolExecutionStatus.PENDING &&
                        index > currentIndex
                    ) {
                        // 如果当前工具不是成功状态，自动取消后续所有待执行的工具
                        val cancelReason = when (status) {
                            ToolExecutionStatus.FAILED -> toolExecutionCoordinator.buildCascadeCancelReason(
                                ToolExecutionCoordinator.PreviousStatus.FAILED
                            )
                            ToolExecutionStatus.CANCELLED -> toolExecutionCoordinator.buildCascadeCancelReason(
                                ToolExecutionCoordinator.PreviousStatus.CANCELLED
                            )
                            else -> context.getString(R.string.ai_tool_cancelled_previous_incomplete)
                        }
                        toolCall.copy(
                            executionStatus = ToolExecutionStatus.CANCELLED,
                            executionError = cancelReason
                        )
                    } else {
                        toolCall
                    }
                }

                val updatedMessage = assistantMessage.copy(toolCalls = updatedToolCalls)

                // 先手动更新 UI 状态（立即生效）
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { msg ->
                            if (msg.id == updatedMessage.id) updatedMessage else msg
                        }
                    )
                }

                // 再更新数据库（异步持久化）
                conversationRepository?.updateMessage(updatedMessage)

                // 如果有工具被自动取消，需要为它们创建工具消息
                if (status != ToolExecutionStatus.SUCCESS) {
                    updatedToolCalls?.forEachIndexed { index, toolCall ->
                        if (index > currentIndex && toolCall.executionStatus == ToolExecutionStatus.CANCELLED) {
                            val cancelledResult = ToolExecutionResult.Cancelled(
                                toolCall.executionError ?: "Cancelled"
                            )
                            val cancelledToolMessage = ChatMessage(
                                role = ChatRole.TOOL,
                                content = cancelledResult.toJsonString(),
                                toolCallId = toolCall.id
                            )
                            conversationRepository?.addMessage(cancelledToolMessage)
                        }
                    }
                }
            }

            // 创建当前工具的工具消息（用于发送给 API，但不显示在 UI 中）
            val toolMessage = ChatMessage(
                role = ChatRole.TOOL,
                content = toolResult.toJsonString(),
                toolCallId = toolCallId
            )

            if (conversationRepository != null && _uiState.value.messages.isEmpty()) {
                conversationRepository.createConversation()
            }

            // 保存工具消息到数据库
            conversationRepository?.addMessage(toolMessage)

            // 工具执行完成后，执行工具结果后处理
            processAfterToolResult()
        }
    }

    /**
     * 工具结果回调后处理方法
     *
     * 每次工具执行完成或取消后调用，继续触发消息后处理
     */
    private suspend fun processAfterToolResult() {
        processAfterAiResponse()
    }

    /**
     * AI 回复结束后的后处理方法
     *
     * 处理逻辑：
     * 1. 如果最后一条消息没有工具调用 → 结束
     * 2. 如果是手动模式 → 检查工具是否都执行完，是则让AI继续回复
     * 3. 如果是自动模式 → 检查工具是否都执行完，是则让AI继续回复，否则执行下一个工具
     */
    private suspend fun processAfterAiResponse() {
        // 获取最后一条 assistant 消息
        val lastAssistantMessage = _uiState.value.messages.findLast { msg ->
            msg.role == ChatRole.ASSISTANT
        }

        if (lastAssistantMessage == null) {
            return
        }

        val toolCalls = lastAssistantMessage.toolCalls

        // 分支1: 没有工具调用 → 结束
        if (toolCalls.isNullOrEmpty()) {
            return
        }

        // 检查是否所有工具都已执行完成（包括取消）
        val allToolsCompleted = toolCalls.all { toolCall ->
            toolCall.executionStatus == ToolExecutionStatus.SUCCESS ||
                toolCall.executionStatus == ToolExecutionStatus.FAILED ||
                toolCall.executionStatus == ToolExecutionStatus.CANCELLED
        }

        // 查找第一个待执行的工具
        val nextPendingTool = toolCalls.firstOrNull { toolCall ->
            toolCall.executionStatus == ToolExecutionStatus.PENDING
        }

        when (_panelConfig.value.toolExecutionMode) {
            ToolExecutionMode.MANUAL -> {
                // 分支2: 手动模式
                if (allToolsCompleted) {
                    val client = apiClient
                    if (client != null) {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        if (!stopGeneration.get()) {
                            launchStreamingReply(client)
                        }
                    } else {
                        Timber.tag(TAG).e("Cannot continue tool flow in manual mode because apiClient is null")
                    }
                }
            }
            ToolExecutionMode.AUTO -> {
                // 分支3: 自动模式
                if (allToolsCompleted) {
                    val client = apiClient
                    if (client != null) {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        if (!stopGeneration.get()) {
                            launchStreamingReply(client)
                        }
                    } else {
                        Timber.tag(TAG).e("Cannot continue tool flow in auto mode because apiClient is null")
                    }
                } else if (nextPendingTool != null) {
                    // 高危工具自动执行守卫统一走 Coordinator
                    val allowDangerousAuto = _config.value?.tools?.allowDangerousToolsAuto == true
                    if (!toolExecutionCoordinator.isAutoExecutionAllowed(nextPendingTool, allowDangerousAuto)) {
                        Timber.tag(TAG).w(
                            "Skipping dangerous tool auto execution: %s",
                            nextPendingTool.function?.name,
                        )
                        return
                    }

                    // 先标记为执行中（等待状态更新完成）
                    nextPendingTool.id?.let { id ->
                        markToolCallExecuting(id)
                    }

                    // 执行工具
                    val toolResult = executeToolCallInternal(nextPendingTool)

                    // 提交结果
                    nextPendingTool.id?.let { id ->
                        submitToolResult(id, toolResult)
                    }
                }
            }
        }
    }

    init {
        // 监听配置变化:
        // - `_config` 订阅所有字段（UI 要用）
        // - `apiClient` 只在 "会影响 HTTP 路径" 的字段变化时重建:
        //   accessMode / activeChannelId。Prompt、网络超时、思考预算等不影响 client 构造,
        //   通过 distinctUntilChanged 过滤,避免流式过程被无关变更打断。
        viewModelScope.launch {
            aiPreferences.configFlow.collect { config ->
                _config.value = config
            }
        }
        viewModelScope.launch {
            aiPreferences.configFlow
                .distinctUntilChanged { old, new ->
                    old.accessMode == new.accessMode && old.activeChannelId == new.activeChannelId
                }
                .collect { config ->
                    apiClient = buildApiClient(config)
                    _apiClientReady.value = true
                }
        }

        // 监听当前对话变化（来自 Repository）
        conversationRepository?.let { repo ->
            viewModelScope.launch {
                repo.currentConversation.collect { conversation ->
                    if (conversation != null) {
                        _uiState.update { state ->
                            state.copy(
                                conversationId = conversation.id,
                                messages = conversation.messages
                            )
                        }
                    }
                }
            }

            // 监听当前对话的工具执行模式
            viewModelScope.launch {
                repo.currentToolExecutionMode.collect { mode ->
                    _panelConfig.update { it.copy(toolExecutionMode = mode) }
                }
            }
        }
    }

    /**
     * 实际执行工具调用。
     *
     * 工具查找 / 异常捕获 / 危险判定都收敛到 [ToolExecutionCoordinator]。
     */
    private suspend fun executeToolCallInternal(toolCall: ToolCall): ToolExecutionResult {
        val context = toolExecutionContext
            ?: run {
                val message = context.getString(R.string.ai_tool_error_context_not_initialized)
                Timber.tag(TAG).e(message)
                return ToolExecutionResult.Error(
                    message,
                )
            }
        return toolExecutionCoordinator.execute(toolCall, context)
    }

    private suspend fun buildApiClient(config: AiConfig): AiApiClient? {
        return when (config.accessMode) {
            AiAccessMode.TINA_GATEWAY -> null
            AiAccessMode.CUSTOM_BYOK -> {
                // 开源版直接使用用户配置的 BYOK 渠道，不再做会员门禁。
                val channelId = config.activeChannelId ?: return null
                val channel = channelRepository.getById(channelId) ?: return null
                val apiKey = channelRepository.getApiKey(channelId)
                channelRepository.markUsed(channelId)
                AiApiClient(
                    config = config.copy(
                        generation = config.generation.copy(model = channel.model),
                    ),
                    endpoint = channel.baseUrl,
                    auth = AuthStrategy.Bearer(apiKey),
                )
            }
        }
    }

    // ===== 发送消息 =====
    fun sendMessage(content: String, context: MessageContext? = null) {
        stopGeneration.set(false)
        viewModelScope.launch {
            // 等待 apiClient 初始化完成
            apiClientReady.first { it }

            val client = apiClient ?: run {
                _uiState.update { it.copy(error = resolveApiClientBlockerMessage(_config.value)) }
                return@launch
            }

            val userMessage = ChatMessage(
                role = ChatRole.USER,
                content = buildMessageContent(content, context)
            )

            // 确保有当前对话
            if (conversationRepository != null && _uiState.value.messages.isEmpty()) {
                // 如果是新对话，先创建
                conversationRepository.createConversation()
            }

            _uiState.update { state ->
                state.copy(
                    messages = state.messages + userMessage,
                    isLoading = true,
                    error = null
                )
            }

            // 保存用户消息
            conversationRepository?.addMessage(userMessage)

            launchStreamingReply(client)
        }
    }

    fun sendImageMessage(
        text: String,
        imageDataUrl: String,
        detail: String? = "auto",
    ) {
        sendImagesMessage(
            text = text,
            imageDataUrls = listOf(imageDataUrl),
            detail = detail
        )
    }

    fun sendImagesMessage(
        text: String,
        imageDataUrls: List<String>,
        detail: String? = "auto",
    ) {
        stopGeneration.set(false)
        viewModelScope.launch {
            // 等待 apiClient 初始化完成
            apiClientReady.first { it }

            val client = apiClient ?: run {
                _uiState.update { it.copy(error = resolveApiClientBlockerMessage(_config.value)) }
                return@launch
            }

            val urls = imageDataUrls.map { it.trim() }.filter { it.isNotBlank() }
            if (urls.isEmpty()) {
                _uiState.update { it.copy(error = context.getString(R.string.ai_error_image_empty)) }
                return@launch
            }

            val parts = buildList {
                if (text.isNotBlank()) {
                    add(OpenAiContentPart(type = "text", text = text))
                }
                urls.forEach { url ->
                    add(
                        OpenAiContentPart(
                            type = "image_url",
                            imageUrl = OpenAiImageUrl(url = url, detail = detail)
                        )
                    )
                }
            }

            val userMessage = ChatMessage(
                role = ChatRole.USER,
                content = if (text.isNotBlank()) text else context.getString(R.string.ai_image_count, urls.size),
                contentParts = parts
            )

            if (conversationRepository != null && _uiState.value.messages.isEmpty()) {
                conversationRepository.createConversation()
            }

            _uiState.update { state ->
                state.copy(
                    messages = state.messages + userMessage,
                    isLoading = true,
                    error = null
                )
            }

            conversationRepository?.addMessage(userMessage)
            launchStreamingReply(client)
        }
    }

    private fun launchStreamingReply(client: AiApiClient, messages: List<ChatMessage>? = null) {
        currentJob?.cancel()

        currentJob = viewModelScope.launch {
            val allMessages = messages ?: buildMessagesForApi()
            when (val result = streamController.stream(client, allMessages)) {
                is ChatStreamController.StreamResult.Completed -> handleStreamCompleted(result.message)
                is ChatStreamController.StreamResult.Failed -> handleStreamFailure(client, result.error)
            }
        }
    }

    private suspend fun handleStreamCompleted(assistantMessage: ChatMessage) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages + assistantMessage,
                isLoading = false,
            )
        }
        conversationRepository?.addMessage(assistantMessage)
        streamController.reset()
        retryCoordinator.reset()
        processAfterAiResponse()
    }

    private fun handleStreamFailure(client: AiApiClient, error: Throwable) {
        val formattedError = AiApiErrorHandler.handleError(error, "sendMessage")
        val userFriendlyMessage = AiApiErrorHandler.getUserFriendlyMessage(formattedError)
        Timber.tag(TAG).e(error, "Send message failed: %s", formattedError.technicalMessage)

        val config = _config.value
        val scheduled = config?.let { cfg ->
            val policy = RetryPolicy(
                maxAttempts = cfg.network.retryCount,
                baseDelayMs = cfg.network.retryDelaySeconds * 1000L,
            )
            retryCoordinator.scheduleRetry(
                error = error,
                policy = policy,
                isStopped = { stopGeneration.get() },
                onProgress = { attempt, delayMs ->
                    Timber.tag(TAG).w(
                        "Auto retry scheduled (attempt %s/%s) after %sms",
                        attempt,
                        cfg.network.retryCount,
                        delayMs,
                    )
                    _uiState.update {
                        it.copy(
                            isRetrying = true,
                            retryAttempt = attempt,
                            maxRetryAttempts = cfg.network.retryCount,
                            retryMessage = context.getString(
                                R.string.ai_auto_retrying,
                                attempt,
                                cfg.network.retryCount,
                            ),
                            retryError = userFriendlyMessage,
                            error = null,
                        )
                    }
                },
                onExecute = { performRetry(client) },
            )
        } ?: false

        if (!scheduled) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = userFriendlyMessage,
                    isRetrying = false,
                    retryAttempt = 0,
                    maxRetryAttempts = 0,
                    retryMessage = null,
                )
            }
            streamController.reset()
            retryCoordinator.reset()
        }
    }

    private fun prepareForRetry() {
        stopGeneration.set(false)
        retryCoordinator.reset()
        retryCoordinator.cancel()
    }

    private suspend fun performRetry(client: AiApiClient) {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) {
            _uiState.update { it.copy(error = context.getString(R.string.ai_error_retry_no_messages), isLoading = false) }
            return
        }

        // 获取最后一条是 USER 或 ASSISTANT 的消息
        val lastUserOrAiMessageIndex = messages.indexOfLast { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
        if (lastUserOrAiMessageIndex == -1) {
            _uiState.update { it.copy(error = context.getString(R.string.ai_error_retry_no_user_or_ai_message), isLoading = false) }
            return
        }

        val lastMessage = messages[lastUserOrAiMessageIndex]

        // 如果最后一条消息是用户消息，直接重新发送
        if (lastMessage.role == ChatRole.USER) {
            _uiState.update {
                it.copy(
                    error = null,
                    isLoading = true,
                    isRetrying = false,
                    retryAttempt = 0,
                    maxRetryAttempts = 0,
                    retryMessage = null,
                    retryError = null
                )
            }
            launchStreamingReply(client, messages)
            return
        }

        // 最后一条消息是 AI 消息
        if (lastMessage.role == ChatRole.ASSISTANT) {
            if (lastMessage.toolCalls != null && lastMessage.toolCalls.isNotEmpty()) {
                // 有工具调用：检查哪些工具没有执行结果
                val toolCallIds = lastMessage.toolCalls.map { it.id }.toSet()
                val executedToolCallIds = messages.drop(lastUserOrAiMessageIndex + 1)
                    .filter { it.toolCallId != null }
                    .map { it.toolCallId }
                    .toSet()
                val unexecutedToolCallIds = toolCallIds - executedToolCallIds

                if (unexecutedToolCallIds.isNotEmpty()) {
                    // 更新 AI 消息中未执行工具的状态为取消
                    val updatedToolCalls = lastMessage.toolCalls.map { toolCall ->
                        if (toolCall.id in unexecutedToolCallIds) {
                            toolCall.copy(executionStatus = ToolExecutionStatus.CANCELLED)
                        } else {
                            toolCall
                        }
                    }
                    val updatedAiMessage = lastMessage.copy(toolCalls = updatedToolCalls)
                    val updatedMessages = messages.toMutableList().apply {
                        set(lastUserOrAiMessageIndex, updatedAiMessage)
                    }

                    for (toolCallId in unexecutedToolCallIds) {
                        val cancelMessage = ChatMessage(
                            role = ChatRole.TOOL,
                            content = context.getString(R.string.ai_tool_execution_cancelled),
                            toolCallId = toolCallId
                        )
                        updatedMessages.add(cancelMessage)
                    }

                    _uiState.update {
                        it.copy(
                            messages = updatedMessages,
                            error = null,
                            isLoading = true,
                            isRetrying = false,
                            retryAttempt = 0,
                            maxRetryAttempts = 0,
                            retryMessage = null,
                            retryError = null
                        )
                    }

                    viewModelScope.launch {
                        conversationRepository?.updateMessage(updatedAiMessage)
                        for (toolCallId in unexecutedToolCallIds) {
                            val cancelMessage = ChatMessage(
                                role = ChatRole.TOOL,
                                content = context.getString(R.string.ai_tool_execution_cancelled),
                                toolCallId = toolCallId
                            )
                            conversationRepository?.addMessage(cancelMessage)
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            error = null,
                            isLoading = true,
                            isRetrying = false,
                            retryAttempt = 0,
                            maxRetryAttempts = 0,
                            retryMessage = null,
                            retryError = null
                        )
                    }
                }
            } else {
                // 没有工具调用：删除这条 AI 消息并重试
                val messagesToKeep = messages.take(lastUserOrAiMessageIndex)
                _uiState.update {
                    it.copy(
                        messages = messagesToKeep,
                        error = null,
                        isLoading = true,
                        isRetrying = false,
                        retryAttempt = 0,
                        maxRetryAttempts = 0,
                        retryMessage = null,
                        retryError = null
                    )
                }

                viewModelScope.launch {
                    conversationRepository?.deleteMessage(lastMessage.id)
                }
            }

            launchStreamingReply(client, _uiState.value.messages)
        }
    }

    // ===== 新建对话 =====
    fun newConversation() {
        currentJob?.cancel()
        streamController.cancel()
        streamController.reset()

        viewModelScope.launch {
            conversationRepository?.clearCurrentConversation()
            _uiState.update { AiChatUiState() }
            // 工具执行模式会通过 Flow 自动重置为 AUTO
        }
    }

    // ===== 显示总结确认对话框 =====
    fun showSummarizeConfirmDialog() {
        _uiState.update { it.copy(showSummarizeConfirmDialog = true) }
    }

    fun dismissSummarizeConfirmDialog() {
        _uiState.update { it.copy(showSummarizeConfirmDialog = false) }
    }

    fun clearSummarizeError() {
        _uiState.update { it.copy(summarizeError = null) }
    }

    // ===== 总结并继续新对话 =====
    fun summarizeAndContinue() {
        stopGeneration.set(false)
        // 关闭确认对话框
        dismissSummarizeConfirmDialog()

        viewModelScope.launch {
            // 等待 apiClient 初始化完成
            apiClientReady.first { it }

            val client = apiClient ?: run {
                _uiState.update { it.copy(error = context.getString(R.string.ai_error_not_ready)) }
                return@launch
            }

            val currentMessages = _uiState.value.messages
            if (currentMessages.isEmpty()) {
                // 没有消息，直接新建对话
                newConversation()
                return@launch
            }

            // 显示总结中状态
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                // 构建总结请求
                val config = _config.value
                val summaryPrompt = config?.prompt?.summaryPrompt?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.ai_summary_prompt)
                val summaryMessages = currentMessages + ChatMessage(
                    role = ChatRole.USER,
                    content = summaryPrompt
                )

                // 调用 API 获取总结（非流式）
                val result = client.chat(summaryMessages, false)

                when (result) {
                    is ApiResult.Success -> {
                        val summary = result.data.choices.firstOrNull()?.message?.content ?: ""

                        if (summary.isNotBlank()) {
                            // 创建新对话
                            newConversation()

                            // 等待状态更新完成
                            delay(100)

                            // 在新对话中发送总结内容作为上下文
                            val contextMessage = context.getString(
                                R.string.ai_summary_context_message,
                                summary
                            )
                            sendMessage(contextMessage, null)
                        } else {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    summarizeError = context.getString(R.string.ai_summary_error_empty)
                                )
                            }
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                summarizeError = context.getString(
                                    R.string.ai_summary_error,
                                    result.message ?: ""
                                )
                            )
                        }
                    }
                    is ApiResult.NetworkError -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                summarizeError = context.getString(
                                    R.string.ai_summary_network_error,
                                    result.message ?: ""
                                )
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to summarize conversation")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        summarizeError = context.getString(
                            R.string.ai_summary_error,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    // ===== 加载对话 =====
    fun loadConversation(id: String) {
        viewModelScope.launch {
            conversationRepository?.loadConversation(id)
            _showConversationList.value = false
        }
    }

    // ===== 删除对话 =====
    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationRepository?.deleteConversation(id)
        }
    }

    // ===== 重命名对话 =====
    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            conversationRepository?.renameConversation(id, newTitle)
        }
    }

    // ===== 切换对话列表显示 =====
    fun toggleConversationList() {
        _showConversationList.update { !it }
    }

    fun hideConversationList() {
        _showConversationList.value = false
    }

    // ===== 停止生成 =====
    fun stopGeneration() {
        stopGeneration.set(true)
        currentJob?.cancel()
        streamController.cancel()
        retryCoordinator.cancel()

        _uiState.update {
            it.copy(
                isLoading = false,
                error = null,
                isRetrying = false,
                retryAttempt = 0,
                maxRetryAttempts = 0,
                retryMessage = null
            )
        }
        // 取消 API 请求
        apiClient?.cancelRequest()

        // 清除重试状态
        retryCoordinator.reset()

        // 保存已经流出的部分消息(如有)
        val partialMessage = streamController.snapshotPartialMessage(
            suffix = context.getString(R.string.ai_generation_stopped),
        )
        if (partialMessage != null) {
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + partialMessage,
                    isLoading = false,
                    isRetrying = false,
                    retryAttempt = 0,
                    maxRetryAttempts = 0,
                    retryMessage = null,
                    retryError = null,
                )
            }
            viewModelScope.launch {
                conversationRepository?.addMessage(partialMessage)
            }
        } else {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRetrying = false,
                    retryAttempt = 0,
                    maxRetryAttempts = 0,
                    retryMessage = null,
                    retryError = null,
                )
            }
        }
        streamController.reset()
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    // ===== 重试最后一条消息 =====
    fun retryLastMessage() {
        viewModelScope.launch {
            prepareForRetry()

            // 立即更新UI状态为加载中，清除错误
            _uiState.update { it.copy(isLoading = true, error = null) }

            // 等待 apiClient 初始化完成
            apiClientReady.first { it }

            val client = apiClient ?: run {
                _uiState.update { it.copy(error = resolveApiClientBlockerMessage(_config.value), isLoading = false) }
                return@launch
            }

            // 调用 performRetry（suspend 函数）
            performRetry(client)
        }
    }

    private fun buildMessagesForApi(): List<ChatMessage> {
        val config = _config.value ?: return _uiState.value.messages
        val messages = mutableListOf<ChatMessage>()
        if (config.prompt.systemPrompt.isNotBlank()) {
            messages.add(ChatMessage(role = ChatRole.SYSTEM, content = config.prompt.systemPrompt))
        }
        messages.addAll(_uiState.value.messages)
        return messages
    }

    private fun buildMessageContent(content: String, context: MessageContext?): String {
        if (context == null) return content
        return buildString {
            when (context) {
                is MessageContext.CurrentFile -> {
                    appendLine(this@AiChatViewModel.context.getString(R.string.ai_context_current_file, context.fileName))
                    appendLine("```${context.language}")
                    appendLine(context.content)
                    appendLine("```")
                    appendLine()
                }
                is MessageContext.SelectedCode -> {
                    appendLine(this@AiChatViewModel.context.getString(R.string.ai_context_selected_code, context.fileName, context.startLine, context.endLine))
                    appendLine("```${context.language}")
                    appendLine(context.content)
                    appendLine("```")
                    appendLine()
                }
                is MessageContext.Error -> {
                    appendLine(this@AiChatViewModel.context.getString(R.string.ai_context_error_info))
                    appendLine("```")
                    appendLine(context.errorMessage)
                    appendLine("```")
                    appendLine()
                }
            }
            append(content)
        }
    }

    /**
     * 根据当前开源版配置生成 apiClient 为空时的具体错误提示。
     *
     * 集中到一处,避免 sendMessage / sendImagesMessage / retryLastMessage 等处复制分支。
     */
    private suspend fun resolveApiClientBlockerMessage(cfg: AiConfig?): String = when (cfg?.accessMode) {
        AiAccessMode.TINA_GATEWAY -> context.getString(R.string.ai_error_gateway_unavailable)
        AiAccessMode.CUSTOM_BYOK -> when {
            cfg.activeChannelId.isNullOrBlank() -> context.getString(R.string.ai_error_no_active_channel)
            else -> context.getString(R.string.ai_error_configure_api_key)
        }
        null -> context.getString(R.string.ai_error_configure_settings)
    }
}

/**
 * UI 状态
 */
data class AiChatUiState(
    val conversationId: String = UUID.randomUUID().toString(),
    val messages: List<ChatMessage> = emptyList(),
    val turns: List<ConversationTurn> = emptyList(),
    val isLoading: Boolean = false,
    val waitingForUserAction: Boolean = false,
    val pendingAutoToolExecution: Boolean = false,
    val autoToolExecutionTrigger: Int = 0, // 用于触发自动执行的计数器
    val error: String? = null,
    val showSummarizeConfirmDialog: Boolean = false,
    val summarizeError: String? = null,
    val isRetrying: Boolean = false,
    val retryAttempt: Int = 0,
    val maxRetryAttempts: Int = 0,
    val retryMessage: String? = null,
    val retryError: String? = null
) {
    /**
     * 获取当前回合
     */
    fun getCurrentTurn(): ConversationTurn? = turns.lastOrNull()

    /**
     * 是否有待执行的动作
     */
    fun hasPendingActions(): Boolean = getCurrentTurn()?.hasPendingActions() ?: false
}
