package com.wuxianggujun.tinaide.ui.compose.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_RENDER_CHARS_PER_ROW = 2048
private const val PAGE_CHAR_BUDGET = 256 * 1024
private const val DEFAULT_MAX_CHARS_IN_MEMORY = 2 * 1024 * 1024

internal data class LargeTextRow(
    val lineNumber: Long,
    val text: String,
    val isContinuation: Boolean,
)

internal data class LargeTextPage(
    val rows: List<LargeTextRow>,
    val isEof: Boolean,
)

internal class LargeTextPager(
    private val file: File,
    private val charset: Charset,
) {
    private companion object {
        const val READ_BUFFER_CHARS = 8192
        const val END_OF_STREAM = -1
        const val NO_PENDING_CHAR = -2
    }

    private var reader: InputStreamReader? = null
    private var isEof: Boolean = false
    private var currentLineNumber: Long = 1L
    private var currentLineContinues: Boolean = false
    private val readBuffer = CharArray(READ_BUFFER_CHARS)
    private var readBufferIndex: Int = 0
    private var readBufferSize: Int = 0
    private var pendingChar: Int = NO_PENDING_CHAR

    suspend fun reset(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            close()
            reader = InputStreamReader(FileInputStream(file), charset)
            isEof = false
            currentLineNumber = 1L
            currentLineContinues = false
            readBufferIndex = 0
            readBufferSize = 0
            pendingChar = NO_PENDING_CHAR
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun close() {
        runCatching { reader?.close() }
        reader = null
        isEof = false
        readBufferIndex = 0
        readBufferSize = 0
        pendingChar = NO_PENDING_CHAR
    }

    suspend fun readNextRows(
        maxRows: Int,
        maxChars: Int,
        maxCharsPerRow: Int = MAX_RENDER_CHARS_PER_ROW,
    ): Result<LargeTextPage> {
        require(maxRows > 0) { "maxRows must be positive" }
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxCharsPerRow > 0) { "maxCharsPerRow must be positive" }
        if (isEof) return Result.success(LargeTextPage(emptyList(), isEof = true))
        val currentReader = reader ?: return Result.failure(IllegalStateException("Reader not initialized"))
        return withContext(Dispatchers.IO) {
            try {
                val rows = ArrayList<LargeTextRow>(maxRows)
                var loadedChars = 0
                while (rows.size < maxRows && loadedChars < maxChars && !isEof) {
                    val rowCharLimit = minOf(maxCharsPerRow, maxChars - loadedChars)
                    val row = readNextRow(currentReader, rowCharLimit) ?: break
                    rows.add(row)
                    loadedChars += row.text.length
                }
                Result.success(LargeTextPage(rows = rows, isEof = isEof))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }

    private fun readNextRow(currentReader: InputStreamReader, maxChars: Int): LargeTextRow? {
        val lineNumber = currentLineNumber
        val isContinuation = currentLineContinues
        val text = StringBuilder(minOf(maxChars, MAX_RENDER_CHARS_PER_ROW))

        while (text.length < maxChars) {
            when (val char = readChar(currentReader)) {
                END_OF_STREAM -> {
                    isEof = true
                    currentLineContinues = false
                    return if (text.isEmpty()) null else LargeTextRow(lineNumber, text.toString(), isContinuation)
                }
                '\n'.code -> {
                    currentLineNumber++
                    currentLineContinues = false
                    return LargeTextRow(lineNumber, text.toString(), isContinuation)
                }
                '\r'.code -> {
                    consumeOptionalLineFeed(currentReader)
                    currentLineNumber++
                    currentLineContinues = false
                    return LargeTextRow(lineNumber, text.toString(), isContinuation)
                }
                else -> text.append(char.toChar())
            }
        }

        var next = readChar(currentReader)
        if (
            text.isNotEmpty() &&
            text.last().isHighSurrogate() &&
            next != END_OF_STREAM &&
            next.toChar().isLowSurrogate()
        ) {
            // 不在 UTF-16 surrogate pair 中间切块；最多只会比预算多 1 个 Char。
            text.append(next.toChar())
            next = readChar(currentReader)
        }
        when (next) {
            END_OF_STREAM -> {
                isEof = true
                currentLineContinues = false
            }
            '\n'.code -> {
                currentLineNumber++
                currentLineContinues = false
            }
            '\r'.code -> {
                consumeOptionalLineFeed(currentReader)
                currentLineNumber++
                currentLineContinues = false
            }
            else -> {
                pendingChar = next
                currentLineContinues = true
            }
        }
        return LargeTextRow(lineNumber, text.toString(), isContinuation)
    }

    private fun consumeOptionalLineFeed(currentReader: InputStreamReader) {
        when (val next = readChar(currentReader)) {
            END_OF_STREAM -> isEof = true
            '\n'.code -> Unit
            else -> pendingChar = next
        }
    }

    private fun readChar(currentReader: InputStreamReader): Int {
        if (pendingChar != NO_PENDING_CHAR) {
            return pendingChar.also { pendingChar = NO_PENDING_CHAR }
        }
        if (readBufferIndex >= readBufferSize) {
            readBufferSize = currentReader.read(readBuffer)
            readBufferIndex = 0
            if (readBufferSize <= 0) return END_OF_STREAM
        }
        return readBuffer[readBufferIndex++].code
    }

}

@Composable
fun LargeTextViewerScreen(
    filePath: String,
    charset: Charset = Charsets.UTF_8,
    modifier: Modifier = Modifier,
    pageSizeLines: Int = 300,
    maxLinesInMemory: Int = 50_000,
    maxCharsInMemory: Int = DEFAULT_MAX_CHARS_IN_MEMORY,
    onOpenAsEditor: (() -> Unit)? = null,
    onOpenAsHex: (() -> Unit)? = null
) {
    val file = remember(filePath) { File(filePath) }
    val pager = remember(filePath, charset) { LargeTextPager(file, charset) }
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val rows = remember(filePath, charset) { mutableStateListOf<LargeTextRow>() }
    var loadedChars by remember(filePath, charset) { mutableStateOf(0) }
    var isLoading by remember(filePath, charset) { mutableStateOf(false) }
    var isEof by remember(filePath, charset) { mutableStateOf(false) }
    var error by remember(filePath, charset) { mutableStateOf<Throwable?>(null) }
    var reachedLimit by remember(filePath, charset) { mutableStateOf(false) }

    suspend fun loadMoreNow() {
        mutex.withLock {
            if (isLoading || isEof || reachedLimit || error != null) return@withLock
            val remainingRows = maxLinesInMemory - rows.size
            val remainingChars = maxCharsInMemory - loadedChars
            if (remainingRows <= 0 || remainingChars <= 0) {
                reachedLimit = true
                return@withLock
            }

            isLoading = true
            error = null
            try {
                pager.readNextRows(
                    maxRows = minOf(pageSizeLines, remainingRows),
                    maxChars = minOf(PAGE_CHAR_BUDGET, remainingChars),
                ).onSuccess { page ->
                    rows.addAll(page.rows)
                    loadedChars += page.rows.sumOf { it.text.length }
                    isEof = page.isEof
                    reachedLimit = rows.size >= maxLinesInMemory || loadedChars >= maxCharsInMemory
                }.onFailure { throwable ->
                    error = throwable
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun loadMore() {
        scope.launch { loadMoreNow() }
    }

    suspend fun resetAndLoad() {
        val resetSucceeded = mutex.withLock {
            isLoading = true
            isEof = false
            error = null
            reachedLimit = false
            loadedChars = 0
            rows.clear()
            try {
                pager.reset().onFailure { throwable -> error = throwable }.isSuccess
            } finally {
                isLoading = false
            }
        }
        if (resetSucceeded) loadMoreNow()
    }

    LaunchedEffect(filePath, charset) {
        resetAndLoad()
    }

    LaunchedEffect(listState, filePath, charset) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                val lastIndex = lastVisibleIndex ?: return@collect
                if (lastIndex >= rows.size - 30) {
                    loadMore()
                }
            }
    }

    DisposableEffect(pager) {
        onDispose { pager.close() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val fileSize = remember(filePath) { runCatching { file.length() }.getOrDefault(0L) }
                Text(
                    text = stringResource(Strings.large_file_viewer_info, file.name, fileSize / 1024),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (onOpenAsEditor != null) {
                    TextButton(onClick = onOpenAsEditor) { Text(stringResource(Strings.large_file_open_editor)) }
                }
                if (onOpenAsHex != null) {
                    TextButton(onClick = onOpenAsHex) { Text(stringResource(Strings.large_file_hex)) }
                }
            }
        }

        when {
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = error?.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: stringResource(Strings.large_file_load_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.padding(4.dp))
                        TextButton(onClick = { scope.launch { resetAndLoad() } }) {
                            Text(stringResource(Strings.large_file_retry))
                        }
                    }
                }
            }

            rows.isEmpty() && isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    itemsIndexed(rows) { _, row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = if (row.isContinuation) "" else row.lineNumber.toString().padStart(6),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(56.dp)
                            )
                            Text(
                                text = row.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    item {
                        when {
                            reachedLimit -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = stringResource(Strings.large_file_reached_safe_limit),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            isEof -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = stringResource(Strings.large_file_end_of_file),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            isLoading -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                                    Text(
                                        text = stringResource(Strings.large_file_loading),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
