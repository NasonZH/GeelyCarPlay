package com.geely.carplay.ui

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.network.CarHotspotTools
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** 用可控的 Wi-Fi 接口模拟系统限制, 不访问真实热点 */
@Implements(WifiManager::class)
class HotspotWifiManagerShadow {
    companion object {
        var configuration: WifiConfiguration? = null
        var state = CarHotspotTools.AOSP_STATE_ENABLED
        var enableRequests = 0
    }

    @Implementation
    fun getWifiApConfiguration(): WifiConfiguration? =
        configuration ?: throw SecurityException("测试系统拒绝读取配置")

    @Implementation
    fun getWifiApState(): Int = state

    @Implementation
    fun setWifiApEnabled(configuration: WifiConfiguration, enabled: Boolean): Boolean {
        enableRequests++
        state = if (enabled) CarHotspotTools.AOSP_STATE_ENABLED else CarHotspotTools.AOSP_STATE_DISABLED
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [HotspotWifiManagerShadow::class])
class HotspotSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        HotspotWifiManagerShadow.configuration = null
        HotspotWifiManagerShadow.state = CarHotspotTools.AOSP_STATE_ENABLED
        HotspotWifiManagerShadow.enableRequests = 0
    }

    @Test
    fun deniedReadUsesSavedInformationWithoutChangingSystemHotspot() {
        HotspotSettings.saveManual(context, "Test network", "test-password")
        val messages = mutableListOf<String>()
        HotspotSettings.prepare(context, messages::add)
        assertEquals("Test network", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("test-password", AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(0, HotspotWifiManagerShadow.enableRequests)
        assertTrue(messages.any { it.contains("PERMISSION_DENIED") })
        assertFalse(messages.any { it.contains("Test network") || it.contains("test-password") })
    }

    @Test
    fun deniedReadRequiresSavedInformation() {
        assertThrows(IllegalStateException::class.java) {
            HotspotSettings.prepare(context) { }
        }
        assertEquals(0, HotspotWifiManagerShadow.enableRequests)
    }

    @Test
    fun closedHotspotRequiresUserToEnableItWhenConfigurationIsRestricted() {
        HotspotSettings.saveManual(context, "Test network", "test-password")
        HotspotWifiManagerShadow.state = CarHotspotTools.AOSP_STATE_DISABLED
        val error = assertThrows(IllegalStateException::class.java) {
            HotspotSettings.prepare(context) { }
        }
        assertTrue(error.message.orEmpty().contains("系统设置"))
        assertEquals(0, HotspotWifiManagerShadow.enableRequests)
    }

    @Test
    @Config(sdk = [23])
    fun readableConfigurationRetainsLegacyAutomaticStartup() {
        HotspotWifiManagerShadow.configuration = WifiConfiguration().apply {
            SSID = "Detected network"
            preSharedKey = "detected-password"
            allowedKeyManagement.set(4)
        }
        HotspotWifiManagerShadow.state = CarHotspotTools.AOSP_STATE_DISABLED
        HotspotSettings.prepare(context) { }
        assertEquals(1, HotspotWifiManagerShadow.enableRequests)
        assertEquals("Detected network", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("detected-password", AirPlayPersistence.loadManualHotspotPassphrase(context))
    }

    @Test
    fun invalidPasswordDoesNotReplaceSavedInformation() {
        HotspotSettings.saveManual(context, "Original", "original-password")
        assertThrows(IllegalArgumentException::class.java) {
            HotspotSettings.saveManual(context, "Replacement", "short")
        }
        assertEquals("Original", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("original-password", AirPlayPersistence.loadManualHotspotPassphrase(context))
    }

    @Test
    fun spacesArePreservedAndOpenNetworksAreExplicitlyStored() {
        HotspotSettings.saveManual(context, " Test network ", " password ")
        assertEquals(" Test network ", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals(" password ", AirPlayPersistence.loadManualHotspotPassphrase(context))
        HotspotSettings.saveManual(context, "Open network", "")
        assertEquals(ManualHotspotSecurity.OPEN, AirPlayPersistence.loadManualHotspotSecurity(context))
        assertEquals(0, AirPlayPersistence.loadManualHotspotPassphrase(context).length)
    }

    @Test
    fun longUtf8NameIsRejectedBeforeSaving() {
        assertThrows(IllegalArgumentException::class.java) {
            HotspotSettings.saveManual(context, "热点名称".repeat(3), "test-password")
        }
        assertEquals(0, AirPlayPersistence.loadManualHotspotSsid(context).length)
    }
}
