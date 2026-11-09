package org.jinterop.dcom.core;

import com.opc2ecu.utgard.ActivationTrace;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import ndr.NdrBuffer;
import ndr.NetworkDataRepresentation;
import org.jinterop.dcom.common.JIRuntimeException;
import org.junit.*;
import static org.junit.Assert.*;

public class ActivationDiagnosticsTest {
    private static final String CLSID = "12345678-1234-5678-90ab-123456789abc";
    private String previous;
    private String previousTail;
    private PrintStream stderr;
    private ByteArrayOutputStream output;
    @Before public void setup() throws Exception {
        previousTail = System.getProperty(ActivationTrace.OMIT_LEGACY_TAIL_PROPERTY);
        System.clearProperty(ActivationTrace.OMIT_LEGACY_TAIL_PROPERTY);
        previous = System.getProperty(ActivationTrace.PROPERTY);
        System.setProperty(ActivationTrace.PROPERTY, "true");
        stderr = System.err;
        output = new ByteArrayOutputStream();
        System.setErr(new PrintStream(output, true, "UTF-8"));
    }
    @After public void restore() {
        if (previousTail == null) System.clearProperty(ActivationTrace.OMIT_LEGACY_TAIL_PROPERTY);
        else System.setProperty(ActivationTrace.OMIT_LEGACY_TAIL_PROPERTY, previousTail);
        System.setErr(stderr);
        if (previous == null) System.clearProperty(ActivationTrace.PROPERTY);
        else System.setProperty(ActivationTrace.PROPERTY, previous);
    }
    private String logs() throws Exception { return output.toString("UTF-8"); }
    private static NetworkDataRepresentation ndr(byte[] bytes) {
        NetworkDataRepresentation n = new NetworkDataRepresentation();
        n.setBuffer(new NdrBuffer(bytes, 0));
        return n;
    }
    private static byte[] request() {
        NetworkDataRepresentation n = ndr(new byte[4096]);
        new JIRemActivation(CLSID).write(n);
        return Arrays.copyOf(n.buf.buf, n.buf.index);
    }
    private static byte[] normalized(byte[] bytes) {
        byte[] copy = bytes.clone();
        Arrays.fill(copy, 12, 28, (byte)0); // random ORPC causality UUID
        Arrays.fill(copy, 68, 72, (byte)0); // unique pointer referent
        return copy;
    }
    @Test public void actualRequestMatchesOriginalLibraryAndTraceOff() throws Exception {
        byte[] on = request();
        assertTrue(logs().contains("event=request"));
        assertTrue(logs().contains("clsid=" + CLSID));
        assertTrue(logs().contains("interfaces=IUnknown,IDispatch protseq=7"));
        ByteBuffer b = ByteBuffer.wrap(on).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, b.getInt(4)); // ORPC flags
        assertEquals(0, b.getInt(8)); // reserved
        assertEquals(0, b.getInt(28)); // no extensions
        assertEquals(0x12345678, b.getInt(32)); // actual CLSID
        assertEquals(3, b.getInt(56));
        assertEquals(0, b.getInt(60));
        assertEquals(2, b.getInt(64));
        assertEquals(7, b.getShort(116));
        output.reset();
        System.setProperty(ActivationTrace.PROPERTY, "false");
        assertArrayEquals(normalized(on), normalized(request()));
        assertEquals("", logs());
        try (URLClassLoader original = originalLoader()) {
            Object a = activation(original);
            Object n = originalNdr(original, new byte[4096]);
            invoke(a, "write", n.getClass(), n);
            Object buffer = n.getClass().getMethod("getBuffer").invoke(n);
            byte[] bytes = (byte[])buffer.getClass().getField("buf").get(buffer);
            int index = buffer.getClass().getField("index").getInt(buffer);
            assertArrayEquals(normalized(on), normalized(Arrays.copyOf(bytes, index)));
        }
    }
    @Test public void experimentChangesOnlyTenByteTail() throws Exception {
        byte[] baseline = request();
        assertEquals(128, baseline.length);
        assertTrue(logs().contains("revision=2"));
        assertTrue(logs().contains("omitLegacyTail=false"));
        System.setProperty(ActivationTrace.OMIT_LEGACY_TAIL_PROPERTY, "false");
        assertArrayEquals(normalized(baseline), normalized(request()));
        System.setProperty(ActivationTrace.OMIT_LEGACY_TAIL_PROPERTY, "true");
        ActivationTrace.validateProperty();
        byte[] experiment = request();
        assertEquals(118, experiment.length);
        assertArrayEquals(Arrays.copyOf(normalized(baseline), 118), normalized(experiment));
        assertTrue(logs().contains("omitLegacyTail=true"));
        System.setProperty(ActivationTrace.PROPERTY, "false");
        assertArrayEquals(normalized(experiment), normalized(request()));
    }
    @Test public void experimentKeepsResponseSemantics() throws Exception {
        System.setProperty(ActivationTrace.OMIT_LEGACY_TAIL_PROPERTY, "true");
        successfulResponseTraversesRealInterfacePointerDecoder();
        invalidHeaderPreservesCodeAndReportsExactFieldBytes();
    }
    @Test public void invalidExperimentFlagIsRejected() {
        System.setProperty(ActivationTrace.OMIT_LEGACY_TAIL_PROPERTY, "typo");
        try { ActivationTrace.validateProperty(); fail(); }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains(ActivationTrace.OMIT_LEGACY_TAIL_PROPERTY));
        }
    }

    private static byte[] response(int hr, boolean bindings, boolean success) {
        ByteBuffer b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0).putInt(0); // ORPCTHAT
        b.putLong(1).putInt(bindings ? 1 : 0);
        if (bindings) {
            b.putInt(4); // conformant ushort array length
            b.putShort((short)4).putShort((short)3);
            b.putShort((short)7).putShort((short)0).putShort((short)0).putShort((short)0);
        }
        b.putLong(0).putLong(0); // remote unknown IPID
        b.putInt(6).putShort((short)5).putShort((short)7).putInt(hr);
        if (success) {
            b.putInt(2).putInt(1).putInt(0); // interface array, non-null IUnknown, null IDispatch
            b.putInt(72).putInt(72); // MInterfacePointer conformance and byte count
            b.putInt(0x574f454d).putInt(1); // MEOW, OBJREF_STANDARD
            b.putLong(0).putLong(0x46000000000000c0L); // IUnknown IID
            b.putInt(0).putInt(5).putLong(1).putLong(2); // STDOBJREF
            b.putLong(0).putLong(0); // IPID
            b.putShort((short)2).putShort((short)1).putShort((short)0).putShort((short)0);
            b.putInt(2).putInt(0).putInt(0); // per-interface results
        }
        return Arrays.copyOf(b.array(), b.position());
    }
    @Test public void invalidHeaderPreservesCodeAndReportsExactFieldBytes() throws Exception {
        for (boolean bindings : new boolean[]{false, true}) {
            byte[] bytes = response(0x80010111, bindings, false);
            NetworkDataRepresentation n = ndr(bytes);
            try { new JIRemActivation(CLSID).read(n); fail(); }
            catch (JIRuntimeException e) { assertEquals(0x80010111, e.getHResult()); }
            assertTrue(logs().contains("hresultBytes=11010180"));
            assertTrue(logs().contains("hresultOffset=" + (bytes.length - 4)));
            assertTrue(logs().contains("stage=response-hresult"));
            assertOriginalOutcome(bytes, JIRuntimeException.class.getName(), n.buf.index, false);
        }
    }
    @Test public void successfulResponseTraversesRealInterfacePointerDecoder() throws Exception {
        byte[] bytes = response(0, false, true);
        NetworkDataRepresentation n = ndr(bytes);
        JIRemActivation a = new JIRemActivation(CLSID);
        a.read(n);
        assertTrue(a.isActivationSuccessful());
        assertNotNull(a.getMInterfacePointer());
        assertEquals(bytes.length, n.buf.index);
        assertTrue(logs().contains("event=response-complete"));
        assertOriginalOutcome(bytes, null, n.buf.index, true);
    }
    @Test public void everyTruncationPreservesOriginalExceptionAndIndex() throws Exception {
        byte[] full = response(0x80010111, false, false);
        for (int length = 0; length < full.length; length++) {
            byte[] bytes = Arrays.copyOf(full, length);
            NetworkDataRepresentation n = ndr(bytes);
            String failure = null;
            try { new JIRemActivation(CLSID).read(n); }
            catch (RuntimeException e) { failure = e.getClass().getName(); }
            assertNotNull("truncation " + length, failure);
            assertOriginalOutcome(bytes, failure, n.buf.index, false);
        }
        assertTrue(logs().contains("stage=response-orpc"));
        assertTrue(logs().contains("stage=response-hresult"));
    }
    @Test public void invalidOrpcHeaderDoesNotMasqueradeAsHresult() throws Exception {
        byte[] bytes = response(0, false, false);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x40000000);
        NetworkDataRepresentation n = ndr(bytes);
        try { new JIRemActivation(CLSID).read(n); fail(); }
        catch (RuntimeException e) {
            assertOriginalOutcome(bytes, e.getClass().getName(), n.buf.index, false);
        }
        assertTrue(logs().contains("stage=response-orpc"));
        assertFalse(logs().contains("hresultBytes="));
    }
    @Test public void traceIsBoundedAndDoesNotDumpObjectNameOrTrailingBytes() throws Exception {
        JIRemActivation a = new JIRemActivation(CLSID);
        a.setfileMonikerAtServer("private-secret");
        for (int i = 0; i < 20; i++) a.write(ndr(new byte[4096]));
        assertEquals(8, activationLines().size());
        assertFalse(logs().contains("private-secret"));
        for (String line : activationLines()) assertTrue(line.length() <= 2048);
    }
    @Test public void responseDoesNotExposeTrailingSecretBytes() throws Exception {
        byte[] response = response(0x80010111, false, false);
        byte[] secret = "private-password-and-NTLM-token".getBytes("UTF-8");
        byte[] bytes = Arrays.copyOf(response, response.length + secret.length);
        System.arraycopy(secret, 0, bytes, response.length, secret.length);
        NetworkDataRepresentation n = ndr(bytes);
        try { new JIRemActivation(CLSID).read(n); fail(); }
        catch (JIRuntimeException expected) { assertEquals(0x80010111, expected.getHResult()); }
        assertEquals(response.length, n.buf.index);
        assertFalse(logs().contains("private-password"));
        assertFalse(logs().contains("70726976617465"));
    }
    @Test public void diagnosticOutputFailurePreservesSuccessAndOriginalError() throws Exception {
        PrintStream capture = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()) {
            @Override public void println(String line) { throw new IllegalStateException("broken sink"); }
        });
        try {
            JIRemActivation success = new JIRemActivation(CLSID);
            success.read(ndr(response(0, false, true)));
            assertTrue(success.isActivationSuccessful());
            try { new JIRemActivation(CLSID).read(ndr(response(0x80010111, false, false))); fail(); }
            catch (JIRuntimeException expected) { assertEquals(0x80010111, expected.getHResult()); }
            ActivationTrace trace = new ActivationTrace();
            trace.observe(() -> { throw new IllegalStateException("field formatting failure"); });
        } finally { System.setErr(capture); }
    }

    @Test public void propertyValidationAndDefaultOff() throws Exception {
        System.clearProperty(ActivationTrace.PROPERTY);
        ActivationTrace.validateProperty();
        request();
        assertEquals("", logs());
        System.setProperty(ActivationTrace.PROPERTY, "typo");
        try { ActivationTrace.validateProperty(); fail(); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("typo")); }
    }
    private List<String> activationLines() throws Exception {
        List<String> lines = new ArrayList<String>();
        for (String line : logs().split("\\r?\\n")) {
            if (line.startsWith("[ACTIVATION]")) lines.add(line);
        }
        return lines;
    }
    private static URLClassLoader originalLoader() throws Exception {
        List<URL> jars = new ArrayList<URL>();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.endsWith(".jar")) jars.add(new File(entry).toURI().toURL());
        }
        return new URLClassLoader(jars.toArray(new URL[0]), null);
    }
    private static Object activation(ClassLoader loader) throws Exception {
        Constructor<?> c = loader.loadClass("org.jinterop.dcom.core.JIRemActivation").getDeclaredConstructor(String.class);
        c.setAccessible(true);
        return c.newInstance(CLSID);
    }
    private static Object originalNdr(ClassLoader loader, byte[] bytes) throws Exception {
        Class<?> b = loader.loadClass("ndr.NdrBuffer");
        Object buffer = b.getConstructor(byte[].class, int.class).newInstance(bytes.clone(), 0);
        Object n = loader.loadClass("ndr.NetworkDataRepresentation").getConstructor().newInstance();
        n.getClass().getMethod("setBuffer", b).invoke(n, buffer);
        return n;
    }
    private static void invoke(Object a, String method, Class<?> parameter, Object arg) throws Exception {
        Method m = a.getClass().getDeclaredMethod(method, parameter);
        m.setAccessible(true);
        m.invoke(a, arg);
    }
    private static void assertOriginalOutcome(byte[] bytes, String exception, int index, boolean success) throws Exception {
        try (URLClassLoader loader = originalLoader()) {
            Object a = activation(loader);
            Object n = originalNdr(loader, bytes);
            String failure = null;
            try { invoke(a, "read", n.getClass(), n); }
            catch (InvocationTargetException e) { failure = e.getCause().getClass().getName(); }
            assertEquals(exception, failure);
            Object buffer = n.getClass().getMethod("getBuffer").invoke(n);
            assertEquals(index, buffer.getClass().getField("index").getInt(buffer));
            Method m = a.getClass().getDeclaredMethod("isActivationSuccessful");
            m.setAccessible(true);
            assertEquals(success, m.invoke(a));
        }
    }
}
