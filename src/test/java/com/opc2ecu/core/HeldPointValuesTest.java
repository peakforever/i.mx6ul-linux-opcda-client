package com.opc2ecu.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class HeldPointValuesTest {
    private final UdpRecordSenderTest.FakeChannel channel = new UdpRecordSenderTest.FakeChannel();
    private final Clock clock = new Clock();
    private final CollectionCycle cycle = new CollectionCycle(Arrays.asList("a", "b"),
            new UdpRecordSender(channel, StandardCharsets.UTF_8), 1000L, clock,
            new CollectionCycle.SnapshotListener() { public void onSnapshot(long at) { } });

    @Test public void initialAndMissingReadsSendZeroWithDifferentApplicationReasons() {
        cycle.publish();
        record(0, 0, 0, 0, 0x0120); record(0, 1, 0, 0, 0x0120);
        read(); cycle.publish();
        record(1, 0, 0, 0, 0x3020); record(1, 1, 0, 0, 0x3020);
        assertEquals(2, cycle.getHeldItemCount());
    }

    @Test public void missingPointHoldsValueAndOriginalTimeThenRecovers() {
        read(sample("a", 12, 192, 123), sample("b", 34, 192, 456)); cycle.publish();
        record(0, 0, 12, 123, 192); record(0, 1, 34, 456, 192);
        read(sample("a", 13, 192, 124)); cycle.publish();
        record(1, 0, 13, 124, 192); record(1, 1, 34, 456, 0x3014);
        assertEquals(1, cycle.getHeldItemCount());
        read(); cycle.publish();
        record(2, 0, 13, 124, 0x3014); record(2, 1, 34, 456, 0x3014);
        read(sample("a", 14, 192, 125), sample("b", 35, 192, 457)); cycle.publish();
        record(3, 1, 35, 457, 192);
        assertEquals(0, cycle.getHeldItemCount());
    }

    @Test public void serverBadAndUncertainKeepLowByteAndDiscardVendorByte() {
        read(sample("a", 99, 0xab0c, 123), sample("b", 11, 0x40, 456)); cycle.publish();
        record(0, 0, 99, 123, 0x000c); record(0, 1, 11, 456, 0x40);
        read(); cycle.publish();
        record(1, 0, 99, 123, 0x3014); record(1, 1, 11, 456, 0x3014);
    }

    @Test public void invalidNumbersAndTimestampsNeverDestroyLastEncodableValues() {
        read(sample("a", 12, 192, 123));
        read(sample("a", "bad", 192, 124), sample("b", new int[] {1}, 192, 124));
        cycle.publish(); record(0, 0, 12, 123, 0x3214); record(0, 1, 0, 0, 0x3220);
        read(sample("a", 77, 192, 0x100000000L)); cycle.publish();
        record(1, 0, 12, 123, 0x3214);
        assertEquals(3, cycle.getInvalidSamples());
    }

    @Test public void connectionLossImmediatelyDowngradesAndDiscardsUncommittedRead() {
        read(sample("a", 12, 192, 123));
        cycle.onCycleStarted(); cycle.onData(sample("b", 88, 192, 999));
        cycle.onConnectionLost(new java.net.NoRouteToHostException()); cycle.publish();
        record(0, 0, 12, 123, 0x1114); record(0, 1, 0, 0, 0x1120);
        read(sample("b", 13, 192, 124)); cycle.publish();
        record(1, 0, 12, 123, 0x3014); record(1, 1, 13, 124, 192);
    }

    @Test public void stalledReadDowngradesAtThreePeriodsAndSendingCannotRefreshIt() {
        read(sample("a", 12, 192, 123));
        clock.tick = 2999; clock.wall = 999999; cycle.publish(); record(0, 0, 12, 123, 192);
        clock.tick = 3000; clock.wall = 1; cycle.publish(); record(1, 0, 12, 123, 0x3314);
        clock.tick = 4000; cycle.publish(); record(2, 0, 12, 123, 0x3314);
        read(sample("a", 13, 192, 124)); cycle.publish(); record(3, 0, 13, 124, 192);
    }

    @Test public void callbackOnlyPublishesCommittedReadsAndSnapshotsMutableObjects() {
        final AtomicInteger mutable = new AtomicInteger(12);
        final Calendar timestamp = Calendar.getInstance(); timestamp.setTimeInMillis(123000L);
        cycle.onCycleStarted(); cycle.onData(new OpcReadValue("a", mutable, 192, timestamp));
        cycle.publish(); record(0, 0, 0, 0, 0x0120);
        mutable.set(77); timestamp.setTimeInMillis(456000L);
        cycle.onCycleComplete(); cycle.publish(); record(1, 0, 12, 123, 192);
        read(sample("b", true, 192, 456)); cycle.publish();
        record(2, 0, 12, 123, 0x3014); record(2, 1, 1, 456, 192);
    }

    @Test public void publicationDoesNotMakeMissingCallbacksHealthy() {
        clock.wall = 3001;
        cycle.publish(); cycle.checkWatchdog(); cycle.publish(); cycle.checkWatchdog();
        assertEquals(1, cycle.getSnapshotStalls());
        assertEquals(0, cycle.getLastCycleItemCount());
    }

    @Test public void processRestartHasNoRetainedValues() {
        read(sample("a", 12, 192, 123)); cycle.publish();
        final CollectionCycle fresh = new CollectionCycle(Arrays.asList("a", "b"),
                new UdpRecordSender(channel, StandardCharsets.UTF_8));
        fresh.publish(); record(1, 0, 0, 0, 0x0120);
    }

    @Test public void staleThresholdUsesLongArithmeticAtMinimumAndMaximumPeriods() {
        for (final long period : new long[] {1, 1000, Integer.MAX_VALUE}) {
            final Clock time = new Clock();
            final CollectionCycle other = new CollectionCycle(Arrays.asList("a", "b"),
                    new UdpRecordSender(channel, StandardCharsets.UTF_8), period, time,
                    new CollectionCycle.SnapshotListener() { public void onSnapshot(long at) { } });
            other.onData(sample("a", 12, 192, 123)); other.onCycleComplete();
            time.tick = 3L * period - 1; other.publish();
            record(channel.sent.size()-1, 0, 12, 123, 192);
            time.tick++; other.publish(); record(channel.sent.size()-1, 0, 12, 123, 0x3314);
        }
    }

    private void read(final OpcReadValue... values) {
        cycle.onCycleStarted();
        for (OpcReadValue value : values) { cycle.onData(value); }
        cycle.onCycleComplete();
    }

    static OpcReadValue sample(String id, Object value, int quality, long seconds) {
        final Calendar time = Calendar.getInstance(); time.setTimeInMillis(seconds * 1000L);
        return new OpcReadValue(id, value, quality, time);
    }

    private void record(int packet, int index, double value, long seconds, int quality) {
        final byte[] bytes = channel.sent.get(packet);
        assertEquals(60, bytes.length);
        final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(index * 30 + 16);
        assertEquals(value, buffer.getDouble(), 0.0);
        assertEquals(seconds, buffer.getInt() & 0xffffffffL);
        assertEquals(quality, buffer.getShort() & 0xffff);
    }

    static final class Clock implements CollectionCycle.TimeSource {
        long wall; long tick;
        public long nowMillis() { return wall; }
        public long monotonicMillis() { return tick; }
    }
}
