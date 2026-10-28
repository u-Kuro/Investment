package com.example.investment.model;

import java.io.Serial;
import java.io.Serializable;

public record RefreshPoint(long sinceMillis, boolean isFullRefresh) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}