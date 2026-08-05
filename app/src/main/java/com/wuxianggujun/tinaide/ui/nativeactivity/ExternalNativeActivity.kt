package com.wuxianggujun.tinaide.ui.nativeactivity

import android.app.NativeActivity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.Keep
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeActivityHost
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeIntentOptions
import com.wuxianggujun.tinaide.ui.runtime.NativeLaunchEnvironment
import timber.log.Timber

/** NativeActivity container that delegates to a staged user shared library. */
class ExternalNativeActivity : NativeActivity() {
    companion object {
        private const val TAG = "ExternalNativeActivity"
        private const val EXTRA_MAIN_LIBRARY_PATH = "extra_native_main_library_path"
        private const val EXTRA_DEPENDENCY_LIBRARY_PATHS = "extra_native_dependency_library_paths"

        fun createIntent(
            context: Context,
            mainLibraryPath: String,
            dependencyLibraryPaths: List<String>,
            enableFloatingLog: Boolean = false,
            launchEnvironment: Map<String, String> = emptyMap(),
        ): Intent = Intent(context, ExternalNativeActivity::class.java).apply {
            putExtra(EXTRA_MAIN_LIBRARY_PATH, mainLibraryPath)
            putStringArrayListExtra(
                EXTRA_DEPENDENCY_LIBRARY_PATHS,
                ArrayList(dependencyLibraryPaths),
            )
            GraphicalRuntimeIntentOptions.putIntoIntent(this, enableFloatingLog)
            NativeLaunchEnvironment.putIntoIntent(this, launchEnvironment)
        }
    }

    private val runtimeHost = GraphicalRuntimeActivityHost(this, TAG)
    private var enableFloatingLog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFloatingLog = GraphicalRuntimeIntentOptions.readFloatingLogEnabled(intent)
        runtimeHost.onPreCreate(savedInstanceState, intent)
        runtimeHost.requestReturnToParentOnFinish()

        val configured = NativeActivityHostBridge.configure(
            mainLibraryPath = intent.getStringExtra(EXTRA_MAIN_LIBRARY_PATH).orEmpty(),
            dependencyLibraryPaths = intent
                .getStringArrayListExtra(EXTRA_DEPENDENCY_LIBRARY_PATHS)
                .orEmpty(),
        )
        if (!configured) {
            Timber.tag(TAG).e("NativeActivity host configuration failed")
        }

        // NativeActivity loads tina_native_activity_host and invokes its ANativeActivity_onCreate here.
        super.onCreate(savedInstanceState)

        if (!isFinishing) {
            findViewById<ViewGroup>(android.R.id.content)?.let { container ->
                runtimeHost.attachOverlay(
                    container = container,
                    enableFloatingLog = enableFloatingLog,
                    onExit = ::exitToParent,
                )
            } ?: Timber.tag(TAG).e("No NativeActivity view container is available for runtime controls")
        }
    }

    override fun onStart() {
        super.onStart()
        runtimeHost.onStart()
    }

    override fun onResume() {
        super.onResume()
        runtimeHost.onResume()
    }

    override fun onPause() {
        runtimeHost.onPause()
        super.onPause()
    }

    override fun onStop() {
        runtimeHost.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        runtimeHost.onSaveInstanceState(outState)
    }

    override fun finish() {
        runtimeHost.onFinish()
        super.finish()
    }

    override fun onDestroy() {
        val shouldTerminateRuntimeProcess = isFinishing && !isChangingConfigurations
        try {
            super.onDestroy()
        } finally {
            try {
                runtimeHost.onDestroy()
            } finally {
                // Android keeps dlopen handles by SONAME, so the next run needs a fresh process.
                if (shouldTerminateRuntimeProcess) Process.killProcess(Process.myPid())
            }
        }
    }

    @Keep
    fun onNativeHostError(errorCode: Int, detail: String?) {
        val message = when (errorCode) {
            NativeActivityHostBridge.ERROR_NOT_CONFIGURED ->
                Strings.native_activity_runtime_host_not_configured.strOr(this)
            NativeActivityHostBridge.ERROR_DEPENDENCY_LOAD ->
                Strings.native_activity_runtime_dependency_load_failed.strOr(this, detail.orEmpty())
            NativeActivityHostBridge.ERROR_MAIN_LOAD ->
                Strings.native_activity_runtime_main_load_failed.strOr(this, detail.orEmpty())
            NativeActivityHostBridge.ERROR_ENTRY_MISSING ->
                Strings.native_activity_runtime_entry_missing.strOr(this, detail.orEmpty())
            NativeActivityHostBridge.ERROR_RAYLIB_MAIN_MISSING ->
                Strings.native_activity_runtime_main_entry_missing.strOr(this)
            NativeActivityHostBridge.ERROR_ENTRY_RECURSION ->
                Strings.native_activity_runtime_bridge_recursion.strOr(this)
            NativeActivityHostBridge.ERROR_MULTIPLE_ENTRIES ->
                Strings.native_activity_runtime_multiple_entries.strOr(this, detail.orEmpty())
            else -> Strings.native_activity_runtime_main_load_failed.strOr(this, detail.orEmpty())
        }
        Timber.tag(TAG).e("NativeActivity host error code=%d detail=%s", errorCode, detail.orEmpty())
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun exitToParent() {
        runtimeHost.requestReturnToParentOnFinish()
        finish()
    }
}
