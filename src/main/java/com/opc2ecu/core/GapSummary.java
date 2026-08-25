package com.opc2ecu.core;

public final class GapSummary {
    private final long startMillis;
    private final long endMillis;
    private final long missedSamples;

    public GapSummary(final long startMillis, final long endMillis, final long missedSamples) {
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.missedSamples = missedSamples;
    }

    public long getStartMillis() { return startMillis; }
    public long getEndMillis() { return endMillis; }
    public long getMissedSamples() { return missedSamples; }
}
