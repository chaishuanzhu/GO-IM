package com.goim.kmp.repository

import com.goim.kmp.common.PlatformContext
import com.goim.kmp.common.userMessage
import com.goim.kmp.domain.FileDownloadEvent
import com.goim.kmp.domain.LocalMediaInfo
import com.goim.kmp.domain.SendFileMessageUseCase
import com.goim.kmp.domain.SendStickerUseCase
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.LocalFileId
import com.goim.kmp.model.MessagePreview
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.MsgType
import com.goim.kmp.model.StickerRef
import com.goim.kmp.model.TransportKind
import com.goim.kmp.network.GoImApi
import com.goim.kmp.network.createHttpClient
import com.goim.kmp.storage.AppDirectories
import com.goim.kmp.storage.DevicePreferencesStore
import com.goim.kmp.storage.InMemorySecureStore
import com.goim.kmp.storage.SessionStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * 对本地网关的端到端联调（M1 验收）。默认跳过；运行方式：
 * `CONFIG_PATH=... go run ./cmd/gateway/`（transport=both）后
 * `GOIM_E2E=1 ./gradlew :data:repository:desktopTest --tests '*GatewayE2ETest*'`。
 */
class GatewayE2ETest {
    private val baseUrl = System.getenv("GOIM_E2E_URL") ?: "http://127.0.0.1:8080"
    private val enabled = System.getenv("GOIM_E2E") == "1"

    private class Device(baseUrl: String) {
        val root = Files.createTempDirectory("goim-e2e").toOkioPath()
        val directories = AppDirectories(root)
        val device = DevicePreferencesStore(directories.deviceJson, FileSystem.SYSTEM)
        val settings = ServerSettingsRepositoryImpl(device).also { it.setApiBaseUrl(baseUrl) }
        val http = createHttpClient()
        val api = GoImApi(http) { settings.apiBaseUrl.value }
        val auth = AuthRepositoryImpl(api, SessionStore(InMemorySecureStore(), device))
        val manager = SessionManager(PlatformContext.INSTANCE, directories, http, api, settings, auth)

        suspend fun login(uid: String, transport: TransportKind): UserSession {
            settings.setPreferredTransport(transport)
            val user = auth.login(uid, "")
            auth.saveSession(user)
            manager.mount(user)
            return connected()
        }

        suspend fun connected(): UserSession {
            val session = manager.currentSession!!
            withTimeout(15_000) { session.connection.state.first { it == ConnectionState.CONNECTED } }
            return session
        }
    }

    private suspend fun awaitMessage(session: UserSession, conversationId: String, text: String) =
        withTimeout(10_000) {
            session.messages.observeMessages(conversationId, 50).first { list -> list.any { it.content == text } }
        }

    private suspend fun awaitSent(session: UserSession, id: String) = withTimeout(10_000) {
        while (session.messages.message(id)?.status != MessageStatus.SENT) delay(50)
    }

    @Test
    fun webSocketAndTcpExchangeOfflineAndSwitch() = runBlocking {
        if (!enabled) return@runBlocking
        val suffix = System.currentTimeMillis() % 1_000_000
        val aliceUid = "e2e_a_$suffix"
        val bobUid = "e2e_b_$suffix"
        val dm = ConversationId.dm(aliceUid, bobUid)
        val aliceDevice = Device(baseUrl)
        val bobDevice = Device(baseUrl)

        val alice = aliceDevice.login(aliceUid, TransportKind.WEB_SOCKET)
        var bob = bobDevice.login(bobUid, TransportKind.TCP)

        // WS → TCP
        val m1 = alice.messages.sendText(bobUid, ChatType.SINGLE, "hello over ws", alice.user)
        awaitSent(alice, m1.id)
        awaitMessage(bob, dm, "hello over ws")
        assertEquals(1, bob.conversations.conversation(dm)?.unreadCount)

        // TCP → WS
        val m2 = bob.messages.sendText(aliceUid, ChatType.SINGLE, "hello over tcp", bob.user)
        awaitSent(bob, m2.id)
        awaitMessage(alice, dm, "hello over tcp")

        // 离线：bob 登出后 alice 发消息，bob 重新挂载后补齐
        bobDevice.manager.logout()
        val m3 = alice.messages.sendText(bobUid, ChatType.SINGLE, "while you were away", alice.user)
        awaitSent(alice, m3.id)
        bobDevice.settings.setPreferredTransport(TransportKind.TCP)
        val bobUser = bob.user
        bobDevice.auth.saveSession(bobUser)
        bobDevice.manager.mount(bobUser)
        bob = bobDevice.connected()
        awaitMessage(bob, dm, "while you were away")
        assertTrue(bob.messages.countMessages(dm) >= 2, "本地库保留登出前的消息")

        // 切换传输：bob TCP → WS 后继续收发
        bob.connection.disconnect()
        bob.connection.connect(bob.user, TransportKind.WEB_SOCKET)
        withTimeout(15_000) { bob.connection.state.first { it == ConnectionState.CONNECTED } }
        val m4 = bob.messages.sendText(aliceUid, ChatType.SINGLE, "switched to ws", bob.user)
        awaitSent(bob, m4.id)
        awaitMessage(alice, dm, "switched to ws")

        aliceDevice.manager.logout()
        bobDevice.manager.logout()
    }

