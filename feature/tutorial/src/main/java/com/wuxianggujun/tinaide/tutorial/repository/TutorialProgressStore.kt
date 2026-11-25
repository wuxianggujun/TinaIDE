package com.wuxianggujun.tinaide.tutorial.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.tutorial.data.ProgressStatus
import com.wuxianggujun.tinaide.tutorial.data.TutorialProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Context 扩展属性，创建教程 DataStore 实例
 */
private val Context.tutorialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tutorial_preferences"
)

/**
 * 教程进度持久化存储
 *
 * 使用 DataStore 存储用户的教程学习进度
 */
class TutorialProgressStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: kotlinx.serialization.json.Json,
) {

    constructor(context: Context) : this(context.tutorialDataStore, JsonSerializer.default)

    internal constructor(dataStore: DataStore<Preferences>) : this(dataStore, JsonSerializer.default)

    private val hasLoggedInvalidProgress = AtomicBoolean(false)

    /**
     * 观察所有教程进度
     */
    val allProgressFlow: Flow<Map<String, TutorialProgress>> = dataStore.data.map { preferences ->
        val json = preferences[KEY_ALL_PROGRESS] ?: return@map emptyMap()
        parseProgressMap(json).orEmpty()
    }

    /**
     * 获取特定教程的进度
     */
    suspend fun getProgress(tutorialId: String): TutorialProgress? = allProgressFlow.first()[tutorialId]

    /**
     * 获取所有教程进度
     */
    suspend fun getAllProgress(): Map<String, TutorialProgress> = allProgressFlow.first()

    /**
     * 保存教程进度
     */
    suspend fun saveProgress(progress: TutorialProgress) {
        updateProgress(progress.tutorialId) { progress }
    }

    /**
     * 开始教程
     */
    suspend fun startTutorial(tutorialId: String) {
        updateProgress(tutorialId) { existing ->
            existing?.copy(
                status = ProgressStatus.IN_PROGRESS,
                currentStepIndex = if (existing.status == ProgressStatus.COMPLETED) 0 else existing.currentStepIndex,
                completedStepIds = if (existing.status == ProgressStatus.COMPLETED) emptySet() else existing.completedStepIds,
                completedAt = null,
            ) ?: TutorialProgress(
                tutorialId = tutorialId,
                status = ProgressStatus.IN_PROGRESS,
                currentStepIndex = 0,
            )
        }
    }

    /**
     * 更新教程步骤进度
     */
    suspend fun updateStepProgress(tutorialId: String, stepIndex: Int) {
        updateProgress(tutorialId) { existing ->
            (existing ?: TutorialProgress(
                tutorialId = tutorialId,
                status = ProgressStatus.IN_PROGRESS,
            )).copy(
                currentStepIndex = stepIndex,
                status = ProgressStatus.IN_PROGRESS,
                completedAt = null,
            )
        }
    }

    /** 原子更新单个课程章节的确认状态，避免与章节索引写入互相覆盖。 */
    suspend fun setStepCompleted(tutorialId: String, stepId: String, completed: Boolean) {
        updateProgress(tutorialId) { current ->
            val existing = current ?: TutorialProgress(
                tutorialId = tutorialId,
                status = ProgressStatus.IN_PROGRESS,
            )
            val completedStepIds = existing.completedStepIds.toMutableSet().apply {
                if (completed) add(stepId) else remove(stepId)
            }
            existing.copy(
                status = ProgressStatus.IN_PROGRESS,
                completedStepIds = completedStepIds,
                completedAt = null,
            )
        }
    }

    /** A delayed verification must not complete a chapter in a restarted or advanced course. */
    suspend fun recordVerifiedStep(
        tutorialId: String,
        stepId: String,
        stepIndex: Int,
        previousStepIds: Set<String>,
        expectedProgress: TutorialProgress?,
    ): Boolean {
        var recorded = false
        dataStore.edit { preferences ->
            val progressMap = readProgressMapForUpdate(preferences)
            val current = progressMap[tutorialId]
            if (current != expectedProgress || (current?.currentStepIndex ?: 0) != stepIndex ||
                !current?.completedStepIds.orEmpty().containsAll(previousStepIds)) return@edit
            progressMap[tutorialId] = (current ?: TutorialProgress(tutorialId = tutorialId, status = ProgressStatus.IN_PROGRESS)).copy(
                status = ProgressStatus.IN_PROGRESS,
                currentStepIndex = stepIndex,
                completedStepIds = current?.completedStepIds.orEmpty() + stepId,
                completedAt = null,
                lastAccessedAt = System.currentTimeMillis(),
            )
            preferences[KEY_ALL_PROGRESS] = json.encodeToString(progressMap)
            recorded = true
        }
        return recorded
    }

    /** 原子替换课程章节完成集合和当前位置，避免撤销章节时进度出现半更新。 */
    suspend fun updateCourseProgress(
        tutorialId: String,
        completedStepIds: Set<String>,
        currentStepIndex: Int,
    ) {
        updateProgress(tutorialId) { current ->
            val existing = current ?: TutorialProgress(
                tutorialId = tutorialId,
                status = ProgressStatus.IN_PROGRESS,
            )
            existing.copy(
                status = ProgressStatus.IN_PROGRESS,
                completedStepIds = completedStepIds,
                currentStepIndex = currentStepIndex,
                completedAt = null,
            )
        }
    }

    /**
     * 完成教程
     */
    suspend fun completeTutorial(tutorialId: String) {
        updateProgress(tutorialId) { existing ->
            (existing ?: TutorialProgress(
                tutorialId = tutorialId,
                status = ProgressStatus.NOT_STARTED,
                currentStepIndex = 0,
            )).copy(
                status = ProgressStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
            )
        }
    }

    /** Persist onboarding completion and its tutorial status in one DataStore transaction. */
    suspend fun completeOnboardingTutorial(tutorialId: String) {
        dataStore.edit { preferences ->
            val progressMap = readProgressMapForUpdate(preferences)
            val existing = progressMap[tutorialId] ?: TutorialProgress(
                tutorialId = tutorialId,
                status = ProgressStatus.NOT_STARTED,
            )
            val completedAt = System.currentTimeMillis()
            progressMap[tutorialId] = existing.copy(
                status = ProgressStatus.COMPLETED,
                completedAt = completedAt,
                lastAccessedAt = completedAt,
            )
            preferences[KEY_ALL_PROGRESS] = json.encodeToString(progressMap)
            preferences[KEY_ONBOARDING_COMPLETED] = true
            preferences[KEY_ONBOARDING_SKIPPED] = false
        }
    }

    /**
     * 重置教程进度
     */
    suspend fun resetProgress(tutorialId: String) {
        dataStore.edit { preferences ->
            val currentMap = readProgressMapForUpdate(preferences)
            currentMap.remove(tutorialId)
            preferences[KEY_ALL_PROGRESS] = json.encodeToString(currentMap)
        }
    }

    /**
     * 清除所有进度
     */
    suspend fun clearAllProgress() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_ALL_PROGRESS)
            preferences.remove(KEY_CORRUPTED_PROGRESS_BACKUP)
            preferences.remove(KEY_ONBOARDING_COMPLETED)
            preferences.remove(KEY_ONBOARDING_SKIPPED)
        }
    }

    /**
     * 观察是否已完成新手引导
     */
    val hasCompletedOnboardingFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_ONBOARDING_COMPLETED] == true
    }

    /**
     * 检查是否已完成新手引导
     */
    suspend fun hasCompletedOnboarding(): Boolean = dataStore.data.first()[KEY_ONBOARDING_COMPLETED] == true

    /**
     * 设置新手引导完成状态
     */
    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    /**
     * 观察是否已跳过新手引导
     */
    val hasSkippedOnboardingFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_ONBOARDING_SKIPPED] == true
    }

    /**
     * 设置跳过新手引导
     */
    suspend fun setOnboardingSkipped(skipped: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_ONBOARDING_SKIPPED] = skipped
        }
    }

    /**
     * 检查是否应该显示新手引导
     * 条件：未完成且未跳过
     */
    suspend fun shouldShowOnboarding(): Boolean {
        val preferences = dataStore.data.first()
        val completed = preferences[KEY_ONBOARDING_COMPLETED] == true
        val skipped = preferences[KEY_ONBOARDING_SKIPPED] == true
        return !completed && !skipped
    }

    /**
     * 获取已完成的教程数量
     */
    suspend fun getCompletedCount(): Int = getAllProgress().values.count { it.status == ProgressStatus.COMPLETED }

    /**
     * 获取进行中的教程数量
     */
    suspend fun getInProgressCount(): Int = getAllProgress().values.count { it.status == ProgressStatus.IN_PROGRESS }

    /**
     * 解析进度 Map JSON
     */
    private fun parseProgressMap(jsonString: String?): Map<String, TutorialProgress>? {
        if (jsonString.isNullOrEmpty()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, TutorialProgress>>(jsonString)
        } catch (error: SerializationException) {
            if (hasLoggedInvalidProgress.compareAndSet(false, true)) {
                Log.w(TAG, "Stored tutorial progress is invalid; it will be backed up before repair", error)
            }
            null
        }
    }

    /** Keep unreadable user progress before a write replaces the active serialized map. */
    private fun readProgressMapForUpdate(preferences: MutablePreferences): MutableMap<String, TutorialProgress> {
        val rawProgress = preferences[KEY_ALL_PROGRESS] ?: return mutableMapOf()
        return parseProgressMap(rawProgress)?.toMutableMap() ?: run {
            if (preferences[KEY_CORRUPTED_PROGRESS_BACKUP] == null) {
                preferences[KEY_CORRUPTED_PROGRESS_BACKUP] = rawProgress
            }
            mutableMapOf()
        }
    }

    /** 在同一 DataStore 事务中读取并更新进度，避免快速操作时覆盖另一个章节变更。 */
    private suspend fun updateProgress(
        tutorialId: String,
        transform: (TutorialProgress?) -> TutorialProgress,
    ) {
        dataStore.edit { preferences ->
            val progressMap = readProgressMapForUpdate(preferences)
            progressMap[tutorialId] = transform(progressMap[tutorialId]).copy(
                lastAccessedAt = System.currentTimeMillis(),
            )
            preferences[KEY_ALL_PROGRESS] = json.encodeToString(progressMap)
        }
    }

    companion object {
        private const val TAG = "TutorialProgressStore"
        private val KEY_ALL_PROGRESS = stringPreferencesKey("all_tutorial_progress")
        private val KEY_CORRUPTED_PROGRESS_BACKUP = stringPreferencesKey("all_tutorial_progress_corrupt_backup")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_ONBOARDING_SKIPPED = booleanPreferencesKey("onboarding_skipped")
    }
}
