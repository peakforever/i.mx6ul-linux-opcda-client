package com.opc2ecu.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.net.NoRouteToHostException;
import org.jinterop.dcom.common.JIException;
import org.junit.Test;
import static org.junit.Assert.*;

public class QualityPropagationTest {
    private final UdpRecordSenderTest.FakeChannel channel = new UdpRecordSenderTest.FakeChannel();
    private final HeldPointValuesTest.Clock clock = new HeldPointValuesTest.Clock();
    private final CollectionCycle cycle = new CollectionCycle(Arrays.asList("a", "b"),
            new UdpRecordSender(channel, StandardCharsets.UTF_8), 1000, clock,
            new CollectionCycle.SnapshotListener() { public void onSnapshot(long at) { } });

    @Test public void pointFailurePreservesOtherPointQualityAndHeldTimestamp() {
        read(cycle, sample("a", 7), sample("b", 8));
        cycle.onCycleStarted(); cycle.onData(sample("a", 9));
        cycle.onPointFailure("b", QualityReason.POINT_READ_FAILED);
        cycle.onCycleComplete(); cycle.publish();
        assertEquals(0xc1, quality(0)); assertEquals(0x3114, quality(1));
        assertEquals(8, bytes().getDouble(46), 0.0); assertEquals(123, bytes().getInt(54));
        read(cycle, sample("a", 10), sample("b", 11)); cycle.publish();
        assertEquals(0xc1, quality(1)); assertEquals(11, bytes().getDouble(46), 0.0);
    }

    @Test public void latestPointResultWinsAndUnknownFailureDoesNotPoisonOtherItems() {
        cycle.onCycleStarted();
        cycle.onPointFailure("a", QualityReason.POINT_READ_FAILED);
        cycle.onData(sample("a", 7)); // successful later result replaces failure
        cycle.onData(sample("b", 8));
        cycle.onPointFailure("b", QualityReason.SAMPLE_NOT_ENCODABLE); // latest result fails
        cycle.onPointFailure("unknown", QualityReason.ACCESS_DENIED);
        cycle.onCycleComplete(); cycle.publish();
        assertEquals(0xc1, quality(0)); assertEquals(0x3220, quality(1));
        assertEquals(1, cycle.getLastCycleItemCount());
    }

    @Test public void explicitConnectionFaultWinsOverStalenessForInitialAndHeldPoints() {
        read(cycle, sample("a", 7));
        cycle.onConnectionLost(new JIException(0x80070005, "synthetic"));
        clock.tick = 100000; cycle.publish();
        assertEquals(0x2114, quality(0)); assertEquals(0x2120, quality(1));
        cycle.onCycleStarted(); cycle.publish(); // starting a new RPC is not recovery
        assertEquals(0x2114, quality(0));
        cycle.onData(sample("b", 8)); cycle.onCycleComplete(); cycle.publish();
        assertEquals(0x3014, quality(0)); assertEquals(0xc1, quality(1));
    }

    @Test public void initialReadThatNeverReturnsBecomesStaleWithoutInventingData() {
        clock.tick = 3000; cycle.publish();
        assertEquals(0x3320, quality(0)); assertEquals(0x3320, quality(1));
        assertEquals(0, bytes().getDouble(16), 0.0); assertEquals(0, bytes().getInt(24));
    }

    @Test public void knownFailureIsNotReplacedByLaterWatchdogInference() {
        read(cycle, sample("a", 7));
        cycle.onConnectionLost(new NoRouteToHostException());
        cycle.onConnectionLost(new OpcDaException(OpcDaException.Kind.TIMEOUT,
                DiagCode.READ_STALE, null, "callback-watchdog"));
        cycle.publish();
        assertEquals(0x1114, quality(0)); assertEquals(0x1120, quality(1));
    }

