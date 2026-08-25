package com.opc2ecu.utgard;

import com.opc2ecu.core.DiagCode;

import org.jinterop.dcom.common.JIException;
import org.junit.Test;

import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;

public class UtgardOpcDaClientDiagnosisTest {

    @Test public void jiExceptionHresultIsClassified() {
        final JIException cause = new JIException(0x80070005, "Access is denied");
        assertEquals(DiagCode.DCOM_ACCESS_DENIED, UtgardOpcDaClient.classify(cause));
    }

    @Test public void nestedCauseChainIsWalked() {
        final JIException root = new JIException(0x800706BA, "RPC server unavailable");
        final Exception mid = new IllegalStateException("wrapped", root);
        assertEquals(DiagCode.RPC_SERVER_UNAVAILABLE, UtgardOpcDaClient.classify(mid));
    }

    @Test public void socketTimeoutIsAttributedToAuthOrNetwork() {
        final Exception cause = new IllegalStateException("hung", new SocketTimeoutException("read timed out"));
        assertEquals(DiagCode.AUTH_OR_NETWORK_TIMEOUT, UtgardOpcDaClient.classify(cause));
    }

    @Test public void unknownFailureIsGeneric() {
        assertEquals(DiagCode.GENERIC_CONNECTION,
                UtgardOpcDaClient.classify(new RuntimeException("something else")));
    }
}
