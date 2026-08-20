package com.taiji.opc2ecu.poc;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.openscada.opc.lib.common.ConnectionInformation;
import org.openscada.opc.lib.da.AccessBase;
import org.openscada.opc.lib.da.DataCallback;
import org.openscada.opc.lib.da.Item;
import org.openscada.opc.lib.da.ItemState;
import org.openscada.opc.lib.da.Group;
import org.openscada.opc.lib.da.Server;
import org.openscada.opc.lib.da.SyncAccess;
import org.openscada.opc.lib.list.Categories;
import org.openscada.opc.lib.list.Category;
import org.openscada.opc.lib.list.ServerList;
import org.openscada.opc.dcom.list.ClassDetails;
import org.openscada.opc.dcom.common.Result;
import org.openscada.opc.dcom.da.OPCITEMRESULT;
import org.openscada.opc.dcom.da.OPCSERVERSTATUS;
import org.jinterop.dcom.core.JISession;
import org.jinterop.dcom.core.JIComServer;

/**
 * Minimal Linux-to-Windows OPC DA connectivity probe.
 *
 * <p>The password is deliberately accepted only through the OPC_PASSWORD
 * environment variable so that it is not committed with the test config.</p>
 */
public final class OpcDaProbe {
    private static final String DEFAULT_CONFIG = "config/opc.properties";

    private OpcDaProbe() {
    }

