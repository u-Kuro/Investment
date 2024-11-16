package com.example.investment.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import com.example.investment.service.TickerService;
import com.example.investment.util.WorkManagerUtil;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            WorkManagerUtil.scheduleTickerWork(context);
        }
    }
} 