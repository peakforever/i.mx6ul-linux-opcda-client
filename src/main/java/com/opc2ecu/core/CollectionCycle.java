package com.opc2ecu.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends the available values of each read independently of missing or failed points. */
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
    private final Set<String> itemIds;
    private final UdpRecordSender sender;
    private final long watchdogMillis;
    private final TimeSource clock;
    private final SnapshotListener snapshotListener;
    private final Map<String, OpcReadValue> current = new LinkedHashMap<String, OpcReadValue>();
    private final Set<String> lastCycleItems = new HashSet<String>();
    private volatile long lastSnapshotAt;
    private long lastCompleteSnapshotAt;
    private volatile long snapshotStalls;
    private volatile int collectedItemCount;
    private volatile int lastCycleItemCount;
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
        this.itemIds = new HashSet<String>(itemOrder);
        this.sender = sender;
        this.watchdogMillis = multiplySaturated(periodMillis, WATCHDOG_PERIODS);
        this.clock = clock;
        this.snapshotListener = snapshotListener;
        this.lastSnapshotAt = clock.nowMillis();
        this.lastCompleteSnapshotAt = lastSnapshotAt;
    }

    @Override public synchronized void onCycleStarted() {
        current.clear();
        collectedItemCount = 0;
    }

    @Override public synchronized void onData(final OpcReadValue value) {
        if (value == null || !itemIds.contains(value.getItemId())) { return; }
        current.put(value.getItemId(), value);
        collectedItemCount = current.size();
    }

    @Override public synchronized void onCycleComplete() {
        final List<OpcReadValue> snapshot = new ArrayList<OpcReadValue>(current.size());
        for (final String itemId : itemOrder) {
            final OpcReadValue value = current.get(itemId);
            if (value != null) { snapshot.add(value); }
        }
        lastCycleItems.clear();
        lastCycleItems.addAll(current.keySet());
        lastCycleItemCount = lastCycleItems.size();
        current.clear();
        collectedItemCount = 0;
        if (!snapshot.isEmpty()) {
            sender.sendCycle(snapshot);
            final long snapshotAt = clock.nowMillis();
            lastSnapshotAt = snapshotAt;
            if (lastCycleItems.size() == itemOrder.size()) {
                if (stallReported) {
                    LOGGER.info("OPC full item set recovered; incompleteMillis={} itemCount={}",
                            Math.max(0L, snapshotAt - lastCompleteSnapshotAt), itemOrder.size());
                }
                lastCompleteSnapshotAt = snapshotAt;
                stallReported = false;
            }
            snapshotListener.onSnapshot(snapshotAt);
        }
    }

    /** Reports a prolonged incomplete item set without interrupting healthy points. */
    public synchronized void checkWatchdog() {
        final long now = clock.nowMillis();
        if (!stallReported && now - lastCompleteSnapshotAt > watchdogMillis) {
            stallReported = true;
            snapshotStalls++;
            LOGGER.warn(
                    "OPC read incomplete or stalled; available points continue forwarding; lastSnapshotAt={} "
                            + "lastCompleteSnapshotAt={} thresholdMillis={} lastReadMissing={}",
                    lastSnapshotAt, lastCompleteSnapshotAt, watchdogMillis,
                    getMissingItemsSummary());
        }
    }

    /** Bounded diagnostic of the last completed read; never includes sample values. */
    public synchronized String getMissingItemsSummary() {
        final StringBuilder result = new StringBuilder("[");
        int missing = 0;
        for (final String itemId : itemOrder) {
            if (!lastCycleItems.contains(itemId)) {
                missing++;
                if (missing <= 10) {
                    if (missing > 1) { result.append(", "); }
                    final String safe = itemId.replace('\n', ' ').replace('\r', ' ');
                    result.append(safe.length() > 80 ? safe.substring(0, 80) + "..." : safe);
                }
            }
        }
        if (missing > 10) { result.append(", ... remaining=").append(missing - 10); }
        return result.append(']').toString();
    }

    public long getLastSnapshotAt() { return lastSnapshotAt; }
    public long getSnapshotStalls() { return snapshotStalls; }
    public int getLastCycleItemCount() { return lastCycleItemCount; }
    public int getCollectedItemCount() { return collectedItemCount; }

    private static long multiplySaturated(final long value, final int multiplier) {
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }
}
