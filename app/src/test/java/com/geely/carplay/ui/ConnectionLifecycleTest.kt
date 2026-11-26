package com.geely.carplay.ui

import android.app.Activity
import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayPreferences
import java.util.concurrent.TimeUnit
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConnectionLifecycleTest {
    @Before
    fun resetSession() {
        CarPlaySessionManager.stop()
    }

    @After
    fun stopSession() {
        CarPlaySessionManager.stop()
    }

    @Test
    fun pendingConnectionCanBeCancelledFromPage() {
        setSessionState("isRunning", true)
        val controller = Robolectric.buildActivity(ConnectActivity::class.java).setup()
        val activity = controller.get()
        val action = activity.findViewById<View>(R.id.connect_action)
        assertEquals(activity.getString(R.string.connect_cancel), action.contentDescription)

        action.performClick()

        assertFalse(CarPlaySessionManager.isRunning)
        assertFalse(CarPlaySessionManager.isConnected)
        assertFalse(CarPlaySessionManager.automaticConnectionAllowed)
        assertEquals(activity.getString(R.string.connect_cancelled), CarPlaySessionManager.statusText)
        controller.pause().stop().destroy()
    }

    @Test
    fun alreadyConnectedSessionCanBeEnteredManually() {
        setSessionState("isRunning", true)
        setSessionState("isConnected", true)
        val controller = Robolectric.buildActivity(ConnectActivity::class.java).setup()
        val activity = controller.get()

        assertEquals(activity.getString(R.string.btn_enter_carplay),
            activity.findViewById<TextView>(R.id.connect_action_label).text)
        assertNull(shadowOf(activity).nextStartedActivity)

        activity.findViewById<View>(R.id.connect_action).performClick()

        assertEquals(CarPlayActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
        controller.pause().stop().destroy()
    }

    @Test
    fun newConnectionEntersCarPlayOnceAndAllowsReturningToLogs() {
        setSessionState("isRunning", true)
        val controller = Robolectric.buildActivity(ConnectActivity::class.java).setup()
        val activity = controller.get()
        assertNull(shadowOf(activity).nextStartedActivity)

        setSessionState("isConnected", true)
        CarPlaySessionManager::class.java.getDeclaredMethod("changed").apply {
            isAccessible = true
        }.invoke(CarPlaySessionManager)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)

        assertEquals(CarPlayActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)

        controller.pause().resume()

        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun homeUsesGreenDeviceNamesAndHidesBluetoothDevicesAfterConnection() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).create()
        val activity = controller.get()
        val label = activity.findViewById<TextView>(R.id.home_connected_devices_label)
        val names = activity.findViewById<TextView>(R.id.home_device_list)
        val green = activity.resources.getColor(R.color.ok)
        val devices = listOf(
            ConnectedDevice("00:11:22:33:44:01", "周二哥", false),
            ConnectedDevice("00:11:22:33:44:02", "小仙女", false),
            ConnectedDevice("00:11:22:33:44:03", "先谢谢", false),
        )
        HomeActivity::class.java.getDeclaredMethod("renderDeviceList", List::class.java).apply {
            isAccessible = true
        }.invoke(activity, devices)

        assertEquals("周二哥 / 小仙女 / 先谢谢", names.text.toString())
        assertEquals(green, names.currentTextColor)
        assertEquals(activity.resources.getColor(R.color.text_secondary), label.currentTextColor)

        AirPlayPersistence.saveWirelessEnabled(activity, true)
        DiPlayPreferences.savePhone(activity, devices.first().address, devices.first().name)
        setSessionState("isConnected", true)
        val render = HomeActivity::class.java.getDeclaredMethod("renderConnectionState").apply {
            isAccessible = true
        }
        render.invoke(activity)

        assertEquals(View.GONE, label.visibility)
        assertEquals(View.GONE, names.visibility)
        val hint = activity.findViewById<TextView>(R.id.home_hint).text as Spanned
        val span = hint.getSpans(0, hint.length, ForegroundColorSpan::class.java).single()
        assertEquals(green, span.foregroundColor)
        assertEquals("周二哥", hint.subSequence(hint.getSpanStart(span), hint.getSpanEnd(span)).toString())

        setSessionState("isConnected", false)
        render.invoke(activity)
        assertEquals(View.VISIBLE, label.visibility)
        assertEquals(View.VISIBLE, names.visibility)
        controller.destroy()
    }

    @Test
    fun returningFromLogPageKeepsPendingSession() {
        setSessionState("isRunning", true)
        val controller = Robolectric.buildActivity(ConnectActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<View>(R.id.connect_back).performClick()

        assertTrue(activity.isFinishing)
        assertTrue(CarPlaySessionManager.isRunning)
        controller.pause().stop().destroy()
    }

    @Test
    fun cancellingPreparationIgnoresLateVpnConsent() {
        val controller = Robolectric.buildActivity(ConnectActivity::class.java).setup()
        val activity = controller.get()
        for (field in listOf("preparing", "vpnPending")) {
            ConnectActivity::class.java.getDeclaredField(field).apply {
                isAccessible = true
                setBoolean(activity, true)
            }
        }

        activity.findViewById<View>(R.id.connect_action).performClick()
        ConnectActivity::class.java.getDeclaredMethod("onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, android.content.Intent::class.java
        ).apply { isAccessible = true }.invoke(activity, 101, Activity.RESULT_OK, null)

        assertFalse(CarPlaySessionManager.isRunning)
        assertNull(shadowOf(activity).nextStartedService)
        controller.pause().stop().destroy()
    }

    @Test
    fun cancellationDiscardsQueuedCallbackFromOldAttempt() {
        val generationField = CarPlaySessionManager::class.java.getDeclaredField("generation").apply { isAccessible = true }
        val token = generationField.getInt(null)
        var callbackDelivered = false
        val callback: () -> Unit = { callbackDelivered = true }
        val deliver = CarPlaySessionManager::class.java.getDeclaredMethod(
            "deliver", Int::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java,
        ).apply { isAccessible = true }
        deliver.invoke(CarPlaySessionManager, token, callback)

        CarPlaySessionManager.cancelConnection(org.robolectric.RuntimeEnvironment.getApplication())
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(callbackDelivered)
    }

    @Test
    fun connectedSessionKeepsPlaceholderUntilPageReceivesFrame() {
        setSessionState("isRunning", true)
        setSessionState("isConnected", true)
        val controller = Robolectric.buildActivity(CarPlayActivity::class.java).setup()
        val activity = controller.get()
        val status = activity.findViewById<TextView>(R.id.carplay_status)

        assertEquals(View.VISIBLE, status.visibility)
        assertEquals(activity.getString(R.string.carplay_waiting_video), status.text)
        controller.pause().stop().destroy()
    }

    private fun setSessionState(name: String, value: Boolean) {
        CarPlaySessionManager::class.java.getDeclaredField(name).apply {
            isAccessible = true
            setBoolean(null, value)
        }
    }
}
