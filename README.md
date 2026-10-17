# ForceAlarm ⏰

**一款"不刷够手机就别想起床"的硬核闹钟。** Kotlin + Jetpack Compose 原生开发，无第三方框架、零联网权限——同时是一份 **MIUI 深度权限对抗**的实战记录。

> 场景：闹钟响了，你按掉继续睡？这里不行。到点全屏锁死，要么刷满指定时长的目标 App，要么答对题，才能起床。

## ✨ 功能

- **多闹钟系统**：无限只闹钟，各自独立预约（requestCode + data URI 隔离）；重复日位掩码（单次/每天/工作日/自定义周几）；重复闹钟响完自动排下一天，单次响完自动关闸
- **开机恢复**：`BOOT_COMPLETED` 全量重排（AlarmManager 预约重启即失效，系统级设定）
- **全屏弹屏**：锁屏直达全屏闹钟界面（Android 14+ 走全屏意图通知通道）；`setAlarmClock` 闹钟级特权穿透 Doze，状态栏亮小闹钟图标
- **双解锁门（二选一）**：
  - 🎵 **刷 App 解锁**：钉屏（`startLockTask`）→ 强制运行目标 App N 分钟，前台服务 + 使用情况统计每秒对表实时计时；Home/切走自动拽回，累计不丢
  - 🧮 **挑战解锁**：数学题（3 连对，错一题清零重出随机题——防半梦半醒背答案）/ 逐字抄写咒语（错一个字当场飘红）/ 自定义问答（睡前出题，醒来作答）
- **机型适配**：识别小米/华为/荣耀/OPPO/vivo/三星/一加/原生 8 类，权限面板按品牌给出手动开启路径 + 直达厂商设置页（深链失败兜底应用详情页，全程防崩）
- **权限面板**：精确闹钟 / 全屏意图 / 使用情况访问 / 悬浮窗 / 电池优化豁免，5 项可检测权限实时红绿灯 + 一键跳转

## 🏗️ 架构

```
app/src/main/java/com/example/forcealarm/
├── MainActivity.kt          # 首页：闹钟列表/编辑器(滚轮+周几胶囊)/权限面板/任务与挑战配置
├── alarm/
│   ├── AlarmStore.kt        # 数据模型 + "下次触发"推导(纯函数可单测) + 重复日位掩码
│   ├── AlarmScheduler.kt    # 多闹钟独立预约/撤销/总闸重排；旧版单闹钟兼容拆除
│   ├── AlarmReceiver.kt     # 到点分叉：重复闹钟排明天，单次闹钟自动关闸
│   ├── BootReceiver.kt      # 开机重排全部闹钟
│   ├── AlarmNotifier.kt     # 全屏意图通知(息屏→全屏弹屏; 亮屏→横幅)
│   ├── WatchService.kt      # 前台服务：刷 App 计时器(独立于钉屏界面存活)
│   ├── Watchdog.kt          # UsageStatsManager 增量累计(单例状态, 秒级对表)
│   ├── TaskPrefs.kt         # 解锁任务配置
│   ├── ChallengePrefs.kt    # 解锁挑战配置(类型/抄写文本/自定义问答)
│   └── VendorDetector.kt    # 机型适配层: 品牌识别→引导文案→跳厂商设置页
└── ui/
    ├── LockScreenActivity.kt  # 闹钟全屏界面: 钉屏+吃返回键+ALARM声道响铃+双解锁门
    ├── ChallengeDialog.kt     # 三套挑战玩法 UI
    └── theme/                 # Compose 主题
```

## 🔧 MIUI 权限矩阵（本项目最硬核的部分）

