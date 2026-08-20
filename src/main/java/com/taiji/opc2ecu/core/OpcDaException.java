package com.taiji.opc2ecu.core;

public final class OpcDaException extends Exception {
    public enum Kind { CONNECTION, READ, TIMEOUT, INTERNAL }

    private final Kind kind;

    public OpcDaException(final Kind kind, final String message) {
        super(message);
        this.kind = kind;
    }

    public OpcDaException(final Kind kind, final String message, final Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
