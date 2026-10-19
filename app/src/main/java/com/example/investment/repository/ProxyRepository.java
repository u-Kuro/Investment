package com.example.investment.repository;

import static com.example.investment.util.Constants.FILE_RECORD_PROXIES;
import static com.example.investment.util.Constants.PROXY_COOLDOWN_MILLISECONDS;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // Routes that answered before
    @NonNull private final Set<Proxy> provenCandidates = new HashSet<>();
    // Routes that failed, left out until the time here passes
    @NonNull private final Map<Proxy, Long> cooldownUntilMilliseconds = new HashMap<>();
    private boolean isLoaded = false;
    private long nextFetchAtMilliseconds = 0;

    // Candidates
    // Routes that worked before go first, then the untried ones, each shuffled, so the load is still spread
    // Failed routes are left out for a while, a dead proxy costs a whole timeout on every request
    // The own IP is one of the candidates too
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public synchronized @NonNull List<Proxy> getCandidates(@NonNull Context context) {
        refreshIfNeeded(context);

        long now = System.currentTimeMillis();
        cooldownUntilMilliseconds.values().removeIf(until -> until <= now);

        @NonNull List<Proxy> proven = new ArrayList<>();
        @NonNull List<Proxy> untried = new ArrayList<>();
        for (InetSocketAddress address : addresses) {
            @NonNull Proxy proxy = new Proxy(Proxy.Type.SOCKS, address);
            if (cooldownUntilMilliseconds.containsKey(proxy)) continue;
            if (provenCandidates.contains(proxy)) {
                proven.add(proxy);
            } else {
                untried.add(proxy);
            }
        }
        if (provenCandidates.contains(Proxy.NO_PROXY)) {
            proven.add(Proxy.NO_PROXY);
        } else {
            untried.add(Proxy.NO_PROXY);
        }

        Collections.shuffle(proven);
        Collections.shuffle(untried);
        @NonNull List<Proxy> candidates = new ArrayList<>(proven);
        candidates.addAll(untried);
        return candidates;
    }

    public synchronized void reportSuccess(@NonNull Proxy proxy) {
        cooldownUntilMilliseconds.remove(proxy);
        provenCandidates.add(proxy);
    }

    public synchronized void reportFailure(@NonNull Proxy proxy) {
        provenCandidates.remove(proxy);
        // The own IP is the last resort, it never leaves the list
        if (Proxy.NO_PROXY.equals(proxy)) return;
        cooldownUntilMilliseconds.put(proxy, System.currentTimeMillis() + PROXY_COOLDOWN_MILLISECONDS);
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
            dropUnlistedCandidates();
            saveObject(context, FILE_RECORD_PROXIES, new ProxyDataRecord(new ArrayList<>(addresses), now));
        } catch (Exception e) {
            // The cached proxies, if any, stay in use
            Logger.error("Error refreshing proxies", e);
        }
    }

    // A route that left the list is never asked for again, so what is remembered about it can go
    private void dropUnlistedCandidates() {
        @NonNull Set<Proxy> listed = new HashSet<>();
        for (InetSocketAddress address : addresses) listed.add(new Proxy(Proxy.Type.SOCKS, address));
        listed.add(Proxy.NO_PROXY);
        provenCandidates.retainAll(listed);
        cooldownUntilMilliseconds.keySet().retainAll(listed);
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
