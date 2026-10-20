package com.example.investment.model;

import java.io.Serial;
import java.io.Serializable;
import java.net.InetSocketAddress;

public record ProxyStateRecord(
    InetSocketAddress address,
    long cooldownMilliseconds,
    long availableAtMilliseconds
) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}