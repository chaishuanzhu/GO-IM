package com.goim.kmp.designsystem

import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.TransportKind
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** 时间 / 文案格式，对齐 iOS `GOIMFormat`。 */
@OptIn(ExperimentalTime::class)
object GoImFormat {

    /** 气泡时间：今天 `HH:mm`、昨天 `昨天 HH:mm`、今年 `M/d HH:mm`、更早 `yyyy/M/d`。 */
    fun messageTime(ms: Long, nowMs: Long = Clock.System.now().toEpochMilliseconds()): String {
        val (date, today) = localPair(ms, nowMs)
        return when {
            date.date == today.date -> hm(date)
            date.date == today.date.minus(DatePeriod(days = 1)) -> "昨天 ${hm(date)}"
            date.year == today.year -> "${date.month.ordinal + 1}/${date.day} ${hm(date)}"
            else -> ymd(date)
        }
    }

    /** 会话列表时间：今天 `HH:mm`、`昨天`、今年 `M/d`、更早 `yyyy/M/d`。 */
    fun conversationTime(ms: Long, nowMs: Long = Clock.System.now().toEpochMilliseconds()): String {
        if (ms <= 0) return ""
        val (date, today) = localPair(ms, nowMs)
        return when {
            date.date == today.date -> hm(date)
            date.date == today.date.minus(DatePeriod(days = 1)) -> "昨天"
            date.year == today.year -> "${date.month.ordinal + 1}/${date.day}"
            else -> ymd(date)
        }
    }

    /** 日志时间 `HH:mm:ss.SSS`。 */
    fun logTime(ms: Long): String {
        val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
        return "${hm(d)}:${d.second.toString().padStart(2, '0')}.${(d.nanosecond / 1_000_000).toString().padStart(3, '0')}"
    }

    fun monogram(title: String): String = title.trim().firstOrNull()?.uppercase() ?: "?"

    fun unreadBadge(count: Int): String = if (count > 99) "99+" else count.toString()

    fun fileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${oneDecimal(bytes / 1024.0)} KB"
        else -> "${oneDecimal(bytes / (1024.0 * 1024.0))} MB"
    }

    private fun oneDecimal(v: Double): String {
        val tenths = kotlin.math.round(v * 10).toLong()
        return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    }

    private fun localPair(ms: Long, nowMs: Long): Pair<LocalDateTime, LocalDateTime> {
        val tz = TimeZone.currentSystemDefault()
        return Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz) to Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz)
    }

    private fun hm(d: LocalDateTime) = "${d.hour.toString().padStart(2, '0')}:${d.minute.toString().padStart(2, '0')}"

    private fun ymd(d: LocalDateTime) = "${d.year}/${d.month.ordinal + 1}/${d.day}"
}

val ConnectionState.title: String
    get() = when (this) {
        ConnectionState.DISCONNECTED -> "已断开"
        ConnectionState.CONNECTING -> "连接中…"
        ConnectionState.CONNECTED -> "已连接"
        ConnectionState.RECONNECTING -> "重连中…"
        ConnectionState.AUTH_EXPIRED -> "登录已失效"
    }

val TransportKind.displayName: String get() = title

/** 完整 http(s) URL（与 iOS 设置页校验一致：必须有 scheme 与 host）。 */
fun isValidApiBaseUrl(text: String): Boolean =
    Regex("^https?://[^\\s/:?#]+(:\\d{1,5})?(/\\S*)?$", RegexOption.IGNORE_CASE).matches(text.trim())
