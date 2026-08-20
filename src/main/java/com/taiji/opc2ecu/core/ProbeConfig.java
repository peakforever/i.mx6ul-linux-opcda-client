package com.taiji.opc2ecu.core;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Immutable, validated runtime configuration. */
public final class ProbeConfig {
    private final String host;
    private final String domain;
    private final String user;
    private final String password;
    private final String progId;
    private final String clsid;
    private final String itemId;
    private final int periodMillis;
    private final int sampleCount;
    private final int readTimeoutSeconds;
    private final int socketTimeoutMillis;
    private final boolean useNtlmV2;
    private final boolean reconnectEnabled;
    private final long reconnectInitialDelayMillis;
    private final long reconnectMaxDelayMillis;
    private final int reconnectMaxAttempts;

    private ProbeConfig(final Properties properties, final String password, final ProbeMode mode) {
        host = required(properties, "host");
        domain = properties.getProperty("domain", "").trim();
        user = required(properties, "user");
        this.password = requireSecret(password);
        if (mode == ProbeMode.LIST_SERVERS) {
            progId = optional(properties, "progId");
            clsid = optional(properties, "clsid");
            itemId = optional(properties, "itemId");
        } else if (mode == ProbeMode.LIST_ITEMS || mode == ProbeMode.EXPORT_CATALOG) {
            progId = required(properties, "progId");
            clsid = required(properties, "clsid");
            itemId = optional(properties, "itemId");
        } else if (mode == ProbeMode.READ_ITEM) {
            progId = required(properties, "progId");
            clsid = required(properties, "clsid");
            itemId = required(properties, "itemId");
        } else if (mode == ProbeMode.COLLECT || mode == ProbeMode.PRECHECK_POINTS) {
            progId = optional(properties, "progId");
            clsid = optional(properties, "clsid");
            if (progId.isEmpty() && clsid.isEmpty()) {
                throw new IllegalArgumentException("progId or clsid is required");
            }
            itemId = optional(properties, "itemId");
        } else {
            progId = required(properties, "progId");
            clsid = required(properties, "clsid");
            itemId = optional(properties, "itemId");
        }
        periodMillis = positiveInt(properties, "periodMillis", 1000);
        sampleCount = positiveInt(properties, "sampleCount", 10);
        readTimeoutSeconds = positiveInt(properties, "readTimeoutSeconds", 30);
        socketTimeoutMillis = positiveInt(properties, "socketTimeoutMillis", 30000);
        useNtlmV2 = booleanValue(properties, "useNtlmV2", true);
        reconnectEnabled = booleanValue(properties, "reconnect.enabled", true);
        reconnectInitialDelayMillis = positiveLong(properties, "reconnect.initialDelayMillis", 1000L);
        reconnectMaxDelayMillis = positiveLong(properties, "reconnect.maxDelayMillis", 30000L);
        reconnectMaxAttempts = nonNegativeInt(properties, "reconnect.maxAttempts", 0);
        if (reconnectMaxDelayMillis < reconnectInitialDelayMillis) {
            throw new IllegalArgumentException(
                    "reconnect.maxDelayMillis must be greater than or equal to reconnect.initialDelayMillis");
        }
    }

    public static ProbeConfig load(
            final String path,
            final String password,
            final ProbeMode mode) throws IOException {
        final Properties properties = new Properties();
        try (InputStream input = new FileInputStream(path)) {
            properties.load(input);
        }
        return fromProperties(properties, password, mode);
    }

    public static ProbeConfig fromProperties(
            final Properties properties,
            final String password,
            final ProbeMode mode) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        return new ProbeConfig(properties, password, mode);
    }

    public static ProbeConfig fromPointsConfig(final PointsConfig points) {
        if (points == null || !points.hasServer()) {
            throw new IllegalArgumentException("points.json server section is required");
        }
        final Properties properties = new Properties();
        properties.setProperty("host", points.getServerHost());
        properties.setProperty("domain", points.getServerDomain());
        properties.setProperty("user", points.getServerUser());
        properties.setProperty("progId", points.getServerProgId());
        properties.setProperty("clsid", points.getServerClsid());
        properties.setProperty("periodMillis", Integer.toString(points.getPeriodMillis()));
        properties.setProperty("socketTimeoutMillis", Integer.toString(points.getSocketTimeoutMillis()));
        properties.setProperty("useNtlmV2", Boolean.toString(points.isUseNtlmV2()));
        properties.setProperty("reconnect.enabled", Boolean.toString(points.isReconnectEnabled()));
        properties.setProperty("reconnect.initialDelayMillis",
                Long.toString(points.getReconnectInitialDelayMillis()));
        properties.setProperty("reconnect.maxDelayMillis",
                Long.toString(points.getReconnectMaxDelayMillis()));
        properties.setProperty("reconnect.maxAttempts",
                Integer.toString(points.getReconnectMaxAttempts()));
        return new ProbeConfig(properties, points.getServerPassword(), ProbeMode.COLLECT);
    }

    private static String required(final Properties properties, final String key) {
        final String value = optional(properties, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Required property is missing: " + key);
        }
        return value;
    }

    private static String optional(final Properties properties, final String key) {
        return properties.getProperty(key, "").trim();
    }

    private static String requireSecret(final String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Environment variable OPC_PASSWORD is required; export it before starting the client");
        }
        return value;
    }

    private static int positiveInt(
            final Properties properties, final String key, final int defaultValue) {
        final int parsed = intValue(properties, key, defaultValue);
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero");
        }
        return parsed;
    }

    private static int nonNegativeInt(
            final Properties properties, final String key, final int defaultValue) {
        final int parsed = intValue(properties, key, defaultValue);
        if (parsed < 0) {
            throw new IllegalArgumentException(key + " must be zero or greater");
        }
        return parsed;
    }

    private static int intValue(
            final Properties properties, final String key, final int defaultValue) {
        final String value = properties.getProperty(key);
        try {
            return value == null ? defaultValue : Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a valid integer", e);
        }
    }

    private static long positiveLong(
            final Properties properties, final String key, final long defaultValue) {
        final String value = properties.getProperty(key);
        final long parsed;
        try {
            parsed = value == null ? defaultValue : Long.parseLong(value.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a valid integer", e);
        }
        if (parsed <= 0L) {
            throw new IllegalArgumentException(key + " must be greater than zero");
        }
        return parsed;
    }

    private static boolean booleanValue(
            final Properties properties, final String key, final boolean defaultValue) {
        final String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false");
    }

    public String getHost() { return host; }
    public String getDomain() { return domain; }
    public String getUser() { return user; }
    public String getPassword() { return password; }
    public String getProgId() { return progId; }
    public String getClsid() { return clsid; }
    public String getItemId() { return itemId; }
    public int getPeriodMillis() { return periodMillis; }
    public int getSampleCount() { return sampleCount; }
    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public int getSocketTimeoutMillis() { return socketTimeoutMillis; }
    public boolean isUseNtlmV2() { return useNtlmV2; }
    public boolean isReconnectEnabled() { return reconnectEnabled; }
    public long getReconnectInitialDelayMillis() { return reconnectInitialDelayMillis; }
    public long getReconnectMaxDelayMillis() { return reconnectMaxDelayMillis; }
    public int getReconnectMaxAttempts() { return reconnectMaxAttempts; }
}
