package com.example.investment;

import static com.example.investment.service.TickerService.FULL_DATA_REFRESH_ACTION;
import static com.example.investment.service.TickerService.INCREMENTAL_DATA_REFRESH_ACTION;
import static com.example.investment.service.TickerService.STOP_SERVICE_ACTION;
import static com.example.investment.util.Constants.ALL_TYPE;
import static com.example.investment.util.Constants.CRYPTOCURRENCY_TYPE;
import static com.example.investment.util.Constants.DEFAULT_AGE_IN_SECONDS_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_NUMBER_OF_TOP_HOLDINGS;
import static com.example.investment.util.Constants.DEFERRED_THREAD;
import static com.example.investment.util.Constants.MOMENTUM_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PASSIVE_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.PREF_AGE_IN_SECONDS_THRESHOLD;
import static com.example.investment.util.Constants.PREF_ASSET_TYPE;
import static com.example.investment.util.Constants.PREF_DAILY_DOLLAR_VOLUME_THRESHOLD;
import static com.example.investment.util.Constants.PREF_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.PREF_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PREF_LIST_VIEW_ASSET_TYPE;
import static com.example.investment.util.BigDecimalUtils.getSafeBigDecimalPrefs;
import static com.example.investment.util.Constants.PREF_NUMBER_OF_TOP_HOLDINGS;
import static com.example.investment.util.Constants.STOCK_TYPE;
import static com.example.investment.util.Constants.ASSERT_MAIN_THREAD;
import static com.example.investment.util.Constants.EVENT_THREAD;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.net.Uri;

