package com.example.investment.repository.cache;

import androidx.annotation.NonNull;

import com.example.investment.model.TickerInfo;

import java.io.Serializable;
import java.util.List;

public class CacheModels {
    
    public interface Listener {
        void onProgress(int current, int total);
        void onTickerUpdate(@NonNull List<TickerInfo> tickers);
        void onFinish();
    }

    public static class CachedData implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final long timestamp;
        public final List<TickerInfo> data;
        
        public CachedData(long timestamp, List<TickerInfo> data) {
            this.timestamp = timestamp;
            this.data = data;
        }
    }

    public static class HtmlCache implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long timestamp;
        public final String html;
        
        public HtmlCache(long timestamp, String html) {
            this.timestamp = timestamp;
            this.html = html;
        }
    }
} 