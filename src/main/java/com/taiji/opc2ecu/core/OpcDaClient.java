package com.taiji.opc2ecu.core;

import java.util.List;

/** OPC DA operations used by the CLI and reconnect controller. */
public interface OpcDaClient {
    void connect() throws Exception;
    void disconnect();
    boolean isConnected();
    List<String> browseItems() throws Exception;
    int exportCatalog() throws Exception;
    default List<PointValidation> validateItems(final List<String> itemIds) throws Exception {
        throw new UnsupportedOperationException("This OPC client does not support item validation");
    }
    OpcReadValue readItem(String itemId) throws Exception;
    /** Iteration-1 compatibility entry point. */
    void bindSyncRead(OpcDataCallback callback) throws Exception;
    default void bindSyncRead(final List<String> items, final OpcDataCallback callback) throws Exception {
        if (items == null || items.size() != 1) {
            throw new UnsupportedOperationException("This OPC client does not support multi-item binding");
        }
        bindSyncRead(callback);
    }
    void unbind() throws Exception;
}
