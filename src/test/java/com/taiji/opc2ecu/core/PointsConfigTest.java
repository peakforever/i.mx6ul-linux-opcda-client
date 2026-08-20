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
        assertEquals(StandardCharsets.UTF_8,c.getMd5Charset());
    }
    @Test public void acceptsUtf8IgnoringCase() throws Exception { assertEquals(StandardCharsets.UTF_8,parse(valid("utf-8")).getMd5Charset()); }
    @Test public void acceptsGbkIgnoringCase() throws Exception { assertEquals("GBK",parse(valid("gbk")).getMd5Charset().name()); }
    @Test public void acceptsPortOne() throws Exception { assertEquals(1,parse(port(1)).getUdpPort()); }
    @Test public void acceptsPort65535() throws Exception { assertEquals(65535,parse(port(65535)).getUdpPort()); }
    @Test(expected=IllegalArgumentException.class) public void rejectsPortZero() throws Exception { parse(port(0)); }
    @Test(expected=IllegalArgumentException.class) public void rejectsPortAboveRange() throws Exception { parse(port(65536)); }
    @Test(expected=IllegalArgumentException.class) public void rejectsZeroPeriod() throws Exception { parse("{\"periodMillis\":0,\"udp\":{\"host\":\"x\",\"port\":1},\"items\":[\"a\"]}"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsEmptyItems() throws Exception { parse("{\"periodMillis\":1,\"udp\":{\"host\":\"x\",\"port\":1},\"items\":[]}"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsBlankItem() throws Exception { parse("{\"periodMillis\":1,\"udp\":{\"host\":\"x\",\"port\":1},\"items\":[\" \"]}"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsDuplicateItem() throws Exception { parse("{\"periodMillis\":1,\"udp\":{\"host\":\"x\",\"port\":1},\"items\":[\"a\",\"a\"]}"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsUnknownCharset() throws Exception { parse(valid("UTF-16")); }
    @Test(expected=UnsupportedOperationException.class) public void itemsAreImmutable() throws Exception { parse(valid("US-ASCII")).getItems().add("b"); }
    @Test public void parsesServerAndReconnectSections() throws Exception {
        final PointsConfig c = parse(v2(
                "\"progId\":\"Example.OPC.1\",\"socketTimeoutMillis\":45000,\"useNtlmV2\":false",
                ",\"reconnect\":{\"enabled\":false,\"initialDelayMillis\":2000,"
                        + "\"maxDelayMillis\":40000,\"maxAttempts\":7}"));
        assertTrue(c.hasServer());
        assertEquals("opc.example", c.getServerHost());
        assertEquals("EXAMPLE", c.getServerDomain());
        assertEquals("opcuser", c.getServerUser());
        assertEquals("test-secret", c.getServerPassword());
        assertEquals("Example.OPC.1", c.getServerProgId());
        assertEquals(45000, c.getSocketTimeoutMillis());
        assertFalse(c.isUseNtlmV2());
        assertFalse(c.isReconnectEnabled());
        assertEquals(2000L, c.getReconnectInitialDelayMillis());
        assertEquals(40000L, c.getReconnectMaxDelayMillis());
        assertEquals(7, c.getReconnectMaxAttempts());
    }
    @Test public void appliesServerAndReconnectDefaults() throws Exception {
        final PointsConfig c = parse(v2("\"clsid\":\"class-id\"", ""));
        assertEquals(30000, c.getSocketTimeoutMillis());
        assertTrue(c.isUseNtlmV2());
        assertTrue(c.isReconnectEnabled());
        assertEquals(1000L, c.getReconnectInitialDelayMillis());
        assertEquals(30000L, c.getReconnectMaxDelayMillis());
        assertEquals(0, c.getReconnectMaxAttempts());
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsMissingServerHost() throws Exception {
        parse(v2Server("\"user\":\"u\",\"password\":\"p\",\"progId\":\"id\""));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsMissingServerUser() throws Exception {
        parse(v2Server("\"host\":\"h\",\"password\":\"p\",\"progId\":\"id\""));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsMissingServerPassword() throws Exception {
        parse(v2Server("\"host\":\"h\",\"user\":\"u\",\"progId\":\"id\""));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsMissingProgIdAndClsid() throws Exception {
        parse(v2("", ""));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsReconnectMaxBelowInitial() throws Exception {
        parse(v2("\"progId\":\"id\"", ",\"reconnect\":{\"initialDelayMillis\":2000,\"maxDelayMillis\":1000}"));
    }
    private String valid(final String charset){return "{\"periodMillis\":1,\"udp\":{\"host\":\"x\",\"port\":1,\"md5Charset\":\""+charset+"\"},\"items\":[\"a\"]}";}
    private String port(final int port){return "{\"periodMillis\":1,\"udp\":{\"host\":\"x\",\"port\":"+port+"},\"items\":[\"a\"]}";}
    private String v2(final String serverExtra, final String rootExtra) {
        final String suffix = serverExtra.isEmpty() ? "" : "," + serverExtra;
        return v2Server("\"host\":\"opc.example\",\"domain\":\"EXAMPLE\","
                + "\"user\":\"opcuser\",\"password\":\"test-secret\"" + suffix, rootExtra);
    }
    private String v2Server(final String serverFields) { return v2Server(serverFields, ""); }
    private String v2Server(final String serverFields, final String rootExtra) {
        return "{\"server\":{" + serverFields + "},\"periodMillis\":1000,"
                + "\"udp\":{\"host\":\"127.0.0.1\",\"port\":5353},\"items\":[\"a\"]"
                + rootExtra + "}";
    }
}
