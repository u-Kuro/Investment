package com.example.investment;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.net.Uri;

import com.example.investment.adapter.TickerAdapter;
import com.example.investment.model.TickerInfo;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.util.Logger;
import com.example.investment.util.WorkManager;
import com.example.investment.service.TickerService;
import com.example.investment.util.CustomTabsHelper;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import android.content.SharedPreferences;
import androidx.activity.OnBackPressedCallback;

public class MainActivity extends AppCompatActivity {
    public static WeakReference<MainActivity> weakActivity;
    private TickerAdapter adapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private TickerDataLoader dataLoader;
    private TextView updateText;
    private ProgressBar progressBar;
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final String PREFS_NAME = "TickerFilterPrefs";
    private static final String PREF_FILTER_TYPE = "filterType";
    private static final String PREF_FILTER_STATUS = "filterStatus";

    private ImageView filterStatusImage;
    private boolean filterStatus = false;
    private ImageView stopProcessingImage;

    private final CustomTabsHelper customTabsHelper = CustomTabsHelper.getInstance();

    private RecyclerView recyclerView;

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread.setDefaultUncaughtExceptionHandler((thread, e) -> Logger.error("Uncaught Exception", e));
        }

        // Create WebView App Instance
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        splashScreen.setKeepOnScreenCondition(() -> false);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EdgeToEdge.enable(this);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        recyclerView = findViewById(R.id.recyclerView);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        updateText = findViewById(R.id.updateText);
        progressBar = findViewById(R.id.progressBar);
        
        adapter = new TickerAdapter();
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        swipeRefreshLayout.setOnRefreshListener(() -> this.loadTickers(true));

        dataLoader = TickerDataLoader.getInstance();

        loadCachedData(true);

        weakActivity = new WeakReference<>(MainActivity.this);

        requestNotificationPermission();

        setupRecyclerView();

        setupFilters();

        stopProcessingImage = findViewById(R.id.stopProcessingImage);
        stopProcessingImage.setVisibility(View.GONE);
        stopProcessingImage.setOnClickListener(v -> {
            AlertDialog alertDialog = new AlertDialog.Builder(this)
                .setTitle("Confirmation")
                .setMessage("Do you want to cancel the update?")
                .setPositiveButton("YES", (dialog, which) -> {
                    Intent stopIntent = new Intent(this, TickerService.class);
                    stopIntent.setAction(TickerService.STOP_SERVICE_ACTION);
                    startService(stopIntent);
                })
                .create();
            Window dialogWindow = alertDialog.getWindow();
            if (dialogWindow != null) {
                dialogWindow.setBackgroundDrawableResource(R.drawable.dialog);
            }
            alertDialog.show();
        });

        customTabsHelper.warmup(MainActivity.this);

        // Add back press handling
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (recyclerView.canScrollVertically(-1)) {
                    recyclerView.scrollToPosition(0);
                } else {
                    moveTaskToBack(true);
                }
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void loadCachedData(boolean checkUpdate) {
        new Thread(() -> {
            try {
                long lastUpdateTime = dataLoader.getLastUpdateTime(MainActivity.this.getApplicationContext());
                runOnUiThread(() -> updateLastUpdateTime(lastUpdateTime));
                List<TickerInfo> cachedTickers = dataLoader.loadCachedTickers(MainActivity.this.getApplicationContext());
                runOnUiThread(() -> {
                    if (cachedTickers != null && !cachedTickers.isEmpty()) {
                        updateTickerList(cachedTickers);
                    }
                    if (checkUpdate) {
                        LocalDateTime lastUpdate = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(lastUpdateTime),
                            ZoneId.systemDefault());
                        if (lastUpdateTime == 0 || lastUpdate.isBefore(LocalDateTime.now().minusDays(1))) {
                            loadTickers(false);
                        }
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
            Intent serviceIntent = new Intent(this, TickerService.class);
            startService(serviceIntent);
            swipeRefreshLayout.setRefreshing(false);
        } else {
            Intent serviceIntent = new Intent(this, TickerService.class);
            startService(serviceIntent);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void updateTickerList(List<TickerInfo> tickers) {
        adapter.setTickers(tickers);
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
            
            updateText.setText(getString(R.string.last_updated_format, timeAgo));
        } else {
            updateText.setText(R.string.last_updated_never);
        }
    }

    public void updateProgress(int current, int total) {
        runOnUiThread(() -> {
            if (total > 0) {
                progressBar.setMax(total);
                progressBar.setProgress(current);
                progressBar.setIndeterminate(false);
                
                if (current < total) {
                    stopProcessingImage.setVisibility(View.VISIBLE);
                    updateText.setText(getString(R.string.processing_ticker_progress, current, total, (double)current/total * 100));
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                } else {
                    stopProcessingImage.setVisibility(View.GONE);
                    updateText.setText(R.string.successful_ticker_progress);
                    progressBar.setVisibility(View.GONE);
                    progressBar.getLayoutParams().height = 0;
                }
            } else {
                stopProcessingImage.setVisibility(View.VISIBLE);
                updateText.setText(getString(R.string.fetching_tickers));
                progressBar.setIndeterminate(true);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            }
            progressBar.requestLayout();
            ((View)progressBar.getParent()).requestLayout();
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void updateFinished(long updateTime) {
        runOnUiThread(() -> {
            stopProcessingImage.setVisibility(View.GONE);
            progressBar.setVisibility(View.GONE);
            progressBar.getLayoutParams().height = 0;
            progressBar.requestLayout();
            ((View) progressBar.getParent()).requestLayout();

            if (updateTime == 0) {
                loadCachedData(false);
            } else {
                updateLastUpdateTime(updateTime);
            }
        });
    }

    public static MainActivity getInstanceActivity() {
        if (weakActivity!=null) {
            return weakActivity.get();
        } else {
            return null;
        }
    }

    private void requestNotificationPermission() {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERMISSION_REQUEST_CODE
                );
                return;
            }
        }
        WorkManager.scheduleTickerWork(this);
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
                String symbol = ticker.getSymbol();
                if (symbol == null || symbol.isEmpty()) throw new Exception("Empty Symbol");
                String url = "https://finance.yahoo.com/quote/" + symbol;
                customTabsHelper.launchUrl(
                    this,
                    Uri.parse(url),
                    ContextCompat.getColor(this, R.color.black),
                    true
                );
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

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void setupFilters() {
        Spinner typeFilterSpinner = findViewById(R.id.typeFilterSpinner);
        filterStatusImage = findViewById(R.id.statusFilterImage);

        // Load saved preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        filterStatus = prefs.getBoolean(PREF_FILTER_STATUS, false);
        int savedFilterPosition = prefs.getInt(PREF_FILTER_TYPE, 0);

        // Setup spinner
        ArrayAdapter<CharSequence> filterAdapter = new ArrayAdapter<>(this,
            R.layout.spinner_item,
            new String[]{"All", "Crypto", "Stock"}
        );
        filterAdapter.setDropDownViewResource(R.layout.spinner_dropdown);
        typeFilterSpinner.setAdapter(filterAdapter);
        typeFilterSpinner.setSelection(savedFilterPosition);

        // Set initial filter states
        adapter.setStatusFilter(filterStatus);
        adapter.setTypeFilter(typeFilterSpinner.getSelectedItem().toString());

        // Update UI to reflect saved state
        filterStatusImage.setImageResource(filterStatus ? R.drawable.check : R.drawable.check_crossed);

        typeFilterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                editor.putInt(PREF_FILTER_TYPE, position);
                editor.apply();
                
                adapter.setTypeFilter(parent.getSelectedItem().toString());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        filterStatusImage.setOnClickListener(v -> {
            filterStatus = !filterStatus;
            filterStatusImage.setImageResource(filterStatus ? R.drawable.check : R.drawable.check_crossed);
                
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
            editor.putBoolean(PREF_FILTER_STATUS, filterStatus);
            editor.apply();
            
            adapter.setStatusFilter(filterStatus);
        });
    }
}