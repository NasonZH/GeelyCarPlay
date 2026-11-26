package com.geely.carplay.ui

import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ResponsiveLayoutTest {
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun phoneHomeKeepsActionsReachableWithLargeText() {
        useLargeText()
        val activity = Robolectric.buildActivity(HomeActivity::class.java).create()
        val root = content(activity.get())
        layout(root, 1920, 1080)
        assertEquals(View.GONE, root.findViewById<View>(R.id.home_preview).visibility)
        root.findViewById<ScrollView>(R.id.home_content_scroll).fullScroll(View.FOCUS_DOWN)
        assertInside(root, root.findViewById(R.id.home_connect_button))
        assertInside(root, root.findViewById(R.id.home_usb_button))
        assertInside(root, root.findViewById(R.id.home_hotspot_icon))
        assertEquals(1, root.findViewById<TextView>(R.id.home_device_list).maxLines)
        assertInside(root, root.findViewById(R.id.home_settings_button))
        assertInside(root, root.findViewById(R.id.home_exit))
        activity.destroy()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun phoneConnectLogPageKeepsActionsReachable() {
        useLargeText()
        val activity = Robolectric.buildActivity(ConnectActivity::class.java).create()
        val root = content(activity.get())
        layout(root, 1920, 1080)
        assertInside(root, root.findViewById(R.id.connect_log_scroll))
        assertInside(root, root.findViewById(R.id.connect_action))
        assertInside(root, root.findViewById(R.id.connect_back))
        assertTrue(root.findViewById<View>(R.id.connect_log_scroll).width > root.width / 2)
        activity.destroy()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun phoneSettingsCanReachLastCategory() {
        useLargeText()
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create()
        val root = content(activity.get())
        layout(root, 1920, 1080)
        val categories = root.findViewById<ViewGroup>(R.id.settings_categories)
        root.findViewById<ScrollView>(R.id.settings_sidebar_scroll).fullScroll(View.FOCUS_DOWN)
        assertInside(root, categories.getChildAt(categories.childCount - 1))
        assertInside(root, root.findViewById(R.id.settings_back))
        assertTrue(root.findViewById<View>(R.id.settings_scroll).width > root.width / 2)
        activity.destroy()
    }

    @Test
    @Config(qualifiers = "w1920dp-h720dp-land-mdpi")
    fun headUnitRetainsPreviewAndReachableActions() {
        val activity = Robolectric.buildActivity(HomeActivity::class.java).create()
        val root = content(activity.get())
        layout(root, 1920, 720)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.home_preview).visibility)
        assertInside(root, root.findViewById(R.id.home_preview))
        root.findViewById<ScrollView>(R.id.home_content_scroll).fullScroll(View.FOCUS_DOWN)
        assertInside(root, root.findViewById(R.id.home_connect_button))
        assertInside(root, root.findViewById(R.id.home_usb_button))
        assertInside(root, root.findViewById(R.id.home_hotspot_icon))
        assertEquals(1, root.findViewById<TextView>(R.id.home_device_list).maxLines)
        activity.destroy()
    }

    private fun useLargeText() {
        val resources = RuntimeEnvironment.getApplication().resources
        val configuration = Configuration(resources.configuration)
        configuration.fontScale = 1.5f
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun content(activity: android.app.Activity): ViewGroup =
        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup

    private fun layout(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    private fun bounds(root: ViewGroup, child: View): Rect {
        val rect = Rect(0, 0, child.width, child.height)
        root.offsetDescendantRectToMyCoords(child, rect)
        return rect
    }

    private fun assertInside(root: ViewGroup, child: View) {
        assertTrue("控件必须有实际尺寸", child.width > 0 && child.height > 0)
        assertTrue("控件应在屏幕范围内: ${bounds(root, child)}", Rect(0, 0, root.width, root.height).contains(bounds(root, child)))
    }
}
