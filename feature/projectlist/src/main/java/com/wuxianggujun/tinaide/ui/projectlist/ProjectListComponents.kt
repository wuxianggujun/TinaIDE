package com.wuxianggujun.tinaide.ui.projectlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.storage.ProjectPaths
import com.wuxianggujun.tinaide.ui.compose.components.TinaSemanticColors
import java.io.File

@Composable
fun SearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.Medium,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
fun ProjectTagChip(tag: ProjectTag) {
    val context = LocalContext.current
    val (backgroundColor, textColor) = when (tag) {
        ProjectTag.PUBLIC_SOURCE ->
            MaterialTheme.colorScheme.secondaryContainer to
                MaterialTheme.colorScheme.onSecondaryContainer
        ProjectTag.PRIVATE_SOURCE ->
            MaterialTheme.colorScheme.tertiaryContainer to
                MaterialTheme.colorScheme.onTertiaryContainer
        ProjectTag.GIT -> TinaSemanticColors.Language.gitBg to TinaSemanticColors.Language.gitText
        ProjectTag.CMAKE -> TinaSemanticColors.Language.cmakeBg to TinaSemanticColors.Language.cmakeText
        ProjectTag.MAKEFILE -> TinaSemanticColors.Language.makefileBg to TinaSemanticColors.Language.makefileText
        ProjectTag.PLUGIN ->
            MaterialTheme.colorScheme.primaryContainer to
                MaterialTheme.colorScheme.onPrimaryContainer
        ProjectTag.C_CPP -> TinaSemanticColors.Language.cppBg to TinaSemanticColors.Language.cppText
        ProjectTag.JAVA -> TinaSemanticColors.Language.javaBg to TinaSemanticColors.Language.javaText
        ProjectTag.KOTLIN -> TinaSemanticColors.Language.kotlinBg to TinaSemanticColors.Language.kotlinText
        ProjectTag.PYTHON -> TinaSemanticColors.Language.pythonBg to TinaSemanticColors.Language.pythonText
        ProjectTag.RUST -> TinaSemanticColors.Language.rustBg to TinaSemanticColors.Language.rustText
        ProjectTag.GO -> TinaSemanticColors.Language.goBg to TinaSemanticColors.Language.goText
        ProjectTag.JAVASCRIPT -> TinaSemanticColors.Language.jsBg to TinaSemanticColors.Language.jsText
        ProjectTag.TYPESCRIPT -> TinaSemanticColors.Language.tsBg to TinaSemanticColors.Language.tsText
        ProjectTag.SHELL -> TinaSemanticColors.Language.shellBg to TinaSemanticColors.Language.shellText
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = tag.getDisplayName(context),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
            ),
            color = textColor,
        )
    }
}

@Composable
fun EmptyProjectsView(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }

            Text(
                text = stringResource(Strings.empty_no_projects),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = stringResource(Strings.empty_create_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = TinaSemanticColors.Project.fabHint,
                )
                Text(
                    text = stringResource(Strings.empty_fab_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TinaSemanticColors.Project.fabHint,
                )
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 16.dp),
        )
    }
}

fun formatPathForDisplay(
    basePath: String,
    projectName: String,
    context: android.content.Context,
): String {
    val internalPath = ProjectPaths.getPrivateProjectsRootPath(context)
    val displayBase = if (File(basePath).absolutePath == internalPath) "projects" else basePath
    return if (projectName.isNotEmpty()) "$displayBase/$projectName" else displayBase
}

data class DirectoryStats(
    val sizeBytes: Long,
    val fileCount: Int,
)

fun calculateDirectoryStats(dir: File): DirectoryStats {
    var sizeBytes = 0L
    var fileCount = 0
    dir.walkTopDown().forEach { file ->
        if (file.isFile) {
            sizeBytes += file.length()
            fileCount++
        }
    }
    return DirectoryStats(sizeBytes = sizeBytes, fileCount = fileCount)
}

fun formatFileSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
    else -> "${size / (1024 * 1024 * 1024)} GB"
}

fun extractProjectNameFromUrl(url: String): String {
    if (url.isBlank()) return ""
    val cleanUrl = url.trim().removeSuffix(".git").removeSuffix("/")
    val lastSegment = cleanUrl.substringAfterLast("/")
    return lastSegment.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }
}

fun isValidGitUrl(url: String): Boolean {
    val trimmedUrl = url.trim()
    return trimmedUrl.startsWith("https://") ||
        trimmedUrl.startsWith("http://") ||
        trimmedUrl.startsWith("git@") ||
        trimmedUrl.startsWith("ssh://")
}
