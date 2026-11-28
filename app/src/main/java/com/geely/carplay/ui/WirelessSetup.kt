package com.geely.carplay.ui

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock

/**
 * 车机的 Wi-Fi 客户端与热点不能同时工作
 *
 * 启动热点前先关闭无线客户端，并等待系统完成切换。
 */
object WirelessSetup {
    fun disableWifiClient(context: Context, onReport: (String) -> Unit = {}): Boolean {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        if (!wifi.isWifiEnabled) return true
        onReport("关闭无线客户端以启动热点")
        val requested = runCatching { wifi.isWifiEnabled = false }.isSuccess
        if (!requested) {
            onReport("关闭无线失败")
            return false
        }
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        while (wifi.isWifiEnabled && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        val disabled = !wifi.isWifiEnabled
        onReport(if (disabled) "无线已关闭" else "等待无线关闭超时")
        return disabled
    }

    private const val TIMEOUT_MILLIS = 10_000L
    private const val POLL_MILLIS = 200L
}
