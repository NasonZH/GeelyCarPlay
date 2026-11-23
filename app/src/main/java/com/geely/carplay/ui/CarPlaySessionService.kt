package com.geely.carplay.ui

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** API18 前台服务负责在页面切换和返回车机后保留会话 */
class CarPlaySessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        val disconnect = PendingIntent.getService(this, 1,
            Intent(this, CarPlaySessionService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_UPDATE_CURRENT)
        if (intent?.action == ACTION_DISCONNECT) {
            CarPlaySessionManager.disconnect(this)
            return START_NOT_STICKY
        }
        startForeground(1, Notification.Builder(this)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("CarPlay 连接服务运行中")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "断开连接", disconnect)
            .build())
        CarPlaySessionManager.start(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (CarPlaySessionManager.isRunning) CarPlaySessionManager.stop()
        stopForeground(true)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        CarPlaySessionManager.disconnect(this)
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val ACTION_DISCONNECT = "com.geely.carplay.DISCONNECT"
    }
}