| 权限 | 类型 | 普通 App 可检测? | 作用 | 获取方式 |
|---|---|---|---|---|
| `SCHEDULE_EXACT_ALARM` | A12+ 运行时 | ✅ `canScheduleExactAlarms()` | 准时响，不被省电拖延 | 引导页 |
| `USE_FULL_SCREEN_INTENT` | A14 运行时 | ✅ | 息屏时闹钟全屏弹出 | 引导页 |
| `PACKAGE_USAGE_STATS` | 特殊权限 | ✅ appops 查询 | 统计目标 App 停留时长 | 引导页 |
| `SYSTEM_ALERT_WINDOW` | 特殊权限 | ✅ | 后台拽回落锁屏 | 引导页 |
| 电池优化白名单 | 特殊权限 | ✅ `isIgnoringBatteryOptimizations` | 防 Doze 拖死 | 引导页 |
| **MIUI 锁屏显示 (appops 10020)** | 🔴 私有 | ❌ 无公开 API | 锁屏上画界面 | 手动/`weld-perms.ps1` |
| **MIUI 后台弹出界面 (appops 10022)** | 🔴 私有 | ❌ | 被杀后 Activity 能弹出来 | 手动/`weld-perms.ps1` |
| **MIUI 自启动 (appops 10021)** | 🔴 私有 | ❌ | 开机收得到 BOOT_COMPLETED | 手动/`weld-perms.ps1` |

> MIUI 每次**重装 APK 会异步重置**这些私有开关——`weld-perms.ps1` 用 adb 一键焊接全部 8 项（含电池白名单），开发期必备。

## 🕳️ 踩坑实录（每个都值一面面试官）

1. **PendingIntent 判重不看 extras**：多闹钟若共用同一 Intent 形状，系统视为同一张"预约单"互相覆盖，只剩最后一只。解法：每闹钟独立 requestCode + `data:` URI
2. **`FLAG_NO_CREATE` 也要配 `FLAG_IMMUTABLE`**：A12+ 的可变性校验发生在"查询"之前，哪怕根本不创建——不配就 `IllegalArgumentException`
3. **R8 `packageScope` 重打包**：release 包把 app 类搬进 `kotlin.**` 包破坏包私有访问 → 启动即 `IllegalAccessError`。debug 全绿、release 崩，只有真机能暴露
4. **`MediaPlayer` 默认走媒体声道**：媒体静音=闹钟无声，必须显式 `USAGE_ALARM`
5. **前台服务启动时序**：`startForegroundService` 有 5 秒挂通知宽限期，慢一步 ANR；进程被杀后服务不自愈，靠锁屏界面 `onResume` 自检兜底
6. **AlarmManager 预约是易失的**：重启清零，BootReceiver 全量重排是唯一解
7. **卸载重装 = 权限坟场**：MIUI 重置 appops、签名不同的 APK 无法覆盖安装（debug↔release 切换必卸载）

## 🚀 构建

```bash
git clone https://github.com/tutouqiang/ForceAlarm.git
# Android Studio 打开即可(Gradle 9.6 wrapper / AGP 9.4 / Kotlin 2.2 / Compose BOM 2026.02)
./gradlew assembleDebug        # 测试包
./gradlew installDebug         # 连接设备直接装机
```

- 首次构建需 JDK 17+ 与 Android SDK 37（Android Studio 自动配置；命令行在 `local.properties` 配 `sdk.dir`）
- 打 release 需自备 keystore 并在 `local.properties` 填 `FORCEALARM_STORE_PASSWORD` / `FORCEALARM_KEY_PASSWORD`（该文件不进版本控制）
- 预编译 APK 见 [Releases](../../releases)

## 📱 安装须知（非小米机型同理）

1. 允许"未知来源安装"
2. 首次打开按引导补齐 5 项可检测权限
3. 小米/MIUI 用户额外手动开启：**锁屏显示、后台弹出界面、自启动**（设置→应用管理→ForceAlarm）
4. 建议把 App 加入电池优化白名单（面板内一键直达）

## ⚠️ 已知边界

- 国产 ROM 私有权限（自启动/锁屏显示/后台弹出）无公开检测 API，App 只能引导无法代检
- 刷 App 计时依赖使用情况访问，多用户/工作资料场景未覆盖
- 包名仍为 `com.example.forcealarm`（发布遗留 TODO）

## License

[MIT](LICENSE)
