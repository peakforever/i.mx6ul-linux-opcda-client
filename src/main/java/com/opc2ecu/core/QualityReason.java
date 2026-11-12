package com.opc2ecu.core;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.jinterop.dcom.common.JIException;

/** Application-owned high byte; the low byte remains standard OPC DA quality. */
public enum QualityReason {
    NONE(0x00), WAITING_FOR_DATA(0x01),
    HOST_RESOLUTION_FAILED(0x10), HOST_UNREACHABLE(0x11), CONNECT_FAILED(0x12), TIMEOUT(0x13),
    AUTHENTICATION_FAILED(0x20), ACCESS_DENIED(0x21), CLASS_NOT_REGISTERED(0x22),
    RPC_UNAVAILABLE(0x23), RPC_CALL_FAILED(0x24), SERVER_BUSY(0x25), PROTOCOL_ERROR(0x26),
    POINT_MISSING(0x30), POINT_READ_FAILED(0x31), SAMPLE_NOT_ENCODABLE(0x32), READ_STALE(0x33),
    OTHER_FAILURE(0xff);

    private final int code;

    QualityReason(final int code) { this.code = code; }

    public int code() { return code; }

    public int withOpcQuality(final int opcQuality) {
        return (code << 8) | (opcQuality & 0xff);
    }

    /** Classify evidence, never guess a password failure from AccessDenied or a timeout. */
    public static QualityReason fromFailure(final Throwable failure) {
        QualityReason fallback = OTHER_FAILURE;
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof JIException) {
                final QualityReason exact = fromHresult(((JIException) current).getErrorCode());
                if (exact != OTHER_FAILURE) { return exact; }
            }
            if (current instanceof UnknownHostException) { return HOST_RESOLUTION_FAILED; }
            if (current instanceof NoRouteToHostException) { return HOST_UNREACHABLE; }
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) { return TIMEOUT; }
            if (current instanceof ConnectException) {
                final String message = current.getMessage();
                final String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
                return normalized.contains("timed out") || normalized.contains("timeout")
                        ? TIMEOUT : CONNECT_FAILED;
            }
            if (current instanceof OpcDaException) {
                final OpcDaException opc = (OpcDaException) current;
                final QualityReason diagnostic = fromDiagnostic(opc.getDiagCode());
                if (diagnostic != OTHER_FAILURE) { fallback = diagnostic; }
                else if (fallback == OTHER_FAILURE && opc.getKind() == OpcDaException.Kind.TIMEOUT) {
                    fallback = TIMEOUT;
                } else if (fallback == OTHER_FAILURE && opc.getKind() == OpcDaException.Kind.READ) {
                    fallback = POINT_READ_FAILED;
                }
            }
        }
        return fallback;
    }

    public static QualityReason fromHresult(final int error) {
        if (error == 0x8007052e || error == 1326 || error == 0x8009030c) {
            return AUTHENTICATION_FAILED;
        }
        return fromDiagnostic(DiagCode.fromHresult(error));
    }

    private static QualityReason fromDiagnostic(final DiagCode diagnostic) {
        switch (diagnostic) {
        case HOST_RESOLUTION_FAILED: return HOST_RESOLUTION_FAILED;
        case HOST_UNREACHABLE: return HOST_UNREACHABLE;
        case SOCKET_CONNECT_FAILED: return CONNECT_FAILED;
        case AUTH_OR_NETWORK_TIMEOUT: return TIMEOUT;
        case READ_STALE: return READ_STALE;
        case DCOM_ACCESS_DENIED: return ACCESS_DENIED;
        case CLASS_NOT_REGISTERED: return CLASS_NOT_REGISTERED;
        case RPC_SERVER_UNAVAILABLE:
        case RPC_ENDPOINT_NOT_FOUND: return RPC_UNAVAILABLE;
        case RPC_CALL_FAILED:
        case RPC_CALL_NOT_EXECUTED: return RPC_CALL_FAILED;
        case RPC_SERVER_TOO_BUSY: return SERVER_BUSY;
        case RPC_INVALID_HEADER:
        case RPC_AUTHENTICATION_UNSUPPORTED: return PROTOCOL_ERROR;
        default: return OTHER_FAILURE;
        }
    }
}
