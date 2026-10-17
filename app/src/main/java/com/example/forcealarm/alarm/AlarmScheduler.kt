package com.example.forcealarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

object AlarmScheduler {

    /** 每个闹钟各自的预约单：requestCode 用闹钟 id，并把 id 塞进 data URI。
     *  【大坑】FLAG_IMMUTABLE 的 PendingIntent 判"是不是同一张"时只看
     *  action/data/type/class/flags/categories，【不看】extras！
     *  只把 id 放 extra 会让所有闹钟共用一张预约单、互相覆盖——必须用 data 区分。 */
    private fun broadcastIntent(ctx: Context, id: Int) =
        Intent(ctx, AlarmReceiver::class.java).apply {
            data = Uri.parse("forcealarm://alarm/$id")
        }

    private fun pendingIntent(ctx: Context, id: Int) = PendingIntent.getBroadcast(
        ctx, id, broadcastIntent(ctx, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** 注册一个闹钟到系统；已禁用或算不出下次时间 → 撤销它的预约 */
    fun schedule(ctx: Context, alarm: Alarm) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx, alarm.id)
        val next = AlarmStore.computeNext(alarm)
        if (next == null) {
            am.cancel(pi); pi.cancel()
            return
        }
        // 【防崩】MIUI 重装会把精确闹钟 appops 重置为 deny，
        // 没权限时 setAlarmClock 直接抛 SecurityException。跳过排期（闹钟
        // 列表里仍在但不响），交给首页权限引导面板提示用户开启
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Log.w("AlarmScheduler", "缺精确闹钟权限，跳过排期 #${alarm.id}")
            return
        }
        // setAlarmClock：闹钟级特权，穿透 Doze，状态栏亮小闹钟图标
        am.setAlarmClock(AlarmManager.AlarmClockInfo(next, pi), pi)
    }

    /** 撤销某个闹钟的系统预约（存储里可还留着，只是不再响）。
     *  【坑】Android 12+ 只要 flags 里没有可变性标志就抱异常，FLAG_NO_CREATE 也不例外——
     *  必须 or上 FLAG_IMMUTABLE，哪怕它压根不创建 */
    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // FLAG_NO_CREATE：只取回已存在的预约单，没有就返回 null，不凭空造
        val existing = PendingIntent.getBroadcast(
            ctx, id, broadcastIntent(ctx, id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        am.cancel(existing)
        existing.cancel()
    }

    /** 把存储里所有闹钟与系统对账（启用→注册，禁用→撤销）。
     *  开机恢复、批量改动后的"总闸"。 */
    fun rescheduleAll(ctx: Context) {
        AlarmStore.list(ctx).forEach { a ->
            if (a.enabled) schedule(ctx, a) else cancel(ctx, a.id)
        }
    }

    /** 清理旧版单闹钟时代的预约（requestCode=100、无 data URI）。
     *  升级后它不在新列表里但仍在系统里挂着，到点照样会响。 */
    fun cancelLegacy(ctx: Context) {
        val legacy = PendingIntent.getBroadcast(
            ctx, 100, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(legacy)
        legacy.cancel()
    }
}
