package com.opc2ecu.core;

/** Sample delivery with explicit boundaries for each synchronous read. */
public interface OpcDataCallback {
    default void onCycleStarted() { }
    void onData(OpcReadValue value);
    default void onCycleComplete() { }
}
