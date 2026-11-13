package com.goim.kmp.repository

import co.touchlab.kermit.Logger
import com.goim.kmp.common.ioDispatcher
import com.goim.kmp.database.AppDatabase
import com.goim.kmp.database.MetaEntity
import com.goim.kmp.domain.StickerRepository
import com.goim.kmp.model.StickerItem
import com.goim.kmp.model.StickerPack
import com.goim.kmp.model.StickerRef
import com.goim.kmp.network.CatalogPack
import com.goim.kmp.network.StickerApi
import com.goim.kmp.storage.LocalMediaStore
import com.goim.kmp.storage.SystemFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * 贴纸来自网关 `GET /stickers/…`；缓存放在 `GOIM/Catalog/Stickers/`（所有账号共用）：
 * `catalog.json`、`{pack}/pack.json`、`{pack}/{sticker_id}.{ext}`。最近使用按账号存在用户库 `meta` 表。
 */
class StickerRepositoryImpl(
    private val api: StickerApi,
    private val catalogDir: Path,
    database: AppDatabase,
    private val fs: FileSystem = SystemFileSystem,
) : StickerRepository {
    private val log = Logger.withTag("Stickers")
    private val meta = database.metaDao()
    private val lock = Mutex()
    private val manifests = mutableMapOf<String, StickerPack>()

    override suspend fun packs(): List<StickerPack> = catalog().map { entry ->
        cachedManifest(entry.packId) ?: StickerPack(entry.packId, entry.name, entry.version, entry.cover, emptyList())
    }

    override suspend fun stickers(packId: String): List<StickerItem> {
        cachedManifest(packId)?.takeIf { it.stickers.isNotEmpty() }?.let { return it.stickers }
        val entry = catalog().firstOrNull { it.packId == packId } ?: return emptyList()
        return runCatching { fetchManifest(entry).stickers }
            .onFailure { if (it is CancellationException) throw it; log.w { "pack $packId: ${it.message}" } }
            .getOrDefault(emptyList())
    }

    override suspend fun syncCatalog() {
        fetchCatalog().forEach { entry ->
            val cached = cachedManifest(entry.packId)
            if (cached == null || cached.version < entry.version || cached.stickers.isEmpty()) {
                runCatching { fetchManifest(entry) }
                    .onFailure { if (it is CancellationException) throw it; log.w { "pack ${entry.packId}: ${it.message}" } }
            }
        }
    }

    override suspend fun imageBytes(ref: StickerRef): ByteArray? {
        val cachePath = catalogDir / LocalMediaStore.diskKey(ref.packId) /
            LocalMediaStore.storedFileName(LocalMediaStore.diskKey(ref.stickerId), "x.${ref.format}")
        withContext(ioDispatcher) { if (fs.exists(cachePath)) runCatching { fs.read(cachePath) { readByteArray() } }.getOrNull() else null }
            ?.let { return it }
        val file = cachedManifest(ref.packId)?.stickers?.firstOrNull { it.stickerId == ref.stickerId }?.fileName
            ?: "${ref.stickerId}.${ref.format}"
        val bytes = try {
            api.image(ref.packId, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "sticker ${ref.key}: ${e.message}" }
            return null
        }
        if (bytes.isEmpty()) return null
        runCatching { write(cachePath, bytes) }
        return bytes
    }

    override suspend fun recordRecent(ref: StickerRef) {
        val list = (listOf(ref) + recentStickers(RECENT_LIMIT)).distinctBy { it.key }.take(RECENT_LIMIT)
        persist { meta.set(MetaEntity(RECENT_KEY, recentJson.encodeToString(recentSerializer, list))) }
    }

    override suspend fun recentStickers(limit: Int): List<StickerRef> {
        val raw = persist { meta.get(RECENT_KEY) } ?: return emptyList()
        return runCatching { recentJson.decodeFromString(recentSerializer, raw) }.getOrDefault(emptyList()).take(limit)
    }

    private suspend fun catalog(): List<CatalogPack> {
        val cached = withContext(ioDispatcher) {
            val path = catalogDir / CATALOG_FILE
            if (fs.exists(path)) runCatching { StickerApi.parseCatalog(fs.read(path) { readUtf8() }) }.getOrNull() else null
        }
        if (!cached.isNullOrEmpty()) return cached
        return runCatching { fetchCatalog() }
            .onFailure { if (it is CancellationException) throw it; log.w { "catalog: ${it.message}" } }
            .getOrNull().orEmpty()
    }

    private suspend fun fetchCatalog(): List<CatalogPack> {
        val remote = api.catalog()
        write(catalogDir / CATALOG_FILE, catalogJson(remote).encodeToByteArray())
        return remote
    }

    private suspend fun cachedManifest(packId: String): StickerPack? {
        lock.withLock { manifests[packId] }?.let { return it }
        val pack = withContext(ioDispatcher) {
            val path = catalogDir / LocalMediaStore.diskKey(packId) / MANIFEST_FILE
            if (fs.exists(path)) runCatching { StickerApi.parsePackManifest(fs.read(path) { readUtf8() }) }.getOrNull() else null
        } ?: return null
        lock.withLock { manifests[packId] = pack }
        return pack
    }

    private suspend fun fetchManifest(entry: CatalogPack): StickerPack {
        val pack = api.pack(entry.packId)
        write(catalogDir / LocalMediaStore.diskKey(entry.packId) / MANIFEST_FILE, manifestJson(pack).encodeToByteArray())
        lock.withLock { manifests[entry.packId] = pack }
        return pack
    }

    private suspend fun write(path: Path, bytes: ByteArray) = withContext(ioDispatcher) {
        fs.createDirectories(path.parent!!)
        val tmp = path.parent!! / "${path.name}.tmp"
        fs.write(tmp) { write(bytes) }
        fs.atomicMove(tmp, path)
    }

    private fun catalogJson(packs: List<CatalogPack>): String = buildString {
        append("{\"packs\":[")
        packs.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append("{\"pack_id\":").append(quote(p.packId))
            append(",\"name\":").append(quote(p.name))
            append(",\"version\":").append(p.version)
            p.cover?.let { append(",\"cover\":").append(quote(it)) }
            append(",\"count\":").append(p.count).append('}')
        }
        append("]}")
    }

    private fun manifestJson(pack: StickerPack): String = buildString {
        append("{\"pack_id\":").append(quote(pack.packId))
        append(",\"name\":").append(quote(pack.name))
        append(",\"version\":").append(pack.version)
        pack.coverFileName?.let { append(",\"cover\":").append(quote(it)) }
        append(",\"stickers\":[")
        pack.stickers.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"id\":").append(quote(s.stickerId)).append(",\"file\":").append(quote(s.fileName))
            append(",\"w\":").append(s.width).append(",\"h\":").append(s.height).append('}')
        }
        append("]}")
    }

    private fun quote(s: String) = Json.encodeToString(String.serializer(), s)

    private companion object {
        const val CATALOG_FILE = "catalog.json"
        const val MANIFEST_FILE = "pack.json"
        const val RECENT_KEY = "sticker.recent"
        const val RECENT_LIMIT = 20
        val recentJson = Json { ignoreUnknownKeys = true }
        val recentSerializer = ListSerializer(StickerRef.serializer())

    }
}
