package com.opc2ecu.utgard;

import com.opc2ecu.core.OpcDataCallback;
import com.opc2ecu.core.OpcReadValue;
import com.opc2ecu.core.QualityReason;
import java.util.*;
import org.jinterop.dcom.common.JIException;
import org.jinterop.dcom.core.JIVariant;
import org.openscada.opc.lib.da.ItemState;
import org.junit.Test;
import static org.junit.Assert.*;

public class IsolatedSyncAccessTest {
    @Test public void failedOrMissingItemStateDoesNotBlockOtherPoints() throws Exception {
        final Capture capture = new Capture();
        final FakeAccess access = new FakeAccess(capture);
        access.batch.put("a", state(1));
        final ItemState bad = state(2);
        bad.setErrorCode(0xC0040001);
        access.batch.put("bad", bad);
        access.batch.put("missing", null);
        access.batch.put("b", state(3));
        access.runOnce();
        assertEquals(Arrays.asList("a", "b"), capture.ids);
        assertEquals(Arrays.asList("start", "a", "b", "end"), capture.events);
        assertEquals(QualityReason.POINT_READ_FAILED, capture.failures.get("bad"));
        assertEquals(QualityReason.POINT_MISSING, capture.failures.get("missing"));
    }

    @Test public void identicalStatesAreDeliveredOnEveryRead() throws Exception {
        final Capture capture = new Capture();
        final FakeAccess access = new FakeAccess(capture);
        access.batch.put("a", state(1));
        access.runOnce();
        access.runOnce();
        assertEquals(Arrays.asList("a", "a"), capture.ids);
        assertEquals(2, capture.completed);
    }

    @Test public void callbackOrConversionFailureDoesNotAbortRemainingPoints() throws Exception {
        final Capture capture = new Capture();
        final FakeAccess access = new FakeAccess(capture);
        access.batch.put("a", state(1));
        access.batch.put("throws", state(2));
        access.batch.put("b", state(3));
        access.runOnce();
        assertEquals(Arrays.asList("a", "b"), capture.ids);
        assertEquals(1, capture.completed);
        assertEquals(QualityReason.SAMPLE_NOT_ENCODABLE, capture.failures.get("throws"));
    }

    @Test public void malformedBatchFallsBackToIndependentReads() throws Exception {
        final Capture capture = new Capture();
        final FakeAccess access = new FakeAccess(capture);
        access.malformed = true;
        access.register("a");
        access.register("bad");
        access.register("b");
        access.runOnce();
        assertEquals(new HashSet<String>(Arrays.asList("a", "b")), new HashSet<String>(capture.ids));
        assertEquals(2, capture.ids.size());
        assertEquals(3, access.singleReads);
        assertEquals(QualityReason.POINT_READ_FAILED, capture.failures.get("bad"));
        assertEquals(1, capture.completed);
        assertNull(capture.connectionFailure);
    }

    @Test public void wholeRpcFailurePreservesExceptionAndClosesReadBoundary() throws Exception {
        final Capture capture = new Capture();
        final FakeAccess access = new FakeAccess(capture);
        access.rpcFailure = true;
        try { access.runOnce(); fail("Expected whole RPC failure"); }
        catch (JIException e) { assertEquals(0x800706BA, e.getErrorCode()); }
        assertEquals(0, access.singleReads);
        assertTrue(capture.ids.isEmpty());
        assertEquals(1, capture.completed);
        assertNotNull(capture.connectionFailure);
        assertEquals(QualityReason.RPC_UNAVAILABLE, QualityReason.fromFailure(capture.connectionFailure));
    }

    @Test public void sharedRpcFailureDuringFallbackStopsFurtherPointReads() throws Exception {
        final Capture capture = new Capture();
        final FakeAccess access = new FakeAccess(capture);
        access.malformed = true;
        access.singleRpcFailure = true;
        access.register("a");
        access.register("b");
        try { access.runOnce(); fail("Expected shared RPC failure"); }
        catch (JIException e) { assertEquals(0x800706BA, e.getErrorCode()); }
        assertEquals(1, access.singleReads);
        assertEquals(1, capture.completed);
    }

    @Test public void badQualityNumericValueIsForwardedUnchanged() throws Exception {
        final Capture capture = new Capture();
        final FakeAccess access = new FakeAccess(capture);
        final ItemState value = state(1);
        value.setQuality((short) 0);
        access.batch.put("a", value);
        access.runOnce();
        assertEquals(0, capture.values.get(0).getQuality());
        assertEquals(1.0, ((Number) capture.values.get(0).getValue()).doubleValue(), 0.0);
    }

    private static ItemState state(double value) {
        return new ItemState(0, new JIVariant(value), Calendar.getInstance(), (short) 192);
    }

    private static class Capture implements OpcDataCallback {
        final List<String> ids = new ArrayList<String>();
        final List<String> events = new ArrayList<String>();
        final List<OpcReadValue> values = new ArrayList<OpcReadValue>();
        final Map<String, QualityReason> failures = new HashMap<String, QualityReason>();
        Throwable connectionFailure;
        int completed;
        public void onCycleStarted() { events.add("start"); }
        public void onData(OpcReadValue value) {
            if ("throws".equals(value.getItemId())) { throw new IllegalArgumentException("test conversion"); }
            ids.add(value.getItemId());
            events.add(value.getItemId());
            values.add(value);
        }
        public void onCycleComplete() { completed++; events.add("end"); }
        public void onPointFailure(String id, QualityReason reason) { failures.put(id, reason); }
        public void onConnectionLost(Throwable failure) {
            assertEquals("end", events.get(events.size()-1));
            connectionFailure = failure;
        }
    }

    private static class FakeAccess extends IsolatedSyncAccess {
        final Map<String, ItemState> batch = new LinkedHashMap<String, ItemState>();
        final List<String> bound = new ArrayList<String>();
        boolean malformed;
        boolean rpcFailure;
        boolean singleRpcFailure;
        int singleReads;
        FakeAccess(OpcDataCallback callback) throws Exception { super(null, 1000, callback); active = true; }
        void register(String item) { bound.add(item); }
        @Override protected List<String> boundItemIds() { return bound; }
        @Override protected void readBatch() throws JIException {
            if (rpcFailure) { throw new JIException(0x800706BA, "test RPC failure"); }
            if (malformed) { throw new NullPointerException("test null OPCITEMSTATE"); }
            for (Map.Entry<String, ItemState> entry : batch.entrySet()) { deliver(entry.getKey(), entry.getValue()); }
        }
        @Override protected ItemState readSingle(String item) throws JIException {
            singleReads++;
            if (singleRpcFailure) { throw new JIException(0x800706BA, "test shared RPC failure"); }
            if ("bad".equals(item)) { throw new JIException(0xC0040001, "test failed item"); }
            return state(1);
        }
    }
}