    private suspend fun awaitNotice(session: UserSession, conversationId: String, notice: String) =
        withTimeout(10_000) {
            session.messages.observeMessages(conversationId, 100).first { list ->
                list.any { MessagePreview.systemNotice(it.content) == notice }
            }
        }

    /** M2：建群 / 群通知 / 群聊双向 / 加群 / 邀请 / 退群 / 未读；好友与搜索在无 MySQL 网关上的降级。 */
    @Test
    fun groupsFriendsAndSearch() = runBlocking {
        if (!enabled) return@runBlocking
        val suffix = System.currentTimeMillis() % 1_000_000
        val aliceUid = "e2e_ga_$suffix"
        val bobUid = "e2e_gb_$suffix"
        val carolUid = "e2e_gc_$suffix"
        val daveUid = "e2e_gd_$suffix"
        val devices = List(4) { Device(baseUrl) }
        val alice = devices[0].login(aliceUid, TransportKind.WEB_SOCKET)
        val bob = devices[1].login(bobUid, TransportKind.TCP)
        val carol = devices[2].login(carolUid, TransportKind.WEB_SOCKET)
        val dave = devices[3].login(daveUid, TransportKind.TCP)

        // 建群并带上 bob：双方都看到加入通知，bob 的群列表经事件触发刷新
        val group = alice.groups.create("E2E 群 $suffix", listOf(bobUid))
        val gid = ConversationId.group(group.groupId)
        assertEquals("E2E 群 $suffix", alice.conversations.conversation(gid)?.title)
        awaitNotice(bob, gid, "$aliceUid 加入了群聊")
        withTimeout(10_000) { while (bob.groups.group(group.groupId) == null) delay(50) }
        assertEquals("E2E 群 $suffix", bob.conversations.conversation(gid)?.title)

        // 群聊双向（WS ⇄ TCP）
        val m1 = alice.messages.sendText(group.groupId, ChatType.GROUP, "hi group", alice.user)
        awaitSent(alice, m1.id)
        awaitMessage(bob, gid, "hi group")
        val m2 = bob.messages.sendText(group.groupId, ChatType.GROUP, "hi from bob", bob.user)
        awaitSent(bob, m2.id)
        awaitMessage(alice, gid, "hi from bob")
        // 未读：bob 未打开群聊，Tab 总未读与会话未读一致
        val bobUnread = bob.conversations.conversation(gid)!!.unreadCount
        assertTrue(bobUnread >= 1)
        assertEquals(bobUnread, bob.conversations.observeTotalUnread().first())

        // carol 按 ID 加群
        assertEquals(group.groupId, carol.groups.join(group.groupId)?.groupId)
        awaitNotice(alice, gid, "$carolUid 加入了群聊")
        assertEquals(
            setOf(aliceUid, bobUid, carolUid),
            alice.groups.members(group.groupId).map { it.uid }.toSet(),
        )

        // 仅群主可邀请
        val notOwner = runCatching { carol.groups.invite(group.groupId, daveUid) }.exceptionOrNull()
        assertEquals("仅群主可以执行此操作", notOwner?.userMessage())
        alice.groups.invite(group.groupId, daveUid)
        awaitNotice(alice, gid, "$daveUid 加入了群聊")
        // 服务端以被邀请人为 from 扇出并跳过 from，被邀请人收不到通知；靠群列表刷新发现新群
        assertTrue(dave.groups.refresh().any { it.groupId == group.groupId })

        // carol 退群：其它成员收到通知，carol 本地群列表移除、会话保留
        carol.groups.leave(group.groupId)
        awaitNotice(alice, gid, "$carolUid 离开了群聊")
        assertTrue(carol.groups.observeGroups().first().none { it.groupId == group.groupId })
        assertTrue(carol.conversations.conversation(gid) != null)

        // 本地网关未启用 MySQL：搜索降级为本地，好友请求给出明确错误
        val result = bob.search.search("hi group")
        assertTrue(result.isLocalFallback)
        assertEquals(gid, result.hits.single().conversationId)
        val friendError = runCatching { alice.friends.sendRequest(bobUid) }.exceptionOrNull()
        assertEquals("服务端未启用该功能", friendError?.userMessage())

        devices.forEach { it.manager.logout() }
    }