    public static void main(final String[] args) {
        final Command command;
        try {
            command = Command.parse(args);
        } catch (final IllegalArgumentException e) {
            System.err.println("[ERROR] " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        Server server = null;
        AccessBase access = null;
        int exitCode = 1;

        try {
            if (command.mode == Mode.SELF_TEST_PROTOCOL) {
                selfTestProtocol();
                exitCode = 0;
                return;
            }
            final ProbeConfig config = ProbeConfig.load(
                    command.configPath,
                    System.getenv("OPC_PASSWORD"),
                    command.mode);
            printTarget(config, command.mode);
            if (command.mode == Mode.CHECK_CONFIG) {
                System.out.println("[RESULT] Configuration validation succeeded; no network connection was attempted.");
                exitCode = 0;
                return;
            }
            enableInitialActivationSessionSecurity();
            if (command.mode == Mode.LIST_SERVERS) {
                listServers(config);
                exitCode = 0;
                return;
            }

            final ConnectionInformation connection = new ConnectionInformation();
            connection.setHost(config.host);
            connection.setDomain(config.domain);
            connection.setUser(config.user);
            connection.setPassword(config.password);
            connection.setProgId(config.progId);
            connection.setClsid(config.clsid);

            System.setProperty("rpc.socketTimeout", Integer.toString(config.socketTimeoutMillis));
            server = new Server(connection, executor);
            final Instant connectStart = Instant.now();
            System.out.println("[CONNECT] Connecting to the OPC DA server...");
            server.connect();
            System.out.printf("[CONNECT] Connected in %d ms%n",
                    Duration.between(connectStart, Instant.now()).toMillis());

            if (command.mode == Mode.LIST_ITEMS) {
                listItems(server);
                exitCode = 0;
                return;
            }
            if (command.mode == Mode.EXPORT_CATALOG) {
                exportCatalog(server, config, command.outputPath);
                exitCode = 0;
                return;
            }

            final CountDownLatch samples = new CountDownLatch(config.sampleCount);
            final AtomicInteger sequence = new AtomicInteger();
            access = new SyncAccess(server, config.periodMillis);
            access.addItem(config.itemId, new DataCallback() {
                @Override
                public void changed(final Item item, final ItemState state) {
                    final int number = sequence.incrementAndGet();
                    System.out.printf(
                            "[READ %d/%d] item=%s value=%s quality=%s timestamp=%s%n",
                            number,
                            config.sampleCount,
                            item.getId(),
                            state.getValue(),
                            state.getQuality(),
                            state.getTimestamp());
                    samples.countDown();
                }
            });

            System.out.printf("[READ] Polling every %d ms; waiting for %d samples...%n",
                    config.periodMillis, config.sampleCount);
            access.bind();

            final long waitSeconds = Math.max(
                    config.readTimeoutSeconds,
                    ((long) config.periodMillis * config.sampleCount / 1000L) + 10L);
            if (!samples.await(waitSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out before receiving " + config.sampleCount + " samples; received "
                                + sequence.get());
            }

            System.out.println("[RESULT] OPC DA single-item read verification succeeded.");
            exitCode = 0;
        } catch (final Exception e) {
            System.err.printf("[ERROR] %s: %s%n", e.getClass().getName(), e.getMessage());
            e.printStackTrace(System.err);
        } finally {
            if (access != null) {
                try {
                    access.unbind();
                } catch (final Exception e) {
                    System.err.printf("[WARN] Failed to unbind access: %s%n", e.getMessage());
                }
            }
            if (server != null) {
                server.disconnect();
            }
            executor.shutdownNow();
            System.out.println("[CLOSE] OPC resources released.");
        }

        System.exit(exitCode);
    }

    /**
     * Enables signing and sealing before J-Interop sends IRemoteActivation.
     *
     * <p>J-Interop 2.1.8 normally applies these properties only after remote
     * activation succeeds. Current Windows DCOM hardening requires packet
     * integrity on the activation call itself, so the defaults must be fixed
     * before the first JIComServer instance is constructed.</p>
     */
    private static void enableInitialActivationSessionSecurity() throws Exception {
        final Field defaultsField = JIComServer.class.getDeclaredField("defaults");
        defaultsField.setAccessible(true);
        final Properties defaults = (Properties) defaultsField.get(null);
        defaults.setProperty("rpc.ntlm.sign", "true");
        defaults.setProperty("rpc.ntlm.seal", "true");
        defaults.setProperty("rpc.ntlm.keyExchange", "true");
        defaults.setProperty("rpc.ntlm.keyLength", "128");
        defaults.setProperty("rpc.ntlm.ntlm2", "true");
        defaults.setProperty("rpc.ntlm.ntlmv2", "true");
        System.out.println("[SECURITY] DCOM initial activation uses NTLM packet privacy/integrity.");
    }

    private static void selfTestProtocol() {
        final byte[] record = EcuRecordCodec.encode(
                "Server.Group.Item",
                StandardCharsets.US_ASCII,
                1.5d,
                0x01020304L,
                192);
        final String actual = EcuRecordCodec.toHex(record);
        final String expected = "199065ab24ae156c84bc8b33e6acc683"
                + "000000000000f83f"
                + "04030201"
                + "c000";
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Protocol vector mismatch: expected=" + expected + " actual=" + actual);
        }
        System.out.println("[PROTOCOL] Record size=30 byteOrder=little-endian maxRecordsPerDatagram=48");
        System.out.println("[PROTOCOL] MD5 input=Server.Group.Item charset=US-ASCII");
        System.out.println("[PROTOCOL] vector=" + actual);
        System.out.println("[RESULT] OPC2ECU protocol codec self-test succeeded.");
    }

    private static void listItems(final Server server) throws Exception {
        final org.openscada.opc.lib.da.browser.FlatBrowser browser = server.getFlatBrowser();
        if (browser == null) {
            throw new IllegalStateException("The OPC DA server does not expose an address-space browser");
        }
        final Set<String> itemIds = new TreeSet<String>(browser.browse());
        System.out.printf("[ITEMS] Found %d item(s).%n", itemIds.size());
        int index = 0;
        for (final String itemId : itemIds) {
            index++;
            System.out.printf("[ITEM %d/%d] %s%n", index, itemIds.size(), itemId);
        }
        System.out.println("[RESULT] Remote OPC DA item enumeration succeeded.");
    }

    private static void exportCatalog(
            final Server server,
            final ProbeConfig config,
            final String outputPath) throws Exception {
        final org.openscada.opc.lib.da.browser.FlatBrowser browser = server.getFlatBrowser();
        if (browser == null) {
            throw new IllegalStateException("The OPC DA server does not expose an address-space browser");
        }

        final List<String> itemIds = new ArrayList<String>(new TreeSet<String>(browser.browse()));
        final List<ItemMetadata> items = validateItems(server, itemIds);
        final OPCSERVERSTATUS status = server.getServerState();
        final Path output = Paths.get(outputPath).toAbsolutePath().normalize();
        final Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(
                output, StandardCharsets.UTF_8)) {
            writeCatalogJson(writer, config, status, items);
        }

        System.out.printf("[EXPORT] Wrote server catalog with %d item(s) to %s%n",
                items.size(), output);
        System.out.println("[RESULT] OPC DA server catalog export succeeded.");
    }

