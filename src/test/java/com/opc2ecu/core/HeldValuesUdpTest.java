package com.opc2ecu.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Verify the new value/quality/time behavior over real loopback UDP. */
public class HeldValuesUdpTest {
    @Test public void receiverSeesInitializationHeldBadAndRecovery() throws Exception {
        final DatagramSocket receiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        final UdpDatagramChannel channel = new UdpDatagramChannel("127.0.0.1", receiver.getLocalPort());
        try {
            receiver.setSoTimeout(2000);
            final CollectionCycle cycle = new CollectionCycle(Arrays.asList("a", "b"),
                    new UdpRecordSender(channel, StandardCharsets.UTF_8));
            cycle.publish();
            packet(receiver, 0, 0, 0x0120, 0x0120);
            cycle.onCycleStarted();
            cycle.onData(HeldPointValuesTest.sample("a", 12, 192, 123));
            cycle.onCycleComplete(); cycle.publish();
            packet(receiver, 12, 123, 192, 0x3020);
            cycle.onConnectionLost(new java.net.SocketTimeoutException()); cycle.publish();
            packet(receiver, 12, 123, 0x1314, 0x1320);
            cycle.onCycleStarted();
            cycle.onData(HeldPointValuesTest.sample("a", 13, 192, 124));
            cycle.onCycleComplete(); cycle.publish();
            packet(receiver, 13, 124, 192, 0x3020);
        } finally { channel.close(); receiver.close(); }
    }

    private void packet(DatagramSocket receiver, double value, int time, int quality, int secondQuality) throws Exception {
        final byte[] bytes = new byte[100];
        final DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        receiver.receive(packet);
        assertEquals(60, packet.getLength());
        final ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(value, data.getDouble(16), 0.0);
        assertEquals(time, data.getInt(24));
        assertEquals(quality, data.getShort(28) & 0xffff);
        assertEquals(0.0, data.getDouble(46), 0.0);
        assertEquals(0, data.getInt(54));
        assertEquals(secondQuality, data.getShort(58) & 0xffff);
    }
}
