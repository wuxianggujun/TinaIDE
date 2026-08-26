package com.wuxianggujun.tinaide.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Raw
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 开源许可展示页面（纯 Compose 实现）。
 *
 * 从 `res/raw/aboutlibraries.json` 读取并展示开源许可信息（自维护，避免引入额外依赖）。
 */
class OpenSourceLicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme() // 应用系统级主题设置
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TinaIDETheme {
                OpenSourceLicensesScreen(
                    onNavigateBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenSourceLicensesScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var uiState by remember { mutableStateOf<UiState>(UiState.Loading) }
    var selectedLibraryId by rememberSaveable { mutableStateOf<String?>(null) }

    val excludedUniqueIdPrefixes = remember {
        // 过滤一部分“系统/基础设施”依赖，减少列表干扰（可按需增删）。
        listOf(
            "androidx.",
            "org.jetbrains.kotlin",
            "org.jetbrains.kotlinx",
            "com.android.",
            "com.google.android.material",
        )
    }

    LaunchedEffect(Unit) {
        uiState = withContext(Dispatchers.Default) {
            runCatching {
                val json = context.resources.openRawResource(Raw.aboutlibraries)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                parseAboutLibrariesJson(json)
                    .mergeBundledLibraries(context)
            }.fold(
                onSuccess = { data ->
                    UiState.Ready(
                        data = data.filterOut(excludedUniqueIdPrefixes)
                    )
                },
                onFailure = { UiState.Error(it) }
            )
        }
    }

    Scaffold(
        topBar = {
            TinaTopBar(
                title = stringResource(Strings.about_licenses_title),
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is UiState.Loading -> {
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressIndicator()
                }

                is UiState.Error -> {
                    Text(
                        text = stringResource(Strings.license_load_error, state.error.message ?: state.error::class.java.simpleName),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                is UiState.Ready -> {
                    val libraries by remember(state) { derivedStateOf { state.data.libraries } }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        item(key = "license_notice") {
                            LicenseNoticeCard(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }

                        items(
                            items = libraries,
                            key = { it.uniqueId }
                        ) { lib ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = lib.name ?: lib.uniqueId,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = listOfNotNull(
                                            lib.uniqueId,
                                            lib.artifactVersion?.takeIf { it.isNotBlank() }?.let { "v$it" }
                                        ).joinToString("  ·  "),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.clickable { selectedLibraryId = lib.uniqueId }
                            )
                        }
                    }

                    val selected = selectedLibraryId?.let { id -> state.data.librariesById[id] }
                    if (selected != null) {
                        val licenseEntries = selected.licenseIds
                            .mapNotNull { id -> state.data.licensesById[id] }

                        LicenseDialog(
                            library = selected,
                            licenses = licenseEntries,
                            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                            onDismiss = { selectedLibraryId = null }
                        )
                    }
                }
            }
        }
    }
}

private sealed interface UiState {
    data object Loading : UiState
    data class Ready(val data: AboutLibrariesData) : UiState
    data class Error(val error: Throwable) : UiState
}

private data class AboutLibrariesData(
    val libraries: List<LibraryEntry>,
    val librariesById: Map<String, LibraryEntry>,
    val licensesById: Map<String, LicenseEntry>
)

private data class LibraryEntry(
    val uniqueId: String,
    val name: String?,
    val artifactVersion: String?,
    val description: String?,
    val website: String?,
    val licenseIds: List<String>
)

private data class LicenseEntry(
    val id: String,
    val name: String?,
    val url: String?,
    val content: String?
)

private fun parseAboutLibrariesJson(jsonString: String): AboutLibrariesData {
    val json = JsonSerializer.default
    val root = json.parseToJsonElement(jsonString).jsonObject
    val librariesJson = root["libraries"]?.jsonArray ?: error(
        // Note: This is an internal parsing error, not user-facing, so we can use a simple string
        "Missing field: libraries"
    )
    val licensesJson = root["licenses"]?.jsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())

    val licensesById = buildMap {
        for ((id, el) in licensesJson) {
            val obj = el.jsonObject
            put(
                id,
                LicenseEntry(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.content,
                    url = obj["url"]?.jsonPrimitive?.content,
                    content = obj["content"]?.jsonPrimitive?.content
                )
            )
        }
    }

    val libraries = librariesJson.mapNotNull { el ->
        val obj = el.jsonObject
        val uniqueId = obj["uniqueId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val licenseIds = obj["licenses"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            .orEmpty()
        LibraryEntry(
            uniqueId = uniqueId,
            name = obj["name"]?.jsonPrimitive?.content,
            artifactVersion = obj["artifactVersion"]?.jsonPrimitive?.content,
            description = obj["description"]?.jsonPrimitive?.content,
            website = obj["website"]?.jsonPrimitive?.content,
            licenseIds = licenseIds
        )
    }.sortedBy { it.name ?: it.uniqueId }

    return AboutLibrariesData(
        libraries = libraries,
        librariesById = libraries.associateBy { it.uniqueId },
        licensesById = licensesById
    )
}

private fun AboutLibrariesData.filterOut(excludedUniqueIdPrefixes: List<String>): AboutLibrariesData {
    if (excludedUniqueIdPrefixes.isEmpty()) return this
    val filtered = libraries.filterNot { lib ->
        excludedUniqueIdPrefixes.any { prefix -> lib.uniqueId.startsWith(prefix) }
    }
    return copy(
        libraries = filtered,
        librariesById = filtered.associateBy { it.uniqueId }
    )
}

private fun AboutLibrariesData.mergeBundledLibraries(context: android.content.Context): AboutLibrariesData {
    val bundledLibraries = listOf(
        LibraryEntry(
            uniqueId = "bundled:termux-terminal",
            name = "Termux Terminal",
            artifactVersion = null,
            description = context.getString(Strings.license_bundled_termux_desc),
            website = null,
            licenseIds = listOf("Apache-2.0")
        )
    )

    val mergedLibraries = (libraries + bundledLibraries)
        .associateBy { it.uniqueId }
        .values
        .sortedBy { it.name ?: it.uniqueId }

    return copy(
        libraries = mergedLibraries,
        librariesById = mergedLibraries.associateBy { it.uniqueId }
    )
}

@Composable
private fun LicenseNoticeCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(Strings.license_notice_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(Strings.license_notice_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun LicenseDialog(
    library: LibraryEntry,
    licenses: List<LicenseEntry>,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        },
        title = {
            TinaDialogTitleText(
                title = library.name ?: library.uniqueId,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            TinaDialogContentColumn(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(scrollState)
            ) {
                if (!library.website.isNullOrBlank()) {
                    Text(
                        text = stringResource(Strings.license_project_homepage, library.website),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenUrl(library.website) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (!library.description.isNullOrBlank()) {
                    Text(text = library.description)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (licenses.isEmpty()) {
                    Text(text = stringResource(Strings.license_not_found))
                    return@TinaDialogContentColumn
                }

                licenses.forEachIndexed { index, license ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    Text(
                        text = license.name ?: license.id,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (!license.url.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = license.url,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenUrl(license.url) }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = license.content ?: stringResource(Strings.license_no_content))
                }
            }
        }
    )
}
