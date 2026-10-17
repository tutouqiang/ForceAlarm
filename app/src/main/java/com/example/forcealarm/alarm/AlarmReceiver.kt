package com.example.forcealarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.forcealarm.ui.LockScreenActivity

class AlarmReceiver : BroadcastReceiver() {

    // 系统到点后回调这里。onReceive 必须 10 秒内干完，不能做耗时活
    override fun onReceive(context: Context, intent: Intent) {

        // 从 data URI 找回是哪只闹钟到点了（forcealarm://alarm/<id>）
        val id = intent.data?.lastPathSegment?.toIntOrNull()

        // 通道 1：全屏意图通知（标准 Android 的官方门，MIUI 有时仍会拦它）
        AlarmNotifier.ring(context)

        // 通道 2：直接弹出闹钟界面（双保险，走 appops 10022 那扇门）
        try {
            val show = Intent(context, LockScreenActivity::class.java)
            show.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            context.startActivity(show)
        } catch (_: Exception) {
            // 被系统拦截也没关系，还有通道 1 的通知在
        }

        // 【单次/重复的分叉】响过之后给这只闹钟安排后事：
        // 重复闹钟 → 重新 schedule，自动排到下一个匹配日；
        // 单次闹钟 → 自动关闸（enabled=false），系统预约也一并撤掉
        if (id != null) {
            val alarm = AlarmStore.find(context, id) ?: return
            if (alarm.daysMask == 0) {
                AlarmStore.setEnabled(context, id, false)
                AlarmScheduler.cancel(context, id)
            } else {
                AlarmScheduler.schedule(context, alarm)
            }
        }
    }
}
