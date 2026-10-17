package com.example.forcealarm.alarm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 机型适配层：国产 ROM 的"省电/自启动/后台弹出"各说各话，
 * 策略 = 识别品牌 → 给手开路径文案 → 尝试跳官方设置页（跳不动兜底应用详情页）。
 * 【为什么不全靠检测】这些私有开关没有公开 API，检测不到就只能引导；
 * 能确定性检测并拿到的（电池优化豁免）就进权限面板当硬检查项。
 */
enum class VendorKind { XIAOMI, HUAWEI, HONOR, OPPO, ONEPLUS, VIVO, SAMSUNG, OTHER }

class Vendor(val kind: VendorKind, val label: String, val tips: List<String>)

object VendorDetector {

    fun current(): Vendor {
        val tag = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            tag.contains("xiaomi") || tag.contains("redmi") || tag.contains("poco") ->
                Vendor(VendorKind.XIAOMI, "小米/MIUI·HyperOS", listOf(
                    "设置→应用设置→ForceAlarm：开启【自启动】【锁屏显示】【后台弹出界面】",
                    "后两项是 MIUI 私有开关，普通 App 检测不到，靠引导手开；开发可用 adb 焊（weld-perms.ps1）"
                ))
            tag.contains("huawei") -> Vendor(VendorKind.HUAWEI, "华为/EMUI·HarmonyOS", listOf(
                "设置→电池→应用启动管理：ForceAlarm 关智能管理、改手动（三项全开）",
                "设置→通知：允许锁屏通知与横幅；近期任务卡片下拉加锁"
            ))
            tag.contains("honor") -> Vendor(VendorKind.HONOR, "荣耀/MagicOS", listOf(
                "设置→电池→启动管理：ForceAlarm 改手动启动",
                "通知设置里允许锁屏通知；最近任务下拉锁定应用"
            ))
            tag.contains("oppo") || tag.contains("realme") -> Vendor(VendorKind.OPPO, "OPPO/一加(Old)/realme·ColorOS", listOf(
                "设置→电池→更多设置→应用智能省电：ForceAlarm 改【允许后台活动】",
                "应用信息→通知：允许；桌面卡片下拉锁定最近任务"
            ))
            tag.contains("oneplus") -> Vendor(VendorKind.ONEPLUS, "一加/HyperOS", listOf(
                "较新机型与小米同源：开启自启动与后台弹出；旧机型在电池设置改无限制"
            ))
            tag.contains("vivo") || tag.contains("iqoo") -> Vendor(VendorKind.VIVO, "vivo/iQOO·OriginOS", listOf(
                "i管家→应用管理→权限→自启动：允许 ForceAlarm",
                "设置→电池→后台高耗电：允许；高刷新通知类保持开启"
            ))
            tag.contains("samsung") -> Vendor(VendorKind.SAMSUNG, "三星/OneUI", listOf(
                "设置→电池→从睡眠中应用：把 ForceAlarm 放入【永不休眠】",
                "应用信息→通知：允许锁定屏幕；别把 App 加入卸载清理"
            ))
            else -> Vendor(VendorKind.OTHER, "标准安卓/" + Build.MANUFACTURER, listOf(
                "把 ForceAlarm 加入省电白名单（见下方【电池优化豁免】一项）",
                "如有【自启动】类开关，在系统管家类应用里手动放行"
            ))
        }
    }

    /** 电池优化豁免（Doze 白名单）：全品牌通用且可确定性检测 */
    fun isIgnoringBattery(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** 跳厂商设置页：按品牌试已知组件，全部失败兜底"应用详情页"（永远能打开） */
    fun openVendorSettings(ctx: Context) {
        val candidates: List<ComponentName> = when (current().kind) {
            VendorKind.XIAOMI -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                ComponentName("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity")
            )
            VendorKind.HUAWEI -> listOf(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
            )
            VendorKind.HONOR -> listOf(
                ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            )
            VendorKind.OPPO -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
            )
            VendorKind.VIVO -> listOf(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            )
            VendorKind.SAMSUNG -> listOf(
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
            )
            else -> emptyList()
        }
        for (c in candidates) {
            try {
                ctx.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* 新版 ROM 常锁深链，继续试下一个 */ }
        }
        openAppDetails(ctx)
    }

    /** 电池优化豁免申请页；MIUI 等个别 ROM 会拒此 Intent，兜底白名单总列表页 */
    fun openBatteryExempt(ctx: Context) {
        val pkg = Uri.parse("package:" + ctx.packageName)
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (_: Exception) { }
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (_: Exception) { }
        openAppDetails(ctx)
    }

    /** 万能兜底：本应用的系统详情页，任何安卓都开得了 */
    fun openAppDetails(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
    }
}
