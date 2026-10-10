package com.goim.kmp.common

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val severity: Severity,
    val tag: String,
    val message: String,
)

/** 开发者工具读取的环形日志缓冲（最近 [capacity] 条）。 */
class LogBuffer(private val capacity: Int = DEFAULT_CAPACITY) : LogWriter() {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val text = if (throwable == null) message else "$message\n${throwable.stackTraceToString().lineSequence().take(8).joinToString("\n")}"
        _entries.update { current ->
            val next = LogEntry((current.lastOrNull()?.id ?: 0) + 1, nowMillis(), severity, tag, text)
            if (current.size >= capacity) current.drop(current.size - capacity + 1) + next else current + next
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 2_000

        /** 进程级实例；[install] 后所有 Kermit 日志同时写入系统日志与本缓冲。 */
        val shared = LogBuffer()

        fun install() {
            Logger.setLogWriters(platformLogWriter(), shared)
        }
    }
}
