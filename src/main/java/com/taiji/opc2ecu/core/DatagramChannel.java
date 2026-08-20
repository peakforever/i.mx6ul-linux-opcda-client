package com.taiji.opc2ecu.core;

import java.io.IOException;

/** Shared UDP endpoint abstraction used by business data and heartbeats. */
public interface DatagramChannel extends AutoCloseable {
    void send(byte[] payload) throws IOException;
    byte[] receive() throws IOException;
    void setReceiveTimeout(int timeoutMillis) throws IOException;
    void close();
}
