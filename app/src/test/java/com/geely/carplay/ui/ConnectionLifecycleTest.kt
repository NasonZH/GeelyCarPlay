package com.geely.carplay.ui

import android.os.Looper
import android.view.View
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
