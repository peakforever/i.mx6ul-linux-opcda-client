package com.opc2ecu.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Commits read cycles and publishes all points, holding failed points with Bad quality. */
public final class CollectionCycle implements OpcDataCallback {
    public static final int BAD_WAITING_FOR_INITIAL_DATA = 0x20;
    public static final int BAD_LAST_KNOWN_VALUE = 0x14;
    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionCycle.class);
    private static final int WATCHDOG_PERIODS = 3;

    public interface TimeSource {
        long nowMillis();
        default long monotonicMillis() { return nowMillis(); }
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
    private final Map<String, OpcReadValue> held = new LinkedHashMap<String, OpcReadValue>();
    private final Map<String, QualityReason> currentFailures = new LinkedHashMap<String, QualityReason>();
    private final Map<String, QualityReason> lastFailures = new LinkedHashMap<String, QualityReason>();
    private QualityReason connectionFailure;
    private final Set<String> lastCycleItems = new HashSet<String>();
    private volatile long lastSnapshotAt;
    private volatile long lastReadAt;
    private long lastCompleteSnapshotAt;
    private volatile long snapshotStalls;
    private volatile int collectedItemCount;
    private volatile int lastCycleItemCount;
    private volatile boolean stallReported;
    private long lastReadTick;
    private boolean hasRead;
    private volatile int heldItemCount;
    private volatile long invalidSamples;

    public CollectionCycle(final List<String> itemOrder, final UdpRecordSender sender) {
        this(itemOrder, sender, 1000L, new TimeSource() {
            @Override public long nowMillis() { return System.currentTimeMillis(); }
            @Override public long monotonicMillis() {
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            }
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
        this.heldItemCount = itemOrder.size();
        this.lastReadTick = clock.monotonicMillis();
    }

    @Override public synchronized void onCycleStarted() {
        current.clear();
        currentFailures.clear();
        collectedItemCount = 0;
    }

    @Override public synchronized void onData(final OpcReadValue value) {
        if (value == null || !itemIds.contains(value.getItemId())) { return; }
        try {
            final double numeric = UdpRecordSender.numericValue(value.getValue());
            final Calendar timestamp = value.getTimestamp() == null
                    ? null : (Calendar) value.getTimestamp().clone();
            final long seconds = timestamp == null ? 0L : timestamp.getTimeInMillis() / 1000L;
            if (seconds < 0L || seconds > 0xffffffffL) {
                throw new IllegalArgumentException("sample timestamp does not fit uint32");
            }
            current.put(value.getItemId(), new OpcReadValue(value.getItemId(), numeric,
                    value.getQuality() & 0xff, timestamp));
            currentFailures.remove(value.getItemId());
        } catch (final IllegalArgumentException invalid) {
            current.remove(value.getItemId());
            currentFailures.put(value.getItemId(), QualityReason.SAMPLE_NOT_ENCODABLE);
            invalidSamples++;
            if (invalidSamples == 1L || invalidSamples % 100L == 0L) {
                LOGGER.warn("OPC samples cannot be encoded; holding Bad values; total={} item={}",
                        invalidSamples, value.getItemId());
            }
        }
        collectedItemCount = current.size();
    }

    @Override public synchronized void onCycleComplete() {
        held.putAll(current);
        lastFailures.clear();
        lastFailures.putAll(currentFailures);
        connectionFailure = null;
        lastCycleItems.clear();
        lastCycleItems.addAll(current.keySet());
        lastCycleItemCount = lastCycleItems.size();
        lastReadTick = clock.monotonicMillis();
        lastReadAt = clock.nowMillis();
        hasRead = true;
        current.clear();
        currentFailures.clear();
        collectedItemCount = 0;
        if (lastCycleItems.size() == itemOrder.size()) {
            final long snapshotAt = clock.nowMillis();
            if (stallReported) {
                LOGGER.info("OPC full item set recovered; incompleteMillis={} itemCount={}",
                        Math.max(0L, snapshotAt - lastCompleteSnapshotAt), itemOrder.size());
            }
            lastCompleteSnapshotAt = snapshotAt;
            stallReported = false;
        }
    }

    @Override public void onConnectionLost() { onConnectionLost(null); }

    @Override public synchronized void onConnectionLost(final Throwable failure) {
        current.clear();
        currentFailures.clear();
        lastCycleItems.clear();
        collectedItemCount = 0;
        lastCycleItemCount = 0;
        hasRead = false;
        final QualityReason nextReason = QualityReason.fromFailure(failure);
        // A later watchdog inference must not erase an already observed concrete failure.
        if (nextReason != QualityReason.READ_STALE || connectionFailure == null) {
            connectionFailure = nextReason;
        }
    }

    @Override public synchronized void onPointFailure(final String itemId, final QualityReason reason) {
        if (!itemIds.contains(itemId)) { return; }
        current.remove(itemId);
        currentFailures.put(itemId, reason == null || reason == QualityReason.NONE
                ? QualityReason.POINT_READ_FAILED : reason);
        collectedItemCount = current.size();
    }

    /** Called only by the independent publisher, including while OPC RPC is blocked. */
    public void publish() {
        final List<OpcReadValue> snapshot = new ArrayList<OpcReadValue>(itemOrder.size());
        synchronized (this) {
            final boolean stale = clock.monotonicMillis() - lastReadTick >= watchdogMillis;
            int heldCount = 0;
            for (final String itemId : itemOrder) {
                final OpcReadValue cached = held.get(itemId);
                final QualityReason reason;
                if (connectionFailure != null) { reason = connectionFailure; }
                else if (stale) { reason = QualityReason.READ_STALE; }
                else if (!hasRead) { reason = QualityReason.WAITING_FOR_DATA; }
                else if (!lastCycleItems.contains(itemId)) {
                    final QualityReason pointReason = lastFailures.get(itemId);
                    reason = pointReason == null ? QualityReason.POINT_MISSING : pointReason;
                } else { reason = QualityReason.NONE; }
                if (reason != QualityReason.NONE) {
                    snapshot.add(new OpcReadValue(itemId, cached == null ? 0.0d : cached.getValue(),
                            reason.withOpcQuality(cached == null ? BAD_WAITING_FOR_INITIAL_DATA : BAD_LAST_KNOWN_VALUE),
                            cached == null ? null : cached.getTimestamp()));
                    heldCount++;
                } else {
                    snapshot.add(cached);
                }
            }
            heldItemCount = heldCount;
        }
        sender.sendCycle(snapshot);
        final long snapshotAt = clock.nowMillis();
        lastSnapshotAt = snapshotAt;
        snapshotListener.onSnapshot(snapshotAt);
    }

    /** Reports a prolonged incomplete item set without interrupting healthy points. */
    public synchronized void checkWatchdog() {
        final long now = clock.nowMillis();
        if (!stallReported && now - lastCompleteSnapshotAt > watchdogMillis) {
            stallReported = true;
            snapshotStalls++;
            LOGGER.warn(
                    "OPC read incomplete or stalled; failed points hold Bad values; lastSnapshotAt={} "
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
    public long getLastReadAt() { return lastReadAt; }
    public long getSnapshotStalls() { return snapshotStalls; }
    public int getLastCycleItemCount() { return lastCycleItemCount; }
    public int getCollectedItemCount() { return collectedItemCount; }
    public int getHeldItemCount() { return heldItemCount; }
    public long getInvalidSamples() { return invalidSamples; }

    private static long multiplySaturated(final long value, final int multiplier) {
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }
}
