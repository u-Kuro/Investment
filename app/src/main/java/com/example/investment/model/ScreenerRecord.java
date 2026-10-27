package com.example.investment.model;

import androidx.annotation.NonNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

public record ScreenerRecord(@NonNull String screenerId, @NonNull List<ScreenerSymbol> symbols, long retrievedAtMillis) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}