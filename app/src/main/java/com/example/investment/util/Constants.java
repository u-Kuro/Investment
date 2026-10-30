package com.example.investment.util;

import android.os.Handler;
import android.os.Looper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class Constants {
    // Runnable Tasks Handlers
    public static final Handler UI_THREAD = new Handler(Looper.getMainLooper());
    public static final SafeSingleTaskRunner RENDER_THREAD = new SafeSingleTaskRunner();
    public static final SafeSingleTaskRunner EVENT_THREAD = new SafeSingleTaskRunner();
    public static final SafeSingleTaskRunner DEFERRED_THREAD = new SafeSingleTaskRunner();
    public static final SafeSingleTaskRunner BACKGROUND_THREAD = new SafeSingleTaskRunner();
    public static void ASSERT_MAIN_THREAD() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("This method must be called on the main thread.");
    }

    // File Data Records
    public static final String FILE_RECORD_TICKERS = "tickers.ser";
    public static final String FILE_RECORD_PASSIVE_LEADING_TICKERS = "passive_leading_tickers.ser";
    public static final String FILE_RECORD_MOMENTUM_LEADING_TICKERS = "momentum_leading_tickers.ser";
    public static final String FILE_RECORD_PROXIES = "proxies.ser";
    public static final String FILE_RECORD_SCREENER_CRYPTO = "screener_crypto.ser";
    public static final String FILE_RECORD_SCREENER_STOCKS = "screener_stocks.ser";

    // Preferences
    public static final String PREFS_NAME = "Investment Shared Preferences";
    public static final String PREF_LIST_VIEW_ASSET_TYPE = "List View Asset Type";
    public static final String PREF_INVESTMENT_TYPE = "Investment Type";
    public static final String PREF_ASSET_TYPE = "Asset Type";
    public static final String PREF_NUMBER_OF_TOP_HOLDINGS = "Number of Top Holdings";
    public static final String PREF_MARKET_CAP_THRESHOLD = "Market Cap Threshold";
    public static final String PREF_DAILY_DOLLAR_VOLUME_THRESHOLD = "Daily Dollar Volume Threshold";
    public static final String PREF_AGE_IN_SECONDS_THRESHOLD = "Age Threshold (Seconds)";
    public static final String PREF_EXPORT_FOLDER_URI = "Export Folder Uri";
    public static final String PREF_BACKUP_OWED_SINCE = "Backup Owed Since";

    // Backup
    public static final String BACKUP_FILE_NAME = "investment-backup.zip";
    public static final String BACKUP_TEMP_FILE_NAME = "investment-backup.tmp";
    public static final String BACKUP_ENTRY_PREFERENCES = "preferences.json";
    public static final int BACKUP_VERSION = 1;
    public static final String BACKUP_WORK_NAME = "backup_work";
    public static final String[] BACKUP_DATA_RECORDS = {
        FILE_RECORD_TICKERS,
        FILE_RECORD_PASSIVE_LEADING_TICKERS,
        FILE_RECORD_MOMENTUM_LEADING_TICKERS,
        FILE_RECORD_PROXIES,
        FILE_RECORD_SCREENER_STOCKS,
        FILE_RECORD_SCREENER_CRYPTO
    };
    
    // Yahoo Finance (query1: HTTP 1.0, query2: HTTP 1.1)
    public static final String YAHOO_CHART_URL = "https://query2.finance.yahoo.com/v8/finance/chart/";
    public static final String YAHOO_SCREENER_URL = "https://query2.finance.yahoo.com/v1/finance/screener/predefined/saved";
    public static final String YAHOO_CHART_URL_ALT = "https://query1.finance.yahoo.com/v8/finance/chart/";
    public static final String YAHOO_SCREENER_URL_ALT = "https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved";
    
    // Yahoo Finance Screener Parameters
    public static final String SCREENER_CRYPTO = "ALL_CRYPTOCURRENCIES_US";
    public static final String SCREENER_STOCKS = "LARGE_CAP_STOCKS";
    public static final int MAX_SCREENER_COUNT = 250;
    public static final BigInteger FIRST_TRADE_TIMESTAMP_SECONDS = BigInteger.valueOf(-11_607_782_632L); // March 1602

    // Yahoo Finance Request Limit
    public static final int MINIMUM_REQUEST_TIMEOUT_MILLISECONDS = 1800; // 2000 Request / Hour | (60 Minutes * 60 Seconds) / 2000 Request = 1.8 Seconds / Request

    public static final String[] PROXY_LIST_URLS = {
        "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/protocols/socks5/data.json", // SOCKS5, refreshed every 5 Minutes
        "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/protocols/socks5/data.json" // Same file on a different host
    };
    public static final int PROXY_LIST_TIMEOUT_MILLISECONDS = 30_000; // 30 Seconds
    public static final long PROXY_REFRESH_MILLISECONDS = 300_000; // 5 Minutes
    public static final int REQUEST_CONNECT_TIMEOUT_MILLISECONDS = 5_000; // 5 Seconds (For Proxies)
    public static final int REQUEST_CALL_TIMEOUT_MILLISECONDS = 30_000; // 30 Seconds (For Proxies)
    public static final long PROXY_COOLDOWN_MILLISECONDS = 600_000; // 10 Minutes
    public static final long PROXY_MAX_COOLDOWN_MILLISECONDS = 3_600_000; // 1 Hour
    public static final int PROXY_POOL_SIZE = 100;
    public static final int PROXY_MAX_PORTS_PER_IP = 10;

    // Asset Types
    public static final String ALL_TYPE = "All";
    public static final String CRYPTOCURRENCY_TYPE = "Crypto";
    public static final String STOCK_TYPE = "Stock";

    // Investment Type
    public static final String PASSIVE_INVESTMENT_TYPE = "Passive Investing";
    public static final String MOMENTUM_INVESTMENT_TYPE = "Momentum Investing";

    // BigDecimal Constants
    public static final BigDecimal THOUSAND_BD = BigDecimal.valueOf(1_000);
    public static final BigInteger THOUSAND_BI = BigInteger.valueOf(1_000);
    public static final BigInteger DAYS_PER_WEEK = BigInteger.valueOf(7);
    public static final BigInteger MILLIS_PER_DAY = BigInteger.valueOf(86_400_000);

    // Asset Intervals
    public static final String[] ASSET_INTERVALS = { "1d", "1wk", "1mo", "3mo" };
    public static final Map<String, BigInteger> ASSET_DURATION_UNTIL_NEXT_UPDATE_MS = Map.ofEntries(
        // Interval time * 2 until Next Update
        // Periodic worker's buffer handles DST shifts and Leap Seconds (1hr + 2s)
        Map.entry("1d", BigInteger.valueOf(172_800_000)), // 1 + 1 Days to Milliseconds
        Map.entry("1wk", BigInteger.valueOf(1_209_600_000)), // 7 + 7
        Map.entry("1mo", BigInteger.valueOf(5_356_800_000L)), // 31 + 31  (July 1 to Aug 31)
        Map.entry("3mo", BigInteger.valueOf(15_897_600_000L)) // 31 + 31 + 30 + 31 + 30 + 31 (July 1 to December 31)
    );
    public static final Map<String, BigDecimal> ASSET_DURATION_DAYS = Map.ofEntries(
        Map.entry("1d", BigDecimal.ONE), // 1
        Map.entry("1wk", BigDecimal.valueOf(DAYS_PER_WEEK.intValue())), // 7
        Map.entry("1mo", BigDecimal.valueOf(31)), // 31 (July)
        Map.entry("3mo", BigDecimal.valueOf(92)) // 31 + 31 + 30 (July 1 to September 30)
    );
    public static final BigInteger PERIODIC_WORKER_UPDATE_BUFFER_MS = MILLIS_PER_DAY; // 1D

    // Asset Price States
    public static final String PRICE_ABOVE = "above_upper";
    public static final String PRICE_BELOW = "below_lower";
    public static final String PRICE_BETWEEN = "between";

    // Notification Channel IDs
    public static final String CHANNEL_ID_SERVICE = "investment_updates";
    public static final String CHANNEL_ID_NEW_LEADING_ASSET = "new_leading_asset";
    public static final String CHANNEL_ID_PROFIT_TAKING = "profit_taking";
    public static final String CHANNEL_ID_LEADING_ASSET_RECOVERY = "leading_asset_recovery";
    public static final String CHANNEL_ID_INVESTMENT_OPPORTUNITY = "investment_opportunity";
    public static final String CHANNEL_ID_LEADING_ASSET_RISK = "asset_risk";
    public static final String CHANNEL_ID_MISSING_REMOVED_LEADING_ASSET = "missing_removed_leading_asset";
    public static final String CHANNEL_ID_OUTDATED_LEADING_ASSET_DATA = "outdated_leading_asset_data";

    // Notification Channel Names
    public static final String CHANNEL_NAME_SERVICE = "Investment Updates";
    public static final String CHANNEL_NAME_NEW_LEADING_ASSET = "New Leading Asset";
    public static final String CHANNEL_NAME_PROFIT_TAKING = "Profit Taking";
    public static final String CHANNEL_NAME_LEADING_ASSET_RECOVERY = "Leading Asset Recovery";
    public static final String CHANNEL_NAME_INVESTMENT_OPPORTUNITY = "Investment Opportunity";
    public static final String CHANNEL_NAME_LEADING_ASSET_RISK = "Leading Asset Risk";
    public static final String CHANNEL_NAME_MISSING_REMOVED_LEADING_ASSET = "Missing/Removed Leading Asset";
    public static final String CHANNEL_NAME_OUTDATED_LEADING_ASSET_DATA = "Outdated Leading Asset Data";

    // Notification IDs
    public static final int NOTIFICATION_ID_SERVICE = 1;
    public static final int NOTIFICATION_ID_PASSIVE_INVESTMENT_GROUP_SUMMARY = 2;
    public static final int NOTIFICATION_ID_MOMENTUM_INVESTMENT_GROUP_SUMMARY = 3;
    public static final int NOTIFICATION_ID_NEW_LEADING_ASSET = 4;
    public static final int NOTIFICATION_ID_PROFIT_TAKING = 5;
    public static final int NOTIFICATION_ID_LEADING_ASSET_RECOVERY = 6;
    public static final int NOTIFICATION_ID_INVESTMENT_OPPORTUNITY = 7;
    public static final int NOTIFICATION_ID_LEADING_ASSET_RISK = 8;
    public static final int NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET = 9;
    public static final int NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET_GROUP_SUMMARY = 10;
    public static final int NOTIFICATION_ID_OUTDATED_LEADING_ASSET_DATA = 11;
    public static final int NOTIFICATION_ID_OUTDATED_LEADING_ASSET_DATA_GROUP_SUMMARY = 12;

    // Notification Messages
    public static final String OUTDATED_TICKER_NOTIFICATION_SUBTEXT = "OUTDATED";
    public static final String PASSIVE_NOTIFICATION_SUBTEXT = "PASSIVE";
    public static final String MOMENTUM_NOTIFICATION_SUBTEXT = "MOMENTUM";

    // Ticker Defaults
    public static final int DEFAULT_NUMBER_OF_TOP_HOLDINGS = 4;

    // Filter Defaults
    public static final BigDecimal DEFAULT_MARKET_CAP_THRESHOLD = BigDecimal.valueOf(10_000_000_000L); // $ 10 Billion
    public static final BigDecimal DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD = BigDecimal.valueOf(1_000_000); // $ 1 Million
    public static final BigDecimal DEFAULT_AGE_IN_SECONDS_THRESHOLD = BigDecimal.valueOf(157_680_000); // 5 Years

    // Ticker Update Records
    public static final String FILE_RECORD_FAILED_TICKERS = "failed_tickers.ser";
    public static final int MAX_TICKER_FAILURES_PER_DAY = 3;
    public static final String FILE_RECORD_REFRESH_POINT = "refresh_point.ser";

    // Ticker Update Tiers
    public static final int UPDATE_TIER_LEADING = 1;
    public static final int UPDATE_TIER_NEW = 2;
    public static final int UPDATE_TIER_OTHER = 3;

    // Ticker Update Triggers
    public static final String TICKER_RETRY_WORK_TAG = "ticker_retry_work";
    public static final String TICKER_RETRY_WORK_NAME = "ticker_retry_work";
    public static final String EXTRA_IS_RETRY_WORK = "is_retry_work";
    public static final long TICKER_RETRY_DELAY_HOURS = 1; // 1 Hour

    // User-Initiated Ticker Update
    public static final String TICKER_UIDT_JOB_ID = "TICKER_UIDT_JOB_ID";
    public static final String EXTRA_TICKER_FULL_REFRESH = "full_refresh";
    public static final String EXTRA_TICKER_REQUEST_ID = "request_id";

    // Work Manager
    private static Map<String, Integer> buildDirectJobIds(@SuppressWarnings("SameParameterValue") String... jobs) {
        LinkedHashMap<String, Integer> ids = new LinkedHashMap<>();
        for (String job : jobs) {
            if (ids.put(job, Integer.MAX_VALUE - ids.size()) != null) {
                throw new IllegalStateException("Duplicate direct job: " + job);
            }
        }
        return Collections.unmodifiableMap(ids); // keeps order, blocks outside edits
    }
    private static final Map<String, Integer> DIRECT_JOB_IDS = buildDirectJobIds(
        TICKER_UIDT_JOB_ID
    );
    public static int directJobId(String job) {
        Integer id = DIRECT_JOB_IDS.get(job);
        if (id == null) throw new IllegalArgumentException("Unknown direct job: " + job);
        return id;
    }
    public static final int WORK_MANAGER_JOB_ID_MIN = 0;
    public static final int WORK_MANAGER_JOB_ID_MAX = Integer.MAX_VALUE - DIRECT_JOB_IDS.size();
}