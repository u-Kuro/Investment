package com.example.investment.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.example.investment.task.WorkerManager;

public class BootReceiver extends BroadcastReceiver {
    private static final String QUICKBOOT_POWERON_ACTION = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        String action = intent.getAction();
        if (
            Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            || QUICKBOOT_POWERON_ACTION.equals(action)
        ) {
            WorkerManager.scheduleTickerWork(context);
        }
    }
}