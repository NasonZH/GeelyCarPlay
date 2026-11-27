package com.geely.carplay.ui

import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.DiagnosticRedactor
import com.shilapi.xcertplay.diagnostics.DiagnosticLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 保存最近的脱敏日志 供连接页和报告读取快照 */
internal class CarPlayLogBuffer {
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private var burstWindowStartMs = 0L
    private var burstCount = 0
    private var burstSuppressed = 0

    /** 实际写入至少一行时返回 true */
    fun append(message: String): Boolean = synchronized(lock) {
        var summaryAdded = false
        if (!DiagnosticLog.verbose && !isCriticalLog(message)) {
            val now = SystemClock.elapsedRealtime()
            if (now - burstWindowStartMs >= 1_000L) {
                burstWindowStartMs = now
                burstCount = 0
                if (burstSuppressed > 0) {
                    summaryAdded = writeLine("日志采样: 上一秒省略 $burstSuppressed 条高频日志")
                    burstSuppressed = 0
                }
            }
            if (burstCount >= BURST_LIMIT_PER_SECOND) {
                burstSuppressed++
                return@synchronized summaryAdded
            }
            burstCount++
        }
        writeLine(message) || summaryAdded
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }

    private fun isCriticalLog(message: String): Boolean =
        message.contains("失败") || message.contains("错误") || message.contains("异常") ||
            message.contains("超时") || message.contains("断开") || message.contains("取消") ||
            message.contains("通话") ||
            message.startsWith("连接时间线") || message.startsWith("会话诊断") ||
            message.startsWith("按键诊断") ||
            message.startsWith("链路诊断") || message.startsWith("画布 ") ||
            message.startsWith("音频能力")

    private fun writeLine(message: String): Boolean {
        val safeMessage = DiagnosticRedactor.redact(message) ?: return false
        Log.i("GeelyCarPlay", safeMessage)
        val time = timeFormat.format(Date())
        lines.addLast("$time  $safeMessage")
        while (lines.size > MAX_LINES) lines.removeFirst()
        return true
    }

    private companion object {
        const val BURST_LIMIT_PER_SECOND = 12
        const val MAX_LINES = 150
    }
}
