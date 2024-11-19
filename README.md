# Investment Tracker

An Android application that tracks and analyzes stock and cryptocurrency investments using data from Yahoo Finance.

## Features

- Real-time tracking of stocks and cryptocurrencies
- Price boundary calculations for investment decisions
- Daily percentage increase tracking
- Filtering by asset type (Crypto/Stock) and status
- Pull-to-refresh updates
- Background data synchronization
- Dark theme UI
- Clipboard support for quick symbol/name copying
- Direct links to Yahoo Finance details
- Notification system for significant price changes

## Technical Details

### Requirements

- Android SDK 24 or higher (Android 7.0+)
- Java 11
- Gradle 8.0+

### Dependencies

- AndroidX Core & AppCompat
- Material Design Components
- OkHttp for network requests
- JSoup for HTML parsing
- WorkManager for background tasks
- Browser for Custom Tabs
- SwipeRefreshLayout for pull-to-refresh
- RecyclerView for efficient list handling

### Architecture

The app follows a clean architecture approach with:

- **UI Layer**: Activities and Adapters
- **Business Logic**: Services and Workers
- **Data Layer**: Repositories and Cache
- **Background Processing**: WorkManager and Services

### Key Components

- `MainActivity`: Main UI and user interaction handling
- `TickerService`: Background data fetching and processing
- `YahooFinanceRepository`: Data source interaction
- `TickerAdapter`: RecyclerView display management
- `TickerWorker`: Periodic background updates

## Building

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle dependencies
4. Build and run