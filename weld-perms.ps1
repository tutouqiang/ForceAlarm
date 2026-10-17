# ForceAlarm 权限焊接脚本
# 用法：手机 USB 连电脑 + 允许调试，然后在 PowerShell 里运行：
#   .\weld-perms.ps1                              # 只焊自家六锁
#   .\weld-perms.ps1 -TargetApps "tv.danmaku.bili" # 顺带给目标软件开【锁屏显示】
param(
    [string[]]$TargetApps = @("com.ss.android.ugc.aweme")
)
$adb = "F:\android-home\Sdk\platform-tools\adb.exe"
$pkg = "com.example.forcealarm"

$ops = @(
    "10020",                    # 锁屏显示
    "10021",                    # 获取应用列表
    "10022",                    # 后台弹出界面
    "USE_FULL_SCREEN_INTENT",   # 全屏意图（Android 14+）
    "android:get_usage_stats",  # 使用情况访问
    "SYSTEM_ALERT_WINDOW",      # 悬浮窗（后台弹 Activity 的豁免证）
    "android:schedule_exact_alarm"  # 精确闹钟（缺了它 setAlarmClock 直接抱 SecurityException）
)

Write-Host "== 给 $pkg 焊六锁 =="
1..2 | ForEach-Object {
    $ops | ForEach-Object { & $adb shell "cmd appops set $pkg $_ allow" }
    Start-Sleep -Seconds 2
}

foreach ($t in $TargetApps) {
    Write-Host "== 给 $t 开【锁屏显示】 =="
    & $adb shell "cmd appops set $t 10020 allow"
}

Write-Host "== 电池优化白名单（全品牌通用，Doze 豁免）=="
& $adb shell "dumpsys deviceidle whitelist +$pkg"

Write-Host "== verify =="
& $adb shell "cmd appops get $pkg 10020"
& $adb shell "cmd appops get $pkg 10022"
& $adb shell "cmd appops get $pkg USE_FULL_SCREEN_INTENT"
& $adb shell "cmd appops get $pkg android:get_usage_stats"
& $adb shell "cmd appops get $pkg SYSTEM_ALERT_WINDOW"
