package com.goim.kmp.feature.chat

import com.goim.kmp.common.nowMillis
import com.goim.kmp.common.userMessage
import com.goim.kmp.domain.FileRepository
import com.goim.kmp.domain.LocalMediaInfo
import com.goim.kmp.domain.SendFileMessageUseCase
import com.goim.kmp.domain.SendStickerUseCase
import com.goim.kmp.domain.StickerRepository
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Message
import com.goim.kmp.model.MimeTypes
import com.goim.kmp.model.StickerItem
import com.goim.kmp.model.StickerPack
import com.goim.kmp.model.StickerRef
import com.goim.kmp.model.User
import com.goim.kmp.platform.ImageCodec
import com.goim.kmp.platform.ImageCompressor
import com.goim.kmp.platform.RecordedAudio
import com.goim.kmp.platform.mediaDurationSeconds
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 会话级附件依赖（由 app 层从 `UserSession` 组装）。 */
class ChatMediaDeps(
    val files: FileRepository,
    val stickers: StickerRepository,
    val sendFile: SendFileMessageUseCase,
    val sendSticker: SendStickerUseCase,
    /** 录音临时目录（`Users/{uid}/tmp`）。 */
    val voiceDir: String,
    val mentionCandidates: suspend () -> List<String>,
)

/** 已读入内存的待发送文件。 */
class PickedFile(val name: String, val bytes: ByteArray)

