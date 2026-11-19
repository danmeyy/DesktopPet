package com.dsh.pet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!android.provider.Settings.canDrawOverlays(context)) return;
        // 用户点过「收起宠物」就别再自己回来：开机、覆盖安装都会走到这里，
        // 少了这一句，收起之后重启一次她又在桌面上了。
        if (!Config.wanted(context)) return;
        Intent s = new Intent(context, OverlayService.class);
        s.setAction(OverlayService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(s);
        else context.startService(s);
    }
}
