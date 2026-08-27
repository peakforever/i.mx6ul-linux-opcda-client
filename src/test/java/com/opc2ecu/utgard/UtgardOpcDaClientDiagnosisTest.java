package com.opc2ecu.utgard;

import com.opc2ecu.core.DiagCode;

import org.jinterop.dcom.common.JIException;
import org.jinterop.dcom.core.JIArray;
import org.jinterop.dcom.core.JICurrency;
import org.jinterop.dcom.core.JIVariant;
import org.junit.Test;

import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test public void unwrapsKepwareFloatVariant() {
        final Object value = UtgardOpcDaClient.unwrapScalar(new JIVariant(789.0f));
        assertTrue(value instanceof Float);
        assertEquals(789.0, ((Number) value).doubleValue(), 0.0);
    }

    @Test public void unwrapsDoubleVariant() {
        final Object value = UtgardOpcDaClient.unwrapScalar(new JIVariant(789.25));
        assertTrue(value instanceof Double);
        assertEquals(789.25, ((Number) value).doubleValue(), 0.0);
    }

    @Test public void unwrapsCurrencyVariantAsNumber() {
        final Object value = UtgardOpcDaClient.unwrapScalar(
                new JIVariant(new JICurrency(12, 3456)));
        assertTrue(value instanceof Number);
        assertEquals(12.3456, ((Number) value).doubleValue(), 0.0);
    }

    @Test public void leavesRealOpcArrayNonNumeric() {
        final JIArray array = new JIArray(new Float[] { Float.valueOf(789.0f) }, true);
        final Object value = UtgardOpcDaClient.unwrapScalar(new JIVariant(array));
        assertTrue(value instanceof JIArray);
    }
}
