package com.taiji.opc2ecu.core;

import com.taiji.opc2ecu.poc.EcuRecordCodec;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Encodes and sends one collection cycle without mixing records between cycles. */
public final class UdpRecordSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(UdpRecordSender.class);
    private final DatagramChannel channel;
    private final Charset charset;
    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong recordsSent = new AtomicLong();
    private final AtomicLong sendFailures = new AtomicLong();

    public UdpRecordSender(final DatagramChannel channel, final Charset charset) {
        if (channel == null || charset == null) {
            throw new IllegalArgumentException("UDP sender dependencies must not be null");
        }
        this.channel = channel;
        this.charset = charset;
    }

    public void sendCycle(final List<OpcReadValue> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        final List<byte[]> records = new ArrayList<byte[]>(values.size());
        for (final OpcReadValue value : values) {
            records.add(EcuRecordCodec.encode(
                    value.getItemId(), charset, numericValue(value.getValue()), unixSeconds(value),
                    value.getQuality() & 0xffff));
        }
        for (int offset = 0; offset < records.size(); offset += EcuRecordCodec.MAX_RECORDS_PER_DATAGRAM) {
            final int count = Math.min(EcuRecordCodec.MAX_RECORDS_PER_DATAGRAM, records.size() - offset);
            final byte[] payload = new byte[count * EcuRecordCodec.RECORD_SIZE];
            for (int i = 0; i < count; i++) {
                System.arraycopy(records.get(offset + i), 0, payload,
                        i * EcuRecordCodec.RECORD_SIZE, EcuRecordCodec.RECORD_SIZE);
            }
            try {
                channel.send(payload);
                packetsSent.incrementAndGet();
                recordsSent.addAndGet(count);
            } catch (final IOException e) {
                sendFailures.incrementAndGet();
                LOGGER.warn("UDP business datagram dropped ({} records); no retry by protocol", count, e);
            }
        }
    }

    private static double numericValue(final Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("OPC value is not numeric: " + value);
        }
        return ((Number) value).doubleValue();
    }

    private static long unixSeconds(final OpcReadValue value) {
        return value.getTimestamp() == null ? 0L : value.getTimestamp().getTimeInMillis() / 1000L;
    }

    public long getPacketsSent() { return packetsSent.get(); }
    public long getRecordsSent() { return recordsSent.get(); }
    public long getSendFailures() { return sendFailures.get(); }
}
