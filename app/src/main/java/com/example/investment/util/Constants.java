package com.example.investment.util;

public class Constants {
    // Cache
    public static final String CACHE_FILE_LEADING_TICKERS = "leading_tickers.ser";
    public static final String CACHE_FILE_DATA = "ticker_cache.ser";

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

    // Price States
    public static final String PRICE_ABOVE = "above_upper";
    public static final String PRICE_BELOW = "below_lower";
    public static final String PRICE_BETWEEN = "between";

    // Notification Channel IDs
    public static final String CHANNEL_ID_SERVICE = "ticker_updates";
    public static final String CHANNEL_ID_GROUP_SUMMARY = "group_summary";
    public static final String CHANNEL_ID_LEADING = "leading_ticker";
    public static final String CHANNEL_ID_PROFIT_TAKING = "price_cross";
    public static final String CHANNEL_ID_ASSET_IMPROVING = "asset_improving";
    public static final String CHANNEL_ID_INVESTMENT_OPPORTUNITY = "investment_opportunity";
    public static final String CHANNEL_ID_ASSET_RISK = "asset_risk";
    public static final String CHANNEL_ID_LEADING_ASSET_REMOVAL = "leading_asset_removal";


    // Notification Channel Names
    public static final String CHANNEL_NAME_SERVICE = "Ticker Updates";
    public static final String CHANNEL_NAME_GROUP_SUMMARY = "Group Summary";
    public static final String CHANNEL_NAME_LEADING = "New Leading Performer";
    public static final String CHANNEL_NAME_PROFIT_TAKING = "Profit Taking";
    public static final String CHANNEL_NAME_ASSET_IMPROVING = "Improving Asset";
    public static final String CHANNEL_NAME_INVESTMENT_OPPORTUNITY = "Investment Opportunity";
    public static final String CHANNEL_NAME_ASSET_RISK = "Asset Risk Warning";
    public static final String CHANNEL_NAME_LEADING_ASSET_REMOVAL = "Leading Asset Removal";

    // Notification IDs
    public static final int NOTIFICATION_ID_SERVICE = 1;
    public static final int NOTIFICATION_ID_GROUP_SUMMARY = 2;
    public static final int NOTIFICATION_ID_LEADING = 3;
    public static final int NOTIFICATION_ID_PROFIT_TAKING = 4;
    public static final int NOTIFICATION_ID_ASSET_IMPROVING = 5;
    public static final int NOTIFICATION_ID_INVESTMENT_OPPORTUNITY = 6;
    public static final int NOTIFICATION_ID_ASSET_RISK = 7;
    public static final int NOTIFICATION_ID_LEADING_ASSET_REMOVAL = 8;

    // Notification Group Keys
    public static final String APP_NOTIFICATION_GROUP_KEY = "Investment";

    // Ticker Defaults
    public static final int SAVED_LEADING_TICKERS_COUNT = 4;

    // Filter Defaults
    public static final long DEFAULT_MARKET_CAP_THRESHOLD = 10_000_000_000L; // $ 10 Billion

    // Request Defaults
    public static final int MAX_FETCH_RETRY_COUNT = 3;
} 