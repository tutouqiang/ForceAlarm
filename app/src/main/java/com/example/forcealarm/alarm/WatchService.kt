package com.example.forcealarm.alarm

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.forcealarm.MainActivity
import com.example.forcealarm.R
import com.example.forcealarm.ui.LockScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 【全局任务状态】闹钟→刷抖音→解锁 这一单活儿的进度。
 * 服务和界面在同一个进程里，用单例对象共享状态最简单。
 */
object Watchdog {
    // 【第6课】目标软件/时长不再硬编码：服务启动时从 TaskPrefs 读
    var targetPkg: String = TaskPrefs.DEFAULT_PKG
    var requiredMs: Long = TaskPrefs.DEFAULT_MIN * 60_000L
    var targetLabel: String = ""   // 应用显示名，供界面文案

    var active = false          // 本轮任务是否进行中
    var accumulatedMs = 0L      // 已在目标 App 累计的时长

    fun reset() { active = false; accumulatedMs = 0L }

    /** 【使用情况访问】是特殊权限：不能弹窗，只能用户去设置页开或 adb appops 开。
     *  检查方法：问 AppOpsManager 我们的 GET_USAGE_STATS 是 allow 还是 ignore */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

/**
 * 【前台服务】安卓的"常驻后台"许可证：
 * 必须挂一条状态栏通知才能活着（用户看得见你在干嘛，系统才放行），
 * 否则应用退到后台几秒就被冻结/杀掉——这也是屏幕时间类 App 常驻通知的由来。
 */
class WatchService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("watch_task", "解锁计时", NotificationManager.IMPORTANCE_LOW)
        )
        // startForeground 必须在服务启动 5 秒内调用，不然系统杀服务
        startForeground(NOTIFY_ID, buildNotification("计时已开始"))
    }

    // 【Bug修复】服务一旦启动过，再次 start 不会重跑 onCreate；用户在首页
    // 改了目标软件/时长后，旧循环还盯着旧目标 → 既不累计也不抓回。
    // 所以把"读配置+启循环"放到每次启动命令都执行的 onStartCommand 里
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        Watchdog.targetPkg = TaskPrefs.targetPackage(this)
        Watchdog.requiredMs = TaskPrefs.requiredMs(this)
        Watchdog.targetLabel = TaskPrefs.label(this, Watchdog.targetPkg)
        loopJob?.cancel()
        loopJob = scope.launch { watchLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        super.onDestroy()
    }

    // 【知识点】isActive 是 CoroutineScope 的属性。把 watchLoop 声明成
    // CoroutineScope 的扩展函数后，launch { } 块里的 this(scope) 自动成为接收者，
    // while(isActive) 就能编译且语义正确：循环随 Job 取消而结束
    private suspend fun CoroutineScope.watchLoop() {
        val usm = getSystemService(UsageStatsManager::class.java)
        var lastTick = System.currentTimeMillis()
        var lastQueryEnd = lastTick
        // 【大坑修正】UsageEvents 是状态切换的"瞬间事件流"，不是心跳！
        // 旧版只查最近 3 秒滑窗：进抖音那条 FOREGROUND 事件滑出窗口后查不到，
        // 被误判"离开"→几秒后自己抓回自己。新版改成：增量消费新事件，
        // currentTop 跨 tick 保持，没新事件就维持上一状态。
        var currentTop: String? = packageName
        var sawTarget = false
        var offStreakMs = 0L

        while (isActive) {
            delay(1000)
            val now = System.currentTimeMillis()
            val delta = now - lastTick
            lastTick = now

            currentTop = advanceTop(usm, lastQueryEnd, now, currentTop)
            lastQueryEnd = now

            if (currentTop == Watchdog.targetPkg) {
                sawTarget = true
                offStreakMs = 0
                Watchdog.accumulatedMs += delta
                val remainMin = (Watchdog.requiredMs - Watchdog.accumulatedMs + 59_999) / 60_000
                updateNotification("还差 $remainMin 分钟，别想跑")
                if (Watchdog.accumulatedMs >= Watchdog.requiredMs) {
                    Log.i(TAG, "刷满任务时长，放行")
                    getSystemService(NotificationManager::class.java)?.cancel(AlarmNotifierId)
                    Watchdog.reset()
                    stopSelf()
                    return
                }
            } else if (sawTarget) {
                offStreakMs += delta
                if (offStreakMs >= 3000) {
                    // 二次确认，防过场动画/瞬时抖动误抓
                    delay(1500)
                    val t2 = System.currentTimeMillis()
                    currentTop = advanceTop(usm, lastQueryEnd, t2, currentTop)
                    lastQueryEnd = t2; lastTick = t2
                    if (currentTop == Watchdog.targetPkg) {
                        offStreakMs = 0
                    } else {
                        Log.i(TAG, "确认离开目标App，抓回闹钟")
                        pullBackToAlarm()
                        stopSelf()
                        return
                    }
                }
            }
        }
    }

    /** 增量消费 [begin,end] 的新事件，在 current 基础上推导最新前台包名 */
    @Suppress("DEPRECATION")
    private fun advanceTop(
        usm: UsageStatsManager, begin: Long, end: Long, current: String?
    ): String? {
        return try {
            val events = usm.queryEvents(begin, end)
            val ev = UsageEvents.Event()
            var top = current
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                when (ev.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> top = ev.packageName
                    UsageEvents.Event.MOVE_TO_BACKGROUND ->
                        if (top == ev.packageName) top = null
                }
            }
            top
        } catch (e: Exception) {
            Log.w(TAG, "查前台失败: ${e.message}")
            current
        }
    }

    /** 抓回：重发全屏意图通知 + 直接弹闹钟界面（和 AlarmReceiver 同款双通道） */
    private fun pullBackToAlarm() {
        AlarmNotifier.ring(this)
        try {
            val back = Intent(this, LockScreenActivity::class.java)
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(back)
        } catch (_: Exception) { }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, "watch_task")
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("ForceAlarm 解锁计时")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFY_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "ForceAlarm"
        private const val NOTIFY_ID = 43
        private const val AlarmNotifierId = 42
    }
}
