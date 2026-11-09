package org.jinterop.dcom.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import ndr.NdrBuffer;
import ndr.NetworkDataRepresentation;
import org.jinterop.dcom.common.JIRuntimeException;

/** Runs with only the packaged jar and test-classes on the classpath; no JUnit dependency. */
public final class ActivationPackagedSmoke {
    public static void main(String[] args) {
        System.setProperty("opc2ecu.activationTrace", "true");
        JIRemActivation a = new JIRemActivation("12345678-1234-5678-90ab-123456789abc");
        NetworkDataRepresentation request = new NetworkDataRepresentation();
        request.setBuffer(new NdrBuffer(new byte[4096], 0));
        a.write(request);
        boolean omit = Boolean.parseBoolean(System.getProperty("opc2ecu.activationOmitLegacyTail", "false"));
        if (request.buf.index != (omit ? 118 : 128)) throw new AssertionError("Unexpected request length");
        ByteBuffer b = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0).putInt(0).putLong(1).putInt(0).putLong(0).putLong(0);
        b.putInt(6).putShort((short)5).putShort((short)7).putInt(0x80010111);
        NetworkDataRepresentation response = new NetworkDataRepresentation();
        response.setBuffer(new NdrBuffer(b.array(), 0));
        try { a.read(response); throw new AssertionError("Expected HRESULT failure"); }
        catch (JIRuntimeException expected) {
            if (expected.getHResult() != 0x80010111 || response.buf.index != 48) {
                throw new AssertionError("Changed protocol result");
            }
        }
        System.out.println("[SMOKE] activation class="
                + JIRemActivation.class.getProtectionDomain().getCodeSource().getLocation()
                + " result=PASS");
    }
}
