package com.wuxianggujun.tinaide.ai.viewmodel

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.api.AiApiClient
import com.wuxianggujun.tinaide.ai.api.AuthStrategy
import com.wuxianggujun.tinaide.ai.api.ChatChoice
import com.wuxianggujun.tinaide.ai.api.ChatConversation
import com.wuxianggujun.tinaide.ai.api.ChatMessage
import com.wuxianggujun.tinaide.ai.api.ChatResponse
import com.wuxianggujun.tinaide.ai.api.ChatResponseMessage
import com.wuxianggujun.tinaide.ai.api.ChatRole
import com.wuxianggujun.tinaide.ai.api.MessageContext
import com.wuxianggujun.tinaide.ai.api.ToolCall
import com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus
import com.wuxianggujun.tinaide.ai.api.ToolFunction
import com.wuxianggujun.tinaide.ai.channel.AiChannelRepository
import com.wuxianggujun.tinaide.ai.config.AiPreferences
import com.wuxianggujun.tinaide.ai.model.ActionStatus
import com.wuxianggujun.tinaide.ai.model.AiPanelConfig
import com.wuxianggujun.tinaide.ai.model.AssistantResponse
import com.wuxianggujun.tinaide.ai.model.ConversationTurn
import com.wuxianggujun.tinaide.ai.model.ToolAction
import com.wuxianggujun.tinaide.ai.model.ToolExecutionMode
import com.wuxianggujun.tinaide.ai.repository.ConversationRepository
import com.wuxianggujun.tinaide.ai.tools.AiTool
import com.wuxianggujun.tinaide.ai.tools.ToolCategory
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.ToolRegistry
import com.wuxianggujun.tinaide.ai.tools.executor.code.CodeAnalysisCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.diagnostics.DiagnosticsCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.editor.EditorToolCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.execution.ExecutionCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.FileSystemCallbacks
import com.wuxianggujun.tinaide.core.config.ai.AiAccessMode
import com.wuxianggujun.tinaide.core.config.ai.AiConfig
import com.wuxianggujun.tinaide.core.config.ai.AiGenerationSettings
import com.wuxianggujun.tinaide.core.config.ai.AiPromptSettings
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.i18n.R
import com.wuxianggujun.tinaide.core.network.ApiResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @After
    fun tearDown() {
        ToolRegistry.clear()
        resetAppStrings()
    }

    @Test
    fun `toggle tool execution mode updates panel state and repository`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.updateToolExecutionMode(any()) } returns Unit
        every { repository.conversationList } returns flowOf(emptyList())
        every { repository.currentConversation } returns flowOf(null)
        every { repository.currentToolExecutionMode } returns flowOf(ToolExecutionMode.AUTO)
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()

        viewModel.toggleToolExecutionMode()
        advanceUntilIdle()

        assertThat(viewModel.panelConfig.value.toolExecutionMode).isEqualTo(ToolExecutionMode.MANUAL)
        coVerify(exactly = 1) { repository.updateToolExecutionMode(ToolExecutionMode.MANUAL) }
    }

    @Test
    fun `callback setters rebuild tool execution context with registered providers`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        val editorCallbacks = mockk<EditorToolCallbacks>(relaxed = true)
        val fileSystemCallbacks = mockk<FileSystemCallbacks>(relaxed = true)
        val codeAnalysisCallbacks = mockk<CodeAnalysisCallbacks>(relaxed = true)
        val diagnosticsCallbacks = mockk<DiagnosticsCallbacks>(relaxed = true)
        val executionCallbacks = mockk<ExecutionCallbacks>(relaxed = true)

        viewModel.initializeProjectContext(
            projectRoot = "C:/project",
            getCurrentFile = { "src/Main.kt" },
            getCurrentFileContent = { "fun main() = Unit" },
        )
        viewModel.setEditorCallbacks(editorCallbacks)
        viewModel.setFileSystemCallbacks(fileSystemCallbacks)
        viewModel.setCodeAnalysisCallbacks(codeAnalysisCallbacks)
        viewModel.setDiagnosticsCallbacks(diagnosticsCallbacks)
        viewModel.setExecutionCallbacks(executionCallbacks)

        val toolContext = viewModel.toolContextForTest()
        assertThat(toolContext.projectRoot).isEqualTo("C:/project")
        assertThat(toolContext.currentFile).isEqualTo("src/Main.kt")
        assertThat(toolContext.currentFileContent).isEqualTo("fun main() = Unit")
        assertThat(toolContext.additionalData["editorCallbacks"]).isSameInstanceAs(editorCallbacks)
        assertThat(toolContext.additionalData["fileSystemCallbacks"]).isSameInstanceAs(fileSystemCallbacks)
        assertThat(toolContext.additionalData["codeAnalysisCallbacks"]).isSameInstanceAs(codeAnalysisCallbacks)
        assertThat(toolContext.additionalData["diagnosticsCallbacks"]).isSameInstanceAs(diagnosticsCallbacks)
        assertThat(toolContext.additionalData["executionCallbacks"]).isSameInstanceAs(executionCallbacks)
    }

    @Test
    fun `panel config and conversation list commands update state and repository`() = runTest {
        val repository = mockConversationRepository()
        coEvery { repository.loadConversation("conversation-1") } returns null
        coEvery { repository.deleteConversation("conversation-2") } returns Unit
        coEvery { repository.renameConversation("conversation-3", "renamed") } returns Unit
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()

        viewModel.updatePanelConfig(
            AiPanelConfig(
                toolExecutionMode = ToolExecutionMode.MANUAL,
                autoScrollToBottom = false,
                showTokenUsage = false,
            )
        )
        viewModel.toggleConversationList()
        assertThat(viewModel.panelConfig.value.toolExecutionMode).isEqualTo(ToolExecutionMode.MANUAL)
        assertThat(viewModel.panelConfig.value.autoScrollToBottom).isFalse()
        assertThat(viewModel.panelConfig.value.showTokenUsage).isFalse()
        assertThat(viewModel.showConversationList.value).isTrue()

        viewModel.loadConversation("conversation-1")
        viewModel.deleteConversation("conversation-2")
        viewModel.renameConversation("conversation-3", "renamed")
        advanceUntilIdle()

        assertThat(viewModel.showConversationList.value).isFalse()
        viewModel.toggleConversationList()
        viewModel.hideConversationList()
        assertThat(viewModel.showConversationList.value).isFalse()
        coVerify(exactly = 1) { repository.loadConversation("conversation-1") }
        coVerify(exactly = 1) { repository.deleteConversation("conversation-2") }
        coVerify(exactly = 1) { repository.renameConversation("conversation-3", "renamed") }
    }

    @Test
    fun `conversation list falls back to empty flow without repository`() = runTest {
        val viewModel = newViewModel(conversationRepository = null)

        assertThat(viewModel.conversationList.first()).isEmpty()
    }

    @Test
    fun `streaming state getters expose initial controller state`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertThat(viewModel.streamingContent.value).isEmpty()
        assertThat(viewModel.streamingReasoningContent.value).isEmpty()
        assertThat(viewModel.streamingToolCalls.value).isEmpty()
        assertThat(viewModel.streamingUsage.value).isNull()
    }

    @Test
    fun `send message reports byok configuration blocker messages`() = runTest {
        val noChannelViewModel = newViewModel()
        advanceUntilIdle()

        noChannelViewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(noChannelViewModel.uiState.value.error).isEqualTo("no active channel")
        assertThat(noChannelViewModel.uiState.value.messages).isEmpty()

        val channelRepository = mockk<AiChannelRepository>(relaxed = true)
        coEvery { channelRepository.getById("missing-key-channel") } returns null
        val missingKeyViewModel = newViewModel(
            config = AiConfig(
                accessMode = AiAccessMode.CUSTOM_BYOK,
                activeChannelId = "missing-key-channel",
                generation = AiGenerationSettings(model = "gpt-test"),
                prompt = AiPromptSettings(systemPrompt = "system prompt"),
            ),
            channelRepository = channelRepository,
        )
        advanceUntilIdle()

        missingKeyViewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(missingKeyViewModel.uiState.value.error).isEqualTo("configure api key")
        assertThat(missingKeyViewModel.uiState.value.messages).isEmpty()
    }

    @Test
    fun `send message reports settings blocker when config is absent`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.clearConfigForTest()

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("configure settings")
        assertThat(viewModel.uiState.value.messages).isEmpty()
    }

    @Test
    fun `send images rejects blank image list before creating message`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.setApiClientForTest(newFakeApiClient(responseBody = streamResponse("unused")))

        viewModel.sendImagesMessage(
            text = "describe this",
            imageDataUrls = listOf(" ", "\n"),
        )

        val state = viewModel.awaitState { it.error == "image empty" }
        assertThat(state.messages).isEmpty()
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `send images reports client blocker before validating images`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.sendImagesMessage(
            text = "describe this",
            imageDataUrls = emptyList(),
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("no active channel")
        assertThat(viewModel.uiState.value.messages).isEmpty()
    }

    @Test
    fun `send images sends multimodal content and stores assistant reply`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.setApiClientForTest(
            newFakeApiClient(
                capturedRequest = capturedRequest,
                responseBody = streamResponse("vision ok"),
                contentType = "text/event-stream",
            )
        )

        viewModel.sendImagesMessage(
            text = "describe this",
            imageDataUrls = listOf(" data:image/png;base64,a ", "", "data:image/jpeg;base64,b"),
            detail = "high",
        )

        val state = viewModel.awaitState { current ->
            current.messages.size == 2 &&
                current.messages.last().role == ChatRole.ASSISTANT &&
                current.messages.last().content == "vision ok" &&
                !current.isLoading
        }
        val userMessage = state.messages.first()
        assertThat(userMessage.content).isEqualTo("describe this")
        assertThat(userMessage.contentParts).hasSize(3)
        assertThat(userMessage.contentParts?.get(1)?.imageUrl?.url).isEqualTo("data:image/png;base64,a")
        assertThat(userMessage.contentParts?.get(1)?.imageUrl?.detail).isEqualTo("high")
        assertThat(userMessage.contentParts?.get(2)?.imageUrl?.url).isEqualTo("data:image/jpeg;base64,b")

        val requestBody = capturedRequest.get().bodyToJsonObject()
        val requestMessages = requestBody["messages"]!!.jsonArray
        val requestUserContent = requestMessages.last().jsonObject["content"]!!.jsonArray
        assertThat(requestUserContent[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("text")
        assertThat(requestUserContent[1].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("image_url")
        assertThat(
            requestUserContent[1]
                .jsonObject["image_url"]!!
                .jsonObject["url"]!!
                .jsonPrimitive
                .content
        ).isEqualTo("data:image/png;base64,a")
    }

    @Test
    fun `send image message uses image count fallback and persists conversation`() = runTest {
        val repository = mockConversationRepository()
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()
        viewModel.setApiClientForTest(
            newFakeApiClient(
                responseBody = streamResponse("single image ok"),
                contentType = "text/event-stream",
            )
        )

        viewModel.sendImageMessage(
            text = "",
            imageDataUrl = " data:image/png;base64,only ",
            detail = null,
        )

        val state = viewModel.awaitState { current ->
            current.messages.size == 2 &&
                current.messages.last().role == ChatRole.ASSISTANT &&
                current.messages.last().content == "single image ok" &&
                !current.isLoading
        }
        val userMessage = state.messages.first()
        assertThat(userMessage.content).isEqualTo("image count: 1")
        assertThat(userMessage.contentParts).hasSize(1)
        assertThat(userMessage.contentParts!!.single().imageUrl?.url).isEqualTo("data:image/png;base64,only")
        assertThat(userMessage.contentParts!!.single().imageUrl?.detail).isNull()

        coVerify(exactly = 1) { repository.createConversation(any(), any()) }
        coVerify(exactly = 1) {
            repository.addMessage(match { it.role == ChatRole.USER && it.content == "image count: 1" })
        }
        coVerify(exactly = 1) {
            repository.addMessage(match { it.role == ChatRole.ASSISTANT && it.content == "single image ok" })
        }
    }

    @Test
    fun `send message prepends current file selected code and error context`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.setApiClientForTest(
            newFakeApiClient(
                responseBody = streamResponse("ok"),
                contentType = "text/event-stream",
            )
        )

        viewModel.sendMessage(
            content = "explain current file",
            context = MessageContext.CurrentFile(
                fileName = "Main.kt",
                language = "kotlin",
                content = "fun main() = Unit",
            ),
        )
        viewModel.awaitState { it.messages.size == 2 && !it.isLoading }

        viewModel.sendMessage(
            content = "explain selected code",
            context = MessageContext.SelectedCode(
                fileName = "Main.kt",
                language = "kotlin",
                content = "val x = 1",
                startLine = 3,
                endLine = 4,
            ),
        )
        viewModel.awaitState { it.messages.size == 4 && !it.isLoading }

        viewModel.sendMessage(
            content = "explain error",
            context = MessageContext.Error("Compilation failed"),
        )
        val state = viewModel.awaitState { it.messages.size == 6 && !it.isLoading }

        val userMessages = state.messages.filter { it.role == ChatRole.USER }
        assertThat(userMessages[0].content).contains("fun main() = Unit")
        assertThat(userMessages[0].content).contains("explain current file")
        assertThat(userMessages[1].content).contains("val x = 1")
        assertThat(userMessages[1].content).contains("explain selected code")
        assertThat(userMessages[2].content).contains("Compilation failed")
        assertThat(userMessages[2].content).contains("explain error")
    }

    @Test
    fun `send message surfaces localized stream parse failure`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.setApiClientForTest(
            newFakeApiClient(
                responseBody = """
                    data: not-json

                """.trimIndent(),
                contentType = "text/event-stream",
            )
        )

        viewModel.sendMessage("hello")

        val state = viewModel.awaitState { current ->
            current.error?.contains("stream unparseable") == true && !current.isLoading
        }
        assertThat(state.messages).hasSize(1)
        assertThat(state.messages.single().content).isEqualTo("hello")
    }

    @Test
    fun `summarize empty conversation clears current conversation`() = runTest {
        val repository = mockConversationRepository()
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()
        viewModel.setApiClientForTest(newFakeApiClient(responseBody = chatResponse("unused")))

        viewModel.showSummarizeConfirmDialog()
        viewModel.summarizeAndContinue()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.showSummarizeConfirmDialog).isFalse()
        assertThat(viewModel.uiState.value.messages).isEmpty()
        verify(exactly = 1) { repository.clearCurrentConversation() }
    }

    @Test
    fun `summarize stores localized error when response is empty`() = runTest {
        val conversation = ChatConversation(
            id = "conversation-1",
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
        )
        val viewModel = newViewModel(conversationRepository = mockConversationRepository(conversation))
        advanceUntilIdle()
        viewModel.setApiClientForTest(newFakeApiClient(responseBody = chatResponse("")))

        viewModel.showSummarizeConfirmDialog()
        viewModel.summarizeAndContinue()

        val state = viewModel.awaitState { it.summarizeError == "summary empty" }
        assertThat(state.isLoading).isFalse()
        assertThat(state.showSummarizeConfirmDialog).isFalse()
    }

    @Test
    fun `summarize maps api network and thrown failures to localized errors`() = runTest {
        suspend fun assertSummarizeError(
            client: AiApiClient,
            expectedError: String,
        ) {
            val conversation = ChatConversation(
                id = "conversation-summary-failure",
                messages = listOf(ChatMessage(role = ChatRole.USER, content = "old question")),
            )
            val viewModel = newViewModel(conversationRepository = mockConversationRepository(conversation))
            advanceUntilIdle()
            viewModel.setApiClientForTest(client)

            viewModel.showSummarizeConfirmDialog()
            viewModel.summarizeAndContinue()

            val state = viewModel.awaitState { it.summarizeError == expectedError }
            assertThat(state.isLoading).isFalse()
            assertThat(state.showSummarizeConfirmDialog).isFalse()
        }

        val apiErrorClient = mockk<AiApiClient>(relaxed = true)
        coEvery { apiErrorClient.chat(any(), false) } returns ApiResult.Error(500, "server down")
        assertSummarizeError(apiErrorClient, "summary error: server down")

        val networkErrorClient = mockk<AiApiClient>(relaxed = true)
        coEvery { networkErrorClient.chat(any(), false) } returns ApiResult.NetworkError("offline")
        assertSummarizeError(networkErrorClient, "summary network error: offline")

        val throwingClient = mockk<AiApiClient>(relaxed = true)
        coEvery { throwingClient.chat(any(), false) } throws IllegalStateException("explode")
        assertSummarizeError(throwingClient, "summary error: explode")
    }

    @Test
    fun `summarize success starts new conversation with summary context and streams reply`() = runTest {
        val conversation = ChatConversation(
            id = "conversation-1",
            messages = listOf(
                ChatMessage(role = ChatRole.USER, content = "old question"),
                ChatMessage(role = ChatRole.ASSISTANT, content = "old answer"),
            ),
        )
        val repository = mockConversationRepository(conversation)
        val client = mockk<AiApiClient>(relaxed = true)
        val summaryRequests = mutableListOf<List<ChatMessage>>()
        val streamRequests = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(any(), false) } coAnswers {
            summaryRequests += firstArg<List<ChatMessage>>()
            ApiResult.Success(chatResponseModel("short summary"))
        }
        coEvery { client.chatStream(any(), any(), any(), any()) } coAnswers {
            streamRequests += firstArg<List<ChatMessage>>()
            val onEvent = secondArg<(AiApiClient.ChatStreamEvent) -> Unit>()
            onEvent(AiApiClient.ChatStreamEvent.TextDelta("continued answer"))
            onEvent(AiApiClient.ChatStreamEvent.Done)
        }
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()
        viewModel.setApiClientForTest(client)

        viewModel.showSummarizeConfirmDialog()
        viewModel.summarizeAndContinue()
        advanceUntilIdle()

        val state = viewModel.awaitState { current ->
            current.messages.any { it.role == ChatRole.ASSISTANT && it.content == "continued answer" } &&
                !current.isLoading
        }
        assertThat(state.showSummarizeConfirmDialog).isFalse()
        assertThat(state.summarizeError).isNull()
        assertThat(summaryRequests.single().map { it.content })
            .containsExactly("old question", "old answer", "summary prompt")
            .inOrder()
        assertThat(
            streamRequests.single().any {
                it.role == ChatRole.USER && it.content == "summary context: short summary"
            }
        ).isTrue()
        verify(atLeast = 1) { repository.clearCurrentConversation() }
        coVerify(exactly = 1) { client.chat(any(), false) }
        coVerify(exactly = 1) { client.chatStream(any(), any(), any(), any()) }
    }

    @Test
    fun `cancel tool updates current call and cascades pending calls`() = runTest {
        val assistantMessage = ChatMessage(
            id = "assistant-tools",
            role = ChatRole.ASSISTANT,
            content = "",
            toolCalls = listOf(
                ToolCall(
                    id = "tool-1",
                    type = "function",
                    function = ToolFunction(name = "first_tool", arguments = "{}"),
                ),
                ToolCall(
                    id = "tool-2",
                    type = "function",
                    function = ToolFunction(name = "second_tool", arguments = "{}"),
                ),
            ),
        )
        val conversation = ChatConversation(
            id = "conversation-tools",
            messages = listOf(assistantMessage),
        )
        val repository = mockConversationRepository(conversation)
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()
        viewModel.setApiClientForTest(newFakeApiClient(responseBody = streamResponse("after tools")))

        viewModel.cancelTool("tool-1", "user denied")

        val state = viewModel.awaitState { current ->
            val calls = current.messages.firstOrNull { it.id == "assistant-tools" }?.toolCalls
            calls?.getOrNull(0)?.executionStatus == ToolExecutionStatus.CANCELLED &&
                calls.getOrNull(1)?.executionStatus == ToolExecutionStatus.CANCELLED &&
                current.messages.any { it.role == ChatRole.ASSISTANT && it.content == "after tools" } &&
                !current.isLoading
        }
        val updatedCalls = state.messages.first { it.id == "assistant-tools" }.toolCalls!!
        assertThat(updatedCalls[0].executionError).isEqualTo("user denied")
        assertThat(updatedCalls[1].executionError).isEqualTo("Cancelled: previous tool was cancelled")

        coVerify(exactly = 1) {
            repository.addMessage(match { it.role == ChatRole.TOOL && it.toolCallId == "tool-1" })
        }
        coVerify(exactly = 1) {
            repository.addMessage(match { it.role == ChatRole.TOOL && it.toolCallId == "tool-2" })
        }
    }

    @Test
    fun `cancel tool with blank id reports localized error`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.setApiClientForTest(newFakeApiClient(responseBody = streamResponse("unused")))

        viewModel.cancelTool("", "no id")

        val state = viewModel.awaitState { it.error == "tool call id empty" }
        assertThat(state.messages).isEmpty()
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `execute tool without project context marks call failed and continues`() = runTest {
        val toolCall = ToolCall(
            id = "manual-tool",
            type = "function",
            function = ToolFunction(name = "manual_tool", arguments = "{}"),
        )
        val assistantMessage = ChatMessage(
            id = "assistant-manual-execute",
            role = ChatRole.ASSISTANT,
            content = "",
            toolCalls = listOf(toolCall),
        )
        val conversation = ChatConversation(
            id = "conversation-manual-execute",
            messages = listOf(assistantMessage),
        )
        val repository = mockConversationRepository(conversation)
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()
        viewModel.setApiClientForTest(
            newFakeApiClient(
                responseBody = streamResponse("continued after tool failure"),
                contentType = "text/event-stream",
            )
        )

        viewModel.executeTool(toolCall)

        val state = viewModel.awaitState { current ->
            val updatedCall = current.messages
                .firstOrNull { it.id == "assistant-manual-execute" }
                ?.toolCalls
                ?.singleOrNull()
            updatedCall?.executionStatus == ToolExecutionStatus.FAILED &&
                current.messages.any {
                    it.role == ChatRole.ASSISTANT && it.content == "continued after tool failure"
                } &&
                !current.isLoading
        }
        val updatedCall = state.messages
            .first { it.id == "assistant-manual-execute" }
            .toolCalls!!
            .single()
        assertThat(updatedCall.executionError).contains("tool context not initialized")

        coVerify(exactly = 1) {
            repository.updateMessage(
                match {
                    it.id == "assistant-manual-execute" &&
                        it.toolCalls?.singleOrNull()?.executionStatus == ToolExecutionStatus.FAILED
                }
            )
        }
        coVerify(exactly = 1) {
            repository.addMessage(
                match {
                    it.role == ChatRole.TOOL &&
                        it.toolCallId == "manual-tool" &&
                        it.content.contains("tool context not initialized")
                }
            )
        }
    }

    @Test
    fun autoModeExecutesSafeToolAndContinuesReply() = runTest {
        var executedCount = 0
        ToolRegistry.clear()
        ToolRegistry.register(
            fakeAiTool(name = "safe_lookup") { _, toolContext ->
                executedCount += 1
                assertThat(toolContext.projectRoot).isEqualTo("C:/project")
                ToolExecutionResult.Success("tool ok")
            }
        )
        val repository = mockConversationRepository(toolExecutionMode = ToolExecutionMode.AUTO)
        val client = mockk<AiApiClient>(relaxed = true)
        var streamCalls = 0
        coEvery { client.chatStream(any(), any(), any(), any()) } coAnswers {
            streamCalls += 1
            val onEvent = secondArg<(AiApiClient.ChatStreamEvent) -> Unit>()
            if (streamCalls == 1) {
                onEvent(
                    AiApiClient.ChatStreamEvent.ToolCallsUpdate(
                        listOf(
                            ToolCall(
                                id = "call-1",
                                type = "function",
                                function = ToolFunction(name = "safe_lookup", arguments = "{}"),
                            )
                        )
                    )
                )
                onEvent(AiApiClient.ChatStreamEvent.Done)
            } else {
                onEvent(AiApiClient.ChatStreamEvent.TextDelta("final answer"))
                onEvent(AiApiClient.ChatStreamEvent.Done)
            }
        }
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()
        viewModel.initializeProjectContext(projectRoot = "C:/project")
        viewModel.setApiClientForTest(client)

        viewModel.sendMessage("use tool")

        val state = viewModel.awaitState { current ->
            current.messages.any { it.role == ChatRole.ASSISTANT && it.content == "final answer" } &&
                !current.isLoading
        }
        val toolAssistant = state.messages.first { it.toolCalls?.isNotEmpty() == true }
        assertThat(executedCount).isEqualTo(1)
        assertThat(streamCalls).isEqualTo(2)
        assertThat(toolAssistant.toolCalls!!.single().executionStatus).isEqualTo(ToolExecutionStatus.SUCCESS)
        coVerify(exactly = 1) {
            repository.addMessage(
                match { it.role == ChatRole.TOOL && it.toolCallId == "call-1" && it.content.contains("tool ok") }
            )
        }
        coVerify(exactly = 2) { client.chatStream(any(), any(), any(), any()) }
    }

    @Test
    fun `manual mode completed tool result continues streaming reply`() = runTest {
        val assistantMessage = ChatMessage(
            id = "assistant-manual-tool",
            role = ChatRole.ASSISTANT,
            content = "",
            toolCalls = listOf(
                ToolCall(
                    id = "manual-tool",
                    type = "function",
                    function = ToolFunction(name = "manual_tool", arguments = "{}"),
                )
            ),
        )
        val conversation = ChatConversation(
            id = "conversation-manual-tool",
            messages = listOf(assistantMessage),
        )
        val repository = mockConversationRepository(
            currentConversation = conversation,
            toolExecutionMode = ToolExecutionMode.MANUAL,
        )
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()
        viewModel.setApiClientForTest(
            newFakeApiClient(
                responseBody = streamResponse("manual continued"),
                contentType = "text/event-stream",
            )
        )

        viewModel.cancelTool("manual-tool", "approved manually")

        val state = viewModel.awaitState { current ->
            current.messages.any { it.role == ChatRole.ASSISTANT && it.content == "manual continued" } &&
                !current.isLoading
        }
        val updatedCall = state.messages.first { it.id == "assistant-manual-tool" }.toolCalls!!.single()
        assertThat(updatedCall.executionStatus).isEqualTo(ToolExecutionStatus.CANCELLED)
        assertThat(updatedCall.executionError).isEqualTo("approved manually")

        coVerify(exactly = 1) {
            repository.addMessage(match { it.role == ChatRole.TOOL && it.toolCallId == "manual-tool" })
        }
    }

    @Test
    fun `auto mode refuses dangerous pending tool without auto allowance`() = runTest {
        var executedCount = 0
        ToolRegistry.clear()
        ToolRegistry.register(
            fakeAiTool(name = "dangerous_write", isDangerous = true) { _, _ ->
                executedCount += 1
                ToolExecutionResult.Success("should not run")
            }
        )
        val client = mockk<AiApiClient>(relaxed = true)
        coEvery { client.chatStream(any(), any(), any(), any()) } coAnswers {
            val onEvent = secondArg<(AiApiClient.ChatStreamEvent) -> Unit>()
            onEvent(
                AiApiClient.ChatStreamEvent.ToolCallsUpdate(
                    listOf(
                        ToolCall(
                            id = "danger-call",
                            type = "function",
                            function = ToolFunction(name = "dangerous_write", arguments = "{}"),
                        )
                    )
                )
            )
            onEvent(AiApiClient.ChatStreamEvent.Done)
        }
        val viewModel = newViewModel(conversationRepository = mockConversationRepository())
        advanceUntilIdle()
        viewModel.initializeProjectContext(projectRoot = "C:/project")
        viewModel.setApiClientForTest(client)

        viewModel.sendMessage("try dangerous tool")

        val state = viewModel.awaitState { current ->
            current.messages.any { it.toolCalls?.singleOrNull()?.id == "danger-call" } && !current.isLoading
        }
        val pendingCall = state.messages.first { it.toolCalls?.isNotEmpty() == true }.toolCalls!!.single()
        assertThat(pendingCall.executionStatus).isEqualTo(ToolExecutionStatus.PENDING)
        assertThat(executedCount).isEqualTo(0)
        coVerify(exactly = 1) { client.chatStream(any(), any(), any(), any()) }
    }

    @Test
    fun `retry last user message streams assistant reply`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val conversation = ChatConversation(
            id = "conversation-retry-user",
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "retry me")),
        )
        val viewModel = newViewModel(conversationRepository = mockConversationRepository(conversation))
        advanceUntilIdle()
        viewModel.setApiClientForTest(
            newFakeApiClient(
                capturedRequest = capturedRequest,
                responseBody = streamResponse("retry ok"),
                contentType = "text/event-stream",
            )
        )

        viewModel.retryLastMessage()

        val state = viewModel.awaitState { current ->
            current.messages.size == 2 &&
                current.messages.last().role == ChatRole.ASSISTANT &&
                current.messages.last().content == "retry ok" &&
                !current.isLoading
        }
        assertThat(state.messages.first().content).isEqualTo("retry me")
        val requestMessages = capturedRequest.get().bodyToJsonObject()["messages"]!!.jsonArray
        assertThat(requestMessages.single().jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("retry me")
    }

    @Test
    fun `retry assistant reply removes stale assistant before streaming`() = runTest {
        val staleAssistant = ChatMessage(
            id = "assistant-stale",
            role = ChatRole.ASSISTANT,
            content = "old answer",
        )
        val conversation = ChatConversation(
            id = "conversation-retry-assistant",
            messages = listOf(
                ChatMessage(role = ChatRole.USER, content = "question"),
                staleAssistant,
            ),
        )
        val repository = mockConversationRepository(conversation)
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()
        viewModel.setApiClientForTest(
            newFakeApiClient(
                responseBody = streamResponse("new answer"),
                contentType = "text/event-stream",
            )
        )

        viewModel.retryLastMessage()

        val state = viewModel.awaitState { current ->
            current.messages.size == 2 &&
                current.messages.last().role == ChatRole.ASSISTANT &&
                current.messages.last().content == "new answer" &&
                current.messages.none { it.id == "assistant-stale" } &&
                !current.isLoading
        }
        assertThat(state.messages.first().content).isEqualTo("question")
        coVerify(exactly = 1) { repository.deleteMessage("assistant-stale") }
    }

    @Test
    fun `retry assistant tool call cancels unexecuted tool before continuing`() = runTest {
        val assistantMessage = ChatMessage(
            id = "assistant-tool-retry",
            role = ChatRole.ASSISTANT,
            content = "",
            toolCalls = listOf(
                ToolCall(
                    id = "tool-done",
                    type = "function",
                    function = ToolFunction(name = "done_tool", arguments = "{}"),
                ),
                ToolCall(
                    id = "tool-pending",
                    type = "function",
                    function = ToolFunction(name = "pending_tool", arguments = "{}"),
                ),
            ),
        )
        val conversation = ChatConversation(
            id = "conversation-tool-retry",
            messages = listOf(
                ChatMessage(role = ChatRole.USER, content = "use tools"),
                assistantMessage,
                ChatMessage(role = ChatRole.TOOL, content = "ok", toolCallId = "tool-done"),
            ),
        )
        val repository = mockConversationRepository(conversation)
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()
        viewModel.setApiClientForTest(
            newFakeApiClient(
                responseBody = streamResponse("after retry tools"),
                contentType = "text/event-stream",
            )
        )

        viewModel.retryLastMessage()

        val state = viewModel.awaitState { current ->
            val updatedCalls = current.messages
                .firstOrNull { it.id == "assistant-tool-retry" }
                ?.toolCalls
            updatedCalls?.getOrNull(1)?.executionStatus == ToolExecutionStatus.CANCELLED &&
                current.messages.any { it.role == ChatRole.TOOL && it.toolCallId == "tool-pending" } &&
                current.messages.any { it.role == ChatRole.ASSISTANT && it.content == "after retry tools" } &&
                !current.isLoading
        }
        val updatedCalls = state.messages.first { it.id == "assistant-tool-retry" }.toolCalls!!
        assertThat(updatedCalls[0].executionStatus).isEqualTo(ToolExecutionStatus.PENDING)
        assertThat(updatedCalls[1].executionStatus).isEqualTo(ToolExecutionStatus.CANCELLED)

        coVerify(exactly = 1) {
            repository.updateMessage(match { it.id == "assistant-tool-retry" })
        }
        coVerify(exactly = 1) {
            repository.addMessage(match { it.role == ChatRole.TOOL && it.toolCallId == "tool-pending" })
        }
    }

    @Test
    fun `retry assistant tool call keeps completed tools and continues`() = runTest {
        val assistantMessage = ChatMessage(
            id = "assistant-tool-complete-retry",
            role = ChatRole.ASSISTANT,
            content = "",
            toolCalls = listOf(
                ToolCall(
                    id = "tool-done",
                    type = "function",
                    function = ToolFunction(name = "done_tool", arguments = "{}"),
                )
            ),
        )
        val conversation = ChatConversation(
            id = "conversation-tool-complete-retry",
            messages = listOf(
                ChatMessage(role = ChatRole.USER, content = "use completed tool"),
                assistantMessage,
                ChatMessage(role = ChatRole.TOOL, content = "ok", toolCallId = "tool-done"),
            ),
        )
        val repository = mockConversationRepository(conversation)
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()
        viewModel.setApiClientForTest(
            newFakeApiClient(
                responseBody = streamResponse("after completed tool retry"),
                contentType = "text/event-stream",
            )
        )

        viewModel.retryLastMessage()

        val state = viewModel.awaitState { current ->
            current.messages.any {
                it.role == ChatRole.ASSISTANT && it.content == "after completed tool retry"
            } &&
                !current.isLoading
        }
        assertThat(
            state.messages
                .first { it.id == "assistant-tool-complete-retry" }
                .toolCalls!!
                .single()
                .executionStatus
        ).isEqualTo(ToolExecutionStatus.PENDING)
        coVerify(exactly = 0) {
            repository.updateMessage(match { it.id == "assistant-tool-complete-retry" })
        }
    }

    @Test
    fun `retry empty conversation reports retry error`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.setApiClientForTest(newFakeApiClient(responseBody = streamResponse("unused")))

        viewModel.retryLastMessage()

        val state = viewModel.awaitState { it.error == "no messages to retry" }
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `retry conversation with only tool messages reports retry error`() = runTest {
        val conversation = ChatConversation(
            id = "conversation-tool-only",
            messages = listOf(
                ChatMessage(role = ChatRole.TOOL, content = "tool result", toolCallId = "tool-only"),
            ),
        )
        val viewModel = newViewModel(conversationRepository = mockConversationRepository(conversation))
        advanceUntilIdle()
        viewModel.setApiClientForTest(newFakeApiClient(responseBody = streamResponse("unused")))

        viewModel.retryLastMessage()

        val state = viewModel.awaitState { it.error == "no user or ai message to retry" }
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `stop generation clears loading and retry state without partial message`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.setApiClientForTest(newFakeApiClient(responseBody = streamResponse("unused")))

        viewModel.stopGeneration()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.isRetrying).isFalse()
        assertThat(state.retryAttempt).isEqualTo(0)
        assertThat(state.maxRetryAttempts).isEqualTo(0)
        assertThat(state.retryMessage).isNull()
        assertThat(state.retryError).isNull()
        assertThat(state.messages).isEmpty()
    }

    @Test
    fun stopGenerationSavesPartialStreamedAssistantMessage() = runTest {
        val repository = mockConversationRepository()
        val client = mockk<AiApiClient>(relaxed = true)
        val streamStarted = CompletableDeferred<Unit>()
        var onEvent: ((AiApiClient.ChatStreamEvent) -> Unit)? = null
        coEvery { client.chatStream(any(), any(), any(), any()) } coAnswers {
            onEvent = secondArg<(AiApiClient.ChatStreamEvent) -> Unit>()
            streamStarted.complete(Unit)
        }
        val viewModel = newViewModel(conversationRepository = repository)
        advanceUntilIdle()
        viewModel.setApiClientForTest(client)

        viewModel.sendMessage("hello")
        streamStarted.await()
        onEvent?.invoke(AiApiClient.ChatStreamEvent.TextDelta("partial answer"))
        onEvent?.invoke(AiApiClient.ChatStreamEvent.ReasoningDelta("reasoning"))
        viewModel.stopGeneration()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val assistant = state.messages.last()
        assertThat(state.isLoading).isFalse()
        assertThat(state.isRetrying).isFalse()
        assertThat(assistant.role).isEqualTo(ChatRole.ASSISTANT)
        assertThat(assistant.content).isEqualTo("partial answer\n\nstopped")
        assertThat(assistant.reasoningContent).isEqualTo("reasoning")
        assertThat(viewModel.streamingContent.value).isEmpty()
        verify(atLeast = 1) { client.cancelRequest() }
        coVerify(exactly = 1) {
            repository.addMessage(
                match { it.role == ChatRole.ASSISTANT && it.content == "partial answer\n\nstopped" }
            )
        }
    }

    @Test
    fun `clear pending auto tool execution only resets pending flag`() {
        val state = AiChatUiState(
            pendingAutoToolExecution = true,
            autoToolExecutionTrigger = 3,
        )

        val cleared = state.copy(pendingAutoToolExecution = false)

        assertThat(cleared.pendingAutoToolExecution).isFalse()
        assertThat(cleared.autoToolExecutionTrigger).isEqualTo(3)
    }

    @Test
    fun `ui state exposes current turn pending action state`() {
        val userMessage = ChatMessage(role = ChatRole.USER, content = "run tool")
        val assistantMessage = ChatMessage(role = ChatRole.ASSISTANT, content = "")
        val currentTurn = ConversationTurn(
            userMessage = userMessage,
            assistantResponses = listOf(
                AssistantResponse(
                    message = assistantMessage,
                    actions = listOf(
                        ToolAction(
                            name = "pending action",
                            status = ActionStatus.PENDING,
                        )
                    ),
                )
            ),
        )

        val state = AiChatUiState(turns = listOf(currentTurn))
        val emptyState = AiChatUiState()

        assertThat(state.getCurrentTurn()).isSameInstanceAs(currentTurn)
        assertThat(state.hasPendingActions()).isTrue()
        assertThat(emptyState.getCurrentTurn()).isNull()
        assertThat(emptyState.hasPendingActions()).isFalse()
    }

    private fun newViewModel(
        conversationRepository: ConversationRepository? = null,
        config: AiConfig = AiConfig(
            accessMode = AiAccessMode.CUSTOM_BYOK,
            activeChannelId = null,
            generation = AiGenerationSettings(model = "gpt-test"),
            prompt = AiPromptSettings(systemPrompt = "system prompt"),
        ),
        channelRepository: AiChannelRepository = mockk(relaxed = true),
    ): AiChatViewModel {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getString(any<Int>()) } answers { "string-${firstArg<Int>()}" }
        every { context.getString(any<Int>(), *anyVararg()) } answers { "string-${firstArg<Int>()}-formatted" }
        every { context.getString(R.string.ai_error_no_active_channel) } returns "no active channel"
        every { context.getString(R.string.ai_error_configure_api_key) } returns "configure api key"
        every { context.getString(R.string.ai_error_configure_settings) } returns "configure settings"
        every { context.getString(R.string.ai_error_image_empty) } returns "image empty"
        every { context.getString(R.string.ai_image_count, any()) } answers {
            "image count: ${secondArg<Array<*>>().firstOrNull()}"
        }
        every { context.getString(R.string.ai_error_not_ready) } returns "not ready"
        every { context.getString(R.string.ai_error_tool_call_id_empty) } returns "tool call id empty"
        every { context.getString(R.string.ai_summary_error_empty) } returns "summary empty"
        every { context.getString(R.string.ai_summary_error, any()) } answers {
            "summary error: ${secondArg<Array<*>>().firstOrNull()}"
        }
        every { context.getString(R.string.ai_summary_network_error, any()) } answers {
            "summary network error: ${secondArg<Array<*>>().firstOrNull()}"
        }
        every { context.getString(R.string.ai_summary_prompt) } returns "summary prompt"
        every { context.getString(R.string.ai_summary_context_message, any()) } answers {
            "summary context: ${secondArg<Array<*>>().firstOrNull()}"
        }
        every { context.getString(R.string.ai_error_retry_no_messages) } returns "no messages to retry"
        every { context.getString(R.string.ai_error_retry_no_user_or_ai_message) } returns "no user or ai message to retry"
        every { context.getString(R.string.ai_error_stream_unparseable) } returns "stream unparseable"
        every { context.getString(R.string.ai_error_suggest_check_logs) } returns "check logs"
        every { context.getString(R.string.ai_error_suggestion_prefix) } returns "hint: "
        every { context.getString(R.string.ai_generation_stopped) } returns "stopped"
        every { context.getString(R.string.ai_tool_execution_cancelled) } returns "tool execution cancelled"
        every { context.getString(R.string.ai_tool_error_context_not_initialized) } returns "tool context not initialized"
        every { context.getString(R.string.ai_tool_cancelled_previous_incomplete) } returns "previous incomplete"
        every { context.getString(R.string.ai_tool_cancelled_previous_failed) } returns "Cancelled: previous tool failed"
        every { context.getString(R.string.ai_tool_cancelled_previous_cancelled) } returns "Cancelled: previous tool was cancelled"

        val configFlow = MutableStateFlow(config)
        val preferences = mockk<AiPreferences>()
        every { preferences.configFlow } returns configFlow

        resetAppStrings()
        AppStrings.initialize(context)

        return AiChatViewModel(
            context = context,
            aiPreferences = preferences,
            channelRepository = channelRepository,
            conversationRepository = conversationRepository,
        )
    }

    private fun mockConversationRepository(
        currentConversation: ChatConversation? = null,
        toolExecutionMode: ToolExecutionMode = ToolExecutionMode.AUTO,
    ): ConversationRepository {
        val repository = mockk<ConversationRepository>(relaxed = true)
        every { repository.conversationList } returns flowOf(emptyList())
        every { repository.currentConversation } returns flowOf(currentConversation)
        every { repository.currentToolExecutionMode } returns flowOf(toolExecutionMode)
        every { repository.clearCurrentConversation() } returns Unit
        coEvery { repository.updateToolExecutionMode(any()) } returns Unit
        coEvery { repository.updateMessage(any()) } returns currentConversation
        coEvery { repository.addMessage(any()) } returns currentConversation
        coEvery { repository.createConversation(any(), any()) } returns ChatConversation(
            id = "created-conversation",
            title = "created",
            messages = emptyList(),
        )
        return repository
    }

    private fun AiChatViewModel.setApiClientForTest(client: AiApiClient) {
        val field = AiChatViewModel::class.java.getDeclaredField("apiClient")
        field.isAccessible = true
        field.set(this, client)
    }

    private fun AiChatViewModel.toolContextForTest(): ToolExecutionContext {
        val field = AiChatViewModel::class.java.getDeclaredField("toolExecutionContext")
        field.isAccessible = true
        return field.get(this) as ToolExecutionContext
    }

    private fun AiChatViewModel.clearConfigForTest() {
        val field = AiChatViewModel::class.java.getDeclaredField("_config")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val config = field.get(this) as MutableStateFlow<AiConfig?>
        config.value = null
    }

    private fun AiChatViewModel.awaitState(
        predicate: (AiChatUiState) -> Boolean,
    ): AiChatUiState {
        val deadline = System.nanoTime() + 3_000_000_000L
        var latest = uiState.value
        while (System.nanoTime() < deadline) {
            latest = uiState.value
            if (predicate(latest)) return latest
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for AiChatUiState, latest=$latest")
    }

    private fun newFakeApiClient(
        capturedRequest: AtomicReference<Request> = AtomicReference(),
        responseCode: Int = 200,
        responseMessage: String = "OK",
        responseBody: String,
        contentType: String = "application/json",
    ): AiApiClient {
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    capturedRequest.set(request)
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(responseCode)
                        .message(responseMessage)
                        .body(responseBody.toResponseBody(contentType.toMediaType()))
                        .build()
                }
            )
            .build()

        return AiApiClient(
            config = AiConfig(
                generation = AiGenerationSettings(model = "gpt-test"),
            ),
            endpoint = "https://example.test/v1/",
            auth = AuthStrategy.Bearer("secret"),
            client = okHttp,
        )
    }

    private fun chatResponse(content: String): String = """
        {
          "id":"summary-response",
          "choices":[
            {
              "index":0,
              "message":{"role":"assistant","content":"$content"},
              "finish_reason":"stop"
            }
          ],
          "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}
        }
    """.trimIndent()

    private fun chatResponseModel(content: String) = ChatResponse(
        id = "summary-response",
        choices = listOf(
            ChatChoice(
                index = 0,
                message = ChatResponseMessage(role = "assistant", content = content),
                finishReason = "stop",
            )
        ),
        usage = null,
    )

    private fun streamResponse(content: String): String = """
        data: {"id":"stream-response","choices":[{"index":0,"delta":{"content":"$content"},"finish_reason":null}]}

        data: [DONE]

    """.trimIndent()

    private fun fakeAiTool(
        name: String,
        isDangerous: Boolean = false,
        onExecute: suspend (ToolCall, ToolExecutionContext) -> ToolExecutionResult,
    ): AiTool = object : AiTool {
        override val name: String = name
        override val description: String = "for tests"
        override val category: ToolCategory = ToolCategory.CUSTOM
        override val isDangerous: Boolean = isDangerous
        override fun getParameters() = JsonObject(emptyMap())
        override suspend fun execute(
            toolCall: ToolCall,
            context: ToolExecutionContext,
        ): ToolExecutionResult = onExecute(toolCall, context)
    }

    private fun Request.bodyToJsonObject() = Json.parseToJsonElement(
        Buffer().also { buffer -> body!!.writeTo(buffer) }.readUtf8()
    ).jsonObject

    private fun resetAppStrings() {
        val field = AppStrings::class.java.getDeclaredField("appContext")
        field.isAccessible = true
        field.set(AppStrings, null)
    }

}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: kotlinx.coroutines.test.TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
