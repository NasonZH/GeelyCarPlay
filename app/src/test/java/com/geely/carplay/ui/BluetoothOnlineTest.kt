package com.geely.carplay.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BluetoothOnlineTest {
    private lateinit var context: Context
    private lateinit var adapter: BluetoothAdapter

    @Before
    fun prepareBluetooth() {
        context = RuntimeEnvironment.getApplication()
        adapter = requireNotNull(BluetoothAdapter.getDefaultAdapter())
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        AirPlayPersistence.saveGeelyBluetoothConnectionEnabled(context, false)
        AirPlayPersistence.saveWirelessEnabled(context, true)
        CarPlaySessionManager.stop()
    }

    @After
    fun stopSession() {
        CarPlaySessionManager.stop()
    }

    @Test
    fun listsOnlyConnectedPhonesWhenPhoneClassIsKnown() {
        val phone = connectedDevice("00:11:22:33:44:01", "Pixel")
        val phoneClass = BluetoothClass::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(BluetoothClass.Device.PHONE_SMART)
        shadowOf(phone).setBluetoothClass(phoneClass)
        val headphones = connectedDevice("00:11:22:33:44:02", "耳机")
        val offline = connectedDevice("00:11:22:33:44:03", "iPhone 离线")
        shadowOf(offline).setConnected(false)
        shadowOf(adapter).setBondedDevices(setOf(phone, headphones, offline))

        val devices = BluetoothOnline.connectedDevices(context)

        assertEquals(listOf(phone.address), devices.map { it.address })
    }

    @Test
    fun fallsBackToAllConnectedDevicesWhenNoPhoneIsRecognized() {
        val first = connectedDevice("00:11:22:33:44:01", "A")
        val second = connectedDevice("00:11:22:33:44:02", "B")
        shadowOf(adapter).setBondedDevices(setOf(second, first))

        assertEquals(listOf(first.address, second.address),
            BluetoothOnline.connectedDevices(context).map { it.address })
    }

    @Test
    fun currentStreamingPhoneComesBeforeNameOrder() {
        val first = connectedDevice("00:11:22:33:44:01", "iPhone A")
        val current = connectedDevice("00:11:22:33:44:02", "iPhone Z")
        shadowOf(adapter).setBondedDevices(setOf(first, current))
        DiPlayPreferences.savePhone(context, current.address, current.name)
        CarPlaySessionManager::class.java.getDeclaredField("isConnected").apply {
            isAccessible = true
            setBoolean(null, true)
        }

        val devices = BluetoothOnline.connectedDevices(context)

        assertEquals(listOf(current.address, first.address), devices.map { it.address })
        assertTrue(devices.first().isCurrent)
    }

    private fun connectedDevice(address: String, name: String): BluetoothDevice {
        return adapter.getRemoteDevice(address).also {
            shadowOf(it).setName(name)
            shadowOf(it).setConnected(true)
        }
    }
}
