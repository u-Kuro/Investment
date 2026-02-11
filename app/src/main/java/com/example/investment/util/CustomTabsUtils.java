package com.example.investment.util;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsSession;

import com.example.investment.R;

import java.util.ArrayList;
import java.util.List;

public class CustomTabsUtils {
    private CustomTabsClient mCustomTabsClient;
    private CustomTabsSession mCustomTabsSession;
    private static CustomTabsUtils INSTANCE;

    public static CustomTabsUtils getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CustomTabsUtils();
        }
        return INSTANCE;
    }

    private CustomTabsUtils() {}

    public void warmup(Context context) {
        if (mCustomTabsClient != null) return;

        String mPackageNameToBind = CustomTabsClient.getPackageName(context, null);
        if (mPackageNameToBind == null) return;

        CustomTabsServiceConnection mCustomTabsServiceConnection = new CustomTabsServiceConnection() {
            @Override
            public void onCustomTabsServiceConnected(@NonNull android.content.ComponentName name, @NonNull CustomTabsClient client) {
                mCustomTabsClient = client;
                mCustomTabsClient.warmup(0);
                mCustomTabsSession = mCustomTabsClient.newSession(new CustomTabsCallback());
                if (mCustomTabsSession == null) return;
                
                // Add finance.yahoo.com as the origin
                Uri originUri = Uri.parse("https://finance.yahoo.com/");
                Uri origin = new Uri.Builder()
                    .scheme(originUri.getScheme())
                    .authority(originUri.getAuthority())
                    .build();
                
                List<Bundle> otherLikelyBundles = new ArrayList<>();
                List<String> paths = new ArrayList<>();
                paths.add("/quote");
                
                for (String path : paths) {
                    Bundle urlBundle = new Bundle();
                    Uri pathUri = origin.buildUpon().path(path).build();
                    urlBundle.putParcelable("url", pathUri);
                    otherLikelyBundles.add(urlBundle);
                }
                
                mCustomTabsSession.mayLaunchUrl(origin, new Bundle(), otherLikelyBundles);
            }

            @Override
            public void onServiceDisconnected(android.content.ComponentName name) {
                mCustomTabsClient = null;
                mCustomTabsSession = null;
            }
        };
        
        CustomTabsClient.bindCustomTabsService(context, mPackageNameToBind, mCustomTabsServiceConnection);
    }

    public void launchUrl(Context context, Uri uri, Integer colorId, boolean setAnimations) {
        CustomTabsIntent.Builder customTabsIntentBuilder = getBuilder().setShowTitle(true);
        
        if (colorId != null) {
            customTabsIntentBuilder.setDefaultColorSchemeParams(
                new CustomTabColorSchemeParams.Builder()
                .setToolbarColor(colorId)
                .build()
            );
        }
        
        if (setAnimations) {
            customTabsIntentBuilder
            .setStartAnimations(context, R.anim.fade_in, R.anim.remove)
            .setExitAnimations(context, R.anim.fade_out, R.anim.remove);
        }
        
        CustomTabsIntent customTabsIntent = customTabsIntentBuilder.build();
        customTabsIntent.launchUrl(context, uri);
    }

    public CustomTabsIntent.Builder getBuilder() {
        return new CustomTabsIntent.Builder(mCustomTabsSession);
    }
} 