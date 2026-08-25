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
        super("OPC DA connection failed: " + diagCode.detail(), cause);
        this.kind = kind;
        this.diagCode = diagCode;
    }

    public Kind getKind() {
        return kind;
    }

    /** Attribution code; GENERIC_CONNECTION when no specific diagnosis was possible. */
    public DiagCode getDiagCode() {
        return diagCode;
    }
}
