package com.taiji.opc2ecu.poc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class EcuRecordCodecTest {
    @Test
    public void encodesFixedProtocolVector() {
        assertEquals(
                "199065ab24ae156c84bc8b33e6acc683000000000000f83f04030201c000",
                EcuRecordCodec.toHex(EcuRecordCodec.encode(
                        "Server.Group.Item", StandardCharsets.US_ASCII,
                        1.5d, 0x01020304L, 192)));
    }

    @Test
    public void recordHasFixedSize() {
        assertEquals(30, EcuRecordCodec.encode(
                "x", StandardCharsets.US_ASCII, 0.0d, 0L, 0).length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyItemPath() {
        EcuRecordCodec.encode("", StandardCharsets.US_ASCII, 0.0d, 0L, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullCharset() {
        EcuRecordCodec.encode("x", null, 0.0d, 0L, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeUnixSeconds() {
        EcuRecordCodec.encode("x", StandardCharsets.US_ASCII, 0.0d, -1L, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnixSecondsAboveUint32() {
        EcuRecordCodec.encode("x", StandardCharsets.US_ASCII, 0.0d, 0x100000000L, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeQuality() {
        EcuRecordCodec.encode("x", StandardCharsets.US_ASCII, 0.0d, 0L, -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsQualityAboveUint16() {
        EcuRecordCodec.encode("x", StandardCharsets.US_ASCII, 0.0d, 0L, 0x10000);
    }

    @Test
    public void honorsNonAsciiCharset() {
        final byte[] utf8 = EcuRecordCodec.encode("中文", StandardCharsets.UTF_8, 0.0d, 0L, 0);
        final byte[] utf16 = EcuRecordCodec.encode("中文", StandardCharsets.UTF_16LE, 0.0d, 0L, 0);
        assertFalse(EcuRecordCodec.toHex(utf8).equals(EcuRecordCodec.toHex(utf16)));
    }
}
