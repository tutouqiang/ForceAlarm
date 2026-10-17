package com.example.forcealarm.alarm

import android.content.Context
import java.util.Calendar

/** 一个闹钟。daysMask 低 7 位：bit0=周一 … bit6=周日，0=不重复（只响一次） */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val daysMask: Int,
    val enabled: Boolean
)

/**
 * 闹钟列表存储。
 * 【设计取舍】字段全是整数/布尔，用 SharedPreferences + 紧凑字符串编码
 * （每条 "id:hour:minute:mask:enabled"，条目用 ';' 分隔），不引 Room——
 * 杀鸡不用牛刀。以后要迁移，仓储接口都在这一个文件里。
 */
object AlarmStore {
    private const val FILE = "alarm_list"
    private const val KEY = "***"

    val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")  // bit0=周一

    fun list(ctx: Context): MutableList<Alarm> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split(";").mapNotNull { line ->
            val p = line.split(":")
            if (p.size < 5) return@mapNotNull null
            Alarm(
                id = p[0].toIntOrNull() ?: return@mapNotNull null,
                hour = p[1].toIntOrNull() ?: 0,
                minute = p[2].toIntOrNull() ?: 0,
                daysMask = p[3].toIntOrNull() ?: 0,
                enabled = p[4] == "1"
            )
        }.toMutableList()
    }

    private fun persist(ctx: Context, alarms: List<Alarm>) {
        val raw = alarms.joinToString(";") {
            "${it.id}:${it.hour}:${it.minute}:${it.daysMask}:${if (it.enabled) 1 else 0}"
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, raw).apply()
    }

    fun find(ctx: Context, id: Int): Alarm? = list(ctx).firstOrNull { it.id == id }

    fun nextId(ctx: Context): Int = (list(ctx).maxOfOrNull { it.id } ?: 0) + 1

    /** 新增或更新 */
    fun upsert(ctx: Context, alarm: Alarm) {
        val items = list(ctx)
        val idx = items.indexOfFirst { it.id == alarm.id }
        if (idx >= 0) items[idx] = alarm else items.add(alarm)
        persist(ctx, items)
    }

    fun remove(ctx: Context, id: Int) = persist(ctx, list(ctx).filterNot { it.id == id })

    fun setEnabled(ctx: Context, id: Int, enabled: Boolean) {
        val items = list(ctx)
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(enabled = enabled)
            persist(ctx, items)
        }
    }

    /** Calendar 星期(Sunday=1) → 位顺序(周一=bit0) */
    private fun dayIndexOf(cal: Calendar): Int =
        (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

    /** 下一次响的绝对毫秒；禁用返回 null。从今天起最多往后找 8 天。 */
    fun computeNext(alarm: Alarm, from: Long = System.currentTimeMillis()): Long? {
        if (!alarm.enabled) return null
        val base = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val repeats = alarm.daysMask != 0
        for (offset in 0..8) {
            val c = base.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, offset)
            val should = !repeats || (alarm.daysMask and (1 shl dayIndexOf(c))) != 0
            if (should && c.timeInMillis > from) return c.timeInMillis
        }
        return null
    }

    /** 重复日的人话摘要，用于列表副标题 */
    fun summary(mask: Int): String = when (mask) {
        0 -> "单次"
        127 -> "每天"
        31 -> "周一至周五"
        96 -> "周六、周日"
        else -> DAY_LABELS.indices.filter { (mask and (1 shl it)) != 0 }
            .joinToString("、") { "周" + DAY_LABELS[it] }
    }
}
