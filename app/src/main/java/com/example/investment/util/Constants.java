package com.example.investment.util;

import java.util.concurrent.TimeUnit;

public class Constants {
    // File Data Records
    public static final String FILE_RECORD_TICKERS = "tickers.ser";
    public static final String FILE_RECORD_LEADING_TICKERS = "leading_tickers.ser";

    // Preferences
    public static final String PREFS_NAME = "TickerFilterPrefs";
    public static final String PREF_FILTER_TYPE = "filterType";
    public static final String PREF_FILTER_STATUS = "filterStatus";
    public static final String PREF_FILTER_MARKET_CAP_THRESHOLD = "filterMarketCapThreshold";
    
    // Yahoo Finance (query1: HTTP 1.0, query2: HTTP 1.1)
    public static final String YAHOO_CHART_URL = "https://query2.finance.yahoo.com/v8/finance/chart/";
    public static final String YAHOO_SCREENER_URL = "https://query2.finance.yahoo.com/v1/finance/screener/predefined/saved";
    public static final String YAHOO_CHART_URL_ALT = "https://query1.finance.yahoo.com/v8/finance/chart/";
    public static final String YAHOO_SCREENER_URL_ALT = "https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved";
    
    // Yahoo Finance Screener Parameters
    public static final String SCREENER_CRYPTO = "ALL_CRYPTOCURRENCIES_US";
    public static final String SCREENER_STOCKS = "LARGE_CAP_STOCKS";
    public static final long SCREENER_COUNT = 250;
    public static final long FIRST_TRADE_TIMESTAMP_SECONDS = -11607782632L; // March 1602

    // Yahoo Finance Request Limit
    public static final int MINIMUM_REQUEST_TIMEOUT_MILLISECONDS = 1800; // 2000 Request / Hour | (60 Minutes * 60 Seconds) / 2000 Request = 1.8 Seconds / Request

    // Asset Types
    public static final String CRYPTOCURRENCY_TYPE = "cryptocurrency";
    public static final String STOCK_TYPE = "stock";

    // Asset Intervals
    public static final String[] ASSET_INTERVALS = { "1d", "1wk", "1mo", "3mo" };
    public static final long[] ASSET_BASE_EXPIRY_DURATIONS_MS = {
        // Added 1 Day Periodic Update Buffer
        TimeUnit.DAYS.toMillis(2),   // 1d
        TimeUnit.DAYS.toMillis(8),   // 1wk
        TimeUnit.DAYS.toMillis(32),  // 1mo
        TimeUnit.DAYS.toMillis(93)   // 3mo
    };

    // Asset Price States
    public static final String PRICE_ABOVE = "above_upper";
    public static final String PRICE_BELOW = "below_lower";
    public static final String PRICE_BETWEEN = "between";

    // Notification Channel IDs
    public static final String CHANNEL_ID_SERVICE = "investment_updates";
    public static final String CHANNEL_ID_GROUP_SUMMARY = "group_summary";
    public static final String CHANNEL_ID_NEW_LEADING_ASSET = "new_leading_asset";
    public static final String CHANNEL_ID_PROFIT_TAKING = "profit_taking";
    public static final String CHANNEL_ID_LEADING_ASSET_RECOVERY = "leading_asset_recovery";
    public static final String CHANNEL_ID_INVESTMENT_OPPORTUNITY = "investment_opportunity";
    public static final String CHANNEL_ID_LEADING_ASSET_RISK = "asset_risk";
    public static final String CHANNEL_ID_MISSING_REMOVED_LEADING_ASSET = "missing_removed_leading_asset";
    public static final String CHANNEL_ID_OUTDATED_LEADING_ASSET_DATA = "outdated_leading_asset_data";

    // Notification Channel Names
    public static final String CHANNEL_NAME_SERVICE = "Investment Updates";
    public static final String CHANNEL_NAME_GROUP_SUMMARY = "Group Summary";
    public static final String CHANNEL_NAME_NEW_LEADING_ASSET = "New Leading Asset";
    public static final String CHANNEL_NAME_PROFIT_TAKING = "Profit Taking";
    public static final String CHANNEL_NAME_LEADING_ASSET_RECOVERY = "Leading Asset Recovery";
    public static final String CHANNEL_NAME_INVESTMENT_OPPORTUNITY = "Investment Opportunity";
    public static final String CHANNEL_NAME_LEADING_ASSET_RISK = "Leading Asset Risk";
    public static final String CHANNEL_NAME_MISSING_REMOVED_LEADING_ASSET = "Missing/Removed Leading Asset";
    public static final String CHANNEL_NAME_OUTDATED_LEADING_ASSET_DATA = "Outdated Leading Asset Data";

    // Notification IDs
    public static final int NOTIFICATION_ID_SERVICE = 1;
    public static final int NOTIFICATION_ID_GROUP_SUMMARY = 2;
    public static final int NOTIFICATION_ID_NEW_LEADING_ASSET = 3;
    public static final int NOTIFICATION_ID_PROFIT_TAKING = 4;
    public static final int NOTIFICATION_ID_LEADING_ASSET_RECOVERY = 5;
    public static final int NOTIFICATION_ID_INVESTMENT_OPPORTUNITY = 6;
    public static final int NOTIFICATION_ID_LEADING_ASSET_RISK = 7;
    public static final int NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET = 8;
    public static final int NOTIFICATION_ID_OUTDATED_LEADING_ASSET_DATA = 9;

    // Notification Group Keys
    public static final String APP_NOTIFICATION_GROUP_KEY = "Investment";

    // Notification Messages
    public static final String OUTDATED_TICKER_NOTIFICATION_SUBTEXT = "OUTDATED";

    // Ticker Defaults
    public static final int SAVED_LEADING_TICKERS_COUNT = 4;

    // Filter Defaults
    public static final long DEFAULT_MARKET_CAP_THRESHOLD = 10_000_000_000L; // $ 10 Billion

    // Request Defaults
    public static final int MAX_FETCH_RETRY_COUNT = 3;
}