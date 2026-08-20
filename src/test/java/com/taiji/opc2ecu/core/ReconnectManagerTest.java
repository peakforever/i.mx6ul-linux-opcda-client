package com.taiji.opc2ecu.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import org.junit.Test;

public class ReconnectManagerTest {
    @Test
    public void startsAndBindsClient() throws Exception {
        final FakeClient client = new FakeClient(false, false);
        final Fixture fixture = fixture(3, client);
        fixture.manager.start(noopCallback());
        assertEquals(ConnectionState.CONNECTED, fixture.manager.getState());
        assertEquals(1, client.connectCount);
        assertEquals(1, client.bindCount);
    }

    @Test
    public void initialFailureIsConnectionError() {
        final FakeClient client = new FakeClient(true, false);
        final Fixture fixture = fixture(3, client);
        try {
            fixture.manager.start(noopCallback());
            fail("Expected connection failure");
        } catch (final OpcDaException e) {
            assertEquals(OpcDaException.Kind.CONNECTION, e.getKind());
            assertEquals(ConnectionState.STOPPED, fixture.manager.getState());
            assertEquals(1, client.disconnectCount);
        }
    }

    @Test
    public void initialBindFailureIsReadError() {
        final FakeClient client = new FakeClient(false, true);
        final Fixture fixture = fixture(3, client);
        try {
            fixture.manager.start(noopCallback());
            fail("Expected bind failure");
        } catch (final OpcDaException e) {
            assertEquals(OpcDaException.Kind.READ, e.getKind());
            assertEquals(ConnectionState.STOPPED, fixture.manager.getState());
        }
    }

    @Test
    public void watchdogDisconnectsAndRecoversWithFreshClient() throws Exception {
        final FakeClient first = new FakeClient(false, false);
        final FakeClient second = new FakeClient(false, false);
        final Fixture fixture = fixture(3, first, second);
        fixture.manager.start(noopCallback());
        fixture.time.now = 4000L;
        fixture.manager.checkWatchdog();
        assertEquals(ConnectionState.CONNECTED, fixture.manager.getState());
        assertNotSame(first, second);
        assertEquals(1, first.unbindCount);
        assertEquals(1, first.disconnectCount);
        assertEquals(1, second.bindCount);
        assertEquals(Collections.singletonList(1000L), fixture.time.sleeps);
    }

    @Test
    public void persistentFailureStopsAtConfiguredAttemptLimit() throws Exception {
        final FakeClient initial = new FakeClient(false, false);
        final FakeClient failedOne = new FakeClient(true, false);
        final FakeClient failedTwo = new FakeClient(true, false);
        final Fixture fixture = fixture(2, initial, failedOne, failedTwo);
        fixture.manager.start(noopCallback());
        try {
            fixture.manager.handleFailure(new Exception("link down"));
            fail("Expected reconnect exhaustion");
        } catch (final OpcDaException e) {
            assertEquals(OpcDaException.Kind.READ, e.getKind());
            assertEquals(ConnectionState.STOPPED, fixture.manager.getState());
            assertEquals(2, fixture.time.sleeps.size());
            assertEquals(Long.valueOf(1000L), fixture.time.sleeps.get(0));
            assertEquals(Long.valueOf(2000L), fixture.time.sleeps.get(1));
        }
    }

    @Test
    public void disabledReconnectFailsWithoutCreatingReplacement() throws Exception {
        final FakeClient initial = new FakeClient(false, false);
        final MutableTime time = new MutableTime();
        final QueueFactory factory = new QueueFactory(initial);
        final ReconnectManager manager = new ReconnectManager(
                factory, new ReconnectPolicy(1000L, 30000L, 3), 1000L, false,
                time, time, fixedJitter(), null);
        manager.start(noopCallback());
        try {
            manager.handleFailure(new Exception("down"));
            fail("Expected read failure");
        } catch (final OpcDaException e) {
            assertEquals(OpcDaException.Kind.READ, e.getKind());
            assertEquals(1, factory.createCount);
        }
    }

    @Test
    public void recoveryRecordsGapAndMissedSamples() throws Exception {
        final FakeClient first = new FakeClient(false, false);
        final FakeClient second = new FakeClient(false, false);
        final Fixture fixture = fixture(3, first, second);
        fixture.manager.start(noopCallback());
        fixture.time.now = 2500L;
        fixture.manager.handleFailure(new Exception("down"));
        final GapSummary gap = fixture.manager.getLastGap();
        assertNotNull(gap);
        assertEquals(4L, gap.getMissedSamples());
        assertEquals(4L, fixture.manager.getMissedSamples());
        assertEquals(0L, gap.getStartMillis());
        assertEquals(3500L, gap.getEndMillis());
    }

