package com.taiji.opc2ecu.core;

import java.util.List;

/** OPC DA operations used by the CLI and reconnect controller. */
public interface OpcDaClient {
    void connect() throws Exception;
    void disconnect();
    boolean isConnected();
    List<String> browseItems() throws Exception;
    int exportCatalog() throws Exception;
    OpcReadValue readItem(String itemId) throws Exception;
    void bindSyncRead(OpcDataCallback callback) throws Exception;
    void unbind() throws Exception;
}
