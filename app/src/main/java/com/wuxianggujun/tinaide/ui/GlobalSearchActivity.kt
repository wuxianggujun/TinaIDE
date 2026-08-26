package com.wuxianggujun.tinaide.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.ui.compose.components.GlobalSearchScreen
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import org.koin.androidx.viewmodel.ext.android.viewModel as koinViewModel

/**
 * 全局搜索 Activity
 */
class GlobalSearchActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_PROJECT_PATH = "project_path"
        const val RESULT_FILE_PATH = "result_file_path"
        const val RESULT_LINE_NUMBER = "result_line_number"

        fun createIntent(context: Context, projectPath: String): Intent = Intent(context, GlobalSearchActivity::class.java).apply {
            putExtra(EXTRA_PROJECT_PATH, projectPath)
        }
    }

    private val viewModel: GlobalSearchViewModel by koinViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH)?.takeIf { it.isNotBlank() }
            ?: run {
                finish()
                return
            }
        viewModel.setProjectPath(projectPath)

        setContent {
            TinaIDETheme {
                GlobalSearchScreen(
                    viewModel = viewModel,
                    onNavigateBack = { finish() },
                    onResultClick = { file, lineNumber ->
                        // 返回结果给调用者
                        val resultIntent = Intent().apply {
                            putExtra(RESULT_FILE_PATH, file.absolutePath)
                            putExtra(RESULT_LINE_NUMBER, lineNumber)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    }
                )
            }
        }
    }
}