    private static List<ItemMetadata> validateItems(
            final Server server,
            final List<String> itemIds) throws Exception {
        final List<ItemMetadata> metadata = new ArrayList<ItemMetadata>(itemIds.size());
        final Group group = server.addGroup("opc2ecu-catalog-" + System.currentTimeMillis());
        try {
            final int batchSize = 50;
            for (int offset = 0; offset < itemIds.size(); offset += batchSize) {
                final int end = Math.min(offset + batchSize, itemIds.size());
                final List<String> batch = itemIds.subList(offset, end);
                final Map<String, Result<OPCITEMRESULT>> results = group.validateItems(
                        batch.toArray(new String[batch.size()]));
                for (final String itemId : batch) {
                    final Result<OPCITEMRESULT> result = results.get(itemId);
                    if (result == null || result.isFailed() || result.getValue() == null) {
                        metadata.add(ItemMetadata.failed(itemId,
                                result == null ? -1 : result.getErrorCode()));
                    } else {
                        final OPCITEMRESULT value = result.getValue();
                        metadata.add(ItemMetadata.valid(
                                itemId,
                                value.getCanonicalDataType(),
                                value.getAccessRights(),
                                result.getErrorCode()));
                    }
                }
            }
        } finally {
            group.remove();
        }
        return metadata;
    }

