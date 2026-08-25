package com.opc2ecu.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Immutable collection and UDP configuration loaded from points.json. */
public final class PointsConfig {
    private final boolean serverConfigured;
    private final String serverHost;
    private final String serverDomain;
    private final String serverUser;
    private final String serverPassword;
    private final String serverProgId;
    private final String serverClsid;
    private final int socketTimeoutMillis;
    private final boolean useNtlmV2;
    private final int periodMillis;
    private final String udpHost;
    private final int udpPort;
    private final Charset md5Charset;
    private final List<String> items;
    private final boolean reconnectEnabled;
    private final long reconnectInitialDelayMillis;
    private final long reconnectMaxDelayMillis;
    private final int reconnectMaxAttempts;

    private PointsConfig(
            final boolean serverConfigured, final String serverHost,
            final String serverDomain, final String serverUser, final String serverPassword,
            final String serverProgId, final String serverClsid,
            final int socketTimeoutMillis, final boolean useNtlmV2,
            final int periodMillis, final String udpHost, final int udpPort,
            final Charset md5Charset, final List<String> items,
            final boolean reconnectEnabled, final long reconnectInitialDelayMillis,
            final long reconnectMaxDelayMillis, final int reconnectMaxAttempts) {
        this.serverConfigured = serverConfigured;
        this.serverHost = serverHost;
        this.serverDomain = serverDomain;
        this.serverUser = serverUser;
        this.serverPassword = serverPassword;
        this.serverProgId = serverProgId;
        this.serverClsid = serverClsid;
        this.socketTimeoutMillis = socketTimeoutMillis;
        this.useNtlmV2 = useNtlmV2;
        this.periodMillis = periodMillis;
        this.udpHost = udpHost;
        this.udpPort = udpPort;
        this.md5Charset = md5Charset;
        this.items = Collections.unmodifiableList(new ArrayList<String>(items));
        this.reconnectEnabled = reconnectEnabled;
        this.reconnectInitialDelayMillis = reconnectInitialDelayMillis;
        this.reconnectMaxDelayMillis = reconnectMaxDelayMillis;
        this.reconnectMaxAttempts = reconnectMaxAttempts;
    }

