package com.example.investment.util;

import android.annotation.SuppressLint;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class UrlBuilder {
    private final StringBuilder urlBuilder;
    private final List<String> params;
    private final boolean hasParams;

    public UrlBuilder(String baseUrl) {
        this.urlBuilder = new StringBuilder(baseUrl);
        this.params = new ArrayList<>();
        this.hasParams = baseUrl.contains("?");
    }

    public UrlBuilder addParam(String key, String value) {
        if (value != null) {
            params.add(encode(key) + "=" + encode(value));
        }
        return this;
    }

    @SuppressLint("NewApi")
    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    public UrlBuilder addPath(String pathSegment) {
        if (pathSegment != null) {
            if (urlBuilder.charAt(urlBuilder.length() - 1) != '/') {
                urlBuilder.append('/');
            }
            urlBuilder.append(encode(pathSegment));
        }
        return this;
    }

    public String build() {
        if (!params.isEmpty()) {
            urlBuilder.append(hasParams ? "&" : "?")
                     .append(String.join("&", params));
        }
        return urlBuilder.toString();
    }
} 