    private static void writeCatalogJson(
            final BufferedWriter writer,
            final ProbeConfig config,
            final OPCSERVERSTATUS status,
            final List<ItemMetadata> items) throws IOException {
        writer.write("{\n");
        writeJsonField(writer, "schemaVersion", "1", 1, true, false);
        writeJsonField(writer, "generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()), 1, true, true);
        writer.write("  \"target\": {\n");
        writeJsonField(writer, "host", config.host, 2, true, true);
        writeJsonField(writer, "domain", config.domain, 2, true, true);
        writeJsonField(writer, "progId", config.progId, 2, true, true);
        writeJsonField(writer, "clsid", config.clsid, 2, false, true);
        writer.write("  },\n");
        writer.write("  \"status\": {\n");
        writeJsonField(writer, "state", status == null ? null : String.valueOf(status.getServerState()), 2, true, true);
        writeJsonField(writer, "vendorInfo", status == null ? null : status.getVendorInfo(), 2, true, true);
        writeJsonField(writer, "version", status == null ? null : serverVersion(status), 2, true, true);
        writeJsonField(writer, "groupCount", status == null ? null : Integer.toString(status.getGroupCount()), 2, true, false);
        writeJsonField(writer, "bandwidth", status == null ? null : Integer.toString(status.getBandWidth()), 2, true, false);
        writeJsonField(writer, "startTime", status == null ? null : calendarText(status.getStartTime().asCalendar()), 2, true, true);
        writeJsonField(writer, "currentTime", status == null ? null : calendarText(status.getCurrentTime().asCalendar()), 2, true, true);
        writeJsonField(writer, "lastUpdateTime", status == null ? null : calendarText(status.getLastUpdateTime().asCalendar()), 2, false, true);
        writer.write("  },\n");
        writer.write("  \"items\": [\n");
        for (int i = 0; i < items.size(); i++) {
            final ItemMetadata item = items.get(i);
            writer.write("    {\n");
            writeJsonField(writer, "itemId", item.itemId, 3, true, true);
            writeJsonField(writer, "valid", Boolean.toString(item.valid), 3, true, false);
            writeJsonField(writer, "canonicalDataType", item.typeName, 3, true, true);
            writeJsonField(writer, "varType", Integer.toString(item.varType & 0xffff), 3, true, false);
            writeJsonField(writer, "accessRights", Integer.toString(item.accessRights), 3, true, false);
            writeJsonField(writer, "access", accessText(item.accessRights), 3, true, true);
            writeJsonField(writer, "errorCode", String.format("0x%08X", item.errorCode), 3, false, true);
            writer.write(i + 1 == items.size() ? "    }\n" : "    },\n");
        }
        writer.write("  ]\n");
        writer.write("}\n");
    }

    private static void writeJsonField(
            final BufferedWriter writer,
            final String name,
            final String value,
            final int indent,
            final boolean comma,
            final boolean quoted) throws IOException {
        for (int i = 0; i < indent; i++) {
            writer.write("  ");
        }
        writer.write("\"");
        writer.write(jsonEscape(name));
        writer.write("\": ");
        if (value == null) {
            writer.write("null");
        } else if (quoted) {
            writer.write("\"");
            writer.write(jsonEscape(value));
            writer.write("\"");
        } else {
            writer.write(value);
        }
        writer.write(comma ? ",\n" : "\n");
    }

    private static String jsonEscape(final String value) {
        final StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }

    private static String serverVersion(final OPCSERVERSTATUS status) {
        return (status.getMajorVersion() & 0xffff) + "."
                + (status.getMinorVersion() & 0xffff) + "."
                + (status.getBuildNumber() & 0xffff);
    }

    private static String calendarText(final Calendar calendar) {
        return calendar == null ? null : DateTimeFormatter.ISO_INSTANT.format(calendar.toInstant());
    }

    private static String accessText(final int accessRights) {
        if ((accessRights & 3) == 3) {
            return "read-write";
        }
        if ((accessRights & 1) != 0) {
            return "read";
        }
        if ((accessRights & 2) != 0) {
            return "write";
        }
        return "none";
    }

    private static String varTypeName(final short varType) {
        switch (varType & 0xffff) {
            case 0: return "VT_EMPTY";
            case 2: return "VT_I2";
            case 3: return "VT_I4";
            case 4: return "VT_R4";
            case 5: return "VT_R8";
            case 6: return "VT_CY";
            case 7: return "VT_DATE";
            case 8: return "VT_BSTR";
            case 11: return "VT_BOOL";
            case 16: return "VT_I1";
            case 17: return "VT_UI1";
            case 18: return "VT_UI2";
            case 19: return "VT_UI4";
            case 20: return "VT_I8";
            case 21: return "VT_UI8";
            case 8194: return "VT_ARRAY|VT_I2";
            case 8195: return "VT_ARRAY|VT_I4";
            case 8196: return "VT_ARRAY|VT_R4";
            case 8197: return "VT_ARRAY|VT_R8";
            case 8200: return "VT_ARRAY|VT_BSTR";
            case 8209: return "VT_ARRAY|VT_UI1";
            case 8210: return "VT_ARRAY|VT_UI2";
            case 8211: return "VT_ARRAY|VT_UI4";
            default: return String.format("VT_0x%04X", varType & 0xffff);
        }
    }

    private static final class ItemMetadata {
        private final String itemId;
        private final boolean valid;
        private final short varType;
        private final String typeName;
        private final int accessRights;
        private final int errorCode;

        private ItemMetadata(
                final String itemId,
                final boolean valid,
                final short varType,
                final int accessRights,
                final int errorCode) {
            this.itemId = itemId;
            this.valid = valid;
            this.varType = varType;
            this.typeName = valid ? varTypeName(varType) : null;
            this.accessRights = accessRights;
            this.errorCode = errorCode;
        }

        private static ItemMetadata valid(
                final String itemId,
                final short varType,
                final int accessRights,
                final int errorCode) {
            return new ItemMetadata(itemId, true, varType, accessRights, errorCode);
        }

        private static ItemMetadata failed(final String itemId, final int errorCode) {
            return new ItemMetadata(itemId, false, (short) 0, 0, errorCode);
        }
    }

    private static void listServers(final ProbeConfig config) throws Exception {
        JISession session = null;
        try {
            session = JISession.createSession(config.domain, config.user, config.password);
            session.useNTLMv2(config.useNtlmV2);
            // Windows DCOM hardening requires at least packet integrity for
            // remote activation. J-Interop calls this NTLM session security;
            // it signs and seals the RPC traffic when enabled before binding.
            session.useSessionSecurity(true);
            session.setGlobalSocketTimeout(config.socketTimeoutMillis);

            System.out.println("[LIST] Connecting to remote OPCEnum...");
            final ServerList serverList = new ServerList(session, config.host);
            final Category[] categories = {
                    Categories.OPCDAServer10,
                    Categories.OPCDAServer20,
                    Categories.OPCDAServer30
            };
            final Set<String> clsids = new TreeSet<String>();
            for (final Category category : categories) {
                final Collection<String> discovered = serverList.listServers(
                        new Category[] { category },
                        new Category[0]);
                clsids.addAll(discovered);
            }

            System.out.printf("[LIST] Found %d registered OPC DA server class(es).%n", clsids.size());
            int index = 0;
            for (final String clsid : clsids) {
                index++;
                try {
                    final ClassDetails details = serverList.getDetails(clsid);
                    System.out.printf(
                            "[SERVER %d/%d] name=%s progId=%s clsid=%s%n",
                            index,
                            clsids.size(),
                            printable(details.getDescription()),
                            printable(details.getProgId()),
                            printable(details.getClsId()));
                } catch (final Exception e) {
                    System.out.printf(
                            "[SERVER %d/%d] name=<unavailable> progId=<unavailable> clsid=%s%n",
                            index,
                            clsids.size(),
                            clsid);
                    System.err.printf("[WARN] Unable to read details for CLSID %s: %s%n",
                            clsid, e.getMessage());
                }
            }
            System.out.println("[RESULT] Remote OPC DA server enumeration succeeded.");
        } finally {
            if (session != null) {
                JISession.destroySession(session);
            }
        }
    }

    private static String printable(final String value) {
        return value == null || value.trim().isEmpty() ? "<empty>" : value.trim();
    }

    private static void printTarget(final ProbeConfig config, final Mode mode) {
        if (mode == Mode.LIST_SERVERS) {
            System.out.printf(
                    "[CONFIG] mode=list-server host=%s domain=%s user=%s ntlmV2=%s sessionSecurity=true socketTimeoutMillis=%d%n",
                    config.host,
                    config.domain,
                    config.user,
                    config.useNtlmV2,
                    config.socketTimeoutMillis);
        } else {
            System.out.printf("[CONFIG] host=%s domain=%s user=%s progId=%s clsid=%s itemId=%s%n",
                    config.host,
                    config.domain,
                    config.user,
                    config.progId,
                    config.clsid,
                    config.itemId);
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

    private enum Mode {
        READ_ITEM,
        CHECK_CONFIG,
        LIST_SERVERS,
        LIST_ITEMS,
        EXPORT_CATALOG,
        SELF_TEST_PROTOCOL
    }

    private static final class Command {
        private final Mode mode;
        private final String configPath;
        private final String outputPath;

        private Command(final Mode mode, final String configPath) {
            this(mode, configPath, null);
        }

        private Command(final Mode mode, final String configPath, final String outputPath) {
            this.mode = mode;
            this.configPath = configPath;
            this.outputPath = outputPath;
        }

        private static Command parse(final String[] args) {
            if (args.length == 0) {
                return new Command(Mode.READ_ITEM, DEFAULT_CONFIG);
            }
            if (args.length == 1 && "--self-test-protocol".equals(args[0])) {
                return new Command(Mode.SELF_TEST_PROTOCOL, null);
            }
            if (args.length == 1 && !args[0].startsWith("--")) {
                return new Command(Mode.READ_ITEM, args[0]);
            }
            if ("--check-config".equals(args[0])) {
                requireAtMostOnePath(args);
                return new Command(Mode.CHECK_CONFIG, args.length == 2 ? args[1] : DEFAULT_CONFIG);
            }
            if ("--list-server".equals(args[0]) || "--list-servers".equals(args[0])) {
                requireAtMostOnePath(args);
                return new Command(Mode.LIST_SERVERS, args.length == 2 ? args[1] : DEFAULT_CONFIG);
            }
            if ("--list-items".equals(args[0])) {
                requireAtMostOnePath(args);
                return new Command(Mode.LIST_ITEMS, args.length == 2 ? args[1] : DEFAULT_CONFIG);
            }
            if ("--export-catalog".equals(args[0])) {
                if (args.length < 2 || args.length > 3) {
                    throw new IllegalArgumentException(
                            "--export-catalog requires an output path and optional config file path");
                }
                return new Command(
                        Mode.EXPORT_CATALOG,
                        args.length == 3 ? args[2] : DEFAULT_CONFIG,
                        args[1]);
            }
            throw new IllegalArgumentException("Unknown command or too many arguments");
        }

        private static void requireAtMostOnePath(final String[] args) {
            if (args.length > 2) {
                throw new IllegalArgumentException("Only one config file path may be supplied");
            }
        }
    }

    private static final class ProbeConfig {
        private final String host;
        private final String domain;
        private final String user;
        private final String password;
        private final String progId;
        private final String clsid;
        private final String itemId;
        private final int periodMillis;
        private final int sampleCount;
        private final int readTimeoutSeconds;
        private final int socketTimeoutMillis;
        private final boolean useNtlmV2;

        private ProbeConfig(final Properties properties, final String password, final Mode mode) {
            host = required(properties, "host");
            domain = properties.getProperty("domain", "").trim();
            user = required(properties, "user");
            this.password = requireSecret(password);
            if (mode == Mode.LIST_SERVERS) {
                progId = properties.getProperty("progId", "").trim();
                clsid = properties.getProperty("clsid", "").trim();
                itemId = properties.getProperty("itemId", "").trim();
            } else if (mode == Mode.LIST_ITEMS || mode == Mode.EXPORT_CATALOG) {
                progId = required(properties, "progId");
                clsid = required(properties, "clsid");
                itemId = properties.getProperty("itemId", "").trim();
            } else {
                progId = required(properties, "progId");
                clsid = required(properties, "clsid");
                itemId = required(properties, "itemId");
            }
            periodMillis = positiveInt(properties, "periodMillis", 1000);
            sampleCount = positiveInt(properties, "sampleCount", 10);
            readTimeoutSeconds = positiveInt(properties, "readTimeoutSeconds", 30);
            socketTimeoutMillis = positiveInt(properties, "socketTimeoutMillis", 30000);
            useNtlmV2 = Boolean.parseBoolean(properties.getProperty("useNtlmV2", "true").trim());
        }

        private static ProbeConfig load(
                final String path,
                final String password,
                final Mode mode) throws IOException {
            final Properties properties = new Properties();
            try (InputStream input = new FileInputStream(path)) {
                properties.load(input);
            }
            return new ProbeConfig(properties, password, mode);
        }

        private static String required(final Properties properties, final String key) {
            return requireText(properties.getProperty(key),
                    "Required property is missing: " + key);
        }

        private static String requireText(final String value, final String message) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(message);
            }
            return value.trim();
        }

        private static String requireSecret(final String value) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("Environment variable OPC_PASSWORD is required");
            }
            return value;
        }

        private static int positiveInt(
                final Properties properties,
                final String key,
                final int defaultValue) {
            final String value = properties.getProperty(key);
            final int parsed = value == null ? defaultValue : Integer.parseInt(value.trim());
            if (parsed <= 0) {
                throw new IllegalArgumentException(key + " must be greater than zero");
            }
            return parsed;
        }
    }
}
