package com.wuxianggujun.tinaide.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * 创建 EncryptedSharedPreferences，并在本地密钥集无法解密时重建存储。
 *
 * Android Keystore 的密钥可能因为换机恢复、系统升级、锁屏凭据变更或应用数据
 * 从备份恢复而失效。此时 AndroidX Security 会在读取 keyset 时抛出
 * `AEADBadTagException`（`GeneralSecurityException` 的子类）。如果调用发生在
 * `Application.onCreate()`，异常会直接终止进程。
 *
 * 凭据无法解密时只能丢弃并重新输入。这里最多尝试三次：
 *
 *  1. 正常创建；
 *  2. 清空该凭据文件后重建（文件从其他安装恢复、master key 未变的常见场景）；
 *  3. 删除 Android Keystore 中的 master key 后重建（master key 本身失效的场景）。
 *
 * 三次都失败时返回 `null`，由调用方按“凭据不存在”处理，绝不让启动流程崩溃。
 */
object EncryptedPreferencesFactory {

    private const val TAG = "EncryptedPreferences"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MAX_ATTEMPTS = 3

    /**
     * @return 可用的加密偏好存储；本地密钥彻底不可用时返回 `null`。
     */
    @Synchronized
    fun open(context: Context, fileName: String): SharedPreferences? {
        val appContext = context.applicationContext
        return recoverEncryptedPreferences(
            open = { create(appContext, fileName) },
            onFailure = { attempt, error ->
                if (attempt < MAX_ATTEMPTS) {
                    Log.w(TAG, "Failed to open encrypted preferences '$fileName' (attempt $attempt); retrying", error)
                } else {
                    Log.e(TAG, "Unable to recreate encrypted preferences '$fileName'", error)
                }
            },
            resetPreferences = { reset(appContext, fileName, deleteMasterKey = false) },
            resetMasterKey = { reset(appContext, fileName, deleteMasterKey = true) },
        )
    }

    private fun create(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun reset(context: Context, fileName: String, deleteMasterKey: Boolean) {
        if (deleteMasterKey) {
            runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            }.onFailure {
                Log.w(TAG, "Failed to delete Android Keystore master key", it)
            }
        }

        runCatching {
            context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }.onFailure {
            Log.w(TAG, "Failed to clear encrypted preferences '$fileName'", it)
        }
    }
}

/**
 * 按“直接打开 → 清空偏好 → 删除 master key”的顺序尝试恢复加密存储。
 *
 * 独立成函数以便在 JVM 上验证重试顺序，不依赖 Android Keystore。
 */
internal fun <T> recoverEncryptedPreferences(
    open: () -> T,
    onFailure: (attempt: Int, error: Exception) -> Unit,
    resetPreferences: () -> Unit,
    resetMasterKey: () -> Unit,
): T? {
    return try {
        open()
    } catch (first: Exception) {
        onFailure(1, first)
        resetPreferences()
        try {
            open()
        } catch (second: Exception) {
            onFailure(2, second)
            resetMasterKey()
            try {
                open()
            } catch (third: Exception) {
                onFailure(3, third)
                null
            }
        }
    }
}
