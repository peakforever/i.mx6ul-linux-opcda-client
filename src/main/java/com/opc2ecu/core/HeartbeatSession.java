package com.opc2ecu.core;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** OPC2ECU heartbeat state machine sharing the business DatagramChannel. */
public final class HeartbeatSession implements AutoCloseable {
    public static final int PERIOD_MILLIS = 1000;
    public static final int RESPONSE_TIMEOUT_MILLIS = 500;
    public static final int OFFLINE_THRESHOLD = 3;
    private static final long UINT32_MASK = 0xffffffffL;
    private static final Logger LOGGER = LoggerFactory.getLogger(HeartbeatSession.class);

    public interface StateListener {
        void onOffline();
        void onRecovered();
    }

    private final DatagramChannel channel;
    private final StateListener listener;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;
    private volatile boolean online = true;
    private long nextSessionId;
    private long awaitingSessionId = -1L;
    private int consecutiveFailures;
    private ScheduledFuture<?> timeoutFuture;

    public HeartbeatSession(final DatagramChannel channel, final StateListener listener) {
        this(channel, listener, Executors.newScheduledThreadPool(2));
    }

    HeartbeatSession(
            final DatagramChannel channel, final StateListener listener,
            final ScheduledExecutorService scheduler) {
        if (channel == null || scheduler == null) {
            throw new IllegalArgumentException("heartbeat dependencies must not be null");
        }
        this.channel = channel;
        this.listener = listener == null ? new StateListener() {
            @Override public void onOffline() { }
            @Override public void onRecovered() { }
        } : listener;
        this.scheduler = scheduler;
    }

    public synchronized void start() throws IOException {
        if (running) { return; }
        running = true;
        channel.setReceiveTimeout(RESPONSE_TIMEOUT_MILLIS);
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override public void run() { sendHeartbeat(); }
        }, 0L, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        scheduler.execute(new Runnable() {
            @Override public void run() { receiveLoop(); }
        });
    }

    /** Sends one request; public to permit deterministic state-machine verification. */
    public synchronized long sendHeartbeat() {
        final long sessionId = nextSessionId;
        nextSessionId = (nextSessionId + 1L) & UINT32_MASK;
        final byte[] request = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) sessionId).array();
        awaitingSessionId = sessionId;
        if (timeoutFuture != null) { timeoutFuture.cancel(false); }
        try {
            channel.send(request);
            timeoutFuture = scheduler.schedule(new Runnable() {
                @Override public void run() { onTimeout(sessionId); }
            }, RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final IOException e) {
            LOGGER.warn("Heartbeat request {} failed to send", sessionId, e);
            onTimeout(sessionId);
        }
        return sessionId;
    }

    public synchronized boolean acceptResponse(final byte[] payload) {
        if (payload == null || payload.length != 4) { return false; }
        final long sessionId = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt()
                & UINT32_MASK;
        if (sessionId != awaitingSessionId) { return false; }
        awaitingSessionId = -1L;
        if (timeoutFuture != null) { timeoutFuture.cancel(false); }
        consecutiveFailures = 0;
        if (!online) {
            online = true;
            LOGGER.info("UDP heartbeat peer recovered");
            listener.onRecovered();
        }
        return true;
    }

    public synchronized void onTimeout(final long sessionId) {
        if (sessionId != awaitingSessionId) { return; }
        awaitingSessionId = -1L;
        consecutiveFailures++;
        if (online && consecutiveFailures >= OFFLINE_THRESHOLD) {
            online = false;
            LOGGER.warn("UDP heartbeat peer offline after {} consecutive failures", consecutiveFailures);
            listener.onOffline();
        }
    }

    private void receiveLoop() {
        while (running) {
            try {
                acceptResponse(channel.receive());
            } catch (final SocketTimeoutException ignored) {
                // The scheduled timeout owns state transitions.
            } catch (final IOException e) {
                if (running) { LOGGER.warn("UDP heartbeat receive failed", e); }
            }
        }
    }

    public synchronized void setNextSessionId(final long value) {
        if (value < 0L || value > UINT32_MASK) {
            throw new IllegalArgumentException("session ID must fit uint32");
        }
        nextSessionId = value;
    }

    public boolean isOnline() { return online; }
    public synchronized int getConsecutiveFailures() { return consecutiveFailures; }
    public synchronized long getAwaitingSessionId() { return awaitingSessionId; }

    @Override public synchronized void close() {
        running = false;
        if (timeoutFuture != null) { timeoutFuture.cancel(false); }
        scheduler.shutdownNow();
        channel.close();
    }
}
