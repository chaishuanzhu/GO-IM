package com.goim.kmp.repository

import com.goim.kmp.common.DomainError
import com.goim.kmp.common.ioDispatcher
import com.goim.kmp.common.userMessage
import com.goim.kmp.domain.FileDownloadEvent
import com.goim.kmp.domain.FileRepository
import com.goim.kmp.model.FileMeta
import com.goim.kmp.model.LocalFileId
import com.goim.kmp.network.GoImFileApi
import com.goim.kmp.storage.LocalMediaStore
import com.goim.kmp.storage.SystemFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer
import okio.use

class FileRepositoryImpl(
    private val api: GoImFileApi,
    private val store: LocalMediaStore,
    private val scope: CoroutineScope,
    private val fs: FileSystem = SystemFileSystem,
) : FileRepository {
    private val _uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val uploadProgress: StateFlow<Map<String, Float>> = _uploadProgress.asStateFlow()

    private val downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val downloads = mutableMapOf<String, Deferred<String>>()
    private val downloadsLock = Mutex()

    override suspend fun stage(bytes: ByteArray, fileName: String): String = io { store.stage(bytes, fileName) }

    override suspend fun stagedBytes(localId: String): ByteArray? = io { store.bytes(localId) }

    override suspend fun upload(localId: String, fileName: String, mime: String): FileMeta {
        val bytes = stagedBytes(localId) ?: throw DomainError.InvalidState("本地文件已失效，请重新选择发送")
        _uploadProgress.update { it + (localId to 0f) }
        try {
            return api.upload(bytes, fileName, mime) { fraction ->
                _uploadProgress.update { if (localId in it) it + (localId to fraction) else it }
            }
        } finally {
            _uploadProgress.update { it - localId }
        }
    }

    override suspend fun promote(localId: String, remoteFileId: String, mime: String) =
        io { store.promote(localId, remoteFileId, mime) }

    override suspend fun discardStaged(localId: String) = io { store.remove(localId) }

    override fun isUploading(localId: String): Boolean = localId in _uploadProgress.value

    override fun localPath(fileId: String): String? = store.path(fileId)?.toString()

    override fun remoteUrl(fileId: String, thumb: Boolean): String? =
        if (fileId.isEmpty() || LocalFileId.isLocal(fileId)) null else api.fileUrl(fileId, thumb)

    override fun observeDownload(fileId: String, suggestedName: String?): Flow<FileDownloadEvent> = channelFlow {
        localPath(fileId)?.let {
            send(FileDownloadEvent.Completed(it))
            return@channelFlow
        }
        if (LocalFileId.isLocal(fileId)) {
            send(FileDownloadEvent.Failed("本地文件已失效"))
            return@channelFlow
        }
        val task = startDownload(fileId, suggestedName)
        val watcher = launch {
            downloadProgress.collect { map -> map[fileId]?.let { send(FileDownloadEvent.Progress(it)) } }
        }
        val event = try {
            FileDownloadEvent.Completed(task.await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileDownloadEvent.Failed(e.userMessage())
        }
        watcher.cancel()
        send(event)
    }

    override suspend fun ensureLocal(fileId: String, suggestedName: String?): String =
        localPath(fileId) ?: startDownload(fileId, suggestedName).await()

    /** 同一 fileId 只下载一次；调用方取消不影响进行中的下载（其它气泡可能在等）。 */
    private suspend fun startDownload(fileId: String, suggestedName: String?): Deferred<String> = downloadsLock.withLock {
        downloads[fileId]?.let { return@withLock it }
        val task = scope.async(ioDispatcher) {
            try {
                downloadProgress.update { it + (fileId to 0f) }
                val part = store.downloadTarget(fileId)
                try {
                    fs.sink(part).buffer().use { sink ->
                        api.download(fileId, onProgress = { f -> downloadProgress.update { it + (fileId to f) } }) { chunk, n ->
                            sink.write(chunk, 0, n)
                        }
                    }
                    store.commitDownload(fileId, part, suggestedName).toString()
                } catch (e: Throwable) {
                    store.abortDownload(part)
                    throw e
                }
            } finally {
                downloadProgress.update { it - fileId }
                downloadsLock.withLock { downloads.remove(fileId) }
            }
        }
        downloads[fileId] = task
        task
    }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) {
        try {
            block()
        } catch (e: DomainError) {
            throw e
        } catch (e: Exception) {
            throw DomainError.Persistence(e.message ?: e::class.simpleName ?: "io error", e)
        }
    }
}
