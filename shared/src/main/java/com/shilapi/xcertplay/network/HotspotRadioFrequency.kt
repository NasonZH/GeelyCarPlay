package com.shilapi.xcertplay.network

/**
 * 只读热点接口当前频率 普通权限即可 不依赖 root
 * 供界面显示频段使用 读不到返回 null
 */
object HotspotRadioFrequency {

    private val interfaceNames = listOf("wlan0", "ap0", "swlan0", "softap0", "wlan1")
    private val bandHints = listOf("2.4 GHz", "5 GHz")

    fun read(): Int? {
        for (name in interfaceNames) {
            val reading = query(name, null)
            reading.frequencyMHz?.let { return it }
            // 只有驱动返回信道号时才需要频段提示 其它失败直接换下一个接口
            if (reading.error != LegacyHotspotRadio.AMBIGUOUS_CHANNEL) continue
            for (band in bandHints) {
                query(name, band).frequencyMHz?.let { return it }
            }
        }
        return null
    }

    private fun query(interfaceName: String, band: String?): LegacyHotspotRadio.Reading =
        runCatching { LegacyHotspotRadio.read(interfaceName, band) }
            .getOrElse { LegacyHotspotRadio.Reading(null, null) }
}