/** 发送图片 / 视频 / 文件 / 语音 / 贴纸（对齐 iOS `ChatViewModel+Media`）。 */
class ChatMediaActions internal constructor(
    private val deps: ChatMediaDeps,
    private val scope: CoroutineScope,
    private val to: String,
    private val chatType: ChatType,
    private val title: String,
    private val self: User,
    private val onError: (String) -> Unit,
) {
    val files: FileRepository get() = deps.files
    val stickers: StickerRepository get() = deps.stickers

    private val _packs = MutableStateFlow<List<StickerPack>>(emptyList())
    val stickerPacks: StateFlow<List<StickerPack>> = _packs.asStateFlow()

    private val _recent = MutableStateFlow<List<StickerRef>>(emptyList())
    val recentStickers: StateFlow<List<StickerRef>> = _recent.asStateFlow()

    private var catalogSynced = false

    /** 多选 ≤ 9：按选择顺序全部入队后并发上传；GIF 保持原样以保留动图。 */
    fun sendImages(picked: List<PickedFile>, original: Boolean) = launchReporting {
        val prepared = withContext(Dispatchers.Default) { picked.take(MAX_IMAGES).mapNotNull { prepareImage(it, original) } }
        if (prepared.size < picked.size.coerceAtMost(MAX_IMAGES)) onError("部分图片无法读取，已跳过")
        val pending = prepared.map { img ->
            deps.sendFile.enqueue(to, chatType, img.bytes, img.name, img.mime, self, title) { LocalMediaInfo(img.width, img.height) }
        }
        pending.forEach { message -> uploadLater(message) }
    }

    /** 读入待发送文件；超过 [MAX_FILE_BYTES] 或读取失败时提示并返回 null。 */
    suspend fun read(file: PlatformFile): PickedFile? {
        if (file.size() > MAX_FILE_BYTES) {
            onError("文件超过 ${MAX_FILE_BYTES / 1024 / 1024}MB，无法发送")
            return null
        }
        return try {
            withContext(Dispatchers.Default) { PickedFile(file.name, file.readBytes()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e.userMessage())
            null
        }
    }

    /** 拖入聊天区的文件（桌面）：图片压缩后发送（最多 9 张），视频与其他文件逐个发送。 */
    fun sendDropped(files: List<PlatformFile>) = launchReporting {
        val (images, others) = files.partition { MimeTypes.fromFileName(it.name).startsWith("image/") }
        if (images.size > MAX_IMAGES) onError("一次最多发送 $MAX_IMAGES 张图片")
        val picked = images.take(MAX_IMAGES).mapNotNull { read(it) }
        if (picked.isNotEmpty()) sendImages(picked, original = false)
        others.forEach { file ->
            val loaded = read(file) ?: return@forEach
            if (MimeTypes.fromFileName(loaded.name).startsWith("video/")) sendVideo(loaded) else sendDocument(loaded)
        }
    }

    fun sendCameraPhoto(file: PlatformFile) = launchReporting {
        val bytes = file.readBytes()
        runCatching { file.delete(mustExist = false) }
        sendImages(listOf(PickedFile("IMG_${nowMillis()}.jpg", bytes)), original = false)
    }

    fun sendVideo(file: PickedFile) = launchReporting {
        val mime = MimeTypes.fromFileName(file.name).takeIf { it.startsWith("video/") } ?: "video/mp4"
        val pending = deps.sendFile.enqueue(to, chatType, file.bytes, file.name, mime, self, title) { path ->
            LocalMediaInfo(duration = path?.let(::mediaDurationSeconds))
        }
        uploadLater(pending)
    }

    fun sendDocument(file: PickedFile) = launchReporting {
        val pending = deps.sendFile.enqueue(to, chatType, file.bytes, file.name, MimeTypes.fromFileName(file.name), self, title)
        uploadLater(pending)
    }

    /** 语音：至少 0.5 秒；时长四舍五入、至少 1 秒。 */
    fun sendVoice(audio: RecordedAudio) = launchReporting {
        val file = PlatformFile(audio.path)
        try {
            if (audio.durationMillis < MIN_VOICE_MILLIS) {
                onError("录音太短，请按住再试")
                return@launchReporting
            }
            val seconds = maxOf(1, (audio.durationMillis / 1000.0).roundToInt())
            val bytes = file.readBytes()
            val extension = audio.path.substringAfterLast('.', "m4a")
            val pending = deps.sendFile.enqueue(to, chatType, bytes, "voice_${nowMillis()}.$extension", audio.mime, self, title) {
                LocalMediaInfo(duration = seconds)
            }
            uploadLater(pending)
        } finally {
            runCatching { file.delete(mustExist = false) }
        }
    }

    fun voiceOutputPath(extension: String = "m4a"): String = "${deps.voiceDir.trimEnd('/')}/rec_${nowMillis()}.$extension"

    fun sendSticker(item: StickerItem) = launchReporting {
        val ref = deps.stickers.enrichedRef(item)
        deps.sendSticker(to, chatType, ref, self, title)
        refreshRecent()
    }

    fun sendSticker(ref: StickerRef) = launchReporting {
        deps.sendSticker(to, chatType, ref, self, title)
        refreshRecent()
    }

    /** 文件类消息重试：仍有暂存字节则重新上传，否则按原 content 重发。 */
    fun retry(message: Message) = launchReporting { deps.sendFile.retry(message) }

    /** 打开表情面板时：先用缓存 / 预置目录，再后台同步远端 catalog。 */
    fun loadStickers() {
        scope.launch {
            _packs.value = runCatching { deps.stickers.packs() }.getOrDefault(emptyList())
            refreshRecent()
            if (!catalogSynced) {
                catalogSynced = true
                runCatching { deps.stickers.syncCatalog() }
                _packs.value = runCatching { deps.stickers.packs() }.getOrDefault(_packs.value)
            }
        }
    }

    suspend fun stickerItems(packId: String): List<StickerItem> = runCatching { deps.stickers.stickers(packId) }.getOrDefault(emptyList())

    suspend fun mentionCandidates(): List<String> =
        runCatching { deps.mentionCandidates() }.getOrDefault(emptyList()).filter { it != self.uid }.distinct()

    private suspend fun refreshRecent() {
        _recent.value = runCatching { deps.stickers.recentStickers() }.getOrDefault(emptyList())
    }

    private fun uploadLater(message: Message) {
        scope.launch {
            try {
                deps.sendFile.upload(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.userMessage())
            }
        }
    }

    private fun launchReporting(block: suspend CoroutineScope.() -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.userMessage())
            }
        }
    }

    private class PreparedImage(val bytes: ByteArray, val name: String, val mime: String, val width: Int?, val height: Int?)

    private fun prepareImage(file: PickedFile, original: Boolean): PreparedImage? {
        val mime = MimeTypes.fromFileName(file.name)
        if (mime == "image/gif") {
            val size = ImageCodec.dimensions(file.bytes)
            return PreparedImage(file.bytes, file.name, mime, size?.first, size?.second)
        }
        val base = file.name.substringBeforeLast('.').ifEmpty { "IMG_${nowMillis()}" }
        ImageCompressor.compress(file.bytes, original)?.let {
            return PreparedImage(it.bytes, "$base.jpg", "image/jpeg", it.width, it.height)
        }
        // 平台无法解码（如桌面端 HEIC）：原样发送
        if (!mime.startsWith("image/")) return null
        val size = ImageCodec.dimensions(file.bytes)
        return PreparedImage(file.bytes, file.name, mime, size?.first, size?.second)
    }

    companion object {
        const val MAX_IMAGES = 9
        const val MIN_VOICE_MILLIS = 500L
        const val MAX_FILE_BYTES = SendFileMessageUseCase.MAX_UPLOAD_BYTES.toLong()
    }
}
