package com.taiji.opc2ecu.core;

public final class ReconnectPolicy {
    public static final double JITTER_FRACTION = 0.20d;

    private final long initialDelayMillis;
    private final long maxDelayMillis;
    private final int maxAttempts;

    public ReconnectPolicy(
            final long initialDelayMillis, final long maxDelayMillis, final int maxAttempts) {
        if (initialDelayMillis <= 0L) {
            throw new IllegalArgumentException("initialDelayMillis must be greater than zero");
        }
        if (maxDelayMillis < initialDelayMillis) {
            throw new IllegalArgumentException("maxDelayMillis must not be less than initialDelayMillis");
        }
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be zero or greater");
        }
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.maxAttempts = maxAttempts;
    }

    public boolean allowsAttempt(final int attempt) {
        return attempt > 0 && (maxAttempts == 0 || attempt <= maxAttempts);
    }

    public long delayMillis(final int attempt, final double jitterUnit) {
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be greater than zero");
        }
        if (jitterUnit < 0.0d || jitterUnit > 1.0d) {
            throw new IllegalArgumentException("jitterUnit must be between zero and one");
        }
        long base = initialDelayMillis;
        for (int i = 1; i < attempt && base < maxDelayMillis; i++) {
            base = Math.min(maxDelayMillis, base > Long.MAX_VALUE / 2L ? maxDelayMillis : base * 2L);
        }
        final double factor = 1.0d - JITTER_FRACTION + (2.0d * JITTER_FRACTION * jitterUnit);
        return Math.min(maxDelayMillis, Math.max(1L, Math.round(base * factor)));
    }

    public int getMaxAttempts() { return maxAttempts; }
}
