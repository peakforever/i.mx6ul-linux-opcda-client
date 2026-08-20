package com.taiji.opc2ecu.poc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Encodes the legacy OPC2ECU fixed-width UDP record. */
public final class EcuRecordCodec {
    public static final int RECORD_SIZE = 30;
    public static final int MAX_RECORDS_PER_DATAGRAM = 48;

    private EcuRecordCodec() {
    }

    public static byte[] encode(
            final String itemPath,
            final Charset pathCharset,
            final double value,
            final long unixSeconds,
            final int quality) {
        if (itemPath == null || itemPath.isEmpty()) {
            throw new IllegalArgumentException("itemPath must not be empty");
        }
        if (pathCharset == null) {
            throw new IllegalArgumentException("pathCharset must not be null");
        }
        if (unixSeconds < 0L || unixSeconds > 0xffffffffL) {
            throw new IllegalArgumentException("unixSeconds does not fit uint32: " + unixSeconds);
        }
        if (quality < 0 || quality > 0xffff) {
            throw new IllegalArgumentException("quality does not fit uint16: " + quality);
        }

        final ByteBuffer record = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        record.put(md5(itemPath.getBytes(pathCharset)));
        record.putDouble(value);
        record.putInt((int) unixSeconds);
        record.putShort((short) quality);
        return record.array();
    }

    public static byte[] md5(final byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime does not provide MD5", e);
        }
    }

    public static String toHex(final byte[] data) {
        final StringBuilder hex = new StringBuilder(data.length * 2);
        for (final byte value : data) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }
}
