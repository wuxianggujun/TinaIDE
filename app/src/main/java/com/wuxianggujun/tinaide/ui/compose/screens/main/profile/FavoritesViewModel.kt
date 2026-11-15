package com.wuxianggujun.tinaide.ui.compose.screens.main.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.user.FavoritePlugin
import com.wuxianggujun.tinaide.core.user.UserContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 收藏界面状态
 */
data class FavoritesUiState(
    val favorites: List<FavoritePlugin> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
)

/**
 * 收藏界面 ViewModel
 *
 * 收藏数据只来自本地 Room 数据库，不再与任何服务器同步。
 */
class FavoritesViewModel(
    private val repository: UserContentRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        loadFavorites()
    }

    fun loadFavorites(refresh: Boolean = false) {
        viewModelScope.launch {
            if (refresh) {
                _uiState.value = FavoritesUiState(isLoading = true)
            } else {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            }

            val page = if (refresh) 1 else _uiState.value.currentPage

            repository.getFavorites(page = page, pageSize = 20).fold(
                onSuccess = { response ->
                    val newFavorites = if (refresh) {
                        response.plugins
                    } else {
                        _uiState.value.favorites + response.plugins
                    }

                    val hasMore = page * response.pageSize < response.total

                    _uiState.value = FavoritesUiState(
                        favorites = newFavorites,
                        isLoading = false,
                        hasMore = hasMore,
                        currentPage = page
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: Strings.profile_load_failed.str()
                    )
                }
            )
        }
    }

    fun loadMore() {
        if (_uiState.value.isLoading || !_uiState.value.hasMore) return
        _uiState.value = _uiState.value.copy(currentPage = _uiState.value.currentPage + 1)
        loadFavorites()
    }

    fun removeFavorite(pluginId: String) {
        viewModelScope.launch {
            repository.removeFavorite(pluginId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        favorites = _uiState.value.favorites.filter { it.pluginId != pluginId }
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        error = error.message ?: Strings.market_unfavorite_failed.str()
                    )
                }
            )
        }
    }

    fun refresh() {
        loadFavorites(refresh = true)
    }
}
