package com.geely.carplay.ui

import androidx.multidex.MultiDexApplication

/**
 * 进程入口 在窗口创建前同步应用主题
 * 冷启动窗口在 Activity attach 时解析 若等到 Activity onCreate 才切换会先闪一帧系统主题
 */
class CarPlayApplication : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        ThemeStore.load(this)
    }
}
