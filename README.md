# ForceAlarm ⏰

一款 Android 硬核闹钟 App：闹钟响后**全屏锁定**，必须完成一道唤醒挑战、或刷满指定时长的目标 App，才能关闭继续睡。

Kotlin + Jetpack Compose 原生开发，无第三方框架，零联网权限。

## 功能

- **多闹钟**：任意数量闹钟，独立开关；重复规则支持单次 / 每天 / 工作日 / 周末 / 自定义周几。重复闹钟响完自动排下一天，单次闹钟响完自动关闭
- **全屏弹屏**：到点锁屏直达闹钟界面（Android 14+ 走全屏意图通知）；使用 `setAlarmClock` 保证准时唤醒，状态栏显示闹钟图标
- **开机恢复**：设备重启后自动恢复全部闹钟预约
- **双解锁门（二选一）**：
  - 🎵 **刷 App**：钉屏锁界面 → 强制运行目标 App（默认抖音）N 分钟，后台服务按秒统计实际停留时长，中途切走自动拽回
  - 🧮 **挑战**：数学题（连对 3 题，答错清零重出题）/ 逐字抄写一段文本（错字即时标红）/ 自定义问答（睡前自己出题，醒来作答）
- **机型适配**：识别小米、华为、荣耀、OPPO、一加、vivo、三星、原生 Android，权限引导页按品牌显示对应的开启路径，并可直达厂商设置页
- **权限面板**：精确闹钟、全屏意图、使用情况访问、悬浮窗、电池优化豁免，5 项实时状态检测 + 一键跳转设置

## 架构

```
app/src/main/java/com/example/forcealarm/
├── MainActivity.kt           # 首页：闹钟列表、编辑器（滚轮选时+周几选择）、权限面板、任务/挑战配置
├── alarm/
│   ├── AlarmStore.kt         # 数据模型与"下次触发时间"计算（纯函数）
│   ├── AlarmScheduler.kt     # 闹钟预约/撤销/重排，每只闹钟独立 PendingIntent
│   ├── AlarmReceiver.kt      # 到点分发：重复闹钟排下次，单次闹钟自动停用
│   ├── BootReceiver.kt       # 开机重排全部预约
│   ├── AlarmNotifier.kt      # 全屏意图通知
│   ├── WatchService.kt       # 前台服务：使用时长计时
│   ├── Watchdog.kt           # UsageStatsManager 增量统计
│   ├── TaskPrefs.kt          # 解锁任务配置
│   ├── ChallengePrefs.kt     # 解锁挑战配置
│   └── VendorDetector.kt     # 机型识别与厂商设置页跳转
└── ui/
    ├── LockScreenActivity.kt # 闹钟全屏界面：钉屏、拦截返回键、响铃、双解锁门
    ├── ChallengeDialog.kt    # 挑战玩法 UI
    └── theme/                # Compose 主题
```

## 权限说明

| 权限 | 用途 | 开启方式 |
|---|---|---|
| 精确闹钟 `SCHEDULE_EXACT_ALARM` | 保证按时触发 | App 内引导 |
| 全屏意图 `USE_FULL_SCREEN_INTENT` | 息屏时全屏弹出 | App 内引导 |
| 使用情况访问 `PACKAGE_USAGE_STATS` | 统计目标 App 停留时长 | App 内引导 |
| 悬浮窗 `SYSTEM_ALERT_WINDOW` | 离开界面后拉回 | App 内引导 |
| 电池优化白名单 | 防省电策略延迟闹钟 | App 内引导 |
| 自启动 / 锁屏显示 / 后台弹出（MIUI 等私有开关） | 开机恢复与后台弹出 | 系统设置手动开启；开发者可用 `weld-perms.ps1` 经 adb 批量授权 |

> 注意：MIUI 在每次重装 APK 后会重置私有权限开关，重装后需重新开启。

## 构建

```bash
git clone https://github.com/tutouqiang/ForceAlarm.git
# Android Studio 直接打开，或命令行：
./gradlew assembleDebug    # 测试包
./gradlew installDebug     # 连接设备装机
```

- 需要 JDK 17+ 与 Android SDK（Android Studio 自动配置）
- 打 release 包需自备 keystore，并在 `local.properties` 中配置 `FORCEALARM_STORE_PASSWORD` / `FORCEALARM_KEY_PASSWORD`
- 安装包见 [Releases](../../releases)

## 安装须知

1. 允许"未知来源安装"
2. 打开 App 按引导完成权限面板中的各项
3. 小米/MIUI 用户需额外手动开启：锁屏显示、后台弹出界面、自启动（设置 → 应用管理 → ForceAlarm）

## 已知限制

- 各国产 ROM 的私有权限（自启动、锁屏显示等）没有公开检测接口，App 只能引导用户开启，无法确认状态
- 刷 App 计时依赖使用情况访问权限
- 包名仍为 `com.example.forcealarm`

## License

[MIT](LICENSE)
