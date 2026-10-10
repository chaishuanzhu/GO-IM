package com.goim.kmp.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.goim.kmp.common.DomainError
import com.goim.kmp.database.AppDatabase
import com.goim.kmp.domain.ConnectionRepository
import com.goim.kmp.domain.InboundEvent
import com.goim.kmp.domain.LocalMediaInfo
import com.goim.kmp.domain.OutboundEnvelope
import com.goim.kmp.domain.SendFileMessageUseCase
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.LocalFileId
import com.goim.kmp.model.MessagePreview
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.MsgType
import com.goim.kmp.model.StickerRef
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User
import com.goim.kmp.network.GoImFileApi
import com.goim.kmp.network.createHttpClient
import com.goim.kmp.storage.LocalMediaStore
import com.goim.kmp.storage.UserHome
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MediaFlowTest {
    private val me = User("alice", "alice", "jwt")
    private val db = Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val fs = FakeFileSystem()
    private val home = UserHome("alice", "/GOIM/Users/alice".toPath())

    private class FakeConnection : ConnectionRepository {
        override val state = MutableStateFlow(ConnectionState.DISCONNECTED)
        val events = Channel<InboundEvent>(Channel.UNLIMITED)
        override val inboundEvents: Flow<InboundEvent> = events.receiveAsFlow()
        val sent = mutableListOf<OutboundEnvelope>()

        override suspend fun connect(user: User, transport: TransportKind) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun ensureConnected() = Unit
        override suspend fun send(envelope: OutboundEnvelope) {
            sent += envelope
        }
    }

    /** 每次上传的响应；默认把语音识别成 video/mp4（同 Go `DetectContentType`）。 */
    private var uploadResponse: () -> Pair<HttpStatusCode, String> = {
        HttpStatusCode.OK to """{"file_id":77,"name":"voice.m4a","size":4,"mime":"video/mp4","width":0,"height":0}"""
    }

    private fun files(scope: CoroutineScope): FileRepositoryImpl {
        val engine = MockEngine {
            val (status, body) = uploadResponse()
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return FileRepositoryImpl(GoImFileApi(createHttpClient(engine), { "http://h" }, me), LocalMediaStore(home, fs), scope, fs)
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun uploadRewritesContentKeepsAudioMimeAndPromotes() = runBlocking {
        val connection = FakeConnection()
        val messages = MessageRepositoryImpl(db, connection, this)
        val files = files(this)
        val useCase = SendFileMessageUseCase(files, messages, ConversationRepositoryImpl(db))

        val pending = useCase.enqueue("bob", ChatType.SINGLE, byteArrayOf(1, 2, 3, 4), "voice.m4a", "audio/mp4", me, "bob") {
            LocalMediaInfo(duration = 3)
        }
        assertEquals(MsgType.Voice, pending.msgType)
        val localId = MessagePreview.decodeFileMeta(pending.content)!!.fileId
        assertTrue(LocalFileId.isLocal(localId))

        val delivered = useCase.upload(pending)
        val meta = MessagePreview.decodeFileMeta(delivered.content)!!
        assertEquals("77", meta.fileId)
        assertEquals("audio/mp4", meta.mime, "保留客户端音频 MIME")
        assertEquals(3, meta.duration)
        assertEquals(MsgType.Voice, (connection.sent.single() as OutboundEnvelope.File).message.msgType)
        assertEquals("/GOIM/Users/alice/media/77.m4a", files.localPath("77"))
        assertEquals(null, files.localPath(localId), "暂存副本已删除")
        messages.cancelPendingAcks()
    }

    @Test
    fun failedUploadMarksFailedAndRetryReuploads() = runBlocking {
        val connection = FakeConnection()
        val messages = MessageRepositoryImpl(db, connection, this)
        val files = files(this)
        val useCase = SendFileMessageUseCase(files, messages, ConversationRepositoryImpl(db))

        uploadResponse = { HttpStatusCode.InternalServerError to "upload failed" }
        val pending = useCase.enqueue("bob", ChatType.SINGLE, byteArrayOf(5), "a.pdf", "application/pdf", me, "bob")
        assertFailsWith<DomainError.Server> { useCase.upload(pending) }
        val failed = messages.message(pending.id)!!
        assertEquals(MessageStatus.FAILED, failed.status)
        assertTrue(connection.sent.isEmpty())

        uploadResponse = { HttpStatusCode.OK to """{"file_id":"f9","name":"a.pdf","size":1,"mime":"application/pdf"}""" }
        useCase.retry(failed)
        assertEquals("f9", MessagePreview.decodeFileMeta(messages.message(pending.id)!!.content)!!.fileId)
        assertEquals(1, connection.sent.size)
        assertEquals("/GOIM/Users/alice/files/f9.pdf", files.localPath("f9"))
        messages.cancelPendingAcks()
    }

    @Test
    fun reconnectFailsStaleLocalUploadsInsteadOfResending() = runBlocking {
        val connection = FakeConnection()
        val messages = MessageRepositoryImpl(db, connection, this)
        val conversations = ConversationRepositoryImpl(db)
        val files = files(this)
        val useCase = SendFileMessageUseCase(files, messages, conversations)
        val stale = useCase.enqueue("bob", ChatType.SINGLE, byteArrayOf(1), "a.jpg", "image/jpeg", me, "bob")

        val pumps = CoroutineScope(coroutineContext + Job())
        SessionCoordinator(me, connection, messages, conversations, pumps, files = files) {}.start()
        connection.state.value = ConnectionState.CONNECTED
        withTimeout(5_000) { while (messages.message(stale.id)?.status != MessageStatus.FAILED) yield() }
        assertTrue(connection.sent.none { it is OutboundEnvelope.File }, "未上传的附件不能发出 local: ID")
        pumps.coroutineContext[Job]!!.cancel()
        messages.cancelPendingAcks()
    }

    @Test
    fun stickerGoesOutAsFileEnvelope() = runBlocking {
        val connection = FakeConnection()
        val messages = MessageRepositoryImpl(db, connection, this)
        val sent = messages.sendSticker("g_1", ChatType.GROUP, StickerRef("doge", "001", "png"), me)
        val envelope = connection.sent.single() as OutboundEnvelope.File
        assertEquals(MsgType.Sticker, envelope.message.msgType)
        assertEquals(StickerRef("doge", "001", "png"), StickerRef.decode(sent.content))
        messages.cancelPendingAcks()
    }
}
