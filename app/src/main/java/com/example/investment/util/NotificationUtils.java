package com.example.investment.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.example.investment.R;

@RequiresApi(api = Build.VERSION_CODES.O)
public class NotificationUtils {
    public static NotificationCompat.Builder createNotification(
        Context context,
        String channelId,
        String channelName,
        int importance,
        boolean enableVibration
    ) {
        NotificationChannel channel = new NotificationChannel(channelId, channelName, importance);
        channel.enableVibration(enableVibration);
        
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
        
        return new NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.logo)
            .setAutoCancel(true);
    }
    
    public static PendingIntent createActivityIntent(Context context, Class<?> activityClass) {
        Intent intent = new Intent(context, activityClass);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }
} 