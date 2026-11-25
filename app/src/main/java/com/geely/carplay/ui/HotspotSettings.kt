package com.geely.carplay.ui

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.network.CarHotspotConfiguration
import com.shilapi.xcertplay.network.CarHotspotFailure
import com.shilapi.xcertplay.network.CarHotspotReadResult
import com.shilapi.xcertplay.network.CarHotspotStartResult
import com.shilapi.xcertplay.network.CarHotspotState
import com.shilapi.xcertplay.network.CarHotspotTools
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity

/** 自动读取与手动填写共用同一份配置, 不修改系统保存的热点信息 */
internal object HotspotSettings {
    fun saveManual(context: Context, ssid: String, passphrase: String) {
        validateManual(ssid, passphrase)
        AirPlayPersistence.saveManualHotspotProfile(
            context, ssid, passphrase,
            if (passphrase.length == 0) ManualHotspotSecurity.OPEN else ManualHotspotSecurity.WPA2,
            ManualHotspotBand.AUTO, 0,
        )
    }

    fun saveDetected(context: Context, configuration: CarHotspotConfiguration) {
        AirPlayPersistence.saveManualHotspotProfile(
            context, configuration.ssid, configuration.passphrase, configuration.security,
            configuration.band ?: ManualHotspotBand.AUTO, configuration.channel ?: 0,
        )
    }

    /** 由会话工作线程调用, 不在主线程访问车机热点接口 */
    fun prepare(context: Context, report: (String) -> Unit) {
        val result = CarHotspotTools.read(context)
        when (result) {
            is CarHotspotReadResult.Available -> saveDetected(context, result.configuration)
            is CarHotspotReadResult.Failure -> {
                report(failureMessage(result.reason))
                check(AirPlayPersistence.loadManualHotspotSsid(context).isNotBlank()) {
                    "系统无法读取热点配置, 请在设置页填写热点信息"
                }
                validateManual(
                    AirPlayPersistence.loadManualHotspotSsid(context),
                    AirPlayPersistence.loadManualHotspotPassphrase(context),
                )
                report("使用已保存的手动热点配置")
            }
        }

        val state = CarHotspotTools.readState(context)
        report("热点状态: ${state?.name ?: "UNKNOWN"}")
        when (state) {
            CarHotspotState.ENABLED -> report("热点已开启, 继续建立连接")
            CarHotspotState.ENABLING -> report("热点正在开启, 等待网络接口就绪")
            CarHotspotState.DISABLED -> {
                check(result is CarHotspotReadResult.Available) {
                    "请先在系统设置中开启热点, 再返回应用连接"
                }
                val started = CarHotspotTools.start(context)
                if (started is CarHotspotStartResult.Failure) {
                    report(failureMessage(started.reason))
                    error("应用无法开启热点, 请在系统设置中手动开启后重试")
                }
                report("热点已开启, 继续建立连接")
            }
            CarHotspotState.DISABLING -> error("热点正在关闭, 请重新开启后再连接")
            CarHotspotState.FAILED -> error("系统热点启动失败, 请在系统设置中重新开启")
            CarHotspotState.UNKNOWN, null ->
                report("无法确认热点开关状态, 将等待已开启的热点网络接口")
        }
    }

    fun failureMessage(reason: CarHotspotFailure): String {
        val description = when (reason) {
            CarHotspotFailure.PERMISSION_DENIED -> "系统不允许读取或操作热点配置"
            CarHotspotFailure.CONFIGURATION_UNAVAILABLE -> "系统未提供热点配置"
            CarHotspotFailure.UNSUPPORTED_CONFIGURATION -> "系统热点配置无法解析"
            CarHotspotFailure.OPERATION_FAILED -> "热点接口调用失败"
            CarHotspotFailure.STATE_UNAVAILABLE -> "无法读取热点开关状态"
            CarHotspotFailure.STATE_TRANSITION_IN_PROGRESS -> "热点正在切换状态"
            CarHotspotFailure.FIRMWARE_REPORTED_FAILURE -> "系统报告热点启动失败"
            CarHotspotFailure.REQUEST_REJECTED -> "系统拒绝开启热点"
            CarHotspotFailure.START_TIMED_OUT -> "等待热点开启超时"
            CarHotspotFailure.OPERATION_CANCELLED -> "热点操作已取消"
            CarHotspotFailure.OPERATION_INTERRUPTED -> "热点操作被中断"
        }
        return "$description (${reason.name})"
    }

    fun logReadFailure(reason: CarHotspotFailure) {
        Log.w("GeelyCarPlay", "热点配置读取失败: ${failureMessage(reason)}")
    }

    private fun validateManual(ssid: String, passphrase: String) {
        require(ssid.isNotBlank()) { "热点名称不能为空" }
        require('\u0000' !in ssid && ssid.toByteArray(Charsets.UTF_8).size <= 32) {
            "热点名称最多为 32 字节, 且不能包含空字符"
        }
        require('\u0000' !in passphrase && (passphrase.length == 0 || passphrase.length in 8..63)) {
            "热点密码需为 8–63 个字符, 无密码热点请留空"
        }
    }
}
