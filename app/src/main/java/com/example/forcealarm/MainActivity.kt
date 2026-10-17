package com.example.forcealarm

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forcealarm.alarm.Alarm
import com.example.forcealarm.alarm.AlarmScheduler
import com.example.forcealarm.alarm.AlarmStore
import com.example.forcealarm.alarm.ChallengePrefs
import com.example.forcealarm.alarm.TaskPrefs
import com.example.forcealarm.alarm.VendorDetector
import com.example.forcealarm.alarm.VendorKind
import com.example.forcealarm.alarm.WatchService
import com.example.forcealarm.alarm.Watchdog
import com.example.forcealarm.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val askNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ForceAlarmTheme {
                Scaffold(containerColor = LightBg) { innerPadding ->
                    HomeScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

// ————— 权限体系 —————

private fun hasOverlayAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            "android:system_alert_window", Process.myUid(), context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            "android:system_alert_window", Process.myUid(), context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/** 一项可检测的权限。MIUI 私有的 10020/10022 无法被普通 App 检测，不进列表 */
private class PermCheck(val name: String, val desc: String, val ok: Boolean, val fix: () -> Unit)

private fun buildPermChecks(context: Context): List<PermCheck> {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val nm = context.getSystemService(NotificationManager::class.java)
    val pkgUri = Uri.parse("package:${context.packageName}")
    return listOf(
        PermCheck(
            "精确闹钟", "到点准时响，不被省电策略拖延",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        ) { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) },
        PermCheck(
            "全屏意图", "息屏时闹钟直接全屏弹出",
            Build.VERSION.SDK_INT < 34 || (nm != null && nm.canUseFullScreenIntent())
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)
            )
        },
        PermCheck(
            "使用情况访问", "统计你在目标 App 的停留时长",
            Watchdog.hasUsageAccess(context)
        ) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
        PermCheck(
            "后台弹出界面", "离开闹钟后把你拽回来",
            hasOverlayAccess(context)
        ) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)) },
        PermCheck(
            "电池优化豁免", "防系统省电策略把闹钟拖死",
            VendorDetector.isIgnoringBattery(context)
        ) { VendorDetector.openBatteryExempt(context) },
    )
}

private fun humanDiff(millis: Long): String {
    val total = millis / 1000
    return when {
        total >= 3600 -> "${total / 3600} 小时 ${total % 3600 / 60} 分后响起"
        total >= 60   -> "${total / 60} 分后响起"
        else          -> "即将响起"
    }
}

/** 列表里所有启用闹钟中最近的下一个触发时间 */
private fun nextUp(ctx: Context): Pair<Alarm, Long>? =
    AlarmStore.list(ctx).filter { it.enabled }
        .mapNotNull { a -> AlarmStore.computeNext(a)?.let { a to it } }
        .minByOrNull { it.second }

