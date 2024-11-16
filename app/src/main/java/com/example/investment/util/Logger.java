package com.example.investment.util;

import android.util.Log;

public class Logger {
    private static final String TAG = "InvestmentApp";
    
    public static void error(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
    }
    
    public static void debug(String message) {
        Log.d(TAG, message);
    }
    
    public static void info(String message) {
        Log.i(TAG, message);
    }
} 