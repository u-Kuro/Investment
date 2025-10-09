package com.example.investment.util;

public class Constants {
    // Cache
    public static final String CACHE_FILE_HIGHEST = "highest_ticker.ser";
    public static final String CACHE_FILE_DATA = "ticker_cache.ser";
    
    // Preferences
    public static final String PREFS_NAME = "TickerFilterPrefs";
    public static final String PREF_FILTER_TYPE = "filterType";
    public static final String PREF_FILTER_STATUS = "filterStatus";
    
    // Yahoo Finance (query1: HTTP 1.0, query2: HTTP 1.1)
    public static final String YAHOO_CHART_URL = "https://query2.finance.yahoo.com/v8/finance/chart/";
    public static final String YAHOO_SCREENER_URL = "https://query2.finance.yahoo.com/v1/finance/screener/predefined/saved";
    
    // Yahoo Finance Screener Parameters
    public static final String SCREENER_CRYPTO = "ALL_CRYPTOCURRENCIES_US";
    public static final String SCREENER_STOCKS = "LARGE_CAP_STOCKS";
    public static final int SCREENER_COUNT = 250;

    // Price States
    public static final String PRICE_ABOVE = "above_upper";
    public static final String PRICE_BELOW = "below_lower";
    public static final String PRICE_BETWEEN = "between";

    // Notification Channel IDs
    public static final String CHANNEL_SERVICE = "ticker_updates";
    public static final String CHANNEL_HIGHEST = "highest_ticker";
    public static final String CHANNEL_PRICE_CROSS = "price_cross";
    public static final String CHANNEL_WARNING = "warning";

    // Notification IDs
    public static final int ID_SERVICE = 1;
    public static final int ID_HIGHEST = 2;
    public static final int ID_PRICE_CROSS = 3;
    public static final int ID_WARNING = 4;
    public static final int ID_MISSING = 5;

    // Notification Channel Names
    public static final String NAME_SERVICE = "Ticker Updates";
    public static final String NAME_HIGHEST = "New Highest Performer";
    public static final String NAME_PRICE_CROSS = "Investment Opportunity";
    public static final String NAME_WARNING = "Investment Warning";
    public static final String NAME_MISSING = "Investment Missing";

    // Market Cap Settings
    public static final String PREF_MARKET_CAP_THRESHOLD = "market_cap_threshold";
    public static final long DEFAULT_MARKET_CAP_THRESHOLD = 10_000_000_000L;
} 