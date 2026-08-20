package com.taiji.opc2ecu.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PointsConfigTest {
    private PointsConfig parse(final String json) throws Exception {
        return PointsConfig.fromJson(new ObjectMapper().readTree(json));
    }
    @Test public void loadsValidSchemaAndDefaultsCharset() throws Exception {
        final PointsConfig c=parse("{\"periodMillis\":1000,\"udp\":{\"host\":\"127.0.0.1\",\"port\":5353},\"items\":[\"a\",\"b\"]}");
        assertEquals(1000,c.getPeriodMillis()); assertEquals(2,c.getItems().size());
        assertEquals(StandardCharsets.US_ASCII,c.getMd5Charset());
    }
    @Test public void acceptsGbkIgnoringCase() throws Exception { assertEquals("GBK",parse(valid("gbk")).getMd5Charset().name()); }
    @Test public void acceptsPortOne() throws Exception { assertEquals(1,parse(port(1)).getUdpPort()); }
    @Test public void acceptsPort65535() throws Exception { assertEquals(65535,parse(port(65535)).getUdpPort()); }
    @Test(expected=IllegalArgumentException.class) public void rejectsPortZero() throws Exception { parse(port(0)); }
    @Test(expected=IllegalArgumentException.class) public void rejectsPortAboveRange() throws Exception { parse(port(65536)); }
    @Test(expected=IllegalArgumentException.class) public void rejectsZeroPeriod() throws Exception { parse("{\"periodMillis\":0,\"udp\":{\"host\":\"x\",\"port\":1},\"items\":[\"a\"]}"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsEmptyItems() throws Exception { parse("{\"periodMillis\":1,\"udp\":{\"host\":\"x\",\"port\":1},\"items\":[]}"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsBlankItem() throws Exception { parse("{\"periodMillis\":1,\"udp\":{\"host\":\"x\",\"port\":1},\"items\":[\" \"]}"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsDuplicateItem() throws Exception { parse("{\"periodMillis\":1,\"udp\":{\"host\":\"x\",\"port\":1},\"items\":[\"a\",\"a\"]}"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsUnknownCharset() throws Exception { parse(valid("UTF-8")); }
    @Test(expected=UnsupportedOperationException.class) public void itemsAreImmutable() throws Exception { parse(valid("US-ASCII")).getItems().add("b"); }
    private String valid(final String charset){return "{\"periodMillis\":1,\"udp\":{\"host\":\"x\",\"port\":1,\"md5Charset\":\""+charset+"\"},\"items\":[\"a\"]}";}
    private String port(final int port){return "{\"periodMillis\":1,\"udp\":{\"host\":\"x\",\"port\":"+port+"},\"items\":[\"a\"]}";}
}
