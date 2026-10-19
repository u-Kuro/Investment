package com.example.investment.repository;

import static com.example.investment.util.Constants.FILE_RECORD_PROXIES;
import static com.example.investment.util.Constants.PROXY_LIST_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.PROXY_LIST_URLS;
import static com.example.investment.util.Constants.PROXY_REFRESH_MILLISECONDS;
import static com.example.investment.util.FileUtils.loadObject;
import static com.example.investment.util.FileUtils.saveObject;

import android.content.Context;
import android.net.InetAddresses;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ProxyDataRecord;
import com.example.investment.util.Logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;

public class ProxyRepository {
    @NonNull private static final OkHttpClient client = new OkHttpClient.Builder()
        .callTimeout(PROXY_LIST_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        .build();

    @NonNull private final List<InetSocketAddress> addresses = new ArrayList<>();
    private boolean isLoaded = false;
    private long nextFetchAtMilliseconds = 0;

    // Candidates
    // Every proxy plus the own IP, shuffled, so a single request can go around all of them
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public synchronized @NonNull List<Proxy> getCandidates(@NonNull Context context) {
        refreshIfNeeded(context);

        @NonNull List<Proxy> candidates = new ArrayList<>();
        for (InetSocketAddress address : addresses) candidates.add(new Proxy(Proxy.Type.SOCKS, address));
        candidates.add(Proxy.NO_PROXY);
        Collections.shuffle(candidates);
        return candidates;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void refreshIfNeeded(@NonNull Context context) {
        if (!isLoaded) loadCache(context);
        if (Thread.currentThread().isInterrupted()) return;

        long now = System.currentTimeMillis();
        if (now < nextFetchAtMilliseconds) return;

        // Set first, so a failed download is not retried on every request
        nextFetchAtMilliseconds = now + PROXY_REFRESH_MILLISECONDS;
        try {
            @NonNull List<InetSocketAddress> fetched = fetchProxies();
            addresses.clear();
            addresses.addAll(fetched);
            saveObject(context, FILE_RECORD_PROXIES, new ProxyDataRecord(new ArrayList<>(addresses), now));
        } catch (Exception e) {
            // The cached proxies, if any, stay in use
            Logger.error("Error refreshing proxies", e);
        }
    }

    // Proxy Fetching
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private @NonNull List<InetSocketAddress> fetchProxies() throws Exception {
        for (String url : PROXY_LIST_URLS) {
            try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
                if (!response.isSuccessful()) throw new Exception("Unexpected proxy list response: " + response);
                ResponseBody body = response.body();
                if (body == null) throw new Exception("Empty response body");

                @NonNull List<InetSocketAddress> proxies = parseProxies(body.string());
                if (proxies.isEmpty()) throw new Exception("No usable proxy in the list");

                Logger.debug("Proxies fetched: " + proxies.size() + " from " + url);
                return proxies;
            } catch (Exception e) {
                // Next source
                Logger.error("Error fetching proxy list from " + url, e);
            }
        }

        throw new Exception("No proxy list source returned usable proxies");
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private @NonNull List<InetSocketAddress> parseProxies(@NonNull String body) throws Exception {
        JSONArray rows = new JSONArray(body);

        @NonNull List<InetSocketAddress> proxies = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                // Numeric only, so there is never a DNS lookup
                InetAddress address = InetAddresses.parseNumericAddress(row.getString("ip"));
                if (!(address instanceof Inet4Address)) continue;

                proxies.add(new InetSocketAddress(address, row.getInt("port")));
            } catch (Exception ignored) {}
        }

        return proxies;
    }

    // Cache
    private void loadCache(@NonNull Context context) {
        isLoaded = true;
        try {
            @Nullable ProxyDataRecord record = (ProxyDataRecord) loadObject(context, FILE_RECORD_PROXIES);
            if (record == null) return;

            addresses.addAll(record.addresses());
            // A file from the last 5 minutes is as good as a new download
            nextFetchAtMilliseconds = record.timestamp() + PROXY_REFRESH_MILLISECONDS;
        } catch (Exception e) {
            Logger.error("Error loading proxies", e);
        }
    }
}
