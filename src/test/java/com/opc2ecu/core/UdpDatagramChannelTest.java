package com.opc2ecu.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class UdpDatagramChannelTest {
    private static final byte[] SESSION = { 0, 0, 0, 0 };

    @Test(timeout = 5000L) public void acceptsConfiguredPeerAfterIgnoringShortForeignPacket() throws Exception {
        final InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket peer = new DatagramSocket(0, loopback);
             DatagramSocket stranger = new DatagramSocket(0, loopback);
             DatagramSocket local = new DatagramSocket(0, loopback);
             UdpDatagramChannel channel = new UdpDatagramChannel(local, loopback, peer.getLocalPort())) {
            channel.setReceiveTimeout(500);
            channel.send(SESSION);
            peer.setSoTimeout(500);
            DatagramPacket request = new DatagramPacket(new byte[4], 4);
            peer.receive(request);
            assertEquals(local.getLocalPort(), request.getPort());
            send(stranger, new byte[] { 1 }, loopback, local.getLocalPort());
            send(peer, SESSION, loopback, local.getLocalPort());
            assertArrayEquals(SESSION, channel.receive());
        }
    }

    @Test(timeout = 5000L) public void matchingSessionFromWrongPortIsRejected() throws Exception {
        final InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket peer = new DatagramSocket(0, loopback);
             DatagramSocket stranger = new DatagramSocket(0, loopback);
             DatagramSocket local = new DatagramSocket(0, loopback);
             UdpDatagramChannel channel = new UdpDatagramChannel(local, loopback, peer.getLocalPort())) {
            channel.setReceiveTimeout(100);
            send(stranger, SESSION, loopback, local.getLocalPort());
            expectTimeout(channel);
        }
    }

    @Test(timeout = 5000L) public void matchingSessionAndPortFromWrongAddressAreRejected() throws Exception {
        final InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket peer = new DatagramSocket(0, loopback);
             DatagramSocket stranger = new DatagramSocket(peer.getLocalPort(), InetAddress.getByName("127.0.0.2"));
             DatagramSocket local = new DatagramSocket(0, loopback);
             UdpDatagramChannel channel = new UdpDatagramChannel(local, loopback, peer.getLocalPort())) {
            channel.setReceiveTimeout(100);
            send(stranger, SESSION, loopback, local.getLocalPort());
            expectTimeout(channel);
        }
    }

    @Test(timeout = 5000L) public void continuousForeignPacketsCannotExtendReceiveBudget() throws Exception {
        final InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket peer = new DatagramSocket(0, loopback);
             final DatagramSocket stranger = new DatagramSocket(0, loopback);
             final DatagramSocket local = new DatagramSocket(0, loopback);
             UdpDatagramChannel channel = new UdpDatagramChannel(local, loopback, peer.getLocalPort())) {
            channel.setReceiveTimeout(100);
            final AtomicBoolean running = new AtomicBoolean(true);
            final Thread noise = new Thread(new Runnable() {
                public void run() {
                    try {
                        while (running.get()) {
                            send(stranger, SESSION, loopback, local.getLocalPort());
                            Thread.sleep(2L);
                        }
                    } catch (Exception e) {
                        if (running.get()) { throw new AssertionError(e); }
                    }
                }
            }, "test-foreign-udp");
            noise.setDaemon(true);
            noise.start();
            try {
                final long started = System.nanoTime();
                expectTimeout(channel);
                assertTrue("Foreign packets must not restart the timeout",
                        System.nanoTime() - started < 1000000000L);
            } finally {
                running.set(false);
                noise.interrupt();
                noise.join(1000L);
            }
        }
    }

    private static void send(DatagramSocket socket, byte[] payload, InetAddress address, int port)
            throws Exception {
        socket.send(new DatagramPacket(payload, payload.length, address, port));
    }

    private static void expectTimeout(UdpDatagramChannel channel) throws Exception {
        try { channel.receive(); fail("Untrusted response must be ignored"); }
        catch (SocketTimeoutException expected) { }
    }
}
