package com.geely.carplay.ui

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import androidx.multidex.MultiDexApplication
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.diagnostics.DiagnosticLog

/** 记录应用内最后处于前台的页面 供通话结束后恢复 */
object ForegroundScreens {
    @Volatile var current: Class<out Activity>? = null
}

/**
 * 进程入口 在窗口创建前同步应用主题
 * 冷启动窗口在 Activity attach 时解析 若等到 Activity onCreate 才切换会先闪一帧系统主题
 */
class CarPlayApplication : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        ThemeStore.load(this)
        AirPlayPersistence.purgeLegacyMfiSettings(this)
        // 详细日志开关随进程启动同步一次 设置页切换时再即时写入
        DiagnosticLog.verbose = AirPlayPersistence.loadDebugLogsEnabled(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                ForegroundScreens.current = activity.javaClass
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
