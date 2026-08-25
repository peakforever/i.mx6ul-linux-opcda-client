package com.opc2ecu.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Collects one callback per configured item and emits a complete periodic snapshot. */
public final class CollectionCycle implements OpcDataCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionCycle.class);
    private static final int WATCHDOG_PERIODS = 3;

    public interface TimeSource {
        long nowMillis();
    }

    public interface SnapshotListener {
        void onSnapshot(long snapshotAtMillis);
    }

    private final List<String> itemOrder;
    private final UdpRecordSender sender;
    private final long watchdogMillis;
    private final TimeSource clock;
    private final SnapshotListener snapshotListener;
    private final Map<String, OpcReadValue> current = new LinkedHashMap<String, OpcReadValue>();
    private volatile long lastSnapshotAt;
    private volatile long snapshotStalls;
    private volatile int collectedItemCount;
    private volatile boolean stallReported;

    public CollectionCycle(final List<String> itemOrder, final UdpRecordSender sender) {
        this(itemOrder, sender, 1000L, new TimeSource() {
            @Override public long nowMillis() { return System.currentTimeMillis(); }
        }, new SnapshotListener() {
            @Override public void onSnapshot(final long snapshotAtMillis) { }
        });
    }

    public CollectionCycle(
            final List<String> itemOrder, final UdpRecordSender sender, final long periodMillis,
            final TimeSource clock, final SnapshotListener snapshotListener) {
        if (itemOrder == null || itemOrder.isEmpty() || sender == null) {
            throw new IllegalArgumentException("collection cycle dependencies must not be empty");
        }
        if (periodMillis <= 0L || clock == null || snapshotListener == null) {
            throw new IllegalArgumentException("collection watchdog dependencies must be valid");
        }
        this.itemOrder = new ArrayList<String>(itemOrder);
        this.sender = sender;
        this.watchdogMillis = multiplySaturated(periodMillis, WATCHDOG_PERIODS);
        this.clock = clock;
        this.snapshotListener = snapshotListener;
        this.lastSnapshotAt = clock.nowMillis();
    }

    @Override public synchronized void onData(final OpcReadValue value) {
        if (value == null || !itemOrder.contains(value.getItemId())) {
            return;
        }
        // A repeated item means SyncAccess started a new cycle before the prior cycle completed.
        if (current.containsKey(value.getItemId())) {
            current.clear();
        }
        current.put(value.getItemId(), value);
        collectedItemCount = current.size();
        if (current.size() == itemOrder.size()) {
            final List<OpcReadValue> snapshot = new ArrayList<OpcReadValue>(itemOrder.size());
            for (final String itemId : itemOrder) { snapshot.add(current.get(itemId)); }
            current.clear();
            sender.sendCycle(snapshot);
            final long snapshotAt = clock.nowMillis();
            lastSnapshotAt = snapshotAt;
            collectedItemCount = 0;
            stallReported = false;
            snapshotListener.onSnapshot(snapshotAt);
        }
    }

    /** Warns once for each period of snapshot stagnation; it never requests a reconnect. */
    public synchronized void checkWatchdog() {
        final long now = clock.nowMillis();
        if (!stallReported && now - lastSnapshotAt > watchdogMillis) {
            stallReported = true;
            snapshotStalls++;
            LOGGER.warn(
                    "OPC snapshot stalled; lastSnapshotAt={} collectedItems={}/{} thresholdMillis={}",
                    lastSnapshotAt, collectedItemCount, itemOrder.size(), watchdogMillis);
        }
    }

    public long getLastSnapshotAt() { return lastSnapshotAt; }
    public long getSnapshotStalls() { return snapshotStalls; }
    public int getCollectedItemCount() { return collectedItemCount; }

    private static long multiplySaturated(final long value, final int multiplier) {
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }
}
