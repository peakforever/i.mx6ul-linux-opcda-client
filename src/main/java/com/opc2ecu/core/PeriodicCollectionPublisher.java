package com.opc2ecu.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A single bounded publisher independent of all OPC connection and read operations. */
public final class PeriodicCollectionPublisher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PeriodicCollectionPublisher.class);
    private final CollectionCycle cycle;
    private final long periodMillis;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;
    private boolean started;

    public PeriodicCollectionPublisher(final CollectionCycle cycle, final long periodMillis) {
        if (cycle == null || periodMillis <= 0L) {
            throw new IllegalArgumentException("publisher dependencies must be valid");
        }
        this.cycle = cycle;
        this.periodMillis = periodMillis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(final Runnable task) {
                return new Thread(task, "opc2ecu-point-publisher");
            }
        });
    }

    public synchronized void start() {
        if (started || scheduler.isShutdown()) {
            throw new IllegalStateException("publisher already started or closed");
        }
        started = true;
        running = true;
        // Synchronous first output ensures initialization is sent before the first RPC starts.
        publish();
        if (!running) { return; }
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { publish(); }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void publish() {
        if (!running) { return; }
        try { cycle.publish(); }
        catch (final RuntimeException failure) {
            LOGGER.error("Periodic point publication failed; next period will retry", failure);
        }
    }

    @Override public synchronized void close() {
        running = false;
        scheduler.shutdownNow();
        // This lock also waits for an in-flight publication before the channel is closed.
        started = true;
    }
}
