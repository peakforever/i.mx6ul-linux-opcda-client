package com.taiji.opc2ecu.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects one callback per configured item and emits a complete periodic snapshot. */
public final class CollectionCycle implements OpcDataCallback {
    private final List<String> itemOrder;
    private final UdpRecordSender sender;
    private final Map<String, OpcReadValue> current = new LinkedHashMap<String, OpcReadValue>();

    public CollectionCycle(final List<String> itemOrder, final UdpRecordSender sender) {
        if (itemOrder == null || itemOrder.isEmpty() || sender == null) {
            throw new IllegalArgumentException("collection cycle dependencies must not be empty");
        }
        this.itemOrder = new ArrayList<String>(itemOrder);
    	this.sender = sender;
    }

    @Override public synchronized void onData(final OpcReadValue value) {
        if (value == null || !itemOrder.contains(value.getItemId())) {
            return;
        }
        // A repeated item means SyncAccess started a new cycle before the prior cycle completed.
        if (current.containsKey(value.getItemId())) {
            current.clear();
        }
        current.put(value.getItemId(), value);
        if (current.size() == itemOrder.size()) {
            final List<OpcReadValue> snapshot = new ArrayList<OpcReadValue>(itemOrder.size());
            for (final String itemId : itemOrder) { snapshot.add(current.get(itemId)); }
            current.clear();
            sender.sendCycle(snapshot);
        }
    }
}
