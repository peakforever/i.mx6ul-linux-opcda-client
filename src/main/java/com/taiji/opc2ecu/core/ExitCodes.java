package com.taiji.opc2ecu.core;

public final class ExitCodes {
    public static final int SUCCESS = 0;
    public static final int INTERNAL_ERROR = 1;
    public static final int CONFIGURATION_ERROR = 2;
    public static final int CONNECTION_ERROR = 3;
    public static final int READ_ERROR = 4;
    public static final int TIMEOUT = 5;

    private ExitCodes() {
    }

    public static int forException(final Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return CONFIGURATION_ERROR;
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof OpcDaException) {
                final OpcDaException.Kind kind = ((OpcDaException) current).getKind();
                if (kind == OpcDaException.Kind.CONNECTION) { return CONNECTION_ERROR; }
                if (kind == OpcDaException.Kind.READ) { return READ_ERROR; }
                if (kind == OpcDaException.Kind.TIMEOUT) { return TIMEOUT; }
                return INTERNAL_ERROR;
            }
            current = current.getCause();
        }
        return INTERNAL_ERROR;
    }
}
