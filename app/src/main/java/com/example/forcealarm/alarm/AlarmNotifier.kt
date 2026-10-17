package com.example.forcealarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.example.forcealarm.R
import com.example.forcealarm.ui.LockScreenActivity

object AlarmNotifier {
    // 【重要知识点】NotificationChannel 创建后，铃声/重要级等配置就被锁死，
    // 改代码再运行也不会生效！想改配置要么改渠道 ID 让系统当成"新渠道"重建，
    // 要么卸载重装/清除应用数据。所以调整渠道参数时把 id 后缀 +v2、+v3……
    private const val CHANNEL_ID = "alarm_fire_v2"
    private const val NOTIFY_ID = 42

    fun ring(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val channel = NotificationChannel(
            CHANNEL_ID, "闹钟响起", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "到点全屏弹出闹钟"
            // USAGE_ALARM：声音走"闹钟"音量通道，媒体静音也照样响
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            // 锁屏上显示完整内容
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)

        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, LockScreenActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("该起床了！")
            .setContentText("刷满 10 分钟抖音才能关闭")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(tapIntent, true)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFY_ID, notification)
    }
}
