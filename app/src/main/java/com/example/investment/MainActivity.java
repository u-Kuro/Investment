package com.example.investment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.browser.customtabs.CustomTabsIntent;
import android.net.Uri;

import com.example.investment.adapter.TickerAdapter;
import com.example.investment.model.TickerInfo;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.util.Logger;
import com.example.investment.util.WorkManager;
import com.example.investment.service.TickerService;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    public static WeakReference<MainActivity> weakActivity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TickerAdapter adapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private TickerDataLoader dataLoader;
    private TextView lastUpdateText;
    private TextView progressText;
    private ProgressBar progressBar;
    private View progressContainer;
    private static final int PERMISSION_REQUEST_CODE = 123;

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread.setDefaultUncaughtExceptionHandler((thread, e) -> Logger.error("Uncaught Exception", e));
        }

        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        lastUpdateText = findViewById(R.id.lastUpdateText);
        progressText = findViewById(R.id.progressText);
        progressBar = findViewById(R.id.progressBar);
        progressContainer = findViewById(R.id.progressContainer);
        
        adapter = new TickerAdapter();
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        swipeRefreshLayout.setOnRefreshListener(() -> this.loadTickers(true));

        dataLoader = TickerDataLoader.getInstance();
        
        loadCachedData();

        weakActivity = new WeakReference<>(MainActivity.this);

        requestNotificationPermission();

        setupRecyclerView();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void loadCachedData() {
        new Thread(() -> {
            try {
                long lastUpdateTime = dataLoader.getLastUpdateTime(MainActivity.this.getApplicationContext());
                Logger.info("KANSHII "+lastUpdateTime);
                mainHandler.post(() -> updateLastUpdateTime(lastUpdateTime));
                List<TickerInfo> cachedTickers = dataLoader.loadCachedTickers(MainActivity.this.getApplicationContext());
                mainHandler.post(() -> {
                    if (cachedTickers != null && !cachedTickers.isEmpty()) {
                        updateTickerList(cachedTickers);
                    }
                    LocalDateTime lastUpdate = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(lastUpdateTime), 
                        ZoneId.systemDefault());
                    if (lastUpdateTime == 0 || lastUpdate.isBefore(LocalDateTime.now().minusDays(1))) {
                        loadTickers(false);
                    }
                });
            } catch (Exception e) {
                Logger.error("Failed to retrieve tickers.", e);
            }
        }).start();
    }

    private void loadTickers(boolean showRefresh) {
        if (showRefresh) {
            swipeRefreshLayout.setRefreshing(true);
        }
        progressContainer.setVisibility(View.VISIBLE);

        Intent serviceIntent = new Intent(this, TickerService.class);
        startService(serviceIntent);

        swipeRefreshLayout.setRefreshing(false);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void updateTickerList(List<TickerInfo> tickers) {
        adapter.setTickers(tickers);
        updateLastUpdateTime(dataLoader.getLastUpdateTime(MainActivity.this.getApplicationContext()));
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void updateLastUpdateTime(long timestamp) {
        if (timestamp > 0) {
            LocalDateTime updateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp), 
                ZoneId.systemDefault()
            );
            LocalDateTime now = LocalDateTime.now();
            String timeAgo;

            if (updateTime.isAfter(now.minusWeeks(1))) {
                long daysDifference = ChronoUnit.DAYS.between(updateTime, now);
                if (daysDifference == 0) {
                    long minutesDifference = ChronoUnit.MINUTES.between(updateTime, now);
                    if (minutesDifference < 1) {
                        timeAgo = "just now";
                    } else if (minutesDifference == 1) {
                        timeAgo = "1 minute ago";
                    } else if (minutesDifference < 60) {
                        timeAgo = minutesDifference + " minutes ago";
                    } else {
                        long hours = minutesDifference / 60;
                        timeAgo = hours + (hours == 1 ? " hour ago" : " hours ago");
                    }
                } else {
                    if (daysDifference == 1) {
                        timeAgo = "Yesterday";
                    } else {
                        timeAgo = daysDifference + " days ago";
                    }
                    // Add day of week
                    DateTimeFormatter dayFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault());
                    timeAgo += ", " + updateTime.format(dayFormatter);
                }
            } else {
                DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm", Locale.getDefault());
                timeAgo = updateTime.format(dateFormatter);
            }
            
            lastUpdateText.setText(getString(R.string.last_updated_format, timeAgo));
        } else {
            lastUpdateText.setText(R.string.last_updated_never);
        }
    }

    public void updateProgress(int current, int total) {
        mainHandler.post(() -> {
            if (total > 0) {
                double progress = ((double) current / total) * 100;
                if (current < total) {
                    progressText.setText(getString(R.string.processing_ticker_progress, current, total, progress));
                } else {
                    progressText.setText(R.string.successful_ticker_progress);
                }
                progressBar.setMax(total);
                progressBar.setProgress(current);
                progressBar.setIndeterminate(false);
            } else {
                progressText.setText(getString(R.string.fetching_tickers));
                progressBar.setIndeterminate(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    public static MainActivity getInstanceActivity() {
        if (weakActivity!=null) {
            return weakActivity.get();
        } else {
            return null;
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    PERMISSION_REQUEST_CODE
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, start the worker
                WorkManager.scheduleTickerWork(this);
            } else {
                // Permission denied, show a message to the user
                Toast.makeText(this, 
                    "Notification permission denied. You won't receive ticker updates.", 
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    private void setupRecyclerView() {
        // Regular click - open Yahoo Finance
        adapter.setOnTickerClickListener(ticker -> {
            try {
                String url = "https://finance.yahoo.com/quote/" + ticker.getSymbol();
                CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
                CustomTabsIntent customTabsIntent = builder.build();
                customTabsIntent.launchUrl(this, Uri.parse(url));
            } catch (Exception ignored) {
                Toast.makeText(getApplicationContext(), "Can't open the link", Toast.LENGTH_LONG).show();
            }
        });

        // Long click - copy symbol
        adapter.setOnTickerLongClickListener(ticker -> {
            String textToCopy = ticker.getName();
            if (textToCopy == null || textToCopy.isEmpty()) {
                textToCopy = ticker.getSymbol();
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Ticker", textToCopy);
            clipboard.setPrimaryClip(clip);

            Toast.makeText(this, "Copied to clipboard.", Toast.LENGTH_SHORT).show();
            return true;
        });
    }
}