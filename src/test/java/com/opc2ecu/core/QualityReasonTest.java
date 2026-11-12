package com.opc2ecu.core;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;
import org.jinterop.dcom.common.JIException;
import org.junit.Test;
import static org.junit.Assert.*;

public class QualityReasonTest {
    @Test public void applicationByteNeverChangesOpcLowByte() {
        final Set<Integer> codes = new HashSet<Integer>();
        for (QualityReason reason : QualityReason.values()) {
            assertTrue(codes.add(reason.code()));
            assertTrue(reason.code() >= 0 && reason.code() <= 255);
            for (int low = 0; low < 256; low++) {
                final int encoded = reason.withOpcQuality(0xab00 | low);
                assertEquals(low, encoded & 0xff);
                assertEquals(reason.code(), encoded >>> 8);
                assertEquals(low & 0xc0, encoded & 0xc0);
            }
        }
    }

    @Test public void accessDeniedIsNotMisreportedAsWrongPassword() {
        assertEquals(QualityReason.ACCESS_DENIED, QualityReason.fromHresult(0x80070005));
        assertEquals(QualityReason.ACCESS_DENIED, QualityReason.fromHresult(5));
        assertEquals(QualityReason.OTHER_FAILURE,
                QualityReason.fromFailure(new RuntimeException("wrong password, access denied")));
    }

    @Test public void explicitLogonErrorsMapToAuthentication() {
        for (int error : new int[] {0x8007052e, 1326, 0x8009030c}) {
            assertEquals(QualityReason.AUTHENTICATION_FAILED,
                    QualityReason.fromFailure(new JIException(error, "synthetic")));
        }
    }

    @Test public void underlyingConnectTimeoutSurvivesGenericComWrapper() {
        final JIException wrapper = new JIException(0x8001ffff, new ConnectException("Connection timed out"));
        assertEquals(QualityReason.TIMEOUT, QualityReason.fromFailure(wrapper));
        assertEquals(QualityReason.TIMEOUT,
                QualityReason.fromFailure(new SocketTimeoutException("synthetic")));
        assertEquals(QualityReason.CONNECT_FAILED,
                QualityReason.fromFailure(new ConnectException("Connection refused")));
    }

    @Test public void networkFaultsDoNotGuessFirewallOrCredentials() {
        assertEquals(QualityReason.HOST_RESOLUTION_FAILED,
                QualityReason.fromFailure(new UnknownHostException("synthetic")));
        assertEquals(QualityReason.HOST_UNREACHABLE,
                QualityReason.fromFailure(new NoRouteToHostException("synthetic")));
    }

    @Test public void knownRpcAndRegistrationEvidenceHasStableCodes() {
        final int[][] cases = {{0x80040154, 0x22}, {0x800706ba, 0x23}, {0x800706d9, 0x23},
                {0x800706be, 0x24}, {0x800706bf, 0x24}, {0x800706bb, 0x25},
                {0x800706d3, 0x26}, {0x80010111, 0x26}};
        for (int[] row : cases) {
            assertEquals(row[1], QualityReason.fromFailure(new JIException(row[0], "synthetic")).code());
        }
    }

    @Test public void diagnosticFallbackPreservesReadWatchdogAndDoesNotBeatSpecificCause() {
        assertEquals(QualityReason.READ_STALE, QualityReason.fromFailure(new OpcDaException(
                OpcDaException.Kind.TIMEOUT, DiagCode.READ_STALE, null, "test")));
        assertEquals(QualityReason.TIMEOUT, QualityReason.fromFailure(new OpcDaException(
                OpcDaException.Kind.CONNECTION, DiagCode.SOCKET_CONNECT_FAILED,
                new ConnectException("Connection timed out"))));
        assertEquals(QualityReason.POINT_READ_FAILED, QualityReason.fromFailure(new OpcDaException(
                OpcDaException.Kind.READ, "synthetic")));
    }

    @Test public void unknownAndCyclicCausesTerminateWithoutInventingAReason() {
        final RuntimeException first = new RuntimeException();
        final RuntimeException second = new RuntimeException();
        first.initCause(second); second.initCause(first);
        assertEquals(QualityReason.OTHER_FAILURE, QualityReason.fromFailure(first));
        assertEquals(QualityReason.OTHER_FAILURE, QualityReason.fromFailure(null));
    }
}