    /** 需在运行期间手动重启网关：断线 → reconnecting → 自动恢复并可继续收发。 */
    @Test
    fun reconnectsAfterGatewayRestart() = runBlocking {
        if (System.getenv("GOIM_E2E_RECONNECT") != "1") return@runBlocking
        val suffix = System.currentTimeMillis() % 1_000_000
        val device = Device(baseUrl)
        val session = device.login("e2e_r_$suffix", TransportKind.TCP)
        println("E2E: connected, restart the gateway now")
        withTimeout(120_000) { session.connection.state.first { it == ConnectionState.RECONNECTING } }
        println("E2E: reconnecting")
        withTimeout(120_000) { session.connection.state.first { it == ConnectionState.CONNECTED } }
        println("E2E: reconnected")
        val msg = session.messages.sendText("e2e_r_peer", ChatType.SINGLE, "after reconnect", session.user)
        awaitSent(session, msg.id)
        device.manager.logout()
    }

    /** M3：图片 / 语音经 `/upload` + CmdFile 往返，接收方下载字节一致；贴纸经 CmdFile(msg_type=6) 往返。 */
    @Test
    fun mediaUploadSendAndDownload() = runBlocking {
        if (!enabled) return@runBlocking
        val suffix = System.currentTimeMillis() % 1_000_000
        val aliceUid = "e2e_ma_$suffix"
        val bobUid = "e2e_mb_$suffix"
        val dm = ConversationId.dm(aliceUid, bobUid)
        val aliceDevice = Device(baseUrl)
        val bobDevice = Device(baseUrl)
        val alice = aliceDevice.login(aliceUid, TransportKind.WEB_SOCKET)
        val bob = bobDevice.login(bobUid, TransportKind.TCP)
        val sendFile = SendFileMessageUseCase(alice.files, alice.messages, alice.conversations)

        val png = java.io.ByteArrayOutputStream().also { out ->
            val img = java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB)
            javax.imageio.ImageIO.write(img, "png", out)
        }.toByteArray()
        val image = sendFile(bobUid, ChatType.SINGLE, png, "pic.png", "image/png", alice.user, null, LocalMediaInfo(64, 48))
        awaitSent(alice, image.id)
        val sentMeta = MessagePreview.decodeFileMeta(alice.messages.message(image.id)!!.content)!!
        assertTrue(!LocalFileId.isLocal(sentMeta.fileId), "content 已改写为远端 ID")
        assertEquals(64, sentMeta.width)
        assertEquals(48, sentMeta.height)
        assertTrue(alice.files.localPath(sentMeta.fileId) != null, "发送方提升后无需下载")

        val received = withTimeout(10_000) {
            bob.messages.observeMessages(dm, 50).first { list -> list.any { it.msgType == MsgType.Image } }
        }.first { it.msgType == MsgType.Image }
        val recvMeta = MessagePreview.decodeFileMeta(received.content)!!
        assertEquals(sentMeta.fileId, recvMeta.fileId)
        val events = bob.files.observeDownload(recvMeta.fileId, recvMeta.name).toList()
        val done = events.last() as FileDownloadEvent.Completed
        assertContentEquals(png, FileSystem.SYSTEM.read(done.path.toPath()) { readByteArray() })
        assertEquals(done.path, bob.files.ensureLocal(recvMeta.fileId, recvMeta.name), "已下载不再重复下载")

        val voiceBytes = ByteArray(2048) { (it % 251).toByte() }
        val voice = sendFile(bobUid, ChatType.SINGLE, voiceBytes, "voice.m4a", "audio/mp4", alice.user, null, LocalMediaInfo(duration = 3))
        awaitSent(alice, voice.id)
        val voiceRecv = withTimeout(10_000) {
            bob.messages.observeMessages(dm, 50).first { list -> list.any { it.msgType == MsgType.Voice } }
        }.first { it.msgType == MsgType.Voice }
        val voiceMeta = MessagePreview.decodeFileMeta(voiceRecv.content)!!
        assertEquals(3, voiceMeta.duration)
        assertTrue(voiceMeta.mime.startsWith("audio/"), "保留客户端 audio mime: ${voiceMeta.mime}")
        assertContentEquals(voiceBytes, bob.files.ensureLocal(voiceMeta.fileId, voiceMeta.name).let { FileSystem.SYSTEM.read(it.toPath()) { readByteArray() } })

        val sticker = StickerRef("capoo", "001-file_1437", "gif", 240, 240, "https://oss.chaisz.com/im-sticker-pack/capoo/001-file_1437.gif")
        val sent = SendStickerUseCase(alice.messages, alice.conversations, alice.stickers)(bobUid, ChatType.SINGLE, sticker, alice.user, null)
        awaitSent(alice, sent.id)
        val stickerRecv = withTimeout(10_000) {
            bob.messages.observeMessages(dm, 50).first { list -> list.any { it.msgType == MsgType.Sticker } }
        }.first { it.msgType == MsgType.Sticker }
        assertEquals(sticker, StickerRef.decode(stickerRecv.content))
        assertEquals(listOf(sticker), alice.stickers.recentStickers())

        aliceDevice.manager.logout()
        bobDevice.manager.logout()
    }
}
