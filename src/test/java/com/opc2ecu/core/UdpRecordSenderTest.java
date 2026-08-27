package com.opc2ecu.core;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class UdpRecordSenderTest {
    @Test public void sendsOneRecordAs30Bytes(){final FakeChannel c=new FakeChannel();sender(c).sendCycle(values(1));assertEquals(30,c.sent.get(0).length);}
    @Test public void sends48RecordsAs1440Bytes(){final FakeChannel c=new FakeChannel();sender(c).sendCycle(values(48));assertEquals(1440,c.sent.get(0).length);}
    @Test public void splits49RecordsInto48AndOne(){final FakeChannel c=new FakeChannel();sender(c).sendCycle(values(49));assertArrayEquals(new int[]{1440,30},lengths(c));}
    @Test public void splits96RecordsIntoTwoFullPackets(){final FakeChannel c=new FakeChannel();sender(c).sendCycle(values(96));assertArrayEquals(new int[]{1440,1440},lengths(c));}
    @Test public void emptyCycleSendsNothing(){final FakeChannel c=new FakeChannel();sender(c).sendCycle(Collections.<OpcReadValue>emptyList());assertTrue(c.sent.isEmpty());}
    @Test public void countsPacketsAndRecords(){final FakeChannel c=new FakeChannel();final UdpRecordSender s=sender(c);s.sendCycle(values(49));assertEquals(2,s.getPacketsSent());assertEquals(49,s.getRecordsSent());}
    @Test public void failureIsCountedWithoutRetry(){final FakeChannel c=new FakeChannel();c.fail=true;final UdpRecordSender s=sender(c);s.sendCycle(values(2));assertEquals(1,s.getSendFailures());assertEquals(1,c.attempts);}
    @Test public void usesServerTimestampAndRawQuality(){final FakeChannel c=new FakeChannel();sender(c).sendCycle(values(1));final ByteBuffer b=ByteBuffer.wrap(c.sent.get(0)).order(ByteOrder.LITTLE_ENDIAN);b.position(24);assertEquals(1234,b.getInt());assertEquals(0xfedc,b.getShort()&0xffff);}
    @Test public void skipsNonNumericValueWithoutThrowing(){final FakeChannel c=new FakeChannel();final UdpRecordSender s=sender(c);s.sendCycle(Collections.singletonList(new OpcReadValue("x","bad",1,null)));assertTrue(c.sent.isEmpty());assertEquals(1,s.getRecordsSkipped());}
    @Test public void badRecordDoesNotBlockValidRecords(){final FakeChannel c=new FakeChannel();final UdpRecordSender s=sender(c);final List<OpcReadValue> v=new ArrayList<OpcReadValue>();v.add(new OpcReadValue("bad","text",192,null));v.addAll(values(2));s.sendCycle(v);assertEquals(1,c.sent.size());assertEquals(60,c.sent.get(0).length);assertEquals(2,s.getRecordsSent());assertEquals(1,s.getRecordsSkipped());}
    private UdpRecordSender sender(final FakeChannel c){return new UdpRecordSender(c,StandardCharsets.US_ASCII);}
    private List<OpcReadValue> values(final int n){final List<OpcReadValue>v=new ArrayList<OpcReadValue>();final Calendar t=Calendar.getInstance(TimeZone.getTimeZone("UTC"));t.setTimeInMillis(1234000L);for(int i=0;i<n;i++)v.add(new OpcReadValue("S.G.I"+i,Double.valueOf(i),0xfedc,t));return v;}
    private int[] lengths(final FakeChannel c){final int[]r=new int[c.sent.size()];for(int i=0;i<r.length;i++)r[i]=c.sent.get(i).length;return r;}
    static class FakeChannel implements DatagramChannel{final List<byte[]>sent=new ArrayList<byte[]>();int attempts;boolean fail;public void send(byte[]p)throws IOException{attempts++;if(fail)throw new IOException("drop");sent.add(p);}public byte[]receive()throws IOException{throw new IOException();}public void setReceiveTimeout(int m){}public void close(){}}
}
