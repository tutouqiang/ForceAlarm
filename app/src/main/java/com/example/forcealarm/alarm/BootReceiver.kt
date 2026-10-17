package com.example.forcealarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 系统重启会抹掉所有 AlarmManager 预约（闹钟列表里看不出来，但全丢了）。
 *  开机后收到 BOOT_COMPLETED 广播 → 把存储里的闹钟全部重新登记。
 *  MIUI 上这条路由【自启动】权限（appops 10021）把关，我们已焊 allow。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmScheduler.rescheduleAll(context)
        }
    }
}
