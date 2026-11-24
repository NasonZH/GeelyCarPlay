// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.diagnostics

/**
 * 设置页的详细日志开关 由设置项持久化后同步到这里
 *
 * 默认关闭 只影响触摸与 RTSP 收发摘要这类明细行 统计 阶段和关闭诊断始终输出
 * 与 wire 级十六进制转储的 AirPlayTrace 相互独立 排查具体协议内容时才需要同时打开
 */
object DiagnosticLog {
    @Volatile
    var verbose: Boolean = false
}
