package com.geely.carplay.ui

import androidx.multidex.MultiDexApplication
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.diagnostics.DiagnosticLog

/**
 * 进程入口 在窗口创建前同步应用主题
 * 冷启动窗口在 Activity attach 时解析 若等到 Activity onCreate 才切换会先闪一帧系统主题
 */
class CarPlayApplication : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        ThemeStore.load(this)
        // 详细日志开关随进程启动同步一次 设置页切换时再即时写入
        DiagnosticLog.verbose = AirPlayPersistence.loadDebugLogsEnabled(this)
    }
}
