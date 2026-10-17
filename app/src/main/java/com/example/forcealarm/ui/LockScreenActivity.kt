package com.example.forcealarm.ui

import android.app.KeyguardManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import android.app.NotificationManager
import com.example.forcealarm.alarm.AlarmNotifier
import com.example.forcealarm.alarm.ChallengePrefs
import com.example.forcealarm.alarm.TaskPrefs
import com.example.forcealarm.alarm.WatchService
import com.example.forcealarm.alarm.Watchdog

class LockScreenActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    // 开发后门开关：true 之后 onStop 不再拽回，可以正常退出
    private var allowedToFinish = false

    // 【第6课】自定义解锁任务：目标软件 + 分钟数（onCreate 从 TaskPrefs 读）
    private var taskPkg = TaskPrefs.DEFAULT_PKG
    private var taskMin = TaskPrefs.DEFAULT_MIN
    private var taskLabel = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "LockScreenActivity onCreate —— 闹钟界面已弹出")

        // ① 窗口特性：锁屏上也显示、自动亮屏、保持常亮
        //    注意：这两句在 onCreate 里设置有时太晚，MIUI/部分机型要在
        //    AndroidManifest 的 <activity> 上也声明 showWhenLocked/turnScreenOn
        // 【知识点】API 版本门禁：minSdk=26，但 setShowWhenLocked 要 API 27、
        // insetsController 要 API 30。不守卫的话新手机没事、老手机直接崩溃。
        // lint 红线就是在提醒这件事，加 Build.VERSION 判断即可。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        getSystemService(KeyguardManager::class.java)
            ?.requestDismissKeyguard(this, null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 【钉屏】Home/上划/最近任务全部失效，直到我们主动 stopLockTask。
        // Android 9+ 普通 App 会弹“开始固定”确认框，用户点确认才生效。
        // 钉屏期间跳不了其它 App，所以去抖音前必须先解钉。
        try {
            startLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "钉屏失败: ${e.message}")
        }

        // ② 吃掉返回键
        onBackPressedDispatcher.addCallback(this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Log.i(TAG, "返回键被拦截")
                }
            })

        // ③ 响铃
        startAlarmSound()

        // 【第6课】读取自定义解锁任务
        taskPkg = TaskPrefs.targetPackage(this)
        taskMin = TaskPrefs.requiredMinutes(this)
        taskLabel = TaskPrefs.label(this, taskPkg)

        // 【第7课】解锁挑战配置（自定义问答没录题自动降级数学题，保证门不消失）
        var chType = ChallengePrefs.type(this)
        if (chType == ChallengePrefs.TYPE_CUSTOM && ChallengePrefs.question(this).isBlank())
            chType = ChallengePrefs.TYPE_MATH
        val chCopy = ChallengePrefs.copyText(this)
        val chQ = ChallengePrefs.question(this)
        val chA = ChallengePrefs.answer(this)

        // 统一放行收口：开发后门与挑战通关都走这一条路
        val releaseAlarm: () -> Unit = {
            Watchdog.reset()
            // 顺手停掉可能还在跑的计时服务，防幽灵拽回
            stopService(Intent(this, WatchService::class.java))
            allowedToFinish = true
            try { stopLockTask() } catch (_: Exception) {}
            getSystemService(NotificationManager::class.java)?.cancel(42)
            stopAlarmSound()
            finish()
        }

        // ④ 界面
        setContent {
            LockScreen(
                taskMin = taskMin,
                taskLabel = taskLabel,
                challengeType = chType,
                challengeCopy = chCopy,
                challengeQuestion = chQ,
                challengeAnswer = chA,
                onDevExit = releaseAlarm,
                onChallengePass = releaseAlarm,
                // 做题页静音：铃声轰炸下没人算得动三位数乘法；
                // 但"算了"退回闹钟时铃声必须恢复，不能借做题躲清静
                onChallengeStart = { stopAlarmSound() },
                onChallengeCancel = { if (!allowedToFinish) startAlarmSound() },
                onGoTask = {
                    // 【第6课】正门：解钉→停声→拉起自定义目标App→放行
                    if (!Watchdog.hasUsageAccess(this)) {
                        android.widget.Toast.makeText(
                            this, "请先允许【使用情况访问】权限", android.widget.Toast.LENGTH_LONG
                        ).show()
                        startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    } else {
                        Watchdog.active = true
                        // 前台服务必须用 startForegroundService 启动（给5秒宽限期挂通知）
                        androidx.core.content.ContextCompat.startForegroundService(
                            this, Intent(this, WatchService::class.java)
                        )
                        allowedToFinish = true
                        try { stopLockTask() } catch (_: Exception) {}
                        getSystemService(NotificationManager::class.java)?.cancel(42)
                        val target = packageManager.getLaunchIntentForPackage(taskPkg)
                        if (target != null) startActivity(target) else Log.w(TAG, "没找到 $taskPkg")
                        stopAlarmSound()
                        finish()
                    }
                }
            )
        }
    }

    // 【自愈】闹钟界面每次回到前台都自检：时长其实已达标？
    // 是就安静放行（防服务完成路径被杀/竞态导致"响着不放"）
    override fun onResume() {
        super.onResume()
        if (!allowedToFinish && Watchdog.accumulatedMs >= Watchdog.requiredMs) {
            Log.i(TAG, "onResume 自愈：已达标，安静退出")
            allowedToFinish = true
            Watchdog.reset()
            stopAlarmSound()
            try { stopLockTask() } catch (_: Exception) {}
            getSystemService(NotificationManager::class.java)?.cancel(42)
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+：每次重新拿到焦点都把系统栏藏回去
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun startAlarmSound() {
        try {
            // 依次兜底：用户闹钟铃声 → Settings 里的 ALARM_ALERT → 通知铃声 → 系统内置
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: Settings.System.getString(contentResolver, Settings.System.ALARM_ALERT)
                    ?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: Settings.System.DEFAULT_ALARM_ALERT_URI

            val am = getSystemService(AudioManager::class.java)
            val alarmVol = am?.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            Log.i(TAG, "闹钟音量=$alarmVol/$maxVol uri=$uri")

            // 【知识点】Android 的每一类声音是独立的"音量通道(stream)"：
            // 媒体、铃铛、闹钟、通知各有各的音量。MediaPlayer.create() 默认走
            // "媒体"通道，媒体静音就听不见——真闹钟必须显式走 ALARM 通道。
            // 所以这里手动组装 MediaPlayer：先设 AudioAttributes，再喂数据源。
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@LockScreenActivity, uri)
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
            Log.i(TAG, "铃声已开始循环播放")
        } catch (e: Exception) {
            Log.w(TAG, "铃声失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 主动停声：离开闹钟界面的两条路径（去抖音/后门）都要立即静音，
     *  不能把希望放在 onDestroy 的不确定时机上 */
    private fun stopAlarmSound() {
        try {
            mediaPlayer?.apply { if (isPlaying) stop(); release() }
        } catch (_: Exception) { }
        mediaPlayer = null
    }

    override fun onStop() {
        super.onStop()
        if (!allowedToFinish) {
            // 用户按了 Home / 切走了。两条通道把他拽回来：
            AlarmNotifier.ring(this)   // 通道1: 重发全屏意图通知（息屏→全屏；亮屏→横幅）
            try {
                // 通道2: 直接再弹自己（吃 MIUI"后台弹出界面"这扇门）
                val back = Intent(this, LockScreenActivity::class.java)
                back.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                startActivity(back)
            } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        stopAlarmSound()
        super.onDestroy()
    }

    companion object { const val TAG = "ForceAlarm" }
}

@Composable
fun LockScreen(
    taskMin: Int,
    taskLabel: String,
    challengeType: Int,
    challengeCopy: String,
    challengeQuestion: String,
    challengeAnswer: String,
    onDevExit: () -> Unit,
    onChallengePass: () -> Unit,
    onChallengeStart: () -> Unit,
    onChallengeCancel: () -> Unit,
    onGoTask: () -> Unit
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var taps by remember { mutableStateOf(0) }
    var showChallenge by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
    val dateText = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(now))

    // 顶部光晕：缓慢呼吸的红，凌晨也看得清又不至于爆闪
    val breath = rememberInfiniteTransition(label = "glow")
    val halo by breath.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "halo"
    )

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color(0xFF0B0E1A), 0.55f to Color(0xFF141830), 1f to Color(0xFF241631)
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        // 背景呼吸光晕
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 60.dp)
                .size(300.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFE5484D).copy(alpha = halo * 0.40f), Color.Transparent)
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp)
        ) {
            // 开发后门：连点标题区 5 下放行（不再是显眼的"关闭闹钟"按钮）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable {
                    taps++
                    if (taps >= 5) onDevExit()
                }
            ) {
                Text(
                    if (taps >= 5) "再见 👋" else "闹 钟",
                    color = Color(0xFFFF6B6B), fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 8.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    timeText, color = Color.White, fontSize = 86.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
            }
            Text(dateText, color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp)
            Spacer(Modifier.height(30.dp))

            // 任务进度玻璃卡：每 500ms 重组，直接读单例就是最新值
            val watchedMin = (Watchdog.accumulatedMs / 60000).toInt()
            val progress = (Watchdog.accumulatedMs.toFloat() / (taskMin * 60_000L))
                .coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0x14FFFFFF))
                    .padding(20.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (Watchdog.accumulatedMs > 0) "已刷 $watchedMin/$taskMin 分钟，想跑？"
                        else "刷满 $taskMin 分钟 $taskLabel 才能关闭",
                        color = Color(0xFFFFD98A), fontSize = 15.sp
                    )
                    if (Watchdog.accumulatedMs > 0) {
                        Spacer(Modifier.height(12.dp))
                        // 迷你进度条（不用线性进度条组件，手撞更可控）
                        Box(
                            Modifier.width(180.dp).height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0x33FFFFFF))
                        ) {
                            Box(
                                Modifier.fillMaxWidth(progress).height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFFFF8A3D), Color(0xFFFFD98A))
                                        )
                                    )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
            // 正门一：去刷自定义目标 App（解锁任务）
            Button(
                onClick = onGoTask,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF26B1D)),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text("🎵 去刷 $taskMin 分钟 $taskLabel", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            // 正门二：做题硬解锁（挑战在界面内完成，不离开锁屏）
            OutlinedButton(
                onClick = {
                    showChallenge = true
                    onChallengeStart()   // 进题页即静音
                },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text(ChallengePrefs.buttonLabel(challengeType), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
            }
        }
        // 挑战弹窗全屏盖在闹钟上；通关才放行，放弃就继续响
        if (showChallenge) {
            ChallengeDialog(
                type = challengeType,
                copyText = challengeCopy,
                question = challengeQuestion,
                answer = challengeAnswer,
                onPass = { showChallenge = false; onChallengePass() },
                onAbort = { showChallenge = false; onChallengeCancel() }
            )
        }
    }
}
