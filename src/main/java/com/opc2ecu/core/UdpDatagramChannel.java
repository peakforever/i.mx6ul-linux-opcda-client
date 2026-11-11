package com.opc2ecu.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** Shared UDP socket accepting responses only from the configured peer address and port. */
public final class UdpDatagramChannel implements DatagramChannel {
    private final DatagramSocket socket;
    private final InetAddress host;
    private final int port;
    private volatile int receiveTimeoutMillis;

    public UdpDatagramChannel(final String host, final int port) throws IOException {
        this(new DatagramSocket(), InetAddress.getByName(host), port);
    }

    UdpDatagramChannel(final DatagramSocket socket, final InetAddress host, final int port) {
        this.socket = socket;
        this.host = host;
        this.port = port;
    }

    @Override public synchronized void send(final byte[] payload) throws IOException {
        socket.send(new DatagramPacket(payload, payload.length, host, port));
    }

    @Override public byte[] receive() throws IOException {
        final byte[] buffer = new byte[1441];
        final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        final int timeout = receiveTimeoutMillis;
        final long startedAt = System.nanoTime();
        final long budget = TimeUnit.MILLISECONDS.toNanos(timeout);
        for (;;) {
            if (timeout > 0) {
                final long remaining = budget - (System.nanoTime() - startedAt);
                if (remaining <= 0L) { throw new SocketTimeoutException("UDP peer response timed out"); }
                socket.setSoTimeout((int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
            packet.setLength(buffer.length);
            socket.receive(packet);
            if (!host.equals(packet.getAddress()) || port != packet.getPort()) { continue; }
            final byte[] result = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), result, 0, result.length);
            return result;
        }
    }

    @Override public void setReceiveTimeout(final int timeoutMillis) throws IOException {
        socket.setSoTimeout(timeoutMillis);
        receiveTimeoutMillis = timeoutMillis;
    }

    @Override public void close() { socket.close(); }
}
