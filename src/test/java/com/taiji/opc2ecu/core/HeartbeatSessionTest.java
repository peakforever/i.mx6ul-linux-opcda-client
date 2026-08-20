package com.taiji.opc2ecu.core;

import org.junit.After;
import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static org.junit.Assert.*;

public class HeartbeatSessionTest {
    private final List<HeartbeatSession> sessions=new ArrayList<HeartbeatSession>();
    @After public void close(){for(HeartbeatSession s:sessions)s.close();}
    @Test public void sessionIdsIncrement(){final HeartbeatSession s=session(new FakeChannel(),new Events());assertEquals(0,s.sendHeartbeat());assertEquals(1,s.sendHeartbeat());}
    @Test public void sessionIdWrapsToZero(){final HeartbeatSession s=session(new FakeChannel(),new Events());s.setNextSessionId(0xffffffffL);assertEquals(0xffffffffL,s.sendHeartbeat());assertEquals(0,s.sendHeartbeat());}
    @Test public void requestIsLittleEndian(){final FakeChannel c=new FakeChannel();final HeartbeatSession s=session(c,new Events());s.setNextSessionId(0x01020304L);s.sendHeartbeat();assertArrayEquals(new byte[]{4,3,2,1},c.sent.get(0));}
    @Test public void matchingResponseAccepted(){final HeartbeatSession s=session(new FakeChannel(),new Events());long id=s.sendHeartbeat();assertTrue(s.acceptResponse(response(id)));assertEquals(0,s.getConsecutiveFailures());}
    @Test public void wrongLengthIgnored(){final HeartbeatSession s=session(new FakeChannel(),new Events());s.sendHeartbeat();assertFalse(s.acceptResponse(new byte[3]));}
    @Test public void wrongIdIgnored(){final HeartbeatSession s=session(new FakeChannel(),new Events());s.sendHeartbeat();assertFalse(s.acceptResponse(response(4)));}
    @Test public void timeoutIncrementsFailures(){final HeartbeatSession s=session(new FakeChannel(),new Events());long id=s.sendHeartbeat();s.onTimeout(id);assertEquals(1,s.getConsecutiveFailures());}
    @Test public void staleTimeoutIgnored(){final HeartbeatSession s=session(new FakeChannel(),new Events());long id=s.sendHeartbeat();s.acceptResponse(response(id));s.onTimeout(id);assertEquals(0,s.getConsecutiveFailures());}
    @Test public void threeTimeoutsMarkOfflineOnce(){final Events e=new Events();final HeartbeatSession s=session(new FakeChannel(),e);for(int i=0;i<3;i++){long id=s.sendHeartbeat();s.onTimeout(id);}assertFalse(s.isOnline());assertEquals(1,e.offline);}
    @Test public void validResponseRecoversOffline(){final Events e=new Events();final HeartbeatSession s=session(new FakeChannel(),e);for(int i=0;i<3;i++){long id=s.sendHeartbeat();s.onTimeout(id);}long id=s.sendHeartbeat();assertTrue(s.acceptResponse(response(id)));assertTrue(s.isOnline());assertEquals(1,e.recovered);}
    @Test public void offlineSessionKeepsSending(){final FakeChannel c=new FakeChannel();final HeartbeatSession s=session(c,new Events());for(int i=0;i<4;i++){long id=s.sendHeartbeat();s.onTimeout(id);}assertEquals(4,c.sent.size());}
    @Test public void validResponseResetsFailureCount(){final HeartbeatSession s=session(new FakeChannel(),new Events());long a=s.sendHeartbeat();s.onTimeout(a);long b=s.sendHeartbeat();s.acceptResponse(response(b));assertEquals(0,s.getConsecutiveFailures());}
    private HeartbeatSession session(FakeChannel c,Events e){HeartbeatSession s=new HeartbeatSession(c,e);sessions.add(s);return s;}
    private byte[] response(long id){return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)id).array();}
    static class Events implements HeartbeatSession.StateListener{int offline,recovered;public void onOffline(){offline++;}public void onRecovered(){recovered++;}}
    static class FakeChannel implements DatagramChannel{final List<byte[]>sent=new ArrayList<byte[]>();public void send(byte[]p){sent.add(p);}public byte[]receive()throws IOException{throw new IOException("none");}public void setReceiveTimeout(int m){}public void close(){}}
}
