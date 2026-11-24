package com.wangzan101.mika.cap.phone.a11y;

import android.app.ActivityOptions;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.view.Display;

/**
 * 在单独的进程里打开 App 的启动页,打开就停。
 * 测试 APK 的组件运行在测试包自己的进程里、不带 Kotlin 标准库,所以用 Java 写。
 */
public class SpillLaunchService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String auth = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_AUTH) : null;
        String dialog = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_DIALOG) : null;
        String component = auth != null ? auth : dialog;
        if (component != null) {
            // 对话框样式或整屏的登录页:落在用户主屏。测试包自己在 Mika 的屏上有页面,
            // 不指定屏时系统也会把它放到那边;后台进程在主屏上没有页面时,系统按默认放到主屏,这里照那样指定主屏。
            Intent launch = new Intent().setComponent(new ComponentName(getPackageName(), component))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch, ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String target = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_TARGET) : null;
        Intent launch = target != null ? getPackageManager().getLaunchIntentForPackage(target) : null;
        if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
