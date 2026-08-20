package com.taiji.opc2ecu.poc;

import com.taiji.opc2ecu.core.ExitCodes;
import com.taiji.opc2ecu.core.GapSummary;
import com.taiji.opc2ecu.core.OpcDaClient;
import com.taiji.opc2ecu.core.OpcDaClientFactory;
import com.taiji.opc2ecu.core.OpcDaException;
import com.taiji.opc2ecu.core.OpcDataCallback;
import com.taiji.opc2ecu.core.OpcReadValue;
import com.taiji.opc2ecu.core.ProbeConfig;
import com.taiji.opc2ecu.core.ProbeMode;
import com.taiji.opc2ecu.core.ReconnectManager;
import com.taiji.opc2ecu.core.ReconnectPolicy;
import com.taiji.opc2ecu.utgard.UtgardOpcDaClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
        OpcDaClient directClient = null;
        ReconnectManager reconnectManager = null;
        Thread shutdownHook = null;
        try {
            final Command command = Command.parse(args);
            if (command.mode == ProbeMode.SELF_TEST_PROTOCOL) {
                selfTestProtocol();
                return ExitCodes.SUCCESS;
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
                reconnectManager = createReconnectManager(config);
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

            directClient = new UtgardOpcDaClient(config, command.outputPath);
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

    private static ReconnectManager createReconnectManager(final ProbeConfig config) {
        final OpcDaClientFactory factory = new OpcDaClientFactory() {
            @Override public OpcDaClient create() {
                return new UtgardOpcDaClient(config, null);
            }
        };
        final ReconnectPolicy policy = new ReconnectPolicy(
                config.getReconnectInitialDelayMillis(),
                config.getReconnectMaxDelayMillis(),
                config.getReconnectMaxAttempts());
        return new ReconnectManager(
                factory,
                policy,
                config.getPeriodMillis(),
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
        manager.start(new OpcDataCallback() {
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
        System.err.println("  java -jar opcda-probe.jar --self-test-protocol");
    }

    private static final class Command {
        private final ProbeMode mode;
        private final String configPath;
        private final String outputPath;

        private Command(final ProbeMode mode, final String configPath) {
            this(mode, configPath, null);
        }

        private Command(final ProbeMode mode, final String configPath, final String outputPath) {
            this.mode = mode;
            this.configPath = configPath;
            this.outputPath = outputPath;
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
            throw new IllegalArgumentException("Unknown command or too many arguments");
        }

        private static void requireAtMostOnePath(final String[] args) {
            if (args.length > 2) {
                throw new IllegalArgumentException("Only one config file path may be supplied");
            }
        }
    }
}
