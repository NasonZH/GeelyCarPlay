package com.geely.carplay.ui

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Implements(WifiManager::class)
class StartupWifiManagerShadow {
    companion object {
        var requested = CountDownLatch(1)
        var configuration: WifiConfiguration? = null
    }

    @Implementation
    fun getWifiApState(): Int = 11

    @Implementation
    fun setWifiApEnabled(config: WifiConfiguration?, enabled: Boolean): Boolean {
        configuration = config
        requested.countDown()
        check(enabled)
        return false
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [StartupWifiManagerShadow::class])
class StartupConnectionsTest {
    @Before
    fun resetStartup() {
        StartupWifiManagerShadow.requested = CountDownLatch(1)
        StartupWifiManagerShadow.configuration = null
        CarPlaySessionManager::class.java.getDeclaredField("isExiting").apply {
            isAccessible = true
            setBoolean(null, false)
        }
    }

    @Test
    fun splashNoLongerEnablesHotspotAndStillOpensHome() {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        while (app.nextStartedActivity != null) { }
        val controller = Robolectric.buildActivity(SplashActivity::class.java).setup()

        assertFalse(
            "启动页不应自动请求开启热点",
            StartupWifiManagerShadow.requested.await(500, TimeUnit.MILLISECONDS),
        )
        assertNull(StartupWifiManagerShadow.configuration)

        shadowOf(Looper.getMainLooper()).idleFor(1800, TimeUnit.MILLISECONDS)
        assertEquals(HomeActivity::class.java.name, app.nextStartedActivity.component?.className)
        controller.pause().stop().destroy()
    }
}
