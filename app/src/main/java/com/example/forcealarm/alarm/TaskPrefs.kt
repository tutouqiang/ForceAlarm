package com.example.forcealarm.alarm

import android.content.Context

/** 解锁任务配置：强制运行的软件 + 时长。
 *  【知识点】SharedPreferences = 系统自带键值存储，落在
 *  /data/data/包名/shared_prefs/，杀进程/重启都还在。
 *  存"一个设置"用它是杀鸡用牛刀里的牛刀——比 Room 轻得多。 */
object TaskPrefs {
    private const val FILE = "task_prefs"
    private const val KEY_PKG = "target_package"
    private const val KEY_MIN = "required_minutes"

    const val DEFAULT_PKG = "com.ss.android.ugc.aweme"
    const val DEFAULT_MIN = 10

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun targetPackage(ctx: Context): String =
        sp(ctx).getString(KEY_PKG, DEFAULT_PKG) ?: DEFAULT_PKG

    fun requiredMinutes(ctx: Context): Int =
        sp(ctx).getInt(KEY_MIN, DEFAULT_MIN).coerceIn(1, 120)

    fun requiredMs(ctx: Context): Long = requiredMinutes(ctx) * 60_000L

    fun save(ctx: Context, pkg: String, minutes: Int) {
        sp(ctx).edit().putString(KEY_PKG, pkg).putInt(KEY_MIN, minutes).apply()
    }

    /** 包名 → 应用显示名，取不到就退回包名尾段 */
    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.')
    }
}
