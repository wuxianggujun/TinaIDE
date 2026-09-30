package com.wuxianggujun.tinaide.core.git

/**
 * Git 文件状态
 */
enum class FileStatus(val symbol: String) {
    MODIFIED("M"),
    ADDED("A"),
    DELETED("D"),
    RENAMED("R"),
    COPIED("C"),
    UNTRACKED("U"),
    IGNORED("!")
}

/**
 * Git 文件状态项
 */
data class GitFileStatus(
    val path: String,
    val status: FileStatus,
    val oldPath: String? = null
)

/**
 * Git 仓库状态
 */
data class GitStatus(
    val isRepository: Boolean,
    val branch: String? = null,
    val staged: List<GitFileStatus> = emptyList(),
    val unstaged: List<GitFileStatus> = emptyList(),
    val untracked: List<String> = emptyList(),
    val hasChanges: Boolean = false
) {
    companion object {
        val NOT_A_REPOSITORY = GitStatus(isRepository = false)
    }
}

/**
 * Git 操作结果
 */
sealed class GitResult<out T> {
    data class Success<T>(val data: T) : GitResult<T>()
    data class Error(val message: String) : GitResult<Nothing>()
}

/**
 * Git Diff 信息
 */
data class GitDiff(
    val filePath: String,
    val hunks: List<DiffHunk>
)

data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>
)

data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?
)

enum class DiffLineType {
    CONTEXT,
    ADDED,
    REMOVED
}

/**
 * 行号栏 git 修改指示色条的逐行改动类型（相对 HEAD 的 diff）。
 *
 * 注意：[GitService.getLineChanges] 直接基于 HEAD blob 与当前文本的行级 diff，
 * 与 [getDiff] 的 unified 文本（index 与 worktree 之间）语义不同。
 */
enum class GitLineChangeType { ADDED, MODIFIED, DELETED }

/**
 * Git 提交信息
 */
data class GitCommit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val authorEmail: String,
    val date: String,
    val message: String,
    val fullMessage: String
)

/**
 * Git 分支信息
 */
data class GitBranch(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean = false
)

/**
 * Git 远程仓库信息
 */
data class GitRemote(
    val name: String,
    val fetchUrl: String,
    val pushUrl: String
)

/**
 * Git HTTPS 凭据（用户名 + Token/密码）
 */
data class GitCredential(
    val protocol: String,
    val host: String,
    val username: String,
    val password: String
)

/**
 * 当前 Git 冲突处理模式
 */
enum class GitConflictKind {
    NONE,
    MERGE,
    REBASE
}

/**
 * Git 远程操作进度（预留：用于解析 fetch/pull/push 输出）
 */
sealed class GitProgress {
    data object Indeterminate : GitProgress()
    data class Counting(val current: Int, val total: Int) : GitProgress()
    data class Compressing(val current: Int, val total: Int) : GitProgress()
    data class Receiving(val current: Int, val total: Int) : GitProgress()
    data class Resolving(val current: Int, val total: Int) : GitProgress()
}
