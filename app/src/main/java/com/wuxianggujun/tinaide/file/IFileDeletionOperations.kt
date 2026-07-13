package com.wuxianggujun.tinaide.file

import com.wuxianggujun.tinaide.storage.FileDeletionCancellationSignal
import com.wuxianggujun.tinaide.storage.FileDeletionProgress
import com.wuxianggujun.tinaide.storage.FileDeletionResult
import java.io.File

interface IFileDeletionOperations {
    suspend fun deleteFile(
        file: File,
        cancellationSignal: FileDeletionCancellationSignal,
        onProgress: suspend (FileDeletionProgress) -> Unit,
    ): FileDeletionResult
}
