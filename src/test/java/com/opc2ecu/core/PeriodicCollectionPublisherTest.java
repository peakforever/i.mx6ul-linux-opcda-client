package com.opc2ecu.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class PeriodicCollectionPublisherTest {
    @Test public void blockedInitialConnectionStillPublishesInitializedPoints() throws Exception {
        exerciseBlockedRpc(false, false);
    }

    @Test public void initialConnectionFailureRetriesWithoutStoppingPublication() throws Exception {
        exerciseBlockedRpc(true, false);
    }

    @Test public void reconnectPublishesHeldBadWithoutWaitingForRpc() throws Exception {
        exerciseBlockedRpc(false, true);
    }

    private void exerciseBlockedRpc(final boolean failFirst, final boolean recovering) throws Exception {
        final Channel channel = new Channel();
        final CollectionCycle cycle = new CollectionCycle(Arrays.asList("a", "b"),
                new UdpRecordSender(channel, StandardCharsets.UTF_8));
        final PeriodicCollectionPublisher publisher = new PeriodicCollectionPublisher(cycle, 10);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger created = new AtomicInteger();
        final AtomicReference<OpcDataCallback> callback = new AtomicReference<OpcDataCallback>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final ReconnectManager manager = new ReconnectManager(new OpcDaClientFactory() {
            public OpcDaClient create() {
                final int attempt = created.incrementAndGet();
                return new Client() {
                    public void connect() throws OpcDaException {
                        if (failFirst && attempt == 1) {
                            throw new OpcDaException(OpcDaException.Kind.CONNECTION, "initial offline",
                                    new java.net.NoRouteToHostException());
                        }
                        if (!recovering || attempt > 1) {
                            entered.countDown();
                            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                            catch (InterruptedException e) { throw new AssertionError(e); }
                        }
                    }
                    public void bindSyncRead(List<String> ids, OpcDataCallback target) { callback.set(target); }
                };
            }
        }, new ReconnectPolicy(1, 1, 2), 1000, true, null);
        final Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    if (recovering) { manager.handleFailure(new java.net.NoRouteToHostException("offline")); }
                    else { manager.startWithRecovery(Arrays.asList("a", "b"), cycle); }
                } catch (Throwable e) { failure.set(e); }
            }
        });
        try {
            publisher.start();
            if (recovering) {
                manager.start(Arrays.asList("a", "b"), cycle);
                callback.get().onCycleStarted();
                callback.get().onData(HeldPointValuesTest.sample("a", 12, 192, 123));
                callback.get().onCycleComplete();
            }
            worker.start();
            assertTrue("RPC reached", entered.await(2, TimeUnit.SECONDS));
            channel.sent.clear();
            // These are actual scheduled sends while the connection worker remains blocked.
            for (int i = 0; i < 3; i++) {
                final byte[] bytes = channel.sent.poll(2, TimeUnit.SECONDS);
                assertNotNull("periodic business packet during RPC", bytes);
                assertEquals(60, bytes.length);
                final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(recovering ? 12.0 : 0.0, buffer.getDouble(16), 0.0);
                assertEquals(recovering ? 123 : 0, buffer.getInt(24));
                assertEquals(recovering ? 0x1114 : failFirst ? 0x1120 : 0x0120, buffer.getShort(28) & 0xffff);
                assertEquals(recovering || failFirst ? 0x1120 : 0x0120, buffer.getShort(58) & 0xffff);
                assertTrue(worker.isAlive());
            }
            publisher.close(); channel.sent.clear();
            assertNull("stop publication even while RPC is blocked",
                    channel.sent.poll(80, TimeUnit.MILLISECONDS));
            assertTrue(worker.isAlive());
            release.countDown(); worker.join(2000);
            assertFalse(worker.isAlive()); assertNull(failure.get());
            assertEquals(ConnectionState.CONNECTED, manager.getState());
            assertEquals(failFirst || recovering ? 2 : 1, created.get());
            publisher.close(); channel.sent.clear();
            assertNull("no packets after publisher close", channel.sent.poll(80, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown(); worker.join(2000); publisher.close(); manager.close();
        }
    }

    @Test public void publicationExceptionDoesNotCancelLaterTicks() throws Exception {
        final Channel channel = new Channel();
        final AtomicInteger attempts = new AtomicInteger();
        final CollectionCycle cycle = new CollectionCycle(Collections.singletonList("a"),
                new UdpRecordSender(channel, StandardCharsets.UTF_8), 1000,
                new CollectionCycle.TimeSource() { public long nowMillis() { return 0; } },
                new CollectionCycle.SnapshotListener() {
                    public void onSnapshot(long at) {
                        if (attempts.incrementAndGet() == 1) { throw new IllegalStateException("test listener"); }
                    }
                });
        final PeriodicCollectionPublisher publisher = new PeriodicCollectionPublisher(cycle, 10);
        try {
            publisher.start();
            for (int i = 0; i < 3; i++) { assertNotNull(channel.sent.poll(2, TimeUnit.SECONDS)); }
        } finally { publisher.close(); }
    }

    @Test public void disabledInitialRecoveryStillPropagatesFailure() throws Exception {
        final ReconnectManager manager = new ReconnectManager(new OpcDaClientFactory() {
            public OpcDaClient create() {
                return new Client() {
                    public void connect() throws OpcDaException {
                        throw new OpcDaException(OpcDaException.Kind.CONNECTION, "offline");
                    }
                };
            }
        }, new ReconnectPolicy(1, 1, 1), 1000, false, null);
        try {
            manager.startWithRecovery(Collections.singletonList("a"), new OpcDataCallback() {
                public void onData(OpcReadValue value) { }
            });
            fail("expected initial failure");
        } catch (OpcDaException expected) {
            assertEquals(ConnectionState.STOPPED, manager.getState());
        } finally { manager.close(); }
    }

    @Test public void initialRecoveryGapDoesNotStartAtEpoch() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        final long[] now = {9000L};
        final ReconnectManager manager = new ReconnectManager(new OpcDaClientFactory() {
            public OpcDaClient create() {
                final int attempt = attempts.incrementAndGet();
                return new Client() {
                    public void connect() throws OpcDaException {
                        if (attempt == 1) {
                            throw new OpcDaException(OpcDaException.Kind.CONNECTION, "offline");
                        }
                    }
                };
            }
        }, new ReconnectPolicy(1, 1, 1), 1000, true,
                new ReconnectManager.TimeSource() { public long nowMillis() { return now[0]; } },
                new ReconnectManager.Sleeper() { public void sleep(long millis) { now[0] = 9500; } },
                new ReconnectManager.JitterSource() { public double nextDouble() { return 0.5; } }, null);
        try {
            manager.startWithRecovery(Collections.singletonList("a"), new OpcDataCallback() {
                public void onData(OpcReadValue value) { }
            });
            assertNotNull(manager.getLastGap());
            assertEquals(1, manager.getMissedSamples());
        } finally { manager.close(); }
    }

    private static class Channel implements DatagramChannel {
        final LinkedBlockingQueue<byte[]> sent = new LinkedBlockingQueue<byte[]>();
        public void send(byte[] payload) { sent.add(payload.clone()); }
        public byte[] receive() { return null; }
        public void setReceiveTimeout(int millis) { }
        public void close() { }
    }

    private static class Client implements OpcDaClient {
        public void connect() throws OpcDaException { }
        public void disconnect() { }
        public boolean isConnected() { return true; }
        public List<String> browseItems() { return Collections.emptyList(); }
        public int exportCatalog() { return 0; }
        public OpcReadValue readItem(String id) { return null; }
        public void bindSyncRead(OpcDataCallback callback) { }
        public void bindSyncRead(List<String> ids, OpcDataCallback callback) { }
        public void unbind() { }
    }
}
