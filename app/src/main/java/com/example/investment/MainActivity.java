package com.example.investment;

import static com.example.investment.service.TickerService.FULL_DATA_REFRESH_ACTION;
import static com.example.investment.service.TickerService.INCREMENTAL_DATA_REFRESH_ACTION;
import static com.example.investment.service.TickerService.STOP_SERVICE_ACTION;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.PREF_FILTER_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.PREF_FILTER_STATUS;
import static com.example.investment.util.Constants.PREF_FILTER_TYPE;

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
import android.widget.EditText;
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

import com.example.investment.model.adapter.TickerAdapter;
import com.example.investment.model.ticker.TickerInfo;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.util.Logger;
import com.example.investment.task.WorkerManager;
import com.example.investment.service.TickerService;
import com.example.investment.util.CustomTabsUtils;
import com.example.investment.util.NumberUtils;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
    private ImageView filterStatusImage;
    private boolean filterStatus = false;
    private long filterMarketCapThreshold = DEFAULT_MARKET_CAP_THRESHOLD;
    private ImageView stopProcessingImage;
    private String dataRefreshType;
    private boolean isManuallyUpdated = false;
    private final CustomTabsUtils customTabsUtils = CustomTabsUtils.getInstance();
    private RecyclerView recyclerView;

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread.setDefaultUncaughtExceptionHandler((thread, e) -> Logger.error("Uncaught Exception", e));
        }

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

        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (isManuallyUpdated) {
                if (!FULL_DATA_REFRESH_ACTION.equals(dataRefreshType)) {
                    Toast.makeText(this, "Full data refresh initiated", Toast.LENGTH_SHORT).show();
                }
                loadCachedData(true, true, true);
            } else {
                isManuallyUpdated = true;
                loadCachedData(true, true);
            }
        });

        dataLoader = TickerDataLoader.getInstance();

        loadCachedData(true, false);

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
                    stopIntent.setAction(STOP_SERVICE_ACTION);
                    startService(stopIntent);
                })
                .create();
            Window dialogWindow = alertDialog.getWindow();
            if (dialogWindow != null) {
                dialogWindow.setBackgroundDrawableResource(R.drawable.dialog);
            }
            alertDialog.show();
        });

        ImageView editMarketCapImage = findViewById(R.id.editMarketCapImage);
        editMarketCapImage.setOnClickListener(v -> showMarketCapThresholdDialog());

        customTabsUtils.warmup(MainActivity.this);

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
    private void loadCachedData(boolean checkUpdate, boolean fromSwipeRefresh) {
        loadCachedData(checkUpdate, fromSwipeRefresh, null);
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void loadCachedData(boolean checkUpdate, boolean fromSwipeRefresh, Boolean fullDataRefresh) {
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
                        if (lastUpdateTime == 0 || cachedTickers == null || cachedTickers.isEmpty()) {
                            loadTickers(fromSwipeRefresh, true);
                        } else {
                            LocalDateTime lastUpdate = LocalDateTime.ofInstant(
                                Instant.ofEpochMilli(lastUpdateTime),
                                ZoneId.systemDefault()
                            );
                            if (lastUpdate.isBefore(LocalDateTime.now().minusDays(1))) {
                                loadTickers(fromSwipeRefresh, Objects.requireNonNullElse(fullDataRefresh, false));
                            }
                        }
                    }
                });
            } catch (Exception e) {
                Logger.error("Failed to retrieve data.", e);
            }
        }).start();
    }

    private void loadTickers(boolean fromSwipeRefresh, boolean fullDataRefresh) {
        // Only restart if it's not a full data refresh
        if (fullDataRefresh && INCREMENTAL_DATA_REFRESH_ACTION.equals(dataRefreshType)) {
            Intent intent = new Intent(this, TickerService.class);
            stopService(intent);
        }

        Intent intent = new Intent(this, TickerService.class);
        intent.setAction(fullDataRefresh ? FULL_DATA_REFRESH_ACTION : INCREMENTAL_DATA_REFRESH_ACTION);

        if (fromSwipeRefresh) {
            swipeRefreshLayout.setRefreshing(true);
            startService(intent);
            swipeRefreshLayout.setRefreshing(false);
        } else {
            startService(intent);
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
                    DateTimeFormatter dayFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault());
                    timeAgo += ", " + updateTime.format(dayFormatter);
                }
            } else {
                DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.getDefault());
                timeAgo = updateTime.format(dateFormatter);
            }

            updateText.setText(getString(R.string.last_updated_format, timeAgo));
        } else {
            updateText.setText(R.string.last_updated_na);
        }
    }

    public void updateProgress(int current, int total, final String dataRefreshType) {
        runOnUiThread(() -> {
            if (this.dataRefreshType == null) {
                this.dataRefreshType = dataRefreshType;
            }
            if (total > 0) {
                progressBar.setMax(total);
                progressBar.setProgress(current);
                progressBar.setIndeterminate(false);

                if (current < total) {
                    stopProcessingImage.setVisibility(View.VISIBLE);
                    updateText.setText(getString(R.string.processing_ticker_progress, dataRefreshType, current, total, (double) current/total * 100));
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
                updateText.setText(getString(R.string.fetching_tickers, dataRefreshType));
                progressBar.setIndeterminate(true);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            }
            progressBar.requestLayout();
            ((View) progressBar.getParent()).requestLayout();
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

            updateLastUpdateTime(updateTime);

            Intent serviceIntent = new Intent(this, TickerService.class);
            stopService(serviceIntent);

            dataRefreshType = null;
            isManuallyUpdated = false;
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void updateError() {
        runOnUiThread(() -> {
            stopProcessingImage.setVisibility(View.GONE);
            progressBar.setVisibility(View.GONE);
            progressBar.getLayoutParams().height = 0;
            progressBar.requestLayout();
            ((View) progressBar.getParent()).requestLayout();

            loadCachedData(false, false);

            Intent serviceIntent = new Intent(this, TickerService.class);
            stopService(serviceIntent);

            dataRefreshType = null;
            isManuallyUpdated = false;
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
        WorkerManager.scheduleTickerWork(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                WorkerManager.scheduleTickerWork(this);
            } else {
                Toast.makeText(this,
                        "Notification permission denied. You won't receive ticker updates.",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    private void setupRecyclerView() {
        adapter.setOnTickerClickListener(ticker -> {
            try {
                String symbol = ticker.getSymbol();
                if (symbol.isEmpty()) throw new Exception("Empty Symbol");
                String url = "https://finance.yahoo.com/quote/" + symbol;
                customTabsUtils.launchUrl(
                    this,
                    Uri.parse(url),
                    ContextCompat.getColor(this, R.color.black),
                    true
                );
            } catch (Exception ignored) {
                Toast.makeText(getApplicationContext(), "Can't open the link", Toast.LENGTH_LONG).show();
            }
        });

        adapter.setOnTickerLongClickListener(ticker -> {
            String name = ticker.getName(true);
            if (name != null && !name.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Ticker", name);
                clipboard.setPrimaryClip(clip);

                Toast.makeText(this, "Copied to clipboard.", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void setupFilters() {
        Spinner typeFilterSpinner = findViewById(R.id.typeFilterSpinner);
        filterStatusImage = findViewById(R.id.statusFilterImage);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        filterStatus = prefs.getBoolean(PREF_FILTER_STATUS, false);
        filterMarketCapThreshold = prefs.getLong(PREF_FILTER_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD);
        int savedFilterPosition = prefs.getInt(PREF_FILTER_TYPE, 0);

        ArrayAdapter<CharSequence> filterAdapter = new ArrayAdapter<>(this,
            R.layout.spinner_item,
            new String[]{"All", "Crypto", "Stock"}
        );
        filterAdapter.setDropDownViewResource(R.layout.spinner_dropdown);
        typeFilterSpinner.setAdapter(filterAdapter);
        typeFilterSpinner.setSelection(savedFilterPosition);

        adapter.setStatusFilter(filterStatus);
        adapter.setTypeFilter(typeFilterSpinner.getSelectedItem().toString());
        adapter.setMarketCapThresholdFilter(filterMarketCapThreshold);

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

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && dataRefreshType == null) {
            new Thread(() -> {
                long lastUpdateTime = dataLoader.getLastUpdateTime(MainActivity.this.getApplicationContext());
                runOnUiThread(() -> updateLastUpdateTime(lastUpdateTime));
            }).start();
        }
    }

    private void showMarketCapThresholdDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_market_cap_threshold, null);
        EditText marketCapThresholdInput = dialogView.findViewById(R.id.marketCapThresholdInput);
        marketCapThresholdInput.setText(NumberUtils.toFullNumericString(filterMarketCapThreshold));

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Market Cap Threshold")
            .setView(dialogView)
            .setPositiveButton("Save", (dialogInterface, i) -> {
                try {
                    String input = marketCapThresholdInput.getText().toString().trim();
                    long newMarketCapThreshold;
                    if (input.isEmpty()) {
                        newMarketCapThreshold = DEFAULT_MARKET_CAP_THRESHOLD;
                    } else {
                        newMarketCapThreshold = Long.parseLong(marketCapThresholdInput.getText().toString());
                    }
                    if (newMarketCapThreshold > 0) {
                        filterMarketCapThreshold = newMarketCapThreshold;
                        adapter.setMarketCapThresholdFilter(filterMarketCapThreshold);
                        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                        editor.putLong(PREF_FILTER_MARKET_CAP_THRESHOLD, filterMarketCapThreshold);
                        editor.apply();
                        Toast.makeText(this, "Market Cap Threshold Updated", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Market Cap Threshold Should Be Greater Than 0", Toast.LENGTH_SHORT).show();
                    }
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Invalid Input", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .create();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawableResource(R.drawable.dialog);
        }
        dialog.show();
    }
}