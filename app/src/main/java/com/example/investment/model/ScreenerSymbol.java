package com.example.investment.model;

import androidx.annotation.NonNull;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

public record ScreenerSymbol(@NonNull String symbol, @NonNull String type, @NonNull BigDecimal marketCap) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}