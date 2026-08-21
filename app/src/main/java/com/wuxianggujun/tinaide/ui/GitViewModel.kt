package com.wuxianggujun.tinaide.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.git.GitBranch
import com.wuxianggujun.tinaide.core.git.GitCommit
import com.wuxianggujun.tinaide.core.git.GitConflictKind
import com.wuxianggujun.tinaide.core.git.GitRemote
import com.wuxianggujun.tinaide.core.git.GitResult
import com.wuxianggujun.tinaide.core.git.GitService
import com.wuxianggujun.tinaide.core.git.GitStatus
import com.wuxianggujun.tinaide.core.git.ssh.TINA_GIT_SSH_PASSPHRASE_MARKER
import com.wuxianggujun.tinaide.core.git.ssh.parseGitSshPassphraseRequired
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Git ViewModel
 */
class GitViewModel(
    private val gitService: GitService
) : ViewModel() {

    companion object {
        private const val TAG = "GitViewModel"
        private const val RECENT_COMMIT_MESSAGE_LIMIT = 10
    }

    private val _status = MutableStateFlow(GitStatus.NOT_A_REPOSITORY)
    val status: StateFlow<GitStatus> = _status.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _commitError = MutableStateFlow<String?>(null)
    val commitError: StateFlow<String?> = _commitError.asStateFlow()

    private val _isCommitting = MutableStateFlow(false)
    val isCommitting: StateFlow<Boolean> = _isCommitting.asStateFlow()

    // 提交历史
    private val _commitHistory = MutableStateFlow<List<GitCommit>>(emptyList())
    val commitHistory: StateFlow<List<GitCommit>> = _commitHistory.asStateFlow()

    private val _recentCommitMessages = MutableStateFlow<List<String>>(emptyList())
    val recentCommitMessages: StateFlow<List<String>> = _recentCommitMessages.asStateFlow()

    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()

    // 分支列表
    private val _branches = MutableStateFlow<List<GitBranch>>(emptyList())
    val branches: StateFlow<List<GitBranch>> = _branches.asStateFlow()

    // Diff 相关状态
    private val _diffContent = MutableStateFlow<String?>(null)
    val diffContent: StateFlow<String?> = _diffContent.asStateFlow()

    private val _diffFilePath = MutableStateFlow<String?>(null)
    val diffFilePath: StateFlow<String?> = _diffFilePath.asStateFlow()

    private val _diffIsStaged = MutableStateFlow(false)
    val diffIsStaged: StateFlow<Boolean> = _diffIsStaged.asStateFlow()

    private val _isLoadingDiff = MutableStateFlow(false)
    val isLoadingDiff: StateFlow<Boolean> = _isLoadingDiff.asStateFlow()

    private val _diffError = MutableStateFlow<String?>(null)
    val diffError: StateFlow<String?> = _diffError.asStateFlow()

    // 远程仓库
    private val _remotes = MutableStateFlow<List<GitRemote>>(emptyList())
    val remotes: StateFlow<List<GitRemote>> = _remotes.asStateFlow()

    private val _isLoadingRemotes = MutableStateFlow(false)
    val isLoadingRemotes: StateFlow<Boolean> = _isLoadingRemotes.asStateFlow()

    private val _remoteError = MutableStateFlow<String?>(null)
    val remoteError: StateFlow<String?> = _remoteError.asStateFlow()

    // 远程同步输出（fetch/pull/push）
    private val _syncOutput = MutableStateFlow<String?>(null)
    val syncOutput: StateFlow<String?> = _syncOutput.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _mergeConflicts = MutableStateFlow<List<String>>(emptyList())
    val mergeConflicts: StateFlow<List<String>> = _mergeConflicts.asStateFlow()

    private val _conflictKind = MutableStateFlow(GitConflictKind.NONE)
    val conflictKind: StateFlow<GitConflictKind> = _conflictKind.asStateFlow()

    private val _isResolvingConflicts = MutableStateFlow(false)
    val isResolvingConflicts: StateFlow<Boolean> = _isResolvingConflicts.asStateFlow()

    private val _conflictError = MutableStateFlow<String?>(null)
    val conflictError: StateFlow<String?> = _conflictError.asStateFlow()

    private val _sshPassphraseRequest = MutableStateFlow<SshPassphraseRequest?>(null)
    internal val sshPassphraseRequest: StateFlow<SshPassphraseRequest?> = _sshPassphraseRequest.asStateFlow()

    private var projectPath: String? = null

    internal sealed interface PendingRemoteOp {
        data class Fetch(val remote: String, val branch: String?, val prune: Boolean) : PendingRemoteOp
        data class Pull(val remote: String, val branch: String?, val rebase: Boolean) : PendingRemoteOp
        data class Push(
            val remote: String,
            val branch: String?,
            val setUpstream: Boolean,
            val force: Boolean,
            val tags: Boolean
        ) : PendingRemoteOp
    }

    internal data class SshPassphraseRequest(
        val keyName: String,
        val host: String?,
        val pendingOp: PendingRemoteOp,
        val error: String? = null,
    )

    // ========== 通用 Git 操作工具函数 ==========

    /**
     * 通用 Git 操作启动器，统一处理 loading / error 状态切换。
     *
     * 在 IO 线程执行 [op]，成功后在主线程调用 [onSuccess]，失败时写入 [errorState]。
     * [loadingState] 可选，提供时会在操作前后自动置为 true/false。
     */
    private fun rethrowIfCancellation(t: Throwable) {
        if (t is CancellationException) throw t
    }

    private fun gitThrowableMessage(t: Throwable, fallbackMessage: String): String = t.message?.trim()?.takeIf { it.isNotEmpty() }
        ?: t.cause?.message?.trim()?.takeIf { it.isNotEmpty() }
        ?: fallbackMessage

    private suspend fun <T> safeGitCall(
        fallbackMessage: String,
        op: suspend () -> GitResult<T>
    ): GitResult<T> = try {
        op()
    } catch (t: Throwable) {
        rethrowIfCancellation(t)
        Timber.tag(TAG).e(t, "Unexpected Git failure")
        GitResult.Error(gitThrowableMessage(t, fallbackMessage))
    }

    private fun <T> launchGitOp(
        loadingState: MutableStateFlow<Boolean>? = null,
        errorState: MutableStateFlow<String?> = _error,
        fallbackMessage: String = Strings.git_error_load_failed.str(),
        op: suspend () -> GitResult<T>,
        onSuccess: suspend (T) -> Unit = {}
    ) {
        viewModelScope.launch {
            loadingState?.value = true
            errorState.value = null
            when (val result = withContext(Dispatchers.IO) { safeGitCall(fallbackMessage, op) }) {
                is GitResult.Success -> onSuccess(result.data)
                is GitResult.Error -> errorState.value = result.message
            }
            loadingState?.value = false
        }
    }

    private fun updateCommitHistory(commits: List<GitCommit>) {
        _commitHistory.value = commits
        _recentCommitMessages.value = commits
            .map { commit -> commit.fullMessage.ifBlank { commit.message }.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(RECENT_COMMIT_MESSAGE_LIMIT)
    }

    // ========== 项目路径 ==========

    /**
     * 设置项目路径
     */
    fun setProjectPath(path: String) {
        projectPath = path
        refresh()
        loadCommitHistory()
        loadBranches()
        loadRemotes()
    }

    /**
     * 刷新状态
     */
    fun refresh() {
        val path = projectPath ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                Timber.tag(TAG).d("Refreshing Git status: %s", path)
                when (
                    val result = withContext(Dispatchers.IO) {
                        safeGitCall(Strings.git_error_load_failed.str()) {
                            GitResult.Success(gitService.getStatus(path))
                        }
                    }
                ) {
                    is GitResult.Success -> {
                        val newStatus = result.data
                        Timber.tag(TAG).d(
                            "Git status refreshed - isRepository: %s, branch: %s",
                            newStatus.isRepository,
                            newStatus.branch
                        )
                        _status.value = newStatus
                    }

                    is GitResult.Error -> {
                        _error.value = result.message
                    }
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 加载提交历史
     */
    fun loadCommitHistory(maxCount: Int = 50) {
        val path = projectPath ?: return
        launchGitOp(
            loadingState = _isLoadingHistory,
            fallbackMessage = Strings.git_error_get_history_failed.str(),
            op = { gitService.getCommitHistory(path, maxCount) },
            onSuccess = { updateCommitHistory(it) }
        )
    }

    fun clearRecentCommitMessages() {
        _recentCommitMessages.value = emptyList()
    }

    /**
     * 加载分支列表
     */
    fun loadBranches() {
        val path = projectPath ?: return
        viewModelScope.launch {
            when (
                val result = withContext(Dispatchers.IO) {
                    safeGitCall(Strings.git_error_get_branches_failed.str()) {
                        gitService.getBranches(path)
                    }
                }
            ) {
                is GitResult.Success -> _branches.value = result.data
                is GitResult.Error -> { /* 忽略分支加载错误 */ }
            }
        }
    }

    /**
     * 切换分支
     */
    fun checkout(branch: String, onSuccess: () -> Unit) {
        val path = projectPath ?: return
        launchGitOp(
            loadingState = _isLoading,
            fallbackMessage = Strings.git_error_checkout_failed.str(),
            op = { gitService.checkout(path, branch) },
            onSuccess = {
                refresh()
                loadCommitHistory()
                loadBranches()
                onSuccess()
            }
        )
    }

    /**
     * 暂存文件
     */
    fun stageFile(filePath: String) {
        val path = projectPath ?: return
        launchGitOp(
            fallbackMessage = Strings.git_error_stage_failed.str(),
            op = { gitService.stageFile(path, filePath) },
            onSuccess = { refresh() }
        )
    }

    /**
     * 取消暂存文件
     */
    fun unstageFile(filePath: String) {
        val path = projectPath ?: return
        launchGitOp(
            fallbackMessage = Strings.git_error_unstage_failed.str(),
            op = { gitService.unstageFile(path, filePath) },
            onSuccess = { refresh() }
        )
    }

    /**
     * 暂存所有更改
     */
    fun stageAll() {
        val path = projectPath ?: return
        launchGitOp(
            fallbackMessage = Strings.git_error_stage_failed.str(),
            op = { gitService.stageAll(path) },
            onSuccess = { refresh() }
        )
    }

    /**
     * 取消暂存所有文件
     */
    fun unstageAll() {
        val path = projectPath ?: return
        launchGitOp(
            fallbackMessage = Strings.git_error_unstage_failed.str(),
            op = { gitService.unstageAll(path) },
            onSuccess = { refresh() }
        )
    }

    /**
     * 放弃文件更改
     */
    suspend fun discardChanges(filePath: String) {
        val path = projectPath ?: return
        when (
            val result = withContext(Dispatchers.IO) {
                safeGitCall(Strings.git_error_discard_failed.str()) {
                    gitService.discardChanges(path, filePath)
                }
            }
        ) {
            is GitResult.Success -> refresh()
            is GitResult.Error -> _error.value = result.message
        }
    }

    /**
     * 提交
     */
    fun commit(message: String, onSuccess: () -> Unit) {
        val path = projectPath ?: return
        launchGitOp(
            loadingState = _isCommitting,
            errorState = _commitError,
            fallbackMessage = Strings.git_error_commit_failed.str(),
            op = { gitService.commit(path, message) },
            onSuccess = {
                refresh()
                loadCommitHistory()
                onSuccess()
            }
        )
    }

    /**
     * 初始化仓库
     */
    fun initRepository(onSuccess: () -> Unit) {
        val path = projectPath ?: return
        Timber.tag(TAG).d("Initializing Git repository: %s", path)
        launchGitOp(
            loadingState = _isLoading,
            fallbackMessage = Strings.git_error_init_failed.str(),
            op = { gitService.init(path) },
            onSuccess = {
                Timber.tag(TAG).d("Git repository initialized, refreshing status")
                refresh()
                onSuccess()
            }
        )
    }

    /**
     * 清除错误
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * 清除提交错误
     */
    fun clearCommitError() {
        _commitError.value = null
    }

    /**
     * 加载文件 Diff
     */
    fun loadDiff(filePath: String, staged: Boolean) {
        val path = projectPath ?: return

        viewModelScope.launch {
            _isLoadingDiff.value = true
            _diffError.value = null
            _diffFilePath.value = filePath
            _diffIsStaged.value = staged

            try {
                when (
                    val result = safeGitCall(Strings.git_error_get_diff_failed.str()) {
                        gitService.getDiff(path, filePath, staged)
                    }
                ) {
                    is GitResult.Success -> {
                        _diffContent.value = result.data.ifEmpty { null }
                    }

                    is GitResult.Error -> {
                        _diffError.value = result.message
                        _diffContent.value = null
                    }
                }
            } finally {
                _isLoadingDiff.value = false
            }
        }
    }

    /**
     * 清除 Diff 状态
     */
    fun clearDiff() {
        _diffContent.value = null
        _diffFilePath.value = null
        _diffError.value = null
        _isLoadingDiff.value = false
    }

    /**
     * 加载远程仓库列表
     */
    fun loadRemotes() {
        val path = projectPath ?: return
        launchGitOp(
            loadingState = _isLoadingRemotes,
            errorState = _remoteError,
            fallbackMessage = Strings.git_error_get_remotes_failed.str(),
            op = { gitService.getRemotes(path) },
            onSuccess = { _remotes.value = it }
        )
    }

    fun addRemote(name: String, url: String, onSuccess: () -> Unit = {}) {
        val path = projectPath ?: return
        launchGitOp(
            errorState = _remoteError,
            fallbackMessage = Strings.git_error_add_remote_failed.str(),
            op = { gitService.addRemote(path, name, url) },
            onSuccess = {
                loadRemotes()
                loadBranches()
                onSuccess()
            }
        )
    }

    fun removeRemote(name: String, onSuccess: () -> Unit = {}) {
        val path = projectPath ?: return
        launchGitOp(
            errorState = _remoteError,
            fallbackMessage = Strings.git_error_remove_remote_failed.str(),
            op = { gitService.removeRemote(path, name) },
            onSuccess = {
                loadRemotes()
                loadBranches()
                onSuccess()
            }
        )
    }

    fun setRemoteUrl(name: String, url: String, onSuccess: () -> Unit = {}) {
        val path = projectPath ?: return
        launchGitOp(
            errorState = _remoteError,
            fallbackMessage = Strings.git_error_update_remote_url_failed.str(),
            op = { gitService.setRemoteUrl(path, name, url) },
            onSuccess = {
                loadRemotes()
                onSuccess()
            }
        )
    }

    fun clearRemoteError() {
        _remoteError.value = null
    }

    fun clearSyncState() {
        _syncOutput.value = null
        _syncError.value = null
        _isSyncing.value = false
    }

    fun clearMergeConflicts() {
        _mergeConflicts.value = emptyList()
        _conflictKind.value = GitConflictKind.NONE
        _conflictError.value = null
        _isResolvingConflicts.value = false
    }

    fun fetch(remote: String = "origin", branch: String? = null, prune: Boolean = false) {
        val path = projectPath ?: return

        viewModelScope.launch {
            runRemoteOp(path, PendingRemoteOp.Fetch(remote = remote, branch = branch, prune = prune))
        }
    }

    fun pull(remote: String = "origin", branch: String? = null, rebase: Boolean = false) {
        val path = projectPath ?: return

        viewModelScope.launch {
            runRemoteOp(path, PendingRemoteOp.Pull(remote = remote, branch = branch, rebase = rebase))
        }
    }

    fun push(
        remote: String = "origin",
        branch: String? = null,
        setUpstream: Boolean = false,
        force: Boolean = false,
        tags: Boolean = false
    ) {
        val path = projectPath ?: return

        viewModelScope.launch {
            runRemoteOp(
                path,
                PendingRemoteOp.Push(
                    remote = remote,
                    branch = branch,
                    setUpstream = setUpstream,
                    force = force,
                    tags = tags
                )
            )
        }
    }

    fun dismissSshPassphraseRequest() {
        _sshPassphraseRequest.value = null
    }

    fun submitSshPassphrase(passphrase: String) {
        val path = projectPath ?: return
        val req = _sshPassphraseRequest.value ?: return
        val pwd = passphrase
        if (pwd.isBlank()) {
            _sshPassphraseRequest.value = req.copy(error = Strings.git_passphrase_empty.str())
            return
        }

        viewModelScope.launch {
            _isSyncing.value = true
            _syncError.value = null

            try {
                when (
                    val unlock = safeGitCall(Strings.git_error_ssh_key_unlock_failed.str()) {
                        gitService.unlockSshKey(req.keyName, pwd)
                    }
                ) {
                    is GitResult.Success -> {
                        _sshPassphraseRequest.value = null
                        runRemoteOp(path, req.pendingOp)
                    }

                    is GitResult.Error -> {
                        _sshPassphraseRequest.value = req.copy(error = unlock.message)
                    }
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private suspend fun runRemoteOp(projectPath: String, op: PendingRemoteOp) {
        _isSyncing.value = true
        _syncError.value = null
        _syncOutput.value = null

        try {
            when (op) {
                is PendingRemoteOp.Fetch -> {
                    when (
                        val result = safeGitCall(Strings.git_error_fetch_failed.str()) {
                            gitService.fetch(projectPath, remote = op.remote, branch = op.branch, prune = op.prune)
                        }
                    ) {
                        is GitResult.Success -> {
                            _syncOutput.value = result.data
                            loadBranches()
                        }

                        is GitResult.Error -> handleRemoteError(result.message, op)
                    }
                }

                is PendingRemoteOp.Pull -> {
                    _mergeConflicts.value = emptyList()
                    _conflictError.value = null
                    when (
                        val result = safeGitCall(Strings.git_error_pull_failed.str()) {
                            gitService.pull(projectPath, remote = op.remote, branch = op.branch, rebase = op.rebase)
                        }
                    ) {
                        is GitResult.Success -> {
                            _syncOutput.value = result.data
                            refresh()
                            loadCommitHistory()
                            loadBranches()
                        }

                        is GitResult.Error -> {
                            handleRemoteError(result.message, op)
                            when (
                                val conflicts = safeGitCall(Strings.git_error_get_conflict_files_failed.str()) {
                                    gitService.getUnmergedFiles(projectPath)
                                }
                            ) {
                                is GitResult.Success -> _mergeConflicts.value = conflicts.data
                                is GitResult.Error -> { /* 忽略 */ }
                            }
                            when (
                                val kind = safeGitCall(Strings.git_error_get_conflict_status_failed.str()) {
                                    gitService.getConflictKind(projectPath)
                                }
                            ) {
                                is GitResult.Success -> _conflictKind.value = kind.data
                                is GitResult.Error -> { /* 忽略 */ }
                            }
                        }
                    }
                }

                is PendingRemoteOp.Push -> {
                    when (
                        val result = safeGitCall(Strings.git_error_push_failed.str()) {
                            gitService.push(
                                projectPath,
                                remote = op.remote,
                                branch = op.branch,
                                setUpstream = op.setUpstream,
                                force = op.force,
                                tags = op.tags
                            )
                        }
                    ) {
                        is GitResult.Success -> {
                            _syncOutput.value = result.data
                            refresh()
                            loadCommitHistory()
                            loadBranches()
                        }

                        is GitResult.Error -> handleRemoteError(result.message, op)
                    }
                }
            }
        } finally {
            _isSyncing.value = false
        }
    }

    private fun handleRemoteError(message: String, op: PendingRemoteOp) {
        val parsed = parseGitSshPassphraseRequired(message)
        if (parsed != null) {
            val (keyName, host) = parsed
            _sshPassphraseRequest.value = SshPassphraseRequest(
                keyName = keyName,
                host = host,
                pendingOp = op,
                error = null
            )
            _syncError.value = stripPassphraseMarker(message).takeIf { it.isNotBlank() }
            return
        }
        _syncError.value = message
    }

    private fun stripPassphraseMarker(message: String): String = message.lineSequence()
        .filterNot { it.contains(TINA_GIT_SSH_PASSPHRASE_MARKER) }
        .joinToString("\n")
        .trim()

    fun abortMergeOrRebase(onSuccess: () -> Unit = {}) {
        val path = projectPath ?: return

        viewModelScope.launch {
            _isResolvingConflicts.value = true
            _conflictError.value = null

            try {
                when (
                    val result = safeGitCall(Strings.git_error_abort_operation_failed.str()) {
                        gitService.abortMergeOrRebase(path)
                    }
                ) {
                    is GitResult.Success -> {
                        clearMergeConflicts()
                        refresh()
                        loadCommitHistory()
                        loadBranches()
                        onSuccess()
                    }

                    is GitResult.Error -> _conflictError.value = result.message
                }
            } finally {
                _isResolvingConflicts.value = false
            }
        }
    }

    fun markConflictsResolved(files: List<String>, onSuccess: () -> Unit = {}) {
        val path = projectPath ?: return

        viewModelScope.launch {
            _isResolvingConflicts.value = true
            _conflictError.value = null

            try {
                when (
                    val result = safeGitCall(Strings.git_error_mark_resolved_failed.str()) {
                        gitService.markConflictsResolved(path, files)
                    }
                ) {
                    is GitResult.Success -> {
                        when (
                            val conflicts = safeGitCall(Strings.git_error_get_conflict_files_failed.str()) {
                                gitService.getUnmergedFiles(path)
                            }
                        ) {
                            is GitResult.Success -> _mergeConflicts.value = conflicts.data
                            is GitResult.Error -> _mergeConflicts.value = emptyList()
                        }
                        when (
                            val kind = safeGitCall(Strings.git_error_get_conflict_status_failed.str()) {
                                gitService.getConflictKind(path)
                            }
                        ) {
                            is GitResult.Success -> _conflictKind.value = kind.data
                            is GitResult.Error -> _conflictKind.value = GitConflictKind.NONE
                        }
                        refresh()
                        onSuccess()
                    }

                    is GitResult.Error -> _conflictError.value = result.message
                }
            } finally {
                _isResolvingConflicts.value = false
            }
        }
    }

    fun acceptOurs(file: String) {
        val path = projectPath ?: return

        viewModelScope.launch {
            _isResolvingConflicts.value = true
            _conflictError.value = null

            try {
                when (
                    val result = safeGitCall(Strings.git_error_checkout_ours_failed.str()) {
                        gitService.acceptOurs(path, file)
                    }
                ) {
                    is GitResult.Success -> {
                        when (
                            val conflicts = safeGitCall(Strings.git_error_get_conflict_files_failed.str()) {
                                gitService.getUnmergedFiles(path)
                            }
                        ) {
                            is GitResult.Success -> _mergeConflicts.value = conflicts.data
                            is GitResult.Error -> _mergeConflicts.value = emptyList()
                        }
                        refresh()
                    }

                    is GitResult.Error -> _conflictError.value = result.message
                }
            } finally {
                _isResolvingConflicts.value = false
            }
        }
    }

    fun acceptTheirs(file: String) {
        val path = projectPath ?: return

        viewModelScope.launch {
            _isResolvingConflicts.value = true
            _conflictError.value = null

            try {
                when (
                    val result = safeGitCall(Strings.git_error_checkout_theirs_failed.str()) {
                        gitService.acceptTheirs(path, file)
                    }
                ) {
                    is GitResult.Success -> {
                        when (
                            val conflicts = safeGitCall(Strings.git_error_get_conflict_files_failed.str()) {
                                gitService.getUnmergedFiles(path)
                            }
                        ) {
                            is GitResult.Success -> _mergeConflicts.value = conflicts.data
                            is GitResult.Error -> _mergeConflicts.value = emptyList()
                        }
                        refresh()
                    }

                    is GitResult.Error -> _conflictError.value = result.message
                }
            } finally {
                _isResolvingConflicts.value = false
            }
        }
    }

    fun continueAfterResolve(onSuccess: () -> Unit = {}) {
        val path = projectPath ?: return

        viewModelScope.launch {
            _isResolvingConflicts.value = true
            _conflictError.value = null

            try {
                when (
                    val result = safeGitCall(Strings.git_error_continue_operation_failed.str()) {
                        gitService.continueAfterResolve(path)
                    }
                ) {
                    is GitResult.Success -> {
                        when (
                            val kind = safeGitCall(Strings.git_error_get_conflict_status_failed.str()) {
                                gitService.getConflictKind(path)
                            }
                        ) {
                            is GitResult.Success -> _conflictKind.value = kind.data
                            is GitResult.Error -> _conflictKind.value = GitConflictKind.NONE
                        }
                        when (
                            val conflicts = safeGitCall(Strings.git_error_get_conflict_files_failed.str()) {
                                gitService.getUnmergedFiles(path)
                            }
                        ) {
                            is GitResult.Success -> _mergeConflicts.value = conflicts.data
                            is GitResult.Error -> _mergeConflicts.value = emptyList()
                        }
                        refresh()
                        loadCommitHistory()
                        loadBranches()
                        onSuccess()
                    }

                    is GitResult.Error -> _conflictError.value = result.message
                }
            } finally {
                _isResolvingConflicts.value = false
            }
        }
    }

    fun rebaseSkip(onSuccess: () -> Unit = {}) {
        val path = projectPath ?: return

        viewModelScope.launch {
            _isResolvingConflicts.value = true
            _conflictError.value = null

            try {
                when (
                    val result = safeGitCall(Strings.git_error_rebase_skip_failed.str()) {
                        gitService.rebaseSkip(path)
                    }
                ) {
                    is GitResult.Success -> {
                        when (
                            val kind = safeGitCall(Strings.git_error_get_conflict_status_failed.str()) {
                                gitService.getConflictKind(path)
                            }
                        ) {
                            is GitResult.Success -> _conflictKind.value = kind.data
                            is GitResult.Error -> _conflictKind.value = GitConflictKind.NONE
                        }
                        when (
                            val conflicts = safeGitCall(Strings.git_error_get_conflict_files_failed.str()) {
                                gitService.getUnmergedFiles(path)
                            }
                        ) {
                            is GitResult.Success -> _mergeConflicts.value = conflicts.data
                            is GitResult.Error -> _mergeConflicts.value = emptyList()
                        }
                        refresh()
                        loadCommitHistory()
                        loadBranches()
                        onSuccess()
                    }

                    is GitResult.Error -> _conflictError.value = result.message
                }
            } finally {
                _isResolvingConflicts.value = false
            }
        }
    }

    /**
     * 获取文件的 Git 状态（用于文件树标记）
     */
    fun getFileStatus(relativePath: String): com.wuxianggujun.tinaide.core.git.FileStatus? {
        val currentStatus = _status.value
        if (!currentStatus.isRepository) return null

        // 检查暂存区
        currentStatus.staged.find { it.path == relativePath }?.let {
            return it.status
        }

        // 检查工作区
        currentStatus.unstaged.find { it.path == relativePath }?.let {
            return it.status
        }

        // 检查未跟踪
        if (currentStatus.untracked.contains(relativePath)) {
            return com.wuxianggujun.tinaide.core.git.FileStatus.UNTRACKED
        }

        return null
    }
}