    @Test
    public void readFailureReconnectsAndRetriesOnFreshClient() throws Exception {
        final FakeClient first = new FakeClient(false, false, true);
        final FakeClient second = new FakeClient(false, false, false);
        final Fixture fixture = fixture(3, first, second);
        fixture.manager.start(noopCallback());
        final OpcReadValue value = fixture.manager.readItem("Group.Item");
        assertEquals("Group.Item", value.getItemId());
        assertEquals(1, first.readCount);
        assertEquals(1, second.readCount);
        assertEquals(1, second.bindCount);
    }

    @Test
    public void closeUnbindsAndStopsWithoutReconnect() throws Exception {
        final FakeClient client = new FakeClient(false, false);
        final Fixture fixture = fixture(3, client);
        fixture.manager.start(noopCallback());
        fixture.manager.close();
        assertEquals(ConnectionState.STOPPED, fixture.manager.getState());
        assertTrue(client.disconnectCount > 0);
        fixture.manager.handleFailure(new Exception("ignored after stop"));
        assertEquals(1, fixture.factory.createCount);
    }

    private static Fixture fixture(final int attempts, final FakeClient... clients) {
        final MutableTime time = new MutableTime();
        final QueueFactory factory = new QueueFactory(clients);
        final ReconnectManager manager = new ReconnectManager(
                factory,
                new ReconnectPolicy(1000L, 30000L, attempts),
                1000L,
                true,
                time,
                time,
                fixedJitter(),
                null);
        return new Fixture(manager, factory, time);
    }

    private static ReconnectManager.JitterSource fixedJitter() {
        return new ReconnectManager.JitterSource() {
            @Override public double nextDouble() { return 0.5d; }
        };
    }

    private static OpcDataCallback noopCallback() {
        return new OpcDataCallback() {
            @Override public void onData(final OpcReadValue value) { }
        };
    }

    private static final class Fixture {
        private final ReconnectManager manager;
        private final QueueFactory factory;
        private final MutableTime time;

        private Fixture(
                final ReconnectManager manager, final QueueFactory factory, final MutableTime time) {
            this.manager = manager;
            this.factory = factory;
            this.time = time;
        }
    }

    private static final class MutableTime
            implements ReconnectManager.TimeSource, ReconnectManager.Sleeper {
        private long now;
        private final List<Long> sleeps = new ArrayList<Long>();

        @Override public long nowMillis() { return now; }
        @Override public void sleep(final long millis) {
            sleeps.add(millis);
            now += millis;
        }
    }

    private static final class QueueFactory implements OpcDaClientFactory {
        private final Deque<FakeClient> clients = new ArrayDeque<FakeClient>();
        private int createCount;

        private QueueFactory(final FakeClient... clients) {
            Collections.addAll(this.clients, clients);
        }

        @Override public OpcDaClient create() {
            createCount++;
            if (clients.isEmpty()) {
                throw new AssertionError("No fake client prepared for create call " + createCount);
            }
            return clients.removeFirst();
        }
    }

    private static final class FakeClient implements OpcDaClient {
        private final boolean failConnect;
        private final boolean failBind;
        private boolean connected;
        private int connectCount;
        private int disconnectCount;
        private int bindCount;
        private int unbindCount;
        private int readCount;
        private final boolean failRead;

        private FakeClient(final boolean failConnect, final boolean failBind) {
            this(failConnect, failBind, false);
        }

        private FakeClient(
                final boolean failConnect, final boolean failBind, final boolean failRead) {
            this.failConnect = failConnect;
            this.failBind = failBind;
            this.failRead = failRead;
        }

        @Override public void connect() throws Exception {
            connectCount++;
            if (failConnect) { throw new Exception("connect failed"); }
            connected = true;
        }
        @Override public void disconnect() { disconnectCount++; connected = false; }
        @Override public boolean isConnected() { return connected; }
        @Override public List<String> browseItems() { return Collections.emptyList(); }
        @Override public int exportCatalog() { return 0; }
        @Override public OpcReadValue readItem(final String itemId) throws Exception {
            readCount++;
            if (failRead) { throw new Exception("read failed"); }
            return new OpcReadValue(itemId, "value", 192, null);
        }
        @Override public void bindSyncRead(final OpcDataCallback callback) throws Exception {
            bindCount++;
            if (failBind) { throw new Exception("bind failed"); }
        }
        @Override public void unbind() { unbindCount++; }
    }
}
