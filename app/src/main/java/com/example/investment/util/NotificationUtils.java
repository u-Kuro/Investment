package com.example.investment.util;

import static com.example.investment.util.Constants.CHANNEL_SERVICE;
import static com.example.investment.util.Constants.ID_SERVICE;
import static com.example.investment.util.Constants.NAME_SERVICE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.example.investment.R;
import com.example.investment.service.TickerService;
import com.example.investment.MainActivity;

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
            .setSmallIcon(R.drawable.logo);
    }
    
    public static PendingIntent createActivityIntent(Context context, Class<?> activityClass) {
        Intent intent = new Intent(context, activityClass);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static Notification createLowPriorityServiceNotification(Context context, String title) {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_SERVICE,
            NAME_SERVICE,
            NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);

        Intent stopIntent = new Intent(context, TickerService.class);
        stopIntent.setAction(TickerService.STOP_SERVICE_ACTION);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            context, 
            0, 
            stopIntent, 
            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE
        );

        return new NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.sync_crossed, "STOP", stopPendingIntent)
            .setVibrate(null)
            .build();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void showLowPriorityServiceNotification(Context context, String message) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.notify(ID_SERVICE, createLowPriorityServiceNotification(context, message));
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void showHighPriorityNotification(
        Context context,
        String channelId,
        String title,
        String content,
        int notificationId
    ) {
        NotificationCompat.Builder builder = createNotification(
            context, 
            channelId,
            title,
            NotificationManager.IMPORTANCE_HIGH,
            true
        )
        .setContentTitle(title)
        .setContentText(content)
        .setContentIntent(createActivityIntent(context, MainActivity.class))
        .setAutoCancel(true);
        
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.notify(notificationId, builder.build());
    }
} 