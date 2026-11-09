package com.opc2ecu.utgard;

import java.util.concurrent.atomic.AtomicLong;
import ndr.NetworkDataRepresentation;

/** Bounded, opt-in activation field diagnostics; never logs credentials or whole buffers. */
public final class ActivationTrace {
    public static final String PROPERTY = "opc2ecu.activationTrace";
    public static final String OMIT_LEGACY_TAIL_PROPERTY = "opc2ecu.activationOmitLegacyTail";
    private static final AtomicLong IDS = new AtomicLong();
    private final boolean enabled = Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    private final long id = enabled ? IDS.incrementAndGet() : 0;
    private int lines;

    public static void validateProperty() {
        validateBooleanProperty(PROPERTY);
        validateBooleanProperty(OMIT_LEGACY_TAIL_PROPERTY);
    }

    private static void validateBooleanProperty(final String property) {
        final String value = System.getProperty(property, "false");
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(property + " must be true or false");
        }
    }

    /** Includes field construction in the isolation boundary; no observation when disabled. */
    public void observe(final Runnable observation) {
        if (!enabled || lines >= 8) { return; }
        try { observation.run(); } catch (RuntimeException ignored) {
            // Keep protocol outcome even if diagnostic access/formatting/output fails.
        }
    }

    public boolean isEnabled() { return enabled; }

    public void emit(final String event, final NetworkDataRepresentation ndr, final String fields) {
        if (!enabled || lines++ >= 8) { return; }
        try {
            String line = "[ACTIVATION] revision=2 traceId=" + id + " event=" + event
                    + " bufferIndex=" + ndr.getBuffer().getIndex()
                    + " bufferReportedLength=" + ndr.getBuffer().getLength()
                    + " capacity=" + ndr.getBuffer().getCapacity() + " " + fields;
            line = line.replace('\n', ' ').replace('\r', ' ');
            System.err.println(line.length() <= 2048 ? line : line.substring(0, 2033) + "...<truncated>");
        } catch (RuntimeException ignored) {
            // Observation must not replace the original protocol result.
        }
    }

    /** Only called for fixed numeric fields, never for arbitrary packets or security tokens. */
    public static String hex(final NetworkDataRepresentation ndr, final int offset, final int count) {
        try {
            final byte[] bytes = ndr.getBuffer().getBuffer();
            if (count < 0 || count > 12 || offset < 0 || offset > bytes.length - count) {
                return "<unavailable>";
            }
            final StringBuilder out = new StringBuilder(count * 2);
            for (int i = offset; i < offset + count; i++) {
                final int value = bytes[i] & 255;
                out.append(Character.forDigit(value >>> 4, 16)).append(Character.forDigit(value & 15, 16));
            }
            return out.toString();
        } catch (RuntimeException ignored) { return "<unavailable>"; }
    }
}
