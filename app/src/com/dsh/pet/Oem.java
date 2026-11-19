package com.dsh.pet;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * 各家 ROM 的差别主要在三个开关上：自启动、后台弹出界面、电池优化。
 * 这里做两件事：认出机型，给出对应路径；能直接跳的尽量跳过去（跳不过就回退到应用详情页）。
 */
public final class Oem {

    public static final int XIAOMI = 1;
    public static final int HUAWEI = 2;
    public static final int OPPO = 3;
    public static final int VIVO = 4;
    public static final int SAMSUNG = 5;
    public static final int STOCK = 6;
    public static final int MEIZU = 7;
    public static final int ASUS = 8;
    public static final int ZTE = 9;
    public static final int MOTOROLA = 10;
    public static final int LENOVO = 11;
    public static final int NOTHING = 12;

    private Oem() {
    }

    public static int brand() {
        String b = (Build.BRAND == null ? "" : Build.BRAND).toLowerCase();
        String m = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER).toLowerCase();
        String all = b + " " + m;
        if (all.contains("xiaomi") || all.contains("redmi") || all.contains("poco")) return XIAOMI;
        if (all.contains("huawei") || all.contains("honor")) return HUAWEI;
        if (all.contains("oppo") || all.contains("realme") || all.contains("oneplus")) return OPPO;
        if (all.contains("vivo") || all.contains("iqoo")) return VIVO;
        if (all.contains("samsung")) return SAMSUNG;
        if (all.contains("meizu")) return MEIZU;
        if (all.contains("asus")) return ASUS;
        if (all.contains("zte") || all.contains("nubia") || all.contains("redmagic")) return ZTE;
        if (all.contains("motorola") || all.contains("moto")) return MOTOROLA;
        if (all.contains("lenovo")) return LENOVO;
        if (all.contains("nothing")) return NOTHING;
        return STOCK;
    }

    /** 卡片标题，例如「小米 / Redmi（HyperOS）」 */
    public static String title() {
        String model = Build.MODEL == null ? "" : Build.MODEL;
        switch (brand()) {
            case XIAOMI:
                return "小米 / Redmi / POCO（HyperOS） · " + model;
            case HUAWEI:
                return "华为 / 荣耀（HarmonyOS / EMUI） · " + model;
            case OPPO:
                return "OPPO / realme / 一加（ColorOS） · " + model;
            case VIVO:
                return "vivo / iQOO（OriginOS） · " + model;
            case SAMSUNG:
                return "三星（One UI） · " + model;
            case MEIZU:
                return "魅族（Flyme） · " + model;
            case ASUS:
                return "华硕（ZenUI） · " + model;
            case ZTE:
                return "中兴 / 努比亚 / 红魔（MyOS） · " + model;
            case MOTOROLA:
                return "摩托罗拉（My UX） · " + model;
            case LENOVO:
                return "联想（ZUI） · " + model;
            case NOTHING:
                return "Nothing（Nothing OS） · " + model;
            default:
                return "原生 / 其它 Android · " + model;
        }
    }

    /** 三条针对本机的设置说明；顺序就是重要性顺序。 */
    public static String[][] tips() {
        switch (brand()) {
            case XIAOMI:
                return new String[][]{
                        {"自启动", "设置 → 应用设置 → 应用管理 → 桌面宠物 → 自启动，打开"},
                        {"后台弹出界面", "同一页 → 其他权限 → 后台弹出界面，选「始终允许」（不给的话点图标有时唤不起她）"},
                        {"电池", "同一页 → 省电策略 → 无限制；再去 设置 → 省电与电池 → 应用智能省电 里把她设为「无限制」"},
                };
            case HUAWEI:
                return new String[][]{
                        {"自启动", "设置 → 应用 → 应用启动管理 → 桌面宠物 → 关掉「自动管理」，勾上允许自启动/关联启动/后台活动"},
                        {"悬浮窗", "设置 → 应用 → 权限管理 → 悬浮窗 → 桌面宠物，允许"},
                        {"后台弹出", "设置 → 应用 → 权限管理 → 后台弹出界面（部分版本叫「悬浮窗」下方），允许"},
                        {"电池", "设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接，打开"},
                };
            case OPPO:
                return new String[][]{
                        {"自启动", "设置 → 应用管理 → 桌面宠物 → 自启动/关联启动，都打开"},
                        {"后台弹出", "同一页 → 权限管理 → 悬浮窗 + 后台弹出界面，允许"},
                        {"电池", "设置 → 电池 → 应用耗电管理 → 桌面宠物 → 允许后台运行 / 允许自启动"},
                };
            case VIVO:
                return new String[][]{
                        {"自启动", "设置 → 应用与权限 → 权限管理 → 自启动 → 桌面宠物，打开"},
                        {"悬浮窗", "同一处 → 悬浮窗，允许（OriginOS 4 起在「悬浮窗与画中画」里）"},
                        {"后台弹出", "同一处 → 后台弹出界面，允许（不给的话点图标唤不起她）"},
                        {"后台", "设置 → 电池 → 后台耗电管理 → 桌面宠物 → 允许后台高耗电"},
                };
            case SAMSUNG:
                return new String[][]{
                        {"悬浮窗", "设置 → 应用程序 → 桌面宠物 → 在其他应用上层显示，允许"},
                        {"自动运行", "设置 → 电池 → 后台使用限制 → 自动运行应用，把桌面宠物加进去"},
                        {"电池", "设置 → 电池 → 后台使用限制 → 把桌面宠物加入「永不休眠的应用」"},
                        {"通知", "设置 → 通知 → 桌面宠物，允许通知（收起按钮在通知里）"},
                };
            case MEIZU:
                return new String[][]{
                        {"自启动", "设置 → 应用管理 → 桌面宠物 → 权限管理 → 自启动，打开"},
                        {"后台", "设置 → 应用管理 → 桌面宠物 → 耗电管理 → 允许后台运行"},
                        {"悬浮窗", "同一页 → 悬浮窗 / 显示在其他应用上层，允许"},
                };
            case ASUS:
                return new String[][]{
                        {"自启动", "设置 → 应用与通知 → 桌面宠物 → 自启动，打开（ZenUI 叫「自动启动」）"},
                        {"后台", "设置 → 电池 → 省电模式 → 排除应用里加上桌面宠物"},
                        {"悬浮窗", "设置 → 应用 → 特殊访问权限 → 显示在其他应用上层，允许"},
                };
            case ZTE:
                return new String[][]{
                        {"自启动", "设置 → 应用 → 应用管理 → 桌面宠物 → 自启动，打开"},
                        {"后台", "设置 → 电池 → 应用耗电管理 → 桌面宠物 → 允许后台运行"},
                        {"悬浮窗", "设置 → 应用 → 特殊权限 → 悬浮窗，允许"},
                };
            case MOTOROLA:
                return new String[][]{
                        {"悬浮窗", "设置 → 应用 → 特殊访问权限 → 显示在其他应用上层，允许"},
                        {"电池", "设置 → 电池 → 电池优化 → 桌面宠物 → 不优化（Moto 还可能在「自适应电池」里限制）"},
                        {"通知", "设置 → 通知 → 桌面宠物，允许通知（收起按钮在通知里）"},
                };
            case LENOVO:
                return new String[][]{
                        {"自启动", "设置 → 应用管理 → 桌面宠物 → 自启动，打开"},
                        {"后台", "设置 → 电池 → 后台管理 → 桌面宠物 → 允许后台运行"},
                        {"悬浮窗", "设置 → 应用 → 特殊权限 → 显示在其他应用上层，允许"},
                };
            case NOTHING:
                return new String[][]{
                        {"悬浮窗", "设置 → 应用 → 桌面宠物 → 显示在其他应用上层，允许"},
                        {"电池", "设置 → 电池 → 自适应电池 → 关掉（或把她排除）"},
                        {"通知", "设置 → 通知 → 桌面宠物，允许通知（收起按钮在通知里）"},
                };
            default:
                return new String[][]{
                        {"悬浮窗", "设置 → 应用 → 特殊应用权限 → 显示在其他应用的上层 → 桌面宠物，允许"},
                        {"电池", "设置 → 电池 → 电池优化 → 桌面宠物 → 不优化"},
                        {"通知", "设置 → 通知 → 桌面宠物，允许通知（收起按钮在通知里）"},
                };
        }
    }

    /** 尽量跳到「自启动/后台」这类设置页；跳不过就退到应用详情。 */
    public static void openAutoStart(Context ctx) {
        String[][] targets = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.miui.securitycenter", "com.miui.powercenter.PowerSettings"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
                {"com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"},
                {"com.zte.heartyservice", "com.zte.heartyservice.setting.ClearMemoryActivity"},
                {"com.motorola.launcher3", "com.motorola.launcher3.settings.AppLaunchSettingsActivity"},
        };
        for (String[] t : targets) {
            Intent i = new Intent();
            i.setComponent(new ComponentName(t[0], t[1]));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        openAppDetail(ctx);
    }

    public static void openBattery(Context ctx) {
        Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        openAppDetail(ctx);
    }

    public static void openAppDetail(Context ctx) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 无障碍被系统回收时，各家该去哪儿看。 */
    public static String a11yTip() {
        switch (brand()) {
            case XIAOMI:
                return "小米：设置 → 无障碍 → 已下载的应用 → 桌面宠物。HyperOS 会在一段时间不用后自动关掉它，关掉后要重新打开；有时还要先「启动宠物」再授权，否则这一页会显示成异常状态。";
            case HUAWEI:
                return "华为/荣耀：设置 → 辅助功能 → 无障碍 → 已安装的服务 → 桌面宠物。EMUI 常在清理后台时一起关掉无障碍，重新打开即可。";
            case OPPO:
            case VIVO:
                return "OPPO/vivo：设置 → 系统设置（或快捷与辅助）→ 无障碍 → 已下载的服务 → 桌面宠物。ColorOS/OriginOS 在「省电」触发后可能回收，需回来重开。";
            case SAMSUNG:
                return "三星：设置 → 辅助功能 → 已安装的服务 → 桌面宠物。One UI 在「深度休眠」后可能关闭，把 App 从休眠列表里拿掉。";
            case MEIZU:
                return "魅族：设置 → 辅助功能 → 无障碍 → 桌面宠物。Flyme 的一键清理会关闭无障碍。";
            default:
                return "设置 → 无障碍 → 已安装的服务 → 桌面宠物。系统在回收后台时可能一并关掉，回来重新打开即可。";
        }
    }
}
