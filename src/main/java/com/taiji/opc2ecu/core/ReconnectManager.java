package com.taiji.opc2ecu.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the OPC client lifecycle and never reuses a failed client instance. */
public final class ReconnectManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReconnectManager.class);
    private static final int DEFAULT_WATCHDOG_PERIODS = 3;

    public interface TimeSource {
        long nowMillis();
    }

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public interface JitterSource {
        double nextDouble();
    }

    public interface GapListener {
        void onGapRecovered(GapSummary gap);
    }

    private final OpcDaClientFactory factory;
    private final ReconnectPolicy policy;
    private final long periodMillis;
    private final long watchdogMillis;
    private final boolean reconnectEnabled;
    private final TimeSource clock;
    private final Sleeper sleeper;
    private final JitterSource jitter;
    private final GapListener gapListener;

    private volatile ConnectionState state = ConnectionState.STOPPED;
    private volatile long lastSampleMillis;
    private volatile boolean stopRequested;
    private OpcDaClient client;
    private OpcDataCallback callback;
    private List<String> items;
    private GapSummary lastGap;
    private long missedSamples;

    public ReconnectManager(
            final OpcDaClientFactory factory,
            final ReconnectPolicy policy,
            final long periodMillis,
            final boolean reconnectEnabled,
            final GapListener gapListener) {
        this(factory, policy, periodMillis, reconnectEnabled,
                new TimeSource() {
                    @Override public long nowMillis() { return System.currentTimeMillis(); }
                },
                new Sleeper() {
                    @Override public void sleep(final long millis) throws InterruptedException {
                        Thread.sleep(millis);
                    }
                },
                new JitterSource() {
                    @Override public double nextDouble() { return Math.random(); }
                },
                gapListener);
    }

    public ReconnectManager(
            final OpcDaClientFactory factory,
            final ReconnectPolicy policy,
            final long periodMillis,
            final boolean reconnectEnabled,
            final TimeSource clock,
            final Sleeper sleeper,
            final JitterSource jitter,
            final GapListener gapListener) {
        if (factory == null || policy == null || clock == null || sleeper == null || jitter == null) {
            throw new IllegalArgumentException("ReconnectManager dependencies must not be null");
        }
        if (periodMillis <= 0L) {
            throw new IllegalArgumentException("periodMillis must be greater than zero");
        }
        this.factory = factory;
        this.policy = policy;
        this.periodMillis = periodMillis;
        this.watchdogMillis = multiplySaturated(periodMillis, DEFAULT_WATCHDOG_PERIODS);
        this.reconnectEnabled = reconnectEnabled;
        this.clock = clock;
        this.sleeper = sleeper;
        this.jitter = jitter;
        this.gapListener = gapListener == null ? new GapListener() {
            @Override public void onGapRecovered(final GapSummary gap) { }
        } : gapListener;
    }

    public synchronized void start(
            final List<String> itemIds, final OpcDataCallback applicationCallback) throws OpcDaException {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (applicationCallback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (state != ConnectionState.STOPPED) {
            throw new IllegalStateException("ReconnectManager is already started");
        }
        stopRequested = false;
        items = Collections.unmodifiableList(new ArrayList<String>(itemIds));
        callback = wrap(applicationCallback);
        state = ConnectionState.CONNECTING;
        final OpcDaClient candidate = factory.create();
        try {
            candidate.connect();
            try {
                candidate.bindSyncRead(items, callback);
            } catch (final Exception bindFailure) {
                throw readFailure(
                        "Connected to the OPC DA server but failed to bind the configured item. "
                                + "Confirm the item ID exists and is readable.",
                        bindFailure);
            }
            client = candidate;
            lastSampleMillis = clock.nowMillis();
            state = ConnectionState.CONNECTED;
        } catch (final Exception e) {
            closeClient(candidate);
            state = ConnectionState.STOPPED;
            if (e instanceof OpcDaException
                    && ((OpcDaException) e).getKind() == OpcDaException.Kind.READ) {
                throw (OpcDaException) e;
            }
            throw new OpcDaException(
                    OpcDaException.Kind.CONNECTION,
                    "Unable to connect to the OPC DA server. Check OPC_PASSWORD, ProgID/CLSID, "
                            + "DCOM permissions, and Windows firewall TCP 135/RPC dynamic ports.",
                    e);
        }
    }

    /** Preserves the iteration-1 API for the single configured item. */
    public synchronized void start(final OpcDataCallback applicationCallback) throws OpcDaException {
        start(Collections.singletonList("__iteration1_single_item__"), applicationCallback);
    }

    public synchronized OpcReadValue readItem(final String itemId) throws OpcDaException {
        if (state != ConnectionState.CONNECTED || client == null) {
            throw new OpcDaException(
                    OpcDaException.Kind.CONNECTION,
                    "OPC DA client is not connected; start it before reading an item.");
        }
        try {
            return client.readItem(itemId);
        } catch (final Exception firstFailure) {
            handleFailure(firstFailure);
            try {
                return client.readItem(itemId);
            } catch (final Exception retryFailure) {
                throw readFailure(
                        "OPC item read still failed after reconnect. Confirm the item ID exists and is readable.",
                        retryFailure);
            }
        }
    }

    public void checkWatchdog() throws OpcDaException {
        if (state == ConnectionState.CONNECTED
                && clock.nowMillis() - lastSampleMillis > watchdogMillis) {
            handleFailure(new OpcDaException(
                    OpcDaException.Kind.TIMEOUT,
                    "No OPC callback was received within " + watchdogMillis + " ms"));
        }
    }

    public synchronized void handleFailure(final Throwable failure) throws OpcDaException {
        if (stopRequested || state == ConnectionState.STOPPED) {
            return;
        }
        final long failureDetectedAt = clock.nowMillis();
        final long gapStart = Math.min(lastSampleMillis, failureDetectedAt);
        LOGGER.warn("OPC connection lost; starting recovery: {}", safeMessage(failure));
        state = ConnectionState.RECONNECTING;
        closeClient(client);
        client = null;

        if (!reconnectEnabled) {
            state = ConnectionState.STOPPED;
            throw readFailure("OPC read failed and reconnect.enabled=false", failure);
        }

        int attempt = 1;
        Throwable lastFailure = failure;
        while (!stopRequested && policy.allowsAttempt(attempt)) {
            final long delay = policy.delayMillis(attempt, jitter.nextDouble());
            LOGGER.info("Reconnect attempt {} will start in {} ms", attempt, delay);
            try {
                sleeper.sleep(delay);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                state = ConnectionState.STOPPED;
                throw new OpcDaException(
                        OpcDaException.Kind.TIMEOUT,
                        "Reconnect wait was interrupted; stop the service cleanly and restart it.", e);
            }
            if (stopRequested) {
                break;
            }

            final OpcDaClient candidate = factory.create();
            try {
                candidate.connect();
                candidate.bindSyncRead(items, callback);
                client = candidate;
                lastSampleMillis = clock.nowMillis();
                state = ConnectionState.CONNECTED;
                recordGap(gapStart, lastSampleMillis);
                LOGGER.info("OPC connection recovered on attempt {}", attempt);
                return;
            } catch (final Exception e) {
                lastFailure = e;
                closeClient(candidate);
                LOGGER.warn("Reconnect attempt {} failed: {}", attempt, safeMessage(e));
                attempt++;
            }
        }

        state = ConnectionState.STOPPED;
        if (stopRequested) {
            return;
        }
        throw readFailure(
                "OPC reconnect attempts were exhausted. Verify network reachability, DCOM permissions, "
                        + "and Windows firewall TCP 135/RPC dynamic ports.",
                lastFailure);
    }

    private OpcDataCallback wrap(final OpcDataCallback applicationCallback) {
        return new OpcDataCallback() {
            @Override
            public void onData(final OpcReadValue value) {
                lastSampleMillis = clock.nowMillis();
                applicationCallback.onData(value);
            }
        };
    }

    private void recordGap(final long start, final long end) {
        final long duration = Math.max(0L, end - start);
        final long missed = Math.max(1L, (duration + periodMillis - 1L) / periodMillis);
        missedSamples += missed;
        lastGap = new GapSummary(start, end, missed);
        gapListener.onGapRecovered(lastGap);
    }

    private static OpcDaException readFailure(final String message, final Throwable cause) {
        return new OpcDaException(OpcDaException.Kind.READ, message, cause);
    }

    private static long multiplySaturated(final long value, final int multiplier) {
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static String safeMessage(final Throwable error) {
        if (error == null || error.getMessage() == null) {
            return error == null ? "unknown failure" : error.getClass().getSimpleName();
        }
        return error.getMessage();
    }

    private static void closeClient(final OpcDaClient target) {
        if (target == null) {
            return;
        }
        try {
            target.unbind();
        } catch (final Exception e) {
            LOGGER.debug("Failed to unbind discarded OPC client", e);
        } finally {
            try {
                target.disconnect();
            } catch (final RuntimeException e) {
                LOGGER.debug("Failed to disconnect discarded OPC client", e);
            }
        }
    }

    public ConnectionState getState() { return state; }
    public GapSummary getLastGap() { return lastGap; }
    public long getMissedSamples() { return missedSamples; }
    public long getLastSampleMillis() { return lastSampleMillis; }

    @Override
    public synchronized void close() {
        stopRequested = true;
        closeClient(client);
        client = null;
        state = ConnectionState.STOPPED;
    }
}
