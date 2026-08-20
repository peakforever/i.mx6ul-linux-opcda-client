package com.taiji.opc2ecu.utgard;

import com.taiji.opc2ecu.core.JsonWriter;
import com.taiji.opc2ecu.core.OpcDaClient;
import com.taiji.opc2ecu.core.OpcDaException;
import com.taiji.opc2ecu.core.OpcDataCallback;
import com.taiji.opc2ecu.core.OpcReadValue;
import com.taiji.opc2ecu.core.ProbeConfig;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.jinterop.dcom.core.JIComServer;
import org.jinterop.dcom.core.JISession;
import org.openscada.opc.dcom.common.Result;
import org.openscada.opc.dcom.da.OPCITEMRESULT;
import org.openscada.opc.dcom.da.OPCSERVERSTATUS;
import org.openscada.opc.dcom.list.ClassDetails;
import org.openscada.opc.lib.common.ConnectionInformation;
import org.openscada.opc.lib.da.AccessBase;
import org.openscada.opc.lib.da.DataCallback;
import org.openscada.opc.lib.da.Group;
import org.openscada.opc.lib.da.Item;
import org.openscada.opc.lib.da.ItemState;
import org.openscada.opc.lib.da.Server;
import org.openscada.opc.lib.da.SyncAccess;
import org.openscada.opc.lib.list.Categories;
import org.openscada.opc.lib.list.Category;
import org.openscada.opc.lib.list.ServerList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utgard/J-Interop adapter. A failed instance is disposable and must not be reused. */
public final class UtgardOpcDaClient implements OpcDaClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(UtgardOpcDaClient.class);
    private static volatile boolean initialActivationSecurityEnabled;

    private final ProbeConfig config;
    private final String catalogOutputPath;
    private final ScheduledExecutorService executor;
    private Server server;
    private AccessBase access;
    private volatile boolean connected;

    public UtgardOpcDaClient(final ProbeConfig config, final String catalogOutputPath) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.catalogOutputPath = catalogOutputPath;
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public synchronized void connect() throws OpcDaException {
        if (connected) {
            return;
        }
        try {
            enableInitialActivationSessionSecurity();
            System.setProperty("rpc.socketTimeout", Integer.toString(config.getSocketTimeoutMillis()));
            final ConnectionInformation connection = new ConnectionInformation();
            connection.setHost(config.getHost());
            connection.setDomain(config.getDomain());
            connection.setUser(config.getUser());
            connection.setPassword(config.getPassword());
            connection.setProgId(config.getProgId());
            connection.setClsid(config.getClsid());
            server = new Server(connection, executor);
            server.connect();
            connected = true;
        } catch (final Exception e) {
            connected = false;
            throw new OpcDaException(
                    OpcDaException.Kind.CONNECTION,
                    "OPC DA connection failed. Check OPC_PASSWORD, ProgID/CLSID, DCOM permissions, "
                            + "and Windows firewall TCP 135/RPC dynamic ports.",
                    e);
        }
    }

    @Override
    public synchronized void disconnect() {
        try {
            if (server != null) {
                server.disconnect();
            }
        } finally {
            connected = false;
            server = null;
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public List<String> browseItems() throws OpcDaException {
        requireConnected();
        try {
            final org.openscada.opc.lib.da.browser.FlatBrowser browser = server.getFlatBrowser();
            if (browser == null) {
                throw new IllegalStateException("The OPC DA server does not expose an address-space browser");
            }
            return new ArrayList<String>(new TreeSet<String>(browser.browse()));
        } catch (final Exception e) {
            throw readException("Unable to browse OPC DA items", e);
        }
    }

    @Override
    public int exportCatalog() throws Exception {
        requireConnected();
        if (catalogOutputPath == null || catalogOutputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Catalog output path is required");
        }
        try {
            final List<String> itemIds = browseItems();
            final List<ItemMetadata> items = validateItems(itemIds);
            final OPCSERVERSTATUS status = server.getServerState();
            final Path output = Paths.get(catalogOutputPath).toAbsolutePath().normalize();
            final Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                writeCatalogJson(writer, status, items);
            }
            return items.size();
        } catch (final OpcDaException e) {
            throw e;
        } catch (final Exception e) {
            throw readException("Unable to export the OPC DA catalog", e);
        }
    }

    @Override
    public OpcReadValue readItem(final String itemId) throws OpcDaException {
        requireConnected();
        Group group = null;
        try {
            group = server.addGroup("opc2ecu-read-" + System.currentTimeMillis());
            final Item item = group.addItem(itemId);
            return convert(item, item.read(false));
        } catch (final Exception e) {
            throw readException("Unable to read OPC item " + itemId, e);
        } finally {
            if (group != null) {
                try {
                    group.remove();
                } catch (final Exception e) {
                    LOGGER.debug("Failed to remove temporary read group", e);
                }
            }
        }
    }

    @Override
    public synchronized void bindSyncRead(final OpcDataCallback callback) throws OpcDaException {
        requireConnected();
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        try {
            access = new SyncAccess(server, config.getPeriodMillis());
            access.addItem(config.getItemId(), new DataCallback() {
                @Override
                public void changed(final Item item, final ItemState state) {
                    callback.onData(convert(item, state));
                }
            });
            access.bind();
        } catch (final Exception e) {
            throw readException("Unable to bind synchronous read for item " + config.getItemId(), e);
        }
    }

    @Override
    public synchronized void unbind() throws Exception {
        if (access != null) {
            try {
                access.unbind();
            } finally {
                access = null;
            }
        }
    }

    private void requireConnected() throws OpcDaException {
        if (!connected || server == null) {
            throw new OpcDaException(
                    OpcDaException.Kind.CONNECTION,
                    "OPC DA client is not connected; verify network and DCOM configuration.");
        }
    }

    private static OpcReadValue convert(final Item item, final ItemState state) {
        return new OpcReadValue(
                item.getId(),
                state.getValue(),
                state.getQuality() == null ? 0 : state.getQuality().intValue() & 0xffff,
                state.getTimestamp());
    }

    private static OpcDaException readException(final String action, final Throwable cause) {
        return new OpcDaException(
                OpcDaException.Kind.READ,
                action + ". Confirm the item exists and the OPC Server remains reachable.",
                cause);
    }

    private List<ItemMetadata> validateItems(final List<String> itemIds) throws Exception {
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
                        metadata.add(ItemMetadata.valid(itemId, value.getCanonicalDataType(),
                                value.getAccessRights(), result.getErrorCode()));
                    }
                }
            }
        } finally {
            group.remove();
        }
        return metadata;
    }

    private void writeCatalogJson(
            final BufferedWriter writer,
            final OPCSERVERSTATUS status,
            final List<ItemMetadata> items) throws Exception {
        writer.write("{\n");
        JsonWriter.writeField(writer, "schemaVersion", "1", 1, true, false);
        JsonWriter.writeField(writer, "generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()), 1, true, true);
        writer.write("  \"target\": {\n");
        JsonWriter.writeField(writer, "host", config.getHost(), 2, true, true);
        JsonWriter.writeField(writer, "domain", config.getDomain(), 2, true, true);
        JsonWriter.writeField(writer, "progId", config.getProgId(), 2, true, true);
        JsonWriter.writeField(writer, "clsid", config.getClsid(), 2, false, true);
        writer.write("  },\n");
        writer.write("  \"status\": {\n");
        JsonWriter.writeField(writer, "state", status == null ? null : String.valueOf(status.getServerState()), 2, true, true);
        JsonWriter.writeField(writer, "vendorInfo", status == null ? null : status.getVendorInfo(), 2, true, true);
        JsonWriter.writeField(writer, "version", status == null ? null : serverVersion(status), 2, true, true);
        JsonWriter.writeField(writer, "groupCount", status == null ? null : Integer.toString(status.getGroupCount()), 2, true, false);
        JsonWriter.writeField(writer, "bandwidth", status == null ? null : Integer.toString(status.getBandWidth()), 2, true, false);
        JsonWriter.writeField(writer, "startTime", status == null ? null : calendarText(status.getStartTime().asCalendar()), 2, true, true);
        JsonWriter.writeField(writer, "currentTime", status == null ? null : calendarText(status.getCurrentTime().asCalendar()), 2, true, true);
        JsonWriter.writeField(writer, "lastUpdateTime", status == null ? null : calendarText(status.getLastUpdateTime().asCalendar()), 2, false, true);
        writer.write("  },\n");
        writer.write("  \"items\": [\n");
        for (int i = 0; i < items.size(); i++) {
            final ItemMetadata item = items.get(i);
            writer.write("    {\n");
            JsonWriter.writeField(writer, "itemId", item.itemId, 3, true, true);
            JsonWriter.writeField(writer, "valid", Boolean.toString(item.valid), 3, true, false);
            JsonWriter.writeField(writer, "canonicalDataType", item.typeName, 3, true, true);
            JsonWriter.writeField(writer, "varType", Integer.toString(item.varType & 0xffff), 3, true, false);
            JsonWriter.writeField(writer, "accessRights", Integer.toString(item.accessRights), 3, true, false);
            JsonWriter.writeField(writer, "access", accessText(item.accessRights), 3, true, true);
            JsonWriter.writeField(writer, "errorCode", String.format("0x%08X", item.errorCode), 3, false, true);
            writer.write(i + 1 == items.size() ? "    }\n" : "    },\n");
        }
        writer.write("  ]\n");
        writer.write("}\n");
    }

    public static void listServers(final ProbeConfig config) throws Exception {
        enableInitialActivationSessionSecurity();
        JISession session = null;
        try {
            session = JISession.createSession(config.getDomain(), config.getUser(), config.getPassword());
            session.useNTLMv2(config.isUseNtlmV2());
            session.useSessionSecurity(true);
            session.setGlobalSocketTimeout(config.getSocketTimeoutMillis());
            System.out.println("[LIST] Connecting to remote OPCEnum...");
            final ServerList serverList = new ServerList(session, config.getHost());
            final Category[] categories = {
                    Categories.OPCDAServer10, Categories.OPCDAServer20, Categories.OPCDAServer30
            };
            final Set<String> clsids = new TreeSet<String>();
            for (final Category category : categories) {
                final Collection<String> discovered = serverList.listServers(
                        new Category[] { category }, new Category[0]);
                clsids.addAll(discovered);
            }
            System.out.printf("[LIST] Found %d registered OPC DA server class(es).%n", clsids.size());
            int index = 0;
            for (final String clsid : clsids) {
                index++;
                try {
                    final ClassDetails details = serverList.getDetails(clsid);
                    System.out.printf("[SERVER %d/%d] name=%s progId=%s clsid=%s%n",
                            index, clsids.size(), printable(details.getDescription()),
                            printable(details.getProgId()), printable(details.getClsId()));
                } catch (final Exception e) {
                    System.out.printf("[SERVER %d/%d] name=<unavailable> progId=<unavailable> clsid=%s%n",
                            index, clsids.size(), clsid);
                    LOGGER.warn("Unable to read details for OPC server CLSID {}", clsid, e);
                }
            }
            System.out.println("[RESULT] Remote OPC DA server enumeration succeeded.");
        } catch (final Exception e) {
            throw new OpcDaException(
                    OpcDaException.Kind.CONNECTION,
                    "Remote OPCEnum failed. Check OPC_PASSWORD, DCOM permissions, OPCEnum service, "
                            + "and Windows firewall TCP 135/RPC dynamic ports.", e);
        } finally {
            if (session != null) {
                JISession.destroySession(session);
            }
        }
    }

    private static synchronized void enableInitialActivationSessionSecurity() throws Exception {
        if (initialActivationSecurityEnabled) {
            return;
        }
        final Field defaultsField = JIComServer.class.getDeclaredField("defaults");
        defaultsField.setAccessible(true);
        final Properties defaults = (Properties) defaultsField.get(null);
        defaults.setProperty("rpc.ntlm.sign", "true");
        defaults.setProperty("rpc.ntlm.seal", "true");
        defaults.setProperty("rpc.ntlm.keyExchange", "true");
        defaults.setProperty("rpc.ntlm.keyLength", "128");
        defaults.setProperty("rpc.ntlm.ntlm2", "true");
        defaults.setProperty("rpc.ntlm.ntlmv2", "true");
        initialActivationSecurityEnabled = true;
        System.out.println("[SECURITY] DCOM initial activation uses NTLM packet privacy/integrity.");
    }

    private static String printable(final String value) {
        return value == null || value.trim().isEmpty() ? "<empty>" : value.trim();
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
        if ((accessRights & 3) == 3) { return "read-write"; }
        if ((accessRights & 1) != 0) { return "read"; }
        if ((accessRights & 2) != 0) { return "write"; }
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
                final String itemId, final boolean valid, final short varType,
                final int accessRights, final int errorCode) {
            this.itemId = itemId;
            this.valid = valid;
            this.varType = varType;
            this.typeName = valid ? varTypeName(varType) : null;
            this.accessRights = accessRights;
            this.errorCode = errorCode;
        }

        private static ItemMetadata valid(
                final String itemId, final short type, final int rights, final int error) {
            return new ItemMetadata(itemId, true, type, rights, error);
        }

        private static ItemMetadata failed(final String itemId, final int error) {
            return new ItemMetadata(itemId, false, (short) 0, 0, error);
        }
    }
}
