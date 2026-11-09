package com.opc2ecu.core;

public final class OpcDaException extends Exception {
    public enum Kind { CONNECTION, READ, TIMEOUT, INTERNAL }

    private final Kind kind;
    private final DiagCode diagCode;

    public OpcDaException(final Kind kind, final String message) {
        super(message);
        this.kind = kind;
        this.diagCode = DiagCode.GENERIC_CONNECTION;
    }

    public OpcDaException(final Kind kind, final String message, final Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.diagCode = DiagCode.GENERIC_CONNECTION;
    }

    public OpcDaException(final Kind kind, final DiagCode diagCode, final Throwable cause) {
        this(kind, diagCode, cause, "unspecified");
    }

    public OpcDaException(final Kind kind, final DiagCode diagCode,
            final Throwable cause, final String stage) {
        super("OPC DA connection failed: " + diagCode.detail()
                + "; stage=" + stage + evidence(cause), cause);
        this.kind = kind;
        this.diagCode = diagCode;
    }

    /** Preserve diagnostic evidence without copying potentially credential-bearing messages. */
    private static String evidence(final Throwable cause) {
        final StringBuilder result = new StringBuilder("; causes=");
        final java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        Throwable cursor = cause;
        while (cursor != null && seen.add(cursor)) {
            if (cursor != cause) { result.append(" -> "); }
            result.append(cursor.getClass().getName());
            if (cursor instanceof org.jinterop.dcom.common.JIException) {
                result.append(String.format("[rawCode=0x%08X]",
                        ((org.jinterop.dcom.common.JIException) cursor).getErrorCode()));
            }
            final StackTraceElement[] frames = cursor.getStackTrace();
            if (frames.length > 0) { result.append("@").append(frames[0]); }
            cursor = cursor.getCause();
        }
        if (cause == null) { result.append("<none>"); }
        return result.toString();
    }

    public Kind getKind() {
        return kind;
    }

    /** Attribution code; GENERIC_CONNECTION when no specific diagnosis was possible. */
    public DiagCode getDiagCode() {
        return diagCode;
    }
}