import com.example.investment.model.adapter.TickerAdapter;
import com.example.investment.model.ticker.TickerData;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.util.Logger;
import com.example.investment.task.WorkerManager;
import com.example.investment.service.TickerService;
import com.example.investment.util.CustomTabsUtils;
import com.example.investment.util.SafeSingleTaskRunner;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.BigInteger;
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
    private static final int PERMISSION_REQUEST_CODE = 123;
    private @NonNull final CustomTabsUtils customTabsUtils = CustomTabsUtils.getInstance();

    private SwipeRefreshLayout swipeRefreshLayout;
    private ImageView stopProcessingImage;
    private ImageView searchIcon;
    private LinearLayout searchBarContainer;
    private EditText searchInput;
    private TextView updateText;
    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private TickerAdapter adapter;

    private @NonNull String investmentType = PASSIVE_INVESTMENT_TYPE;
    private @NonNull String assetType = ALL_TYPE;
    private int numberOfTopHoldings = DEFAULT_NUMBER_OF_TOP_HOLDINGS;
    private @NonNull BigDecimal marketCapThreshold = DEFAULT_MARKET_CAP_THRESHOLD;
    private @NonNull BigDecimal dailyDollarVolumeThreshold = DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD;
    private @NonNull BigDecimal ageInSecondsThreshold = DEFAULT_AGE_IN_SECONDS_THRESHOLD;

    private TickerDataLoader tickerDataLoader;
    private final Object AUTO_LOAD_TASK_ID = new Object();
    private final Object MANUAL_UPDATE_TASK_ID = new Object();

    private boolean isManuallyUpdated = false;
    private String dataRefreshType;

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

        searchIcon = findViewById(R.id.searchIcon);
        searchInput = findViewById(R.id.searchInput);
        searchBarContainer = findViewById(R.id.searchBarContainer);

        adapter = setupFiltersAndGetAdapter(searchIcon, searchInput, searchBarContainer);
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (isManuallyUpdated || dataRefreshType != null) {
                if (FULL_DATA_REFRESH_ACTION.equals(dataRefreshType)) {
                    Toast.makeText(this, "Update already started...", Toast.LENGTH_LONG).show();
                    swipeRefreshLayout.setRefreshing(false);
                } else {
                    Toast.makeText(this, "Full data update starting...", Toast.LENGTH_LONG).show();
                }
                manualUpdateTickerDataRecord(true);
            } else {
                isManuallyUpdated = true;
                Toast.makeText(this, "Update starting...", Toast.LENGTH_LONG).show();
                swipeRefreshLayout.setRefreshing(false);
                manualUpdateTickerDataRecord(false);
            }
        });

        tickerDataLoader = TickerDataLoader.getInstance();

        weakActivity = new WeakReference<>(MainActivity.this);

        stopProcessingImage = findViewById(R.id.stopProcessingImage);
        stopProcessingImage.setVisibility(View.GONE);
        stopProcessingImage.setOnClickListener(v -> {
            AlertDialog alertDialog = new AlertDialog.Builder(this)
                .setTitle("Confirmation")
                .setMessage("Do you want to cancel the ongoing update?")
                .setPositiveButton("YES", (dialog, which) -> {
                    EVENT_THREAD.cancelCurrentUniqueTask(MANUAL_UPDATE_TASK_ID);
                    DEFERRED_THREAD.cancelCurrentUniqueTask(MANUAL_UPDATE_TASK_ID);

                    Intent stopIntent = new Intent(this, TickerService.class);
                    stopIntent.setAction(STOP_SERVICE_ACTION);
                    startService(stopIntent);

                    stopProcessingImage.setVisibility(View.GONE);
                    progressBar.setVisibility(View.GONE);
                    progressBar.getLayoutParams().height = 0;
                    progressBar.requestLayout();
                    ((View) progressBar.getParent()).requestLayout();

                    dataRefreshType = null;
                    isManuallyUpdated = false;
                })
                .create();
            Window dialogWindow = alertDialog.getWindow();
            if (dialogWindow != null) {
                dialogWindow.setBackgroundDrawableResource(R.drawable.dialog);
            }
            alertDialog.show();
        });

        ImageView investmentConfigurationsImage = findViewById(R.id.investmentConfigurationsImage);
        investmentConfigurationsImage.setOnClickListener(v -> showConfigurationsDialog());

        customTabsUtils.warmup(MainActivity.this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (searchInput.hasFocus()) {
                    hideKeyboardForEditText(searchInput);
                } else if (searchBarContainer.getVisibility() == View.VISIBLE) {
                    searchBarContainer.setVisibility(View.GONE);
                    searchIcon.setImageResource(R.drawable.search);
                    hideKeyboardForEditText(searchInput);
                    searchInput.setText("");
                } else if (recyclerView.canScrollVertically(-1)) {
                    recyclerView.scrollToPosition(0);
                } else {
                    moveTaskToBack(true);
                }
            }
        });

        requestNotificationPermission();

        autoLoadTickerDataRecord(true);
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void autoLoadTickerDataRecord(final boolean isInitialLoad) {
        autoLoadTickerDataRecord(isInitialLoad, false);
    }
    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void autoLoadTickerDataRecord(final boolean isInitialLoad, final boolean isCancelled) {
        final TickerDataLoader finalDataLoader = this.tickerDataLoader;
        final TickerAdapter tickerAdapter = this.adapter;

        // Initial load shouldn't be canceled
        if (isInitialLoad) {
            swipeRefreshLayout.setRefreshing(true);
            Toast.makeText(this, "Please wait...", Toast.LENGTH_LONG).show();

            EVENT_THREAD.execute(ui -> {
                try {
                    final long lastUpdateTime = finalDataLoader.getLastUpdateTime(MainActivity.this);
                    final List<TickerData> tickers = finalDataLoader.getTickers(MainActivity.this);
                    ui.post(() -> {
                        updateLastUpdateTime(lastUpdateTime);
                        if (tickers != null && !tickers.isEmpty()) {
                            tickerAdapter.setInitialTickers(tickers, () -> {
                                if (lastUpdateTime == 0 || tickers.isEmpty()) {
                                    updateTickers(false, true);
                                } else {
                                    LocalDateTime lastUpdate = LocalDateTime.ofInstant(
                                        Instant.ofEpochMilli(lastUpdateTime),
                                        ZoneId.systemDefault()
                                    );
                                    if (lastUpdate.isBefore(LocalDateTime.now().minusDays(1))) {
                                        updateTickers(false, false);
                                    }
                                }
                                swipeRefreshLayout.setRefreshing(false);
                            });
                        } else {
                            updateTickers(false, true);
                            swipeRefreshLayout.setRefreshing(false);
                        }
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        Toast.makeText(this, "Failed to load recent data...", Toast.LENGTH_LONG).show();
                        swipeRefreshLayout.setRefreshing(false);
                    });
                    Logger.error("Failed to load data.", e);
                }
            });
        } else {
            final SafeSingleTaskRunner THREAD_RUNNER = isCancelled ? EVENT_THREAD : DEFERRED_THREAD;
            THREAD_RUNNER.replaceUniqueTask(AUTO_LOAD_TASK_ID, ui -> {
                try {
                    final long lastUpdateTime = finalDataLoader.getLastUpdateTime(MainActivity.this);
                    final List<TickerData> tickers = finalDataLoader.getTickers(MainActivity.this);
                    ui.post(() -> {
                        updateLastUpdateTime(lastUpdateTime);
                        if (tickers != null && !tickers.isEmpty()) {
                            updateTickerList(tickers);
                        }
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        Toast.makeText(this, "Failed to load recent data...", Toast.LENGTH_LONG).show();
                        swipeRefreshLayout.setRefreshing(false);
                    });
                    Logger.error("Failed to load data.", e);
                }
            });
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void manualUpdateTickerDataRecord(final boolean isFullDataRefresh) {
        final TickerDataLoader dataLoader = this.tickerDataLoader;
        EVENT_THREAD.replaceUniqueTask(MANUAL_UPDATE_TASK_ID, ui -> {
            try {
                final long lastUpdateTime = dataLoader.getLastUpdateTime(MainActivity.this);
                final List<TickerData> tickers = dataLoader.getTickers(MainActivity.this);
                ui.post(() -> {
                    if (lastUpdateTime == 0 || tickers == null || tickers.isEmpty()) {
                        updateTickers(true, true);
                    } else {
                        updateTickers(true, isFullDataRefresh);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    Toast.makeText(this, "Failed to update data...", Toast.LENGTH_LONG).show();
                    swipeRefreshLayout.setRefreshing(false);
                });
                Logger.error("Failed to update data.", e);
            }
        });
    }

    private void updateTickers(boolean isManual, boolean isFullDataRefresh) {
        Intent intent = new Intent(this, TickerService.class);
        intent.setAction(isFullDataRefresh ? FULL_DATA_REFRESH_ACTION : INCREMENTAL_DATA_REFRESH_ACTION);

        if (isManual) {
            if (isFullDataRefresh) {
                swipeRefreshLayout.setRefreshing(false);
            }
            startService(intent);
        } else {
            startService(intent);
        }
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

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void updateTickerList(@NonNull List<TickerData> tickers) {
        ASSERT_MAIN_THREAD();
        adapter.setTickers(tickers);
    }

    public void updateTickersProgress(int current, int total, final String dataRefreshType) {
        ASSERT_MAIN_THREAD();
        this.dataRefreshType = dataRefreshType;
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
    }
    public void updateSymbolsProgress(int current, final String dataRefreshType) {
        ASSERT_MAIN_THREAD();
        this.dataRefreshType = dataRefreshType;
        if (current > 0) {
            updateText.setText(getString(R.string.fetching_tickers_found, dataRefreshType, current));
        } else {
            updateText.setText(getString(R.string.fetching_tickers, dataRefreshType));
        }
        stopProcessingImage.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        progressBar.requestLayout();
        ((View) progressBar.getParent()).requestLayout();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void updateFinished(long updateTime) {
        ASSERT_MAIN_THREAD();
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
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void updateError() {
        ASSERT_MAIN_THREAD();
        stopProcessingImage.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        progressBar.getLayoutParams().height = 0;
        progressBar.requestLayout();
        ((View) progressBar.getParent()).requestLayout();

        autoLoadTickerDataRecord(false, false);

        Intent serviceIntent = new Intent(this, TickerService.class);
        stopService(serviceIntent);

        dataRefreshType = null;
        isManuallyUpdated = false;
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void updateCancel() {
        ASSERT_MAIN_THREAD();
        stopProcessingImage.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        progressBar.getLayoutParams().height = 0;
        progressBar.requestLayout();
        ((View) progressBar.getParent()).requestLayout();

        autoLoadTickerDataRecord(false, true);

        Intent serviceIntent = new Intent(this, TickerService.class);
        stopService(serviceIntent);

        dataRefreshType = null;
        isManuallyUpdated = false;
    }

    public static MainActivity getInstanceActivity() {
        ASSERT_MAIN_THREAD();

        if (weakActivity == null) {
            return null;
        }

        MainActivity activity = weakActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return null;
        }

        if (activity.isActivityNotCreated()) {
            return null;
        }

        return activity;
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

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private @NonNull TickerAdapter setupFiltersAndGetAdapter(
        @NonNull ImageView searchIcon,
        @NonNull EditText searchInput,
        @NonNull LinearLayout searchBarContainer
    ) {
        @NonNull final SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        @NonNull final String listViewAssetType = sharedPreferences.getString(PREF_LIST_VIEW_ASSET_TYPE, ALL_TYPE);
        investmentType = sharedPreferences.getString(PREF_INVESTMENT_TYPE, PASSIVE_INVESTMENT_TYPE);
        assetType = sharedPreferences.getString(PREF_ASSET_TYPE, ALL_TYPE);
        numberOfTopHoldings = sharedPreferences.getInt(PREF_NUMBER_OF_TOP_HOLDINGS, DEFAULT_NUMBER_OF_TOP_HOLDINGS);
        marketCapThreshold = getSafeBigDecimalPrefs(sharedPreferences, PREF_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD);
        dailyDollarVolumeThreshold = getSafeBigDecimalPrefs(sharedPreferences, PREF_DAILY_DOLLAR_VOLUME_THRESHOLD, DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD);
        ageInSecondsThreshold = getSafeBigDecimalPrefs(sharedPreferences, PREF_AGE_IN_SECONDS_THRESHOLD, DEFAULT_AGE_IN_SECONDS_THRESHOLD);

        @NonNull final TickerAdapter tickerAdapter = new TickerAdapter(
            listViewAssetType,
            investmentType,
            marketCapThreshold,
            dailyDollarVolumeThreshold,
            ageInSecondsThreshold,
            ticker -> {
                try {
                    @NonNull String symbol = ticker.getSymbol();
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
            },
            ticker -> {
                String name = ticker.getName(true);
                if (name != null && !name.isEmpty()) {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Ticker", name);
                    clipboard.setPrimaryClip(clip);

                    Toast.makeText(this, "Copied to clipboard.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        );

        @NonNull ArrayAdapter<CharSequence> listViewAssetTypeAdapter = new ArrayAdapter<>(this,
            R.layout.spinner_item,
            new String[]{ALL_TYPE, CRYPTOCURRENCY_TYPE, STOCK_TYPE}
        );
        listViewAssetTypeAdapter.setDropDownViewResource(R.layout.spinner_dropdown);

        @NonNull Spinner listTypeAssetTypeSpinner = findViewById(R.id.listViewAssetTypeSpinner);
        listTypeAssetTypeSpinner.setAdapter(listViewAssetTypeAdapter);
        listTypeAssetTypeSpinner.setSelection(listViewAssetTypeAdapter.getPosition(listViewAssetType));
        listTypeAssetTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                @NonNull String newListViewAssetType = parent.getSelectedItem().toString();

                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                editor.putString(PREF_LIST_VIEW_ASSET_TYPE, newListViewAssetType);
                editor.apply();

                tickerAdapter.setListViewAssetType(newListViewAssetType);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                tickerAdapter.setSearchQuery(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) { }
        });
        searchIcon.setOnClickListener(v -> {
            if (searchBarContainer.getVisibility() == View.GONE) {
                searchBarContainer.setVisibility(View.VISIBLE);
                searchIcon.setImageResource(R.drawable.search_off);
                showKeyboardForEditText(searchInput);
            } else {
                searchBarContainer.setVisibility(View.GONE);
                searchIcon.setImageResource(R.drawable.search);
                hideKeyboardForEditText(searchInput);
                searchInput.setText("");
            }
        });

        return tickerAdapter;
    }

    private final Object ON_WINDOW_FOCUS_CHANGE_TASK_ID = new Object();
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (isActivityNotCreated()) return;
        if (hasFocus && dataRefreshType == null) {
            DEFERRED_THREAD.replaceUniqueTask(ON_WINDOW_FOCUS_CHANGE_TASK_ID, ui -> {
                long lastUpdateTime = tickerDataLoader.getLastUpdateTime(MainActivity.this.getApplicationContext());
                ui.post(() -> updateLastUpdateTime(lastUpdateTime));
            });
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            // Hides keyboard
            View v = getCurrentFocus();
            if (v instanceof EditText editText) {
                int x = (int) ev.getRawX();
                int y = (int) ev.getRawY();
                // Not touched view that opens keyboard
                View touchedView = findViewAtLocation(getWindow().getDecorView(), x, y);
                if (touchedView != searchIcon) {
                    // Touched outside the focused EditText
                    Rect rect = new Rect();
                    editText.getGlobalVisibleRect(rect);
                    if (!rect.contains(x, y)) {
                        hideKeyboardForEditText(editText);
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }
    private void showKeyboardForEditText(EditText editText) {
        if (editText == null) return;
        editText.post(() -> {
            editText.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }
    private void hideKeyboardForEditText(EditText editText) {
        if (editText == null) return;
        editText.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
        }
    }
    private View findViewAtLocation(View parent, int x, int y) {
        if (parent instanceof ViewGroup group) {
            // Loop backwards to check views on top (highest Z-index) first
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                int[] location = new int[2];
                child.getLocationOnScreen(location);

                Rect rect = new Rect(
                    location[0],
                    location[1],
                    location[0] + child.getWidth(),
                    location[1] + child.getHeight()
                );

                if (rect.contains(x, y) && child.getVisibility() == View.VISIBLE) {
                    return findViewAtLocation(child, x, y);
                }
            }
        }
        return parent;
    }

    private AlertDialog configurationsDialog;
    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void showConfigurationsDialog() {
        if (configurationsDialog != null && configurationsDialog.isShowing()) return;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_configurations, null);

        MaterialAutoCompleteTextView investmentTypeSpinner = dialogView.findViewById(R.id.investmentTypeSpinner);
        ArrayAdapter<CharSequence> investmentTypeAdapter = new ArrayAdapter<>(this,
                R.layout.auto_complete_text_view_spinner_item,
                new String[]{PASSIVE_INVESTMENT_TYPE, MOMENTUM_INVESTMENT_TYPE}
        );
        investmentTypeAdapter.setDropDownViewResource(R.layout.spinner_dropdown);
        investmentTypeSpinner.setAdapter(investmentTypeAdapter);

        MaterialAutoCompleteTextView assetTypeSpinner = dialogView.findViewById(R.id.assetTypeSpinner);
        ArrayAdapter<CharSequence> assetTypeAdapter = new ArrayAdapter<>(this,
                R.layout.auto_complete_text_view_spinner_item,
                new String[]{ALL_TYPE, CRYPTOCURRENCY_TYPE, STOCK_TYPE}
        );
        assetTypeAdapter.setDropDownViewResource(R.layout.spinner_dropdown);
        assetTypeSpinner.setAdapter(assetTypeAdapter);

        EditText numberOfTopHoldingsInput = dialogView.findViewById(R.id.numberOfTopHoldingsInput);
        EditText marketCapThresholdInput = dialogView.findViewById(R.id.marketCapThresholdInput);
        EditText dailyDollarVolumeThresholdInput = dialogView.findViewById(R.id.dailyDollarVolumeThresholdInput);
        EditText ageInSecondsThresholdInput = dialogView.findViewById(R.id.ageInSecondsThresholdInput);

        investmentTypeSpinner.setText(investmentType, false);
        assetTypeSpinner.setText(assetType, false);
        numberOfTopHoldingsInput.setText(String.format(Locale.ROOT, "%d", numberOfTopHoldings));
        marketCapThresholdInput.setText(marketCapThreshold.toPlainString());
        dailyDollarVolumeThresholdInput.setText(dailyDollarVolumeThreshold.toPlainString());
        ageInSecondsThresholdInput.setText(ageInSecondsThreshold.toPlainString());

        configurationsDialog = new AlertDialog.Builder(this)
            .setTitle(getResources().getText(R.string.investment_configurations))
            .setView(dialogView)
            .setPositiveButton("Save", (dialogInterface, i) -> {
                try {
                    SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();

                    @NonNull String newInvestmentType = investmentTypeSpinner.getText().toString();
                    investmentType = newInvestmentType;
                    adapter.setInvestmentType(investmentType);
                    editor.putString(PREF_INVESTMENT_TYPE, investmentType);

                    @NonNull String newAssetType = assetTypeSpinner.getText().toString();
                    assetType = newAssetType;
                    editor.putString(PREF_ASSET_TYPE, assetType);

                    @NonNull String newNumberOfTopHoldingsString = numberOfTopHoldingsInput.getText().toString().trim();
                    @NonNull BigInteger newNumberOfTopHoldings;
                    if (newNumberOfTopHoldingsString.isEmpty()) {
                        newNumberOfTopHoldings = BigInteger.valueOf(DEFAULT_NUMBER_OF_TOP_HOLDINGS);
                    } else {
                        newNumberOfTopHoldings = new BigInteger(newNumberOfTopHoldingsString);
                    }
                    if (newNumberOfTopHoldings.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                        Toast.makeText(this, String.format(Locale.ROOT, "Number of Top Holdings Should Be Less Than %d", Integer.MAX_VALUE), Toast.LENGTH_SHORT).show();
                    } else if (newNumberOfTopHoldings.compareTo(BigInteger.ONE) < 0) {
                        Toast.makeText(this, "Number of Top Holdings Should Be Greater Than 0", Toast.LENGTH_SHORT).show();
                    } else {
                        numberOfTopHoldings = newNumberOfTopHoldings.intValue();
                        editor.putInt(PREF_NUMBER_OF_TOP_HOLDINGS, numberOfTopHoldings);
                    }

                    String newMarketCapThresholdString = marketCapThresholdInput.getText().toString().trim();
                    @NonNull BigDecimal newMarketCapThreshold;
                    if (newMarketCapThresholdString.isEmpty()) {
                        newMarketCapThreshold = DEFAULT_MARKET_CAP_THRESHOLD;
                    } else {
                        newMarketCapThreshold = new BigDecimal(newMarketCapThresholdString);
                    }
                    if (newMarketCapThreshold.compareTo(BigDecimal.ZERO) > 0) {
                        marketCapThreshold = newMarketCapThreshold;
                        adapter.setMarketCapThreshold(marketCapThreshold);
                        editor.putString(PREF_MARKET_CAP_THRESHOLD, marketCapThreshold.stripTrailingZeros().toPlainString());
                    } else {
                        Toast.makeText(this, "Market Cap Threshold Should Be Greater Than 0", Toast.LENGTH_SHORT).show();
                    }

                    String newDailyDollarVolumeThresholdString = dailyDollarVolumeThresholdInput.getText().toString().trim();
                    @NonNull BigDecimal newDailyDollarVolumeThreshold;
                    if (newDailyDollarVolumeThresholdString.isEmpty()) {
                        newDailyDollarVolumeThreshold = DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD;
                    } else {
                        newDailyDollarVolumeThreshold = new BigDecimal(newDailyDollarVolumeThresholdString);
                    }
                    if (newDailyDollarVolumeThreshold.compareTo(BigDecimal.ZERO) > 0) {
                        dailyDollarVolumeThreshold = newDailyDollarVolumeThreshold;
                        adapter.setDailyDollarVolumeThreshold(dailyDollarVolumeThreshold);
                        editor.putString(PREF_DAILY_DOLLAR_VOLUME_THRESHOLD, dailyDollarVolumeThreshold.stripTrailingZeros().toPlainString());
                    } else {
                        Toast.makeText(this, "Daily Dollar Volume Threshold Should Be Greater Than 0", Toast.LENGTH_SHORT).show();
                    }

                    String newAgeInSecondsThresholdString = ageInSecondsThresholdInput.getText().toString().trim();
                    @NonNull BigDecimal newAgeInSecondsThreshold;
                    if (newAgeInSecondsThresholdString.isEmpty()) {
                        newAgeInSecondsThreshold = DEFAULT_AGE_IN_SECONDS_THRESHOLD;
                    } else {
                        newAgeInSecondsThreshold = new BigDecimal(newAgeInSecondsThresholdString);
                    }
                    if (newAgeInSecondsThreshold.compareTo(BigDecimal.ZERO) > 0) {
                        ageInSecondsThreshold = newAgeInSecondsThreshold;
                        adapter.setAgeInSecondsThreshold(ageInSecondsThreshold);
                        editor.putString(PREF_AGE_IN_SECONDS_THRESHOLD, ageInSecondsThreshold.stripTrailingZeros().toPlainString());
                    } else {
                        Toast.makeText(this, "Age Threshold (Seconds) Should Be Greater Than 0", Toast.LENGTH_SHORT).show();
                    }

                    editor.apply();
                } catch (Exception e) {
                    Toast.makeText(this, "Invalid Input", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .create();

        Window dialogWindow = configurationsDialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawableResource(R.drawable.dialog);
        }
        configurationsDialog.show();
    }

    private boolean isActivityNotCreated() {
        return !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.CREATED);
    }
}