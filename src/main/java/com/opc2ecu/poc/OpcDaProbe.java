package com.opc2ecu.poc;

import com.opc2ecu.core.ExitCodes;
import com.opc2ecu.core.CollectionCycle;
import com.opc2ecu.core.DatagramChannel;
import com.opc2ecu.core.GapSummary;
import com.opc2ecu.core.HeartbeatSession;
import com.opc2ecu.core.OpcDaClient;
import com.opc2ecu.core.OpcDaClientFactory;
import com.opc2ecu.core.OpcDaException;
import com.opc2ecu.core.OpcDataCallback;
import com.opc2ecu.core.OpcReadValue;
import com.opc2ecu.core.ProbeConfig;
import com.opc2ecu.core.ProbeMode;
import com.opc2ecu.core.PointsConfig;
import com.opc2ecu.core.PointValidation;
import com.opc2ecu.core.ReconnectManager;
import com.opc2ecu.core.ReconnectPolicy;
import com.opc2ecu.core.UdpDatagramChannel;
import com.opc2ecu.core.UdpRecordSender;
import com.opc2ecu.utgard.UtgardOpcDaClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Linux-to-Windows OPC DA connectivity probe and resilient single-item reader. */
public final class OpcDaProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpcDaProbe.class);
    private static final String DEFAULT_CONFIG = "config/opc.properties";

    private OpcDaProbe() {
    }

    public static void main(final String[] args) {
        System.exit(run(args, System.getenv("OPC_PASSWORD")));
    }

    static int run(final String[] args, final String password) {
        return run(args, password, new ClientProvider() {
            @Override public OpcDaClient create(
                    final ProbeConfig config, final String outputPath) {
                return new UtgardOpcDaClient(config, outputPath);
            }
        });
    }

    static int run(
            final String[] args, final String password, final ClientProvider clientProvider) {
        OpcDaClient directClient = null;
        ReconnectManager reconnectManager = null;
        HeartbeatSession heartbeat = null;
        Thread shutdownHook = null;
        try {
            final Command command = Command.parse(args);
            if (command.mode == ProbeMode.SELF_TEST_PROTOCOL) {
                selfTestProtocol();
                return ExitCodes.SUCCESS;
            }

            if (command.mode == ProbeMode.COLLECT) {
                final PointsConfig points = loadPointsConfig(command.pointsPath);
                final ProbeConfig config = loadCollectionConfig(command, points, password);
                final DatagramChannel channel = new UdpDatagramChannel(
                        points.getUdpHost(), points.getUdpPort());
                heartbeat = new HeartbeatSession(channel, new HeartbeatSession.StateListener() {
                    @Override public void onOffline() {
                        System.out.println("[OFFLINE] OPC2ECU UDP peer is offline; business sending continues.");
                    }
                    @Override public void onRecovered() {
                        System.out.println("[RECOVERED] OPC2ECU UDP peer is online.");
                    }
                });
                reconnectManager = createReconnectManager(config, points.getPeriodMillis());
                runCollection(points, reconnectManager, heartbeat, channel);
                return ExitCodes.SUCCESS;
            }
            if (command.mode == ProbeMode.PRECHECK_POINTS) {
                final PointsConfig points = loadPointsConfig(command.pointsPath);
                final ProbeConfig config = loadCollectionConfig(command, points, password);
                directClient = clientProvider.create(config, null);
                connectWithOutput(directClient);
                return runPrecheck(points, directClient);
            }
            final ProbeConfig config = loadConfig(command.configPath, password, command.mode);
            printTarget(config, command.mode);
            if (command.mode == ProbeMode.CHECK_CONFIG) {
                System.out.println("[RESULT] Configuration validation succeeded; no network connection was attempted.");
                return ExitCodes.SUCCESS;
            }
            if (command.mode == ProbeMode.LIST_SERVERS) {
                UtgardOpcDaClient.listServers(config);
                return ExitCodes.SUCCESS;
            }

            if (command.mode == ProbeMode.READ_ITEM) {
                reconnectManager = createReconnectManager(config, config.getPeriodMillis());
                final AtomicReference<ReconnectManager> shutdownTarget =
                        new AtomicReference<ReconnectManager>(reconnectManager);
                shutdownHook = new Thread(new Runnable() {
                    @Override public void run() {
                        final ReconnectManager manager = shutdownTarget.get();
                        if (manager != null) {
                            manager.close();
                        }
                    }
                }, "opcda-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                runSingleItemRead(config, reconnectManager);
                shutdownTarget.set(null);
                return ExitCodes.SUCCESS;
            }

            directClient = clientProvider.create(config, command.outputPath);
            connectWithOutput(directClient);
            if (command.mode == ProbeMode.LIST_ITEMS) {
                printItems(directClient.browseItems());
                return ExitCodes.SUCCESS;
            }
            if (command.mode == ProbeMode.EXPORT_CATALOG) {
                final int count = directClient.exportCatalog();
                System.out.printf("[EXPORT] Wrote server catalog with %d item(s) to %s%n",
                        count, Paths.get(command.outputPath).toAbsolutePath().normalize());
                System.out.println("[RESULT] OPC DA server catalog export succeeded.");
                return ExitCodes.SUCCESS;
            }
            throw new IllegalStateException("Unsupported command mode: " + command.mode);
        } catch (final Throwable e) {
            final int exitCode = ExitCodes.forException(e);
            System.err.printf("[ERROR] %s%n", actionableMessage(e, exitCode));
            LOGGER.debug("OPC DA command failed with exit code " + exitCode, e);
            if (exitCode == ExitCodes.CONFIGURATION_ERROR) {
                printUsage();
            }
            return exitCode;
        } finally {
            if (reconnectManager != null) {
                reconnectManager.close();
            }
            if (heartbeat != null) {
                heartbeat.close();
            }
            if (directClient != null) {
                directClient.disconnect();
            }
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (final IllegalStateException ignored) {
                    LOGGER.debug("JVM shutdown is already in progress");
                }
            }
            System.out.println("[CLOSE] OPC resources released.");
        }
    }

    private static ReconnectManager createReconnectManager(
            final ProbeConfig config, final int periodMillis) {
        final OpcDaClientFactory factory = new OpcDaClientFactory() {
            @Override public OpcDaClient create() {
                return new UtgardOpcDaClient(config, null, periodMillis);
            }
        };
        final ReconnectPolicy policy = new ReconnectPolicy(
                config.getReconnectInitialDelayMillis(),
                config.getReconnectMaxDelayMillis(),
                config.getReconnectMaxAttempts());
        return new ReconnectManager(
                factory,
                policy,
                periodMillis,
                config.isReconnectEnabled(),
                new ReconnectManager.GapListener() {
                    @Override public void onGapRecovered(final GapSummary gap) {
                        System.out.printf(
                                "[GAP] recovered start=%d end=%d missedSamples=%d%n",
                                gap.getStartMillis(), gap.getEndMillis(), gap.getMissedSamples());
                    }
                });
    }

    private static void runSingleItemRead(
            final ProbeConfig config,
            final ReconnectManager manager) throws Exception {
        final CountDownLatch samples = new CountDownLatch(config.getSampleCount());
        final AtomicInteger sequence = new AtomicInteger();
        final Instant connectStart = Instant.now();
        System.out.println("[CONNECT] Connecting to the OPC DA server...");
        manager.start(Collections.singletonList(config.getItemId()), new OpcDataCallback() {
            @Override
            public void onData(final OpcReadValue value) {
                final int number = sequence.incrementAndGet();
                if (number <= config.getSampleCount()) {
                    System.out.printf(
                            "[READ %d/%d] item=%s value=%s quality=%s timestamp=%s%n",
                            number,
                            config.getSampleCount(),
                            value.getItemId(),
                            value.getValue(),
                            value.getQuality(),
                            value.getTimestamp());
                    samples.countDown();
                }
            }
        });
        System.out.printf("[CONNECT] Connected in %d ms%n",
                Duration.between(connectStart, Instant.now()).toMillis());
        System.out.printf("[READ] Polling every %d ms; waiting for %d samples...%n",
                config.getPeriodMillis(), config.getSampleCount());

        final long waitMillis = Math.max(
                TimeUnit.SECONDS.toMillis(config.getReadTimeoutSeconds()),
                ((long) config.getPeriodMillis() * config.getSampleCount()) + 10000L);
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (samples.getCount() > 0L) {
            final long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new OpcDaException(
                        OpcDaException.Kind.TIMEOUT,
                        "Timed out before receiving " + config.getSampleCount() + " samples; received "
                                + sequence.get() + ". Check item activity and socketTimeoutMillis.");
            }
            final long sliceMillis = Math.max(1L, Math.min(
                    250L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            try {
                samples.await(sliceMillis, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpcDaException(
                        OpcDaException.Kind.TIMEOUT,
                        "Sample wait was interrupted; stop the service cleanly and restart it.", e);
            }
            if (samples.getCount() > 0L) {
                manager.checkWatchdog();
            }
        }
        System.out.println("[RESULT] OPC DA single-item read verification succeeded.");
    }

    private static void runCollection(
            final PointsConfig points, final ReconnectManager manager,
            final HeartbeatSession heartbeat, final DatagramChannel channel) throws Exception {
        final CountDownLatch stopped = new CountDownLatch(1);
        final AtomicReference<ReconnectManager> managerTarget =
                new AtomicReference<ReconnectManager>(manager);
        final AtomicReference<HeartbeatSession> heartbeatTarget =
                new AtomicReference<HeartbeatSession>(heartbeat);
        final Thread hook = new Thread(new Runnable() {
            @Override public void run() {
                final ReconnectManager currentManager = managerTarget.getAndSet(null);
                if (currentManager != null) { currentManager.close(); }
                final HeartbeatSession currentHeartbeat = heartbeatTarget.getAndSet(null);
                if (currentHeartbeat != null) { currentHeartbeat.close(); }
                stopped.countDown();
            }
        }, "opc2ecu-collect-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            final UdpRecordSender sender = new UdpRecordSender(channel, points.getMd5Charset());
            final CollectionCycle cycle = new CollectionCycle(
                    points.getItems(), sender, points.getPeriodMillis(),
                    new CollectionCycle.TimeSource() {
                        @Override public long nowMillis() { return System.currentTimeMillis(); }
                    }, new CollectionCycle.SnapshotListener() {
                        @Override public void onSnapshot(final long snapshotAtMillis) { }
                    });
            manager.start(points.getItems(), cycle);
            heartbeat.start();
            System.out.printf("[START] Collecting %d item(s) every %d ms to %s:%d.%n",
                    points.getItems().size(), points.getPeriodMillis(),
                    points.getUdpHost(), points.getUdpPort());
            while (!stopped.await(250L, TimeUnit.MILLISECONDS)) {
                manager.checkWatchdog();
                cycle.checkWatchdog();
            }
        } finally {
            managerTarget.set(null);
            heartbeatTarget.set(null);
            try { Runtime.getRuntime().removeShutdownHook(hook); }
            catch (final IllegalStateException ignored) { }
        }
    }

    private static int runPrecheck(
            final PointsConfig points, final OpcDaClient client) throws Exception {
        final List<PointValidation> results = client.validateItems(points.getItems());
        int passed = 0;
        for (int i = 0; i < results.size(); i++) {
            final PointValidation result = results.get(i);
            final String reason;
            if (!result.isReadable()) {
                reason = "not-readable";
            } else if (!result.isNumeric()) {
                reason = "non-numeric";
            } else {
                reason = "ok";
                passed++;
            }
            System.out.printf("[PRECHECK %d/%d] item=%s result=%s reason=%s%n",
                    i + 1, results.size(), result.getItemId(),
                    "ok".equals(reason) ? "PASS" : "FAIL", reason);
        }
        final int failed = results.size() - passed;
        System.out.printf("[PRECHECK] summary passed=%d failed=%d%n", passed, failed);
        return failed == 0 ? ExitCodes.SUCCESS : ExitCodes.READ_ERROR;
    }

    private static void connectWithOutput(final OpcDaClient client) throws Exception {
        final Instant connectStart = Instant.now();
        System.out.println("[CONNECT] Connecting to the OPC DA server...");
        client.connect();
        System.out.printf("[CONNECT] Connected in %d ms%n",
                Duration.between(connectStart, Instant.now()).toMillis());
    }

    private static ProbeConfig loadConfig(
            final String path, final String password, final ProbeMode mode) {
        try {
            return ProbeConfig.load(path, password, mode);
        } catch (final IOException e) {
            throw new IllegalArgumentException(
                    "Unable to read configuration file " + path
                            + ". Check that the path exists and is readable.", e);
        }
    }

    private static PointsConfig loadPointsConfig(final String path) {
        try {
            return PointsConfig.load(path);
        } catch (final IOException e) {
            throw new IllegalArgumentException(
                    "Unable to read points configuration file " + path, e);
        }
    }

    private static ProbeConfig loadCollectionConfig(
            final Command command, final PointsConfig points, final String legacyPassword) {
        if (points.hasServer()) {
            return ProbeConfig.fromPointsConfig(points);
        }
        System.err.println(
                "[DEPRECATED] points.json has no server section; falling back to opc.properties. "
                        + "Move connection settings into points.json v2.");
        return loadConfig(command.configPath, legacyPassword, command.mode);
    }

    private static void printItems(final List<String> itemIds) {
        System.out.printf("[ITEMS] Found %d item(s).%n", itemIds.size());
        for (int i = 0; i < itemIds.size(); i++) {
            System.out.printf("[ITEM %d/%d] %s%n", i + 1, itemIds.size(), itemIds.get(i));
        }
        System.out.println("[RESULT] Remote OPC DA item enumeration succeeded.");
    }

    private static String actionableMessage(final Throwable error, final int exitCode) {
        final String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        if (exitCode == ExitCodes.CONFIGURATION_ERROR && message.indexOf("OPC_PASSWORD") < 0) {
            return message + ". Check the properties file and OPC_PASSWORD environment variable.";
        }
        if (exitCode == ExitCodes.CONNECTION_ERROR && message.indexOf("TCP 135") < 0) {
            return message + ". Confirm Windows firewall TCP 135/RPC dynamic ports and DCOM permissions.";
        }
        if (exitCode == ExitCodes.READ_ERROR && message.indexOf("item") < 0) {
            return message + ". Confirm the configured item ID exists and is readable.";
        }
        return message;
    }

    private static void selfTestProtocol() {
        final byte[] record = EcuRecordCodec.encode(
                "Server.Group.Item", StandardCharsets.US_ASCII, 1.5d, 0x01020304L, 192);
        final String actual = EcuRecordCodec.toHex(record);
        final String expected = "199065ab24ae156c84bc8b33e6acc683"
                + "000000000000f83f" + "04030201" + "c000";
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Protocol vector mismatch: expected=" + expected + " actual=" + actual);
        }
        System.out.println("[PROTOCOL] Record size=30 byteOrder=little-endian maxRecordsPerDatagram=48");
        System.out.println("[PROTOCOL] MD5 input=Server.Group.Item charset=US-ASCII");
        System.out.println("[PROTOCOL] vector=" + actual);
        System.out.println("[RESULT] OPC2ECU protocol codec self-test succeeded.");
    }

    private static void printTarget(final ProbeConfig config, final ProbeMode mode) {
        if (mode == ProbeMode.LIST_SERVERS) {
            System.out.printf(
                    "[CONFIG] mode=list-server host=%s domain=<redacted> user=<redacted> ntlmV2=%s sessionSecurity=true socketTimeoutMillis=%d%n",
                    config.getHost(), config.isUseNtlmV2(), config.getSocketTimeoutMillis());
        } else {
            System.out.printf(
                    "[CONFIG] host=%s domain=<redacted> user=<redacted> progId=%s clsid=%s itemId=%s%n",
                    config.getHost(), config.getProgId(), config.getClsid(), config.getItemId());
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java -jar opcda-probe.jar [config-file]");
        System.err.println("  java -jar opcda-probe.jar --check-config [config-file]");
        System.err.println("  java -jar opcda-probe.jar --list-server [config-file]");
        System.err.println("  java -jar opcda-probe.jar --list-items [config-file]");
        System.err.println("  java -jar opcda-probe.jar --export-catalog <output.json> [config-file]");
        System.err.println("  java -jar opcda-probe.jar --collect <points.json> [opc.properties]");
        System.err.println("  java -jar opcda-probe.jar --precheck-points <points.json> [opc.properties]");
        System.err.println("  java -jar opcda-probe.jar --self-test-protocol");
    }

    private static final class Command {
        private final ProbeMode mode;
        private final String configPath;
        private final String outputPath;
        private final String pointsPath;

        private Command(final ProbeMode mode, final String configPath) {
            this(mode, configPath, null);
        }

        private Command(final ProbeMode mode, final String configPath, final String outputPath) {
            this(mode, configPath, outputPath, null);
        }

        private Command(
                final ProbeMode mode, final String configPath,
                final String outputPath, final String pointsPath) {
            this.mode = mode;
            this.configPath = configPath;
            this.outputPath = outputPath;
            this.pointsPath = pointsPath;
        }

        private static Command parse(final String[] args) {
            if (args.length == 0) {
                return new Command(ProbeMode.READ_ITEM, DEFAULT_CONFIG);
            }
            if (args.length == 1 && "--self-test-protocol".equals(args[0])) {
                return new Command(ProbeMode.SELF_TEST_PROTOCOL, null);
            }
            if (args.length == 1 && !args[0].startsWith("--")) {
                return new Command(ProbeMode.READ_ITEM, args[0]);
            }
            if ("--check-config".equals(args[0])) {
                requireAtMostOnePath(args);
                return new Command(ProbeMode.CHECK_CONFIG, args.length == 2 ? args[1] : DEFAULT_CONFIG);
            }
            if ("--list-server".equals(args[0]) || "--list-servers".equals(args[0])) {
                requireAtMostOnePath(args);
                return new Command(ProbeMode.LIST_SERVERS, args.length == 2 ? args[1] : DEFAULT_CONFIG);
            }
            if ("--list-items".equals(args[0])) {
                requireAtMostOnePath(args);
                return new Command(ProbeMode.LIST_ITEMS, args.length == 2 ? args[1] : DEFAULT_CONFIG);
            }
            if ("--export-catalog".equals(args[0])) {
                if (args.length < 2 || args.length > 3) {
                    throw new IllegalArgumentException(
                            "--export-catalog requires an output path and optional config file path");
                }
                return new Command(ProbeMode.EXPORT_CATALOG,
                        args.length == 3 ? args[2] : DEFAULT_CONFIG, args[1]);
            }
            if ("--collect".equals(args[0])) {
                if (args.length < 2 || args.length > 3) {
                    throw new IllegalArgumentException(
                            "--collect requires points.json and optional opc.properties paths");
                }
                return new Command(ProbeMode.COLLECT,
                        args.length == 3 ? args[2] : DEFAULT_CONFIG, null, args[1]);
            }
            if ("--precheck-points".equals(args[0])) {
                if (args.length < 2 || args.length > 3) {
                    throw new IllegalArgumentException(
                            "--precheck-points requires points.json and optional opc.properties paths");
                }
                return new Command(ProbeMode.PRECHECK_POINTS,
                        args.length == 3 ? args[2] : DEFAULT_CONFIG, null, args[1]);
            }
            throw new IllegalArgumentException("Unknown command or too many arguments");
        }

        private static void requireAtMostOnePath(final String[] args) {
            if (args.length > 2) {
                throw new IllegalArgumentException("Only one config file path may be supplied");
            }
        }
    }

    interface ClientProvider {
        OpcDaClient create(ProbeConfig config, String outputPath);
    }
}
