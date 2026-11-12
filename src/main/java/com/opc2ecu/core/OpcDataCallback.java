package com.opc2ecu.core;

/** Sample delivery with explicit boundaries for each synchronous read. */
public interface OpcDataCallback {
    default void onCycleStarted() { }
    void onData(OpcReadValue value);
    default void onCycleComplete() { }
    default void onConnectionLost() { }
    default void onConnectionLost(Throwable failure) { onConnectionLost(); }
    default void onPointFailure(String itemId, QualityReason reason) { }
}