    public static PointsConfig load(final String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("points.json path must not be empty");
        }
        return fromJson(new ObjectMapper().readTree(new File(path)));
    }

    static PointsConfig fromJson(final JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("points.json root must be an object");
        }
        final JsonNode server = root.get("server");
        final boolean serverConfigured = server != null;
        if (serverConfigured && !server.isObject()) {
            throw new IllegalArgumentException("server object is required when server is present");
        }
        final String serverHost;
        final String serverDomain;
        final String serverUser;
        final String serverPassword;
        final String serverProgId;
        final String serverClsid;
        final int socketTimeoutMillis;
        final boolean useNtlmV2;
        if (serverConfigured) {
            serverHost = requiredText(server, "host");
            serverDomain = optionalText(server, "domain", "");
            serverUser = requiredText(server, "user");
            serverPassword = requiredText(server, "password");
            serverProgId = optionalText(server, "progId", "");
            serverClsid = optionalText(server, "clsid", "");
            if (serverProgId.isEmpty() && serverClsid.isEmpty()) {
                throw new IllegalArgumentException("server.progId or server.clsid is required");
            }
            socketTimeoutMillis = optionalInt(server, "socketTimeoutMillis", 30000);
            if (socketTimeoutMillis <= 0) {
                throw new IllegalArgumentException("server.socketTimeoutMillis must be greater than zero");
            }
            useNtlmV2 = optionalBoolean(server, "useNtlmV2", true);
        } else {
            serverHost = "";
            serverDomain = "";
            serverUser = "";
            serverPassword = "";
            serverProgId = "";
            serverClsid = "";
            socketTimeoutMillis = 30000;
            useNtlmV2 = true;
        }
        final int period = requiredInt(root, "periodMillis");
        if (period <= 0) {
            throw new IllegalArgumentException("periodMillis must be greater than zero");
        }
        final JsonNode udp = root.get("udp");
        if (udp == null || !udp.isObject()) {
            throw new IllegalArgumentException("udp object is required");
        }
        final String host = requiredText(udp, "host");
        final int port = requiredInt(udp, "port");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("udp.port must be between 1 and 65535");
        }
        final String charsetName = optionalText(udp, "md5Charset", "UTF-8");
        final Charset charset;
        if ("UTF-8".equalsIgnoreCase(charsetName)) {
            charset = StandardCharsets.UTF_8;
        } else if ("US-ASCII".equalsIgnoreCase(charsetName)) {
            charset = StandardCharsets.US_ASCII;
        } else if ("GBK".equalsIgnoreCase(charsetName)) {
            charset = Charset.forName("GBK");
        } else {
            throw new IllegalArgumentException("udp.md5Charset must be UTF-8, US-ASCII, or GBK");
        }
        final JsonNode itemNodes = root.get("items");
        if (itemNodes == null || !itemNodes.isArray() || itemNodes.size() == 0) {
            throw new IllegalArgumentException("items must be a non-empty array");
        }
        final List<String> items = new ArrayList<String>();
        final Set<String> unique = new HashSet<String>();
        final Iterator<JsonNode> iterator = itemNodes.elements();
        while (iterator.hasNext()) {
            final JsonNode item = iterator.next();
            if (!item.isTextual() || item.asText().trim().isEmpty()) {
                throw new IllegalArgumentException("items must contain non-empty strings");
            }
            final String value = item.asText().trim();
            if (!unique.add(value)) {
                throw new IllegalArgumentException("items contains duplicate entry: " + value);
            }
            items.add(value);
        }
        final JsonNode reconnect = root.get("reconnect");
        if (reconnect != null && !reconnect.isObject()) {
            throw new IllegalArgumentException("reconnect must be an object");
        }
        final boolean reconnectEnabled = reconnect == null
                ? true : optionalBoolean(reconnect, "enabled", true);
        final long initialDelay = reconnect == null
                ? 1000L : optionalLong(reconnect, "initialDelayMillis", 1000L);
        final long maxDelay = reconnect == null
                ? 30000L : optionalLong(reconnect, "maxDelayMillis", 30000L);
        final int maxAttempts = reconnect == null
                ? 0 : optionalInt(reconnect, "maxAttempts", 0);
        if (initialDelay <= 0L) {
            throw new IllegalArgumentException("reconnect.initialDelayMillis must be greater than zero");
        }
        if (maxDelay <= 0L) {
            throw new IllegalArgumentException("reconnect.maxDelayMillis must be greater than zero");
        }
        if (maxDelay < initialDelay) {
            throw new IllegalArgumentException(
                    "reconnect.maxDelayMillis must be greater than or equal to reconnect.initialDelayMillis");
        }
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("reconnect.maxAttempts must be zero or greater");
        }
        return new PointsConfig(
                serverConfigured, serverHost, serverDomain, serverUser, serverPassword,
                serverProgId, serverClsid, socketTimeoutMillis, useNtlmV2,
                period, host, port, charset, items, reconnectEnabled,
                initialDelay, maxDelay, maxAttempts);
    }

    private static int requiredInt(final JsonNode parent, final String name) {
        final JsonNode value = parent.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return value.intValue();
    }

    private static String requiredText(final JsonNode parent, final String name) {
        final JsonNode value = parent.get(name);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be a non-empty string");
        }
        return value.asText().trim();
    }

    private static String optionalText(
            final JsonNode parent, final String name, final String defaultValue) {
        return parent.has(name) ? requiredText(parent, name) : defaultValue;
    }

    private static int optionalInt(
            final JsonNode parent, final String name, final int defaultValue) {
        return parent.has(name) ? requiredInt(parent, name) : defaultValue;
    }

    private static long optionalLong(
            final JsonNode parent, final String name, final long defaultValue) {
        final JsonNode value = parent.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return value.longValue();
    }

    private static boolean optionalBoolean(
            final JsonNode parent, final String name, final boolean defaultValue) {
        final JsonNode value = parent.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return value.booleanValue();
    }

    public boolean hasServer() { return serverConfigured; }
    public String getServerHost() { return serverHost; }
    public String getServerDomain() { return serverDomain; }
    public String getServerUser() { return serverUser; }
    public String getServerPassword() { return serverPassword; }
    public String getServerProgId() { return serverProgId; }
    public String getServerClsid() { return serverClsid; }
    public int getSocketTimeoutMillis() { return socketTimeoutMillis; }
    public boolean isUseNtlmV2() { return useNtlmV2; }
    public int getPeriodMillis() { return periodMillis; }
    public String getUdpHost() { return udpHost; }
    public int getUdpPort() { return udpPort; }
    public Charset getMd5Charset() { return md5Charset; }
    public List<String> getItems() { return items; }
    public boolean isReconnectEnabled() { return reconnectEnabled; }
    public long getReconnectInitialDelayMillis() { return reconnectInitialDelayMillis; }
    public long getReconnectMaxDelayMillis() { return reconnectMaxDelayMillis; }
    public int getReconnectMaxAttempts() { return reconnectMaxAttempts; }
}
