package com.example.investment.util;

import android.util.Log;

public class Logger {
    private static final String TAG = "InvestmentApp";
    
    public static void error(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
    }
    
    /** @noinspection unused*/
    public static void debug(String message) {
        Log.d(TAG, message);
    }
} 