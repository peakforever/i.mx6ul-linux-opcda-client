package com.opc2ecu.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ExitCodesTest {
    @Test public void mapsConfigurationError() {
        assertEquals(2, ExitCodes.forException(new IllegalArgumentException("bad config")));
    }

    @Test public void mapsConnectionError() {
        assertEquals(3, ExitCodes.forException(
                new OpcDaException(OpcDaException.Kind.CONNECTION, "down")));
    }

    @Test public void mapsReadError() {
        assertEquals(4, ExitCodes.forException(
                new OpcDaException(OpcDaException.Kind.READ, "bad item")));
    }

    @Test public void mapsTimeout() {
        assertEquals(5, ExitCodes.forException(
                new OpcDaException(OpcDaException.Kind.TIMEOUT, "late")));
    }

    @Test public void mapsUnknownErrorToInternal() {
        assertEquals(1, ExitCodes.forException(new RuntimeException("unknown")));
    }
}
