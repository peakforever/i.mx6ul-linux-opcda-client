package com.opc2ecu.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DiagCodeTest {

    @Test public void knownHresultsMapToSpecificCodes() {
        assertEquals(DiagCode.DCOM_ACCESS_DENIED, DiagCode.fromHresult(0x80070005));
        assertEquals(DiagCode.RPC_SERVER_UNAVAILABLE, DiagCode.fromHresult(0x800706BA));
        assertEquals(DiagCode.RPC_ENDPOINT_NOT_FOUND, DiagCode.fromHresult(0x800706BF));
        assertEquals(DiagCode.RPC_SERVER_TOO_BUSY, DiagCode.fromHresult(0x800706D3));
        assertEquals(DiagCode.RPC_CALL_FAILED, DiagCode.fromHresult(0x800706BE));
        assertEquals(DiagCode.CLASS_NOT_REGISTERED, DiagCode.fromHresult(0x80040154));
    }

    @Test public void unknownHresultFallsBackToUnknown() {
        assertEquals(DiagCode.UNKNOWN, DiagCode.fromHresult(0x12345678));
    }

    @Test public void bareWin32CodesMapLikeTheirHresultForm() {
        // J-Interop sometimes reports 0x00000005 instead of 0x80070005
        assertEquals(DiagCode.DCOM_ACCESS_DENIED, DiagCode.fromHresult(0x00000005));
        assertEquals(DiagCode.RPC_SERVER_UNAVAILABLE, DiagCode.fromHresult(1722)); // 0x6BA
        assertEquals(DiagCode.RPC_ENDPOINT_NOT_FOUND, DiagCode.fromHresult(1727)); // 0x6BF
    }

    @Test public void timeoutAttributionIsAuthOrNetwork() {
        assertEquals(DiagCode.AUTH_OR_NETWORK_TIMEOUT, DiagCode.timeout());
    }

    @Test public void codeIsStableMachineReadableName() {
        assertEquals("OPC_E_DCOM_ACCESS_DENIED", DiagCode.DCOM_ACCESS_DENIED.code());
    }

    @Test public void hresultPresentOnlyWhereApplicable() {
        assertEquals(Integer.valueOf(0x80070005), DiagCode.DCOM_ACCESS_DENIED.hresult());
        assertNull(DiagCode.AUTH_OR_NETWORK_TIMEOUT.hresult());
    }
}
