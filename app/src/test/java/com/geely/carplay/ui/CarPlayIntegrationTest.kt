package com.geely.carplay.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.geely.carplay.ui.widget.CarPlaySwitch
import com.geely.carplay.ui.widget.SettingRowView
import com.shilapi.xcertplay.AirPlayPersistence
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        for (name in listOf("xcertplay_airplay", "diplay", "ui_prefs")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun audioSettingSurvivesActivityRecreation() {
        AirPlayPersistence.saveGeelyAudioRouting(context, true)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val root = activity.get().findViewById<ViewGroup>(R.id.settings_groups)
        val row = children(root).filterIsInstance<SettingRowView>().first {
            it.findViewById<TextView>(R.id.row_title).text == context.getString(R.string.settings_geely_audio)
        }
        row.performClick()
        assertFalse(AirPlayPersistence.loadGeelyAudioRouting(context))
        activity.recreate()
        val recreated = activity.get().findViewById<ViewGroup>(R.id.settings_groups)
        val persistedRow = children(recreated).filterIsInstance<SettingRowView>().first {
            it.findViewById<TextView>(R.id.row_title).text == context.getString(R.string.settings_geely_audio)
        }
        assertFalse(persistedRow.findViewById<CarPlaySwitch>(R.id.row_switch).isChecked)
        activity.pause().stop().destroy()
    }

    @Test
    fun bootLaunchRequiresExplicitSetting() {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        while (app.nextStartedActivity != null) { }
        val receiver = BootReceiver()
        val boot = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, boot)
        assertNull(app.nextStartedActivity)
        AirPlayPersistence.saveAutoStartOnBoot(context, true)
        receiver.onReceive(context, boot)
        assertEquals(SplashActivity::class.java.name, app.nextStartedActivity.component?.className)
    }

    @Test
    fun oldPrototypeConnectionFlagDoesNotCreateRealSession() {
        context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("carplay_connected", true).commit()
        assertFalse(CarPlaySessionManager.isConnected)
        assertFalse(CarPlaySessionManager.isRunning)
    }

    private fun children(group: ViewGroup): List<View> =
        (0 until group.childCount).map { group.getChildAt(it) }
}
