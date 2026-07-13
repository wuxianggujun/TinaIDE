package com.wuxianggujun.tinaide.core.packages

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 当前进程内置包安装批次的轻量就绪屏障。
 *
 * 安装器通过 staging 原子发布目录；编译器只需等待正在进行的批次结束，
 * 超时或失败时仍可继续使用磁盘上最后一份稳定安装。
 */
object BundledPackagesReadiness {
    private const val DEFAULT_WAIT_TIMEOUT_MS = 120_000L

    enum class State {
        IDLE,
        INSTALLING,
        READY,
        FAILED,
        TIMED_OUT,
    }

    private val state = MutableStateFlow(State.IDLE)

    fun markInstalling() {
        state.value = State.INSTALLING
    }

    fun markReady() {
        state.value = State.READY
    }

    fun markFailed() {
        state.value = State.FAILED
    }

    suspend fun awaitCurrentInstall(timeoutMillis: Long = DEFAULT_WAIT_TIMEOUT_MS): State {
        if (state.value != State.INSTALLING) return state.value
        return withTimeoutOrNull(timeoutMillis) {
            state.filter { it != State.INSTALLING }.first()
        } ?: State.TIMED_OUT
    }
}
