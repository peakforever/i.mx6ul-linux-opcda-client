package com.opc2ecu.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class MultiItemCollectionTest {
    @Test public void reconnectBindsEveryItemToFreshClients() throws Exception {
        final CapturingFactory factory=new CapturingFactory();
        final ReconnectManager manager=new ReconnectManager(factory,new ReconnectPolicy(1,1,1),1000,true,
                new ReconnectManager.TimeSource(){long now;public long nowMillis(){return now+=1000;}},
                new ReconnectManager.Sleeper(){public void sleep(long m){}},
                new ReconnectManager.JitterSource(){public double nextDouble(){return 0;}},
                null);
        final List<String> items=Arrays.asList("a","b"," c ");
        manager.start(items,new OpcDataCallback(){public void onData(OpcReadValue value){}});
        manager.handleFailure(new RuntimeException("down"));
        assertEquals(2,factory.clients.size());
        assertEquals(items,factory.clients.get(0).items);
        assertEquals(items,factory.clients.get(1).items);
        assertNotSame(factory.clients.get(0),factory.clients.get(1));
        manager.close();
    }
    @Test public void collectionSendsOnlyOnPublication(){
        final UdpRecordSenderTest.FakeChannel channel=new UdpRecordSenderTest.FakeChannel();
        final CollectionCycle cycle=new CollectionCycle(Arrays.asList("a","b"),new UdpRecordSender(channel,java.nio.charset.StandardCharsets.US_ASCII));
        cycle.onData(value("a")); assertTrue(channel.sent.isEmpty()); cycle.onData(value("b")); assertTrue(channel.sent.isEmpty()); cycle.onCycleComplete();cycle.publish(); assertEquals(1,channel.sent.size());
    }
    @Test public void repeatedItemKeepsLatestInCurrentRead(){
        final UdpRecordSenderTest.FakeChannel channel=new UdpRecordSenderTest.FakeChannel();
        final CollectionCycle cycle=new CollectionCycle(Arrays.asList("a","b"),new UdpRecordSender(channel,java.nio.charset.StandardCharsets.US_ASCII));
        cycle.onData(value("a"));cycle.onData(value("a"));cycle.onData(value("b"));cycle.onCycleComplete();cycle.publish();assertEquals(1,channel.sent.size());
    }
    @Test public void partialCallbacksTriggerOneSnapshotStallWarningCount(){
        final UdpRecordSenderTest.FakeChannel channel=new UdpRecordSenderTest.FakeChannel();
        final MutableCycleTime time=new MutableCycleTime();
        final CollectionCycle cycle=new CollectionCycle(Arrays.asList("a","b"),new UdpRecordSender(channel,java.nio.charset.StandardCharsets.US_ASCII),1000,time,new NoopSnapshotListener());
        cycle.onData(value("a"));cycle.onCycleComplete();cycle.publish();time.now=3001;cycle.checkWatchdog();cycle.checkWatchdog();
        assertEquals(1,cycle.getSnapshotStalls());assertEquals(1,cycle.getLastCycleItemCount());assertEquals(1,channel.sent.size());
    }
    @Test public void completeSnapshotPreventsStallAndNotifiesListener(){
        final UdpRecordSenderTest.FakeChannel channel=new UdpRecordSenderTest.FakeChannel();
        final MutableCycleTime time=new MutableCycleTime();final List<Long>snapshots=new ArrayList<Long>();
        final CollectionCycle cycle=new CollectionCycle(Arrays.asList("a","b"),new UdpRecordSender(channel,java.nio.charset.StandardCharsets.US_ASCII),1000,time,new CollectionCycle.SnapshotListener(){public void onSnapshot(long at){snapshots.add(at);}});
        time.now=2500;cycle.onData(value("a"));cycle.onData(value("b"));cycle.onCycleComplete();cycle.publish();time.now=5001;cycle.checkWatchdog();
        assertEquals(0,cycle.getSnapshotStalls());assertEquals(Collections.singletonList(2500L),snapshots);assertEquals(2500L,cycle.getLastSnapshotAt());
    }
    @Test public void completedSnapshotResetsStallEpisode(){
        final UdpRecordSenderTest.FakeChannel channel=new UdpRecordSenderTest.FakeChannel();
        final MutableCycleTime time=new MutableCycleTime();
        final CollectionCycle cycle=new CollectionCycle(Arrays.asList("a","b"),new UdpRecordSender(channel,java.nio.charset.StandardCharsets.US_ASCII),1000,time,new NoopSnapshotListener());
        time.now=3001;cycle.checkWatchdog();cycle.onData(value("a"));cycle.onData(value("b"));cycle.onCycleComplete();cycle.publish();time.now=6002;cycle.checkWatchdog();
        assertEquals(2,cycle.getSnapshotStalls());
    }
    @Test public void missingItemsSummaryIsBoundedAndTracksCurrentCycle() {
        final List<String> items = new ArrayList<String>();
        items.add("present");
        for (int i = 0; i < 20; i++) { items.add(String.join("", Collections.nCopies(100, "x")) + i); }
        final CollectionCycle cycle = new CollectionCycle(items,
                new UdpRecordSender(new UdpRecordSenderTest.FakeChannel(), java.nio.charset.StandardCharsets.UTF_8));
        cycle.onData(value("present"));
        cycle.onCycleComplete();cycle.publish();
        final String missing = cycle.getMissingItemsSummary();
        assertFalse(missing.contains("present"));
        assertTrue(missing.contains("remaining=10"));
        assertTrue(missing.length() < 900);
        assertEquals(1, cycle.getLastCycleItemCount());
    }
    @Test public void missingPointHoldsLastValueWithoutBlockingHealthyPoints() {
        final UdpRecordSenderTest.FakeChannel channel = new UdpRecordSenderTest.FakeChannel();
        final UdpRecordSender sender = new UdpRecordSender(channel, java.nio.charset.StandardCharsets.UTF_8);
        final CollectionCycle cycle = new CollectionCycle(Arrays.asList("a", "b", "c"), sender);
        for (int i = 1; i <= 4; i++) {
            complete(cycle, sample("b", i + 10), sample("a", i));
            assertPacket(channel.sent.get(i - 1), i, i + 10);
        }
        assertEquals("[c]", cycle.getMissingItemsSummary());
        complete(cycle, sample("c", 9), sample("a", 5), sample("b", 6));
        assertEquals(90, channel.sent.get(4).length);
        complete(cycle, sample("a", 7), sample("b", 8));
        assertPacket(channel.sent.get(5), 7, 8);
        complete(cycle);
        assertEquals(7, channel.sent.size());
    }

    @Test public void newReadDiscardsUnfinishedValuesAndRepeatedPointUsesLatestValue() {
        final UdpRecordSenderTest.FakeChannel channel = new UdpRecordSenderTest.FakeChannel();
        final CollectionCycle cycle = new CollectionCycle(Arrays.asList("a", "b", "c"),
                new UdpRecordSender(channel, java.nio.charset.StandardCharsets.UTF_8));
        cycle.onCycleStarted();
        cycle.onData(sample("c", 999));
        complete(cycle, sample("a", 1), sample("a", 2), sample("b", 3));
        assertPacket(channel.sent.get(0), 2, 3);
        complete(cycle, sample("unknown", 5));
        assertEquals(2, channel.sent.size());
    }

    @Test public void badPointAndPartialReadStillRespect48RecordPackets() {
        final UdpRecordSenderTest.FakeChannel channel = new UdpRecordSenderTest.FakeChannel();
        final List<String> items = new ArrayList<String>();
        for (int i = 0; i < 51; i++) { items.add("item" + i); }
        final UdpRecordSender sender = new UdpRecordSender(channel, java.nio.charset.StandardCharsets.UTF_8);
        final CollectionCycle cycle = new CollectionCycle(items, sender);
        cycle.onCycleStarted();
        for (int i = 0; i < 49; i++) { cycle.onData(sample(items.get(i), i)); }
        cycle.onData(new OpcReadValue("item49", "bad", 0, null));
        cycle.onCycleComplete();cycle.publish(); // item50 never arrives; item49 cannot be encoded.
        assertEquals(2, channel.sent.size());
        assertEquals(1440, channel.sent.get(0).length);
        assertEquals(90, channel.sent.get(1).length);
        assertEquals(51, sender.getRecordsSent());
        assertEquals(0, sender.getRecordsSkipped());
        assertEquals(1, cycle.getInvalidSamples());
    }

    @Test public void reconnectRejectsOldCycleBoundariesAndValues() throws Exception {
        final CapturingFactory factory = new CapturingFactory();
        final ReconnectManager manager = new ReconnectManager(factory, new ReconnectPolicy(1,1,1),
                1000, true, null);
        final UdpRecordSenderTest.FakeChannel channel = new UdpRecordSenderTest.FakeChannel();
        final CollectionCycle cycle = new CollectionCycle(Arrays.asList("a", "b", "c"),
                new UdpRecordSender(channel, java.nio.charset.StandardCharsets.UTF_8));
        try {
            manager.start(Arrays.asList("a", "b", "c"), cycle);
            final OpcDataCallback old = factory.clients.get(0).callback;
            old.onCycleStarted();
            old.onData(sample("c", 99));
            manager.handleFailure(new Exception("down"));
            final OpcDataCallback fresh = factory.clients.get(1).callback;
            fresh.onCycleStarted();
            fresh.onData(sample("a", 1));
            complete(old, sample("a", 99), sample("c", 99));
            fresh.onData(sample("b", 2));
            fresh.onCycleComplete();
            cycle.publish();
            assertEquals(1, channel.sent.size());
            assertPacket(channel.sent.get(0), 1, 2);
        } finally { manager.close(); }
    }

    @Test public void healthyPointsKeepConnectionWatchdogAliveWhileOnePointIsMissing() throws Exception {
        final CapturingFactory factory = new CapturingFactory();
        final MutableCycleTime time = new MutableCycleTime();
        final ReconnectManager manager = new ReconnectManager(factory, new ReconnectPolicy(1, 1, 1),
                1000L, true, time,
                new ReconnectManager.Sleeper() { public void sleep(long millis) { } },
                new ReconnectManager.JitterSource() { public double nextDouble() { return 0.5; } }, null);
        final UdpRecordSenderTest.FakeChannel channel = new UdpRecordSenderTest.FakeChannel();
        final CollectionCycle cycle = new CollectionCycle(Arrays.asList("a", "b", "c"),
                new UdpRecordSender(channel, java.nio.charset.StandardCharsets.UTF_8), 1000L, time,
                new NoopSnapshotListener());
        try {
            manager.start(Arrays.asList("a", "b", "c"), cycle);
            for (int i = 1; i <= 10; i++) {
                time.now = i * 1000L;
                complete(factory.clients.get(0).callback, sample("a", i), sample("b", i));
                cycle.publish();
                manager.checkWatchdog();
                cycle.checkWatchdog();
            }
            assertEquals(1, factory.clients.size());
            assertEquals(ConnectionState.CONNECTED, manager.getState());
            assertEquals(10, channel.sent.size());
            assertEquals(1, cycle.getSnapshotStalls());
        } finally { manager.close(); }
    }

    private static OpcReadValue sample(String id, double number) {
        return new OpcReadValue(id, number, 192, Calendar.getInstance());
    }
    private static void complete(OpcDataCallback callback, OpcReadValue... values) {
        callback.onCycleStarted();
        for (OpcReadValue value : values) { callback.onData(value); }
        callback.onCycleComplete();
        if (callback instanceof CollectionCycle) { ((CollectionCycle) callback).publish(); }
    }
    private static void assertPacket(byte[] packet, double a, double b) {
        assertTrue(packet.length >= 60);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byte[] hash = new byte[16];
        buffer.get(hash);
        assertArrayEquals(com.opc2ecu.poc.EcuRecordCodec.md5("a".getBytes(java.nio.charset.StandardCharsets.UTF_8)), hash);
        assertEquals(a, buffer.getDouble(), 0.0);
        buffer.position(30);
        buffer.get(hash);
        assertArrayEquals(com.opc2ecu.poc.EcuRecordCodec.md5("b".getBytes(java.nio.charset.StandardCharsets.UTF_8)), hash);
        assertEquals(b, buffer.getDouble(), 0.0);
    }

    private OpcReadValue value(String id){return new OpcReadValue(id,1.0,192,Calendar.getInstance());}
    static class CapturingFactory implements OpcDaClientFactory{
        final List<Client>clients=new ArrayList<Client>();
        public OpcDaClient create(){Client c=new Client();clients.add(c);return c;}
    }
    static class Client implements OpcDaClient{
        List<String>items;boolean connected;OpcDataCallback callback;
        public void connect(){connected=true;}public void disconnect(){connected=false;}public boolean isConnected(){return connected;}
        public List<String>browseItems(){return Collections.emptyList();}public int exportCatalog(){return 0;}
        public OpcReadValue readItem(String id){return null;}public void bindSyncRead(OpcDataCallback c){}
        public void bindSyncRead(List<String>ids,OpcDataCallback c){items=new ArrayList<String>(ids);callback=c;}
        public void unbind(){}
    }
    static class MutableCycleTime implements CollectionCycle.TimeSource, ReconnectManager.TimeSource{long now;public long nowMillis(){return now;}}
    static class NoopSnapshotListener implements CollectionCycle.SnapshotListener{public void onSnapshot(long at){}}
}
