package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult
import com.wuxianggujun.tinaide.core.textengine.Position
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Hover 与 SignatureHelp 的 UI 状态机控制器。
 *
 * 从 [EditorState] 抽出的内聚块：维护 hover / signatureHelp 两套“requestId 防竞态 + UiState publish”
 * 状态机，以及 signatureHelp 的选中序号。两块彼此独立，只共享 cursorPosition（经 [Host] 注入），
 * 与补全 / snippet 无耦合，因此整体抽出最干净。
 *
 * 该块依赖宿主的少量只读量与回调（光标位置、两个 provider 回调），通过 [Host] 接口注入，
 * 组合而非继承（D 依赖倒置）。
 *
 * 行为与原内联实现严格一致：requestId 自增/比对、异常分支清理、normalize* 归一化时机均逐行对应原
 * [EditorState] 实现。
 *
 * 可观察性说明：
 * - [hoverUiState] / [signatureHelpUiState] / [signatureHelpSelectedSignatureIndex] 仍由
 *   `mutableStateOf` 承载。Compose 快照按 StateObject 身份记录读取，与持有它的对象类无关，因此
 *   [EditorState] 暴露的 get-only 委托属性（及模块内 9+ 文件经 state.x 的直接读取）仍会触发重组。
 */
internal class EditorHoverSignatureController(
    private val host: Host
) {
    /**
     * 宿主只读视图与回调。读取宿主当前状态；通过挂起回调请求 hover / signatureHelp 数据。
     */
    internal interface Host {
        /** 当前光标位置（requestSignatureHelp 与 requestHover 默认参数用）。 */
        val cursorPosition: Position

        /** hover 数据 provider（null 表示未接线，请求直接忽略）。 */
        val onRequestHover: (suspend (Position) -> String?)?

        /** signatureHelp 数据 provider（null 表示未接线，请求直接忽略）。 */
        val onRequestSignatureHelp: (suspend (Position) -> SignatureHelpResult?)?
    }

    // ========== Hover ==========
    var hoverUiState by mutableStateOf<HoverUiState>(HoverUiState.Hidden)
        private set

    private var hoverRequestSeq: Long = 0L
    private var activeHoverRequestId: Long = 0L

    // ========== SignatureHelp ==========
    var signatureHelpUiState by mutableStateOf<SignatureHelpUiState>(SignatureHelpUiState.Hidden)
        private set
    var signatureHelpSelectedSignatureIndex by mutableStateOf<Int?>(null)
        private set

    private var signatureHelpRequestSeq: Long = 0L
    private var activeSignatureHelpRequestId: Long = 0L

    // ---- Hover publish ----

    fun publishHoverLoading() {
        hoverUiState = HoverUiState.Loading
    }

    fun publishHoverVisible(markdown: String) {
        val normalizedMarkdown = markdown.trim()
        if (normalizedMarkdown.isEmpty()) {
            clearHover()
            return
        }
        hoverUiState = HoverUiState.Visible(normalizedMarkdown)
    }

    private fun clearHover() {
        hoverUiState = HoverUiState.Hidden
    }

    // ---- SignatureHelp publish ----

    fun publishSignatureHelpLoading(
        previousResult: SignatureHelpResult?,
        requestId: Long,
        selectedIndex: Int? = signatureHelpSelectedSignatureIndex
    ) {
        signatureHelpSelectedSignatureIndex =
            normalizeSignatureHelpSelection(previousResult, selectedIndex)
        signatureHelpUiState = SignatureHelpUiState.Loading(
            previousResult = previousResult,
            requestId = requestId
        )
    }

    fun publishSignatureHelpVisible(
        result: SignatureHelpResult,
        requestId: Long,
        selectedIndex: Int? = signatureHelpSelectedSignatureIndex
    ) {
        val normalized = normalizeSignatureHelpResult(result)
        if (normalized == null) {
            clearSignatureHelp()
            return
        }
        signatureHelpSelectedSignatureIndex =
            normalizeSignatureHelpSelection(normalized, selectedIndex)
        signatureHelpUiState = SignatureHelpUiState.Visible(
            result = normalized,
            requestId = requestId
        )
    }

    private fun publishSignatureHelpSelection(index: Int) {
        signatureHelpSelectedSignatureIndex =
            normalizeSignatureHelpSelection(currentSignatureHelpResult(), index)
    }

    private fun clearSignatureHelp() {
        signatureHelpSelectedSignatureIndex = null
        signatureHelpUiState = SignatureHelpUiState.Hidden
    }

    // ---- Hover request / dismiss ----

    suspend fun requestHover(position: Position = host.cursorPosition) {
        val provider = host.onRequestHover ?: return
        val requestId = ++hoverRequestSeq
        activeHoverRequestId = requestId
        publishHoverLoading()
        try {
            val markdown = provider(position)?.trim()
            if (requestId != activeHoverRequestId) return
            if (markdown.isNullOrBlank()) {
                activeHoverRequestId = 0L
                clearHover()
            } else {
                publishHoverVisible(markdown)
            }
        } catch (cancellation: CancellationException) {
            if (requestId == activeHoverRequestId) {
                activeHoverRequestId = 0L
                clearHover()
            }
            throw cancellation
        } catch (error: Exception) {
            Timber.tag("EditorHoverSignature").w(error, "Hover request failed")
            if (requestId == activeHoverRequestId) {
                activeHoverRequestId = 0L
                clearHover()
            }
        }
    }

    fun dismissHover() {
        activeHoverRequestId = 0L
        clearHover()
    }

    // ---- SignatureHelp request / dismiss ----

    suspend fun requestSignatureHelp() {
        val provider = host.onRequestSignatureHelp ?: return
        val requestId = ++signatureHelpRequestSeq
        activeSignatureHelpRequestId = requestId
        val previousResult = when (val ui = signatureHelpUiState) {
            is SignatureHelpUiState.Visible -> ui.result
            is SignatureHelpUiState.Loading -> ui.previousResult
            SignatureHelpUiState.Hidden -> null
        }
        publishSignatureHelpLoading(
            previousResult = previousResult,
            requestId = requestId
        )
        try {
            val result = provider(host.cursorPosition)
            if (requestId != activeSignatureHelpRequestId) return
            val normalized = normalizeSignatureHelpResult(result)
            if (normalized == null) {
                activeSignatureHelpRequestId = 0L
                clearSignatureHelp()
            } else {
                publishSignatureHelpVisible(
                    result = normalized,
                    requestId = requestId
                )
            }
        } catch (cancellation: CancellationException) {
            if (requestId == activeSignatureHelpRequestId) {
                activeSignatureHelpRequestId = 0L
                clearSignatureHelp()
            }
            throw cancellation
        } catch (error: Exception) {
            Timber.tag("EditorHoverSignature").w(error, "Signature help request failed")
            if (requestId == activeSignatureHelpRequestId) {
                activeSignatureHelpRequestId = 0L
                clearSignatureHelp()
            }
        }
    }

    fun dismissSignatureHelp() {
        activeSignatureHelpRequestId = 0L
        clearSignatureHelp()
    }

    // ---- SignatureHelp selection ----

    fun resolveDisplayedSignatureHelpIndex(
        result: SignatureHelpResult?
    ): Int {
        val resolvedResult = result ?: return 0
        val fallback = resolvedResult.activeSignature.coerceIn(0, resolvedResult.signatures.lastIndex)
        return signatureHelpSelectedSignatureIndex?.coerceIn(0, resolvedResult.signatures.lastIndex)
            ?: fallback
    }

    fun selectSignatureHelp(index: Int): Boolean {
        val result = currentSignatureHelpResult() ?: return false
        if (index !in result.signatures.indices) return false
        publishSignatureHelpSelection(index)
        return true
    }

    fun cycleSignatureHelp(delta: Int): Boolean {
        val result = currentSignatureHelpResult() ?: return false
        if (delta == 0 || result.signatures.size <= 1) return false
        val currentIndex = resolveDisplayedSignatureHelpIndex(result)
        val size = result.signatures.size
        val nextIndex = ((currentIndex + delta) % size + size) % size
        publishSignatureHelpSelection(nextIndex)
        return true
    }

    private fun currentSignatureHelpResult(): SignatureHelpResult? = when (val ui = signatureHelpUiState) {
        is SignatureHelpUiState.Visible -> ui.result
        is SignatureHelpUiState.Loading -> ui.previousResult
        SignatureHelpUiState.Hidden -> null
    }
}
