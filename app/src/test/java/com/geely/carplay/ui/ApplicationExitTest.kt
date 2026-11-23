package com.geely.carplay.ui

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApplicationExitTest {
    @Before
    fun resetSession() {
        setSessionState("isExiting", false)
        CarPlaySessionManager.stop()
    }

    @After
    fun clearSession() {
        setSessionState("isExiting", false)
        CarPlaySessionManager.stop()
    }

    @Test
    fun pendingConnectionRequiresConfirmationAndCancelKeepsItRunning() {
        setSessionState("isRunning", true)
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<View>(R.id.home_exit).performClick()

        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.exit_disconnect_message), dialog.findViewById<TextView>(android.R.id.message).text)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        assertTrue(CarPlaySessionManager.isRunning)
        assertFalse(CarPlaySessionManager.isExiting)
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun connectedSessionRequiresConfirmation() {
        setSessionState("isConnected", true)
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()

        controller.get().findViewById<View>(R.id.home_exit).performClick()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun confirmedExitClearsOldTaskBeforeProcessTermination() {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        while (app.nextStartedActivity != null) { }
        setSessionState("isRunning", true)
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        controller.get().findViewById<View>(R.id.home_exit).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS)

        val exitIntent = app.nextStartedActivity
        assertNotNull(exitIntent)
        assertEquals(HomeActivity.ACTION_EXIT, exitIntent.action)
        assertEquals(HomeActivity::class.java.name, exitIntent.component?.className)
        assertTrue(exitIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(exitIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        controller.pause().stop().destroy()
    }

    @Test
    fun exitStopsSessionBlocksReconnectAndCompletesOnlyOnce() {
        val context = RuntimeEnvironment.getApplication()
        val app = shadowOf(context)
        while (app.nextStartedService != null) { }
        setSessionState("isRunning", true)
        var completions = 0

        CarPlaySessionManager.prepareExit(context) { completions++ }
        CarPlaySessionManager.prepareExit(context) { fail("退出回调不能重复注册") }
        CarPlaySessionManager.connect(context)

        assertTrue(CarPlaySessionManager.isExiting)
        assertFalse(CarPlaySessionManager.isRunning)
        assertFalse(CarPlaySessionManager.automaticConnectionAllowed)
        assertNull(app.nextStartedService)
        shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS)
        assertEquals(1, completions)
    }

    private fun setSessionState(name: String, value: Boolean) {
        CarPlaySessionManager::class.java.getDeclaredField(name).apply {
            isAccessible = true
            setBoolean(null, value)
        }
    }
}
