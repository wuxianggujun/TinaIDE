package com.wuxianggujun.tinaide.core.git

import android.content.Context
import com.wuxianggujun.tinaide.core.git.ssh.GitSshManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import java.io.File
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import timber.log.Timber

/**
 * Git 服务 — 纯 JGit 实现
 *
 * 所有 Git 操作通过 JGit Java API 完成，不依赖外部 git CLI 或 PRoot。
 * SSH 认证通过 GitSshManager 构建的 SshdSessionFactory 完成。
 * HTTPS 认证通过 AndroidGitCredentialManager 完成。
 */
class GitService(context: Context) {

    companion object {
        private const val TAG = "GitService"
        private const val MAX_GIT_GUTTER_CHARS = 500_000
    }

    private val appContext = context.applicationContext
    private val credentialManager: GitCredentialManager by lazy { AndroidGitCredentialManager(appContext) }
    private val sshManager: GitSshManager by lazy { GitSshManager(appContext) }

    // ── 仓库检测 ──

    suspend fun isGitRepository(projectPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { true } ?: false
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to check Git repository")
            false
        }
    }

    // ── 分支操作 ──

    suspend fun getCurrentBranch(projectPath: String): String? = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { it.branch }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to get current branch: ${e.message}")
            null
        }
    }

    suspend fun getBranches(projectPath: String): GitResult<List<GitBranch>> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                val currentBranch = repo.branch

                val locals = git.branchList().call().map { ref ->
                    val name = Repository.shortenRefName(ref.name)
                    GitBranch(name = name, isCurrent = name == currentBranch, isRemote = false)
                }
                val remotes = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call().map { ref ->
                    val name = Repository.shortenRefName(ref.name)
                    GitBranch(name = name, isCurrent = false, isRemote = true)
                }
                GitResult.Success(locals + remotes)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_get_branches_failed.str())
        }
    }

    suspend fun checkout(projectPath: String, branch: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                val localBranches = git.branchList().call().map { Repository.shortenRefName(it.name) }

                if (branch in localBranches) {
                    git.checkout().setName(branch).call()
                } else {
                    // 远程分支 → 创建本地跟踪分支
                    val shortName = branch.removePrefix("origin/")
                    git.checkout()
                        .setCreateBranch(true)
                        .setName(shortName)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                        .setStartPoint("refs/remotes/$branch")
                        .call()
                }
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_checkout_failed.str())
        }
    }

    // ── 状态 ──

    suspend fun getStatus(projectPath: String): GitStatus = withContext(Dispatchers.IO) {
        try {
            val repo = openRepo(projectPath) ?: return@withContext GitStatus.NOT_A_REPOSITORY
            repo.use {
                val git = Git(repo)
                val branch = repo.branch
                val status = git.status().call()

                val staged = mutableListOf<GitFileStatus>()
                val unstaged = mutableListOf<GitFileStatus>()
                val untracked = status.untracked.sorted().toList()

                status.added.forEach { staged.add(GitFileStatus(it, FileStatus.ADDED)) }
                status.changed.forEach { staged.add(GitFileStatus(it, FileStatus.MODIFIED)) }
                status.removed.forEach { staged.add(GitFileStatus(it, FileStatus.DELETED)) }

                status.modified.forEach { unstaged.add(GitFileStatus(it, FileStatus.MODIFIED)) }
                status.missing.forEach { unstaged.add(GitFileStatus(it, FileStatus.DELETED)) }
                status.conflicting.forEach { unstaged.add(GitFileStatus(it, FileStatus.MODIFIED)) }

                GitStatus(
                    isRepository = true,
                    branch = branch,
                    staged = staged.sortedBy { it.path },
                    unstaged = unstaged.sortedBy { it.path },
                    untracked = untracked,
                    hasChanges = staged.isNotEmpty() || unstaged.isNotEmpty() || untracked.isNotEmpty()
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to get status")
            GitStatus.NOT_A_REPOSITORY
        }
    }

    // ── Diff ──

    suspend fun getDiff(projectPath: String, filePath: String, staged: Boolean = false): GitResult<String> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                val outputStream = java.io.ByteArrayOutputStream()
                val diffCommand = git.diff().setOutputStream(outputStream)

                diffCommand.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(filePath))
                if (staged) diffCommand.setCached(true)

                diffCommand.call()
                GitResult.Success(outputStream.toString(Charsets.UTF_8.name()))
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_get_diff_failed.str())
        }
    }

    // ── Git Gutter（行号栏逐行改动色条） ──

    /**
     * 计算单个文件“相对 HEAD 的逐行改动”，用于编辑器行号栏色条。
     *
     * 与 [getDiff] 的区别：基线是 HEAD（不是 index），且直接拿 [currentText]（内存缓冲区）
     * 与 HEAD blob 做行级 diff，所以未保存的编辑也能立刻反映，行号不漂移。
     *
     * - 未跟踪 / 无任何提交：整份视为新增（全部 ADDED）
     * - 与 HEAD 内容完全一致：返回空表
     * - 非 Git 仓库：[GitResult.Error]
     */
    suspend fun getLineChanges(
        projectPath: String,
        filePath: String,
        currentText: String,
        newLineCount: Int,
        charset: Charset = Charsets.UTF_8
    ): GitResult<Map<Int, GitLineChangeType>> = withContext(Dispatchers.IO) {
        try {
            if (currentText.length > MAX_GIT_GUTTER_CHARS) return@withContext GitResult.Success(emptyMap())
            openRepo(projectPath)?.use { repo ->
                val bufferBytes = currentText.toByteArray(charset)
                val headBytes = readHeadBlobBytes(repo, filePath)
                if (headBytes != null && headBytes.contentEquals(bufferBytes)) {
                    return@use GitResult.Success(emptyMap())
                }
                val edits = HistogramDiff().diff(
                    RawTextComparator.DEFAULT,
                    RawText(headBytes ?: ByteArray(0)),
                    RawText(bufferBytes)
                )
                GitResult.Success(mapDiffEditsToLineChanges(edits, newLineCount))
            } ?: notARepo()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to compute git line changes for $filePath")
            GitResult.Error(e.message ?: Strings.git_error_get_diff_failed.str())
        }
    }

    private fun readHeadBlobBytes(repo: Repository, repoRelativePath: String): ByteArray? {
        val head = repo.resolve("HEAD") ?: return null
        val tree = repo.parseCommit(head).tree ?: return null
        org.eclipse.jgit.treewalk.TreeWalk(repo).use { walk ->
            walk.isRecursive = true
            walk.filter = org.eclipse.jgit.treewalk.filter.PathFilter.create(repoRelativePath)
            walk.addTree(tree)
            if (!walk.next()) return null
            return repo.open(walk.getObjectId(0)).getBytes()
        }
    }

    // ── Stage / Unstage ──

    suspend fun stageFile(projectPath: String, filePath: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                val file = File(repo.workTree, filePath)
                if (file.exists()) {
                    git.add().addFilepattern(filePath).call()
                } else {
                    git.rm().addFilepattern(filePath).call()
                }
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_stage_failed.str())
        }
    }

    suspend fun unstageFile(projectPath: String, filePath: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                Git(repo).reset().addPath(filePath).call()
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_unstage_failed.str())
        }
    }

    suspend fun stageAll(projectPath: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                git.add().addFilepattern(".").call()
                git.add().addFilepattern(".").setUpdate(true).call()
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_stage_failed.str())
        }
    }

    suspend fun unstageAll(projectPath: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                Git(repo).reset().call()
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_unstage_failed.str())
        }
    }

    // ── Commit ──

    suspend fun commit(projectPath: String, message: String): GitResult<String> = withContext(Dispatchers.IO) {
        try {
            if (message.isBlank()) {
                return@withContext GitResult.Error(Strings.git_error_commit_message_empty.str())
            }
            openRepo(projectPath)?.use { repo ->
                val revCommit = Git(repo).commit().setMessage(message).call()
                GitResult.Success(revCommit.id.abbreviate(7).name())
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_commit_failed.str())
        }
    }

    // ── Discard ──

    suspend fun discardChanges(projectPath: String, filePath: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                Git(repo).checkout().addPath(filePath).call()
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_discard_failed.str())
        }
    }

    // ── Init ──

    suspend fun init(projectPath: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = File(projectPath)
            if (!dir.exists()) dir.mkdirs()
            Git.init().setDirectory(dir).call().close()

            // 创建 .gitignore 文件，排除 .tinaide 目录
            val gitignoreFile = File(dir, ".gitignore")
            if (!gitignoreFile.exists()) {
                gitignoreFile.writeText(
                    """
                    # TinaIDE 项目配置目录
                    .tinaide/

                    """.trimIndent()
                )
            }

            GitResult.Success(Unit)
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_init_failed.str())
        }
    }

    // ── Clone ──

    suspend fun cloneRepository(
        url: String,
        destinationPath: String,
        branch: String? = null,
        onProgress: ((String) -> Unit)? = null,
    ): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val destDir = File(destinationPath)
            if (!destDir.exists()) destDir.mkdirs()

            val cloneCommand = Git.cloneRepository()
                .setURI(url)
                .setDirectory(destDir)

            if (!branch.isNullOrBlank()) {
                cloneCommand.setBranch(branch)
            }

            if (onProgress != null) {
                cloneCommand.setProgressMonitor(object : ProgressMonitor {
                    private var currentTask = ""
                    private var totalWork = 0
                    private var completed = 0

                    override fun start(totalTasks: Int) {}

                    override fun beginTask(title: String?, total: Int) {
                        currentTask = title.orEmpty()
                        totalWork = total
                        completed = 0
                        if (total > 0) {
                            onProgress("$currentTask (0%)")
                        } else {
                            onProgress(currentTask)
                        }
                    }

                    override fun update(work: Int) {
                        completed += work
                        if (totalWork > 0) {
                            val pct = (completed * 100 / totalWork).coerceAtMost(100)
                            onProgress("$currentTask ($pct%)")
                        }
                    }

                    override fun endTask() {}
                    override fun isCancelled(): Boolean = false
                    override fun showDuration(enabled: Boolean) {}
                })
            }

            configureTransport(cloneCommand, url)
            cloneCommand.call().close()
            GitResult.Success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Clone failed: ${e.message}")
            GitResult.Error(e.message ?: Strings.git_error_clone_failed.str())
        }
    }

    // ── Fetch ──

    suspend fun fetch(
        projectPath: String,
        remote: String = "origin",
        branch: String? = null,
        prune: Boolean = false,
    ): GitResult<String> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                val url = repo.config.getString("remote", remote, "url").orEmpty()

                val fetchCommand = git.fetch()
                    .setRemote(remote)
                    .setRemoveDeletedRefs(prune)

                if (!branch.isNullOrBlank()) {
                    fetchCommand.setRefSpecs(RefSpec("+refs/heads/$branch:refs/remotes/$remote/$branch"))
                }

                configureTransport(fetchCommand, url)
                val result = fetchCommand.call()

                val sb = StringBuilder()
                result.trackingRefUpdates.forEach { update ->
                    sb.appendLine("${update.localName}: ${update.oldObjectId?.abbreviate(7)?.name() ?: "new"} → ${update.newObjectId.abbreviate(7).name()}")
                }
                GitResult.Success(sb.toString().ifBlank { "Already up to date." })
            } ?: notARepo()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Fetch failed")
            GitResult.Error(e.message ?: Strings.git_error_fetch_failed.str())
        }
    }

    // ── Pull ──

    suspend fun pull(
        projectPath: String,
        remote: String = "origin",
        branch: String? = null,
        rebase: Boolean = false,
    ): GitResult<String> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                val url = repo.config.getString("remote", remote, "url").orEmpty()

                val pullCommand = git.pull()
                    .setRemote(remote)
                    .setRebase(rebase)

                if (!branch.isNullOrBlank()) {
                    pullCommand.setRemoteBranchName(branch)
                }

                configureTransport(pullCommand, url)
                val result = pullCommand.call()

                if (!result.isSuccessful) {
                    val mergeResult = result.mergeResult
                    if (mergeResult != null && mergeResult.mergeStatus == MergeResult.MergeStatus.CONFLICTING) {
                        val conflicts = mergeResult.conflicts?.keys?.sorted() ?: emptyList()
                        val msg = Strings.git_error_merge_conflicts_need_resolution.str() + "\n" + conflicts.joinToString("\n")
                        return@use GitResult.Error(msg)
                    }
                    val rebaseResult = result.rebaseResult
                    if (rebaseResult != null && rebaseResult.status == RebaseResult.Status.CONFLICTS) {
                        val conflicts = rebaseResult.conflicts?.sorted() ?: emptyList()
                        val msg = Strings.git_error_merge_conflicts_need_resolution.str() + "\n" + conflicts.joinToString("\n")
                        return@use GitResult.Error(msg)
                    }
                    return@use GitResult.Error(Strings.git_error_pull_failed.str())
                }

                GitResult.Success("Pull successful.")
            } ?: notARepo()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Pull failed")
            GitResult.Error(e.message ?: Strings.git_error_pull_failed.str())
        }
    }

    // ── Push ──

    suspend fun push(
        projectPath: String,
        remote: String = "origin",
        branch: String? = null,
        setUpstream: Boolean = false,
        force: Boolean = false,
        tags: Boolean = false,
    ): GitResult<String> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                val url = repo.config.getString("remote", remote, "url").orEmpty()

                val pushCommand = git.push()
                    .setRemote(remote)
                    .setForce(force)

                if (!branch.isNullOrBlank()) {
                    if (setUpstream) {
                        pushCommand.setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                    } else {
                        pushCommand.setRefSpecs(RefSpec(branch))
                    }
                }

                if (tags) {
                    pushCommand.setPushTags()
                }

                configureTransport(pushCommand, url)
                val results = pushCommand.call()

                val sb = StringBuilder()
                results.forEach { pushResult ->
                    pushResult.remoteUpdates.forEach { update ->
                        sb.appendLine("${update.remoteName}: ${update.status}")
                    }
                }

                // 设置 upstream tracking
                if (setUpstream && !branch.isNullOrBlank()) {
                    repo.config.setString("branch", branch, "remote", remote)
                    repo.config.setString("branch", branch, "merge", "refs/heads/$branch")
                    repo.config.save()
                }

                GitResult.Success(sb.toString().ifBlank { "Push successful." })
            } ?: notARepo()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Push failed")
            GitResult.Error(e.message ?: Strings.git_error_push_failed.str())
        }
    }

    // ── 提交历史 ──

    suspend fun getCommitHistory(projectPath: String, maxCount: Int = 50): GitResult<List<GitCommit>> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                val log = git.log().setMaxCount(maxCount).call()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                val commits = log.map { revCommit ->
                    GitCommit(
                        hash = revCommit.name,
                        shortHash = revCommit.abbreviate(7).name(),
                        author = revCommit.authorIdent.name,
                        authorEmail = revCommit.authorIdent.emailAddress,
                        date = dateFormat.format(revCommit.authorIdent.whenAsInstant),
                        message = revCommit.shortMessage,
                        fullMessage = revCommit.fullMessage
                    )
                }
                GitResult.Success(commits)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_get_history_failed.str())
        }
    }

    // ── 远程仓库管理 ──

    suspend fun getRemotes(projectPath: String): GitResult<List<GitRemote>> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                val remotes = git.remoteList().call().map { remoteConfig ->
                    GitRemote(
                        name = remoteConfig.name,
                        fetchUrl = remoteConfig.urIs.firstOrNull()?.toString().orEmpty(),
                        pushUrl = remoteConfig.pushURIs.firstOrNull()?.toString()
                            ?: remoteConfig.urIs.firstOrNull()?.toString().orEmpty()
                    )
                }
                GitResult.Success(remotes)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_get_remotes_failed.str())
        }
    }

    suspend fun addRemote(projectPath: String, name: String, url: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                Git(repo).remoteAdd()
                    .setName(name)
                    .setUri(URIish(url))
                    .call()
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_add_remote_failed.str())
        }
    }

    suspend fun removeRemote(projectPath: String, name: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                Git(repo).remoteRemove().setRemoteName(name).call()
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_remove_remote_failed.str())
        }
    }

    suspend fun setRemoteUrl(projectPath: String, name: String, url: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                Git(repo).remoteSetUrl()
                    .setRemoteName(name)
                    .setRemoteUri(URIish(url))
                    .call()
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_update_remote_url_failed.str())
        }
    }

    // ── SSH 密钥解锁 ──

    /**
     * 缓存 SSH 密钥的 passphrase，供后续远程操作使用。
     * passphrase 仅保存在内存中，不持久化。
     */
    suspend fun unlockSshKey(keyName: String, passphrase: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val keyFile = File(sshManager.getSshDir(), keyName)
            if (!keyFile.exists()) {
                return@withContext GitResult.Error(Strings.git_error_ssh_key_unlock_failed.str())
            }
            sshManager.cachePassphrase(keyName, passphrase)
            Timber.tag(TAG).d("SSH key passphrase cached for: $keyName")
            GitResult.Success(Unit)
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_ssh_key_unlock_failed.str())
        }
    }

    // ── 冲突处理 ──

    suspend fun getUnmergedFiles(projectPath: String): GitResult<List<String>> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val status = Git(repo).status().call()
                GitResult.Success(status.conflicting.sorted().toList())
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_get_conflict_files_failed.str())
        }
    }

    suspend fun getConflictKind(projectPath: String): GitResult<GitConflictKind> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val mergeHead = File(repo.directory, "MERGE_HEAD")
                val rebaseDir = File(repo.directory, "rebase-merge")
                val rebaseApplyDir = File(repo.directory, "rebase-apply")

                val kind = when {
                    rebaseDir.exists() || rebaseApplyDir.exists() -> GitConflictKind.REBASE
                    mergeHead.exists() -> GitConflictKind.MERGE
                    else -> GitConflictKind.NONE
                }
                GitResult.Success(kind)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_get_conflict_status_failed.str())
        }
    }

    suspend fun abortMergeOrRebase(projectPath: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                val mergeHead = File(repo.directory, "MERGE_HEAD")
                val rebaseDir = File(repo.directory, "rebase-merge")
                val rebaseApplyDir = File(repo.directory, "rebase-apply")

                when {
                    rebaseDir.exists() || rebaseApplyDir.exists() -> {
                        git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
                    }
                    mergeHead.exists() -> {
                        git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call()
                        mergeHead.delete()
                    }
                    else -> {
                        return@use GitResult.Error(Strings.git_error_no_in_progress_merge_or_rebase.str())
                    }
                }
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_abort_operation_failed.str())
        }
    }

    suspend fun markConflictsResolved(projectPath: String, files: List<String>): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                files.forEach { filePath ->
                    git.add().addFilepattern(filePath).call()
                }
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_mark_resolved_failed.str())
        }
    }

    suspend fun acceptOurs(projectPath: String, file: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (file.isBlank()) return@withContext GitResult.Error(Strings.git_error_file_path_empty.str())
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                git.checkout().setStage(org.eclipse.jgit.api.CheckoutCommand.Stage.OURS).addPath(file).call()
                git.add().addFilepattern(file).call()
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_checkout_ours_failed.str())
        }
    }

    suspend fun acceptTheirs(projectPath: String, file: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (file.isBlank()) return@withContext GitResult.Error(Strings.git_error_file_path_empty.str())
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                git.checkout().setStage(org.eclipse.jgit.api.CheckoutCommand.Stage.THEIRS).addPath(file).call()
                git.add().addFilepattern(file).call()
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_checkout_theirs_failed.str())
        }
    }

    suspend fun continueAfterResolve(projectPath: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val git = Git(repo)
                val rebaseDir = File(repo.directory, "rebase-merge")
                val rebaseApplyDir = File(repo.directory, "rebase-apply")
                val mergeHead = File(repo.directory, "MERGE_HEAD")

                when {
                    rebaseDir.exists() || rebaseApplyDir.exists() -> {
                        val result = git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call()
                        if (result.status == RebaseResult.Status.CONFLICTS) {
                            return@use GitResult.Error(Strings.git_error_rebase_continue_failed.str())
                        }
                    }
                    mergeHead.exists() -> {
                        git.commit().setMessage("Merge conflict resolved").call()
                    }
                    else -> {
                        return@use GitResult.Error(Strings.git_error_no_in_progress_merge_or_rebase.str())
                    }
                }
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_continue_operation_failed.str())
        }
    }

    suspend fun rebaseSkip(projectPath: String): GitResult<Unit> = withContext(Dispatchers.IO) {
        try {
            openRepo(projectPath)?.use { repo ->
                val result = Git(repo).rebase().setOperation(RebaseCommand.Operation.SKIP).call()
                if (result.status == RebaseResult.Status.CONFLICTS) {
                    return@use GitResult.Error(Strings.git_error_rebase_skip_failed.str())
                }
                GitResult.Success(Unit)
            } ?: notARepo()
        } catch (e: Exception) {
            GitResult.Error(e.message ?: Strings.git_error_rebase_skip_failed.str())
        }
    }

    // ── 内部工具方法 ──

    private fun openRepo(projectPath: String): Repository? {
        val gitDir = File(projectPath, ".git")
        if (!gitDir.exists()) return null
        return FileRepositoryBuilder()
            .setGitDir(gitDir)
            .readEnvironment()
            .build()
    }

    private fun <T> notARepo(): GitResult<T> = GitResult.Error("Not a Git repository")

    /**
     * 根据 URL 协议配置 Transport（SSH 或 HTTPS 认证）
     */
    private suspend fun configureTransport(command: org.eclipse.jgit.api.TransportCommand<*, *>, url: String) {
        if (isSshUrl(url)) {
            val factory = sshManager.buildSshSessionFactory(url)
            command.setTransportConfigCallback { transport ->
                if (transport is SshTransport) {
                    transport.sshSessionFactory = factory
                }
            }
        } else {
            // HTTPS — 尝试从凭据管理器获取
            val credential = try {
                val host = URIish(url).host.orEmpty()
                credentialManager.getHttpsCredential(host)
            } catch (e: Exception) {
                null
            }
            if (credential != null) {
                command.setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(credential.username, credential.password)
                )
            }
        }
    }

    private fun isSshUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("ssh://") ||
            trimmed.startsWith("git+ssh://") ||
            trimmed.startsWith("git@") ||
            (trimmed.contains("@") && trimmed.contains(":") && !trimmed.startsWith("https://") && !trimmed.startsWith("http://"))
    }
}
