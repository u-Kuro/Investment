package com.example.investment.model;

public class NumberRange {
    private final double value;
    private final String suffix;

    public NumberRange(double value, String suffix) {
        this.value = value;
        this.suffix = suffix;
    }

    public double getValue() {
        return value;
    }

    public String getSuffix() {
        return suffix;
    }
}