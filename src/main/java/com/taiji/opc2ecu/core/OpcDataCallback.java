package com.taiji.opc2ecu.core;

public interface OpcDataCallback {
    void onData(OpcReadValue value);
}