// ————— 首页：闹钟列表 —————

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var alarms by remember { mutableStateOf(AlarmStore.list(context)) }
    var nextAlarm by remember { mutableStateOf<Pair<Alarm, Long>?>(nextUp(context)) }
    var taskDlg by remember { mutableStateOf(false) }
    var minDlg by remember { mutableStateOf(false) }
    var minInput by remember {
        mutableStateOf(TaskPrefs.requiredMinutes(context).toString())
    }
    var chType by remember { mutableIntStateOf(ChallengePrefs.type(context)) }
    var copyDlg by remember { mutableStateOf(false) }
    var copyInput by remember { mutableStateOf(ChallengePrefs.copyText(context)) }
    var qaDlg by remember { mutableStateOf(false) }
    var qInput by remember { mutableStateOf(ChallengePrefs.question(context)) }
    var aInput by remember { mutableStateOf(ChallengePrefs.answer(context)) }
    // 编辑器：null=关；-1=新建；其他=编辑该 id
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var perms by remember { mutableStateOf(buildPermChecks(context)) }
    var permPrompt by remember { mutableStateOf(false) }
    var firstCheck by remember { mutableStateOf(true) }
    val vendor = remember { VendorDetector.current() }

    fun reload() {
        alarms = AlarmStore.list(context)
        nextAlarm = nextUp(context)
    }

    // 首次启动迁移：旧版单闹钟（requestCode=100）还在系统里挂着的话，
    // 把它认领成列表里的一条"单次"闹钟，然后拆掉旧预约——防幽灵闹钟
    LaunchedEffect(Unit) {
        AlarmScheduler.cancelLegacy(context)
        if (AlarmStore.list(context).isEmpty()) {
            val legacy = (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .getNextAlarmClock()?.triggerTime
            if (legacy != null && legacy > System.currentTimeMillis()) {
                val c = Calendar.getInstance().apply { timeInMillis = legacy }
                val a = Alarm(
                    AlarmStore.nextId(context),
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                    daysMask = 0, enabled = true
                )
                AlarmStore.upsert(context, a)
                AlarmScheduler.schedule(context, a)
            }
        }
        reload()
    }

    // 每秒轮询：倒计时 + 权限台账双刷新
    LaunchedEffect(Unit) {
        while (true) {
            nextAlarm = nextUp(context)
            perms = buildPermChecks(context)
            if (firstCheck) {
                firstCheck = false
                permPrompt = perms.any { !it.ok }
            } else if (permPrompt && perms.all { it.ok }) {
                permPrompt = false
            }
            delay(1000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // —— 品牌区 ——
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(AlarmOrangeSoft),
            contentAlignment = Alignment.Center
        ) { Text("⏰", fontSize = 34.sp) }
        Spacer(Modifier.height(10.dp))
        Text("ForceAlarm", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        Text("刷不完别想起床", color = TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        // —— 下一只闹钟 ——
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(LightCard)
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("下一个闹钟", style = MaterialTheme.typography.labelMedium,
                 color = TextMuted, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            val nx = nextAlarm
            if (nx != null) {
                Text(
                    "%02d:%02d".format(nx.first.hour, nx.first.minute),
                    fontSize = 52.sp, fontWeight = FontWeight.Bold, color = AlarmOrange
                )
                Text(
                    AlarmStore.summary(nx.first.daysMask) + " · " +
                        humanDiff(nx.second - System.currentTimeMillis()),
                    color = TextMuted, fontSize = 13.sp
                )
            } else {
                Text("未设置", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC9CFD8))
                Spacer(Modifier.height(4.dp))
                Text("点下面的 + 添加一只", color = TextMuted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))

        // —— 闹钟列表 ——
        SectionTitle("闹钟列表")
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(20.dp), color = LightCard,
            tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                if (alarms.isEmpty()) {
                    Text(
                        "还没有闹钟", color = TextMuted, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 28.dp), textAlign = TextAlign.Center
                    )
                }
                alarms.forEachIndexed { i, a ->
                    AlarmRow(
                        alarm = a,
                        onToggle = { on ->
                            AlarmStore.setEnabled(context, a.id, on)
                            val fresh = AlarmStore.find(context, a.id)
                            if (fresh != null) AlarmScheduler.schedule(context, fresh)
                            reload()
                        },
                        onClick = {
                            if (perms.any { !it.ok }) permPrompt = true
                            else editing = a
                        }
                    )
                    if (i < alarms.size - 1) {
                        HorizontalDivider(color = Color(0x14000000), modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (perms.any { !it.ok }) permPrompt = true
                else {
                    val c = Calendar.getInstance()
                    editing = Alarm(
                        id = -1,
                        hour = (c.get(Calendar.HOUR_OF_DAY) + 1) % 24,
                        minute = 0, daysMask = 0, enabled = true
                    )
                }
            },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) { Text("＋ 添加闹钟", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(20.dp))

        // —— 解锁任务 ——
        SectionTitle("解锁任务")
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(20.dp), color = LightCard,
            tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                SettingRow(
                    "强制运行的软件",
                    TaskPrefs.label(context, TaskPrefs.targetPackage(context))
                ) { taskDlg = true }
                HorizontalDivider(color = Color(0x14000000))
                SettingRow(
                    "强制运行时长", "${TaskPrefs.requiredMinutes(context)} 分钟"
                ) {
                    minInput = TaskPrefs.requiredMinutes(context).toString()
                    minDlg = true
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ——解锁挑战（刷 App 的替代门：二选一）——
        SectionTitle("解锁挑战")
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(20.dp), color = LightCard,
            tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    "闹钟响时当场景挑战就能解锁；或去刷 App，两道门任选一道",
                    fontSize = 12.sp, color = TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        ChallengePrefs.TYPE_MATH to "数学题",
                        ChallengePrefs.TYPE_COPY to "抄写咒语",
                        ChallengePrefs.TYPE_CUSTOM to "自定义问答"
                    ).forEach { (t, label) ->
                        FilterChip(
                            selected = chType == t,
                            onClick = {
                                chType = t
                                ChallengePrefs.saveType(context, t)
                            },
                            label = { Text(label) },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
                if (chType == ChallengePrefs.TYPE_COPY) {
                    HorizontalDivider(color = Color(0x14000000))
                    SettingRow("抄写内容", ChallengePrefs.copyText(context)) {
                        copyInput = ChallengePrefs.copyText(context)
                        copyDlg = true
                    }
                }
                if (chType == ChallengePrefs.TYPE_CUSTOM) {
                    HorizontalDivider(color = Color(0x14000000))
                    SettingRow(
                        "我的题目",
                        ChallengePrefs.question(context).ifBlank { "未录题 · 将自动降级数学题" }
                    ) {
                        qInput = ChallengePrefs.question(context)
                        aInput = ChallengePrefs.answer(context)
                        qaDlg = true
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // —— 权限面板 ——
        SectionTitle("系统权限")
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(20.dp), color = LightCard,
            tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                perms.forEach { p -> PermissionRow(p.name, p.ok) { p.fix() } }
                HorizontalDivider(color = Color(0x14000000))
                // 机型适配块：品牌识别 + 手开路径引导 + 跳官方设置页
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(InfoBlue))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "当前机型：${vendor.label}", fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { VendorDetector.openVendorSettings(context) }) {
                            Text("去设置", color = InfoBlue)
                        }
                    }
                    vendor.tips.forEach { t ->
                        Text("· $t", fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (vendor.kind == VendorKind.XIAOMI) {
                        Text(
                            "· 开发者可跑 weld-perms.ps1 一键焊接全部私有开关",
                            fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            if (vendor.kind == VendorKind.XIAOMI) "MIUI 会在每次重装后重置权限"
            else "重装后请重新完成上方权限项",
            color = Color(0xFFB0B6C0), fontSize = 11.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
    }

    // —— 权限引导 ——
    if (permPrompt) {
        val missing = perms.filter { !it.ok }
        AlertDialog(
            onDismissRequest = { permPrompt = false },
            icon = { Text("🔑", fontSize = 26.sp) },
            title = { Text("开工前请补齐权限") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (missing.isEmpty()) Text("全部权限已就绪 ✅", color = Good)
                    missing.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(p.desc, fontSize = 12.sp, color = TextMuted)
                            }
                            Button(
                                onClick = p.fix,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) { Text("去开启", fontSize = 13.sp) }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("授权返回后自动检测，补齐即关闭", fontSize = 11.sp, color = TextMuted)
                }
            },
            confirmButton = {
                TextButton(onClick = { permPrompt = false }) { Text("稍后再说") }
            }
        )
    }

    // —— 闹钟编辑器（新建/编辑共用） ——
    editing?.let { draft ->
        AlarmEditorDialog(
            draft = draft,
            onDismiss = { editing = null },
            onSave = { alarm ->
                val toSave = if (alarm.id == -1)
                    alarm.copy(id = AlarmStore.nextId(context)) else alarm
                AlarmStore.upsert(context, toSave)
                AlarmScheduler.schedule(context, toSave)
                editing = null
                reload()
            },
            onDelete = if (draft.id == -1) null else {
                {
                    AlarmScheduler.cancel(context, draft.id)
                    AlarmStore.remove(context, draft.id)
                    editing = null
                    reload()
                }
            }
        )
    }

    if (taskDlg) {
        AppPickerDialog(onDismiss = { taskDlg = false }) { pkg ->
            TaskPrefs.save(context, pkg, TaskPrefs.requiredMinutes(context))
            Watchdog.reset()
            context.stopService(Intent(context, WatchService::class.java))
            taskDlg = false
        }
    }
    if (minDlg) {
        AlertDialog(
            onDismissRequest = { minDlg = false },
            title = { Text("强制运行时长（分钟）") },
            text = {
                Column {
                    OutlinedTextField(
                        value = minInput,
                        onValueChange = { minInput = it.filter { c -> c.isDigit() }.take(3) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 20, 30).forEach { n ->
                            OutlinedButton(
                                onClick = { minInput = n.toString() },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("$n") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = minInput.toIntOrNull() ?: TaskPrefs.DEFAULT_MIN
                    TaskPrefs.save(context, TaskPrefs.targetPackage(context), m.coerceIn(1, 120))
                    Watchdog.reset()
                    context.stopService(Intent(context, WatchService::class.java))
                    minDlg = false
                }) { Text("保存", color = AlarmOrange) }
            },
            dismissButton = { TextButton(onClick = { minDlg = false }) { Text("取消") } }
        )
    }
    if (copyDlg) {
        AlertDialog(
            onDismissRequest = { copyDlg = false },
            title = { Text("抄写内容") },
            text = {
                Column {
                    Text("清晨要一字不差抄下的句子，越不想抄越清醒", fontSize = 12.sp, color = TextMuted)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = copyInput, onValueChange = { copyInput = it },
                        minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ChallengePrefs.saveCopy(context, copyInput)
                    copyDlg = false
                }) { Text("保存", color = AlarmOrange) }
            },
            dismissButton = { TextButton(onClick = { copyDlg = false }) { Text("取消") } }
        )
    }
    if (qaDlg) {
        AlertDialog(
            onDismissRequest = { qaDlg = false },
            title = { Text("睡前出题，清晨作答") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = qInput, onValueChange = { qInput = it },
                        label = { Text("题目") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = aInput, onValueChange = { aInput = it },
                        label = { Text("答案") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("答案区分大小写前后空格不计；未录题自动降级数学题，门不会消失",
                         fontSize = 11.sp, color = TextMuted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ChallengePrefs.saveQA(context, qInput, aInput)
                    qaDlg = false
                }) { Text("保存", color = AlarmOrange) }
            },
            dismissButton = { TextButton(onClick = { qaDlg = false }) { Text("取消") } }
        )
    }
}

/** 列表一行：大字时间 + 重复摘要 + Switch；整行点击进编辑 */
@Composable
private fun AlarmRow(alarm: Alarm, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "%02d:%02d".format(alarm.hour, alarm.minute),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = if (alarm.enabled) TextPrimary else Color(0xFFC9CFD8)
            )
            Text(
                AlarmStore.summary(alarm.daysMask),
                fontSize = 12.sp, color = TextMuted
            )
        }
        Switch(
            checked = alarm.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = AlarmOrange)
        )
    }
}

/** 闹钟编辑弹窗：滚轮选时 + 重复日多选 + 快捷档 */
@Composable
private fun AlarmEditorDialog(
    draft: Alarm,
    onDismiss: () -> Unit,
    onSave: (Alarm) -> Unit,
    onDelete: (() -> Unit)?
) {
    var hour by remember { mutableIntStateOf(draft.hour) }
    var minute by remember { mutableIntStateOf(draft.minute) }
    var mask by remember { mutableIntStateOf(draft.daysMask) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == -1) "新建闹钟" else "编辑闹钟") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    WheelColumn(
                        values = (0..23).map { "%02d".format(it) },
                        initialIndex = hour,
                        onSelected = { hour = it },
                        modifier = Modifier.width(76.dp)
                    )
                    Text(":", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                         color = TextMuted, modifier = Modifier.align(Alignment.CenterVertically))
                    WheelColumn(
                        values = (0..59).map { "%02d".format(it) },
                        initialIndex = minute,
                        onSelected = { minute = it },
                        modifier = Modifier.width(76.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                // 快捷档
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("单次" to 0, "每天" to 127, "工作日" to 31, "周末" to 96)
                        .forEach { (label, m) ->
                            val sel = mask == m
                            if (sel) AssistChip(onClick = {}, label = { Text(label) })
                            else SuggestionChip(onClick = { mask = m }, label = { Text(label) })
                        }
                }
                Spacer(Modifier.height(12.dp))
                // 七天多选胶囊
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AlarmStore.DAY_LABELS.forEachIndexed { idx, d ->
                        val on = (mask and (1 shl idx)) != 0
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (on) AlarmOrange else Color(0x11000000))
                                .clickable {
                                    mask = if (on) mask and (1 shl idx).inv()
                                    else mask or (1 shl idx)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                d, fontSize = 13.sp,
                                color = if (on) Color.White else TextMuted,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(AlarmStore.summary(mask), fontSize = 12.sp, color = TextMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(draft.copy(hour = hour, minute = minute, daysMask = mask, enabled = true))
            }) {
                Text("保存", color = AlarmOrange, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = Bad) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/** 滑动式时间选择弹窗（调试/快速设单个时刻用） */
@Composable
private fun WheelTimePickerDialog(
    initHour: Int,
    initMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var hour by remember { mutableIntStateOf(initHour) }
    var minute by remember { mutableIntStateOf(initMinute) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择闹钟时刻") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                WheelColumn((0..23).map { "%02d".format(it) }, hour, { hour = it }, Modifier.width(76.dp))
                Text(":", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextMuted,
                     modifier = Modifier.align(Alignment.CenterVertically))
                WheelColumn((0..59).map { "%02d".format(it) }, minute, { minute = it }, Modifier.width(76.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour, minute) }) {
                Text("设定", color = AlarmOrange, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 单列滑动轮：5 槽居中即选中。
 *  上下各垫 2 槽 contentPadding，选中索引 =
 *  firstVisibleItemIndex + round(offset/slotHeight)，derivedStateOf 防每帧重组 */
@Composable
private fun WheelColumn(
    values: List<String>,
    initialIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val slotH = 48.dp
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex.coerceIn(values.indices)
    )
    val slotPx = with(LocalDensity.current) { slotH.toPx() }
    val center by remember {
        derivedStateOf {
            val raw = listState.firstVisibleItemIndex +
                (listState.firstVisibleItemScrollOffset / slotPx).roundToInt()
            raw.coerceIn(values.indices)
        }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(center) { onSelected(center) }

    Box(modifier = modifier.height(slotH * 5)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = slotH * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(values) { index, label ->
                val d = abs(index - center)
                Text(
                    label,
                    fontSize = if (d == 0) 28.sp else 18.sp,
                    fontWeight = if (d == 0) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        d == 0 -> AlarmOrange
                        d == 1 -> TextMuted.copy(alpha = 0.55f)
                        else -> TextMuted.copy(alpha = 0.25f)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .height(slotH).fillMaxWidth()
                        .clickable { scope.launch { listState.scrollToItem(index) } }
                )
            }
        }
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            Column {
                HorizontalDivider(color = Color(0x22000000))
                Spacer(Modifier.height(slotH))
                HorizontalDivider(color = Color(0x22000000))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.width(4.dp).height(16.dp).clip(CircleShape).background(AlarmOrange))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(value, fontSize = 13.sp, color = TextMuted,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", fontSize = 20.sp, color = TextMuted)
    }
}

@Composable
private fun AppPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launch, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != context.packageName }
            .sortedBy { it.second }
    }
    var keyword by remember { mutableStateOf("") }
    val shown = apps.filter {
        keyword.isEmpty() ||
            it.second.contains(keyword, ignoreCase = true) ||
            it.first.contains(keyword, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要强制运行的软件") },
        text = {
            Column {
                OutlinedTextField(
                    value = keyword, onValueChange = { keyword = it },
                    placeholder = { Text("搜索应用…") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(shown) { (pkg, name) ->
                        Text(
                            name,
                            Modifier.fillMaxWidth().clickable { onPick(pkg) }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PermissionRow(name: String, ok: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (ok) Good else Bad))
        Spacer(Modifier.width(12.dp))
        Text(name, Modifier.weight(1f), fontSize = 15.sp,
             color = if (ok) TextPrimary else TextMuted)
        if (ok) Text("已开启", color = Good, fontSize = 12.sp)
        else TextButton(onClick = onClick) { Text("去开启", color = AlarmOrange) }
    }
}
