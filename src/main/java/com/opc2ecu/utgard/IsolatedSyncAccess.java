package com.opc2ecu.utgard;

import com.opc2ecu.core.DiagCode;
import com.opc2ecu.core.OpcDataCallback;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jinterop.dcom.common.JIException;
import org.openscada.opc.lib.da.Item;
import org.openscada.opc.lib.da.DataCallback;
import org.openscada.opc.lib.da.ItemState;
import org.openscada.opc.lib.da.Server;
import org.openscada.opc.lib.da.SyncAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keeps Utgard's polling lifecycle, with explicit read boundaries and per-item isolation. */
class IsolatedSyncAccess extends SyncAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger(IsolatedSyncAccess.class);
    private final OpcDataCallback callback;
    private long pointFailures;

    IsolatedSyncAccess(final Server server, final int periodMillis, final OpcDataCallback callback)
            throws Exception {
        super(server, periodMillis);
        this.callback = callback;
    }

    void registerItem(final String itemId) throws Exception {
        // AccessBase requires a registered callback to realize an item during bind.
        addItem(itemId, new DataCallback() {
            @Override public void changed(final Item item, final ItemState state) {
                deliver(item.getId(), state);
            }
        });
    }

    @Override protected void runOnce() throws JIException {
        if (!active) { return; }
        callback.onCycleStarted();
        try {
            readBatch();
        } catch (final RuntimeException malformedBatch) {
            // Group.read dereferences each OPCITEMSTATE before returning the map.
            // A null result for one failed item must not discard all other results.
            reportFailure("<batch>", malformedBatch);
            readIndividually();
        } finally {
            callback.onCycleComplete();
        }
    }

    protected void readBatch() throws JIException {
        final Map<Item, ItemState> values;
        synchronized (this) {
            if (!active || group == null || items.isEmpty()) { return; }
            values = group.read(false, items.keySet().toArray(new Item[items.size()]));
        }
        for (final Map.Entry<Item, ItemState> entry : values.entrySet()) {
            deliver(entry.getKey() == null ? null : entry.getKey().getId(), entry.getValue());
        }
    }

    protected synchronized List<String> boundItemIds() {
        return new ArrayList<String>(itemMap.keySet());
    }

    protected ItemState readSingle(final String itemId) throws JIException {
        final Item item;
        synchronized (this) { item = itemMap.get(itemId); }
        return item == null ? null : item.read(false);
    }

    private void readIndividually() throws JIException {
        for (final String itemId : boundItemIds()) {
            if (!active) { break; }
            try {
                deliver(itemId, readSingle(itemId));
            } catch (final JIException e) {
                final DiagCode code = UtgardOpcDaClient.classify(e);
                if (code != DiagCode.UNKNOWN && code != DiagCode.GENERIC_CONNECTION) { throw e; }
                reportFailure(itemId, e);
            } catch (final RuntimeException e) {
                reportFailure(itemId, e);
            }
        }
    }

    protected final void deliver(final String itemId, final ItemState state) {
        if (itemId == null || state == null || state.getErrorCode() < 0) {
            reportFailure(itemId, null);
            return;
        }
        try {
            // Deliberately bypass AccessBase's change-only itemCache: this is periodic sampling.
            callback.onData(UtgardOpcDaClient.convert(itemId, state));
        } catch (final RuntimeException e) {
            reportFailure(itemId, e);
        }
    }

    private void reportFailure(final String itemId, final Exception failure) {
        pointFailures++;
        if (pointFailures == 1L || pointFailures % 100L == 0L) {
            LOGGER.warn("OPC read isolated; total={} item={} failureType={} (sampled every 100 failures)",
                    pointFailures, itemId, failure == null ? "missing/failed item state"
                            : failure.getClass().getSimpleName());
        }
    }
}
