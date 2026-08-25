package com.opc2ecu.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Properties;

import org.junit.Test;

public class ProbeConfigTest {
    @Test
    public void parsesDefaults() {
        final ProbeConfig config = config(base(), "secret", ProbeMode.READ_ITEM);
        assertEquals(1000, config.getPeriodMillis());
        assertEquals(30000L, config.getReconnectMaxDelayMillis());
        assertTrue(config.isReconnectEnabled());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingRequiredHost() {
        final Properties properties = base();
        properties.remove("host");
        config(properties, "secret", ProbeMode.READ_ITEM);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidInteger() {
        final Properties properties = base();
        properties.setProperty("periodMillis", "one-second");
        config(properties, "secret", ProbeMode.READ_ITEM);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingPassword() {
        config(base(), " ", ProbeMode.READ_ITEM);
    }

    @Test
    public void listServersAllowsOpcFieldsToBeAbsent() {
        final Properties properties = base();
        properties.remove("progId");
        properties.remove("clsid");
        properties.remove("itemId");
        final ProbeConfig config = config(properties, "secret", ProbeMode.LIST_SERVERS);
        assertEquals("", config.getProgId());
        assertEquals("", config.getItemId());
    }

    @Test
    public void listItemsAllowsItemToBeAbsent() {
        final Properties properties = base();
        properties.remove("itemId");
        assertEquals("", config(properties, "secret", ProbeMode.LIST_ITEMS).getItemId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void readModeRequiresItem() {
        final Properties properties = base();
        properties.remove("itemId");
        config(properties, "secret", ProbeMode.READ_ITEM);
    }

    @Test
    public void zeroReconnectAttemptsMeansUnlimited() {
        final Properties properties = base();
        properties.setProperty("reconnect.maxAttempts", "0");
        assertEquals(0, config(properties, "secret", ProbeMode.READ_ITEM).getReconnectMaxAttempts());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsReconnectMaxBelowInitial() {
        final Properties properties = base();
        properties.setProperty("reconnect.initialDelayMillis", "2000");
        properties.setProperty("reconnect.maxDelayMillis", "1000");
        config(properties, "secret", ProbeMode.READ_ITEM);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidBoolean() {
        final Properties properties = base();
        properties.setProperty("reconnect.enabled", "sometimes");
        config(properties, "secret", ProbeMode.READ_ITEM);
    }

    private static ProbeConfig config(
            final Properties properties, final String password, final ProbeMode mode) {
        return ProbeConfig.fromProperties(properties, password, mode);
    }

    private static Properties base() {
        final Properties properties = new Properties();
        properties.setProperty("host", "192.0.2.1");
        properties.setProperty("domain", "EXAMPLE");
        properties.setProperty("user", "opcuser");
        properties.setProperty("progId", "Example.OPC.1");
        properties.setProperty("clsid", "00000000-0000-0000-0000-000000000000");
        properties.setProperty("itemId", "Group.Item");
        return properties;
    }
}