    @Test public void oldConnectionPointAndConnectionErrorsCannotOverrideFreshSamples() throws Exception {
        final List<Client> clients = new ArrayList<Client>();
        final ReconnectManager manager = new ReconnectManager(new OpcDaClientFactory() {
            public OpcDaClient create() { final Client client = new Client(); clients.add(client); return client; }
        }, new ReconnectPolicy(1, 1, 1), 1000, true, null);
        try {
            manager.start(Arrays.asList("a", "b"), cycle);
            final OpcDataCallback old = clients.get(0).callback;
            read(old, sample("a", 7), sample("b", 8));
            manager.handleFailure(new NoRouteToHostException());
            final OpcDataCallback fresh = clients.get(1).callback;
            fresh.onCycleStarted(); fresh.onData(sample("a", 9));
            old.onPointFailure("a", QualityReason.ACCESS_DENIED);
            old.onConnectionLost(new NoRouteToHostException());
            read(old, sample("a", 999));
            fresh.onData(sample("b", 10)); fresh.onCycleComplete(); cycle.publish();
            assertEquals(0xc1, quality(0)); assertEquals(0xc1, quality(1));
            assertEquals(9, bytes().getDouble(16), 0.0); assertEquals(10, bytes().getDouble(46), 0.0);
        } finally { manager.close(); }
    }

    @Test public void retryFailuresUpdateReasonAndSuccessfulBindDoesNotPretendToHaveData() throws Exception {
        final List<Client> clients = new ArrayList<Client>();
        final List<Integer> duringBackoff = new ArrayList<Integer>();
        final ReconnectManager manager = new ReconnectManager(new OpcDaClientFactory() {
            public OpcDaClient create() {
                final int attempt = clients.size();
                final Client client = new Client() {
                    public void connect() throws OpcDaException {
                        if (attempt < 2) {
                            throw new OpcDaException(OpcDaException.Kind.CONNECTION, "synthetic",
                                    attempt == 0 ? new JIException(0x80070005, "denied")
                                            : new NoRouteToHostException());
                        }
                    }
                };
                clients.add(client); return client;
            }
        }, new ReconnectPolicy(1, 1, 2), 1000, true,
                new ReconnectManager.TimeSource() { public long nowMillis() { return 1000; } },
                new ReconnectManager.Sleeper() {
                    public void sleep(long millis) { cycle.publish(); duringBackoff.add(quality(0)); }
                }, new ReconnectManager.JitterSource() { public double nextDouble() { return 0.5; } }, null);
        try {
            manager.startWithRecovery(Arrays.asList("a", "b"), cycle);
            assertEquals(Arrays.asList(0x2120, 0x1120), duringBackoff);
            cycle.publish(); assertEquals(0x1120, quality(0));
            read(clients.get(2).callback, sample("a", 7)); cycle.publish();
            assertEquals(0xc1, quality(0)); assertEquals(0x3020, quality(1));
        } finally { manager.close(); }
    }

    @Test public void publicationDoesNotHideConnectionWatchdogReason() throws Exception {
        final long[] now = {1000};
        final ReconnectManager manager = new ReconnectManager(new OpcDaClientFactory() {
            public OpcDaClient create() { return new Client(); }
        }, new ReconnectPolicy(1, 1, 1), 1000, true,
                new ReconnectManager.TimeSource() { public long nowMillis() { return now[0]; } },
                new ReconnectManager.Sleeper() { public void sleep(long millis) { } },
                new ReconnectManager.JitterSource() { public double nextDouble() { return 0.5; } }, null);
        try {
            manager.start(Arrays.asList("a", "b"), cycle);
            now[0] = 4001; cycle.publish(); manager.checkWatchdog(); cycle.publish();
            assertEquals(0x3320, quality(0));
        } finally { manager.close(); }
    }

    private static OpcReadValue sample(String id, double number) {
        return HeldPointValuesTest.sample(id, number, 0xabc1, 123);
    }

    private static void read(OpcDataCallback target, OpcReadValue... samples) {
        target.onCycleStarted();
        for (OpcReadValue value : samples) { target.onData(value); }
        target.onCycleComplete();
    }

    private ByteBuffer bytes() {
        return ByteBuffer.wrap(channel.sent.get(channel.sent.size()-1)).order(ByteOrder.LITTLE_ENDIAN);
    }
    private int quality(int index) { return bytes().getShort(index * 30 + 28) & 0xffff; }

    private static class Client implements OpcDaClient {
        OpcDataCallback callback;
        public void connect() throws OpcDaException { }
        public void disconnect() { }
        public boolean isConnected() { return true; }
        public List<String> browseItems() { return Collections.emptyList(); }
        public int exportCatalog() { return 0; }
        public OpcReadValue readItem(String id) { return null; }
        public void bindSyncRead(OpcDataCallback target) { callback = target; }
        public void bindSyncRead(List<String> ids, OpcDataCallback target) { callback = target; }
        public void unbind() { }
    }
}
