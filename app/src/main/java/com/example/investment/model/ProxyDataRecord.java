package com.example.investment.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

public record ProxyDataRecord(List<ProxyStateRecord> proxies, long timestamp) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}