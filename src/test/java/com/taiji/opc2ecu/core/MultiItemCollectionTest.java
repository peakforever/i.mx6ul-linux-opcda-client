package com.taiji.opc2ecu.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class MultiItemCollectionTest {
    @Test public void reconnectBindsEveryItemToFreshClients() throws Exception {
        final CapturingFactory factory=new CapturingFactory();
        final ReconnectManager manager=new ReconnectManager(factory,new ReconnectPolicy(1,1,1),1000,true,
                new ReconnectManager.TimeSource(){long now;public long nowMillis(){return now+=1000;}},
                new ReconnectManager.Sleeper(){public void sleep(long m){}},
                new ReconnectManager.JitterSource(){public double nextDouble(){return 0;}},
                null);
        final List<String> items=Arrays.asList("a","b","c");
        manager.start(items,new OpcDataCallback(){public void onData(OpcReadValue value){}});
        manager.handleFailure(new RuntimeException("down"));
        assertEquals(2,factory.clients.size());
        assertEquals(items,factory.clients.get(0).items);
        assertEquals(items,factory.clients.get(1).items);
        assertNotSame(factory.clients.get(0),factory.clients.get(1));
        manager.close();
    }
    @Test public void collectionWaitsForAllItems(){
        final UdpRecordSenderTest.FakeChannel channel=new UdpRecordSenderTest.FakeChannel();
        final CollectionCycle cycle=new CollectionCycle(Arrays.asList("a","b"),new UdpRecordSender(channel,java.nio.charset.StandardCharsets.US_ASCII));
        cycle.onData(value("a")); assertTrue(channel.sent.isEmpty()); cycle.onData(value("b")); assertEquals(1,channel.sent.size());
    }
    @Test public void repeatedItemDropsIncompletePriorCycle(){
        final UdpRecordSenderTest.FakeChannel channel=new UdpRecordSenderTest.FakeChannel();
        final CollectionCycle cycle=new CollectionCycle(Arrays.asList("a","b"),new UdpRecordSender(channel,java.nio.charset.StandardCharsets.US_ASCII));
        cycle.onData(value("a"));cycle.onData(value("a"));cycle.onData(value("b"));assertEquals(1,channel.sent.size());
    }
    private OpcReadValue value(String id){return new OpcReadValue(id,1.0,192,Calendar.getInstance());}
    static class CapturingFactory implements OpcDaClientFactory{
        final List<Client>clients=new ArrayList<Client>();
        public OpcDaClient create(){Client c=new Client();clients.add(c);return c;}
    }
    static class Client implements OpcDaClient{
        List<String>items;boolean connected;
        public void connect(){connected=true;}public void disconnect(){connected=false;}public boolean isConnected(){return connected;}
        public List<String>browseItems(){return Collections.emptyList();}public int exportCatalog(){return 0;}
        public OpcReadValue readItem(String id){return null;}public void bindSyncRead(OpcDataCallback c){}
        public void bindSyncRead(List<String>ids,OpcDataCallback c){items=new ArrayList<String>(ids);}
        public void unbind(){}
    }
}
