package com.example.forcealarm.alarm

import android.content.Context

/**
 * 挑战解锁配置：闹钟界面除了"刷 App 满 N 分钟"这条门，
 * 还给一条"做题硬解锁"的门，用户三选一：
 * 数学题 / 抄写咒语 / 自定义问答。
 * 【知识点】挑战在自家锁屏 Activity 内完成，不用离开界面，
 * 所以和钉屏（startLockTask）、响铃都零冲突——这正是
 * Alarmy 那类应用的核心交互形态。
 */
object ChallengePrefs {
    private const val FILE = "challenge"

    const val TYPE_MATH = 0
    const val TYPE_COPY = 1
    const val TYPE_CUSTOM = 2

    const val DEFAULT_COPY = "起床是为了成为谁，而不是为了睡回笼觉"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun type(ctx: Context): Int = sp(ctx).getInt("type", TYPE_MATH)
    fun saveType(ctx: Context, t: Int) { sp(ctx).edit().putInt("type", t).apply() }

    fun copyText(ctx: Context): String = sp(ctx).getString("copy", DEFAULT_COPY) ?: DEFAULT_COPY
    fun saveCopy(ctx: Context, s: String) {
        sp(ctx).edit().putString("copy", s.ifBlank { DEFAULT_COPY }).apply()
    }

    fun question(ctx: Context): String = sp(ctx).getString("q", "") ?: ""
    fun answer(ctx: Context): String = sp(ctx).getString("a", "") ?: ""
    fun saveQA(ctx: Context, q: String, a: String) {
        sp(ctx).edit().putString("q", q.trim()).putString("a", a.trim()).apply()
    }

    fun typeName(t: Int): String = when (t) {
        TYPE_COPY -> "抄写咒语"
        TYPE_CUSTOM -> "自定义问答"
        else -> "数学题"
    }

    fun buttonLabel(t: Int): String = when (t) {
        TYPE_COPY -> "✍️ 抄写解锁"
        TYPE_CUSTOM -> "❓ 答题解锁"
        else -> "🧮 做题解锁"
    }
}
