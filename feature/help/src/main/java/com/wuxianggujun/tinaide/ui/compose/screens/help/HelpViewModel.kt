package com.wuxianggujun.tinaide.ui.compose.screens.help

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.help.HelpCategory
import com.wuxianggujun.tinaide.core.help.HelpDocument
import com.wuxianggujun.tinaide.core.help.HelpRepository
import com.wuxianggujun.tinaide.core.help.HelpSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 帮助中心 ViewModel
 * 管理帮助文档的加载、搜索和显示状态
 */
class HelpViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: HelpRepository = HelpRepository(application),
) : AndroidViewModel(application) {

    private var contentLoadGeneration = 0L
    private var searchGeneration = 0L

    // UI 状态
    private val _uiState = MutableStateFlow(HelpUiState())
    val uiState: StateFlow<HelpUiState> = _uiState.asStateFlow()

    // 当前文档内容
    private val _documentContent = MutableStateFlow<String?>(null)
    val documentContent: StateFlow<String?> = _documentContent.asStateFlow()

    // 搜索结果
    private val _searchResults = MutableStateFlow<List<HelpSearchResult>>(emptyList())
    val searchResults: StateFlow<List<HelpSearchResult>> = _searchResults.asStateFlow()

    init {
        loadDocuments()
    }

    /**
     * 加载所有文档
     */
    private fun loadDocuments() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val documents = repository.getAllDocuments()
            val categories = repository.getAllCategories()

            // 调试日志
            Timber.tag("HelpViewModel").d("Loaded documents: %d", documents.size)
            Timber.tag("HelpViewModel").d("Loaded categories: %d", categories.size)

            // 按分类分组
            val documentsByCategory = categories.associateWith { category ->
                val docs = repository.getDocumentsByCategory(category)
                Timber.tag("HelpViewModel").d(
                    "Category %s contains %d documents",
                    getApplication<Application>().getString(category.displayNameRes),
                    docs.size
                )
                docs
            }.filterValues { it.isNotEmpty() }

            Timber.tag("HelpViewModel").d("Non-empty categories: %d", documentsByCategory.size)

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                documents = documents,
                categories = categories,
                documentsByCategory = documentsByCategory
            )

            // 后台预加载文档内容（用于搜索）
            repository.preloadAllContent()
        }
    }

    /**
     * 选择文档
     */
    fun selectDocument(document: HelpDocument) {
        selectDocumentInternal(document)
    }

    /**
     * 根据文档 ID 选择文档。
     */
    fun selectDocumentById(documentId: String) {
        val document = repository.getDocumentById(documentId) ?: return
        selectDocumentInternal(document)
    }

    /**
     * 根据 Markdown 链接目标打开站内帮助文档。
     *
     * @return `true` 表示已按站内文档处理。
     */
    fun openDocumentByLinkTarget(linkTarget: String): Boolean {
        val document = repository.resolveDocumentByLinkTarget(linkTarget) ?: return false
        selectDocumentInternal(document)
        return true
    }

    private fun selectDocumentInternal(document: HelpDocument) {
        val loadGeneration = ++contentLoadGeneration
        _documentContent.value = null
        _uiState.value = _uiState.value.copy(
            selectedDocument = document,
            isLoadingContent = true,
            error = null,
        )
        viewModelScope.launch {
            val result = repository.loadDocumentContent(document)
            result.onSuccess { content ->
                if (!isCurrentContentLoad(document, loadGeneration)) return@onSuccess
                if (content.isBlank()) {
                    _documentContent.value = null
                    _uiState.value = _uiState.value.copy(
                        isLoadingContent = false,
                        error = "Help document content is blank",
                    )
                    return@onSuccess
                }
                _documentContent.value = content
                _uiState.value = _uiState.value.copy(isLoadingContent = false)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (!isCurrentContentLoad(document, loadGeneration)) return@onFailure
                _documentContent.value = null
                _uiState.value = _uiState.value.copy(
                    isLoadingContent = false,
                    error = error.message ?: "Unable to load help document",
                )
            }
        }
    }

    /**
     * Retry the currently selected document after a transient asset/load failure.
     */
    fun retrySelectedDocument() {
        _uiState.value.selectedDocument?.let(::selectDocumentInternal)
    }

    private fun isCurrentContentLoad(document: HelpDocument, generation: Long): Boolean =
        generation == contentLoadGeneration && _uiState.value.selectedDocument?.id == document.id

    /**
     * 清除选中的文档（返回列表）
     */
    fun clearSelectedDocument() {
        contentLoadGeneration++
        _documentContent.value = null
        _uiState.value = _uiState.value.copy(
            selectedDocument = null,
            error = null,
            isLoadingContent = false,
        )
    }

    /**
     * 搜索文档
     */
    fun search(query: String) {
        val generation = ++searchGeneration
        _uiState.value = _uiState.value.copy(searchQuery = query)

        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _uiState.value = _uiState.value.copy(isSearching = false)
            return
        }

        _searchResults.value = emptyList()
        viewModelScope.launch {
            if (!isCurrentSearch(query, generation)) return@launch
            _uiState.value = _uiState.value.copy(isSearching = true)
            val results = repository.search(query)
            if (!isCurrentSearch(query, generation)) return@launch
            _searchResults.value = results
            _uiState.value = _uiState.value.copy(isSearching = false)
        }
    }

    /**
     * 清除搜索
     */
    fun clearSearch() {
        searchGeneration++
        _uiState.value = _uiState.value.copy(searchQuery = "", isSearching = false)
        _searchResults.value = emptyList()
    }

    private fun isCurrentSearch(query: String, generation: Long): Boolean =
        generation == searchGeneration && _uiState.value.searchQuery == query

    /**
     * 清除错误
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

/**
 * 帮助中心 UI 状态
 */
data class HelpUiState(
    val isLoading: Boolean = false,
    val isLoadingContent: Boolean = false,
    val isSearching: Boolean = false,
    val documents: List<HelpDocument> = emptyList(),
    val categories: List<HelpCategory> = emptyList(),
    val documentsByCategory: Map<HelpCategory, List<HelpDocument>> = emptyMap(),
    val selectedDocument: HelpDocument? = null,
    val searchQuery: String = "",
    val error: String? = null
)
