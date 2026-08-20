package com.taiji.opc2ecu.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Datagram socket connected to the configured OPC2ECU UDP peer. */
public final class UdpDatagramChannel implements DatagramChannel {
    private final DatagramSocket socket;
    private final InetAddress host;
    private final int port;

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
        socket.receive(packet);
        final byte[] result = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), result, 0, result.length);
        return result;
    }

    @Override public void setReceiveTimeout(final int timeoutMillis) throws IOException {
        socket.setSoTimeout(timeoutMillis);
    }

    @Override public void close() { socket.close(); }
}
